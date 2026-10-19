"""Tests for verify.py (Q16): lanes, device choice, tool lookup, summaries and the source archive check."""
import json
import os
import shutil
from pathlib import Path
import tempfile
import unittest
import zipfile

import verify


class Args:
    def __init__(self, **values):
        self.lane, self.checks, self.serial, self.tier, self.mode = "pr", None, None, None, "fixture"
        self.evidence_dir, self.allow_install, self.previous_apk, self.offline = None, False, None, True
        self.__dict__.update(values)


class SummaryTest(unittest.TestCase):
    def test_every_required_gate_pass_is_pass_local(self):
        gates = {name: dict(status="PASS") for name in verify.LANES["pr"]}
        self.assertEqual("PASS_LOCAL", verify.summarize("pr", gates)["status"])

    def test_a_missing_gate_is_a_failure_not_a_zero(self):
        gates = {name: dict(status="PASS") for name in verify.LANES["pr"][1:]}
        summary = verify.summarize("pr", gates)
        self.assertEqual("FAIL", summary["status"])
        self.assertEqual("FAIL", summary["gates"][verify.LANES["pr"][0]]["status"])
        self.assertIn("missing", summary["gates"][verify.LANES["pr"][0]]["reason"])

    def test_blocked_without_failures_is_blocked_and_an_unknown_status_fails(self):
        gates = {name: dict(status="PASS") for name in verify.LANES["emulator"]}
        gates["install"] = dict(status="BLOCKED", reason="no device")
        self.assertEqual("BLOCKED", verify.summarize("emulator", gates)["status"])
        gates["install"] = dict(status="SKIPPED")
        self.assertEqual("FAIL", verify.summarize("emulator", gates)["status"])

    def test_the_written_summary_never_holds_the_serial(self):
        gates = {"device": dict(status="BLOCKED", reason="serial 10ABCDEF123 is offline")}
        summary = verify.sanitize_summary(verify.summarize("device", gates), "10ABCDEF123")
        self.assertNotIn("10ABCDEF123", json.dumps(summary))

    def test_every_lane_has_required_gates(self):
        self.assertEqual({"pr", "emulator", "device", "benchmark", "packaging"}, set(verify.LANES))
        self.assertTrue(all(verify.LANES.values()))


class DeviceTest(unittest.TestCase):
    listing = "* daemon not running; starting now at tcp:5037\n* daemon started successfully\nList of devices attached\n" \
              "emulator-5580\tdevice\n10ABCDEF123\tdevice\nR58M\tunauthorized\n\n"

    def test_daemon_lines_are_not_devices(self):
        self.assertEqual([("emulator-5580", "device"), ("10ABCDEF123", "device"), ("R58M", "unauthorized")],
                         verify.parse_devices(self.listing))

    def test_one_ready_emulator_is_chosen_without_a_serial(self):
        self.assertEqual("emulator-5580", verify.choose_device(verify.parse_devices(self.listing), want_emulator=True))

    def test_a_phone_is_never_chosen_automatically(self):
        with self.assertRaises(verify.Blocked):
            verify.choose_device(verify.parse_devices(self.listing), want_emulator=False)
        self.assertEqual("10ABCDEF123", verify.choose_device(verify.parse_devices(self.listing), "10ABCDEF123", False))

    def test_several_ready_devices_need_a_serial(self):
        with self.assertRaises(verify.Blocked) as caught:
            verify.choose_device(verify.parse_devices(self.listing))
        self.assertIn("--serial", str(caught.exception))

    def test_the_wrong_kind_of_device_fails_and_an_unauthorized_one_blocks(self):
        devices = verify.parse_devices(self.listing)
        with self.assertRaises(verify.Failed):
            verify.choose_device(devices, "10ABCDEF123", want_emulator=True)
        with self.assertRaises(verify.Failed):
            verify.choose_device(devices, "emulator-5580", want_emulator=False)
        with self.assertRaises(verify.Blocked):
            verify.choose_device(devices, "R58M")
        with self.assertRaises(verify.Blocked):
            verify.choose_device(devices, "absent")

    def test_model_names_are_cleaned(self):
        self.assertEqual("sdk_gphone64_x86_64", verify.clean_model("sdk_gphone64_x86_64\r\n"))
        self.assertEqual("Pixel 7", verify.clean_model("Pixel 7;rm -rf /$(x)")[:7])
        self.assertIsNone(verify.clean_model(""))

    def test_a_device_lock_is_exclusive_and_removed(self):
        with tempfile.TemporaryDirectory() as root:
            with verify.DeviceLock(Path(root), "emulator-5580") as lock:
                self.assertNotIn("emulator-5580", lock.path.name)
                with self.assertRaises(verify.Blocked):
                    verify.DeviceLock(Path(root), "emulator-5580").__enter__()
                with verify.DeviceLock(Path(root), "emulator-5582"):
                    pass
            self.assertFalse(lock.path.exists())

    def test_a_stale_lock_is_taken_over(self):
        with tempfile.TemporaryDirectory() as root:
            lock = verify.DeviceLock(Path(root), "emulator-5580", max_age_s=0)
            lock.path.parent.mkdir(parents=True)
            lock.path.write_text("1 old")
            os.utime(lock.path, (1, 1))
            with lock:
                self.assertTrue(lock.path.exists())


