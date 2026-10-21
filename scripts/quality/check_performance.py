"""Q14: hold benchmark results and the release APK to the performance budgets.

  check_performance.py apk --apk app-release.apk [--profile app/src/release/generated/baselineProfiles/baseline-prof.txt]
  check_performance.py summary --tier R|L --refresh-hz 60 <benchmarkData.json>...
  check_performance.py adopt --collected <pulled profile> [--out app/src/release/generated/baselineProfiles/baseline-prof.txt]

`summary` reads the JSON that Macrobenchmark writes for :benchmark and prints, for every budgeted measurement,
the number of samples, p50, p95, the budget and a verdict. A verdict is PASS or FAIL only when the samples are
evidence: taken on a physical device and at least as many as the protocol asks for. Otherwise the row says why
it is not, and the exit status is 3. Nothing here turns an emulator run into a pass.

Exit status: 0 every budget met, 2 a budget failed or the APK check failed, 3 results missing or not evidence.
"""
import argparse
import json
import math
import sys
import zipfile
from pathlib import Path

MIB = 1024 * 1024
APK_LIMIT_BYTES = 40 * MIB
# Things only the benchmark build types hold (app/src/benchmarkFeed).
FEED_MARKERS = ("BenchmarkFeed", "benchmark-feed", "benchmark_network_security_config")
FEED_CLASSES = ("BenchmarkFeed", "ToneServer")
FEED_ASSETS = ("assets/home.json", "assets/playlist-owned.json", "assets/watch-next.json")
# The journeys the profile was collected over must have left rules for these.
PROFILE_CLASSES = ("Lcom/musicglass/personal/MainActivity;", "Lcom/musicglass/personal/RiffAppKt;",
                   "Lcom/musicglass/personal/HomeFeedKt;", "Lcom/musicglass/personal/NowPlayingKt;",
                   "Lcom/musicglass/personal/InlineQueueKt;", "Lcom/musicglass/personal/ImmersiveCollectionKt;")
PROFILE_MIN_RULES = 500

ACTIONS = ("player_openMs", "queue_openMs", "queue_closeMs", "play_toggleMs", "skipMs", "player_closeMs")

# (label, benchmark method, metric, protocol samples, budget for tier R, budget for tier L)
BUDGETS = (
    ("Cold start to usable shell, p95 ms", "coldStartWithBaselineProfile", "timeToFullDisplayMs", 20, 1200, 2000),
    *((f"Action {name[:-2]}, p95 ms", "playerAndQueueActions", name, 30, 150, 250) for name in ACTIONS),
    ("2,000 entry queue, p95 ms", "queueOfTwoThousand", "queue_prepareMs", 10, 2000, 4000),
    ("Local bundle first audio, p95 ms", "firstAudioLocalBundle", "first_audioMs", 20, 500, 800),
    ("Controlled online first audio, p95 ms", "firstAudioOnline", "first_audioMs", 20, 3000, 5000),
)
FRAME_JOURNEYS = ("homeScroll", "collectionScroll", "playerAndQueue")
FRAME_LOOPS = 5
# Reported beside the budgets, never judged: what the Baseline Profile and the phases are worth.
INFORMATION = (
    ("Cold start without the profile", "coldStartWithoutProfile", "timeToFullDisplayMs"),
    ("Cold start first frame, with the profile", "coldStartWithBaselineProfile", "timeToInitialDisplayMs"),
    ("Cold start first frame, without the profile", "coldStartWithoutProfile", "timeToInitialDisplayMs"),
    ("Queue: encode, off the main thread", "queueOfTwoThousand", "queue_io_encodeMs"),
    ("Queue: decode, off the main thread", "queueOfTwoThousand", "queue_io_decodeMs"),
    ("Queue: on the main thread", "queueOfTwoThousand", "queue_mainMs"),
    ("First audio, loopback with no limit", "firstAudioUnthrottled", "first_audioMs"),
    ("Home scroll missed frames without the profile, %", "homeScrollWithoutProfile", "missedDeadlinePercent"),
)


class PerformanceError(Exception):
    pass


def percentile(samples, fraction):
    """Nearest rank: the smallest sample with at least `fraction` of the samples at or below it."""
    if not samples:
        raise PerformanceError("No samples")
    ordered = sorted(samples)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def flatten(runs):
    out = []
    for run in runs or []:
        out.extend(run if isinstance(run, list) else [run])
    return [float(value) for value in out]


