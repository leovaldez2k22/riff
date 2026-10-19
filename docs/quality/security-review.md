# Security, diagnostics and privacy review (Q15)

What Riff's boundaries are, what was tried against each, what happened, and what is still open.

**This is engineering evidence from one review of the code, on 13 October 2026. It is not an audit, a
penetration test or a certification, and nobody outside the project has looked at it.** Everything under
"Results" was run on this machine as a JVM test, a script against a built APK, or a reading of the code; the
table says which. Nothing here was tried on a phone.

## What is being protected

| Asset | Where it lives |
| --- | --- |
| The Google session (cookies) | WebView's cookie store, in the app's private storage |
| Visitor and account identifiers, the account scope | app-private preferences and databases |
| Listening history, queue, downloads, settings | app-private storage, per account scope (ADR-Q02) |
| Signed media addresses, stream tokens | memory only; never written (Q09, Q10) |
| The release signing key | outside the repository and outside CI (`docs/release-signing.md`) |

Not protected against, by design: someone with the unlocked phone in their hand, a rooted phone, a modified
copy of the app, or Google itself, which is the provider and sees every request the app makes to it.

## Boundaries

1. **The diagnostic ring, logcat and the copied report.** The only place app events are written down.
2. **Three embedded browsers.** Sign-in (`AccountScreen`), the token runtime (`AndroidTokenRuntime`), the live
   video player (`InlineYouTubeVideo`).
3. **Pages opened in the user's browser.** Photo credit and licence links on Home.
4. **The media session service.** The one service other apps can bind to.
5. **The manifest.** Exported components, permissions, backup, cleartext.
6. **Local audio.** MediaStore rows the user gave access to.
7. **Files the user picks.** The Q05A data file, through the system picker only.
8. **The build.** Wrapper, dependencies, CI, signing.

## 1. Diagnostics

Every line goes through `DiagnosticEvent.parse` before it is stored, before it is written to logcat, and again
when the report is built (`scrubStoredLine`).

- An event is a name and `key=value` fields. A field is kept only if its key is one of 96 listed in
  `DiagnosticEvent.kt` and its value has the shape that key allows: a number, `true`/`false`, or one short word
  (a phase, a reason, an error-code name, a class name; up to five class names joined by `>` for an error chain).
- Anything else becomes `<redacted>`: a URL, a header, a cookie, a token, a title, an identifier, a path, an
  exception message, a line break, any character outside printable ASCII. A text with no proper event name is
  stored as `rejected_event` and nothing else.
- Bounds: 1,000 characters looked at, 400 kept, 24 fields, 48 characters per value, 200 lines in the ring.
- A host is reduced to one of six words (`googlevideo`, `youtube`, `google_images`, `google`, `other`, `none`)
  before it is logged. Which media server answered is no longer kept.
- The check is on shape, not a search for known secrets, so it holds for text nobody foresaw.

Changed call sites: `stream_resolve_start` carries a small number that tells a repeat from a new entry
instead of the first eight characters of the entry's identifier; `cookies_rotated` carries a count instead of cookie names; `process_exit` no longer carries the
system's free-text description; `sign_in_blocked` and `http_rejected` carry the host's class; three events
written as words with spaces (`token minted`) became names (`token_minted`); the three direct logcat calls
(`RiffArtwork`, `RiffProfile`, `RiffSearch`) go through the same reduction.

A ring written by an older version is reduced once, on the first write after the update, and in any case on
the way out.

The report ("Copy playback diagnostics" in Settings) is the only export of diagnostics. Its content is fixed:
one heading line, the build identity, the ring. It reads no file, and there is no general file export. The
other export is the user's own data file (Q05A). Q15A will add a share action for a report of the same kind.

**What the shapes cannot do.** A short all-lowercase identifier with few digits looks like a word, and a
class-like identifier with few digits looks like a class name. The list of keys is the control for that: no
listed key is filled from an identifier, a title or a message, and `DiagnosticEventTest` fails when the code
writes a key that is not listed. A future event that puts content into a listed word key would get through if
the content happened to look like a word. That is a review rule the test cannot enforce.

