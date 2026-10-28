#!/usr/bin/env python3
"""Q16: one entry point for the quality lanes, on Windows and Linux, with no personal path in it.

    python scripts/quality/verify.py --lane pr                                    read-only, the CI lane
    python scripts/quality/verify.py --lane emulator --allow-install [--serial emulator-5580]
    python scripts/quality/verify.py --lane device --allow-install --serial <id> --tier R|L [--mode live]
    python scripts/quality/verify.py --lane benchmark --allow-install --serial <id> --tier R|L
    python scripts/quality/verify.py --lane packaging [--allow-install --previous-apk old.apk]
    python scripts/quality/verify.py --lane pr --checks script-tests,static          only some steps
    python scripts/quality/verify.py --tools                                       where the tools were found

Lanes
  pr         Script tests, static checks, JVM and Compose tests, release lint, an unsigned shrunk release with
             its build identity, the release content check (merged manifest, components, permissions, leftovers),
             the 40 MiB ceiling and Baseline Profile, and the solver seam in the R8 output. Installs nothing.
  emulator   The fixture suite (FIXTURE_CLASSES) on an emulator with its network turned off: a debug app and its
             test APK built together, their identities compared before anything is installed, then a strict read
             of the instrumentation output. Never a physical device.
  device     The same suite on an authorized physical test device, named by --serial. Fixture mode by default;
             --mode live only labels the run: credentials stay on the device, nothing here supplies them.
  benchmark  Macrobenchmark on a physical device of tier R or L, read by check_performance.py summary. An
             emulator is refused: its timings are not evidence.
  packaging  A source archive of the tracked files with its contents checked, the release APK's identity and
             certificate, SHA256SUMS, and, with --allow-install and --previous-apk on an emulator, an in-place
             upgrade from the previous APK (install -r, never an uninstall).

Each lane has a fixed list of required gates. The summary (lane-summary.json in the evidence directory) holds
every one of them: a gate that did not run is BLOCKED with the reason, and one that is missing is FAIL. Nothing
defaults to zero or to a pass. The summary names the device by tier, API level and a cleaned model name only,
never by serial.

Installing is separate and visible: without --allow-install no lane touches a device, and a lane that needs one
says BLOCKED. Two lanes never use the same device at once (a lock per device under artifacts/quality/.locks).

Tools are found in this order: ANDROID_HOME, ANDROID_SDK_ROOT, sdk.dir in local.properties, then the project's
own .toolchain/sdk; JAVA_HOME, then .toolchain/jdk-*, then PATH; GRADLE_USER_HOME, then .toolchain/gradle-user-home.
Exit status: 0 every required gate PASS, 2 any BLOCKED and none FAIL, 1 any FAIL.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import collect_results  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.musicglass.personal"
# Q18C1: the lanes build debug with -PriffSideBySide=true, which gives it its own package id, so they install beside
# Riff and Riff Preview. Its test APK is the debug package plus ".test"; the test classes keep the source namespace.
DEBUG_PACKAGE = f"{PACKAGE}.debug"
TEST_PACKAGE = f"{DEBUG_PACKAGE}.test"
RUNNER = f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
BUILD_TOOLS = "36.0.0"
# Q18-050: the benchmark lane installs these two itself and runs am instrument, rather than Gradle's connected task,
# which needs Android's test platform (42 artifacts outside the dependency-verification list). Both have their own
# package ids and the fixture feed, so neither can install over Riff or reach a provider.
BENCHMARK_APP = f"{PACKAGE}.benchmark"
MACROBENCHMARK = f"{PACKAGE}.macrobenchmark"
BENCHMARK_RUNNER = f"{MACROBENCHMARK}/androidx.test.runner.AndroidJUnitRunner"
BENCHMARK_OUTPUT = f"/sdcard/Android/media/{MACROBENCHMARK}/additional_test_output"
# Q18-023: on the owner's phone the runner can stop after a launch and wait for ever. Silence this long ends the run.
BENCHMARK_QUIET_SECONDS = 15 * 60

# The fixture suite: no account, no provider, synthetic audio and identities (docs/quality/deterministic-harness.md).
FIXTURE_CLASSES = [f"{PACKAGE}.{name}" for name in (
    "AccountSwitchDeviceTest", "CrossfadeFixtureDeviceTest", "DownloadLifecycleDeviceTest", "FixturePlaybackTest",
    "LegacyUpgradeDeviceTest", "LocalDataDeviceTest", "LocalDataTransferDeviceTest", "NormalizationDeviceTest",
    "PlaybackOrderDeviceTest", "RecoveryDeviceTest", "LyricsKaraokeDeviceTest", "PlayerFixesDeviceTest",
    "LyricShareDeviceTest", "OwnerFeaturesDeviceTest", "SeekTimingDeviceTest", "CarBrowseDeviceTest")]
# Run only where they apply, never skipped: a skip is not a pass. DownloadTimeLimitDeviceTest shortens Android 15's
# dataSync time limit with device_config, so emulators only, from API 35 (Q18-026). SignInScreensDeviceTest needs
# the network off for Google's page not to load, which only the emulator lane guarantees (Q19C3, Q18-044).
EMULATOR_CLASSES = {f"{PACKAGE}.DownloadTimeLimitDeviceTest": 35, f"{PACKAGE}.SignInScreensDeviceTest": 31}
# Device tests kept out of both lists, each with its reason (Q18-044: a new class must be placed on purpose).
OUTSIDE_THE_LANES = {
    "CrossfadeDeviceTest": "two real players and decoders, about 3.5 minutes; run beside the lane by hand (Q08 to Q10)",
    "SongFadeDeviceTest": "run with CrossfadeDeviceTest beside the lane by hand (Q08 to Q10)",
    "CrossfadeServiceDeviceTest": "drives the real playback service, effects and media controller; run by hand on the phone",
    "FrostedPopupDeviceTest": "a pixel test of the platform's window blur, which differs by device; run by hand",
    "PodcastMetadataDeviceTest": "the player's lazy stream resolution with the real service; run by hand",
    "PlaylistSortDeviceTest": "live: needs a signed-in account and changes a playlist preference (case map CAT04)",
    "ProviderCanaryDeviceTest": "live: sends real provider requests, only when asked for (Q10A)",
}


# Q18-071: a device test that waits on something that never comes fails after this long instead of holding the lane.
TEST_TIMEOUT_MS = 300_000


def instrument_command(classes):
    """The am instrument call for the fixture classes, with a time limit on every test (AndroidJUnitRunner timeout_msec)."""
    return ["shell", "am", "instrument", "-w", "-r", "-e", "timeout_msec", str(TEST_TIMEOUT_MS), "-e", "class", ",".join(classes), RUNNER]


def fixture_classes(lane, api):
    """The fixture suite for this lane and API level."""
    return FIXTURE_CLASSES + [name for name, since in EMULATOR_CLASSES.items() if lane == "emulator" and (api or 0) >= since]

LANES = {
    "pr": ["script-tests", "static", "build", "artifactIdentity", "jvm", "lint", "release-content", "apk", "solver-seam"],
    "emulator": ["device", "pairing", "install", "instrumentation"],
    "device": ["device", "pairing", "install", "instrumentation"],
    "benchmark": ["device", "benchmark"],
    "packaging": ["source-archive", "release-identity", "checksums", "upgrade"],
}
INSTALLING = {"emulator", "device", "benchmark"}

# Never inside a source archive: build output, local tools and settings, keys, credentials, evidence.
FORBIDDEN_IN_SOURCE = re.compile(
    r"(^|/)(build|\.gradle|\.toolchain|artifacts|\.idea|\.kotlin)/|(^|/)local\.properties$|"
    r"\.(jks|keystore|p12|pfx|pem|key)$|(^|/)\.env(\.|$)|release-signing\.properties$|(^|/)cookies?(\.|$)|"
    r"^references/", re.I)
# D3 (owner, 19 October 2026): the design reference pictures are tracked but are not Riff's work, so they stay out
# of the source archive.
LEFT_OUT_OF_SOURCE = ("references/",)
SOURCE_REQUIRED = ["app/build.gradle.kts", "settings.gradle.kts", "gradlew", "gradle/wrapper/gradle-wrapper.properties",
                   "gradle/verification-metadata.xml", "app/src/main/AndroidManifest.xml", "LICENSE", "README.md"]


class Blocked(Exception):
    """A gate that could not run here; the message says what it needs."""


class Failed(Exception):
    pass


# ------------------------------------------------------------------ tools

def read_properties(path):
    values = {}
    try:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith(("#", "!")) and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().replace("\\:", ":").replace("\\\\", "\\")
    except OSError:
        pass
    return values


def find_sdk(root=ROOT, env=os.environ):
    for candidate in (env.get("ANDROID_HOME"), env.get("ANDROID_SDK_ROOT"),
                      read_properties(root / "local.properties").get("sdk.dir"), str(root / ".toolchain/sdk")):
        if candidate and Path(candidate).is_dir():
            return Path(candidate)
    return None


def find_java(root=ROOT, env=os.environ):
    if env.get("JAVA_HOME") and Path(env["JAVA_HOME"]).is_dir():
        return Path(env["JAVA_HOME"])
    local = sorted((root / ".toolchain").glob("jdk-*")) if (root / ".toolchain").is_dir() else []
    return local[-1] if local else None


def executable(path, windows=os.name == "nt"):
    path = Path(path)
    for suffix in ((".exe", ".bat", "") if windows else ("",)):
        candidate = path.with_name(path.name + suffix)
        if candidate.is_file():
            return candidate
    return None


def tool_environment(root=ROOT, env=None):
    env = dict(os.environ if env is None else env)
    sdk, java = find_sdk(root, env), find_java(root, env)
    if sdk:
        env["ANDROID_HOME"] = env["ANDROID_SDK_ROOT"] = str(sdk)
    if java:
        env["JAVA_HOME"] = str(java)
    if not env.get("GRADLE_USER_HOME") and (root / ".toolchain/gradle-user-home").is_dir():
        env["GRADLE_USER_HOME"] = str(root / ".toolchain/gradle-user-home")
    return env


def watched(command, log, quiet_seconds, total_seconds, cwd=None, env=None):
    """Runs command, writing its output to log; stops it after quiet_seconds without a line. (exit code, stalled)."""
    import queue
    import threading
    lines = queue.Queue()
    process = subprocess.Popen([str(c) for c in command], cwd=cwd, env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, errors="replace")
    threading.Thread(target=lambda: [lines.put(line) for line in process.stdout] + [lines.put(None)], daemon=True).start()
    start = last = time.monotonic()
    with Path(log).open("w", encoding="utf-8", errors="replace") as out:
        while True:
            try:
                line = lines.get(timeout=5)
            except queue.Empty:
                line = ""
            if line is None:
                return process.wait(), False
            if line:
                out.write(line); out.flush(); last = time.monotonic()
            now = time.monotonic()
            if now - last > quiet_seconds or now - start > total_seconds:
                process.kill(); process.wait()
                return None, True


def gradle(root=ROOT):
    return root / ("gradlew.bat" if os.name == "nt" else "gradlew")


# ------------------------------------------------------------------ devices

def parse_devices(text):
    """(serial, state) for each device line of `adb devices`; daemon start-up lines before the list are skipped."""
    lines = text.splitlines()
    start = next((i + 1 for i, line in enumerate(lines) if line.startswith("List of devices")), len(lines))
    return [tuple(line.split()[:2]) for line in lines[start:] if len(line.split()) >= 2]


def is_emulator(serial):
    return serial.startswith("emulator-")


def choose_device(devices, serial=None, want_emulator=None):
    """The one device a lane may use. With no serial, exactly one ready device of the wanted kind."""
    ready = [s for s, state in devices if state == "device" and (want_emulator is None or is_emulator(s) == want_emulator)]
    if serial:
        state = dict(devices).get(serial)
        if state is None:
            raise Blocked("the named device is not connected")
        if state != "device":
            raise Blocked(f"the named device is {state}: unlock it and approve USB debugging")
        if want_emulator is not None and is_emulator(serial) != want_emulator:
            raise Failed("this lane runs on an emulator only" if want_emulator else "this lane runs on a physical device only")
        return serial
    if want_emulator is False:
        raise Blocked("name the physical test device with --serial; a phone is never chosen automatically")
    if len(ready) == 1:
        return ready[0]
    raise Blocked("no ready emulator" if not ready else "several ready devices: name one with --serial")


def clean_model(text):
    return re.sub(r"[^A-Za-z0-9 _.-]", "", text or "").strip()[:40] or None


class DeviceLock:
    """One lane per device at a time. The lock file holds no serial, only its hash, and is removed at the end."""
    def __init__(self, root, serial, max_age_s=6 * 3600):
        self.path = root / "artifacts/quality/.locks" / (hashlib.sha256(serial.encode()).hexdigest()[:16] + ".lock")
        self.max_age_s = max_age_s

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.path.exists() and time.time() - self.path.stat().st_mtime > self.max_age_s:
            self.path.unlink()  # left by a run that died; older than any lane takes
        try:
            fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            raise Blocked("another lane is using this device") from None
        os.write(fd, f"{os.getpid()} {datetime.now(timezone.utc).isoformat()}\n".encode())
        os.close(fd)
        return self

    def __exit__(self, *exc):
        try:
            self.path.unlink()
        except OSError:
            pass


# ------------------------------------------------------------------ summary

def summarize(lane, gates, device=None):
    """The lane's verdict from its required gates. A gate not recorded is a FAIL, never a quiet pass."""
    required = LANES[lane]
    result = {name: gates.get(name) or dict(status="FAIL", reason="required gate missing from the results")
              for name in required}
    statuses = [g["status"] for g in result.values()]
    status = "FAIL" if "FAIL" in statuses or any(s not in ("PASS", "BLOCKED") for s in statuses) else \
        "BLOCKED" if "BLOCKED" in statuses else "PASS_LOCAL"
    extra = {k: v for k, v in gates.items() if k not in result}
    return dict(schemaVersion=1, lane=lane, status=status, required=required, gates=result, other=extra,
                device=device or dict(tier=None, api=None, model=None))


