"""Packaging fixtures must preserve data, reject incomplete evidence and never reinstall to recover."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
import verify
from test_verify import Args


class PackagingProbeTest(unittest.TestCase):
    def report(self):
        fields = {key: "a" * 64 for key in ("preferences", "history", "totals", "hourly", "queue", "download", "audio", "analysis")}
        return dict(fields, widgetId=42, widgetRenderedFixture=True, ready="2448802")

    def output(self, report=None, code=-1):
        return "INSTRUMENTATION_RESULT: report=" + json.dumps(self.report() if report is None else report) + f"\nINSTRUMENTATION_CODE: {code}\n"

    def test_only_complete_successful_fixture_evidence_passes(self):
        self.assertEqual(self.report(), verify.parse_packaging_probe(self.output()))
        cases = ["", self.output(code=0), self.output() + self.output(), self.output() + "INSTRUMENTATION_RESULT: error=failed\n"]
        for key, value in (("history", ""), ("audio", "not a digest"), ("widgetId", True), ("widgetRenderedFixture", False), ("ready", "0")):
            invalid = self.report(); invalid[key] = value; cases.append(self.output(invalid))
        for output in cases:
            with self.subTest(output=output), self.assertRaises(verify.Failed):
                verify.parse_packaging_probe(output)

    def test_both_aapt_minimum_api_formats_are_understood(self):
        for text in ("sdkVersion:'30'\n", "minSdkVersion:'30'\ntargetSdkVersion:'36'\n"):
            self.assertEqual(30, verify.package_min_api(text))
        for text in ("", "sdkVersion:'30'\nminSdkVersion:'31'\n"):
            with self.assertRaises(verify.Failed): verify.package_min_api(text)

    def test_first_install_time_must_be_present_and_unambiguous(self):
        text = "firstInstallTime=2026-11-07 12:00:00\n"
        self.assertEqual("2026-11-07 12:00:00", verify.package_first_install_time(text))
        for invalid in ("", text + text):
            with self.assertRaises(verify.Failed): verify.package_first_install_time(invalid)


class PackagingLaneTest(unittest.TestCase):
    def lane(self, **kwargs):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        lane = verify.Lane(Args(lane="packaging", evidence_dir=str(root / "evidence"), **kwargs), root=root)
        lane.source_archive = lambda: "archive checked"
        lane.release_identity = lambda: "identity checked"
        lane.checksums = lambda: "checksums checked"
        return lane

    def test_no_install_permission_blocks_both_paths(self):
        lane = self.lane()
        lane.switch_install_paths = lambda: self.fail("installation must not run")
        lane.packaging()
        self.assertEqual("BLOCKED", lane.gates["edition-switch"]["status"])
        self.assertEqual("BLOCKED", lane.gates["upgrade"]["status"])

    def test_one_trial_proves_both_paths_and_a_failure_is_never_retried(self):
        for failed in (False, True):
            lane = self.lane(allow_install=True, previous_apk=Path("old.apk"))
            calls = []
            def trial():
                calls.append(1)
                if failed: raise verify.Failed("fixture data changed")
                return {"paths": ["upgrade-plus", "switch-riff", "switch-plus"]}
            lane.switch_install_paths = trial
            lane.packaging()
            self.assertEqual(1, len(calls))
            for name in ("edition-switch", "upgrade"):
                self.assertEqual("FAIL" if failed else "PASS", lane.gates[name]["status"])

    def test_missing_previous_release_never_claims_an_upgrade(self):
        lane = self.lane(allow_install=True)
        lane.switch_install_paths = lambda: {"paths": ["switch-riff", "switch-plus"]}
        lane.packaging()
        self.assertEqual("PASS", lane.gates["edition-switch"]["status"])
        self.assertEqual("BLOCKED", lane.gates["upgrade"]["status"])

    def driver(self, existing=False, changed_data=False, changed_install=False):
        lane = self.lane(allow_install=True, previous_apk=Path("old.apk"))
        calls = []
        lane.attach = lambda emulator: self.assertTrue(emulator)
        lane.serial = "emulator-5580"
        lane.packaging_preflight = lambda: ({"riff": Path("riff.apk"), "plus": Path("plus.apk")}, Path("probe.apk"))
        lane.network_quiet = lambda: calls.append("network-off")
        lane.install_packaging_apk = lambda apk, name: calls.append(name)
        times = [0]
        def adb(*args, **kwargs):
            if args[:3] == ("shell", "pm", "path"):
                return SimpleNamespace(returncode=0, stdout="package:/data/app/base.apk\n" if existing else "", stderr="")
            if args[:3] == ("shell", "dumpsys", "package"):
                times[0] += 1
                second = "01" if changed_install and times[0] > 1 else "00"
                return SimpleNamespace(returncode=0, stdout=f"firstInstallTime=2026-11-07 12:00:{second}\n", stderr="")
            return SimpleNamespace(returncode=0, stdout="", stderr="")
        lane.adb = adb
        lane.probe_state = lambda action, token, name: {"fixture": "changed" if changed_data and action != "seed" else "kept"}
        return lane, calls

    def test_existing_app_is_never_overwritten_or_removed(self):
        lane, calls = self.driver(existing=True)
        with self.assertRaises(verify.Blocked): lane.switch_install_paths()
        self.assertEqual([], calls)

    def test_preflight_failure_prevents_every_mutation(self):
        lane, calls = self.driver()
        def refused(): raise verify.Failed("certificate or source identity mismatch")
        lane.packaging_preflight = refused
        with self.assertRaises(verify.Failed): lane.switch_install_paths()
        self.assertEqual([], calls)

    def test_upgrade_and_both_same_version_switches_keep_the_fixture(self):
        lane, calls = self.driver()
        result = lane.switch_install_paths()
        self.assertEqual(["upgrade-plus", "switch-riff", "switch-plus"], result["paths"])
        self.assertEqual(["network-off", "previous", "probe", "upgrade-plus", "switch-riff", "switch-plus"], calls)

    def test_changed_data_or_reinstallation_stops_at_the_first_failure(self):
        for data, install in ((True, False), (False, True)):
            lane, calls = self.driver(changed_data=data, changed_install=install)
            with self.assertRaises(verify.Failed): lane.switch_install_paths()
            self.assertNotIn("switch-riff", calls)
            self.assertNotIn("switch-plus", calls)


if __name__ == "__main__":
    unittest.main()
