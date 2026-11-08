from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile
import verify


class ArchiveCorrespondenceTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.files = {name: b"fixture" for name in verify.SOURCE_REQUIRED}
        self.files.update({"app/src/plus/assets/automix/model.onnx": b"model", "app/src/riff/java/Edition.kt": b"neutral", "gradle/libs.versions.toml": b"versions"})
        for name, value in self.files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(value)

    def archive(self, files=None, duplicate=None):
        path = self.root / "source.zip"
        with zipfile.ZipFile(path, "w") as out:
            for name, value in (self.files if files is None else files).items():
                out.writestr(name, value)
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    out.writestr(duplicate, b"fixture")
        return path

    def test_complete_current_source_passes(self):
        self.assertEqual([], verify.check_source_archive(self.archive(), self.root))

    def test_omitted_flavor_model_or_neutral_seam_fails(self):
        for name in ("app/src/plus/assets/automix/model.onnx", "app/src/riff/java/Edition.kt"):
            files = dict(self.files); files.pop(name)
            self.assertIn("missing fingerprint input: " + name, verify.check_source_archive(self.archive(files), self.root))

    def test_stale_bytes_fail_even_when_length_matches(self):
        files = dict(self.files); files["app/src/riff/java/Edition.kt"] = b"changed"
        self.assertIn("source differs from current fingerprint input: app/src/riff/java/Edition.kt", verify.check_source_archive(self.archive(files), self.root))

    def test_a_removed_source_left_in_the_zip_fails(self):
        files = dict(self.files); files["app/src/main/java/Removed.kt"] = b"old"
        self.assertIn("unexpected fingerprint input: app/src/main/java/Removed.kt", verify.check_source_archive(self.archive(files), self.root))

    def test_duplicate_names_fail(self):
        self.assertIn("duplicate source entry: LICENSE", verify.check_source_archive(self.archive(duplicate="LICENSE"), self.root))

    def test_case_collisions_cannot_overwrite_source_on_windows(self):
        files = dict(self.files); files["APP/src/riff/java/Edition.kt"] = b"changed"
        problems = verify.check_source_archive(self.archive(files), self.root)
        self.assertIn("duplicate source entry: APP/src/riff/java/Edition.kt", problems)
        self.assertIn("unexpected fingerprint input: APP/src/riff/java/Edition.kt", problems)

    def test_unsafe_paths_and_secrets_fail_without_extraction(self):
        for name in ("C:/escape.kt", "../escape.kt", "local.properties", ".toolchain/signing/key.p12"):
            files = dict(self.files); files[name] = b"refuse"
            self.assertTrue(verify.check_source_archive(self.archive(files), self.root))

    def test_factoring_source_inputs_keeps_the_fingerprint(self):
        import hashlib
        digest = hashlib.sha256()
        for name in sorted(n for n in self.files if verify.collect_results.is_source_input(n)):
            digest.update(name.encode()); digest.update(b"\0"); digest.update(self.files[name]); digest.update(b"\0")
        self.assertEqual(digest.hexdigest(), verify.collect_results.source_digest(self.root))


class ReleaseSourceCorrespondenceTest(unittest.TestCase):
    def test_only_the_current_source_and_exact_edition_can_pass(self):
        expected = dict(sourceDigest="a" * 64, revision="b" * 40, sourceState="dirty")
        for edition in ("riff", "plus"):
            valid = dict(expected, variant=edition + "Release")
            self.assertEqual([], verify.release_source_problems(valid, expected, edition))
            for field, value in (("sourceDigest", "c" * 64), ("revision", "d" * 40), ("sourceState", "clean"), ("variant", edition + "Preview")):
                invalid = dict(valid); invalid[field] = value
                self.assertTrue(verify.release_source_problems(invalid, expected, edition), field)
            other = "plus" if edition == "riff" else "riff"
            self.assertTrue(verify.release_source_problems(valid, expected, other))


if __name__ == "__main__":
    unittest.main()