## 2. Embedded browsers

| | Sign-in | Token runtime | Live video |
| --- | --- | --- | --- |
| Loads | HTTPS on `google.com`, `youtube.com`, `googleusercontent.com`, `gstatic.com` and real subdomains | one empty page; every request answered empty; network loads blocked | `https://www.youtube.com/embed/<id>`, navigation only within `youtube.com` |
| JavaScript | on (Google's pages need it) | on (it runs Google's attestation script) | on |
| Native bridge (`addJavascriptInterface`) | none | none | none |
| File access | off | off | off |
| Content addresses | **on** (see below) | off | off |
| Certificate error | load ends | load ends | load ends |

- `httpsAddressOn` is the one rule for "an HTTPS address on these domains". It refuses any other scheme, a
  user name or password before the host, a port other than 443, a backslash or control character anywhere,
  a host that is not plain letters, digits, dots and hyphens, and an address over 4,096 characters.
- All three clients extend `StrictWebViewClient`, whose `onReceivedSslError` is final and cancels. The
  platform default does the same; this makes it impossible to change by accident and lets a test hold it.
- An Android app link (`intent://`) met during sign-in is never executed. Its fallback address, or its own
  address, is loaded in the same browser only if it passes the rule above.
- The live player's video identifier is checked (11 characters of the identifier alphabet) before it is
  written into the address.

**Closed in Q18F (Q18-027): content addresses in the sign-in browser.** `allowContentAccess` was on there for
a profile-picture upload inside Google's pages. Q18B11 showed on the owner's phone that the upload reaches
Google's crop screen with it off as well, so it is now `false`; WebBoundariesTest checks that no browser turns
it on. The upload is repeated once on the final candidate (A9).

**Q18F (Q18-009): Google's country domains.** Google's sign-in moves to the user's country domain
(accounts.google.co.in and others), which the allow-list refused. The list now adds Google's own country
domains, fixed from google.com/supported_domains (GoogleDomains.kt), not a pattern: a google.<suffix> that
Google does not list, and every lookalike already refused, stays refused (WebBoundariesTest).

**Not a finding, recorded:** the request to Google's attestation service carries an API key that is written
in the source. It is the public key of YouTube's own web client, the same for every user; it is not a
credential of this project or of the user.

## 3. Pages opened in the browser

**Found and fixed (medium).** The photo credit dialog on Home opened the photograph's page and licence with
`Intent(ACTION_VIEW, Uri.parse(text))`, where the text came from Openverse's or Wikimedia's answer and was
stored in a file. An answer could name any scheme the phone has a handler for (`tel:`, `content:`, a deep
link into another app). `openWebPage` now opens only an address that passes `httpsAddress`, as a plain view
with the browsable category and no component; anything else opens nothing, and a licence link that is not
such an address is not shown.

The share actions send text the app composed itself. The Bluetooth settings shortcut uses a fixed action.

Added in Q12A: the update notice's release page opens through `openWebPage` with the configured page hosts,
after the feed entry's page passed `httpsAddressOn` when it was read and again when it was read back from
storage. The update request itself carries no cookie, account or device detail and is bounded to 256 KiB
(`docs/quality/update-notice.md`).

## 4. The media session service

`MusicPlaybackService` is exported with no permission, and has to be: the system's media controls, Bluetooth,
a car and an assistant bind to it, and a permission would lock them out. Lint reports this
(`ExportedService`); the manifest now says why.

What a caller gets is decided per controller in `onConnect`:

- **The app itself** (`isOwnController`: same package name **and** same user id): everything, including its
  six custom commands.
- **Anyone else**: Media3's default player commands minus three (`COMMAND_CHANGE_MEDIA_ITEMS`,
  `COMMAND_SET_MEDIA_ITEM`, `COMMAND_SET_PLAYLIST_METADATA`). Play, pause, seek, next, previous, stop, speed,
  repeat, shuffle and reading the queue and metadata stay.

