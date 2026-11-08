# Sing's model (Riff+, feature 20)

`app/src/plus/assets/sing/spleeter-2stems.onnx` is Deezer's Spleeter 2-stems model (MIT, `licenses/SPLEETER-MIT.txt`),
converted from its published TensorFlow checkpoint by `convert_spleeter.py`. The app does the rest of Spleeter's
arithmetic itself (`SingDsp.kt`).

To make it again:

1. Download `https://github.com/deezer/spleeter/releases/download/v1.4.0/2stems.tar.gz` into a new, empty directory and
   check its SHA-256 against Deezer's `checksum.json` (`f3a90b39dd2874269e8b05a48a86745df897b848c61f3958efc80a39152bd692`).
   Extract it into another new directory (`model.data-00000-of-00001`, `model.index`, `model.meta`, `checkpoint`).
2. Make a Python 3.10 environment with `spleeter==2.4.0 tensorflow==2.9.0 tf2onnx==1.16.1 onnx==1.17.0
   onnxruntime==1.18.1 numpy==1.26.4 protobuf==3.20.3` (on this PC: `.toolchain/sing-convert`).
3. From the repository root, with the checkpoint's directory as an argument (never run Python from inside it):

   ```
   .toolchain/sing-convert/Scripts/python.exe -I scripts/sing/convert_spleeter.py <checkpoint dir> <output.onnx>
   ```

It prints the file's size and SHA-256 and how far ONNX Runtime is from TensorFlow, and exits non-zero if they disagree
beyond float32 noise. tf2onnx's bytes differ from run to run, so record the hash of the copy you bundle.
