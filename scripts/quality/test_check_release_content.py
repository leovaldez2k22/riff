import unittest

import check_release_content as content

A = "http://schemas.android.com/apk/res/android:"


def manifest(application="", body="", permissions=None, package="com.musicglass.personal", extra=""):
    """An `aapt2 dump xmltree` of a manifest, as the tool prints it."""
    permissions = sorted(name.format(package=package) for name in content.PERMISSIONS) if permissions is None else permissions
    lines = ["N: android=http://schemas.android.com/apk/res/android (line=2)", "  E: manifest (line=2)",
             f'    A: package="{package}" (Raw: "{package}")', extra]
    for name in permissions:
        lines += ["      E: uses-permission (line=9)", f'        A: {A}name(0x01010003)="{name}" (Raw: "{name}")']
    lines += ["      E: application (line=20)", f"        A: {A}allowBackup(0x01010280)=false",
              f"        A: {A}usesCleartextTraffic(0x010104ec)=false", f"        A: {A}dataExtractionRules(0x0101063e)=@0x7f130000",
              application, component("activity", "com.musicglass.personal.MainActivity", "true"),
              component("service", "com.musicglass.personal.MusicPlaybackService", "true"),
              component("activity", "androidx.media3.session.BluetoothValidationActivity", "true", "android.permission.BLUETOOTH_PRIVILEGED"),
              component("receiver", "androidx.profileinstaller.ProfileInstallReceiver", "true", "android.permission.DUMP"),
              component("service", "com.musicglass.personal.SolverService", "false"), body]
    return content.parse_tree("\n".join(line for line in lines if line))


def component(kind, name, exported=None, permission=None, extra=""):
    lines = [f"          E: {kind} (line=30)", f'            A: {A}name(0x01010003)="{name}" (Raw: "{name}")']
    if permission:
        lines.append(f'            A: {A}permission(0x01010006)="{permission}" (Raw: "{permission}")')
    if exported is not None:
        lines.append(f"            A: {A}exported(0x01010010)={exported}")
    return "\n".join(lines + ([extra] if extra else []))


RULES = content.parse_tree("""E: data-extraction-rules (line=2)
    E: cloud-backup (line=3)
        E: exclude (line=4)
          A: domain="root" (Raw: "root")
          A: path="." (Raw: ".")
    E: device-transfer (line=6)
        E: exclude (line=7)
          A: domain="root" (Raw: "root")
          A: path="." (Raw: ".")""")


def failures(tree, rules=RULES, package="com.musicglass.personal"):
    return [text for ok, text in content.check_manifest(tree, package, rules) if not ok]


