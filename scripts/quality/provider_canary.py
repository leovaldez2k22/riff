#!/usr/bin/env python3
"""Provider canary (Q10A): is YouTube Music still answering the way the pinned integration expects?

    python scripts/quality/provider_canary.py --run-jvm --trigger manual
    python scripts/quality/provider_canary.py --run-jvm --trigger schedule       at most one a day
    python scripts/quality/provider_canary.py --run-jvm --stream-log run.log     add the device's stream phase
    python scripts/quality/provider_canary.py --status                           show the last result again

No account, no credential and no secret. Signed out, it asks for one search and one collection page on the
JVM (ProviderCanaryLive). The stream phase needs the Android solver, so it runs on a device or emulator
(ProviderCanaryDeviceTest) and its one status line is read here from the instrumentation log.

Each phase comes back as one of: Healthy, ParserBreak, ResolutionBreak, Blocked, Outage, HarnessFault.
A block or an outage says nothing about the app and is never reported as a break. A break is flagged only
when two results in a row that could tell (Healthy or a break) are breaks.

What is stored (history.jsonl and latest.md in the state folder): time to the minute, trigger, lanes, the
pinned library version, and per phase a class, a reason word, milliseconds and a request count. No response
body, address, header or id. Anything else in the harness output is refused, not stored.

A canary result never satisfies the Q10 live gate or any release gate. It edits no code, bumps no
dependency and publishes nothing.

Exit: 0 recorded (or skipped by the daily limit), 1 confirmed break, 2 the canary itself is at fault.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / "artifacts" / "quality" / "canary"
RUNBOOK = "docs/quality/provider-compatibility.md#when-the-canary-reports-a-break"

CLASSES = ("Healthy", "ParserBreak", "ResolutionBreak", "Blocked", "Outage", "HarnessFault")
BREAKS = ("ParserBreak", "ResolutionBreak")
CONCLUSIVE = ("Healthy",) + BREAKS
PHASES = ("search", "collection", "stream")
REASONS = frozenset((
    "ok", "empty", "unreadable", "rejected", "not_found", "refused", "rate_limited", "sign_in_required", "server_error",
    "unreachable", "timeout", "targets_stale", "harness_exception", "no_stream", "stream_refused", "no_bytes",
    "all_targets_unavailable", "solver_unavailable", "signed_in", "no_output", "unsanitary_output", "build_failed"))
PHASE_KEYS = {"phase", "classification", "reason", "millis", "requests"}
MIN_SCHEDULE_GAP = timedelta(hours=20)
# Nothing of these may ever be in a stored record or a summary.
FORBIDDEN = re.compile(r"https?://(?!example\.invalid)|googlevideo|videoplayback|[?&](sig|signature|n|pot|key|expire)=|SAPISID|Cookie|Authorization|[A-Za-z0-9_-]{40,}", re.I)


class Unsanitary(ValueError):
    pass


def sanitize_phase(raw):
    """One phase exactly as allowed, or Unsanitary. Unknown keys and values are refused, not dropped."""
    if not isinstance(raw, dict) or set(raw) != PHASE_KEYS:
        raise Unsanitary("unexpected fields")
    if raw["phase"] not in PHASES or raw["classification"] not in CLASSES or raw["reason"] not in REASONS:
        raise Unsanitary("unexpected value")
    for name, limit in (("millis", 600_000), ("requests", 50)):
        if type(raw[name]) is not int or not 0 <= raw[name] <= limit:
            raise Unsanitary("unexpected number")
    return {key: raw[key] for key in ("phase", "classification", "reason", "millis", "requests")}


def sanitize_phases(text, allowed):
    try:
        data = json.loads(text)
    except (TypeError, ValueError) as error:
        raise Unsanitary("not JSON") from error
    if not isinstance(data, dict) or set(data) != {"schema", "phases"} or data["schema"] != 1 or not isinstance(data["phases"], list):
        raise Unsanitary("unexpected shape")
    phases = [sanitize_phase(item) for item in data["phases"]]
    names = [phase["phase"] for phase in phases]
    if len(set(names)) != len(names) or any(name not in allowed for name in names):
        raise Unsanitary("unexpected phase")
    return phases


def fault(phase, reason):
    return {"phase": phase, "classification": "HarnessFault", "reason": reason, "millis": 0, "requests": 0}


def stream_phase(log_text):
    """The stream phase from an instrumentation log: exactly one riffCanary status line."""
    lines = re.findall(r"^INSTRUMENTATION_STATUS: riffCanary=(.*)$", log_text, re.M)
    if len(lines) != 1:
        return fault("stream", "no_output")
    try:
        phase = sanitize_phase(json.loads(lines[0].strip()))
    except (ValueError, Unsanitary):
        return fault("stream", "unsanitary_output")
    return phase if phase["phase"] == "stream" else fault("stream", "unsanitary_output")


def overall(phases):
    """One class for the run. Anything that cannot tell hides a break seen in the same run: a blocked or
    unreachable provider can make another phase look broken, so the run as a whole is not called a break."""
    seen = {phase["classification"] for phase in phases}
    for name in ("HarnessFault", "Outage", "Blocked", "ParserBreak", "ResolutionBreak"):
        if name in seen:
            return name
    return "Healthy" if seen else "HarnessFault"


def confirmed(history):
    """True when the newest result is a break and so was the conclusive result before it. Results that could
    not tell (Blocked, Outage, HarnessFault) between the two neither count nor reset."""
    if not history or history[-1]["classification"] not in BREAKS:
        return False
    for record in reversed(history[:-1]):
        if record["classification"] in CONCLUSIVE:
            return record["classification"] in BREAKS
    return False


def due(history, trigger, moment):
    """A scheduled run is skipped when a scheduled result is less than 20 hours old. A manual run always runs."""
    if trigger != "schedule":
        return True
    scheduled = [record for record in history if record.get("trigger") == "schedule"]
    return not scheduled or moment - datetime.fromisoformat(scheduled[-1]["utc"]) >= MIN_SCHEDULE_GAP


def dependency(root=ROOT):
    text = (root / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
    match = re.search(r'^innertubex\s*=.*version\s*=\s*"([0-9][0-9A-Za-z.\-]{0,20})"', text, re.M)
    return "innertubex " + (match.group(1) if match else "unknown")


def load_history(state):
    path = Path(state) / "history.jsonl"
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def record(phases, trigger, stream_lane, moment, root=ROOT):
    return {"schema": 1, "utc": moment.replace(second=0, microsecond=0).isoformat(), "trigger": trigger,
            "catalogLane": "jvm" if any(phase["phase"] != "stream" for phase in phases) else "none",
            "streamLane": stream_lane, "dependency": dependency(root), "classification": overall(phases),
            "phases": phases, "gate": "none"}


HEADLINES = {
    "Healthy": "Healthy. The provider answered every phase that ran as the pinned integration expects.",
    "Blocked": "Blocked or rate limited. The provider refused this runner. This says nothing about the app.",
    "Outage": "Outage. The provider could not be reached or answered with a server error. This says nothing about the app.",
    "HarnessFault": "Harness fault. The canary itself failed; the provider was not judged. Repair the canary.",
}


def summary(history):
    latest = history[-1]
    name = latest["classification"]
    flagged = confirmed(history)
    if flagged:
        head = "CONFIRMED BREAK (%s, two results in a row). Follow %s" % (name, RUNBOOK)
    elif name in BREAKS:
        head = "%s seen once. Not confirmed: one more result is needed before this is treated as a break." % name
    else:
        head = HEADLINES[name]
    lines = ["## Provider canary", "", head, "",
             "%s, trigger %s, %s, catalog lane %s, stream lane %s." % (
                 latest["utc"], latest["trigger"], latest["dependency"], latest["catalogLane"], latest["streamLane"]), "",
             "| Phase | Class | Reason | ms | Requests |", "|---|---|---|---|---|"]
    lines += ["| %(phase)s | %(classification)s | %(reason)s | %(millis)d | %(requests)d |" % phase for phase in latest["phases"]]
    ran = {phase["phase"] for phase in latest["phases"]}
    lines += ["| %s | not run | | | |" % phase for phase in PHASES if phase not in ran]
    lines += ["", "This result satisfies no release gate and is not the Q10 live check."]
    text = "\n".join(lines) + "\n"
    if FORBIDDEN.search(text.replace(RUNBOOK, "")):
        raise Unsanitary("summary")
    return text, flagged


def store(state, entry):
    """Appends one record. The line is scanned once more before it is written."""
    line = json.dumps(entry, sort_keys=True)
    if FORBIDDEN.search(line):
        raise Unsanitary("record")
    state = Path(state)
    state.mkdir(parents=True, exist_ok=True)
    with (state / "history.jsonl").open("a", encoding="utf-8", newline="\n") as stream:
        stream.write(line + "\n")


def run_jvm(root=ROOT, offline=False):
    """Runs the two catalog phases through Gradle and returns what the harness wrote, or None."""
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    with tempfile.TemporaryDirectory() as folder:
        out = Path(folder) / "phases.json"
        command = [str(wrapper)] + (["--offline"] if offline else []) + [
            ":app:testDebugUnitTest", "--tests", "com.musicglass.personal.ProviderCanaryLive", "-PriffCanaryOut=" + str(out)]
        done = subprocess.run(command, cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        if not out.exists():
            return None, done.returncode
        return out.read_text(encoding="utf-8"), done.returncode


def finish(history, state):
    text, flagged = summary(history)
    (Path(state) / "latest.md").write_text(text, encoding="utf-8", newline="\n")
    print(text)
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as stream:
            stream.write(text)
    name = history[-1]["classification"]
    if flagged:
        print("::error title=Provider canary::Confirmed %s. See %s" % (name, RUNBOOK))
        return 1
    if name == "HarnessFault":
        print("::warning title=Provider canary::The canary itself failed; the provider was not judged.")
        return 2
    if name in BREAKS:
        print("::warning title=Provider canary::%s seen once; not confirmed." % name)
    return 0


def main(argv=None, moment=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--run-jvm", action="store_true", help="run the catalog phases now (sends two or three requests)")
    parser.add_argument("--phases", help="a phases file the harness already wrote, in place of --run-jvm")
    parser.add_argument("--stream-log", help="instrumentation log of ProviderCanaryDeviceTest")
    parser.add_argument("--stream-lane", choices=("emulator", "device"), default="emulator")
    parser.add_argument("--trigger", choices=("manual", "schedule"), default="manual")
    parser.add_argument("--state", default=str(STATE))
    parser.add_argument("--offline", action="store_true", help="pass --offline to Gradle (dependencies only; the canary still needs the network)")
    parser.add_argument("--status", action="store_true")
    args = parser.parse_args(argv)
    moment = moment or datetime.now(timezone.utc)
    history = load_history(args.state)

    if args.status:
        if not history:
            print("No canary result is stored.")
            return 0
        return finish(history, args.state)
    if not (args.run_jvm or args.phases or args.stream_log):
        parser.error("nothing to do: give --run-jvm, --phases, --stream-log or --status")
    if not due(history, args.trigger, moment):
        print("Skipped: a scheduled canary result is less than 20 hours old. Nothing was sent.")
        return 0

    phases = []
    if args.run_jvm or args.phases:
        if args.phases:
            text = Path(args.phases).read_text(encoding="utf-8") if Path(args.phases).exists() else None
        else:
            text, _ = run_jvm(ROOT, args.offline)
        if text is None:
            phases.append(fault("search", "no_output" if args.phases else "build_failed"))
        else:
            try:
                phases += sanitize_phases(text, ("search", "collection"))
            except Unsanitary:
                phases.append(fault("search", "unsanitary_output"))
    if args.stream_log:
        log = Path(args.stream_log)
        phases.append(stream_phase(log.read_text(encoding="utf-8", errors="replace")) if log.exists() else fault("stream", "no_output"))

    entry = record(phases, args.trigger, args.stream_lane if args.stream_log else "none", moment)
    store(args.state, entry)
    return finish(history + [entry], args.state)


if __name__ == "__main__":
    sys.exit(main())
