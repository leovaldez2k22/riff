"""Convert Deezer's Spleeter 2-stems checkpoint (MIT) into the ONNX model Riff+ bundles for Sing (feature 20).

The model takes the magnitude spectrogram Spleeter feeds its U-Nets, [1, 512, 1024, 2] (frames, the lowest 1,024 bins,
stereo), and returns the accompaniment's ratio mask in the same layout, computed exactly as spleeter/model/__init__.py
(_build_masks, v2.4) does: (accompaniment^2 + eps/2) / (vocals^2 + accompaniment^2 + eps). The rest of Spleeter's
arithmetic (STFT, mask extension, inverse STFT) is SingDsp.kt in the app.

Run with the converter environment, outside the checkpoint's directory:
    .toolchain/sing-convert/Scripts/python.exe -I scripts/sing/convert_spleeter.py <checkpoint dir> <output.onnx>
It builds the graph with Spleeter's own model function so the variable names match the checkpoint, restores it,
freezes it, converts it with tf2onnx and checks ONNX Runtime against TensorFlow on random and song-like input.
"""
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import tensorflow as tf
import tf2onnx
import onnxruntime as ort
from spleeter.model.functions.unet import unet

T, F, CHANNELS, EPSILON = 512, 1024, 2, 1e-10


def build(checkpoint: Path):
    tf.compat.v1.disable_eager_execution()
    tf.compat.v1.keras.backend.set_learning_phase(0)  # batch norm and dropout in inference mode
    graph = tf.Graph()
    with graph.as_default():
        magnitudes = tf.compat.v1.placeholder(tf.float32, [1, T, F, CHANNELS], name="magnitudes")
        outputs = unet(magnitudes, ["vocals", "accompaniment"], {})
        vocals, accompaniment = outputs["vocals_spectrogram"], outputs["accompaniment_spectrogram"]
        total = vocals ** 2 + accompaniment ** 2 + EPSILON
        tf.identity((accompaniment ** 2 + EPSILON / 2) / total, name="accompaniment_mask")
        session = tf.compat.v1.Session(graph=graph)
        tf.compat.v1.train.Saver().restore(session, str(checkpoint / "model"))
        frozen = tf.compat.v1.graph_util.convert_variables_to_constants(session, graph.as_graph_def(), ["accompaniment_mask"])
    return session, frozen


def song_like(seed: int) -> np.ndarray:
    """Magnitudes shaped like music: a falling spectrum, harmonics on a few notes, quiet frames."""
    rng = np.random.default_rng(seed)
    bins = np.arange(F)
    base = 40.0 / (1 + bins / 30.0)
    x = np.abs(rng.normal(0, 1, (1, T, F, CHANNELS))) * base[None, None, :, None]
    for note in rng.uniform(50, 400, 6):
        for h in range(1, 12):
            b = int(note * h / 44100 * 4096)
            if b < F:
                x[0, :, b, :] += 200.0 / h * rng.uniform(.5, 1.5, (T, CHANNELS))
    x[0, 400:, :, :] = 0  # padding at the end of a song
    return x.astype(np.float32)


def main(checkpoint: str, output: str) -> int:
    checkpoint, output = Path(checkpoint), Path(output)
    session, frozen = build(checkpoint)
    output.parent.mkdir(parents=True, exist_ok=True)
    tf2onnx.convert.from_graph_def(frozen, input_names=["magnitudes:0"], output_names=["accompaniment_mask:0"],
                                   opset=13, output_path=str(output))
    runtime = ort.InferenceSession(str(output), providers=["CPUExecutionProvider"])
    worst, tail, off = 0.0, 0.0, 0
    cases = [np.abs(np.random.default_rng(1).normal(0, 5, (1, T, F, CHANNELS))).astype(np.float32), song_like(2), song_like(3),
             np.zeros((1, T, F, CHANNELS), np.float32)]
    for x in cases:
        expected = session.run("accompaniment_mask:0", {"magnitudes:0": x})
        actual = runtime.run(None, {runtime.get_inputs()[0].name: x})[0]
        assert actual.shape == expected.shape, (actual.shape, expected.shape)
        assert np.all(actual >= 0) and np.all(actual <= 1.0001)
        difference = np.abs(actual - expected)
        worst = max(worst, float(difference.max()))
        tail = max(tail, float(np.quantile(difference, .9999)))
        off += int((difference > 1e-3).sum())
    report = {
        "onnx": output.name,
        "bytes": output.stat().st_size,
        "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "input": [i.name for i in runtime.get_inputs()],
        "output": [o.name for o in runtime.get_outputs()],
        "max_abs_difference_vs_tensorflow": worst,
        "p99_99_abs_difference": tail,
        "values_off_by_more_than_1e-3": off,
        "mean_mask_song_like": float(runtime.run(None, {runtime.get_inputs()[0].name: song_like(2)})[0].mean()),
    }
    print(json.dumps(report, indent=2))
    # Where both U-Nets output almost nothing (about 1e-5) the ratio is float32 noise in either runtime: allow a few
    # such values to differ by up to 1e-2; everywhere else the two must agree to 1e-4.
    return 0 if worst < 1e-2 and tail < 1e-4 and off <= 64 else 1


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:3]))