class ToolsTest(unittest.TestCase):
    def test_sdk_order_environment_then_local_properties_then_project(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            env_sdk, prop_sdk, own = root / "env", root / "prop", root / ".toolchain/sdk"
            for d in (env_sdk, prop_sdk, own):
                d.mkdir(parents=True)
            (root / "local.properties").write_text("sdk.dir=" + str(prop_sdk).replace("\\", "\\\\").replace(":", "\\:") + "\n")
            self.assertEqual(env_sdk, verify.find_sdk(root, {"ANDROID_HOME": str(env_sdk)}))
            self.assertEqual(env_sdk, verify.find_sdk(root, {"ANDROID_SDK_ROOT": str(env_sdk)}))
            self.assertEqual(prop_sdk, verify.find_sdk(root, {}))
            (root / "local.properties").unlink()
            self.assertEqual(own, verify.find_sdk(root, {"ANDROID_HOME": str(root / "missing")}))

    def test_no_sdk_is_none_and_a_gate_needing_it_blocks(self):
        with tempfile.TemporaryDirectory() as temp:
            self.assertIsNone(verify.find_sdk(Path(temp), {}))
            lane = verify.Lane(Args(evidence_dir=temp), root=Path(temp))
            lane.sdk = None
            lane.gate("release-content", lambda: lane.sdk_tool("build-tools", "36.0.0", "aapt2"))
            self.assertEqual("BLOCKED", lane.gates["release-content"]["status"])

    def test_tool_environment_fills_only_what_is_unset(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / ".toolchain/sdk").mkdir(parents=True)
            (root / ".toolchain/jdk-21.0.1").mkdir()
            (root / ".toolchain/gradle-user-home").mkdir()
            env = verify.tool_environment(root, {"GRADLE_USER_HOME": "/elsewhere"})
            self.assertEqual(str(root / ".toolchain/sdk"), env["ANDROID_HOME"])
            self.assertEqual(str(root / ".toolchain/jdk-21.0.1"), env["JAVA_HOME"])
            self.assertEqual("/elsewhere", env["GRADLE_USER_HOME"])

    def test_no_supported_script_carries_a_personal_path(self):
        root = Path(__file__).resolve().parents[2]
        # A home folder on any system, or the agent workspace a helper was first written in.
        personal = ("C:\\Users\\", "c:\\users\\", "/home/", "/Users/", ".gemini", "\\brain\\")
        for path in [*root.joinpath("scripts").rglob("*.py"), *root.joinpath("scripts").rglob("*.ps1"),
                     *root.joinpath(".github").rglob("*.yml"), root / "app/build.gradle.kts", root / "gradle.properties"]:
            if path.name == "test_verify.py" or "__pycache__" in path.parts:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            for marker in personal:
                self.assertFalse(marker in text, f"{path.relative_to(root)} holds {marker!r}")


class InstallTest(unittest.TestCase):
    def test_device_lanes_install_nothing_without_permission(self):
        with tempfile.TemporaryDirectory() as temp:
            for lane_name in ("emulator", "device"):
                lane = verify.Lane(Args(lane=lane_name, evidence_dir=temp), root=Path(temp))
                lane.adb = lambda *a, **k: self.fail("adb was called")
                lane.device_lane(emulator=lane_name == "emulator")
                self.assertEqual("BLOCKED", lane.gates["device"]["status"])
                self.assertIn("--allow-install", lane.gates["device"]["reason"])
                self.assertEqual("BLOCKED", verify.summarize(lane_name, lane.gates)["status"])

    def test_benchmarks_refuse_without_a_physical_tier(self):
        with tempfile.TemporaryDirectory() as temp:
            lane = verify.Lane(Args(lane="benchmark", evidence_dir=temp, allow_install=True), root=Path(temp))
            lane.adb = lambda *a, **k: self.fail("adb was called")
            lane.benchmark()
            self.assertEqual("BLOCKED", lane.gates["device"]["status"])
            self.assertEqual("BLOCKED", lane.gates["benchmark"]["status"])

    def test_unselected_checks_are_blocked_not_passed(self):
        with tempfile.TemporaryDirectory() as temp:
            lane = verify.Lane(Args(checks="static", evidence_dir=temp), root=Path(temp))
            lane.gate("script-tests", lambda: 0)
            self.assertEqual("BLOCKED", lane.gates["script-tests"]["status"])


class NetworkGateTest(unittest.TestCase):
    """Q18-025: the emulator lane waits until the network is really off, not just asked to be."""
    class Result:
        def __init__(self, out):
            self.stdout, self.stderr, self.returncode = out, "", 0

    def run_gate(self, temp, states, limit_s):
        """states: (switches, default network) the emulator shows on each check, the last one repeating."""
        lane = verify.Lane(Args(lane="emulator", evidence_dir=temp), root=Path(temp))
        checks = [0]
        def adb(*args, **kwargs):
            command = " ".join(args)
            if "svc" in command:
                return self.Result("")
            switches, default = states[min(checks[0], len(states) - 1)]
            if "settings get" in command:
                return self.Result(switches)
            checks[0] += 1
            return self.Result(default)
        lane.adb = adb
        now = [0.0]
        def sleep(seconds):
            now[0] += seconds
        return lambda: lane.network_quiet(settle_s=5, limit_s=limit_s, sleep=sleep, clock=lambda: now[0]), now

    def test_mobile_data_that_comes_back_after_boot_is_waited_for(self):
        with tempfile.TemporaryDirectory() as temp:
            on, off = ("1\n0", "Active default network: 100"), ("0\n0", "Active default network: none")
            gate, now = self.run_gate(temp, [on, on, on, off], limit_s=60)
            self.assertIn("network off", gate())
            self.assertGreaterEqual(now[0], 3 + 5, "not before the cellular network had gone and stayed gone")

    def test_switches_at_0_with_a_default_network_up_are_not_off(self):
        with tempfile.TemporaryDirectory() as temp:
            gate, _ = self.run_gate(temp, [("0\n0", "Active default network: 100")], limit_s=30)
            with self.assertRaises(verify.Failed):
                gate()

    def test_a_network_that_drops_back_restarts_the_wait(self):
        with tempfile.TemporaryDirectory() as temp:
            on, off = ("0\n1", "Active default network: 101"), ("0\n0", "Active default network: none")
            gate, now = self.run_gate(temp, [off, off, on, off], limit_s=60)
            gate()
            self.assertGreaterEqual(now[0], 3 + 5)


class PackageTest(unittest.TestCase):
    """Q18C1: the lanes install the debug build under its own package, never over Riff or Riff Preview."""
    def test_the_runner_and_fixtures_name_the_debug_package_and_the_source_classes(self):
        self.assertEqual("com.musicglass.personal.debug", verify.DEBUG_PACKAGE)
        self.assertTrue(verify.RUNNER.startswith("com.musicglass.personal.debug.test/"))
        self.assertTrue(all(name.startswith("com.musicglass.personal.") and ".debug." not in name
                            for name in verify.FIXTURE_CLASSES))

    def pair(self, packages):
        temp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, temp, True)
        lane = verify.Lane(Args(lane="device", evidence_dir=temp), root=Path(temp))
        lane.build_pair = lambda: (Path(temp, "app.apk"), Path(temp, "test.apk"))
        for name in ("app.apk", "test.apk"):
            Path(temp, name).write_bytes(b"apk")
        lane.package_names = lambda app, test: packages
        identity = dict(revision="r", sourceState="clean", sourceDigest="d")
        original = verify.collect_results.apk_identity
        verify.collect_results.apk_identity = lambda path: identity
        try:
            lane.gate("pairing", lane.pairing)
        finally:
            verify.collect_results.apk_identity = original
        return lane.gates["pairing"]

    def test_apks_for_the_main_app_or_preview_are_refused_before_install(self):
        for packages in (("com.musicglass.personal", "com.musicglass.personal.test"),
                         ("com.musicglass.personal.preview", "com.musicglass.personal.debug.test")):
            gate = self.pair(packages)
            self.assertEqual("FAIL", gate["status"])
            self.assertIn("nothing is installed over another app", gate["reason"])
        self.assertEqual("PASS", self.pair((verify.DEBUG_PACKAGE, verify.TEST_PACKAGE))["status"])


