import argparse
import tempfile
from pathlib import Path
import unittest
from unittest import mock

from desktop_smoke import build_environment, positive_runs, parse_scales, run


class DesktopSmokeIsolationTest(unittest.TestCase):
    def test_nonempty_runs_and_scales_are_required(self):
        for value in ("0", "-1"):
            with self.assertRaises(argparse.ArgumentTypeError):
                positive_runs(value)
        self.assertEqual(2, positive_runs("2"))
        for value in ("", "1.0,", "NaN", "inf", "0", "-1", "oops"):
            with self.assertRaises(argparse.ArgumentTypeError):
                parse_scales(value)
        self.assertEqual(["1.0", "1.25"], parse_scales("1.0,1.25"))

    def test_environment_has_isolated_roots_and_cannot_inherit_overrides(self):
        original = {"JAVA_TOOL_OPTIONS": "old", "_JAVA_OPTIONS": "-Duser.home=real",
                    "JDK_JAVA_OPTIONS": "-Duser.home=real", "USERPROFILE": "real", "LOCALAPPDATA": "real", "APPDATA": "real"}
        with tempfile.TemporaryDirectory(prefix="riff-smoke-test-") as owned:
            root = Path(owned) / "path with spaces"
            env = build_environment("1.25", root, original)
            self.assertIn(f'"-Duser.home={root / "home"}"', env["JAVA_TOOL_OPTIONS"])
            self.assertNotIn("_JAVA_OPTIONS", env)
            self.assertNotIn("JDK_JAVA_OPTIONS", env)
            self.assertEqual(str(root / "local"), env["LOCALAPPDATA"])
            self.assertEqual(str(root / "roaming"), env["APPDATA"])
            self.assertEqual(str(root / "home"), env["USERPROFILE"])
        self.assertEqual("real", original["USERPROFILE"])
        self.assertIn("_JAVA_OPTIONS", original)

    def test_each_run_owns_and_cleans_only_its_temporary_root(self):
        roots = []
        def fake_process(exe, scale, root):
            roots.append(root)
            (root / "test-state").write_text("fixture", encoding="utf-8")
            return {"isolatedStorage": True}
        with mock.patch("desktop_smoke.run_process", side_effect=fake_process):
            a = run(Path("unused.exe"), "1.0", True)
            b = run(Path("unused.exe"), "1.25", True)
        self.assertNotEqual(roots[0], roots[1])
        self.assertTrue(all(not root.exists() for root in roots))
        self.assertEqual(["test-state"], a["isolatedFiles"])
        self.assertEqual(["test-state"], b["isolatedFiles"])


if __name__ == "__main__":
    unittest.main()