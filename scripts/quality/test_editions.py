"""Edition migration: verify that both exact APKs are required and failures cannot mask each other."""
import json
from pathlib import Path
import tempfile
import unittest

import verify
from test_verify import Args


class EditionLaneTest(unittest.TestCase):
    def run_pr(self, plus_gate=None):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        lane = verify.Lane(Args(evidence_dir=str(root / "evidence")), root=root)
        calls = []
        for edition in ("riff", "plus"):
            folder = root / f"app/build/outputs/apk/{edition}/release"
            folder.mkdir(parents=True)
            (folder / f"app-{edition}-release-unsigned.apk").write_bytes(b"fixture")
        lane.sdk_tool = lambda *args: root / "aapt2"

        def scripted_run(command, log, **kwargs):
            calls.append([str(part) for part in command])
            if "scripts/quality/collect_results.py" in command:
                edition = command[command.index("--edition") + 1]
                gates = {name: dict(status="PASS") for name in ("build", "artifactIdentity", "jvm", "lint")}
                if edition == "plus" and plus_gate:
                    gates[plus_gate] = dict(status="FAIL", reason="synthetic edition regression")
                manifest = root / f"{edition}-manifest.json"
                manifest.write_text(json.dumps(dict(gates=gates)), encoding="utf-8")
                (lane.evidence / log).write_text(str(manifest) + "\n", encoding="utf-8")
            return 0

        lane.run = scripted_run
        lane.pr()
        return lane, calls

    def test_pr_requires_both_exact_release_and_test_variants(self):
        lane, calls = self.run_pr()
        self.assertEqual("PASS_LOCAL", verify.summarize("pr", lane.gates)["status"])
        collectors = [call for call in calls if "scripts/quality/collect_results.py" in call]
        self.assertEqual(["riff", "plus"], [call[call.index("--edition") + 1] for call in collectors])
        content = [call for call in calls if "scripts/quality/check_release_content.py" in call]
        self.assertEqual(2, len(content))
        for call, edition in zip(content, ("riff", "plus")):
            self.assertIn(f"/{edition}/release/", call[call.index("--apk") + 1].replace("\\", "/"))
        self.assertEqual({"riff", "plus"}, set(lane.gates["artifactIdentity"]["editions"]))

    def test_a_plus_failure_fails_the_lane_even_when_riff_passes(self):
        for gate in ("build", "artifactIdentity", "jvm", "lint"):
            with self.subTest(gate=gate):
                lane, _ = self.run_pr(plus_gate=gate)
                self.assertEqual("FAIL", verify.summarize("pr", lane.gates)["status"])
                self.assertEqual("PASS", lane.gates[gate]["editions"]["riff"]["status"])
                self.assertEqual("FAIL", lane.gates[gate]["editions"]["plus"]["status"])

    def test_an_ambiguous_release_apk_is_refused(self):
        lane, _ = self.run_pr()
        lane.release_apk("plus").with_name("stale-signed.apk").write_bytes(b"stale")
        with self.assertRaises(verify.Failed):
            lane.release_apk("plus")
