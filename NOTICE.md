# License and third-party notices

Riff is licensed under GNU GPL version 3. See [LICENSE](LICENSE).

The app depends on [InnerTubeX 0.7.2](https://github.com/MetrolistGroup/innertubex),
copyright its contributors, also licensed under GNU GPL version 3. The dependency is fetched
from JitPack at build time. Riff's Android stream attestation adapter and JavaScript runtime
(`AndroidStreamTokenProvider.kt`, `AndroidTokenRuntime.kt`) are adapted from its GPL-3.0 harness
(`HarnessTokenProvider.kt` and `BrowserRuntime.kt`); copyright the InnerTubeX contributors.

Design reference images and retired prototype assets are not bundled in the APK.

The app bundles [Inter 4.1](https://github.com/rsms/inter) (the variable font), copyright The Inter Project Authors,
under the SIL Open Font License 1.1. See [licenses/INTER-OFL.txt](licenses/INTER-OFL.txt).

Automix's beat model is [Beat This!](https://github.com/CPJKU/beat_this)'s `small0`, converted to ONNX and its
weights quantized to int8 (`assets/automix/beat-this-small0-int8.onnx`), copyright 2024 Institute of Computational
Perception, JKU Linz, Austria, under the MIT License. See [licenses/BEAT-THIS-MIT.txt](licenses/BEAT-THIS-MIT.txt).
`BeatModel.kt` ports its pre- and post-processing from that code.

The app bundles [ONNX Runtime 1.31.1](https://github.com/microsoft/onnxruntime) for Android, copyright Microsoft
Corporation, under the MIT License. See [licenses/ONNXRUNTIME-MIT.txt](licenses/ONNXRUNTIME-MIT.txt). Its native library
contains third-party components listed in ONNX Runtime's
[ThirdPartyNotices.txt](https://github.com/microsoft/onnxruntime/blob/v1.31.1/ThirdPartyNotices.txt).

YouTube Music and YouTube are trademarks of Google LLC. This is an independent client and is
not affiliated with or endorsed by Google or YouTube.


## Other components

Every other runtime dependency, its version and its declared license is listed in
[docs/dependency-license-audit.csv](docs/dependency-license-audit.csv) and summarized in
[docs/dependency-license-inventory.md](docs/dependency-license-inventory.md): Apache-2.0 for AndroidX, Kotlin,
Ktor, OkHttp, Okio, Coil, QuickJS for Kotlin and Guava, MIT for SLF4J, and Google's Android SDK terms for the
Google Play services parts Cast uses (closed source, distributed by Google). That inventory is declared metadata, not a
legal review.
