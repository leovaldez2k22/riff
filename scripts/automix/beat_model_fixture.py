"""Automix stage 4: reference values from Beat This!'s own code, for BeatModelTest's check of the Kotlin port.

Writes app/src/test/resources/automix/beat-model-fixture.json: the log-mel of a fixed test signal, the chunked
prediction of a fake model that marks each frame's place in its chunk, and the minimal postprocessor's beats.
Usage: <beat-this venv python> scripts/automix/beat_model_fixture.py
"""
import json, math, os
import numpy as np
import torch
from beat_this.preprocessing import LogMelSpect
from beat_this.inference import split_predict_aggregate
from beat_this.model.postprocessor import Postprocessor

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app/src/test/resources/automix/beat-model-fixture.json")

# The signal BeatModelTest makes: two tones and a click every half second, 3 s at 22,050 Hz.
n = np.arange(66_150)
x = 0.5 * np.sin(2 * math.pi * 440 * n / 22_050) + 0.3 * np.sin(2 * math.pi * 3_000 * n / 22_050 + 0.5)
x = x + np.where(n % 11_025 == 0, 0.8, 0.0)
spect = LogMelSpect()(torch.tensor(x, dtype=torch.float32))
frames = [0, 1, 2, 75, 150]

# A fake model whose beat logit is the chunk's first band plus the frame's place in the chunk, so a wrong border or
# overlap shows; its downbeat logit is the chunk's length.
T = 3_100
fake = torch.tensor([[math.sin(f * 0.37 + b) for b in range(128)] for f in range(T)], dtype=torch.float32)
class Marker(torch.nn.Module):
    def forward(self, c):
        place = torch.arange(c.shape[1], dtype=torch.float32)
        return {"beat": c[:, :, 0] + 0.001 * place, "downbeat": torch.full((1, c.shape[1]), float(c.shape[1]))}
chunked = split_predict_aggregate(fake, 1_500, 6, "keep_first", Marker())
short = split_predict_aggregate(fake[:1_000], 1_500, 6, "keep_first", Marker())

# Logits with a steady beat every 25 frames (120 BPM), a downbeat every fourth, a tie and a weak bump.
L = 400
beat = np.full(L, -5.0, dtype=np.float32); down = np.full(L, -5.0, dtype=np.float32)
for f in range(10, L, 25):
    beat[f] = 3.0; beat[f - 1] = 1.0
for f in range(10, L, 100):
    down[f + 1] = 2.0
beat[200] = beat[201] = 2.5  # a tie of two frames: one beat between them
beat[300] = 0.0              # not more likely than not: no beat
beats, downbeats = Postprocessor("minimal")(torch.tensor(beat), torch.tensor(down))

os.makedirs(os.path.dirname(OUT), exist_ok=True)
json.dump({
    "logMel": {str(f): [round(float(v), 5) for v in spect[f]] for f in frames},
    "logMelFrames": int(spect.shape[0]),
    "chunkedBeat": [round(float(v), 5) for v in chunked["beat"]],
    "chunkedDownbeat": [float(v) for v in chunked["downbeat"]],
    "shortBeat": [round(float(v), 5) for v in short["beat"]],
    "peaksBeat": [float(v) for v in beat], "peaksDownbeat": [float(v) for v in down],
    "beatsS": [float(v) for v in beats], "downbeatsS": [float(v) for v in downbeats],
}, open(OUT, "w"), separators=(",", ":"))
print("wrote", OUT, os.path.getsize(OUT), "bytes")