def is_emulator(build):
    text = " ".join(str(build.get(key, "")) for key in ("model", "device", "fingerprint", "brand")).lower()
    return any(mark in text for mark in ("sdk_gphone", "emulator", "generic", "goldfish", "ranchu"))


def load(paths):
    """{method: {metric: [samples]}} and the device descriptions, from Macrobenchmark's benchmarkData.json files."""
    results, devices = {}, []
    for path in paths:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        build = data.get("context", {}).get("build", {})
        devices.append({"model": build.get("model", "?"), "sdk": build.get("version", {}).get("sdk", "?"),
                        "fingerprint": build.get("fingerprint", "?"), "emulator": is_emulator(build),
                        "memTotalBytes": data.get("context", {}).get("memTotalBytes")})
        for bench in data.get("benchmarks", []):
            metrics = results.setdefault(bench["name"], {})
            for group in ("metrics", "sampledMetrics"):
                for name, value in (bench.get(group) or {}).items():
                    metrics.setdefault(name, []).extend(flatten(value.get("runs")))
    return results, devices


def judge(samples, needed, budget, evidence):
    if not samples:
        return "MISSING"
    if not evidence:
        return "NOT EVIDENCE (emulator)"
    if len(samples) < needed:
        return f"INSUFFICIENT ({len(samples)} of {needed})"
    return "PASS" if percentile(samples, .95) <= budget else "FAIL"


def missed_frames(metrics):
    """Missed-deadline frames over all loops, in percent, weighted by each loop's frame count."""
    counts, percents = metrics.get("deadlineFrameCount", []), metrics.get("missedDeadlinePercent", [])
    if not counts or len(counts) != len(percents) or sum(counts) == 0:
        return None, 0
    return sum(c * p for c, p in zip(counts, percents)) / sum(counts), len(counts)


def summarize(results, devices, tier, refresh_hz):
    evidence = bool(devices) and not any(device["emulator"] for device in devices)
    column = 4 if tier == "R" else 5
    rows = []
    for budget in BUDGETS:
        label, method, metric, needed = budget[:4]
        samples = results.get(method, {}).get(metric, [])
        rows.append((label, len(samples), percentile(samples, .5) if samples else None,
                     percentile(samples, .95) if samples else None, budget[column],
                     judge(samples, needed, budget[column], evidence)))
    # 60 Hz: 2% on R and 5% on L. A higher refresh rate has one budget, 3%.
    frame_budget = 3.0 if refresh_hz > 60 else (2.0 if tier == "R" else 5.0)
    for journey in FRAME_JOURNEYS:
        percent, loops = missed_frames(results.get(journey, {}))
        if percent is None:
            verdict = "MISSING"
        elif not evidence:
            verdict = "NOT EVIDENCE (emulator)"
        elif loops < FRAME_LOOPS:
            verdict = f"INSUFFICIENT ({loops} of {FRAME_LOOPS})"
        else:
            verdict = "PASS" if percent <= frame_budget else "FAIL"
        rows.append((f"Missed-deadline frames, {journey}, % at {refresh_hz:g} Hz", loops, None, percent, frame_budget, verdict))
    information = []
    for label, method, metric in INFORMATION:
        samples = results.get(method, {}).get(metric, [])
        if samples:
            information.append((label, len(samples), percentile(samples, .5), percentile(samples, .95)))
    return rows, information


def status(rows):
    verdicts = [row[5] for row in rows]
    if any(verdict == "FAIL" for verdict in verdicts):
        return 2
    return 0 if all(verdict == "PASS" for verdict in verdicts) else 3


def number(value):
    return "-" if value is None else f"{value:.1f}"


def render(rows, information, devices, tier):
    lines = [f"Tier {tier}"]
    for device in devices:
        memory = f"{device['memTotalBytes'] / MIB / 1024:.1f} GiB" if device["memTotalBytes"] else "?"
        lines.append(f"Device: {device['model']} API {device['sdk']} RAM {memory}"
                     + (" EMULATOR - timings are not evidence" if device["emulator"] else ""))
    lines.append("")
    lines.append(f"{'Measurement':<58}{'n':>4}{'p50':>10}{'p95':>10}{'budget':>9}  verdict")
    for label, count, p50, p95, budget, verdict in rows:
        lines.append(f"{label:<58}{count:>4}{number(p50):>10}{number(p95):>10}{budget:>9g}  {verdict}")
    if information:
        lines += ["", f"{'Not budgeted':<58}{'n':>4}{'p50':>10}{'p95':>10}"]
        lines += [f"{label:<58}{count:>4}{number(p50):>10}{number(p95):>10}" for label, count, p50, p95 in information]
    return "\n".join(lines)


