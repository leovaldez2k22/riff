# Dependency, asset and code inventory for 0.10.0

Regenerated 15 October 2026 (Q17) for Riff 0.10.0-rc1 (version code 22). The result was regenerated again on
24 October 2026 after lyrics translation was removed (Q18-101), which took ML Kit and everything it brought out.
Checked for 0.10.0 (version code 33) on 24 October 2026: the release script's audit of rc11 resolved the same 210
release dependencies as `dependency-license-audit.csv`; colours were the only change after it.

**This is an inventory of declared metadata. It is not legal advice, a legal review or a certification of compliance.** It lists what the build resolves and what each artifact's Maven metadata says its license is. Nobody has read every included notice file or compared every license's conditions with GPL-3.0 distribution.

## How it is made

```bash
./gradlew --offline :app:dependencies --configuration releaseRuntimeClasspath > dependencies.txt
python scripts/audit_dependency_licenses.py dependencies.txt
```

The script reads each resolved coordinate's POM from the local Gradle cache and writes [dependency-license-audit.csv](dependency-license-audit.csv). No network is used. A POM with no license stays unresolved unless the upstream license is recorded in the script (Guava, SLF4J). `scripts/build-release-candidate.ps1` does the same for a candidate, from `artifacts/release/dependencies.txt`.

## Result

235 resolved release runtime dependencies on 29 October 2026, after Cast (Feature 19): 231 licenses from POM declarations, 4 from upstream sources (three Guava modules, SLF4J). None unresolved.

| Declared license | Artifacts |
|---|---:|
| Apache-2.0 (under three spellings) | 227 |
| Android Software Development Kit License (Google Play services: `play-services-base`, `-basement`, `-cast`, `-cast-framework`, `-flags`, `-tasks`) | 6 |
| GNU GPL-3.0 (`innertubex`, `innertubex-android`) | 2 |
| MIT (`slf4j-api`) | 1 |

**Closed 24 October 2026:** the seven artifacts under Google's proprietary terms (ML Kit and the Play services parts it brought) are gone with lyrics translation (Q18-101), and so are the Firebase components and Google data-transport libraries ML Kit used. Every artifact left is under an open-source license.

**Reopened 29 October 2026 (Feature 19, Cast):** `androidx.media3:media3-cast` 1.11.1 brings Google Play services' Cast
framework 22.3.1 and five Play services parts under Google's Android SDK terms, closed source, plus AndroidX
MediaRouter 1.8.1 and the AppCompat and Fragment libraries it needs (Apache-2.0). The plan accepted this for Cast. Play
services' Firebase data transport comes with them. Its uploader (`transport-backend-cct`) has to stay in the build,
because the Cast library refers to its classes and R8 refuses a release without them, but Riff's manifest removes its
registration, so the Cast library's own usage reports have nowhere to go and are dropped on the phone (see
`docs/privacy.md`).
Cast does nothing on a phone without Play services.

**Added 29 October 2026 (Feature 9, skip silence on streams):** `androidx.media3:media3-inspector` 1.11.1 (Apache-2.0), Media3's
own extractor behind the `MediaExtractor` API. It needs nothing beyond `media3-exoplayer`, which Riff already has.

## Main components

