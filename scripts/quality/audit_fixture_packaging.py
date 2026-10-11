"""Verify that Q02 decoder fixtures stay in the instrumentation APK."""
import argparse
import hashlib
import io
import json
import re
from pathlib import Path
import subprocess
import wave
import zipfile
from datetime import datetime, timezone

from collect_results import apk_identity, EvidenceError

NAMES = {"tone-a": (30, 1), "tone-b": (30, 1), "long": (300, 1),
         "short": (5, 1), "stereo": (30, 2), "silence": (5, 1)}
TEST_TYPES = (b"FixtureHttpServer", b"ScriptedResolver", b"FakeCatalog",
              b"FakeSession", b"FakeClock", b"FixturePlaybackTest")


def require(condition, message):
    if not condition:
        raise EvidenceError(message)


def manifest(aapt2, apk, name="AndroidManifest.xml"):
    return subprocess.run([str(aapt2), "dump", "xmltree", str(apk), "--file", name],
                          check=True, capture_output=True, text=True, timeout=30).stdout


def audit(args):
    identities = {kind: apk_identity(getattr(args, kind)) for kind in ("debug", "release", "test")}
    require(identities["debug"] == identities["test"], "App/test identity mismatch")
    require(identities["debug"]["sourceDigest"] == identities["release"]["sourceDigest"],
            "Debug/release source mismatch")
    for kind in ("debug", "release"):
        apk = getattr(args, kind)
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            require(not any(name.startswith("assets/quality/audio/") for name in names),
                    kind + " app contains audio fixture assets")
            for name in names:
                if name.endswith(".dex"):
                    dex = archive.read(name)
                    require(not any(marker in dex for marker in TEST_TYPES),
                            kind + " app contains test fixture implementation")
            if kind == "release":
                require(not any("fixture_network_security_config" in name for name in names),
                        "Release contains fixture cleartext config")
        text = manifest(args.aapt2, apk)
        require("usesCleartextTraffic(0x010104ec)=false" in text, kind + " cleartext policy changed")
        if kind == "release":
            require("networkSecurityConfig" not in text, "Release unexpectedly overrides network security")
            require(not any(name in text for name in ("FeatureTestActivity", "FeaturePreviewActivity",
                                                      "StressDiagnostics", ".test/")),
                    "Release manifest contains test entry points")
        else:
            require("networkSecurityConfig" in text, "Debug loopback config is missing")
    cleartext = manifest(args.aapt2, args.debug, "res/xml/fixture_network_security_config.xml")
    tokens = [re.sub(r" \(line=\d+\)$", "", line.strip()) for line in cleartext.splitlines() if line.strip()]
    require(tokens == ["E: network-security-config", "E: base-config", "A: cleartextTrafficPermitted=false",
                       "E: domain-config", "A: cleartextTrafficPermitted=true", "E: domain",
                       "A: includeSubdomains=false", "T: '127.0.0.1'", "E: domain",
                       "A: includeSubdomains=false", "T: 'localhost'"],
            "Debug cleartext allowance extends beyond exact loopback domains")
    with zipfile.ZipFile(args.test) as archive:
        metadata = json.loads(archive.read("assets/quality/audio/metadata.json"))
        require(len(metadata["fixtures"]) == len(NAMES), "Fixture metadata count mismatch")
        require({item["file"] for item in metadata["fixtures"]} == {name + ".wav" for name in NAMES},
                "Missing or duplicate audio fixture")
        for item in metadata["fixtures"]:
            name = Path(item["file"]).stem
            require(name in NAMES, "Unexpected fixture")
            data = archive.read("assets/quality/audio/" + item["file"])
            require(hashlib.sha256(data).hexdigest() == item["sha256"], "Fixture digest mismatch")
            seconds, channels = NAMES[name]
            with wave.open(io.BytesIO(data), "rb") as audio:
                require((audio.getnframes(), audio.getframerate(), audio.getnchannels(), audio.getsampwidth()) ==
                        (seconds * 16000, 16000, channels, 2), "Fixture audio format/duration mismatch")
            require(item["durationMs"] == seconds * 1000 and item["frames"] == seconds * 16000,
                    "Fixture timing metadata mismatch")
    return dict(status="PASS", checkedAtUtc=datetime.now(timezone.utc).isoformat(),
                identities=identities, audioFixtures=len(NAMES),
                apkSha256={kind: hashlib.sha256(getattr(args, kind).read_bytes()).hexdigest()
                           for kind in ("debug", "release", "test")},
                scope="Q02 fixture asset/code exclusion, manifest cleartext and exact audio metadata")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("debug", "release", "test", "aapt2"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(audit(args), indent=2))
    except (EvidenceError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, "FAIL: " + str(error) + "\n")

if __name__ == "__main__":
    main()
