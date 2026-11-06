"""Losslessly encode owner-selected real analysis excerpts; never ship these test files."""
from pathlib import Path
import hashlib
import json
import subprocess

root = Path.cwd()
source_root = root / "artifacts/automix/pcm"
output = root / "artifacts/desktop/phase3/automix-recorded-corpus"
output.mkdir(parents=True, exist_ok=True)
sdk = next(p for p in (root / ".toolchain/desktop-native/ffmpeg-8.1-lgpl-shared").iterdir() if p.is_dir())
windows = {row["n"]: row for row in json.loads((source_root / "windows.json").read_text(encoding="utf-8"))}
manifest = []
for number, part in ((0, "tail"), (0, "head"), (1, "head")):
    source = source_root / f"{number}.{part}.pcm"
    data = source.read_bytes()
    if not data or len(data) % 4:
        raise ValueError("Real stereo PCM excerpt is missing or malformed")
    target = output / f"{number}.{part}.flac"
    # Always re-encode from the verified, actual retained excerpt. No generated tone/padding.
    subprocess.run([str(sdk / "bin/ffmpeg.exe"), "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
                    "-f", "s16le", "-ar", "48000", "-ac", "2", "-i", str(source), "-c:a", "flac", str(target)], check=True)
    row = windows[number]
    manifest.append({"number": number, "part": part, "recording": row["file"],
                     "excerptStartMs": row[f"{part}StartMs"], "frames": len(data) // 4,
                     "sourcePcmSha256": hashlib.sha256(data).hexdigest(), "encodedFile": target.name,
                     "encodedSha256": hashlib.sha256(target.read_bytes()).hexdigest()})
(output / "manifest.json").write_text(json.dumps({"scope": "Real owner-selected recording excerpts, not full recordings; unshipped acceptance only",
                                                  "rate": 48000, "channels": 2, "inputs": manifest}, indent=2), encoding="utf-8")
print("Prepared three actual recorded FLAC excerpts with source/encoded SHA-256 provenance")