**Found and fixed (medium).** Before this, any app that connected a media controller was given the default
commands in full, which include removing, moving and clearing queue entries. And the app recognised itself
in `onConnect` by package name alone, which is what the caller says about itself; `onCustomCommand` already
checked the user id as well, so the custom commands were not reachable, but the two checks are now the same
function.

Custom commands were already refused to other callers and validate what they are given: the queue file name
must match `queue-request-<uuid>.json` in the app's cache directory, a local track must name its own
MediaStore row, the sleep timer takes 0 to 1,440 minutes.

The widget buttons start the service with a token that only the app holds; a start without it does nothing.

**Not verified:** that a real foreign controller on a phone is in fact refused queue edits and still gets
transport, and that Bluetooth, the lock screen, Android Auto and Assistant are unaffected by losing the three
commands. The rule and its wiring are tested on the JVM; a second app as the caller is a device test that was
not written, because a test in the app's own package runs with the app's user id and so cannot be foreign.

## 5. The manifest and the APK

`scripts/quality/check_release_content.py` reads the built APK (not the source) and fails unless:

- backup is off (`allowBackup=false`, no legacy rules) and the data extraction rules exclude everything from
  both cloud backup and device transfer;
- cleartext is off and there is no network security override;
- the app is not debuggable, not test-only, not profileable from the shell, has no shared user id and no
  instrumentation entry;
- the exported components are exactly four: `MainActivity` (launcher), `MusicPlaybackService` (above),
  Media3's `BluetoothValidationActivity` (callers need `BLUETOOTH_PRIVILEGED`), and the profile installer's
  receiver (callers need `DUMP`, which only the shell has);
- no provider is exported or grants URI access;
- the permissions are exactly the twelve reviewed ones;
- no class, file or manifest entry from the test, stress, fixture or benchmark builds is inside;
- no key or credential file is inside;
- and, for a signed APK, the certificate is the one pinned in `docs/release-signing.md`.

The lists in the script are the review: a new exported component or permission fails the check until someone
adds it on purpose. For an unsigned APK the certificate line reads "not checked", which is not a pass.
`scripts/build-release-candidate.ps1` already refuses to finish a release with another certificate; that check
is unchanged.

## 6. Local audio

A local track may only name `content://media/external/audio/media/<its own number>` (`localAudioUri`; the
saved queue applies the same rule when it is read back). Playing asks for the permission first and opens the
row off the main thread; a revoked permission or a row that has gone gives "not available" as an ordinary
playback failure, and listing returns a failure the screen shows. No path is ever taken from a caller.

## 7. Picked files

The data file (Q05A) is read and written only through the system's document picker. Bounds and validation are
in `docs/quality/local-export-import.md`. Nothing else reads a file the user or another app names.

## 8. The build

- **Wrapper.** `gradle-wrapper.properties` now carries `distributionSha256Sum` for `gradle-8.13-bin.zip`:
  `20f1b117...aed78`, read from `services.gradle.org` and from `gradle.org/release-checksums` on 13 October
  2026 (both agree). The committed `gradle-wrapper.jar` hashes to `81a82aae...ae45f`, which is the value the
  same two pages give for the 8.13 wrapper. CI also runs Gradle's own wrapper validation.