class ManifestChecks(unittest.TestCase):
    def test_the_reviewed_manifest_passes_every_check(self):
        self.assertEqual([], failures(manifest()))

    def test_attributes_are_read_with_and_without_a_resource_id(self):
        tree = manifest()
        self.assertEqual("com.musicglass.personal", tree.attributes["package"])
        self.assertEqual("false", tree.all("application")[0].get("allowBackup"))
        self.assertEqual("@0x7f130000", tree.all("application")[0].get("dataExtractionRules"))

    def test_backup_and_transfer_must_be_off_and_exclude_everything(self):
        self.assertTrue(any("backup disabled" in text for text in failures(content.parse_tree(
            "E: manifest (line=1)\n  A: package=\"com.musicglass.personal\" (Raw: \"x\")\n    E: application (line=2)\n"
            f"      A: {A}allowBackup(0x01010280)=true\n      A: {A}usesCleartextTraffic(0x010104ec)=false"))))
        self.assertTrue(any("exclude all app data" in text for text in failures(manifest(), rules=None)))
        partial = content.parse_tree("""E: data-extraction-rules (line=2)
    E: cloud-backup (line=3)
        E: exclude (line=4)
          A: domain="root" (Raw: "root")
          A: path="." (Raw: ".")""")
        self.assertTrue(any("exclude all app data" in text for text in failures(manifest(), rules=partial)))
        with_include = content.parse_tree("""E: data-extraction-rules (line=2)
    E: cloud-backup (line=3)
        E: include (line=4)
          A: domain="sharedpref" (Raw: "sharedpref")
        E: exclude (line=5)
          A: domain="root" (Raw: "root")
          A: path="." (Raw: ".")
    E: device-transfer (line=6)
        E: exclude (line=7)
          A: domain="root" (Raw: "root")
          A: path="." (Raw: ".")""")
        self.assertTrue(any("exclude all app data" in text for text in failures(manifest(), rules=with_include)))
        self.assertTrue(any("legacy backup" in text for text in failures(manifest(f"        A: {A}fullBackupContent(0x010104eb)=@0x7f130009"))))

    def test_cleartext_debuggable_test_only_and_shared_user_fail(self):
        self.assertTrue(any("network security" in text for text in failures(manifest(f"        A: {A}networkSecurityConfig(0x01010527)=@0x7f130002"))))
        self.assertTrue(any("not debuggable" in text for text in failures(manifest(f"        A: {A}debuggable(0x0101000f)=true"))))
        self.assertTrue(any("test-only" in text for text in failures(manifest(f"        A: {A}testOnly(0x01010272)=true"))))
        self.assertTrue(any("shared user" in text for text in failures(manifest(extra=f'    A: {A}sharedUserId(0x0101000b)="x.y" (Raw: "x.y")'))))
        shell = "          E: profileable (line=40)\n            A: " + A + "shell(0x01010594)=true"
        self.assertTrue(any("profileable" in text for text in failures(manifest(body=shell))))

    def test_a_new_exported_component_or_a_lost_permission_guard_fails(self):
        self.assertTrue(any("reviewed four" in text for text in failures(manifest(body=component("activity", "com.musicglass.personal.ShareActivity", "true")))))
        unguarded = manifest()
        receiver = [item for item in unguarded.all("receiver") if item.get("name").endswith("ProfileInstallReceiver")][0]
        del receiver.attributes[A + "permission"]
        self.assertTrue(any("reviewed four" in text for text in failures(unguarded)))
        unexported = manifest()
        [item for item in unexported.all("service") if item.get("name").endswith("MusicPlaybackService")][0].attributes[A + "exported"] = "false"
        self.assertTrue(any("reviewed four" in text for text in failures(unexported)))
        implicit = component("receiver", "com.musicglass.personal.Boot", None, extra="              E: intent-filter (line=41)")
        self.assertTrue(any("exported is stated" in text for text in failures(manifest(body=implicit))))

    def test_providers_may_not_be_exported_or_grant_access(self):
        self.assertTrue(any("provider" in text for text in failures(manifest(body=component("provider", "x.Files", "true")))))
        granting = component("provider", "x.Files", "false", extra=f"            A: {A}grantUriPermissions(0x0101001b)=true")
        self.assertTrue(any("provider" in text for text in failures(manifest(body=granting))))
        self.assertEqual([], failures(manifest(body=component("provider", "androidx.startup.InitializationProvider", "false"))))

    def test_permissions_must_be_exactly_the_reviewed_list(self):
        wanted = sorted(name.format(package="com.musicglass.personal") for name in content.PERMISSIONS)
        self.assertTrue(any("added ['android.permission.READ_CONTACTS']" in text for text in failures(manifest(permissions=wanted + ["android.permission.READ_CONTACTS"]))))
        self.assertTrue(any("missing" in text for text in failures(manifest(permissions=wanted[1:]))))

    def test_test_stress_and_benchmark_entries_fail(self):
        for name in ("com.musicglass.personal.FeatureTestActivity", "com.musicglass.personal.StressDiagnostics", "com.musicglass.personal.BenchmarkFeed"):
            self.assertTrue(any("entry in the manifest" in text for text in failures(manifest(body=component("activity", name, "false")))), name)
        self.assertTrue(any("instrumentation" in text for text in failures(manifest(body="      E: instrumentation (line=50)"))))

    def test_the_package_must_match(self):
        self.assertTrue(any("package is" in text for text in failures(manifest(package="com.musicglass.personal.benchmark"))))


