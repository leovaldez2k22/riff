"""Original synthetic PCM fixtures. No recordings, personal media or third-party samples."""
import hashlib
import json
import math
from pathlib import Path
import struct
import wave

ROOT = Path(__file__).resolve().parents[2] / "app/src/androidTest/assets/quality/audio"
RATE = 16000
SPECS = [("tone-a", 30, (440,)), ("tone-b", 30, (660,)),
         ("long", 300, (220,)), ("short", 5, (880,)),
         ("stereo", 30, (440, 660)), ("silence", 5, (0,))]

def generate():
    ROOT.mkdir(parents=True, exist_ok=True)
    fixtures = []
    for name, seconds, frequencies in SPECS:
        path = ROOT / (name + ".wav")
        with wave.open(str(path), "wb") as output:
            output.setnchannels(len(frequencies))
            output.setsampwidth(2)
            output.setframerate(RATE)
            # One exact second repeated: frequencies have integer periods per second.
            block = b"".join(struct.pack("<h", round(8192 * math.sin(2 * math.pi * f * i / RATE)))
                             for i in range(RATE) for f in frequencies)
            for _ in range(seconds):
                output.writeframesraw(block)
        fixtures.append(dict(file=path.name, durationMs=seconds * 1000, sampleRate=RATE,
                             channels=len(frequencies), pcmBits=16, frames=seconds * RATE,
                             frequenciesHz=list(frequencies), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    (ROOT / "metadata.json").write_text(json.dumps(dict(schemaVersion=1,
        provenance="Original mathematically generated sine waves and silence; no sampled recordings.",
        generator="scripts/quality/generate_audio_fixtures.py; PCM16 little endian; peak amplitude 8192",
        timing="Frame counts are exact; decoder tests use real elapsedRealtime with bounded tolerance.",
        fixtures=fixtures), indent=2) + "\n", encoding="utf-8")

if __name__ == "__main__":
    generate()
