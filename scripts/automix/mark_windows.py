"""Beat This! on the windows Riff analyses, as a phone would run it: each song's first 30 s and last 45 s only.

Usage: <beat-this venv python> scripts/automix/mark_windows.py <pcm folder> <checkpoint> <out.json>

Reads windows.json and the .pcm files from extract_windows.py and writes the same rows AutomixSetEvaluation writes
(beats and downbeats in ms from the song's start, the tempo from the median gap, the time taken), so
score_beats.py can score it in place of Riff's tracker.
"""
import json
import os
import statistics
import sys
import time

import numpy as np
from beat_this.inference import Audio2Beats

RATE, CHANNELS = 48_000, 2


def main(pcm, checkpoint, out):
    tracker = Audio2Beats(checkpoint_path=checkpoint, device="cpu", dbn=False)
    rows = []
    for w in json.load(open(os.path.join(pcm, "windows.json"), encoding="utf-8")):
        for part, start_ms in (("head", 0), ("tail", w["tailStartMs"])):
            raw = np.fromfile(os.path.join(pcm, f"{w['n']}.{part}.pcm"), dtype="<i2").astype(np.float32) / 32768
            mono = raw.reshape(-1, CHANNELS).mean(axis=1)
            started = time.time()
            beats, downbeats = tracker(mono, RATE)
            ms = int((time.time() - started) * 1000)
            row = {"n": w["n"], "part": part, "ms": ms, "beats": [int(start_ms + b * 1000) for b in beats],
                   "downbeats": [int(start_ms + d * 1000) for d in downbeats]}
            if len(beats) > 2:
                row.update(bpm=60 / statistics.median(np.diff(beats)), confidence=1.0, downbeatConfidence=1.0)
            rows.append(row)
        print(w["n"], flush=True)
    json.dump(rows, open(out, "w", encoding="utf-8"), indent=0)


if __name__ == "__main__":
    main(*sys.argv[1:4])
