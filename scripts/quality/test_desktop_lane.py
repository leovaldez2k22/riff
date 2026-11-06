"""The desktop lane of verify.py: its gates, its list of excluded tests, and how it reads test results."""
import json
from pathlib import Path
import re
import tempfile
import unittest

import verify

REPO = Path(__file__).resolve().parents[2]


class Args:
    def __init__(self, **values):
        self.lane, self.checks, self.serial, self.tier, self.mode = "desktop", None, None, None, "fixture"
        self.evidence_dir, self.allow_install, self.previous_apk, self.offline = None, False, None, True
        self.__dict__.update(values)


def suite(folder, name, cases):
    """A JUnit XML file like Gradle's. cases: {test: None | "failure" | "error" | "skipped"}."""
    folder.mkdir(parents=True, exist_ok=True)
    body = "".join(f'<testcase classname="com.musicglass.desktop.{name}" name="{test}">' +
                   (f"<{kind}/>" if kind else "") + "</testcase>" for test, kind in cases.items())
    (folder / f"TEST-com.musicglass.desktop.{name}.xml").write_text(
        f'<?xml version="1.0"?><testsuite name="{name}" tests="{len(cases)}">{body}</testsuite>', encoding="utf-8")


class FakeLane(verify.Lane):
    """Records the commands instead of running Gradle, and writes the test results a run would leave."""
    def __init__(self, root, results, codes=None, **args):
        super().__init__(Args(evidence_dir=str(root / "evidence"), **args), root=root)
        self.commands, self.results, self.codes = [], results, codes or {}

    def run(self, command, log, timeout=7200, check=True):
        self.commands.append([str(c) for c in command])
        (self.evidence / log).write_text("log\n", encoding="utf-8")
        if log == "desktop-unit.log":
            for project, cases in self.results.items():
                for name, tests in cases.items():
                    suite(self.root / project / "build/test-results/test", name, tests)
        code = self.codes.get(log, 0)
        if check and code:
            raise verify.Failed(f"exited {code}")
        return code


class LaneTest(unittest.TestCase):
    def setUp(self):
        self._dir = tempfile.TemporaryDirectory(prefix="riff-desktop-lane-")
        self.root = Path(self._dir.name)

    def tearDown(self):
        self._dir.cleanup()

    def summary(self, lane):
        lane.desktop()
        return lane.write_summary()

    def test_the_pr_lane_is_unchanged_and_the_desktop_lane_has_its_gates(self):
        self.assertEqual(["compile", "unit", "provider-jvm"], verify.LANES["desktop"])
        self.assertFalse(set(verify.LANES["pr"]) & {"compile", "unit", "provider-jvm"})

    def test_passing_tests_pass_and_every_command_builds_the_desktop_projects_only(self):
        lane = FakeLane(self.root, {"desktop": {"QueueTest": {"a": None, "b": None}},
                                    "desktop-data": {"StoreTest": {"c": None}}})
        summary = self.summary(lane)
        self.assertEqual("PASS_LOCAL", summary["status"])
        self.assertEqual(3, summary["gates"]["unit"]["detail"]["tests"])
        for command in lane.commands:
            self.assertIn("-PriffDesktop=true", command)
            self.assertIn("--offline", command)
            tasks = [part for part in command[1:] if not part.startswith("-")]
            self.assertTrue(all(task.startswith((":desktop:", ":desktop-data:")) for task in tasks), tasks)
            # The lane opens no window: never run, package or acceptance tasks.
            self.assertFalse([t for t in tasks if re.search(r":(run|package|.*Acceptance|.*Native|.*native)", t)], tasks)
        self.assertIn(":desktop:verifyProviderJvm", lane.commands[-1])

    def test_a_failing_test_fails_the_lane_and_is_named(self):
        lane = FakeLane(self.root, {"desktop": {"TokenParityContractTest": {"desktopHasEveryToken": "failure", "x": None}}},
                        codes={"desktop-unit.log": 1})
        summary = self.summary(lane)
        self.assertEqual("FAIL", summary["status"])
        self.assertIn("TokenParityContractTest.desktopHasEveryToken", summary["gates"]["unit"]["reason"])
        self.assertEqual("PASS", summary["gates"]["provider-jvm"]["status"])  # one failure does not hide the rest
        recorded = json.loads((lane.evidence / "desktop-unit.json").read_text(encoding="utf-8"))
        self.assertEqual(["TokenParityContractTest.desktopHasEveryToken"], recorded["failures"])

    def test_gradle_failing_with_no_failed_test_still_fails(self):
        lane = FakeLane(self.root, {"desktop": {"QueueTest": {"a": None}}}, codes={"desktop-unit.log": 1})
        self.assertEqual("FAIL", self.summary(lane)["gates"]["unit"]["status"])

    def test_no_results_is_a_failure_not_a_pass(self):
        self.assertEqual("FAIL", self.summary(FakeLane(self.root, {}))["gates"]["unit"]["status"])

    def test_old_results_are_removed_before_the_run(self):
        suite(self.root / "desktop/build/test-results/test", "DeletedTest", {"old": "failure"})
        lane = FakeLane(self.root, {"desktop": {"QueueTest": {"a": None}}})
        self.assertEqual("PASS", self.summary(lane)["gates"]["unit"]["status"])

    def test_excluded_and_skipped_tests_are_listed_in_the_receipt(self):
        lane = FakeLane(self.root, {"desktop": {"QueueTest": {"a": None, "later": "skipped"}}})
        summary = self.summary(lane)
        excluded = summary["other"]["excluded-tests"]["tests"]
        self.assertIn(":desktop:NativeAudioIntegrationTest.actualDualDeckPipelineReachesWindowsLoopback", excluded)
        self.assertIn(":desktop-data:EncoreLoadAcceptanceTest", excluded)
        self.assertEqual({"QueueTest.later": "skipped"}, summary["other"]["skipped-tests"]["tests"])
        text = (lane.evidence / "lane-summary.md").read_text(encoding="utf-8")
        self.assertIn("actualDualDeckPipelineReachesWindowsLoopback", text)
        self.assertIn("QueueTest.later", text)

    def test_only_selected_gates_run(self):
        lane = FakeLane(self.root, {}, checks="provider-jvm")
        summary = self.summary(lane)
        self.assertEqual("BLOCKED", summary["gates"]["unit"]["status"])
        self.assertEqual(1, len(lane.commands))


