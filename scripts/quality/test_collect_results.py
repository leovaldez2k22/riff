import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

import collect_results as c


def junit(cases='<testcase classname="Policy" name="works"/>', tests=1, failures=0, errors=0, skipped=0):
    return f'<testsuite name="Policy" tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">{cases}</testsuite>'


def instrument():
    fields = "INSTRUMENTATION_STATUS: class=example.Policy\nINSTRUMENTATION_STATUS: test=works\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: current=1\n"
    return fields + "INSTRUMENTATION_STATUS_CODE: 1\n" + fields + "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"


class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.report = self.root / "TEST-Policy.xml"

    def parse(self, text):
        self.report.write_text(text, encoding="utf-8")
        return c.parse_junit([self.report])

    def test_complete_junit(self):
        self.assertEqual(1, self.parse(junit())["tests"])

    def test_truncated_junit(self):
        with self.assertRaises(c.EvidenceError):
            self.parse(junit()[:-8])

    def test_missing_junit_counts(self):
        with self.assertRaises(c.EvidenceError):
            self.parse(junit().replace('errors="0"', ""))

    def test_case_count_mismatch(self):
        with self.assertRaises(c.EvidenceError):
            self.parse(junit(tests=2))

    def test_failure_cannot_be_hidden_in_zero_count(self):
        with self.assertRaises(c.EvidenceError):
            self.parse(junit('<testcase name="works"><failure/></testcase>'))

    def test_failure_and_skip_are_not_pass(self):
        for tag, kwargs in [("failure", {"failures": 1}), ("error", {"errors": 1}), ("skipped", {"skipped": 1})]:
            with self.subTest(tag=tag), self.assertRaises(c.EvidenceError):
                self.parse(junit(f'<testcase name="works"><{tag}/></testcase>', **kwargs))

    def test_empty_and_duplicate_reports_rejected(self):
        for paths in ([], [self.report, self.report]):
            self.report.write_text(junit(), encoding="utf-8")
            with self.subTest(paths=paths), self.assertRaises(c.EvidenceError):
                c.parse_junit(paths)

    def test_zero_tests_rejected(self):
        with self.assertRaises(c.EvidenceError):
            self.parse(junit("", tests=0))

    def test_complete_structured_instrumentation(self):
        self.assertEqual(1, c.parse_instrumentation(instrument())["tests"])

    def test_terminal_code_and_friendly_filename_do_not_imply_success(self):
        for text in ("INSTRUMENTATION_CODE: -1\n", "OK (1 test)\nINSTRUMENTATION_CODE: -1\n", ""):
            with self.subTest(text=text), self.assertRaises(c.EvidenceError):
                c.parse_instrumentation(text)

    def test_crash_even_with_success_markers_rejected(self):
        for failure in ("INSTRUMENTATION_RESULT: shortMsg=Process crashed\n", "INSTRUMENTATION_FAILED: broken\n"):
            with self.subTest(failure=failure), self.assertRaises(c.EvidenceError):
                c.parse_instrumentation(instrument() + failure)

    def test_truncated_instrumentation_rejected(self):
        with self.assertRaises(c.EvidenceError):
            c.parse_instrumentation(instrument().split("OK")[0])

    def test_unstarted_and_failed_test_rejected(self):
        fields = instrument()
        for text in (fields.replace("INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: -2"),
                     fields[fields.index("INSTRUMENTATION_STATUS_CODE: 1") + len("INSTRUMENTATION_STATUS_CODE: 1\n"):],
                     fields.replace("INSTRUMENTATION_STATUS: numtests=1", "INSTRUMENTATION_STATUS: numtests=2")):
            with self.subTest(text=text), self.assertRaises(c.EvidenceError):
                c.parse_instrumentation(text)

    def test_missing_or_unknown_lint_never_passes(self):
        for text in ("<issues", "<issues/>", '<issues format="6"><issue id="Problem" severity="Error"/></issues>',
                     '<issues format="6"><issue id="Problem" severity="Mystery"/></issues>'):
            self.report.write_text(text, encoding="utf-8")
            with self.subTest(text=text), self.assertRaises(c.EvidenceError):
                c.parse_lint(self.report)

    def test_timezone_required(self):
        with self.assertRaises(c.EvidenceError):
            c.utc("2026-10-08T10:00:00")

    def test_git_unavailable_is_unknown_and_never_clean(self):
        with patch.object(c.subprocess, "run", side_effect=FileNotFoundError()):
            identity = c.source_identity(self.root)
        self.assertEqual("local-unknown", identity["revision"])
        self.assertIsNone(identity["dirty"])

    def test_source_digest_tracks_content_and_renames(self):
        source = self.root / "app/src/main/example.kt"
        source.parent.mkdir(parents=True)
        source.write_text("first")
        first = c.source_digest(self.root)
        source.write_text("second")
        second = c.source_digest(self.root)
        source.rename(source.with_name("renamed.kt"))
        self.assertNotEqual(first, second)
        self.assertNotEqual(second, c.source_digest(self.root))

    def evidence(self, mismatch=False, failed=False, stale=False):
        start = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
        expected = dict(revision="a" * 40, dirty=True, sourceState="dirty", sourceDigest=c.source_digest(self.root))
        identity = dict(expected, variant="release", versionName="0.10.0-rc1", versionCode=22, toolchain={})
        if mismatch:
            identity["sourceDigest"] = "b" * 64
        apk = self.root / "candidate.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr(c.ASSET, json.dumps(identity))
        self.report.write_text(junit(), encoding="utf-8")
        lint = self.root / "lint.xml"
        lint.write_text('<issues format="6"/>', encoding="utf-8")
        if stale:
            import os
            os.utime(self.report, (1, 1))
        return c.collect(self.root, self.root / "final-accepted", apk, "release", start, c.now(),
                         1 if failed else 0, ["synthetic"], [self.report], lint, expected)

    def test_matching_apk_and_reports_only_pass_local(self):
        result = self.evidence()
        self.assertEqual("PASS_LOCAL", result["status"])
        self.assertEqual("BLOCKED", result["gates"]["instrumentation"]["status"])
        self.assertIsNone(result["certificateSha256"])

    def test_apk_source_mismatch_fails(self):
        self.assertEqual("FAIL", self.evidence(mismatch=True)["status"])

    def test_nonzero_build_fails_even_with_valid_reports(self):
        self.assertEqual("FAIL", self.evidence(failed=True)["status"])

    def test_stale_report_fails(self):
        self.assertEqual("FAIL", self.evidence(stale=True)["status"])


