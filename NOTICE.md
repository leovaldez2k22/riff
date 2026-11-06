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
Ktor, OkHttp, Okio, Coil and Guava, MIT for SLF4J, and Google's Android SDK terms for the
Google Play services parts Cast uses (closed source, distributed by Google). That inventory is declared metadata, not a
legal review.

## Desktop feasibility preview (1 November 2026)

The opt-in desktop module uses JetBrains Compose Multiplatform 1.12.2 (Apache-2.0), with Skiko/Skia native rendering dependencies. Inter is reused unchanged under SIL OFL-1.1; its license is bundled in the desktop resources. Native transitive notices and corresponding source inventory must be audited before distributing an installer; this preview is not a release artifact.


## Windows walking skeleton (2 November 2026)

The opt-in desktop app now bundles SQLDelight 2.1.0 (Apache-2.0), Xerial SQLite JDBC 3.51.3.0 (Apache-2.0/BSD-2-Clause declared upstream), Ktor 3.6.0 (Apache-2.0), and InnerTubeX desktop 0.11.2 with QuackJS 0.1.2 (GPL-3.0). Native audio dynamically links FFmpeg `n8.1.3-32-g760d10603e-20261102`, supplied by BtbN under LGPL-3.0; see [license](licenses/FFMPEG-LGPL-3.0.txt), [native build/provenance](native/riff-audio/README.md), and [pinned downloads](native/riff-audio/dependencies.json). Zig 0.15.2 is used only for compilation and is not shipped. Local music test recordings and downloaded provider acceptance audio are excluded from the packaged app. These local acceptance builds do not establish public release license/source compliance; complete native transitive notices and corresponding sources remain a release gate.

## Windows lyrics transliteration (3 November 2026)

The desktop module uses ICU4J 78.3 from Unicode, under Unicode License v3 with its included historical third-party notices. The complete upstream release license is retained in [licenses/ICU4J-78.3-LICENSE.txt](licenses/ICU4J-78.3-LICENSE.txt) and copied into desktop resources. The Any-Latin; Latin-ASCII transform runs locally and its mechanical output is labelled Auto. Source-provided romanization takes precedence. No new translation or transliteration network destination is introduced.


Riff Desktop's canonical equalizer ports Riff Android's `EqualizerCurve.kt` and `PcmEqualizer`
to independent Float32 decoder decks. The section layout and equalizer design follow the
[BitChord](https://github.com/kushagrasinghx/BitChord) reference documented in Riff's Feature 10
plan (GPL-3.0; reference revision 04a4a83). The Android PCM16 comparison processor is isolated
test source and is not distributed.

Riff Android's `ChunkedStreamDataSource` adapts the design of `ChunkedDataSource` and the range sizes of
`PlayerClient.rangeBytesFor` from [BitChord](https://github.com/kushagrasinghx/BitChord) (GPL-3.0; revision b690dc1).


Riff Desktop's Float32 pitch-preserving tempo engine includes unmodified [SoundTouch 2.4.1](https://www.surina.net/soundtouch),
copyright Olli Parviainen and contributors, LGPL version 2.1 or later. Full license:
[licenses/SOUNDTOUCH-LGPL-2.1.txt](licenses/SOUNDTOUCH-LGPL-2.1.txt). The pinned corresponding source archive
is retained at `native/riff-audio/vendor/soundtouch-2.4.1.tar.gz` and supplied with the desktop native notices.
