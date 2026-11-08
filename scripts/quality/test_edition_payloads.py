"""Containment checks exercise accidental native, asset and bytecode leaks independently."""
from pathlib import Path
import tempfile
import unittest

import check_release_content as content
import static_checks
from test_check_release_content import manifest, component, launcher_alias, plus_launchers, LAUNCHER

RUNTIME = ["lib/arm64-v8a/libonnxruntime.so", "lib/arm64-v8a/libonnxruntime4j_jni.so"]
MODEL = "assets/automix/beat-this-small0-int8.onnx"
SING = "assets/sing/spleeter-2stems.onnx"
PROFILES = "assets/autoeq/profiles.tsv"
BINDINGS = b"fixture Lai/onnxruntime/OrtEnvironment;"
RIFF_LAUNCHER = component("activity", "com.musicglass.personal.MainActivity", "true", extra=LAUNCHER)
CAMERA_FEATURE = "\n".join([
    "      E: uses-feature (line=8)",
    '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.hardware.camera.any" (Raw: "android.hardware.camera.any")',
    "        A: http://schemas.android.com/apk/res/android:required(0x0101028e)=false"])


def edition_manifest(edition, body, camera=None):
    """A manifest as each edition ships it: Riff+ asks for the camera (feature 24) without requiring one; Riff does not."""
    camera = edition == "plus" if camera is None else camera
    permissions = sorted(name.format(package="com.musicglass.personal") for name in content.PERMISSIONS | (content.PLUS_PERMISSIONS if camera else set()))
    return manifest(body=body, permissions=permissions, extra=CAMERA_FEATURE if camera else "")


