"""Local quality evidence. No installation, provider access, or third-party Python packages."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ASSET = "assets/quality/build-identity.json"
REVISION = re.compile(r"[0-9a-f]{40,64}")
DIGEST = re.compile(r"[0-9a-f]{64}")


class EvidenceError(ValueError):
    pass


def utc(value):
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if result.tzinfo is None:
            raise ValueError()
        return result.astimezone(timezone.utc)
    except (TypeError, ValueError) as error:
        raise EvidenceError("Invalid timezone-aware UTC timestamp") from error


def now():
    return datetime.now(timezone.utc).isoformat()


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def source_digest(root=ROOT):
    # Same sorted, framed relative paths/raw bytes as app/build.gradle.kts.
    paths = list((root / "app/src").rglob("*"))
    paths += [root / name for name in (
        "app/build.gradle.kts", "app/proguard-rules.pro", "build.gradle.kts",
        "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat")]
    paths += list((root / "gradle").rglob("*"))
    digest = hashlib.sha256()
    for path in sorted({p for p in paths if p.is_file()}, key=lambda p: p.relative_to(root).as_posix()):
        digest.update(path.relative_to(root).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def source_identity(root=ROOT):
    def git(*args):
        return subprocess.run(["git", "-C", str(root), *args], capture_output=True,
                              text=True, check=False, timeout=15)
    try:
        head = git("rev-parse", "HEAD")
        status = git("status", "--porcelain", "--untracked-files=normal")
        revision = head.stdout.strip()
        if head.returncode or status.returncode or not REVISION.fullmatch(revision):
            raise EvidenceError("Git unavailable")
        dirty = bool(status.stdout.strip())
        state = "dirty" if dirty else "clean"
    except (OSError, subprocess.TimeoutExpired, EvidenceError):
        revision, dirty, state = "local-unknown", None, "local-unknown"
    return dict(revision=revision, dirty=dirty, sourceState=state, sourceDigest=source_digest(root))


def parse_junit(paths):
    if not paths:
        raise EvidenceError("No JUnit XML reports")
    totals = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
    seen = set()
    for path in paths:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as error:
            raise EvidenceError("Unreadable or truncated JUnit XML") from error
        if root.tag not in ("testsuite", "testsuites"):
            raise EvidenceError("Unknown JUnit structure")
        suites = [node for node in root.iter("testsuite") if not node.findall("testsuite")]
        if not suites:
            raise EvidenceError("Missing JUnit suites")
        for suite in suites:
            name = suite.get("name")
            if not name or name in seen:
                raise EvidenceError("Missing or duplicate suite identity")
            seen.add(name)
            try:
                counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
            except (KeyError, ValueError) as error:
                raise EvidenceError("Incomplete JUnit counts") from error
            cases = suite.findall("testcase")
            if any(value < 0 for value in counts.values()) or counts["tests"] != len(cases):
                raise EvidenceError("JUnit case/count mismatch")
            actual = {key: sum(case.find(tag) is not None for case in cases)
                      for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped"))}
            if any(counts[key] != actual[key] for key in actual):
                raise EvidenceError("JUnit outcome/count mismatch")
            if any(len([case.find(tag) for tag in ("failure", "error", "skipped")
                        if case.find(tag) is not None]) > 1 for case in cases):
                raise EvidenceError("Ambiguous JUnit outcome")
            identities = [(case.get("classname"), case.get("name")) for case in cases]
            if any(not name for _, name in identities) or len(set(identities)) != len(identities):
                raise EvidenceError("Missing or duplicate testcase identity")
            totals["suites"] += 1
            for key, count in counts.items():
                totals[key] += count
    if not totals["tests"]:
        raise EvidenceError("Zero executed tests cannot pass")
    if totals["failures"] or totals["errors"] or totals["skipped"]:
        raise EvidenceError("JUnit contains failures, errors or unexpected skips")
    return totals


def parse_instrumentation(text):
    # Require structured per-case start/end pairs, a consistent total, final code AND runner summary.
    if re.search(r"(?im)Process crashed|shortMsg=|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|FAILURES!!!", text):
        raise EvidenceError("Instrumentation crashed or failed")
    fields, pending, finished = {}, {}, set()
    expected = None
    for line in text.splitlines():
        match = re.fullmatch(r"INSTRUMENTATION_STATUS: ([A-Za-z]+)=(.*)", line)
        if match:
            fields[match[1]] = match[2]
            continue
        match = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if not match:
            continue
        code = int(match[1])
        if code not in (1, 0):
            raise EvidenceError("Failed, skipped or unknown instrumentation status")
        try:
            total, current = int(fields["numtests"]), int(fields["current"])
            identity = (fields["class"], fields["test"])
        except (KeyError, ValueError) as error:
            raise EvidenceError("Incomplete instrumentation case") from error
        if total < 1 or not 1 <= current <= total or not all(identity):
            raise EvidenceError("Invalid instrumentation counts")
        if expected is not None and expected != total:
            raise EvidenceError("Instrumentation total changed")
        expected = total
        if code == 1:
            if current in pending or current in finished:
                raise EvidenceError("Duplicate instrumentation start")
            pending[current] = identity
        else:
            if pending.pop(current, None) != identity:
                raise EvidenceError("Unpaired instrumentation completion")
            finished.add(current)
        fields = {}
    codes = re.findall(r"(?m)^INSTRUMENTATION_CODE: (-?\d+)\s*$", text)
    summaries = re.findall(r"(?m)^OK \((\d+) tests?\)\s*$", text)
    if codes != ["-1"] or summaries != [str(expected)] or pending or fields or expected is None:
        raise EvidenceError("Truncated or incomplete instrumentation run")
    if finished != set(range(1, expected + 1)):
        raise EvidenceError("Missing instrumentation cases")
    return dict(tests=expected, failures=0, errors=0, skipped=0)


def parse_lint(path):
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as error:
        raise EvidenceError("Unreadable or truncated lint XML") from error
    if root.tag != "issues" or "format" not in root.attrib:
        raise EvidenceError("Unknown lint report format")
    counts = {}
    for issue in root.findall("issue"):
        if not issue.get("id") or issue.get("severity") not in ("Fatal", "Error", "Warning", "Information", "Informational", "Hint", "Ignore"):
            raise EvidenceError("Incomplete lint issue")
        key = issue.get("severity")
        counts[key] = counts.get(key, 0) + 1
    if counts.get("Error", 0) or counts.get("Fatal", 0):
        raise EvidenceError("Lint errors")
    return counts


def apk_identity(path):
    try:
        with zipfile.ZipFile(path) as archive:
            value = json.loads(archive.read(ASSET))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise EvidenceError("APK lacks valid embedded build identity") from error
    for key in ("revision", "sourceState", "sourceDigest", "variant", "versionName", "versionCode", "toolchain"):
        if key not in value:
            raise EvidenceError("Incomplete APK build identity")
    if not DIGEST.fullmatch(value["sourceDigest"]):
        raise EvidenceError("Invalid source digest")
    if value["sourceState"] not in ("clean", "dirty", "local-unknown"):
        raise EvidenceError("Unknown source state")
    if value["sourceState"] == "local-unknown":
        if value["revision"] != "local-unknown" or value.get("dirty") is not None:
            raise EvidenceError("Unknown Git state claims a known/clean revision")
    elif not REVISION.fullmatch(value["revision"]) or value.get("dirty") is not (value["sourceState"] == "dirty"):
        raise EvidenceError("Inconsistent revision/dirty state")
    return value


def certificate(path, apksigner=None):
    # Signed APKs must be verified, unsigned APKs explicitly return null.
    with zipfile.ZipFile(path) as archive:
        v1 = any(re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC)", name) for name in archive.namelist())
    v2 = False
    with Path(path).open("rb") as stream:
        # APK signing-block magic is near the ZIP central directory; avoid reading the whole APK.
        stream.seek(max(0, Path(path).stat().st_size - 65557))
        tail = stream.read()
        eocd = tail.rfind(b"PK\x05\x06")
        if eocd >= 0 and len(tail) >= eocd + 20:
            offset = int.from_bytes(tail[eocd + 16:eocd + 20], "little")
            if offset >= 16:
                stream.seek(offset - 16)
                v2 = stream.read(16) == b"APK Sig Block 42"
    if not v1 and not v2:
        return None
    if apksigner is None:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        suffix = "apksigner.bat" if os.name == "nt" else "apksigner"
        apksigner = Path(sdk) / "build-tools/36.0.0" / suffix if sdk else None
    if apksigner is None or not Path(apksigner).is_file():
        raise EvidenceError("Signed APK requires apksigner certificate verification")
    result = subprocess.run([str(apksigner), "verify", "--print-certs", str(path)],
                            text=True, capture_output=True, timeout=120, check=False)
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})", result.stdout)
    if result.returncode or not match:
        raise EvidenceError("APK signature verification failed")
    return match[1].lower()


def collect(root, output, apk, variant, start, end, exit_code, commands, junit_paths, lint_path,
            expected_source, instrumentation=None, device=None, mode="not-run", apksigner=None,
            test_apk=None, checks_skipped=False):
    started, ended = utc(start), utc(end)
    if ended < started:
        raise EvidenceError("Evidence ends before it starts")
    result = dict(schemaVersion=1, revision=expected_source["revision"],
                  dirty=expected_source["dirty"], sourceState=expected_source["sourceState"],
                  sourceDigest=expected_source["sourceDigest"], variant=variant,
                  versionName=None, versionCode=None, toolchain=None, apkSha256=None,
                  certificateSha256=None, startUtc=started.isoformat(), endUtc=ended.isoformat(),
                  device=device or dict(tier=None, api=None, model=None),
                  mode=mode, commands=commands, reports={}, gates={})
    Path(output).mkdir(parents=True, exist_ok=True)
    gates = result["gates"]

    def gate(name, action):
        try:
            value = action()
            gates[name] = dict(status="PASS")
            return value
        except (EvidenceError, OSError, ValueError, subprocess.SubprocessError) as error:
            gates[name] = dict(status="FAIL", reason=str(error))
            return None

    def build_ok():
        if exit_code != 0:
            raise EvidenceError("Build command failed")
    gate("build", build_ok)

    def fresh(path):
        path = Path(path)
        if not started.timestamp() - 2 <= path.stat().st_mtime <= ended.timestamp() + 2:
            raise EvidenceError("Stale report or APK predates run")
        return path

    def verify_apk():
        path = Path(apk) if instrumentation else fresh(apk)
        identity = apk_identity(path)
        if identity["variant"] != variant:
            raise EvidenceError("APK variant mismatch")
        for key in ("revision", "dirty", "sourceState", "sourceDigest"):
            if identity.get(key) != expected_source.get(key):
                raise EvidenceError("APK/source identity mismatch")
        if source_digest(root) != expected_source["sourceDigest"]:
            raise EvidenceError("Source changed during evidence run")
        result.update({key: identity[key] for key in ("versionName", "versionCode", "toolchain")})
        result["apkSha256"] = sha256(path)
        result["certificateSha256"] = certificate(path, apksigner)
        result["apkPath"] = "app.apk"
        shutil.copy2(path, Path(output) / "app.apk")
        if instrumentation:
            if test_apk is None:
                raise EvidenceError("Instrumentation requires its exact test APK")
            test_identity = apk_identity(test_apk)
            if test_identity != identity:
                raise EvidenceError("App/test APK build pairing mismatch")
            result["testApkSha256"] = sha256(test_apk)
            shutil.copy2(test_apk, Path(output) / "test.apk")
    gate("artifactIdentity", verify_apk)

    def junit_ok():
        for path in junit_paths:
            fresh(path)
        result["junit"] = parse_junit(junit_paths)
    if checks_skipped:
        gates["jvm"] = dict(status="BLOCKED", reason="Caller explicitly skipped local checks")
    else:
        gate("jvm", junit_ok)
    archived_junit = Path(output) / "reports/junit"
    archived_junit.mkdir(parents=True, exist_ok=True)
    for path in junit_paths:
        shutil.copy2(path, archived_junit / Path(path).name)
    result["reports"]["junit"] = [
        dict(path="reports/junit/" + Path(path).name, sha256=sha256(path)) for path in junit_paths]

    def lint_ok():
        result["lint"] = parse_lint(fresh(lint_path))
    if checks_skipped:
        gates["lint"] = dict(status="BLOCKED", reason="Caller explicitly skipped local checks")
    else:
        gate("lint", lint_ok)
    if lint_path and Path(lint_path).is_file():
        shutil.copy2(lint_path, Path(output) / "reports/lint.xml")
        result["reports"]["lint"] = dict(path="reports/lint.xml", sha256=sha256(lint_path))
    if instrumentation:
        def instrumentation_ok():
            result["instrumentation"] = parse_instrumentation(fresh(instrumentation).read_text(encoding="utf-8-sig"))
        gate("instrumentation", instrumentation_ok)
        shutil.copy2(instrumentation, Path(output) / "reports/instrumentation.txt")
        result["reports"]["instrumentation"] = dict(path="reports/instrumentation.txt", sha256=sha256(instrumentation))
    else:
        gates["instrumentation"] = dict(status="BLOCKED", reason="No device run supplied")
    # This status covers requested local gates only. BLOCKED device gates remain explicit.
    result["status"] = ("FAIL" if any(g["status"] == "FAIL" for g in gates.values()) else
                        "BLOCKED" if checks_skipped else "PASS_LOCAL")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "manifest.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def run(args):
    root = ROOT
    expected = source_identity(root)
    run_id = args.run_id or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", run_id):
        raise EvidenceError("Invalid run ID")
    source_id = expected["revision"] + "-" + expected["sourceState"] + "-" + expected["sourceDigest"][:12]
    output = root / "artifacts/quality" / source_id / run_id
    output.mkdir(parents=True, exist_ok=False)  # Preserve failures; never overwrite a previous run.
    title = args.variant.capitalize()
    tasks = [":app:testDebugUnitTest", "--rerun", f":app:lint{title}", "--rerun", f":app:assemble{title}"]
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper), *tasks, "--continue", "--console=plain"]
    if args.offline:
        command.append("--offline")
    if args.collect_only:
        start, end, code = args.start_utc, args.end_utc, args.exit_code
    else:
        start = now()
        with (output / "build.log").open("w", encoding="utf-8") as log:
            try:
                process = subprocess.Popen(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
                code = process.wait()
            except OSError as error:
                log.write("Build could not start: " + str(error))
                code = 1
        end = now()
        if "BUILD SUCCESSFUL" not in (output / "build.log").read_text(encoding="utf-8"):
            code = code or 1
    apk_dir = root / "app/build/outputs/apk" / args.variant
    # An unsigned baseline may coexist with old signed outputs: never choose by directory order.
    candidates = [path for path in apk_dir.glob("*.apk") if path.stat().st_mtime >= utc(start).timestamp() - 2]
    apk = args.apk if args.collect_only else (
        candidates[0] if len(candidates) == 1 else apk_dir / "missing-or-ambiguous.apk")
    junit_dir = args.junit_dir or root / "app/build/test-results/testDebugUnitTest"
    junit_paths = [] if args.checks_skipped else sorted(junit_dir.glob("TEST-*.xml"))
    lint_path = args.lint_report or root / f"app/build/reports/lint-results-{args.variant}.xml"
    result = collect(root, output, apk, args.variant, start, end, code,
                     [args.command or "collect-only: independently revalidated reports and artifact" if args.collect_only else " ".join(command)],
                     junit_paths, lint_path,
                     expected, args.instrumentation,
                     dict(tier=args.device_tier, api=args.device_api, model=args.device_model), args.mode,
                     test_apk=args.test_apk, checks_skipped=args.checks_skipped)
    print(str(output / "manifest.json"), flush=True)
    print(result["status"], flush=True)
    return 0 if result["status"] == "PASS_LOCAL" else 2 if result["status"] == "BLOCKED" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=("debug", "release", "stress"), default="debug")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--collect-only", action="store_true")
    parser.add_argument("--checks-skipped", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--junit-dir", type=Path)
    parser.add_argument("--lint-report", type=Path)
    parser.add_argument("--start-utc")
    parser.add_argument("--end-utc")
    parser.add_argument("--exit-code", type=int)
    parser.add_argument("--command")
    parser.add_argument("--inspect-apk", type=Path)
    parser.add_argument("--validate-instrumentation", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--instrumentation", type=Path)
    parser.add_argument("--device-tier", choices=("R", "L", "emulator", "unclassified"))
    parser.add_argument("--device-api", type=int)
    parser.add_argument("--device-model")
    parser.add_argument("--mode", choices=("fixture", "live", "not-run"), default="not-run")
    args = parser.parse_args()
    if args.collect_only and (not args.apk or not args.start_utc or not args.end_utc or args.exit_code is None):
        parser.error("Collection requires APK, UTC start/end and command exit code")
    if args.variant == "stress" and not args.collect_only:
        parser.error("Stress collection requires prebuilt paired APKs and collect-only mode")
    if args.checks_skipped and not args.collect_only:
        parser.error("Skipped checks require collect-only mode")
    if args.instrumentation and (not args.device_tier or not args.device_api or not args.device_model or args.mode == "not-run"):
        parser.error("Instrumentation requires sanitized device tier/API/model and fixture/live mode")
    try:
        if args.inspect_apk:
            print(json.dumps(apk_identity(args.inspect_apk)))
            return 0
        if args.validate_instrumentation:
            print(json.dumps(parse_instrumentation(args.validate_instrumentation.read_text(encoding="utf-8-sig"))))
            return 0
        return run(args)
    except (EvidenceError, OSError, subprocess.SubprocessError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