class InstrumentationPairTest(unittest.TestCase):
    def test_paired_apks_required_and_skipped_local_checks_remain_blocked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected = dict(revision="a" * 40, dirty=True, sourceState="dirty", sourceDigest=c.source_digest(root))
            identity = dict(expected, variant="stress", versionName="0.10.0-rc1", versionCode=22, toolchain={})
            app, test = root / "app.apk", root / "test.apk"
            with zipfile.ZipFile(app, "w") as archive:
                archive.writestr(c.ASSET, json.dumps(identity))
            log = root / "instrumentation.txt"
            log.write_text(instrument(), encoding="utf-8")
            start = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
            for paired, expected_status in ((False, "FAIL"), (True, "BLOCKED")):
                with zipfile.ZipFile(test, "w") as archive:
                    archive.writestr(c.ASSET, json.dumps(identity if paired else dict(identity, sourceDigest="b" * 64)))
                result = c.collect(root, root / str(paired), app, "stress", start, c.now(), 0,
                                   ["synthetic"], [], None, expected, log,
                                   dict(tier="emulator", api=36, model="synthetic"),
                                   "fixture", test_apk=test, checks_skipped=True)
                self.assertEqual(expected_status, result["status"])
                self.assertEqual("PASS", result["gates"]["instrumentation"]["status"])

    def test_unknown_revision_cannot_claim_clean(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            identity = dict(revision="local-unknown", dirty=False, sourceState="local-unknown",
                            sourceDigest="b" * 64, variant="debug", versionName="candidate", versionCode=22, toolchain={})
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(c.ASSET, json.dumps(identity))
            with self.assertRaises(c.EvidenceError):
                c.apk_identity(apk)


if __name__ == "__main__":
    unittest.main()