- **CI actions** are pinned to commits, with the release each belonged to as a comment:

  | Action | Commit | Release |
  | --- | --- | --- |
  | `actions/checkout` | `3d3c42e5aac5ba805825da76410c181273ba90b1` | v7.0.1 |
  | `actions/setup-java` | `de7274f081f381c8f8158605e0321c36c376e2e6` | v6.0.1 |
  | `android-actions/setup-android` | `be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd` | v4.0.4 |
  | `gradle/actions` | `3f5f9adaf7d9fecd50b5935e54106014257a94e6` | v6.4.0 |
  | `actions/cache` | `55cc8345863c7cc4c66a329aec7e433d2d1c52a9` | v6.1.0 |
  | `actions/upload-artifact` | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` | v7.0.2 |

  These are the commits the release tags pointed to on 13 October 2026, read from GitHub. **"Reviewed" here
  means the release and its publisher were identified; the code of the actions was not read.** A tag can be
  moved to other code; a commit cannot. To update one, read the new release's notes, take its commit, and
  change the line and this table together.
- **Workflow permissions.** Both workflows run with `contents: read` and use no secret. CI runs on pull
  requests with that read-only token, so code from a fork gets nothing to steal. The workflows were not run:
  nothing was pushed.
- **Signing.** The key is not in the repository (`.gitignore` covers key stores and
  `release-signing.properties`) and never goes to CI, which builds unsigned.
- **Dependency verification:** see "Open" below.

## Attack cases and results

| Case | Tried as | Result |
| --- | --- | --- |
| SEC01 Lookalike hosts: suffix and prefix tricks, user-info, backslash, fragment, port, encoded dot and null, IDN, trailing dot, 39 addresses in all | `WebBoundariesTest`, JVM | all refused for sign-in; ten real addresses still allowed |
| SEC01 Other schemes: `file`, `content`, `javascript`, `data`, `blob`, `intent`, `market`, `http`, `tel`, a custom scheme | same | refused; nothing started |
| SEC01 App link with a hostile fallback, component and package | same | never executed; fallback refused; no activity started |
| SEC01 Certificate error | same, with the platform's handler | cancelled, never proceeded; no other client class exists in the code |
| SEC01 Photo link naming another scheme or app | same | nothing opened; an HTTPS page opens as a browsable view with no component |
| SEC01 A hostile video identifier or local-audio address | same | refused |
| SEC02 Foreign package; own package name with another user id; the legacy controller's name | same | not treated as the app |
| SEC02 Foreign controller's commands | same | transport kept, the three queue commands gone; the service's wiring read from its source |
| SEC03 Twelve planted secrets and contents (media address with signature and token, API key, cookies, auth headers, title and artist, video and playlist ids, account scope, exception message with an address, file path, e-mail) in six positions each | `DiagnosticEventTest`, JVM | none in the stored ring, in logcat or in the report |
| SEC03 Line breaks forging a second line; control characters, lone surrogates, a direction override | same | one line, printable ASCII only |
| SEC03 A 100,000-character value, 5,000 fields, a 2,000,000-character name | same | bounded or rejected |
| SEC03 20,000 random inputs | same | every output fits the grammar and uses only listed keys |
| SEC03 A ring left by an older version, with an address, an identifier and cookie names in it | same | reduced on first write and in the report |
| SEC03 Every event text in the source | same, reading the source | 0 unlisted keys, 0 malformed names |
| REL01 (content part) Release APK | `check_release_content.py` on the built APK | every check ok; certificate not checked (unsigned) |
| REL01 The check itself: the benchmark APK | same script | fails seven checks, as it should |

## Open, by severity

No high-severity issue is known to be open. That statement is only as good as this review.

**Medium**

- *Remote solver code from a third-party repository (found in Q17).* When a playback challenge is not solved
  otherwise, the pinned library (innertubex 0.7.2) fetches a pre-processed YouTube player script from
  `cdn.jsdelivr.net/gh/MetrolistGroup/faraday@player-<hash>/...` (cached a day, 4 MiB cap, no redirects, no
  cookies: the `:solver` process's HTTP client has none) and runs it in QuickJS in the `:solver` process.
  Whoever controls that repository therefore controls code that runs there. It has no host access beyond the
  globals the library sets and cannot reach the main process, cookies or files, but it decides the challenge
  answers: it could make playback fail or see the challenge values it is given. This request was not disclosed
  before Q17; it is now in `docs/privacy.md`. Read from the library's 0.7.1 sources in `.toolchain/innertubex`;
  the 0.7.2 classes the app builds with hold the same addresses and allow-list. Not observed on a network.
  Options: keep and disclose (done); turn it off (`AppPlayerConfigRepository.enabled = false`) and accept that
  playback may break sooner after a YouTube player change; or ask upstream for a pinned hash. A product
  decision; not changed here.
- *Dependency verification on CI is not yet proven on a hosted run.* Q16 added the Linux and macOS aapt2
  checksums and made CI strict; no workflow has run since (nothing was pushed). See the section below.
- *Foreign-controller behaviour is not verified on a device* (section 4).

**Low**

- *A configured player-configuration address that is never used (Q17).* `AppPlayerConfigRepository` names
  `raw.githubusercontent.com/ZemerTeam/zemer-cipher/.../player_configs.json`, but the library's allow-list
  accepts only `MetrolistGroup/faraday` addresses, so no request is made to it and the table it would supply is
  not used; its only effect is to select the jsDelivr fallback above. Playback does not depend on it. Removing
  or replacing it is a change to playback behaviour and is left for a package that can check playback live.
- *Content addresses in the sign-in browser* (section 2).
- *Session cookies are stored as WebView stores them*, in app-private storage, not additionally encrypted.
  Backup and device transfer exclude them. On a rooted phone they can be read.
- *ML Kit*, used for on-device lyric translation, ships Google's data-transport component and sends SDK usage
  metrics to Google (`docs/privacy.md`). The app does not control that channel; it is not part of the
  diagnostic ring.
- *The shape check cannot tell a short identifier from a word* (section 1).
- *Heading of the report* contains the phone's maker and model, as before; it is cut to 64 plain characters.
- *Actions' code was not read* (section 8).

**Not looked at in this package**

- The provider integration's own network code (InnerTubeX) beyond what Q10 covered and the remote solver
  inputs above (Q17); certificate pinning
  (none is used: the platform's trust store decides).
- Tapjacking and overlay attacks on the sign-in screen; screenshots and the recents thumbnail of the sign-in
  browser.
- The clipboard: the copied report stays on the clipboard until something replaces it.
- Dependencies' known vulnerabilities. No scanner was run.
- Anything on a phone.

## Dependency verification

`gradle/verification-metadata.xml` holds a SHA-256 for every artifact the build resolved on this machine:
616 components, covering the debug, release, stress, benchmark and test builds, the unit tests and lint. With
the file present Gradle refuses an artifact whose checksum differs or is missing, for plugins as well as
libraries. Local builds run strict.

What this is and is not:

- **It is a record, not provenance.** The checksums were taken from this machine's Gradle cache by
  `--write-verification-metadata`. They say "the same bytes as on 13 October 2026", not "the bytes the
  publisher made". Nobody compared them with the publishers' own checksums, and signatures are not verified
  (`verify-signatures` is off). A cache that was already wrong has been recorded as right.
- **Linux and macOS (Q16).** The one platform-specific artifact the build resolves is `aapt2`, published per
  operating system. Its Linux and macOS jars were added by hand: each was downloaded from Google's Maven
  repository and matched the SHA-1 published beside it there, and the Windows jar fetched the same way hashed
  to the value already recorded. Both workflows now run strict, like local builds. No hosted run has
  confirmed that nothing else is missing (nothing was pushed). If the first run names another artifact,
  add it from that run's log after reading it; do not set the build lenient again.
- Sources and documentation jars are trusted by name, so that an IDE can sync. They are never compiled in.
- Dependency locking was not added. Versions are already fixed in `gradle/libs.versions.toml`; locking would
  pin the transitive ones too and is a separate decision.

After changing a dependency:

```
gradlew --write-verification-metadata sha256 :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest ^
    :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleBenchmarkRelease ^
    :app:assembleNonMinifiedRelease :benchmark:assembleBenchmarkRelease
gradlew --write-verification-metadata sha256 :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress
```

and read the diff of the file before committing it: every new line is a new thing trusted.

## Running the checks

```
gradlew --offline :app:testDebugUnitTest --tests *DiagnosticEventTest --tests *WebBoundariesTest
gradlew --offline :app:assembleRelease
python scripts/quality/check_release_content.py --apk app/build/outputs/apk/release/<apk> --aapt2 <sdk>/build-tools/36.0.0/aapt2
```

For a signed candidate add `--apksigner <sdk>/build-tools/36.0.0/apksigner`.