SERIAL_LIKE = re.compile(r"\b(?=[A-Za-z0-9]*[0-9])(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{10,20}\b")


def sanitize_summary(summary, serial=None):
    """The summary as written: the serial removed from every reason, whatever wrote it."""
    text = json.dumps(summary)
    if serial:
        text = text.replace(serial, "<device>")
    return json.loads(text)


# ------------------------------------------------------------------ runner

class Lane:
    def __init__(self, args, root=ROOT):
        self.args, self.root = args, root
        self.env = tool_environment(root)
        self.sdk = find_sdk(root, self.env)
        self.python = sys.executable or "python3"
        self.gates = {}
        self.evidence = Path(args.evidence_dir) if args.evidence_dir else \
            root / "artifacts/quality/lanes" / (args.lane + "-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
        self.evidence.mkdir(parents=True, exist_ok=True)
        self.serial = None
        self.device = None
        self.selected = set(args.checks.split(",")) if args.checks else None

    def wanted(self, name):
        return self.selected is None or name in self.selected

    def gate(self, name, action):
        if not self.wanted(name):
            self.gates[name] = dict(status="BLOCKED", reason="not selected with --checks")
            return None
        print(f"== {name}", flush=True)
        try:
            value = action()
            self.gates[name] = dict(status="PASS", **({"detail": value} if isinstance(value, (str, dict)) else {}))
            return value
        except Blocked as error:
            self.gates[name] = dict(status="BLOCKED", reason=str(error))
        except (Failed, collect_results.EvidenceError, OSError, subprocess.SubprocessError, ValueError) as error:
            self.gates[name] = dict(status="FAIL", reason=str(error) or type(error).__name__)
        print(f"   {self.gates[name]['status']}: {self.gates[name].get('reason', '')}", flush=True)
        return None

    def run(self, command, log, timeout=7200, check=True):
        with (self.evidence / log).open("w", encoding="utf-8", errors="replace") as out:
            process = subprocess.run([str(c) for c in command], cwd=self.root, env=self.env, stdout=out,
                                     stderr=subprocess.STDOUT, timeout=timeout, check=False)
        if check and process.returncode:
            raise Failed(f"{Path(str(command[0])).name} exited {process.returncode} (see {log})")
        return process.returncode

    def sdk_tool(self, *parts):
        if not self.sdk:
            raise Blocked("no Android SDK: set ANDROID_HOME or sdk.dir in local.properties")
        found = executable(self.sdk.joinpath(*parts))
        if not found:
            raise Blocked(f"missing from the SDK: {'/'.join(parts)}")
        return found

    def adb(self, *args, timeout=600):
        command = [self.sdk_tool("platform-tools", "adb")] + (["-s", self.serial] if self.serial else []) + list(args)
        return subprocess.run([str(c) for c in command], capture_output=True, text=True, timeout=timeout,
                              env=self.env, check=False, errors="replace")

    def write_summary(self):
        summary = sanitize_summary(summarize(self.args.lane, self.gates, self.device), self.serial)
        (self.evidence / "lane-summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        lines = [f"# {summary['lane']} lane: {summary['status']}", ""] + [
            f"- {name}: {gate['status']}" + (f" ({gate['reason']})" if gate.get("reason") else "")
            for name, gate in summary["gates"].items()]
        (self.evidence / "lane-summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("\n".join(lines), flush=True)
        return summary

    # -------------------------------------------------------------- pr

    def pr(self):
        self.gate("script-tests", lambda: self.run(
            [self.python, "-m", "unittest", "discover", "-s", "scripts/quality", "-p", "test_*.py"], "script-tests.log"))
        self.gate("static", lambda: self.run([self.python, "scripts/quality/static_checks.py"], "static-checks.log"))
        manifest = None
        if any(self.wanted(n) for n in ("build", "artifactIdentity", "jvm", "lint")):
            command = [self.python, "scripts/quality/collect_results.py", "--variant", "release",
                       "--run-id", "lane-" + self.evidence.name[-40:].replace(".", "-")]
            if self.args.offline:
                command.append("--offline")
            self.run(command, "collect-release.log", check=False)
            text = (self.evidence / "collect-release.log").read_text(encoding="utf-8", errors="replace")
            paths = [line.strip() for line in text.splitlines() if line.strip().endswith("manifest.json")]
            manifest = json.loads(Path(paths[-1]).read_text(encoding="utf-8")) if paths and Path(paths[-1]).is_file() else None
            (self.evidence / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8") if manifest else None
        for name in ("build", "artifactIdentity", "jvm", "lint"):
            if not self.wanted(name):
                continue
            if manifest is None:
                self.gates[name] = dict(status="FAIL", reason="the release collector wrote no manifest (see collect-release.log)")
            else:
                self.gates[name] = manifest["gates"].get(name) or dict(status="FAIL", reason="gate missing from the manifest")
        self.gate("release-content", lambda: self.run([self.python, "scripts/quality/check_release_content.py", "--apk", self.release_apk(),
                                                       "--aapt2", self.sdk_tool("build-tools", BUILD_TOOLS, "aapt2")], "release-content.log"))
        self.gate("apk", lambda: self.run([self.python, "scripts/quality/check_performance.py", "apk", "--apk", self.release_apk()], "apk-check.log"))
        self.gate("solver-seam", lambda: self.run([self.python, "scripts/quality/check_solver_seam.py", "--mapping",
                                                   self.root / "app/build/outputs/mapping/release/mapping.txt"], "solver-seam.log"))

    def release_apk(self):
        apks = sorted((self.root / "app/build/outputs/apk/release").glob("*.apk"))
        if len(apks) != 1:
            raise Failed("exactly one release APK is expected, found %d" % len(apks))
        return apks[0]

    # -------------------------------------------------------------- devices

    def attach(self, want_emulator):
        if not self.args.allow_install:
            raise Blocked("installs on a device: run again with --allow-install")
        listing = self.adb("devices")
        if listing.returncode:
            raise Blocked("adb could not list devices")
        self.serial = choose_device(parse_devices(listing.stdout), self.args.serial, want_emulator)
        boot = self.adb("shell", "getprop", "sys.boot_completed").stdout.strip()
        if boot != "1":
            raise Blocked("the device has not finished starting")
        api = self.adb("shell", "getprop", "ro.build.version.sdk").stdout.strip()
        model = clean_model(self.adb("shell", "getprop", "ro.product.model").stdout)
        tier = "emulator" if want_emulator else (self.args.tier or "unclassified")
        self.device = dict(tier=tier, api=int(api) if api.isdigit() else None, model=model)
        return dict(self.device)

    def build_pair(self):
        command = [gradle(self.root), ":app:assembleDebug", ":app:assembleDebugAndroidTest", "-PriffSideBySide=true",
                   "--console=plain"]
        if self.args.offline:
            command.append("--offline")
        self.run(command, "build-pair.log")
        app = sorted((self.root / "app/build/outputs/apk/debug").glob("*.apk"))
        test = sorted((self.root / "app/build/outputs/apk/androidTest/debug").glob("*.apk"))
        if len(app) != 1 or len(test) != 1:
            raise Failed("exactly one debug app APK and one test APK are required")
        return app[0], test[0]

    def pairing(self):
        """Builds the app and its test APK, and before anything is installed checks they come from one build."""
        app, test = self.build_pair()
        self.app, self.test = app, test
        a, t = collect_results.apk_identity(app), collect_results.apk_identity(test)
        if a != t:
            raise Failed("the app and test APKs were not built together")
        packages = self.package_names(app, test)
        if packages != (DEBUG_PACKAGE, TEST_PACKAGE):
            raise Failed(f"the APKs are {packages[0]} and {packages[1]}, not {DEBUG_PACKAGE} and {TEST_PACKAGE}: "
                         "nothing is installed over another app")
        hashes = dict(app=collect_results.sha256(app), test=collect_results.sha256(test))
        (self.evidence / "apks.sha256").write_text(f"{hashes['app']}  app.apk\n{hashes['test']}  test.apk\n", encoding="utf-8")
        return dict(revision=a["revision"], sourceState=a["sourceState"], sourceDigest=a["sourceDigest"], **hashes)

    def package_names(self, app, test):
        aapt2 = self.sdk_tool("build-tools", BUILD_TOOLS, "aapt2")
        names = []
        for apk in (app, test):
            result = subprocess.run([str(aapt2), "dump", "packagename", str(apk)], text=True, capture_output=True, timeout=120)
            if result.returncode or not result.stdout.strip():
                raise Failed("could not read the package name of " + apk.name)
            names.append(result.stdout.strip())
        return tuple(names)

    def network_quiet(self, settle_s=5, limit_s=300, sleep=time.sleep, clock=time.monotonic):
        """Q18-025: on a fresh API 36 image mobile data comes up minutes after boot and a "svc data disable" made
        before telephony is ready does not stick. The switches are set again until both read 0 and no default
        network is up for settle_s seconds in a row."""
        deadline, quiet_since = clock() + limit_s, None
        while clock() < deadline:
            self.adb("shell", "svc wifi disable; svc data disable")
            switches = self.adb("shell", "settings get global mobile_data; settings get global wifi_on").stdout.split()
            default = self.adb("shell", "dumpsys connectivity | grep -m 1 'Active default network'").stdout.strip()
            if switches == ["0", "0"] and default.endswith(": none"):
                quiet_since = clock() if quiet_since is None else quiet_since
                if clock() - quiet_since >= settle_s:
                    return "network off (mobile data and Wi-Fi 0, no default network)"
            else:
                quiet_since = None
            sleep(1)
        raise Failed("the emulator's network did not stay off (mobile data, Wi-Fi or a default network came back)")

    def install(self, app, test, network_off):
        if network_off:
            self.network_quiet()
        for apk in (app, test):
            result = self.adb("install", "-r", "-t", str(apk))
            (self.evidence / "install.log").open("a", encoding="utf-8").write(result.stdout + result.stderr)
            if result.returncode or "Success" not in result.stdout:
                raise Failed("install failed; never uninstall to get past a certificate mismatch (see install.log)")
        if network_off:
            ping = self.adb("shell", "ping -c 1 -W 2 8.8.8.8 >/dev/null 2>&1; echo $?").stdout.strip()
            if ping == "0":
                raise Failed("the emulator still reaches the network")
            return self.network_quiet() + ", ping exit " + ping

    def instrument(self):
        app, test = self.app, self.test
        log = self.evidence / "instrumentation.txt"
        start = datetime.now(timezone.utc).isoformat()
        result = self.adb(*instrument_command(fixture_classes(self.args.lane, self.device["api"])), timeout=7200)
        log.write_text(result.stdout + result.stderr, encoding="utf-8")
        end = datetime.now(timezone.utc).isoformat()
        command = [self.python, "scripts/quality/collect_results.py", "--collect-only", "--variant", "debug",
                   "--apk", app, "--test-apk", test, "--start-utc", start, "--end-utc", end, "--exit-code", str(result.returncode),
                   "--instrumentation", log, "--device-tier", self.device["tier"], "--device-api", str(self.device["api"] or 0),
                   "--device-model", self.device["model"] or "unknown", "--mode", self.args.mode,
                   "--run-id", "lane-instr-" + self.evidence.name[-34:].replace(".", "-"),
                   "--junit-dir", self.root / "app/build/test-results/testDebugUnitTest",
                   "--lint-report", self.root / "app/build/reports/lint-results-debug.xml", "--checks-skipped"]
        self.run(command, "collect-instrumentation.log", check=False)
        # The collector binds the run to the exact APK pair and the source; its gates decide, not this script.
        text = (self.evidence / "collect-instrumentation.log").read_text(encoding="utf-8", errors="replace")
        paths = [line.strip() for line in text.splitlines() if line.strip().endswith("manifest.json")]
        if not paths or not Path(paths[-1]).is_file():
            raise Failed("the collector wrote no manifest (see collect-instrumentation.log)")
        manifest = json.loads(Path(paths[-1]).read_text(encoding="utf-8"))
        (self.evidence / "instrumentation-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        for name in ("artifactIdentity", "instrumentation"):
            gate = manifest["gates"].get(name, dict(status="FAIL", reason="missing"))
            if gate["status"] != "PASS":
                raise Failed(f"{name}: {gate.get('reason', gate['status'])}")
        return dict(tests=manifest["instrumentation"]["tests"])

    def device_lane(self, emulator):
        if not self.gate("device", lambda: self.attach(emulator)):
            for name in ("pairing", "install", "instrumentation"):
                self.gates[name] = dict(status="BLOCKED", reason="no device")
            return
        with DeviceLock(self.root, self.serial):
            if self.gate("pairing", self.pairing) and \
                    self.gate("install", lambda: self.install(self.app, self.test, network_off=emulator) or "installed"):
                # Checked again just before the suite: the cellular network can come up during the install.
                self.gate("instrumentation", lambda: (self.network_quiet() if emulator else None, self.instrument())[1])
        for name in ("install", "instrumentation"):
            self.gates.setdefault(name, dict(status="BLOCKED", reason="an earlier gate did not pass"))

    def benchmark(self):
        def attach():
            if self.args.tier not in ("R", "L"):
                raise Blocked("benchmarks need --tier R or L and a physical device of that tier")
            return self.attach(False)
        if not self.gate("device", attach):
            self.gates["benchmark"] = dict(status="BLOCKED", reason="no physical R or L device")
            return
        def measure():
            command = [gradle(self.root), ":app:assembleBenchmarkRelease", ":benchmark:assembleBenchmarkRelease",
                       "--console=plain"] + (["--offline"] if self.args.offline else [])
            self.run(command, "benchmark-build.log", timeout=3600)
            app = sorted((self.root / "app/build/outputs/apk/benchmarkRelease").glob("*.apk"))
            test = sorted((self.root / "benchmark/build/outputs/apk/benchmarkRelease").glob("*.apk"))
            if len(app) != 1 or len(test) != 1:
                raise Failed("exactly one Riff Benchmark APK and one macrobenchmark APK are required")
            for apk in (app[0], test[0]):
                result = self.adb("install", "-r", "-t", str(apk))
                (self.evidence / "install.log").open("a", encoding="utf-8").write(result.stdout + result.stderr)
                if result.returncode or "Success" not in result.stdout:
                    raise Failed("install failed; never uninstall to get past a certificate mismatch (see install.log)")
            self.adb("shell", "rm", "-rf", BENCHMARK_OUTPUT)
            adb = [self.sdk_tool("platform-tools", "adb"), "-s", self.serial]
            code, stalled = watched(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "additionalTestOutputDir",
                                           BENCHMARK_OUTPUT, BENCHMARK_RUNNER], self.evidence / "benchmark.log",
                                    BENCHMARK_QUIET_SECONDS, 4 * 3600, cwd=self.root, env=self.env)
            if stalled:
                self.adb("shell", "am", "force-stop", MACROBENCHMARK); self.adb("shell", "am", "force-stop", BENCHMARK_APP)
                raise Blocked(f"the runner went quiet for {BENCHMARK_QUIET_SECONDS // 60} minutes (Q18-023; see benchmark.log)")
            results_dir = self.evidence / "benchmark-results"
            results_dir.mkdir(exist_ok=True)
            self.adb("pull", BENCHMARK_OUTPUT + "/.", str(results_dir), timeout=1800)
            self.adb("shell", "rm", "-rf", BENCHMARK_OUTPUT)
            log = (self.evidence / "benchmark.log").read_text(encoding="utf-8", errors="replace")
            if code or "FAILURES!!!" in log or "INSTRUMENTATION_FAILED" in log:
                raise Failed("the benchmark run failed (see benchmark.log)")
            results = sorted(results_dir.glob("**/*benchmarkData.json"))
            if not results:
                raise Failed("the benchmark wrote no results")
            status = self.run([self.python, "scripts/quality/check_performance.py", "summary", "--tier", self.args.tier, *results],
                              "benchmark-summary.log", check=False)
            if status == 3:
                raise Blocked("results are not evidence (see benchmark-summary.log)")
            if status:
                raise Failed("a budget was missed (see benchmark-summary.log)")
        with DeviceLock(self.root, self.serial):
            self.gate("benchmark", measure)

    # -------------------------------------------------------------- packaging

    def tracked_files(self):
        result = subprocess.run(["git", "-C", str(self.root), "ls-files", "-z"], capture_output=True, check=False)
        if result.returncode:
            raise Blocked("git is needed to know which files are source")
        return sorted(p for p in result.stdout.decode("utf-8").split("\0") if p)

    def source_archive(self):
        identity = collect_results.source_identity(self.root)
        files = [p for p in self.tracked_files() if (self.root / p).is_file() and not p.startswith(LEFT_OUT_OF_SOURCE)]
        archive = self.evidence / "source.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as out:
            for name in files:
                info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                out.writestr(info, (self.root / name).read_bytes())
        problems = check_source_archive(archive)
        if problems:
            raise Failed("; ".join(problems[:5]))
        note = "" if identity["sourceState"] == "clean" else " (working tree not clean: not an exact candidate)"
        return f"{len(files)} files, state {identity['sourceState']}{note}"

    def release_identity(self):
        apks = sorted((self.root / "app/build/outputs/apk/release").glob("*.apk"))
        if len(apks) != 1:
            raise Blocked("build the release first (the pr lane does)")
        identity = collect_results.apk_identity(apks[0])
        try:
            certificate = collect_results.certificate(apks[0], executable(self.sdk / "build-tools" / BUILD_TOOLS / "apksigner") if self.sdk else None)
        except collect_results.EvidenceError as error:
            raise Failed(str(error)) from error
        (self.evidence / "release-identity.json").write_text(json.dumps(dict(identity, certificateSha256=certificate), indent=2) + "\n", encoding="utf-8")
        if certificate is None:
            raise Blocked("the release APK is unsigned: the certificate needs a signed candidate (docs/release-signing.md)")
        return dict(versionName=identity["versionName"], versionCode=identity["versionCode"], certificate=certificate)

    def checksums(self):
        names = [p for p in ("source.zip",) if (self.evidence / p).is_file()]
        apks = sorted((self.root / "app/build/outputs/apk/release").glob("*.apk"))
        lines = [f"{collect_results.sha256(self.evidence / n)}  {n}" for n in names] + \
                [f"{collect_results.sha256(a)}  {a.name}" for a in apks]
        if not lines:
            raise Failed("nothing to list")
        (self.evidence / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")
        return f"{len(lines)} files"

    def upgrade(self):
        if not self.args.previous_apk:
            raise Blocked("needs --previous-apk (the last distributed APK, same certificate) and --allow-install")
        self.attach(True)
        candidate = sorted((self.root / "app/build/outputs/apk/release").glob("*.apk"))
        if len(candidate) != 1:
            raise Blocked("build the release first")
        with DeviceLock(self.root, self.serial):
            first = self.adb("install", "-r", str(self.args.previous_apk))
            if first.returncode:
                raise Failed("the previous APK did not install")
            before = self.adb("shell", "dumpsys", "package", PACKAGE).stdout
            second = self.adb("install", "-r", str(candidate[0]))
            (self.evidence / "upgrade.log").write_text(first.stdout + first.stderr + second.stdout + second.stderr, encoding="utf-8")
            if second.returncode or "Success" not in second.stdout:
                raise Failed("the candidate does not install over the previous APK (certificate or version code); never uninstall to get past it")
            after = self.adb("shell", "dumpsys", "package", PACKAGE).stdout
            installed = lambda text: re.search(r"firstInstallTime=(\S+ \S+)", text)
            if not installed(before) or not installed(after) or installed(before)[1] != installed(after)[1]:
                raise Failed("the app was reinstalled, not updated: its data did not survive")
            return "updated in place"

    def packaging(self):
        self.gate("source-archive", self.source_archive)
        self.gate("release-identity", self.release_identity)
        self.gate("checksums", self.checksums)
        if self.args.allow_install:
            self.gate("upgrade", self.upgrade)
        else:
            self.gates["upgrade"] = dict(status="BLOCKED", reason="installs on an emulator: run again with --allow-install and --previous-apk")


def check_source_archive(path):
    """What must and must not be in a source archive. Returns the problems found."""
    problems = []
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
    for name in names:
        if FORBIDDEN_IN_SOURCE.search(name):
            problems.append(f"must not be in the source: {name}")
        if name.startswith("/") or ".." in name.split("/") or "\\" in name:
            problems.append(f"unsafe path: {name}")
    for name in SOURCE_REQUIRED:
        if name not in names:
            problems.append(f"missing from the source: {name}")
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lane", choices=sorted(LANES), default="pr")
    parser.add_argument("--checks", help="comma-separated gates of the lane to run; the others are BLOCKED")
    parser.add_argument("--serial", help="the device to use; required for a physical device")
    parser.add_argument("--tier", choices=("R", "L", "unclassified"))
    parser.add_argument("--mode", choices=("fixture", "live"), default="fixture")
    parser.add_argument("--evidence-dir")
    parser.add_argument("--allow-install", action="store_true", help="the lane may install on the selected device")
    parser.add_argument("--previous-apk", type=Path)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--tools", action="store_true", help="print where the tools were found and stop")
    args = parser.parse_args(argv)
    if args.tools:
        env = tool_environment()
        print(json.dumps(dict(sdk=env.get("ANDROID_HOME"), java=env.get("JAVA_HOME"), gradleUserHome=env.get("GRADLE_USER_HOME"),
                              gradle=str(gradle())), indent=2))
        return 0
    if args.checks and set(args.checks.split(",")) - set(LANES[args.lane]):
        parser.error("unknown gates for this lane: " + ",".join(sorted(set(args.checks.split(",")) - set(LANES[args.lane]))))
    mode = "INSTALLS ON A DEVICE" if args.allow_install and (args.lane in INSTALLING or args.previous_apk) else "READ-ONLY"
    print(f"Riff verify: lane {args.lane}, {mode}", flush=True)
    lane = Lane(args)
    if args.lane == "pr":
        lane.pr()
    elif args.lane == "emulator":
        lane.device_lane(emulator=True)
    elif args.lane == "device":
        lane.device_lane(emulator=False)
    elif args.lane == "benchmark":
        lane.benchmark()
    else:
        lane.packaging()
    summary = lane.write_summary()
    return {"PASS_LOCAL": 0, "BLOCKED": 2}.get(summary["status"], 1)


if __name__ == "__main__":
    sys.exit(main())
