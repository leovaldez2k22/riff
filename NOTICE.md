# License and third-party notices

Riff is licensed under GNU GPL version 3. See [LICENSE](LICENSE).

The app depends on [InnerTubeX 0.7.2](https://github.com/MetrolistGroup/innertubex),
copyright its contributors, also licensed under GNU GPL version 3. The dependency is fetched
from JitPack at build time; The Android stream attestation adapter and JavaScript runtime are adapted from its GPL-3.0 harness (HarnessTokenProvider.kt and BrowserRuntime.kt); copyright the InnerTubeX contributors.

Design reference images and retired prototype assets are not bundled in the APK.

The app bundles [Inter 4.1](https://github.com/rsms/inter), copyright The Inter Project Authors,
under the SIL Open Font License 1.1. See [licenses/INTER-OFL.txt](licenses/INTER-OFL.txt).

YouTube Music and YouTube are trademarks of Google LLC. This is an independent client and is
not affiliated with or endorsed by Google or YouTube.


## On-device lyrics translation (rc9)

Google ML Kit Translation 17.0.3 and Language Identification 17.0.6, together with their transitive runtime components, are distributed subject to their respective declared licenses and [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms). Input lyric text and translation output are processed on-device. Google services supply downloaded language models and receive SDK performance and usage metrics. The complete resolved inventory is in `docs/dependency-license-audit.csv`; ML Kit models and native SDK components are provided by Google, rather than the app's own source package.