class FileChecks(unittest.TestCase):
    def failed(self, names, dex=()):
        return [text for ok, text in content.check_files(names, list(dex)) if not ok]

    def test_a_plain_release_passes_and_the_build_identity_file_is_allowed(self):
        self.assertEqual([], self.failed(["classes.dex", "assets/quality/build-identity.json", "assets/dexopt/baseline.prof", "res/4j.xml"], [b"Lcom/musicglass/personal/MainActivity;"]))

    def test_fixture_and_benchmark_files_and_classes_fail(self):
        self.assertTrue(self.failed(["assets/quality/audio/tone-a.wav"]))
        self.assertTrue(self.failed(["assets/quality/provider/home.json"]))
        self.assertTrue(self.failed(["assets/home.json"]))
        self.assertTrue(self.failed(["res/xml/fixture_network_security_config.xml"]))
        for mark in content.FOREIGN_DEX:
            self.assertTrue(self.failed(["classes.dex"], [b"\x00" + mark + b"\x00"]), mark)

    def test_key_and_credential_files_fail(self):
        for name in ("riff-release.jks", "assets/debug.keystore", "release-signing.properties", "META-INF/x.p12", "assets/.env", "assets/.env.local",
                     "local.properties", "assets/google-services.json", "assets/id_rsa", "server.pem", "private.key"):
            self.assertTrue(self.failed([name]), name)
        self.assertEqual([], self.failed(["assets/environment.json", "kotlin/kotlin.kotlin_builtins", "META-INF/androidx.core_core.version"]))


class CertificateChecks(unittest.TestCase):
    PIN = "ac" * 32

    def test_an_unsigned_apk_is_not_a_pass(self):
        ok, text = content.check_certificate("", False, self.PIN)
        self.assertIsNone(ok)
        self.assertIn("not checked", text)
        self.assertFalse(content.is_signed(["META-INF/MANIFEST.MF", "classes.dex"]))
        self.assertTrue(content.is_signed(["META-INF/RIFF.RSA"]))

    def test_the_pinned_certificate_passes_and_any_other_fails(self):
        line = "Signer #1 certificate SHA-256 digest: "
        self.assertEqual(True, content.check_certificate(line + self.PIN, True, self.PIN)[0])
        self.assertEqual(True, content.check_certificate(line + self.PIN.upper(), True, self.PIN)[0])
        self.assertEqual(False, content.check_certificate(line + "bd" * 32, True, self.PIN)[0])
        self.assertEqual(False, content.check_certificate("", True, self.PIN)[0])
        two = line + self.PIN + "\nSigner #2 certificate SHA-256 digest: " + "bd" * 32
        self.assertEqual(False, content.check_certificate(two, True, self.PIN)[0])

    def test_the_pin_is_read_from_the_signing_document(self):
        self.assertEqual(self.PIN, content.pinned_certificate("text\nCertificate SHA-256: " + self.PIN + "\nmore"))
        with self.assertRaises(content.ContentError):
            content.pinned_certificate("no pin here")


class Parsing(unittest.TestCase):
    def test_a_resource_reference_is_resolved_to_its_file(self):
        dump = "    resource 0x7f130000 xml/data_extraction_rules\n      () (file) res/4j.xml type=XML\n    resource 0x7f130001 xml/other\n      () (file) res/VZ.xml type=XML\n"
        self.assertEqual("res/4j.xml", content.resource_file(dump, "@0x7f130000"))
        with self.assertRaises(content.ContentError):
            content.resource_file(dump, "@0x7f139999")

    def test_an_empty_or_broken_dump_is_an_error_not_a_pass(self):
        with self.assertRaises(content.ContentError):
            content.parse_tree("")
        with self.assertRaises(content.ContentError):
            content.parse_tree("E: manifest (line=1)\n  A: nonsense")


if __name__ == "__main__":
    unittest.main()
