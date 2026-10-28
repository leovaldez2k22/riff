"""Q15: what a release APK is allowed to contain, read from the APK itself.

  check_release_content.py --apk app-release.apk --aapt2 <sdk>/build-tools/<v>/aapt2
                           [--apksigner <sdk>/build-tools/<v>/apksigner] [--signing-doc docs/release-signing.md]
                           [--package com.musicglass.personal]

Checks, each printed as "ok" or "FAIL": backup and device transfer excluded, cleartext off with no network
security override, not debuggable and not test-only, the exported components and the permissions are exactly
the reviewed lists below, no provider is exported and only the reviewed share provider grants URI access (Q18-043),
nothing from the stress, fixture or benchmark builds is inside, no key or credential file is inside, and, for a
signed APK, the certificate is the one pinned in docs/release-signing.md. With --apksigner, apksigner decides
whether the APK is signed, under any scheme (Q18-047: Riff's releases carry a v2 signature only). An unsigned APK
(every local and CI build), or one checked without --apksigner, reports the certificate as "not checked": that is
not a pass for the certificate.

The lists are the review. A new exported component or permission fails here until someone adds it on purpose.
Exit status: 0 every check ok, 2 a check failed, 3 the tools could not read the APK.
"""
import argparse
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android:"

# Exported components, and the permission a caller must hold (None: anyone may call; see security-review.md).
EXPORTED = {
    "com.musicglass.personal.MainActivity": None,
    "com.musicglass.personal.MusicPlaybackService": None,
    "androidx.media3.session.BluetoothValidationActivity": "android.permission.BLUETOOTH_PRIVILEGED",
    "androidx.profileinstaller.ProfileInstallReceiver": "android.permission.DUMP",
    # Feature 27: only the system, holding this permission, can bind a Quick Settings tile.
    "com.musicglass.personal.PlaybackTile": "android.permission.BIND_QUICK_SETTINGS_TILE",
}
PERMISSIONS = {
    "android.permission.RECORD_AUDIO", "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK", "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE", "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.POST_NOTIFICATIONS",
    "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WAKE_LOCK", "{package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}
# Q18-043: the one provider that may grant URI access, the lyric card's share (F2): androidx's FileProvider at
# <package>.files, not exported, whose paths file names only the shared_lyrics folder of the cache.
SHARE_PROVIDER = "androidx.core.content.FileProvider"
SHARE_PATHS = [("cache-path", "shared_lyrics", "shared_lyrics/")]
# Names that exist only in test, stress, fixture or benchmark code.
FOREIGN_MANIFEST = ("FeatureTestActivity", "FeaturePreviewActivity", "StressDiagnostics", "BenchmarkFeed",
                    "benchmark-feed", "androidx.test", "android.test.runner", "instrumentation")
FOREIGN_DEX = (b"FixtureHttpServer", b"ScriptedResolver", b"FakeCatalog", b"FakeSession", b"FakeClock",
               b"FixturePlaybackTest", b"StressDiagnostics", b"BenchmarkFeed", b"ToneServer",
               b"Lcom/musicglass/personal/benchmark/", b"Landroidx/test/", b"Lorg/junit/", b"Lorg/robolectric/")
FOREIGN_FILES = ("assets/quality/audio/", "assets/quality/provider/", "assets/home.json", "assets/playlist-owned.json", "assets/watch-next.json",
                 "fixture_network_security_config", "benchmark_network_security_config")
SECRET_FILES = re.compile(r"(\.jks|\.keystore|\.p12|\.pfx|\.pem|\.key|(^|/)\.env[^/]*|release-signing\.properties|"
                          r"local\.properties|google-services\.json|(^|/)id_[rd]sa[^/]*)$", re.IGNORECASE)


class ContentError(Exception):
    pass


class Element:
    def __init__(self, name):
        self.name, self.attributes, self.children = name, {}, []

    def get(self, attribute):
        return self.attributes.get(ANDROID + attribute, self.attributes.get(attribute))

    def all(self, name):
        found = [self] if self.name == name else []
        for child in self.children:
            found.extend(child.all(name))
        return found


def parse_tree(text):
    """The element tree that `aapt2 dump xmltree` prints, by indentation."""
    root, stack = None, []
    for raw in text.splitlines():
        line = raw.strip()
        indent = len(raw) - len(raw.lstrip(" "))
        if line.startswith("E: "):
            element = Element(line[3:].split(" (line=")[0].strip())
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if stack:
                stack[-1][1].children.append(element)
            elif root is None:
                root = element
            else:
                raise ContentError("More than one root element")
            stack.append((indent, element))
        elif line.startswith("A: ") and stack:
            match = re.match(r"A: (\S+?)(?:\(0x[0-9a-f]+\))?=(.*)$", line)
            if not match:
                raise ContentError("Unreadable attribute: " + line[:80])
            value = match.group(2)
            quoted = re.match(r'"(.*)" \(Raw: ".*"\)$', value)
            stack[-1][1].attributes[match.group(1)] = quoted.group(1) if quoted else value
    if root is None:
        raise ContentError("No element in the dump")
    return root


def resource_file(resources, reference):
    """The file inside the APK that a resource reference such as @0x7f130000 names."""
    match = re.search(r"resource " + re.escape(reference.lstrip("@")) + r" \S+\n\s+\(.*?\) \(file\) (\S+)", resources)
    if not match:
        raise ContentError("Resource " + reference + " is not a file")
    return match.group(1)


def excludes_everything(rules):
    """True when cloud backup and device transfer each exclude the whole of the app's data and include nothing."""
    for section in ("cloud-backup", "device-transfer"):
        found = rules.all(section)
        if len(found) != 1:
            return False
        children = found[0].children
        if any(child.name != "exclude" for child in children):
            return False
        if not any(child.get("domain") == "root" and child.get("path") == "." for child in children):
            return False
    return True


def share_paths_reference(provider):
    """The FILE_PROVIDER_PATHS resource a provider names, or None."""
    for item in provider.all("meta-data"):
        if item.get("name") == "android.support.FILE_PROVIDER_PATHS":
            return item.get("resource")
    return None


def is_reviewed_share(provider, package, paths):
    """True for the reviewed share provider only: see SHARE_PROVIDER. `paths` is its parsed paths file, or None."""
    if provider.get("name") != SHARE_PROVIDER or provider.get("authorities") != package + ".files":
        return False
    if provider.get("exported") != "false" or paths is None or paths.name != "paths":
        return False
    return [(child.name, child.get("name"), child.get("path")) for child in paths.children] == SHARE_PATHS


def check_manifest(manifest, package, rules, share_paths=None):
    """(ok, text) for every manifest check. `rules` is the parsed data extraction rules, or None when absent;
    `share_paths` the parsed paths file of the share provider, or None."""
    results = []

    def add(ok, text):
        results.append((bool(ok), text))

    application = (manifest.all("application") or [None])[0]
    add(manifest.attributes.get("package") == package, "package is " + package + ": found " + str(manifest.attributes.get("package")))
    if application is None:
        return results + [(False, "an application element exists")]
    add(application.get("allowBackup") == "false", "backup disabled: allowBackup=" + str(application.get("allowBackup")))
    add(application.get("fullBackupContent") is None, "no legacy backup rules: fullBackupContent=" + str(application.get("fullBackupContent")))
    add(rules is not None and excludes_everything(rules), "cloud backup and device transfer exclude all app data")
    add(application.get("usesCleartextTraffic") == "false", "cleartext traffic off: usesCleartextTraffic=" + str(application.get("usesCleartextTraffic")))
    add(application.get("networkSecurityConfig") is None, "no network security override")
    add(application.get("debuggable") in (None, "false"), "not debuggable: debuggable=" + str(application.get("debuggable")))
    add(application.get("testOnly") in (None, "false"), "not test-only: testOnly=" + str(application.get("testOnly")))
    add(manifest.get("sharedUserId") is None, "no shared user id")
    profileable = manifest.all("profileable")
    add(all(item.get("shell") in (None, "false") for item in profileable), "not profileable from the shell")
    add(not manifest.all("instrumentation"), "no instrumentation entry")

    components = [item for kind in ("activity", "activity-alias", "service", "receiver", "provider") for item in manifest.all(kind)]
    exported = {}
    for item in components:
        # A component with an intent filter and no explicit value was exported by default before API 31;
        # the build refuses that now, and this check refuses it too.
        value = item.get("exported")
        if value is None and item.all("intent-filter"):
            add(False, "exported is stated on " + str(item.get("name")))
        if value == "true":
            exported[item.get("name")] = item.get("permission")
    add(exported == EXPORTED, "exported components are the reviewed five: " +
        ("yes" if exported == EXPORTED else "found " + ", ".join(sorted(f"{k} [{v}]" for k, v in exported.items()))))
    providers = manifest.all("provider")
    granting = [item for item in providers if item.get("grantUriPermissions") not in (None, "false")]
    add(all(item.get("exported") == "false" for item in providers), f"no provider is exported ({len(providers)} providers)")
    add(len(granting) <= 1 and all(is_reviewed_share(item, package, share_paths) for item in granting),
        "only the reviewed share provider grants URI access: " + ("yes" if not granting else ", ".join(
            f"{item.get('name')} [{item.get('authorities')}]" for item in granting) +
            (" (reviewed: cache/shared_lyrics only)" if len(granting) == 1 and is_reviewed_share(granting[0], package, share_paths) else " (not reviewed)")))
    wanted = {name.format(package=package) for name in PERMISSIONS}
    asked = {item.get("name") for item in manifest.all("uses-permission") + manifest.all("uses-permission-sdk-23")}
    add(asked == wanted, "permissions are the reviewed list: " + ("yes" if asked == wanted else
        "added " + str(sorted(asked - wanted)) + " missing " + str(sorted(wanted - asked))))
    names = " ".join(str(item.get("name")) for item in components + manifest.all("meta-data")) + " " + " ".join(
        str(item.get("authorities")) for item in providers)
    present = [mark for mark in FOREIGN_MANIFEST if mark.lower() in names.lower()]
    add(not present, "no test, stress, fixture or benchmark entry in the manifest" + (": " + ", ".join(present) if present else ""))
    return results


def check_files(names, dex):
    """(ok, text) for the archive's file names and the bytes of its dex files."""
    foreign = sorted(name for name in names if any(mark in name for mark in FOREIGN_FILES))
    marks = sorted({mark.decode() for mark in FOREIGN_DEX for data in dex if mark in data})
    secrets = sorted(name for name in names if SECRET_FILES.search(name))
    return [
        (not foreign, "no fixture or benchmark file" + (": " + ", ".join(foreign[:5]) if foreign else "")),
        (not marks, "no test, stress, fixture or benchmark class" + (": " + ", ".join(marks) if marks else "")),
        (not secrets, "no key or credential file" + (": " + ", ".join(secrets[:5]) if secrets else "")),
    ]


def pinned_certificate(document):
    match = re.search(r"Certificate SHA-256:\s*([0-9a-f]{64})", document)
    if not match:
        raise ContentError('No "Certificate SHA-256:" line in the signing document')
    return match.group(1)


def check_certificate(signer_output, signed, pinned):
    """(ok or None, text). None means not checked: the APK is unsigned."""
    if not signed:
        return None, "certificate not checked: the APK is unsigned (expected " + pinned[:16] + "...)"
    found = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", signer_output or "")
    if len(found) != 1:
        return False, f"exactly one signer: found {len(found)}"
    return found[0].lower() == pinned, "certificate is the pinned one: found " + found[0].lower()[:16] + "..."


def is_signed(names):
    """A v1 (JAR) signature file is inside. A v2 or v3 signature leaves none, so this is only a hint (Q18-047)."""
    return any(re.match(r"META-INF/[^/]+\.(RSA|DSA|EC)$", name) for name in names)


def signature_state(v1_files, verified, output):
    """(signed, problem) from the v1 hint and an apksigner run: `verified` is None when apksigner was not given,
    else whether `apksigner verify` succeeded, with its `output`. Without apksigner a v2-only APK cannot be told
    from an unsigned one, so it is reported as not checked, never as a pass."""
    if verified is None:
        return (True, "apksigner is given for a signed APK") if v1_files else (False, None)
    if verified:
        return True, None
    if v1_files or "Signer #" in output:
        return True, "the signature does not verify: " + output.strip()[:160]
    return False, None


def run_status(tool, *arguments):
    """(succeeded, stdout and stderr) of a tool that is allowed to fail."""
    try:
        done = subprocess.run([str(Path(str(tool)).resolve()), *arguments], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ContentError(f"{Path(str(tool)).name} could not run: {error.__class__.__name__}")
    return done.returncode == 0, done.stdout + done.stderr


def run(tool, *arguments):
    try:
        done = subprocess.run([str(Path(str(tool)).resolve()), *arguments], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ContentError(f"{Path(str(tool)).name} could not run: {error.__class__.__name__}")
    if done.returncode != 0:
        raise ContentError(f"{Path(str(tool)).name} failed: " + done.stderr.strip()[:200])
    return done.stdout


def audit(args):
    apk = Path(args.apk)
    manifest = parse_tree(run(args.aapt2, "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"))
    application = (manifest.all("application") or [None])[0]
    rules = None
    reference = application.get("dataExtractionRules") if application is not None else None
    if reference:
        name = resource_file(run(args.aapt2, "dump", "resources", str(apk)), reference)
        rules = parse_tree(run(args.aapt2, "dump", "xmltree", str(apk), "--file", name))
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        dex = [archive.read(name) for name in names if re.fullmatch(r"classes\d*\.dex", name)]
    share_paths = None
    granting = [item for item in manifest.all("provider") if item.get("grantUriPermissions") not in (None, "false")]
    reference = share_paths_reference(granting[0]) if len(granting) == 1 else None
    if reference:
        share_paths = parse_tree(run(args.aapt2, "dump", "xmltree", str(apk), "--file",
                                     resource_file(run(args.aapt2, "dump", "resources", str(apk)), reference)))
    results = check_manifest(manifest, args.package, rules, share_paths) + check_files(names, dex)
    pinned = pinned_certificate(Path(args.signing_doc).read_text(encoding="utf-8"))
    verified, output = run_status(args.apksigner, "verify", "--print-certs", str(apk)) if args.apksigner else (None, "")
    signed, problem = signature_state(is_signed(names), verified, output)
    if problem:
        results.append((False, problem))
    else:
        results.append(check_certificate(output if signed else "", signed, pinned))
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--apk", required=True)
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--apksigner")
    parser.add_argument("--signing-doc", default="docs/release-signing.md")
    parser.add_argument("--package", default="com.musicglass.personal")
    args = parser.parse_args(argv)
    try:
        results = audit(args)
    except (ContentError, OSError, zipfile.BadZipFile) as error:
        print("could not read the APK: " + str(error))
        return 3
    for ok, text in results:
        print(("n/a  " if ok is None else "ok   " if ok else "FAIL ") + text)
    return 2 if any(ok is False for ok, _ in results) else 0


if __name__ == "__main__":
    sys.exit(main())
