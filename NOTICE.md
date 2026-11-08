# License and third-party notices

Riff is licensed under GNU GPL version 3. See [LICENSE](LICENSE).

The app depends on [InnerTubeX 0.11.2](https://github.com/MetrolistGroup/innertubex) and on
[QuackJS 0.1.2](https://github.com/MetrolistGroup/quackjs), which InnerTubeX uses to run YouTube's
challenges, each copyright its contributors and licensed under GNU GPL version 3. Both are fetched
from JitPack at build time. Riff's Android stream attestation adapter and JavaScript runtime
(`AndroidStreamTokenProvider.kt`, `AndroidTokenRuntime.kt`) are adapted from its GPL-3.0 harness
(`HarnessTokenProvider.kt` and `BrowserRuntime.kt`); copyright the InnerTubeX contributors.

Design reference images and retired prototype assets are not bundled in the APK.

The app bundles [Inter 4.1](https://github.com/rsms/inter) (the variable font), copyright The Inter Project Authors,
under the SIL Open Font License 1.1. See [licenses/INTER-OFL.txt](licenses/INTER-OFL.txt).

From the 0.30.0 edition split, Riff+ alone bundles Automix's beat model, [Beat This!](https://github.com/CPJKU/beat_this) `small0`, converted to ONNX and its
weights quantized to int8 (`assets/automix/beat-this-small0-int8.onnx`), copyright 2024 Institute of Computational
Perception, JKU Linz, Austria, under the MIT License. See [licenses/BEAT-THIS-MIT.txt](licenses/BEAT-THIS-MIT.txt).
`BeatModel.kt` ports its pre- and post-processing from that code.

Riff+ bundles the 2-stems model of [Spleeter](https://github.com/deezer/spleeter) (release v1.4.0), copyright 2019-present
Deezer SA, under the MIT License, for Sing: its weights converted to ONNX at full precision (`assets/sing/spleeter-2stems.onnx`,
[scripts/sing](scripts/sing/convert_spleeter.py)). See [licenses/SPLEETER-MIT.txt](licenses/SPLEETER-MIT.txt). `SingDsp.kt` ports
its transform from Spleeter's code. Spleeter is described in Hennequin, Khlif, Voituret and Moussallam, "Spleeter: a fast and
efficient music source separation tool with pre-trained models", Journal of Open Source Software 5(50), 2154 (2020),
doi:10.21105/joss.02154. As its authors ask: separate only music you have the rights to use; Sing keeps what it makes on
your phone and never shares it.

Riff+ bundles [ONNX Runtime 1.31.1](https://github.com/microsoft/onnxruntime) for Android, copyright Microsoft
Corporation, under the MIT License. See [licenses/ONNXRUNTIME-MIT.txt](licenses/ONNXRUNTIME-MIT.txt). Its native library
contains third-party components listed in ONNX Runtime's
[ThirdPartyNotices.txt](https://github.com/microsoft/onnxruntime/blob/v1.31.1/ThirdPartyNotices.txt).

Riff+ bundles headphone profiles from [AutoEq](https://github.com/jaakkopasanen/AutoEq) (results at commit 7ae0f56,
20 July 2025), copyright 2018-2022 Jaakko Pasanen, under the MIT License; see [licenses/AUTOEQ-MIT.txt](licenses/AUTOEQ-MIT.txt),
which is also carried inside the list (`assets/autoeq/profiles.tsv`). Riff fitted each profile to its seven bands
([scripts/autoeq](scripts/autoeq/README.md)). The measurements behind them are those AutoEq credits, named in the app on every
profile: oratory1990, crinacle, Innerfidelity, Rtings, Headphone.com Legacy, HypetheSonics, Super Review, Kuulokenurkka,
Auriculares Argentina, Bakkwatan, DHRME, Fahryst, Filk, Harpo, Hi End Portable, Jaytiss, Kazi, Regan Cipher, RikudouGoku,
Ted's Squig Hoard, ToneDeafMonk, freeryder05 and kr0mka.

YouTube Music and YouTube are trademarks of Google LLC. This is an independent client and is
not affiliated with or endorsed by Google or YouTube.


## Other components

Every other runtime dependency, its version and its declared license is listed in
[docs/dependency-license-audit.csv](docs/dependency-license-audit.csv) and summarized in
[docs/dependency-license-inventory.md](docs/dependency-license-inventory.md): Apache-2.0 for AndroidX (CameraX too, Riff+ only), Kotlin,
Ktor, OkHttp, Okio, Coil, Guava and ZXing (Riff+ only, for the blend code), MIT for SLF4J, and Google's Android SDK terms for the
Google Play services parts Cast uses (closed source, distributed by Google). That inventory is declared metadata, not a
legal review.

Riff Android's `ChunkedStreamDataSource` adapts the design of `ChunkedDataSource` and the range sizes of
`PlayerClient.rangeBytesFor` from [BitChord](https://github.com/kushagrasinghx/BitChord) (GPL-3.0; revision b690dc1).
