# Riff remediation plan

Drafted 2026-10-02 against **Riff 0.9.0-rc16** (versionCode 17). Status: **plan only — no code has been changed.**

This plan fixes the problems found in the project assessment, except:

- **Not covered:** the legal and terms-of-service side of point 1. Its technical side (breakage when YouTube changes) is covered in Phase 8.
- **Not covered:** all of point 6 (hardcoded UI strings and localisation).

Every step names the files it touches, how to do it, and how to prove it worked. Facts about the codebase were checked directly before writing: file and line references, script behaviour, APK contents and test counts.

---

## 0. Overview

| Original problem | Phase | Effort | Outcome |
|---|---|---|---|
| 8. Signing key has no backup; script can silently create a new key | **P0** | 1–2 h | Two verified off-machine backups; script can't replace the key; certificate fingerprint checked on every build |
| 2. No version control; workspace bloat; one-off patch scripts | **P1** | 3–4 h | Git repository with rc4–rc16 rebuilt as real history, tags, and a private remote |
| 9. No Unix `gradlew`; retired `:core:glass` still built; README is a changelog | **P2** (+ P9) | 0.5 day | Builds on any OS, dead module removed, `CHANGELOG.md` |
| 7. 24 compiler warnings, 77 lint warnings | **P2** | 0.5–1 day | Zero warnings; warnings fail the build from then on |
| 4a. Dense code (several statements per line, 108 lines over 200 characters) | **P3** | 0.5 day | One mechanical reformat commit, then enforced formatting |
| 7. No UI tests, no CI | **P4** | 2 days | Compose UI tests on the JVM, a local quality gate, GitHub Actions CI |
| 3. 134 MB APK | **P5** | 1 day | R8 + resource shrinking + arm64-only release; target ≤ 35 MB with a size budget |
| 4b. Huge files, navigation by string, no DI | **P6** | 5–6 days | Typed navigation, provider/service/UI split into focused files and packages, a small manual DI graph |
| 5. Fragile WebView sign-in | **P7** | 2–3 days | Session health model, expiry detection, rotated-cookie persistence, encrypted session storage |
| 1 (technical). Breaks when YouTube changes | **P8** | 2–3 days | Pinned and verified dependencies, owned player-config source, canary script, diagnostics, runbook |
| 9. Sleep timer lost when the process dies | **P9** | 0.5 day | Timer survives the process being killed; README finalised |

**Total:** about 15–19 working days for one person. P0 and P1 must come first. P2–P5 can ship together as rc17. P6 ships as rc18. P7–P9 ship as rc19.

### Decisions needed before starting

1. **Remote:** a private GitHub repository (recommended; P4 CI depends on it), or local-only git?
2. **Release CPU types:** arm64-v8a only (recommended: the target phone, an iQOO I2217, is arm64), or keep armeabi-v7a for older 32-bit phones (+~12 MB)?
3. **Sign-in fallback:** add an optional "paste session headers from a desktop browser" fallback (P7.6), or skip it?
4. **InnerTubeX:** build it from a fork as a composite build (recommended; P8.2), or keep JitPack and add checksum verification only?

### Rules for every phase

