"""mark_windows.py's rows with small0 run by ONNX Runtime instead of PyTorch, for score_beats.py.

Usage: <beat-this venv python> scripts/automix/mark_windows_onnx.py <pcm folder> <checkpoint> <model.onnx> <out.json>
"""
import json, os, statistics, sys, time
import numpy as np
import torch
import onnxruntime as ort
from beat_this.inference import Audio2Beats

pcm, ckpt, model_path, out = sys.argv[1:5]
so = ort.SessionOptions(); so.intra_op_num_threads = 1
sess = ort.InferenceSession(model_path, so, providers=["CPUExecutionProvider"])

class OrtModel(torch.nn.Module):
    def forward(self, x):
        beat, down = sess.run(None, {"spect": x.numpy().astype(np.float32)})
        return {"beat": torch.from_numpy(beat), "downbeat": torch.from_numpy(down)}

tracker = Audio2Beats(checkpoint_path=ckpt, device="cpu", dbn=False)
tracker.model = OrtModel()
rows = []
for w in json.load(open(os.path.join(pcm, "windows.json"), encoding="utf-8")):
    for part, start_ms in (("head", 0), ("tail", w["tailStartMs"])):
        raw = np.fromfile(os.path.join(pcm, f"{w['n']}.{part}.pcm"), dtype="<i2").astype(np.float32) / 32768
        mono = raw.reshape(-1, 2).mean(axis=1)
        t0 = time.time(); beats, downbeats = tracker(mono, 48_000); ms = int((time.time() - t0) * 1000)
        row = {"n": w["n"], "part": part, "ms": ms, "beats": [int(start_ms + b * 1000) for b in beats],
               "downbeats": [int(start_ms + d * 1000) for d in downbeats]}
        if len(beats) > 2:
            row.update(bpm=60 / statistics.median(np.diff(beats)), confidence=1.0, downbeatConfidence=1.0)
        rows.append(row)
json.dump(rows, open(out, "w", encoding="utf-8"), indent=0)