| Component | Version | License / source |
|---|---|---|
| Riff (this repository) | 0.10.0-rc1 | GNU GPL-3.0; root `LICENSE` |
| InnerTubeX | 0.7.2 (JitPack) | GNU GPL-3.0; [upstream](https://github.com/MetrolistGroup/innertubex) |
| AndroidX: Compose BOM, Activity, Media3 (with Cast), MediaRouter, Profile installer | 2026.06.01, 1.13.0, 1.11.1, 1.8.1, 1.4.1 | Apache-2.0; [AndroidX](https://github.com/androidx/androidx) |
| Google Play services Cast framework (through `media3-cast`) | 22.3.1 | Android Software Development Kit License; closed source |
| Kotlin, kotlinx coroutines and serialization | 2.4.10, 1.11.0, 1.11.0 | Apache-2.0; [Kotlin](https://github.com/JetBrains/kotlin) |
| Ktor client | 3.5.2 | Apache-2.0; [upstream](https://github.com/ktorio/ktor) |
| OkHttp, Okio | 5.3.2, 3.17.0 | Apache-2.0; [OkHttp](https://github.com/square/okhttp), [Okio](https://github.com/square/okio) |
| Coil | 3.3.0 | Apache-2.0; [upstream](https://github.com/coil-kt/coil) |
| QuickJS for Kotlin (`quickjs-kt`) | 1.0.14 | Apache-2.0; [upstream](https://github.com/dokar3/quickjs-kt) |
| Guava (transitive) | 33.3.1-android | Apache-2.0; [upstream](https://github.com/google/guava) |
| SLF4J API (transitive) | 2.0.18 | MIT; [license](https://www.slf4j.org/license.html) |
| Tests only, never in the APK: JUnit, Robolectric, AndroidX Test, Macrobenchmark | 4.13.2, 4.17, 1.5.0, 1.5.0 | EPL-1.0 (JUnit); Apache-2.0 (the rest) |

The build obtains dependencies from Google Maven, Maven Central and JitPack, checked against `gradle/verification-metadata.xml`.

## InnerTubeX provenance (plan 2 S03, 19 October 2026)

InnerTubeX resolves only from JitPack, which builds it from the upstream tag on demand. What Riff accepts is fixed
by hash in `gradle/verification-metadata.xml` (`verify-metadata` is on), so a rebuilt or replaced artifact fails
the build instead of being used:

| Artifact | SHA-256 |
|---|---|
| innertubex-android-0.7.2.aar | `c4c3b4de9c7f943a08ea4fc5c655de05eec289d959906ee971fd52b0dbf07bcc` |
| innertubex-android-0.7.2.module | `e6ed5b4bee292bdddf7416a7ad3f3d022f1f8b0e82eb46a0c6eff52352550517` |
| innertubex-0.7.2.module | `012f046e60e7138faf366fc2515fc15b9d0a0d2a84ec97ef4eb62950385374cd` |
| innertubex-android-0.7.2.pom (not verified by Gradle; recorded here) | `76bba4251a1f238a0d5f8e52343ca39bb4bfd7114b743a71ddbba4d424297016` |
| innertubex-0.7.2.pom (as above) | `69eb404da7fe7e9cec19e8adec8e3edeef4b29f78916543c1e49d7e768ad1a4d` |

The cached files on this computer match the recorded hashes.

Upstream source, fetched with the owner's approval on 20 October 2026:

| Item | Value |
|---|---|
| Tag | `v0.7.2` of github.com/MetrolistGroup/innertubex (GitHub tags API) |
| Commit | `195bac930073233fd2450edc5589033528cccd43` (the tag's commit; also the archive's zip comment) |
| Source archive | `innertubex-0.7.2.zip` from github.com/MetrolistGroup/innertubex/archive/refs/tags/v0.7.2.zip, 516,084 bytes, SHA-256 `af42ab2d091b5e378f44555b03283a9407136c4e07ca6565a9e39079674f908a`; 249 entries, no unsafe paths; `VERSION_NAME=v0.7.2` |
| Kept at | `.toolchain/innertubex-0.7.2-src/` (not tracked; nothing in it was built or run) |

Read from that source: `RemotePlayerConfigStore.validatedSourceUrlOrNull` accepts only HTTPS addresses under
`MetrolistGroup/faraday` on raw.githubusercontent.com, cdn.jsdelivr.net and github.com (releases), as the compiled
0.7.2 classes showed; Riff's ZemerTeam address is refused (Q18-041, left as it is by the owner). The JitPack AAR
is not rebuilt from this source: that fallback (S03 action 3) was declined by the owner (D9), so the link between
this source and the hashed AAR rests on JitPack building the tag.

## Assets in the APK

| Asset | Provenance |
|---|---|
| Inter 4.1 variable (`res/font/inter_variable.ttf`, axes `opsz` 14–32 and `wght`) | The Inter Project Authors, SIL OFL-1.1; `licenses/INTER-OFL.txt`. `InterVariable.ttf` taken unchanged from `Inter-4.1.zip` of the official release (github.com/rsms/inter/releases/tag/v4.1, downloaded 28 October 2026). SHA-256: zip `9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e`, font `4989b125924991b90d05b2d16e0e388c48f7d5bb8b30539bbf9c755278d0ccaf`. The zip's `LICENSE.txt` matches `licenses/INTER-OFL.txt`. |
| 20 fallback covers (`res/drawable-nodpi/cover_fallback_01..20.webp`) | Generated for Riff on 2 October 2026 with an image-generation tool; prompts and process in [licenses/placeholder-art.md](../licenses/placeholder-art.md) |
| Launcher icon, liked-songs art, widget backgrounds and controls (`res/drawable/*.xml`) | Vector drawings in this repository; history in Git |
| Icons in screens | Material icons from `androidx.compose.material:material-icons-extended`, Apache-2.0 |

Not in the APK: the generated test audio (`app/src/androidTest/assets/quality/audio`, made by `scripts/quality/generate_audio_fixtures.py`, test APK only), the fixture provider answers (`scripts/quality/generate_provider_fixtures.py`), the design reference images (`references/`), and the rc20 screenshots (`docs/images/`). Home mood photographs are fetched at run time from Openverse and Wikimedia Commons with their creator and license (CC0, public domain, CC BY, CC BY-SA) and are not bundled.

**Decided (D3, 19 October 2026):** the four design reference images in `references/` (R01 to R04), supplied by the owner on 27 September 2026 as visual references with no recorded copyright holder, stay tracked for the owner's use but are left out of the source archive: `scripts/package-source.ps1` skips them and the packaging lane refuses an archive that contains them (Q19G).

Earlier versions of this file listed generated demo audio and a motion loop. Neither is in the source or the APK any more.

## Code adapted from elsewhere

- `AndroidStreamTokenProvider.kt` and `AndroidTokenRuntime.kt` are adapted from InnerTubeX's GPL-3.0 harness (`HarnessTokenProvider.kt`, `BrowserRuntime.kt`); copyright the InnerTubeX contributors. See `NOTICE.md`.
- No other source file is known to be copied from another project. This has not been checked by a tool.