- **Branches:** one branch per phase (`fix/p2-build-hygiene` and so on), merged into `main` only when the definition of done passes. `main` is always releasable.
- **No behaviour changes in refactors:** P2, P3, P5 and P6 must not change behaviour. If a behaviour change is found to be needed, it gets its own commit with its own explanation.
- **Commits:** small commits, imperative subject lines, and the reason in the body.
- **Definition of done**, checked at the end of every phase:
  1. `spotlessCheck` (from P3 on), `:app:testDebugUnitTest`, `:app:lintRelease` and `:app:assembleRelease` all pass with zero warnings (from P2 on).
  2. The release APK installs **over the previous release** on the phone (`adb install -r`). The paused queue, position, account session, downloads and settings survive.
  3. Manual smoke checklist:
     - Sign in.
     - Search and play a song (SABR).
     - Play live radio (HLS).
     - Play an official video.
     - Download a song, then play it with the phone in flight mode.
     - Lyrics: word sync, then English translation.
     - Use a home-screen widget to pause, play and skip.
     - Mood photo appears.
     - Sleep timer fades out.
     - Queue drag and swipe-to-remove.
     - Run once on the Android 12 emulator.
  4. Docs updated (`CHANGELOG.md`, plus this plan's status line). Tag created for release phases.

---

## P0 — Protect the signing key (point 8)

**Why first:** this is the only loss that can't be recovered. If `.toolchain/signing/` is lost, every installed copy of Riff has to be uninstalled (losing downloads and saves) before a new build can be installed. Worse, `scripts/build-release-candidate.ps1` lines 38–50 **silently generate a new key** when both files are missing.

### P0.1 Record the certificate fingerprint
1. Run `.toolchain\sdk\build-tools\36.0.0\apksigner.bat verify --print-certs artifacts\release\riff-0.9.0-rc16.apk`.
2. Create `docs/release-signing.md` with:
   - the alias (`musicglass-release`), key type (RSA 3072, PKCS12) and validity (3650 days from first generation; record the actual "Valid until" date from `keytool -list -v`);
   - the **SHA-256 certificate digest**. The digest is public information and safe to commit.
   - where the backups live (described, not the secrets themselves).

### P0.2 Make two off-machine backups
1. **Password manager entry:** attach `musicglass-release.p12` and put the password in the password field.
2. **Encrypted offline copy:** a 7-Zip AES-256 archive (with encrypted file names) of the `.p12` and `password.txt`, protected by a *different* passphrase, on an external drive or USB stick that is normally unplugged.
3. **Restore drill:** extract the archive to a temporary folder and run `keytool -list -v -storetype PKCS12 -keystore <copy> -alias musicglass-release`. Confirm the SHA-256 digest matches P0.1, then delete the temporary copy.

### P0.3 Protect the password at rest on this PC
Replace plaintext `password.txt` with a DPAPI-encrypted file, readable only by this Windows user account:

```powershell
# one-time migration (run after P0.2 is verified)
$plain = (Get-Content .toolchain\signing\password.txt -Raw).Trim()
ConvertTo-SecureString $plain -AsPlainText -Force | ConvertFrom-SecureString |
    Set-Content .toolchain\signing\password.dpapi
# verify round-trip before deleting password.txt
$sec = Get-Content .toolchain\signing\password.dpapi | ConvertTo-SecureString
[Net.NetworkCredential]::new('', $sec).Password -eq $plain   # must print True
```

DPAPI is tied to the Windows user profile, so a reinstalled OS can't decrypt it. That is why the P0.2 backups must hold the plain password.

### P0.4 Harden `scripts/build-release-candidate.ps1`
1. **Read the password** from `password.dpapi`, falling back to `password.txt` for one release cycle. Then delete the fallback.
2. **Never create a key silently.** Generate a key only when `-NewSigningKey` is passed **and** no `artifacts/release/riff-*.apk` exists. Otherwise throw: `Signing key missing. Restore from backup (see docs/release-signing.md).`
3. **Pin the certificate.** After `apksigner verify --print-certs` (around line 71), parse the `SHA-256 digest:` line and compare it with the value recorded in `docs/release-signing.md`. Fail on mismatch.
4. **Refuse a dirty tree** once P1 is done: `git status --porcelain` must be empty, unless `-AllowDirty` is passed.

**Done when:**
- The restore drill passes.
- The release script fails when `.toolchain/signing` is temporarily renamed, rather than generating a key.
- A release build passes the fingerprint check.

---

## P1 — Version control and workspace (point 2)

### P1.1 Rebuild real history from the source snapshots
`artifacts/release/` holds verified source zips for rc4–rc16, listed in `SHA256SUMS.txt`. Importing them in order gives real diffs between release candidates. rc1 and rc3 are optional: they carry about 14 MB of generated demo audio each.

**Do this in a scratch folder, never in the project folder**, because each step wipes the working tree.

```powershell
$project = 'C:\Users\KIIT0001\.gemini\antigravity\scratch\App'
$rel     = "$project\artifacts\release"
$work    = "$env:TEMP\riff-history"
# 1. Verify every zip against SHA256SUMS.txt first; abort on any mismatch.
# 2. Build history.
New-Item -ItemType Directory $work | Out-Null; Set-Location $work
git init -b main
$snapshots = (4..11 | ForEach-Object { "youtube-music-glass-0.9.0-rc$_-source.zip" }) +
             (12..16 | ForEach-Object { "riff-0.9.0-rc$_-source.zip" })
foreach ($zip in $snapshots) {
    Get-ChildItem -Force | Where-Object Name -ne '.git' | Remove-Item -Recurse -Force
    Expand-Archive "$rel\$zip" -DestinationPath $work
    $tag  = 'v' + ($zip -replace '^.*?(0\.9\.0-rc\d+)-source\.zip$', '$1')
    $date = (Get-Item "$rel\$zip").LastWriteTime.ToString('o')
    git add -A
    $env:GIT_AUTHOR_DATE = $date; $env:GIT_COMMITTER_DATE = $date
    git commit -q -m "Import $tag source snapshot" -m "Reconstructed from artifacts/release/$zip."
    git tag $tag
}
Remove-Item Env:GIT_AUTHOR_DATE, Env:GIT_COMMITTER_DATE
```

Then move only the `.git` folder into the project (the project has no `.git` today):
1. `Move-Item $work\.git $project\.git`
2. In the project, run `git status`. It now shows exactly what changed since the rc16 zip. That should be today's native-island probe work (`app/src/androidTest/...NativeIsland*`, `OriginIslandProbePayload.kt`, the androidTest manifest), `docs/spotify-feasibility-assessment-2026-10-02.md`, and files the zip never included (`.gitignore`, `references/`).
3. Before the first real commit, finish P1.2 and P1.3 so ignored files never enter history.
4. Commit in two steps: `chore: add repository metadata (.gitignore, .gitattributes, references)`, then `wip: native-island instrumentation probe` on a branch `wip/native-island`. This keeps `main` equal to rc16 plus metadata.

Set your identity first (`git config user.name` / `user.email`). Optionally copy the hooks from P4.4 at the same time.

### P1.2 `.gitignore` (replace the current 9-line file)
```gitignore
# Build outputs
.gradle/
.kotlin/
**/build/
captures/
*.apk
*.aab
*.idsig
# IDE / OS
.idea/
*.iml
.DS_Store
Thumbs.db
local.properties
# Project-local toolchain: SDK, Gradle, emulator, signing
.toolchain/
# Secrets (defence in depth)
*.p12
*.jks
*.keystore
*.dpapi
password*.txt
release-signing.properties
# Evidence and release outputs live outside git (see docs/artifacts-policy.md)
artifacts/
```
`references/` (6.7 MB of PNGs) is committed. `artifacts/` (2.6 GB) is not.

### P1.3 `.gitattributes` and line endings
The sources are currently CRLF. Store LF in the repository and check scripts out with the endings their tools expect:

```gitattributes
* text=auto eol=lf
*.bat  text eol=crlf
*.ps1  text eol=crlf
gradlew text eol=lf
*.png  binary
*.jpg  binary
*.jar  binary
*.ttf  binary
*.otf  binary
*.p12  binary
```
Run `git add --renormalize .` in its own commit: `chore: normalise line endings`.

### P1.4 Remote and conventions
- **Remote:** `gh repo create riff --private --source . --push`, then `git push --tags`. It must be **private**.
- **Release tags:** `vX.Y.Z[-rcN]` on the exact commit that was built.
- **Version stamp:** P2.7 records the commit SHA in the APK.

### P1.5 Workspace clean-up and artifacts policy
1. **Write `docs/artifacts-policy.md`:**
   - **Locally:** keep the latest two release APKs and all source zips (about 1.6 MB each).
   - **Evidence folders:** keep them under `artifacts/<topic>/`, backed up with the same external drive as P0.2.
   - **Older APKs:** move rc1–rc14 (about 1.5 GB) to the external archive.
2. **Clean `.toolchain/` so it holds tools only.** Move its screenshots, UI dumps (`*.png`, `*.xml`, `*.txt`), `perf-release-signed.apk*` and one-off scripts (`fix-*.py`, `audit-*.py`, `bugfix-check.py`, `lyrics-phone-check.py`) to `artifacts/archive/toolchain-scratch-2026-10-02/`.
3. **No more script patching:** the `fix-*.py` scripts patched source by script. From now on every source change is a commit, so that practice ends here. Add a line to the contributor section of the README (P9.2).

**Done when:**
- `git log --oneline` shows rc4…rc16 plus the metadata commits, with tags.
- `git status` is clean on `main`.
- A fresh `git clone` into a temporary folder contains no `.toolchain`, `artifacts` or key material.
- `git count-objects -vH` reports under 30 MB.

---

## P2 — Build hygiene (point 9 partial, point 7 warnings and lint)

### P2.1 Add the Unix Gradle wrapper
1. Run `.toolchain\gradle-8.13\bin\gradle.bat wrapper --gradle-version 8.13 --distribution-type bin`. This regenerates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and the properties file.
2. Add `distributionSha256Sum=<value>` to `gradle/wrapper/gradle-wrapper.properties`. Copy the value from https://gradle.org/release-checksums/ for *gradle-8.13-bin.zip*; don't type it from memory.
3. Run `git update-index --chmod=+x gradlew`.
4. Add `'gradlew'` to `$topLevel` in `scripts/package-source.ps1` (line 22).
5. Verify on Linux (WSL, or the CI job in P4): `./gradlew --version`.

### P2.2 Remove the retired `:core:glass` module
Checked: nothing in `app/` imports `com.musicglass.glass`, and `app/build.gradle.kts` doesn't depend on it.
1. Run `git tag archive/core-glass` so the prototype is always recoverable.
2. `settings.gradle.kts:21`: change to `include(":app")`.
3. Delete `core/`.
4. `scripts/build-release-candidate.ps1:63`: remove `':core:glass:clean'`.
5. `scripts/package-source.ps1:14` and `:22`: remove `'core\glass\src'` and `'core\glass\build.gradle.kts'`.
6. Root `build.gradle.kts`: remove `alias(libs.plugins.android.library) apply false`, and the `android-library` entry in `gradle/libs.versions.toml`, if nothing else uses them.
7. `README.md:41`: delete the `core/glass` sentence.

### P2.3 Fix all 24 Kotlin compiler warnings
The P0 test build listed these warnings:

| File:line | Warning | Fix |
|---|---|---|
| `AudioOutputControls.kt:99,105,112,132`; `CollectionTools.kt:46`; `SongActionMenu.kt:21–22` | 9 deprecated `Icons.Rounded.*` | Use `Icons.AutoMirrored.Rounded.*` (mirrors correctly in right-to-left layouts) |
| `MainActivity.kt:14–16` | `setDecorFitsSystemWindows`, `statusBarColor`, `navigationBarColor` deprecated | Replace lines 14–18 with `enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), SystemBarStyle.dark(TRANSPARENT))` (activity 1.13) and `window.isNavigationBarContrastEnforced = false` (stops Android 12's 3-button nav scrim). **Visually check** the status bar and nav bar on Android 12 and 16 in gesture and 3-button modes (`scripts/check-layout-modes.ps1`). |
| `MusicPlaybackService.kt:158` | Deprecated `AcceptedResultBuilder(session)` constructor | Use the replacement named in Media3 1.11's deprecation KDoc. If there isn't one yet, use `@Suppress("DEPRECATION")` on that expression only, with a comment |
| `HomeFeed.kt:118`; `MoodPhotos.kt:61,93` (×2 each) | Unnecessary `?.` | Use `.` |
| `OfflineDownloadEngine.kt:77,143` (×2); `YouTubeMusicProvider.kt:334,375` | Unnecessary `!!` | Remove |
| `ProviderArtwork.kt:30` | Condition always true | Read the branch; delete the dead path. Add a test to `ProviderArtworkTest` if the behaviour isn't already covered |

Then make warnings fatal in `app/build.gradle.kts`:
```kotlin
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17); allWarningsAsErrors.set(true) } }
```

### P2.4 Lint: baseline, then burn it down
1. Add to `android {}` in `app/build.gradle.kts`:
   ```kotlin
   lint {
       baseline = file("lint-baseline.xml")
       abortOnError = true
       warningsAsErrors = true
   }
   ```
2. Run `:app:lintRelease` once to write `app/lint-baseline.xml` (about 77 entries), and commit it.
3. Fix the entries by category, one commit per category. Lint issues about string externalisation belong to the excluded point 6: leave them in the baseline with a comment in the commit message.
4. The target is a baseline holding only point-6 entries.

### P2.5 Let release builds run unsigned (needed for CI)
`app/build.gradle.kts` currently always attaches `localRelease`, even when the `MUSIC_GLASS_*` variables are absent. Attach it only when they're present:
```kotlin
release {
    val signed = System.getenv("MUSIC_GLASS_KEYSTORE")?.isNotBlank() == true
    signingConfig = if (signed) signingConfigs.getByName("localRelease") else null
}
```
The release script still sets all four variables, so local releases stay signed. CI builds `app-release-unsigned.apk`.

### P2.6 Extract the changelog
1. Create `CHANGELOG.md` in Keep a Changelog style, newest first:
   - Fill it from the README's first paragraph, one section per version.
   - Link each section to its `docs/app-revamp-rcN.md`.
   - For rc1–rc6, draw on `docs/release-notes-*` and the phase reviews.
2. Cut the README's first paragraph down to two sentences: what Riff is, and the current version.
3. The full README restructure waits for P9, after the build commands and project layout settle.

### P2.7 Stamp the commit into the build
```kotlin
val gitSha = providers.exec { commandLine("git", "rev-parse", "--short=12", "HEAD") }
    .standardOutput.asText.map { it.trim() }.orElse("unknown")
defaultConfig { buildConfigField("String", "GIT_SHA", "\"${gitSha.get()}\"") }
```
Settings → About and the diagnostics screen (P8.4) show it. The release script writes it into `SHA256SUMS.txt` next to the APK hash.

**Done when:**
- `./gradlew --version` works on Linux.
- A clean build shows **0** Kotlin warnings and **0** lint warnings outside the baseline.
- `:core:glass` is gone, and the release and source-package scripts still succeed.

---

## P3 — Mechanical reformat (point 4, density)

The goal is one large commit that changes layout only, then enforcement so the dense style can't come back.

1. **Tooling.** Add Spotless with ktfmt in Kotlin-language style:
   - Pin the latest stable versions at implementation time in `libs.versions.toml`.
   - Add an `.editorconfig` with `max_line_length = 100`, `indent_size = 4`, `insert_final_newline = true` so Android Studio matches.
   ```kotlin
   // root build.gradle.kts
   plugins { alias(libs.plugins.spotless) }
   spotless {
       kotlin { target("app/src/**/*.kt"); ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle() }
       kotlinGradle { target("*.gradle.kts", "app/*.gradle.kts"); ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle() }
   }
   ```
   ktfmt rebuilds layout from the syntax tree, so it splits `a();b()` onto separate lines, wraps the 108 lines over 200 characters, and removes unused imports (for example `kotlinx.coroutines.flow.collect` in `RiffApp.kt:124`). Wildcard imports (`kotlinx.coroutines.*`, `androidx.compose.ui.input.key.*`) are replaced with explicit ones by hand in the same commit.
2. **Order.** Do this after P2 and before any refactor, so P6 diffs are readable.
3. **Prove nothing changed.**
   - **Non-Compose files** (provider, playback service, policies, lyrics, offline, widgets): compile before and after, run `javap -c -p` over `app/build/tmp/kotlin-classes/debug/**/*.class` for those classes, and diff. Output must be **identical**.
   - **Compose files:** the Compose compiler embeds source positions (group keys, `sourceInformation`, trace strings), so their bytecode legitimately changes. Rely on the 142 unit tests plus a before/after screenshot pass with `scripts/check-shell.ps1` instead.
4. **Commit and hide it from blame.** Commit as `style: reformat with ktfmt (no functional change)`. Add the SHA to `.git-blame-ignore-revs` and run `git config blame.ignoreRevsFile .git-blame-ignore-revs`.
5. **Enforce it:** `spotlessCheck` runs in the local gate and CI (P4).

**Done when:** `spotlessCheck` passes, the non-Compose bytecode diff is empty, all tests pass, and the screenshots match.

---

## P4 — Tests and CI (point 7)

The 142 unit tests cover pure logic well. What's missing:
- UI-level tests;
- automated runs of the existing device smoke tests (`OfflineSmokeInstrumentation`, `TranslationSmokeInstrumentation`, `NativeIslandInstrumentation`, which use raw `android.app.Instrumentation`);
- any automatic gate.

### P4.1 Compose UI tests on the JVM (Robolectric)
```kotlin
// app/build.gradle.kts
android { testOptions { unitTests.isIncludeAndroidResources = true } }
dependencies {
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:<latest stable>")
}
```
- **SDK level:** pin `@Config(sdk = [35])` unless the chosen Robolectric release supports API 36. The app targets 36.
- **Disable the glass shader in tests.** Run UI tests with `AccessibilitySettings` solid controls **on**, so the AGSL `RenderEffect` path (which Robolectric doesn't render) is skipped.
- **Fake playback.** `PlaybackGateway` is already an interface (`PlaybackGateway.kt:45`). Add `FakePlaybackGateway` in `app/src/test`.

First tests to write. These pin down current behaviour **before** P6 moves the code:
1. **Tab navigation:** tapping a tab selects it. A double-tap within 350 ms resets that tab's stack and filters (`ShellModel.selectFromTap`, `RiffApp.kt:160`).
2. **Back-stack order:** sheet → stack → Home, including the collection trail (`ShellModel.back`, `RiffApp.kt:183`).
3. **Mini player:** it appears when `mediaId` becomes non-blank and the sheet clears when it goes blank.
4. **Sleep timer dialog:** presets, custom 1–1440 validation, and the Cancel label when active (`SleepTimer.kt`).
5. **Queue panel:** the accessibility move-up and move-down actions reorder through the gateway.
6. **Account screen:** signed-out and signed-in button states. This needs the provider seam from P6.2, so add it during P6.

### P4.2 Provider parser fixtures
- **Fixtures:** capture real InnerTube JSON once into `app/src/test/resources/fixtures/`:
  - home;
  - search;
  - album continuation;
  - library playlists;
  - `next` with the Song/Video switcher;
  - native `timedLyricsData`.
- **Scrub personal data first:** account name, avatar URLs, private playlist titles, `DATASYNC_ID` and visitor data.
- **Tests:** test the parse functions that P6.2 moves into `ProviderJsonParsers.kt`. Until then, test them through the existing internal functions (`catalogPage`, `parseHomeCard`).
- **Value:** when YouTube changes a response shape, a new fixture plus a failing test pins down the breakage exactly (this ties into P8).

### P4.3 Device smoke script
Add `scripts/run-smoke.ps1 -Serial <id> [-Release]`. It should:
1. Install the app plus the test APK.
2. Run each instrumentation with `adb shell am instrument -w com.musicglass.personal.test/com.musicglass.personal.<Name>`.
3. Save output to `artifacts/device-checks/smoke/<timestamp>/`.
4. Exit non-zero on any failure.

It must work both on the Android 12 x86_64 emulator (`.toolchain/avd/riff_android12`) and on the phone.

### P4.4 Local quality gate
- **Gate script:** `scripts/check.ps1` runs `spotlessCheck :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`, then the APK size budget from P5.5.
- **Hook:** `.githooks/pre-push` calls it. Enable with `git config core.hooksPath .githooks`.

### P4.5 GitHub Actions (needs P1.4 remote, P2.1 `gradlew`, P2.5 unsigned release)
`.github/workflows/ci.yml`:
```yaml
name: ci
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: android-actions/setup-android@v3
      - run: sdkmanager "platforms;android-37.0" "build-tools;36.0.0"
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew spotlessCheck :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --console=plain
      - name: APK size budget (40 MiB)
        run: |
          f=app/build/outputs/apk/release/app-release-unsigned.apk
          s=$(stat -c%s "$f"); echo "APK bytes: $s"; test "$s" -le 41943040
      - uses: actions/upload-artifact@v4
        with:
          name: release-unsigned
          path: |
            app/build/outputs/apk/release/*.apk
            app/build/outputs/mapping/release/mapping.txt
            app/build/reports/lint-results-release.html
```
- **Live tests stay local.** Tests that call YouTube are not run in CI: data-centre IPs are commonly blocked or challenged, so they'd be flaky. They run through P4.3 and the P8.3 canary.
- **Signing stays local.** Signing keys never go to CI. Signed releases are built locally with P0.4's script.

**Done when:**
- The new UI and fixture tests pass on the JVM.
- CI is green on `main`.
- The pre-push hook blocks a push with a formatting error (tried deliberately once).

---

## P5 — Release size: 134 MB → ≤ 35 MB (point 3)

Measured composition of `riff-0.9.0-rc16.apk` (135.9 MB uncompressed):

| Item | Size |
|---|---|
| `classes*.dex` (4 files, R8 off) | ≈ 61.4 MB |
| `libtranslate_jni.so` (ML Kit) for x86_64 / x86 / arm64 / armv7 | 17.4 + 17.3 + 16.4 + 11.6 ≈ 62.7 MB |
| `liblanguage_id_l2c_jni.so` ×4 | ≈ 4.4 MB |
| `libquickjs.so` ×4 (InnerTubeX → quickjs-kt) | ≈ 3.5 MB |
| Resources and the rest | ≈ 4 MB |

The jump from 63 MB (rc5) to 134 MB (rc9) is when ML Kit translation was added.

### P5.1 Enable R8 and resource shrinking
```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        ndk { abiFilters += "arm64-v8a" }          // decision 2
        signingConfig = /* P2.5 */
    }
}
```

### P5.2 `app/proguard-rules.pro` (new file)
```proguard
# Readable stack traces; keep mapping.txt per release (P5.4).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The app shows and logs exception class names via ::class.simpleName / javaClass.simpleName
# (16 sites, e.g. YouTubeMusicProvider.kt:855-856, OfflineDownloadEngine.kt:153,
# AndroidStreamTokenProvider.kt:156-179). Keep them readable instead of "a"/"b".
-keepnames class * extends java.lang.Throwable

# quickjs-kt: native code calls back into JVM classes by name.
-keep class com.dokar.quickjs.** { *; }
```
These dependencies ship their own consumer rules and need nothing extra unless the build reports problems: Media3 (including the HLS factory it loads by reflection), ML Kit, kotlinx.serialization (123 `@Serializable` classes in InnerTubeX), Coil, Compose and OkHttp.

Checked:
- **No app reflection:** no `Class.forName`, `getDeclared*` or `getIdentifier` in `app/src/main`.
- **No JavaScript bridge:** there's no `@JavascriptInterface`.
- **Components are safe:** manifest components (activity, services, job services, six widget receivers) are kept automatically.

### P5.3 Iterate until clean
1. Run `:app:assembleRelease`. If R8 fails, copy the suggested `-dontwarn` lines from `app/build/outputs/mapping/release/missing_rules.txt` into `proguard-rules.pro`, **one dependency at a time, each with a comment**.
2. Run the smoke checklist on a **minified** build. The highest-risk paths are:
   - SABR playback (InnerTubeX serialization);
   - HLS live radio (reflection-loaded factory);
   - PO token minting (QuickJS + WebView);
   - ML Kit translation;
   - download, remux and offline playback;
   - widgets.
3. **Emulator note:** the emulator is x86_64, but the release APK will be arm64-only. When `-PreleaseSmoke=true` (the existing switch in `app/build.gradle.kts`), also add `"x86_64"` to `abiFilters`, so the minified build can run instrumentation on the emulator. The shipped APK stays arm64-only.

### P5.4 Keep R8 mappings
The release script copies `app/build/outputs/mapping/release/mapping.txt` to `artifacts/release/riff-<version>-mapping.txt`. Without it, stack traces from future releases can't be decoded. Keep mappings for as long as the APK is kept.

### P5.5 Size budget
`scripts/check.ps1` and CI fail if the release APK is over **40 MiB**. Record the measured size of each release in `CHANGELOG.md`.

**Expected result:** about 6–10 MB of dex plus about 19–20 MB of arm64 native code, so roughly **25–35 MB**. Native libraries stay uncompressed (the default for minSdk ≥ 23). That's intentional: it avoids extracting them at install time.

**Optional further step (not in the estimate):** ML Kit translation alone is about 16 MB on arm64. If translation is rarely used, a later release could move it into an optional download. That isn't practical for a sideloaded APK without Play Feature Delivery, so it isn't recommended now.

**Done when:**
- The APK is ≤ 35 MB.
- The full smoke checklist passes on the minified build, on the phone and on the emulator (x86_64 smoke variant).
- The upgrade install over rc16 keeps all data.
- The mapping file is archived.

---

## P6 — Structural refactor (point 4, architecture)

Approach: replace the old code piece by piece, behind the tests from P4. Each numbered step is one branch, merged on its own, with the app working after every merge.

**Rule that must not be broken:** the following classes **stay in package `com.musicglass.personal` under their current names**. The launcher, JobScheduler and placed widgets refer to them by name: moving one breaks the launcher icon, removes placed widgets, or crashes pending jobs.
- `MainActivity`
- `MusicPlaybackService`
- `OfflineDownloadService`
- `MoodPhotoRefreshJob`
- `OfflineDownloadJob`
- `CompactRiffWidget`, `WideRiffWidget`, `ArtworkRiffWidget`, `PlayerRiffWidget`, `LargeRiffWidget`, `CircleRiffWidget`

The application ID also stays `com.musicglass.personal`.

### P6.1 Typed navigation (replaces the string routes)
Today tabs, sheets and routes are plain strings compared in many places:
- **Sheets:** `"mini"` ×8, `"lyrics"` ×5, `"queue"` ×4, `"libraryShortcuts"` ×3, `"queueCollapsed"` ×2.
- **Routes:** `"account"`, `"device"`, `"settings"`, `"downloads"`, `"collection"`, `"accessibility"`, `"remote_library"`.

Replace them with types. New file `ui/shell/Navigation.kt`:
```kotlin
internal enum class Tab(val label: String) { Home("Home"), Podcasts("Podcasts"), Radio("Radio"), Search("Search"), Library("Library") }
internal enum class Sheet { None, Mini, Queue, QueueCollapsed, Lyrics, LibraryShortcuts }
@Serializable internal sealed interface Route {
    @Serializable data object Account : Route
    @Serializable data object Device : Route
    @Serializable data object RemoteLibrary : Route
    @Serializable data object Accessibility : Route
    @Serializable data object Settings : Route
    @Serializable data object Downloads : Route
    @Serializable data class Collection(val id: String, val title: String) : Route
}
```
- **Collection routes carry their own data.** `Route.Collection` holds its id and title, so the parallel `selectedPlaylistId` / `selectedCollectionTitle` fields and the `collectionTrail: List<Triple<…>>` workaround (`RiffApp.kt:178–192`) go away. The back stack itself remembers which collection each entry was.
- **Closed lists become enums:** `libraryFilter`, `searchFilter` and `searchSort`.
- **Data-driven lists stay text:** `podcastGenre` and `radioCategory` come from data (`RadioCatalog.kt`), so they stay `String`, wrapped in a `@JvmInline value class` for type safety.
- **New saved-state format:** replace the positional `List<String>` Saver (`RiffApp.kt:200–243`, with its `"rc7"` marker and index arithmetic) with a `@Serializable data class ShellState(val version: Int = 2, …)`, stored as JSON in the Saver.
- **Old saved state still restores:** keep the old reader as `decodeLegacyShellState(List<String>)`. The existing `ShellModelRestoreTest` cases become its tests, so upgrades from rc16 keep restoring.
- **Size limits:** keep the existing caps (title ≤ 180, id ≤ 200, stack ≤ 8) to stay well inside the saved-state size limit.

### P6.2 Split `YouTubeMusicProvider.kt` (993 lines, one class doing everything)
Target layout, package `com.musicglass.personal.provider`:

| New file | Moves from (current lines) | Responsibility |
|---|---|---|
| `ProviderModels.kt` | 55–78 | `ProviderTrack`, `ProviderHomeCard`, `ProviderStream`, … |
| `YouTubeSession.kt` | 129–139, 196–257 | Cookies/visitor/sync ID, `signedIn`, `authRevision`, `accountScopeKey`, sign-in/out. Also merges the **copy-pasted cache reset** in `refreshAccount` (208–213) and `signOut` (235–241) into one `resetSessionState()` |
| `ProviderCaches.kt` | 112–127, 140–143 | Feed, collection, search and stream caches; `invalidateAll()` |
| `CatalogClient.kt` | 260–341, 354–370, 484–511, 550–702 | Browse, search, home, podcasts, radio, profile, history |
| `LibraryClient.kt` | 343–405, 449–483, 658–681 | Library, playlist edits, likes, saving albums |
| `StreamResolver.kt` | 157–172, 782–858 | Extractor, cipher, tokens, `resolve` single-flight cache, HLS manifest preparation, `videoFor` |
| `ProviderJsonParsers.kt` | 875–993 | Pure parse functions (tested by P4.2 fixtures) |
| `ProviderHttp.kt` | 148–156, 184–192 | Ktor client, the `/next` pairing interceptor |

Migration steps:
1. Move the pure parsers and models first; nothing else changes.
2. Extract `YouTubeSession`. The other clients take it in their constructor and read `session.revision`.
3. Extract one client at a time. `YouTubeMusicProvider` stays as a **thin front** that delegates, so the roughly 40 call sites don't all change at once.
4. Move call sites to the specific clients screen by screen.
5. Delete the front, or keep it only as a small holder object.

Add interfaces `CatalogSource`, `LibrarySource`, `StreamSource` and `SessionSource`, so UI tests (P4.1 test 6) and service tests can use fakes.

### P6.3 Split `MusicPlaybackService.kt` (489 lines; `onCreate` alone is lines 67–328)
Package `com.musicglass.personal.playback`. The **service class itself stays in the root package** (see the rule above).

| New file | Moves from |
|---|---|
| `CatalogMediaSourceFactory.kt` | 74–112 (deferred catalog source, offline substitution, merged video, SABR/HTTP data sources, disk cache) |
| `RiffSessionCallback.kt` | 154–307 (`onConnect` allow-list, custom commands) |
| `PlaybackSnapshotStore.kt` | 59–66, 308–318, 352–379 (restore, save, persist on a conflated channel) |
| `RadioQueue.kt` | 46–52, 398–403, 428–464 (recommendations, de-duplication, replenishing) |
| `LiveProfileFallback.kt` | 45–46, 404–427 |
| `ListeningTracker.kt` | 57–58, 320–326 (the 5-second listening loop) |
| `WidgetCommandHandler.kt` | 329–341 |

`onCreate` ends up as about 30 lines that assemble these parts.

### P6.4 Split the UI god-files
- **`RiffApp.kt` (1,027 lines)** → package `ui/shell/`:
  - `RiffApp.kt` (root composable only);
  - `ShellState.kt` (P6.1);
  - `ChromeBar.kt`, `MiniPlayer.kt`, `CollapsedQueue.kt`, `PlayerMorphHost.kt`, `LiquidTabBar.kt`, `GlassScene.kt`;
  - plus `ui/theme/Colors.kt` for `Ink`, `Raised`, `Muted`, `Pink` and `White` (lines 126–130).
- **`ShellScene.kt` (750 lines)** → one file per screen:
  - `ui/search/SearchScreen.kt`;
  - `ui/library/LibraryScreen.kt`;
  - `ui/collection/ProviderCollectionScreen.kt`;
  - `ui/account/AccountScreen.kt` (P7 builds on this);
  - `ui/settings/AccessibilityScreen.kt`;
  - `ui/device/OnDeviceScreen.kt`;
  - `ui/common/Headings.kt` (`ScreenTitle`, `SectionTitle`, `BackHeading`, `Eyebrow`, `ToolLink`).
- **`NowPlaying.kt` (528 lines)** → `ui/player/`. `NowPlayingPanel` (lines 161–471, 310 lines) splits into its artwork, title/actions, seek, transport and footer sections.
- **Visibility:** helpers that were `private` at file level and are now shared across files become `internal`. Nothing becomes `public`.

### P6.5 Move the remaining flat files into packages
- `lyrics/`: `Lyrics*.kt`, `YouTubeLyricsRequest.kt`
- `offline/`: `Offline*.kt`, `WebmSeekIndex.kt`, `MusicDiskCache.kt`, `MusicCachePolicy.kt`
- `glass/`: `*Glass*.kt`, `NavigationLens.kt`, `AgslNavigationLens.kt`
- `motion/`: `GlassMotion.kt`, `MotionFeedback.kt`, `UiRefreshRate.kt`, `UiHaptics.kt`
- `widgets/`: everything except the six receiver classes
- `settings/`, `home/`, `radio/`, `video/`

Tests move to matching packages. Use the IDE's move refactoring, one package per commit.

### P6.6 A small manual DI graph (no Hilt)
Hilt or Koin would add annotation processing and build time for little gain in a single-module personal app. Instead:
1. Add `RiffApplication : Application` with `val graph: AppGraph by lazy { AppGraph(this) }`. Register it with `android:name=".RiffApplication"` in the manifest (this is a new class, so the component-name rule doesn't apply).
2. `AppGraph` owns the singletons now created in scattered places:
   - `YouTubeMusicProvider.shared` (then its replacements from P6.2);
   - `OfflineMusicStore.get` (`OfflineMusicStore.kt:20`);
   - `MoodPhotos.get` (`MoodPhotos.kt:135`);
   - `MusicDiskCache.get` (`MusicDiskCache.kt:12`);
   - `PlaybackEventLog`;
   - `LyricsRepository`.
3. Replace `remember(context) { X(context) }` in `RiffApp.kt:273–281` and the `.get(context)` calls with `LocalAppGraph.current.x`, provided once in `MainActivity` and read via `(applicationContext as RiffApplication).graph` in services and jobs.
4. For tests, `AppGraph` takes its parts through constructor parameters with production defaults, so tests swap in fakes.

### P6.7 Screen state that survives rotation (optional, recommended)
Search results and pagination, library pages and collection pages are kept in composable `remember` today and are lost on configuration changes such as rotation or dark-mode toggles. Move these into `ViewModel`s (`SearchViewModel`, `LibraryViewModel`, `CollectionViewModel`), created through a factory that reads `AppGraph`. Add `androidx.lifecycle:lifecycle-viewmodel-compose`, version from the Compose BOM ecosystem.

### P6.8 Keep the structure from decaying
- Add **detekt** with a baseline, using `LargeClass` (600 lines), `LongMethod` (80), `LongParameterList` (8) and `ComplexCondition`.
- Wire it into `scripts/check.ps1` and CI.
- The baseline should only ever shrink.

**Done when:**
- No file in `app/src/main` is over about 400 lines.
- Navigation has no string literals for tabs, sheets or routes.
- Upgrading from rc17 restores the shell state (legacy decoder test).
- All component class names in the manifest are unchanged.
- All tests and the smoke checklist pass.
- detekt is green.

---

## P7 — Sign-in robustness (point 5)

How it works today:
- **Sign-in page:** `ShellScene.kt:479` loads `accounts.google.com/ServiceLogin` in an in-app WebView with JavaScript, DOM storage and third-party cookies on.
- **Finish sign-in:** reads `VISITOR_DATA`, `DATASYNC_ID` and `SESSION_INDEX` from the page (`ShellScene.kt:489`).
- **Session check:** `refreshAccount()` (`YouTubeMusicProvider.kt:196`) treats the session as valid if a `SAPISID`-family cookie *exists*.
- **No expiry detection:** `providerResult` (`:269`) turns every error into a generic message, so an expired session looks like "could not load".
- **Rotated cookies are lost:** cookies refreshed through `Set-Cookie` on API responses never reach the WebView cookie store.

### P7.1 Session state model
In `YouTubeSession` (from P6.2):
```kotlin
internal sealed interface SessionState {
    data object SignedOut : SessionState
    data object Checking : SessionState
    data class Active(val profile: ProviderProfile?) : SessionState
    data object Expired : SessionState   // cookies present but rejected
}
```
- **Replaces two flags:** `signedIn` and `accountProfile`.
- **Expired banner:** the UI shows one non-blocking banner, "Your YouTube Music session expired. Sign in again.", with a button that opens the account screen.
- **Nothing is deleted on expiry:** local saves, downloads and the account scope are kept.

### P7.2 Typed provider errors
Replace the string mapping in `providerResult` with:
```kotlin
internal sealed class ProviderError(message: String) : Exception(message) {
    class Offline : ProviderError("You're offline.")
    class SessionExpired : ProviderError("Your YouTube Music session expired.")
    class Unavailable(val http: Int?) : ProviderError("YouTube Music is unavailable right now.")
    class Unsupported(detail: String) : ProviderError(detail)   // e.g. bounded-range streams
    class Parse : ProviderError("YouTube Music returned something Riff can't read yet.")
}
```
- **Classification rules:**
  - `UnknownHostException` / `ConnectException` → `Offline`;
  - HTTP 401/403 on an authenticated request, or the logged-in check below → `SessionExpired`;
  - other HTTP ≥ 400 → `Unavailable`;
  - serialization or JSON errors → `Parse`.
- **Event log:** P5's `-keepnames` keeps class names readable, and the log records `error=<category>`.

### P7.3 Detect expiry from the responses themselves
Authenticated InnerTube responses carry a logged-in indicator in `responseContext.serviceTrackingParams`, typically a `logged_in` key with value `0` or `1`.
1. **Confirm with fixtures first.** Capture a signed-in and a signed-out browse response into P4.2 fixtures and confirm the exact field.
2. **Code against the fixture:** a request sent with credentials that comes back `logged_in=0` means `SessionExpired`.
3. **Health checks:** run a lightweight check (`profile()`) at app start (debounced), on return from more than 6 hours in the background, and after any `SessionExpired`.

### P7.4 Keep rotated cookies (longer-lived sessions)
1. **Read InnerTubeX first.** Read `InnerTube.replaceSession` and its cookie handling in `.toolchain/innertubex/src/commonMain` to confirm whether it already applies `Set-Cookie`. Document the answer in the commit.
2. **If it doesn't,** extend the existing `HttpSend` interceptor (`YouTubeMusicProvider.kt:184`) to read `Set-Cookie` from responses whose host is `music.youtube.com`, `www.youtube.com` or `accounts.google.com`. Merge by cookie name into the session's cookie map, call `innerTube.replaceSession(...)` with the merged header, and persist it (P7.5).
3. **Never** log cookie names or values. The event log only records `session_cookie_rotated count=N`.
4. **Unit-test the merge function:** overwrite, add, delete by `Max-Age=0` or a past `Expires`, and ignore other domains.

### P7.5 Encrypt the session at rest
Today the session lives in the WebView's plaintext cookie database. That database is app-private, and backup and transfer are disabled (`allowBackup=false`; `data_extraction_rules.xml` excludes everything), so this step is defence in depth.
1. **Key:** create an AES-256-GCM key in the **Android Keystore** (`KeyGenParameterSpec`, `PURPOSE_ENCRYPT|DECRYPT`, not exportable). Don't use `EncryptedSharedPreferences`, which is deprecated.
2. **Store:** after "Finish sign-in", copy the cookie header for `music.youtube.com` into `SessionVault` (encrypted blob plus IV in SharedPreferences), then `CookieManager.removeAllCookies` and `flush()`. From then on the provider reads cookies only from `SessionVault`, which P7.4 updates.
3. **Recovery:** if decryption fails (key invalidated, restored device), treat the session as `SignedOut` and show "Sign in again". Never crash.
4. **Sign-out** (`YouTubeMusicProvider.kt:228` / `ShellScene.kt:510`) must also:
   - clear `SessionVault`;
   - call `WebStorage.getInstance().deleteAllData()` (DOM storage is on in the sign-in WebView and is not cleared today);
   - clear the sign-in WebView's cache (`clearCache(true)`) and history.

### P7.6 Harden the sign-in WebView
1. **Navigation allow-list.** In `shouldOverrideUrlLoading`, allow only `https` and hosts ending in `.google.com`, `.youtube.com`, `.gstatic.com`, `.googleusercontent.com` (plus country-specific Google sign-in domains as observed on the phone). Block everything else.
2. **Third-party cookies.** Try sign-in with them **off**. Keep them on only if Google's flow fails without them, and record the result in the commit message.
3. **Detect rejection.** When Google refuses embedded sign-in (its "This browser or app may not be secure" page), show a clear explanation instead of a dead end. Capture the real rejection page on the phone once and match on its URL path. **Don't** change the user agent or otherwise disguise the WebView to get past Google's check: that's a security control, and fighting it risks the account.
4. **Optional fallback (decision 3).** An "Advanced: paste request headers" screen, the same approach ytmusicapi uses: the user copies the `Cookie` header from a signed-in desktop browser session.
   - Validate it: it must have a `SAPISID`-family cookie, ≤ 16 KB, no CR/LF (same checks as `AndroidStreamTokenProvider.kt:72`).
   - Store it only in `SessionVault`.
   - Clear the input field and clipboard afterwards.
   - Warn plainly that the header grants full access to the Google account.

**Done when:**
- Expiry is detected and shown as the banner: simulate it by corrupting the vaulted cookie.
- Rotated cookies persist across app restarts.
- After sign-out, the WebView storage, cookies and vault are all empty: check `app_webview/` and the shared preferences with `adb shell run-as` on a debug build.
- All new logic has unit tests.

---

## P8 — Provider resilience (point 1, technical only)

Playback depends on several moving parts:
- InnerTubeX 0.7.2 (built by JitPack);
- a player configuration downloaded at runtime from **the `master` branch of a third-party GitHub repository** (`YouTubeMusicProvider.kt:84–86`: `raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/...`);
- BotGuard attestation;
- YouTube's live response shapes.

The goal: notice breakage early, diagnose it fast, fix it without waiting for others, and degrade gracefully.

### P8.1 Own the player-config source
1. **Audit what the config controls.** Read `RemotePlayerConfigStore` and its parser in `.toolchain/innertubex` and write down which fields it reads (player URLs, signature timestamps, cipher hints?). This decides how much harm a bad or hostile config could do.
2. **Pin it to an exact version.** Change `sourceUrl` from `/master/` to a **commit-SHA URL**, or to a fork under your own account, and update it deliberately.
3. **Ship a fallback.** Bundle a known-good snapshot as `app/src/main/assets/player_configs.json`. Use it when the network fetch fails and nothing is cached.
4. **Validate before caching:** size ≤ 256 KB, parses as JSON, expected keys present. Only replace `cachedJson` (`:87`) after validation, so a broken upstream can't wipe the last good copy.

### P8.2 Control the InnerTubeX dependency (decision 4)
1. **Checksums for every dependency.** Run `./gradlew --write-verification-metadata sha256 help` and commit `gradle/verification-metadata.xml`. A rebuilt or tampered JitPack artifact then fails the build instead of being picked up silently.
2. **Recommended: build it from your own fork.**
   - Fork `MetrolistGroup/innertubex` at tag `0.7.2`.
   - Add it as a git submodule at `third_party/innertubex`.
   - In `settings.gradle.kts`:
     ```kotlin
     includeBuild("third_party/innertubex") {
         dependencySubstitution {
             substitute(module("com.github.MetrolistGroup.innertubex:innertubex"))
                 .using(project(":"))
         }
     }
     ```
   - When YouTube changes break extraction, patch the fork, rebuild and release, without waiting for an upstream release or JitPack.
   - **Spike this first (half a day).** It's a Kotlin Multiplatform build with desktop and native targets, so confirm that only the Android target is configured on Windows.
   - **Fallback if the composite build is impractical:** publish the fork to a file-based Maven repository in `third_party/m2` and point `settings.gradle.kts` at it instead of JitPack.
3. **Licence notices:** keep `NOTICE.md` accurate (GPL-3.0, link to the fork and commit).

### P8.3 Provider canary
1. Add `ProviderCanaryInstrumentation` (raw `Instrumentation`, like the existing smoke tests). It runs against the live service and checks:
   - search for a fixed query returns ≥ 5 songs;
   - home returns ≥ 3 shelves;
   - `resolve` succeeds for three fixed IDs: a normal song (SABR path), a live station (HLS path), and `download=true` (HIGH quality, bounded range);
   - it can read the first 256 KB of each;
   - lyrics come back for a known synced song;
   - the PO token stage completes.
2. It writes a JSON report (stage, timing, error category, no personal data) to `artifacts/device-checks/canary/<date>.json`.
3. `scripts/provider-canary.ps1` runs it on the emulator or phone. Schedule it **weekly** with Windows Task Scheduler, and run it **before every release** (add it to the definition of done).
4. A failing canary with green unit tests means the provider changed: follow the P8.6 runbook.

### P8.4 In-app diagnostics
Add Settings → About → **Diagnostics** showing:
- app version and `BuildConfig.GIT_SHA` (P2.7);
- InnerTubeX version and player-config source and age;
- session state (P7.1);
- the last 80 `PlaybackEventLog` entries (already free of personal data by design: `PlaybackEventLog.kt:5`).

Add a **Copy report** button. This turns "it doesn't play" into a precise failure stage, with no need for a USB cable.

### P8.5 Degrade gracefully
1. **Retire failing playback clients.** Generalise the live-only `failedLiveProfiles` / `retryLiveProfile` mechanism (`MusicPlaybackService.kt:45–46, 404–427`) to on-demand songs. After 3 consecutive `resolve` failures for a client profile, exclude it for 30 minutes, using the existing `excludedProfiles` parameter of `resolve` (`YouTubeMusicProvider.kt:790`).
2. **Offer downloads when the service is down.** If resolving keeps failing while there's a network connection (`ProviderError.Unavailable` or `Parse`), the player error offers "Play your downloads". The offline path doesn't need extraction (`MusicPlaybackService.kt:77–82`).
3. **No retry storms:** `providerResult`'s session-refresh retry stays a single retry, as it is today.

### P8.6 Breakage runbook
Write `docs/provider-breakage-runbook.md`:
1. **Find the stage:** run the canary and read the failing stage.
2. **Check upstream:** look at the InnerTubeX and Metrolist issue trackers and releases for the same symptom.
3. **Update or patch:** bump or patch the fork (P8.2). If a response shape changed, add a P4.2 fixture plus a failing test, then fix the parser.
4. **Release:** run the canary, then follow the release checklist.

Include the decision rules: when to update the player config (P8.1) and when to update InnerTubeX.

**Done when:**
- The config source is pinned and validated, with a bundled fallback.
- `verification-metadata.xml` is committed and the build passes with it.
- The canary runs green on schedule.
- Diagnostics show the version, SHA and session state.
- The runbook exists.

---

## P9 — Sleep timer persistence and README finish (point 9)

### P9.1 Sleep timer survives the process being killed
Today `SleepTimerController` holds its deadline only in memory, measured on the `elapsedRealtime` clock. The deadline reaches the UI through session extras (`MusicPlaybackService.kt:43`). If the process dies, the timer is gone, and the README states this.

Design:
1. **Persist on set** (`sleep_timer` preferences): `deadlineElapsedMs` and the boot count (`Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)`). Clear both on cancel, on firing, and in `close()`.
2. **Split the controller's API:** `set(minutes)` becomes `arm(deadlineElapsedMs)` plus a wrapper that keeps `set(minutes)` for callers. The fade loop (lines 18–35) is unchanged.
3. **Pure decision function** (unit-tested):
   ```kotlin
   internal fun restoredSleepDeadline(saved: Long, savedBoot: Int, nowElapsed: Long, boot: Int): Long =
       if (saved > nowElapsed && savedBoot == boot) saved else 0L
   ```
   A reboot or an elapsed deadline discards the timer. Playback restores paused anyway (`MusicPlaybackService.kt:313`), so nothing has to be "caught up".
4. **Restore on start:** in the service, right after snapshot restore (after line 318), call `sleepTimer.arm(restoredSleepDeadline(...))` when the result is > 0. The deadline is published to the UI through the same session extras, so the countdown (`SleepTimer.kt`) appears with no UI changes.
5. **Fading at restore time:** if less than 15 s remains, the fade starts from the player's current volume. That's correct, because a new player starts at volume 1.0.
6. **Tests** (`SleepTimerControllerTest`):
   - future deadline, same boot → re-armed and fires;
   - past deadline → not armed;
   - different boot → not armed;
   - cancel clears saved state.
7. **README:** delete "Timers do not survive process termination" and state the new behaviour.

### P9.2 Final README structure
Rewrite `README.md` now that layout and commands are stable (about 150 lines). Move the long "Behavior and limits" list, unchanged in substance, into `docs/behavior.md`. Sections:
1. **What Riff is:** two sentences, current version, link to `CHANGELOG.md`.
2. **Status and independence notice:** the existing trademark and independence wording from `NOTICE.md`.
3. **Features:** grouped bullets for playback, library, lyrics, offline, radio and video, widgets, and accessibility, linking to `docs/behavior.md`.
4. **Requirements and build:** Windows (PowerShell) and macOS/Linux (`./gradlew`) commands.
5. **Release process:** `build-release-candidate.ps1`, `docs/release-signing.md`, the size budget, the canary.
6. **Project layout:** the package map after P6.
7. **Quality gate:** `scripts/check.ps1`, CI, hooks, `.git-blame-ignore-revs`.
8. **Docs index:** current documents first; historical phase and rc reports grouped as "History".
9. **Licence.**

**Done when:** sleep timer tests pass, a manual kill test passes, and the README has been reviewed on GitHub's renderer.

Manual kill test:
1. Set a 3-minute timer.
2. Kill the process with `adb shell am kill com.musicglass.personal` while paused in the background.
3. Reopen the app and press play.
4. The countdown shows the remaining time, then it fades and pauses.

---

## Risks

| Risk | Phase | Likelihood | Mitigation |
|---|---|---|---|
| History import wipes real files | P1 | Low (if followed) | Import only in `%TEMP%\riff-history`; move only `.git` into the project |
| R8 strips something only used at runtime (InnerTubeX serialization, QuickJS callbacks, Media3 HLS) | P5 | Medium | P5.2 keep rules, minified smoke on phone and emulator, `missing_rules.txt`, canary |
| Moving a manifest component renames it and breaks widgets, jobs or the launcher | P6 | Medium | Explicit "stays in place" list; a check in `scripts/check.ps1` that diffs component names in the merged manifest against rc16 |
| Formatting hides a behaviour change | P3 | Low | Identical bytecode for non-Compose files; screenshots for Compose |
| Saved-state migration loses the user's place after upgrade | P6.1 | Medium | Legacy decoder kept and tested with real rc16 data |
| Vaulting cookies breaks sign-in (rotation not captured) | P7 | Medium | Do P7.4 before P7.5; ship P7.5 behind a debug toggle for one release |
| Composite build of InnerTubeX is impractical on Windows | P8.2 | Medium | Half-day spike; file-based Maven repo fallback |
| `allWarningsAsErrors` blocks builds after a dependency deprecates an API | P2 | Medium | Fix or narrowly `@Suppress` with a comment; never turn the flag off for the whole build |

## Release mapping

| Release | Contents | Check before shipping |
|---|---|---|
| **0.9.0-rc17**: hygiene | P0–P5 | Size ≤ 35 MB; upgrade over rc16; full smoke on a minified build |
| **0.9.0-rc18**: structure | P6 | Shell-state migration; component names unchanged; detekt green |
| **0.9.0-rc19**: robustness | P7–P9 | Expiry banner; cookie rotation; canary green; sleep timer kill test |
| **0.9.0** | Stabilisation after rc19 | Two consecutive green weekly canaries; no open regressions |
