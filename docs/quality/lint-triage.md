# Lint and R8 triage (Q18C4)

Source: `app/build/reports/lint-results-debug.xml` from the Q18C1 run (18 October 2026, 19:00), the last full
debug lint; nothing linted in debug has changed since (Q18B4 touched only the stress build type and its manifest).
97 issues: 94 warnings and 3 hints, 0 errors. Every one is listed below by rule with a disposition and a reason.
Nothing is suppressed in bulk: no `lint.xml` severity change, no baseline file, no `@SuppressLint` added here.

Dispositions: **False positive** (lint is wrong about this code), **Accepted** (true, but deliberate; the reason
is given), **Change** (worth changing; listed for the fix package in `q18-bugs.md`), **Upgrade** (a newer version
exists; upgrades are their own change with a full rerun, never a side effect of triage).

## Code

| Rule | n | Where | Disposition | Reason |
| --- | --- | --- | --- | --- |
| Recycle | 1 | LocalAudioRepository.kt:33 | False positive | The cursor is closed by `cursor.use {}` on every path; lint does not follow a cursor assigned first and used on the next line. Commented in the code (Q15 review). |
| StaticFieldLeak | 3 | AppContainer.kt:172, MoodPhotos.kt:134, OfflineMusicStore.kt:28 | False positive | Each singleton keeps `context.applicationContext` (AppContainer.kt:106, MoodPhotos.kt:22, OfflineMusicStore.get), which lives as long as the process; no Activity or View is held. Commented in the code. |
| ExportedService | 1 | AndroidManifest.xml:49 (MusicPlaybackService) | Accepted | A media session service must be reachable by the system, Bluetooth, cars and assistants; a permission would lock them out. Access is decided per controller in `onConnect`: other apps get transport only, no queue edits. Shown on the emulator in Q18C3 (a foreign app could not change the queue but could play and pause). Commented in the manifest. |
| ApplySharedPref | 5 | ContentMigration.kt:26-33 | Accepted | The one-time migration needs each write on disk before the next step: it stops if the first `commit()` fails and only then clears the old stores, so a crash cannot lose both copies. `apply()` would make that ordering untrue. Runs once per install. |
| UseKtx | 41 | 20 files (SharedPreferences.edit, toUri, toColorInt and similar) | Accepted | Style only: the KTX helpers compile to the same calls. Changing 41 call sites for style is churn without a behaviour change; new code may use either. |
| ConfigurationScreenWidthHeight | 2 | DownloadsScreen.kt:194, ImmersiveCollection.kt:36 | Accepted | `screenHeightDp` sizes a header from the screen, not the window. In split screen or a freeform window the header is sized for the full screen; the layout still scrolls and nothing is cut. Revisit if multi-window support becomes a goal. |
| ModifierParameter | 2 | FrostedPopup.kt:74, InlineQueue.kt:46 | Accepted | Compose API convention for public components; both are internal to one screen and called from one place. |
| AutoboxingStateCreation (hint) | 3 | HomeFeed.kt:84, OnDeviceScreen.kt:55, ShellNavigation.kt:212 | Change (low) | `mutableIntStateOf` avoids boxing on each write. Each is a page or tab index written on user action, so the cost is negligible; worth taking in the fix package because it is a one-word change each. Q18-024. |

## Resources

| Rule | n | Where | Disposition | Reason |
| --- | --- | --- | --- | --- |
| HardcodedText | 11 | widget_artwork, widget_circle, widget_compact, widget_large, widget_player, widget_wide (.xml) | Change (low) | The placeholder text "Ready when you are" and "Riff" in the six widget layouts shows until the first update. Both already exist as `@string/ready_when_you_are` and `@string/app_name`; the layouts should use them so pseudo-locales and any future translation cover the widgets too. Q18-024. |
| RtlSymmetry | 1 | widget_player.xml | Change (low) | `paddingStart` without `paddingEnd`: the widget's text sits closer to one edge in a right-to-left layout (ar-XB pseudo-locale). Q18-024. |
| VectorRaster | 1 | drawable/liked_music_art.xml | Accepted | A large vector drawn once as the Liked music cover; it is cached as a bitmap by the image pipeline after the first draw. The Home scroll measured 1.11% missed frames at 120 Hz on the phone (Q18B7). |
| ObsoleteSdkInt | 1 | mipmap-anydpi-v26 | Accepted | The `-v26` qualifier is redundant with minSdk 31 but harmless; renaming the folder changes nothing at run time. |

## Build files

| Rule | n | Where | Disposition | Reason |
| --- | --- | --- | --- | --- |
| UseTomlInstead | 9 | app/build.gradle.kts:217-230 | Accepted | Test-only dependencies (JUnit, Robolectric, AndroidX test) declared inline in the app's build file. Moving them into the catalog is tidiness, not correctness. |
| NewerVersionAvailable | 7 | libs.versions.toml (Kotlin 2.4.21, Coil 3.6.3, Ktor 3.6.0) | Upgrade | Versions are pinned on purpose for a reproducible candidate. Each upgrade is a separate change with the full lanes rerun. |
| GradleDependency | 3 | androidx.test runner 1.7.0, ext.junit 1.3.0, Compose BOM 2026.09.00 | Upgrade | As above. |
| AndroidGradlePluginVersion | 3 | Gradle 8.14.6, AGP 9.4.1 | Upgrade | AGP 9 is a major version with DSL changes; see also the R8 note below, which an AGP upgrade would also settle. |
| OldTargetApi | 1 | targetSdk 36 (37 available) | Accepted | Targeting 37 changes platform behaviour and needs its own pass on an API 37 device or image; 36 is tested on the owner's phone (Android 16). |
| ChromeOsAbiSupport | 2 | release and lite ABI filters | Accepted | Riff ships arm64-v8a (Lite adds armeabi-v7a) to keep the APK small; ChromeOS and x86 phones are not targets. Debug keeps every ABI for the emulator. |

