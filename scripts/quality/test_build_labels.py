"""Q18-060: every build that installs beside Riff on the owner's phone has a launcher name of its own."""
import pathlib
import re
import unittest

APP = pathlib.Path(__file__).resolve().parents[2] / "app" / "src"
# Source set -> the name the owner sees. Debug and stress take theirs from a Gradle placeholder.
OWN_LABELS = {"preview": "Riff Preview", "benchmarkFeed": "Riff Benchmark"}
PLACEHOLDERS = {"debug": ("riffDebugLabel", "Riff Debug"), "stress": ("riffStressLabel", "Riff Stress")}


def label_ref(source_set):
    manifest = (APP / source_set / "AndroidManifest.xml").read_text(encoding="utf-8")
    application = re.search(r"<application\b[^>]*>", manifest, re.S).group(0)
    assert 'tools:replace="android:label"' in application, f"{source_set} does not replace the label"
    return re.search(r'android:label="([^"]+)"', application).group(1)


class BuildLabelsTest(unittest.TestCase):
    def test_each_side_by_side_build_names_itself(self):
        for source_set, name in OWN_LABELS.items():
            ref = label_ref(source_set)
            self.assertTrue(ref.startswith("@string/"), source_set)
            strings = "".join(p.read_text(encoding="utf-8") for p in (APP / source_set / "res" / "values").glob("*.xml"))
            self.assertIn(f'<string name="{ref[len("@string/"):]}">{name}</string>', strings, source_set)

    def test_debug_and_stress_name_themselves_side_by_side(self):
        gradle = (APP.parent / "build.gradle.kts").read_text(encoding="utf-8")
        for source_set, (placeholder, name) in PLACEHOLDERS.items():
            self.assertEqual("${" + placeholder + "}", label_ref(source_set))
            self.assertIn(f'manifestPlaceholders["{placeholder}"] = if (sideBySide) "{name}"', gradle)

    def test_no_two_names_alike_and_none_is_riff(self):
        names = list(OWN_LABELS.values()) + [name for _, name in PLACEHOLDERS.values()]
        self.assertEqual(len(names), len(set(names)))
        self.assertNotIn("Riff", names)


if __name__ == "__main__":
    unittest.main()
