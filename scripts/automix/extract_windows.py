"""Decode the first 30 s and last 45 s of each song in an Automix test set to raw PCM, the parts Riff analyses.

Usage: python scripts/automix/extract_windows.py <set.json> <song folder> <out folder>

The set is a JSON list of {"file": ..., ...}. Each song gets <n>.head.pcm and <n>.tail.pcm (48 kHz, stereo,
16-bit little-endian) and a line in <out>/windows.json with the windows' start times in the song.
"""
import json
import os
import subprocess
import sys

HEAD_MS = 30_000  # TrackAnalysis.ANALYSIS_HEAD_MS
TAIL_MS = 45_000  # TrackAnalysis.ANALYSIS_TAIL_MS
RATE, CHANNELS = 48_000, 2


def duration_ms(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                         capture_output=True, text=True, check=True).stdout
    return int(float(out) * 1000)


def decode(path, start_ms, length_ms, target):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{start_ms / 1000:.3f}", "-t", f"{length_ms / 1000:.3f}",
                    "-i", path, "-f", "s16le", "-ac", str(CHANNELS), "-ar", str(RATE), target], check=True)


def main(set_path, songs, out):
    os.makedirs(out, exist_ok=True)
    windows = []
    for n, song in enumerate(json.load(open(set_path, encoding="utf-8"))):
        path = os.path.join(songs, song["file"])
        total = duration_ms(path)
        tail_start = max(0, total - TAIL_MS)
        decode(path, 0, HEAD_MS, os.path.join(out, f"{n}.head.pcm"))
        decode(path, tail_start, TAIL_MS, os.path.join(out, f"{n}.tail.pcm"))
        windows.append({"n": n, "file": song["file"], "durationMs": total, "headStartMs": 0, "tailStartMs": tail_start})
        print(n, song["file"], flush=True)
    json.dump(windows, open(os.path.join(out, "windows.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main(*sys.argv[1:4])