class EditionPayloadTest(unittest.TestCase):
    def passing(self, names, dex, edition, size=7 * 1024 * 1024):
        return all(ok for ok, _ in content.check_edition_payload(names, dex, edition, size))

    def test_light_has_no_model_native_code_or_java_bindings(self):
        self.assertTrue(self.passing([], [], "riff"))
        for names, dex in (([RUNTIME[0]], []), ([RUNTIME[1]], []), ([MODEL], []), ([SING], []), ([], [BINDINGS]), ([PROFILES], [])):
            with self.subTest(names=names, dex=dex):
                self.assertFalse(self.passing(names, dex, "riff"))

    def test_runtime_on_another_abi_cannot_escape_the_light_check(self):
        self.assertFalse(self.passing(["lib/x86_64/libonnxruntime4j_jni.so"], [], "riff"))

    def test_plus_requires_both_native_parts_the_model_and_the_bindings(self):
        names = RUNTIME + [MODEL, SING, PROFILES]
        self.assertTrue(self.passing(names, [BINDINGS], "plus"))
        for missing in names:
            with self.subTest(missing=missing):
                self.assertFalse(self.passing([name for name in names if name != missing], [BINDINGS], "plus"))
        self.assertFalse(self.passing(names, [], "plus"))
        self.assertFalse(self.passing(names + ["assets/sing/other.onnx"], [BINDINGS], "plus"), "an unreviewed model")

    def test_each_edition_enforces_its_own_ceiling(self):
        self.assertFalse(self.passing([], [], "riff", size=16 * 1024 * 1024 + 1))
        self.assertTrue(self.passing(RUNTIME + [MODEL, SING, PROFILES], [BINDINGS], "plus", size=20 * 1024 * 1024))
        self.assertTrue(self.passing(RUNTIME + [MODEL, SING, PROFILES], [BINDINGS], "plus", size=400 * 1024 * 1024),
                        "Riff+ has no size limit (owner, 8 November 2026)")

    def test_a_model_cannot_hide_outside_the_automix_directory(self):
        self.assertFalse(self.passing(["assets/models/hidden.ONNX"], [], "riff"))

    def test_compiled_labels_match_in_every_resource_locale(self):
        for edition, name in (("riff", "Riff"), ("plus", "Riff+")):
            good = f"application-label:'{name}'\napplication-label-fr:'{name}'"
            self.assertTrue(all(ok for ok, _ in content.check_edition_label(good, edition)))
            for bad in ("", good + "\napplication-label-ja:'Wrong edition'"):
                self.assertFalse(all(ok for ok, _ in content.check_edition_label(bad, edition)))

    def test_the_runtime_telemetry_initializer_is_forbidden_in_both_editions(self):
        body = component("receiver", "com.musicglass.personal.EncoreWidget", "false") + "\n" + component("service", "com.musicglass.personal.EncoreRecapJob", "false")
        body += "\n" + component("provider", "ai.onnxruntime.TelemetryInitializer", "false")
        for edition in ("riff", "plus"):
            results = content.check_edition_components(manifest(body=body), edition)
            self.assertTrue(any(not ok and "telemetry" in text for ok, text in results))

    def test_both_editions_must_keep_the_whole_encore_system(self):
        body = component("receiver", "com.musicglass.personal.EncoreWidget", "false") + "\n" + component("service", "com.musicglass.personal.EncoreRecapJob", "false")
        for edition in ("riff", "plus"):
            launchers = plus_launchers() if edition == "plus" else RIFF_LAUNCHER
            self.assertTrue(all(ok for ok, _ in content.check_edition_components(edition_manifest(edition, body + "\n" + launchers), edition)))
            self.assertFalse(all(ok for ok, _ in content.check_edition_components(edition_manifest(edition, launchers), edition)))

    def test_riff_plus_has_every_icon_entry_and_one_launcher_riff_has_none(self):
        encore = component("receiver", "com.musicglass.personal.EncoreWidget", "false") + "\n" + component("service", "com.musicglass.personal.EncoreRecapJob", "false")

        def ok(edition, launchers):
            return all(passed for passed, _ in content.check_edition_components(edition_manifest(edition, encore + "\n" + launchers), edition))
        self.assertTrue(ok("plus", plus_launchers()))
        self.assertTrue(ok("riff", RIFF_LAUNCHER))
        self.assertFalse(ok("plus", plus_launchers(drop="com.musicglass.personal.LauncherASilver")), "a missing icon entry")
        self.assertFalse(ok("plus", plus_launchers() + "\n" + RIFF_LAUNCHER), "MainActivity kept its own launcher entry")
        self.assertFalse(ok("plus", plus_launchers(enabled="com.musicglass.personal.LauncherDBlue", drop=content.LAUNCHER_DEFAULT) + "\n"
                            + launcher_alias(content.LAUNCHER_DEFAULT, "true")), "two enabled launchers")
        self.assertFalse(ok("riff", RIFF_LAUNCHER + "\n" + plus_launchers()), "Riff with Riff+'s icon entries")
        self.assertFalse(ok("riff", ""), "Riff without a launcher entry")

    def test_only_riff_plus_asks_for_the_camera_and_never_requires_one(self):
        encore = component("receiver", "com.musicglass.personal.EncoreWidget", "false") + "\n" + component("service", "com.musicglass.personal.EncoreRecapJob", "false")

        def ok(edition, camera):
            launchers = plus_launchers() if edition == "plus" else RIFF_LAUNCHER
            return all(passed for passed, _ in content.check_edition_components(edition_manifest(edition, encore + "\n" + launchers, camera), edition))
        self.assertTrue(ok("plus", True))
        self.assertTrue(ok("riff", False))
        self.assertFalse(ok("plus", False), "Riff+ without the blend scanner's camera")
        self.assertFalse(ok("riff", True), "Riff with a camera permission")
        required = manifest(body=encore + "\n" + plus_launchers(), permissions=sorted(content.PERMISSIONS | content.PLUS_PERMISSIONS),
                            extra=CAMERA_FEATURE.replace("=false", "=true"))
        self.assertFalse(all(passed for passed, _ in content.check_edition_components(required, "plus")), "a camera required to install")
        # The edition-neutral manifest check leaves the camera to the edition check.
        problems = [text for good, text in content.check_manifest(edition_manifest("plus", ""), "com.musicglass.personal", None) if not good]
        self.assertFalse(any("permissions" in text for text in problems), problems)


class EditionSourceBoundaryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def source(self, edition, name, text):
        path = self.root / f"app/src/{edition}/java/{name}.kt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_only_plus_may_import_the_runtime(self):
        self.source("plus", "Runtime", "import ai.onnxruntime.OrtSession\nclass Runtime")
        self.assertEqual([], static_checks.edition_boundaries(self.root))
        self.source("main", "Leak", "import ai.onnxruntime.OrtSession")
        self.assertTrue(any("outside src/plus" in issue for issue in static_checks.edition_boundaries(self.root)))

    def test_a_light_reference_to_a_plus_only_class_fails(self):
        self.source("plus", "Runtime", "class PlusRuntime")
        self.source("riff", "Leak", "val engine = PlusRuntime()")
        self.assertTrue(any("Plus-only PlusRuntime" in issue for issue in static_checks.edition_boundaries(self.root)))

    def test_a_real_neutral_provider_and_documentation_are_allowed(self):
        self.source("plus", "BeatModel", "object BeatModel\nclass NativeEngine")
        self.source("riff", "BeatModel", 'object BeatModel { fun get() = null } // NativeEngine\nval documentation = "NativeEngine"')
        self.assertEqual([], static_checks.edition_boundaries(self.root))