class SourceArchiveTest(unittest.TestCase):
    def archive(self, names):
        handle = tempfile.NamedTemporaryFile(suffix=".zip", delete=False)
        handle.close()
        with zipfile.ZipFile(handle.name, "w") as out:
            for name in names:
                out.writestr(name, "x")
        self.addCleanup(os.unlink, handle.name)
        return handle.name

    def test_a_complete_archive_passes(self):
        self.assertEqual([], verify.check_source_archive(self.archive(verify.SOURCE_REQUIRED + ["app/src/main/java/A.kt"])))

    def test_keys_settings_builds_and_evidence_are_refused(self):
        for bad in ("release.jks", "app/upload.keystore", "local.properties", "app/build/outputs/x.apk", ".toolchain/sdk/a",
                    "artifacts/quality/x/manifest.json", ".env", ".env.local", "release-signing.properties", "cookies.txt",
                    "../escape.kt", "certs/key.pem"):
            problems = verify.check_source_archive(self.archive(verify.SOURCE_REQUIRED + [bad]))
            self.assertTrue(problems, bad)

    def test_a_missing_required_file_is_reported(self):
        problems = verify.check_source_archive(self.archive(verify.SOURCE_REQUIRED[1:]))
        self.assertEqual(["missing from the source: " + verify.SOURCE_REQUIRED[0]], problems)


if __name__ == "__main__":
    unittest.main()