def check_apk(apk, profile=None):
    """The facts about a release APK that Q14 budgets, as (name, ok, detail) rows."""
    apk = Path(apk)
    size = apk.stat().st_size
    rows = [("size at most 40 MiB", size <= APK_LIMIT_BYTES, f"{size} bytes = {size / MIB:.2f} MiB")]
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        packaged = {name: archive.getinfo(name).file_size for name in ("assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm") if name in names}
        rows.append(("Baseline Profile packaged", len(packaged) == 2 and all(packaged.values()),
                     ", ".join(f"{name} {size} bytes" for name, size in packaged.items()) or "assets/dexopt/baseline.prof is missing"))
        found = sorted(name for name in FEED_ASSETS if name in names)
        for name in sorted(names):
            if name.endswith(".dex") or name == "AndroidManifest.xml":
                blob = archive.read(name)
                found += [f"{marker} in {name}" for marker in FEED_MARKERS
                          if marker.encode() in blob or marker.encode("utf-16-le") in blob]
        rows.append(("benchmark fixture feed absent", not found, ", ".join(found) or "no feed class, provider, config or fixture page"))
    if profile is not None:
        rows += check_profile_source(profile)
    return rows


def check_profile_source(profile):
    path = Path(profile)
    if not path.is_file():
        return [("Baseline Profile committed as source", False, f"{profile} is missing")]
    rules = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    missing = [name for name in PROFILE_CLASSES if not any(name in rule for rule in rules)]
    return [("Baseline Profile committed as source", len(rules) >= PROFILE_MIN_RULES, f"{len(rules)} rules in {path.as_posix()}"),
            ("profile covers shell, collection, player and queue", not missing, "missing " + ", ".join(missing) if missing else "all journey classes have rules")]


def adopt_profile(collected, out):
    """
    Write the profile a device run collected as the committed source, without the rules for the fixture feed's
    own classes (they are not in a release). This is what :app:generateReleaseBaselineProfile does after its
    device run; it is here for when the run was started with `am instrument` and the file pulled by hand.
    """
    rules = [line for line in Path(collected).read_text(encoding="utf-8").splitlines() if line.strip()]
    kept = [rule for rule in rules if not any(f"Lcom/musicglass/personal/{name}" in rule for name in FEED_CLASSES)]
    if len(kept) < PROFILE_MIN_RULES:
        raise PerformanceError(f"{collected} holds {len(kept)} rules; that is not a collected profile")
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(kept) + "\n", encoding="utf-8", newline="\n")
    return len(rules), len(kept)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    apk = commands.add_parser("apk")
    apk.add_argument("--apk", required=True)
    apk.add_argument("--profile")
    summary = commands.add_parser("summary")
    summary.add_argument("--tier", choices=("R", "L"), required=True)
    summary.add_argument("--refresh-hz", type=float, default=60.0)
    summary.add_argument("results", nargs="+")
    adopt = commands.add_parser("adopt")
    adopt.add_argument("--collected", required=True)
    adopt.add_argument("--out", default="app/src/release/generated/baselineProfiles/baseline-prof.txt")
    args = parser.parse_args(argv)
    if args.command == "adopt":
        total, kept = adopt_profile(args.collected, args.out)
        print(f"{kept} of {total} rules written to {args.out}")
        return 0
    if args.command == "apk":
        rows = check_apk(args.apk, args.profile)
        for name, ok, detail in rows:
            print(f"{'ok  ' if ok else 'FAIL'} {name}: {detail}")
        return 0 if all(ok for _, ok, _ in rows) else 2
    results, devices = load(args.results)
    rows, information = summarize(results, devices, args.tier, args.refresh_hz)
    print(render(rows, information, devices, args.tier))
    return status(rows)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (PerformanceError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"check_performance: {error}", file=sys.stderr)
        sys.exit(2)