## Q19F4: the warnings added since (19 October 2026)

Source: the debug lint at f463a14 and 9713908 (F1 to F5, the Q18-013 sign-in and the Q19F fixes): 107 warnings, 0
errors, against the 94 above. Only the differences are listed; every rule above keeps its disposition.

| Rule | n | Where | Disposition | Reason |
| --- | --- | --- | --- | --- |
| UnusedResources | 21 | strings.xml: the old sign-in's texts (Opening secure sign-in, Reload page, the slow and failed page messages and others) | **Changed** in Q19F4 | Left behind by the Q18-013 rebuild; each was referenced only by its own definition (checked across app/src and scripts). Removed |
| UseOfNonLambdaOffsetOverload | 1 | AccountScreen.kt, the travelling record | **Changed** in Q19F4 | The record's position animates every frame; the lambda `offset {}` moves it in the placement step without recomposing |
| PluralsCandidate | 7 | strings.xml: signin_see_all, signin_used_days, signin_used_weeks, signin_x_used, x_of_y_lines, summary_x_not_interested, summary_x_widgets | Accepted | English only, and none can read wrongly at its call site: "See all" shows only past three accounts, days start at 2 and weeks at 2 (lastUsedLabel), the widgets count is the six presets, and "1 used on this phone", "1 of 5 lines" and "1 not interested" read correctly. Plurals come with translation, which Riff does not ship |
| ModifierParameter | +1 | SignInDesign.kt, SignInTopBar | Accepted | As FrostedPopup above: internal, every caller passes it by name |
| Recycle | +1 | LyricsShare.kt, saveLyricCard | False positive | The stream is closed by `use {}`; lint does not follow `!!.use` |
| UseKtx | +3 | LyricsShare.kt (createBitmap, withTranslation), SignInDesign.kt (edit) | Accepted | Style only, as above |
| GradleDependency | +1 | libs.versions.toml, Coil 3.6.3 | Upgrade | As above; no upgrade in the final stretch |

After the two changes: 85 warnings, 0 errors.

## R8 Kotlin-metadata warnings

Every shrunk build prints `R8: An error occurred when parsing kotlin metadata` (179 times in the Q18B7 benchmark
build, `artifacts/quality/q18b/b7/build.log`, and nothing else from R8). The cause is a version gap: AGP 8.13.2
bundles R8 8.13.19, which predates the metadata written by Kotlin 2.4.10; Android's compatibility table lists R8
9.1.29 for Kotlin 2.4 (baseline.md). When R8 cannot parse a class's Kotlin metadata it keeps it unrewritten, so the
only code at risk is code that reads Kotlin metadata at run time (kotlin-reflect). Riff has none: kotlinx.serialization
uses generated serializers, and there is no kotlin-reflect dependency. Evidence that the shrunk app works: Riff
Preview (shrunk release) used all day on the owner's phone in Q18A and Q18B, and JourneyCheck on benchmarkRelease
(Q15). Disposition: **Accepted with a known fix**: pin R8 9.1.29 in the buildscript classpath or move to an AGP that
bundles it, then rerun the release lanes. Listed as Q18-024.

## Q19R R5: the final lint (24 October 2026)

Source: the PR lane's debug lint on the final commit (bc69f02, the code of 0.10.0, a9b1299;
`artifacts/quality/lanes/pr-20261024T151040Z` in ..\Riff-final): **93 warnings, 3 hints, 0 errors**. Since Q19F4 the
build moved to AGP 9.4.1 (Q18-024), whose lint adds a rule and drops others, and the Q18 and Q19 fixes added code.
Only the differences from the 85 above are listed; every other rule keeps its disposition. Nothing is suppressed.

| Rule | n | Where | Disposition | Reason |
| --- | --- | --- | --- | --- |
| SetJavaScriptEnabled | 3 | AccountScreen.kt (the sign-in browser), AndroidTokenRuntime.kt (the playback attestation page), InlineYouTubeVideo.kt (live radio's video) | Accepted | Each page needs JavaScript to work at all. The sign-in browser loads only Google's sign-in hosts (security review section 2, WebBoundariesTest); the attestation page runs YouTube's own script with no cookies; the video view plays only live radio. New with AGP 9.4.1's lint, not new code |
| UseKtx | +8 (52) | SharedPreferences.edit, String.toUri, createBitmap and SQLiteDatabase.transaction in code added by the Q18 and Q19 fixes | Accepted | Style only, as above; changing them now would touch every store without a behaviour change |
| PluralsCandidate | +1 (8) | strings.xml collection_n_unavailable ("%1$d unavailable") | Accepted | Reads correctly for any number in English, as the seven above; Riff ships no translations |
| UseTomlInstead | 7 (was 9) | build files | Accepted | As above; two fewer after the AGP move |
| Gone since Q19F4 | | NewerVersionAvailable, AndroidGradlePluginVersion (offline lint, AGP upgraded), HardcodedText, Recycle, RtlSymmetry (fixed or no longer reported) | none | |

The 3 hints are AutoboxingStateCreation, as above (Change, low; left for later).
