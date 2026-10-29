"""Click tracks for checking reference beat marks by ear: 30 s of a song's last 45 s with a tick on every marked beat
(1 kHz) and a higher, louder one on every downbeat (1.6 kHz).

Usage: <beat-this venv python> scripts/automix/click_tracks.py <marks.json> <song folder> <pcm folder> <out folder> <n>...
"""
import json
import os
import subprocess
import sys

import numpy as np

RATE, SECONDS = 44_100, 30


def tick(hz, gain):
    t = np.arange(int(RATE * 0.04)) / RATE
    return (gain * np.sin(2 * np.pi * hz * t) * np.exp(-t * 90)).astype(np.float32)


def main(marks_path, songs, pcm, out, *numbers):
    marks = {m["n"]: m for m in json.load(open(marks_path, encoding="utf-8"))}
    windows = {w["n"]: w for w in json.load(open(os.path.join(pcm, "windows.json"), encoding="utf-8"))}
    os.makedirs(out, exist_ok=True)
    beat, down = tick(1000, 0.35), tick(1600, 0.6)
    for n in map(int, numbers):
        m, start = marks[n], windows[n]["tailStartMs"] / 1000
        raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{start:.3f}", "-t", str(SECONDS), "-i", os.path.join(songs, m["file"]),
                              "-f", "f32le", "-ac", "2", "-ar", str(RATE), "-"], capture_output=True, check=True).stdout
        audio = np.frombuffer(raw, dtype=np.float32).reshape(-1, 2).copy() * 0.7
        downbeats = [d for d in m["downbeats"] if start <= d < start + SECONDS]
        for b in (b for b in m["beats"] if start <= b < start + SECONDS):
            sound = down if any(abs(b - d) < 0.01 for d in downbeats) else beat
            at = int((b - start) * RATE)
            part = sound[: max(0, len(audio) - at)]
            audio[at: at + len(part)] += part[:, None]
        audio /= max(1.0, float(np.abs(audio).max()))
        name = os.path.join(out, f"{n:02d} - {os.path.splitext(m['file'])[0]}.mp3")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ac", "2", "-ar", str(RATE), "-i", "-", "-b:a", "192k", name],
                       input=audio.astype(np.float32).tobytes(), check=True)
        print(name, flush=True)


if __name__ == "__main__":
    main(*sys.argv[1:])
