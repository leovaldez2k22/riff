"""Stage 4 groundwork, PC only: Beat This! small0 as ONNX, checked against PyTorch and timed with ONNX Runtime.

Usage: <beat-this venv python> scripts/automix/export_onnx.py <checkpoint> <out folder> <pcm folder>
"""
import collections, json, os, sys, time
import numpy as np
import torch
from beat_this.inference import load_model
from beat_this.preprocessing import LogMelSpect

ckpt, out_dir, pcm = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out_dir, exist_ok=True)
model = load_model(ckpt, "cpu").eval()
params = sum(p.numel() for p in model.parameters())
frames = 1500  # Beat This!'s chunk: 30 s at 50 frames a second, one head window
x = torch.randn(1, frames, 128)
path = os.path.join(out_dir, "small0.onnx")
torch.onnx.export(model, (x,), path, input_names=["spect"], output_names=["beat", "downbeat"],
                  dynamic_axes={"spect": {1: "time"}, "beat": {1: "time"}, "downbeat": {1: "time"}}, opset_version=17, dynamo=False)
size = os.path.getsize(path)
import onnx, onnxruntime as ort
graph = onnx.load(path).graph
ops = collections.Counter(n.op_type for n in graph.node)

# The same frames through both, from a real head window.
raw = np.fromfile(os.path.join(pcm, "0.head.pcm"), dtype="<i2").astype(np.float32) / 32768
mono = torch.from_numpy(raw.reshape(-1, 2).mean(axis=1))
import torchaudio
mono22 = torchaudio.functional.resample(mono, 48_000, 22_050)
spect = LogMelSpect()
t0 = time.perf_counter(); s = spect(mono22); mel_ms = (time.perf_counter() - t0) * 1000
s = s[:frames].unsqueeze(0)
with torch.no_grad():
    ref = model(s)
results = {}
for threads in (1, 2, 4):
    so = ort.SessionOptions(); so.intra_op_num_threads = threads; so.inter_op_num_threads = 1
    sess = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
    got = sess.run(None, {"spect": s.numpy()})
    sess.run(None, {"spect": s.numpy()})
    times = []
    for _ in range(5):
        t0 = time.perf_counter(); sess.run(None, {"spect": s.numpy()}); times.append((time.perf_counter() - t0) * 1000)
    results[threads] = round(float(np.median(times)), 1)
diff = max(float(np.abs(got[0] - ref["beat"].numpy()).max()), float(np.abs(got[1] - ref["downbeat"].numpy()).max()))
torch.set_num_threads(1)
with torch.no_grad():
    model(s); t0 = time.perf_counter(); model(s); torch_ms = (time.perf_counter() - t0) * 1000
report = dict(params=params, onnx_bytes=size, ops=dict(ops.most_common()), max_logit_difference=diff,
              ort_ms_per_30s_window_by_threads=results, torch_ms_1_thread=round(torch_ms, 1), mel_ms=round(mel_ms, 1),
              ort_version=ort.__version__, frames=frames)
json.dump(report, open(os.path.join(out_dir, "small0-onnx.json"), "w"), indent=1)
print(json.dumps(report, indent=1))
