import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

_SCRIPT = Path(__file__).resolve().parents[1] / "desktop" / "evidence-manifest.py"
_SPEC = importlib.util.spec_from_file_location("evidence_manifest", _SCRIPT)
evidence = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(evidence)


def _tree(root: Path) -> None:
    files = {
        "phase3/soak/evidence/result.json": '{"status":"PASS"}',
        "phase3/acceptance/run.log": "line\n",
        "phase1/ui-review/shot.png": "png-bytes",
        "phase3/soak/runtime-123/lib/app.json": "{}",          # staged runtime copy
        "phase3/soak/runtime-123/classes/a.json": "{}",        # staged runtime copy
        "phase3/soak/runtime-123/evidence/result.json": "{}",  # soak evidence inside a runtime
        "phase3/soak/lib/notes.txt": "kept",                   # lib outside a runtime is not special
        "phase1/negative-image-abc/app/config.json": "{}",      # copied packaged image
        "phase1/corpus/track.json": "{}",                       # test input
        "phase3/automix-recorded-corpus/a.json": "{}",          # test input
        "phase3/night-ui/audio-cache/index.json": "{}",         # cache
        "phase3/acceptance/library.sqlite": "db",               # database
        "phase3/corpus-notes/track.flac": "audio",              # audio, wrong extension
    }
    for relative, text in files.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


class EvidenceManifestTest(unittest.TestCase):
    def setUp(self):
        self._dir = tempfile.TemporaryDirectory(prefix="riff-evidence-test-")
        self.root = Path(self._dir.name) / "evidence with spaces"
        self.manifest = Path(self._dir.name) / "manifest.json"
        _tree(self.root)

    def tearDown(self):
        self._dir.cleanup()

    def test_records_only_evidence_with_relative_paths(self):
        data = evidence.write_manifest(self.root, self.manifest)
        paths = [entry["path"] for entry in data["files"]]
        self.assertEqual(["phase1/ui-review/shot.png", "phase3/acceptance/run.log",
                          "phase3/soak/evidence/result.json", "phase3/soak/lib/notes.txt",
                          "phase3/soak/runtime-123/evidence/result.json"], paths)
        text = self.manifest.read_text(encoding="utf-8")
        self.assertNotIn(self._dir.name, text)
        self.assertNotIn("\\", text)

    def test_unchanged_tree_passes(self):
        evidence.write_manifest(self.root, self.manifest)
        result = evidence.check(self.root, evidence.load_manifest(self.manifest))
        self.assertEqual({"missing": [], "changed": [], "unrecorded": []}, result)
        self.assertEqual(0, evidence.main(["check", "--root", str(self.root), "--manifest", str(self.manifest)]))

    def test_changed_file_fails(self):
        evidence.write_manifest(self.root, self.manifest)
        (self.root / "phase3/acceptance/run.log").write_text("edited\n", encoding="utf-8")
        result = evidence.check(self.root, evidence.load_manifest(self.manifest))
        self.assertEqual(["phase3/acceptance/run.log"], result["changed"])
        self.assertEqual(1, evidence.main(["check", "--root", str(self.root), "--manifest", str(self.manifest)]))

    def test_same_size_change_fails(self):
        evidence.write_manifest(self.root, self.manifest)
        (self.root / "phase3/soak/evidence/result.json").write_text('{"status":"FAI"}', encoding="utf-8")
        result = evidence.check(self.root, evidence.load_manifest(self.manifest))
        self.assertEqual(["phase3/soak/evidence/result.json"], result["changed"])

    def test_missing_file_fails_and_new_file_does_not(self):
        evidence.write_manifest(self.root, self.manifest)
        (self.root / "phase1/ui-review/shot.png").unlink()
        (self.root / "phase3/acceptance/later.log").write_text("new\n", encoding="utf-8")
        result = evidence.check(self.root, evidence.load_manifest(self.manifest))
        self.assertEqual(["phase1/ui-review/shot.png"], result["missing"])
        self.assertEqual(["phase3/acceptance/later.log"], result["unrecorded"])

    def test_paths_outside_root_are_rejected(self):
        for bad in ("../secret.json", "/etc/passwd"):
            self.manifest.write_text(json.dumps({"version": 1, "files": [{"path": bad, "bytes": 1, "sha256": "0"}]}),
                                     encoding="utf-8")
            with self.assertRaises(ValueError):
                evidence.load_manifest(self.manifest)

    def test_missing_root_is_an_error(self):
        with self.assertRaises(FileNotFoundError):
            evidence.collect(self.root / "absent")


if __name__ == "__main__":
    unittest.main()
