"""Reference beats and downbeats for an Automix test set from Beat This! (CPJKU, MIT; final0 model, no DBN).

Usage: <beat-this venv python> scripts/automix/mark_beats.py <set.json> <song folder> <out.json> [checkpoint]

The checkpoint is final0 by name (downloaded by Beat This!) or a local copy of final0.ckpt.

Each whole song is decoded by ffmpeg to 22.05 kHz mono and marked; the marks are in seconds from the song's start.
"""
import json
import os
import subprocess
import sys
import time

import numpy as np
from beat_this.inference import Audio2Beats

RATE = 22_050


def samples(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(RATE), "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32)


def main(set_path, songs, out, checkpoint="final0"):
    tracker = Audio2Beats(checkpoint_path=checkpoint, device="cpu", dbn=False)
    marks = []
    for n, song in enumerate(json.load(open(set_path, encoding="utf-8"))):
        started = time.time()
        beats, downbeats = tracker(samples(os.path.join(songs, song["file"])), RATE)
        marks.append({"n": n, "file": song["file"], "beats": [round(float(b), 4) for b in beats],
                      "downbeats": [round(float(d), 4) for d in downbeats]})
        print(n, len(beats), "beats", f"{time.time() - started:.1f}s", song["file"], flush=True)
    json.dump(marks, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main(*sys.argv[1:5])