class ExcludedTestsTest(unittest.TestCase):
    def test_the_table_matches_the_build_files(self):
        """Every test kept out of `test` in a build file is in DESKTOP_EXCLUDED_TESTS, and nothing else is."""
        for project in verify.DESKTOP_PROJECTS:
            text = (REPO / project.strip(":") / "build.gradle.kts").read_text(encoding="utf-8")
            found = set()
            for block in re.findall(r"tasks\.test\s*\{([^}]*)\}", text):
                for pattern in re.findall(r'exclude\(([^)]*)\)', block):
                    found |= {re.sub(r"^\*\*/|\*$|\.class$", "", p) for p in re.findall(r'"([^"]+)"', pattern)}
            # A method left out of every task that runs its class (excludeTestsMatching("*.method")).
            methods = re.findall(r'excludeTestsMatching\("\*\.(\w+)"\)', text)
            for method in methods:
                owners = [path.stem for path in (REPO / project.strip(":") / "src/test").rglob("*.kt")
                          if re.search(rf"\bfun\s+`?{method}`?\s*\(", path.read_text(encoding="utf-8"))]
                self.assertEqual(1, len(owners), method)
                found.add(f"{owners[0]}.{method}")
            self.assertEqual(found, set(verify.DESKTOP_EXCLUDED_TESTS[project]), project)

    def test_junit_results_count_failures_errors_and_skips(self):
        with tempfile.TemporaryDirectory() as folder:
            suite(Path(folder), "A", {"ok": None, "bad": "failure", "broken": "error", "later": "skipped"})
            counts = verify.junit_results([Path(folder), Path(folder) / "absent"])
        self.assertEqual((1, 4, 2, 1), (counts["suites"], counts["tests"], counts["failed"], counts["skipped"]))
        self.assertEqual(["A.bad", "A.broken"], counts["failures"])


class WorkflowTest(unittest.TestCase):
    """.github/workflows/desktop.yml, the desktop lane in CI (PLAN.md Task 1.4)."""
    TEXT = (REPO / ".github/workflows/desktop.yml").read_text(encoding="utf-8")
    CI = (REPO / ".github/workflows/ci.yml").read_text(encoding="utf-8")

    def test_it_runs_the_desktop_lane_on_windows_with_jdk_21(self):
        self.assertIn("  desktop-unit:\n    runs-on: windows-latest\n", self.TEXT)
        self.assertIn("java-version: '21'", self.TEXT)
        self.assertRegex(self.TEXT, r"run: python scripts/quality/verify\.py --lane desktop --evidence-dir \S+")
        self.assertNotIn("--checks", self.TEXT)  # every gate, never a partial run

    def test_actions_are_pinned_to_the_commits_ci_uses(self):
        uses = re.findall(r"uses: (\S+)@(\S+)", self.TEXT)
        self.assertTrue(uses)
        pinned = dict(re.findall(r"uses: (\S+)@(\S+)", self.CI))
        for action, ref in uses:
            self.assertRegex(ref, r"^[0-9a-f]{40}$", action)
            self.assertEqual(pinned.get(action), ref, action)

    def test_it_needs_no_secret_cannot_write_and_installs_no_sdk(self):
        self.assertNotIn("secrets.", self.TEXT)
        self.assertIn("permissions:\n  contents: read", self.TEXT)
        self.assertNotIn("setup-android", self.TEXT)
        self.assertNotIn("\t", self.TEXT)

    def test_a_change_to_the_lane_or_the_workflow_runs_it(self):
        for trigger in ("push:", "pull_request:"):
            block = re.split(r"\n  \S", self.TEXT.split(f"\n  {trigger}\n", 1)[1], maxsplit=1)[0]
            for path in ("'desktop/**'", "'desktop-data/**'", "'scripts/quality/**'", "'.github/workflows/desktop.yml'"):
                self.assertIn(path, block, trigger)


if __name__ == "__main__":
    unittest.main()
