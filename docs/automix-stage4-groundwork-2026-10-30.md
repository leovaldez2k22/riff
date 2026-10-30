# Automix stage 4 groundwork: Beat This! small0, measured on the PC (30 Oct 2026)

Nothing here is in the app. These are the measurements behind the owner's two open decisions (at the end), made on
the PC overnight. The phone was not used, and nothing was published or pushed.

## Why stage 4

Stage 2's own tracker missed the engine report's targets on the owner's 50-song set (plan, row "Automix, stage 2").
Beat This!'s small model (`small0`, CPJKU, code and weights MIT), run the way a phone would run it, meets every
target. The phone way means only each song's first 30 s and last 45 s, from the same decoded windows, with no DBN
(`scripts/automix/mark_windows.py`, then `score_beats.py` with the estimate argument).

| Steady songs, 70 windows | Target | Riff's tracker (stage 2) | small0, PyTorch | small0, ONNX float32 | small0, ONNX int8 |
|---|---|---|---|---|---|
| Tempo Acc1 | ≥ 85 % | 66 % | 91 % | 91 % | 91 % |
| Tempo Acc2 | ≥ 95 % | 91 % | 97 % | 97 % | 97 % |
| Beat F | ≥ 0.85 | 0.74 | 0.93 | 0.93 | 0.93 |
| Downbeat F | ≥ 0.70 | 0.31 | 0.88 | 0.88 | 0.88 |
| Hard songs (30 windows): Acc1 / beat F / downbeat F | | 33 % / 0.54 / 0.19 | 60 % / 0.88 / 0.80 | same | same |

The reference is Beat This!'s large model, `final0`, run on whole songs. small0 is a smaller sibling trained on the
same data, so its agreement flatters it somewhat. The owner's ear check of the reference (10 click tracks, all on
the beat, 30 Oct) is what makes the reference trustworthy. Where small0 still misses on steady songs, it is mostly
double or half tempo (Unholy, STAY, bad guy), plus a few heads where the beat comes in late.

The int8 copy (dynamic quantisation of the weights, `onnxruntime.quantization.quantize_dynamic`) loses nothing
measurable. One window's bar line came out right where float32's did not.

## The model

| | |
|---|---|
| Parameters | 2,099,960 |
| ONNX, float32 | 10.5 MB (`artifacts/automix/onnx/small0.onnx`, local) |
| ONNX, int8 weights | 4.7 MB |
| Work per 30 s window (1,500 frames) | 59.6 GFLOP (PyTorch's flop counter) |
| Front end | log-mel, 22,050 Hz, 1,024-point FFT, hop 441 (50 frames a second), 128 Slaney bands, log(1 + 1000·x) |
| Layers | a conv stem, three blocks of frequency-then-time transformers (32 → 64 → 128 channels) with rotary embeddings, then a time transformer; GELU, RMSNorm, softmax attention |
| ONNX graph | 33 kinds of op, all standard: MatMul, Conv, Softmax, Erf, Sin/Cos (rotary), Einsum, BatchNormalization… |
| Outputs | beat and downbeat logits a frame; peaks picked as Beat This!'s "minimal" postprocessor does |
| ONNX vs PyTorch | largest logit difference 7.4e-5 |

## Speed on this PC

| Run | ms per 30 s window |
|---|---|
| ONNX Runtime 1.31.1, 1 thread | 1,278 |
| ONNX Runtime, 2 threads | 982 |
| ONNX Runtime, 4 threads | 914 |
| PyTorch, 1 thread | 1,814 |
| Log-mel front end | 14 |
| Riff's own tracker (stage 2) | about 80 |

These times are the model alone. `score_beats.py`'s medians per window also count the resampling and the
postprocessing: 2.9 s in PyTorch, 2.6 s with ONNX float32, and 3.9 s with int8, which this PC runs slower. A song
needs a head and a tail, about 3.5 windows of work. So on one PC core it is about 4.5 s a song.

**Phone: not measured.** The owner's phone is needed for that. A phone core with NEON usually runs ONNX Runtime 2 to
4 times slower than this PC's AVX2, which would make it about 3 to 5 s a window and 10 to 20 s a song. That is far
from stage 1's "a few hundred ms". It is fine at low priority after a download, or for the next song queued minutes
ahead. It is too slow to read a stream's tail in the last minute.

## The three ways to run it on the phone

| | A: ONNX Runtime in the APK | B: ONNX Runtime, a reduced build | C: Plain Kotlin |
|---|---|---|---|
| APK size | **+34 MB**. The arm64 `libonnxruntime.so` in onnxruntime-android 1.31.1 is 34.2 MB, and Riff stores native libraries uncompressed (the release APK is 10.7 MB today). Compressed it would still be 12.9 MB | About +3 to 5 MB, usually (a "minimal build" with only these 33 ops; not built tonight) | About +50 KB of code |
| Model | 4.7 MB int8 (or 10.5 MB) | 4.7 MB, in ORT's own format | 4.7 MB int8, or 8.4 MB float32 weights in Riff's own format |
| Speed per window, estimated | 3 to 5 s | the same as A | 15 to 60 s: ART runs plain float loops at roughly 1 to 4 GFLOP/s without NEON |
| Work | Small: a dependency, a session, the log-mel front end and the peak picking in Kotlin | A from-source build of ORT with the NDK and CMake for each version, which Riff would have to keep up | Large: every layer by hand, checked against the ONNX outputs; nothing native |
| Licence | MIT | MIT | MIT weights; Riff's own code |

A fourth way is untested: Google's LiteRT (TensorFlow Lite) runtime through Play services, which the APK does not
carry. It needs the model converted from ONNX or PyTorch to TFLite, and the phone to have Play services, which
Cast already relies on. It may suit Riff best, but nothing about it was measured tonight.

**The plan said otherwise.** The plan's stage 4 says the model is delivered "as a download on first use (about
11 MB)". The model can be downloaded that way, at 4.7 MB in int8, but the runtime cannot. ONNX Runtime is native
code and has to ship inside the APK. This is logged in the plan.

## What the owner decides

1. **Where the model file lives** (the plan: a fixed URL in `privacy.md`, checked by SHA-256). Hosting it as a
   release asset on the owner's GitHub is publishing, so it needs the owner's yes. The other choice is putting the
   4.7 MB file in the APK, which needs no hosting at all.
2. **How it runs**: A (+34 MB, fastest to build), B (+3 to 5 MB, a native build to keep up), C (no native code,
   slow, a lot of work), or trying LiteRT first. The recommendation is to try LiteRT through Play services first.
   If it can't run the model, take B, with A as a measuring build only (debug, never over the owner's Riff), to
   time a window on the phone before anything is chosen.

## Files

All of these are local and gitignored: `artifacts/automix/small0-beats.json` and `small0-report.md` (PyTorch); and
in `artifacts/automix/onnx/`, `small0.onnx`, `small0-int8.onnx`, `small0-onnx.json` (export check and timings), and
the beats and reports for both ONNX models.

## Answered (30 Oct)

The owner chose the model inside the APK, and ONNX Runtime as published (A) in the debug build for now, with the reduced build (B) later on request. Built the same day; the phone's times and the stalls seen are in the plan's row "Automix, stage 4".
