# Sign-in reliability and crossfade fixes

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

## Sign-in

The account screen is now separate from the shell. A signed-out account opens a Riff welcome screen; Google sign-in opens only after the user chooses to continue. Connected accounts show an account summary, reconnect/change-account action, sign-out, and the existing legacy-import action.

Page navigation gets a slow-loading message after 20 seconds and recovery controls after 45 seconds. These deadlines stop when the page finishes and are suspended while the app is backgrounded or the image picker is open. Reload, restart, and exit remain available for Google pages that load but subsequently stop progressing internally. A verification request is bounded to 20 seconds, including up to 6 seconds waiting for YouTube Music's page configuration to initialize.

The screen supports image selection through Android's system picker and completes pending callbacks on selection, cancellation, restart, and disposal. Continue to Riff navigates to YouTube Music when the user wants to leave optional Google profile setup. The app completes the connection only when the account endpoint verifies the candidate session; required account setup can redirect the user back to Google's flow.

Physical-phone follow-up found that Change account reused the existing Google session: ServiceLogin opened YouTube Music, then automatic verification immediately returned to Riff. Change account now starts at Google's AccountChooser and requires an explicit Continue to Riff before verification/commit, even if a cached session redirects to YouTube Music. Ordinary sign-in/reconnect retains automatic verified completion. The change-account intent is saved across process recreation and retained on restart. The chooser endpoint also appears in [Chromium's Gaia URL definitions](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/google_apis/gaia/gaia_urls.cc).

The browser region now shows native opening/checking feedback over the WebView during navigation and verification, covering its brief black startup and hiding the unnecessary YouTube Music flash during automatic connection. Recovery and exit controls remain outside that region.

Candidate verification uses an isolated InnerTube session, preserving the committed provider session until verification succeeds. Account metadata is normalized consistently with the existing provider, including the first portion of DATASYNC_ID. A missing account scope prevents completion. Revision and cookie-identity checks reject stale verification after an account change. Screen generations and navigation checks reject abandoned browser callbacks. Leaving the screen explicitly clears the saved browser-open state.

Rotation and the other configuration changes already handled by MainActivity keep the current WebView. Process recreation reopens sign-in with browser cookies available, without attempting to persist password fields or an unfinished form. WebView resources and verification work are released when the screen is disposed. Existing HTTPS/domain restrictions remain in effect. This change does not migrate the provider to OAuth or bypass Google's embedded-browser restrictions.

## Crossfade

The outgoing copy is prepared with exact seeking, then warmed up while muted starting eight seconds before the blend boundary. Its initial seek/readiness allowance is bounded to three seconds. After two seconds for decoder clocks to settle, a temporary 20% speed correction aligns the muted copy. Synchronization continues to the actual handoff; the copy must run at the original playback speed and stay within 120 milliseconds for at least 200 milliseconds before replacing the outgoing audio. Synchronization is bounded to ten seconds. An unready or misaligned copy skips the blend and leaves normal playback running.

Both players disable Media3's experimental dynamic scheduling to keep playback-position updates frequent enough for synchronization. Media3 1.11 enables that scheduling by default; delayed position updates are reported in the [upstream issue](https://github.com/androidx/media/issues/3286). This can increase renderer wakeups, so battery impact still needs a physical-device check.

Once the outgoing decoder stops playing or buffers during a blend, its gain stays at zero for the rest of that transition. It cannot become audible again over the incoming song. Pause, manual seek/skip, disabling crossfade, and cancellation mute and pause the outgoing player synchronously, before coroutine cleanup. Expected internal seek and transition callbacks are distinguished from user actions, including callbacks delivered after the seek call returns. Incoming buffering holds fade progress; a 15-second stall ends the blend and releases its outgoing player.

The monitor also survives crossfade being disabled at service startup. An unqualified `cancel()` inside the monitoring coroutine previously resolved to `CoroutineScope.cancel()`, killing monitoring permanently before the setting could be enabled. The call now explicitly cancels the current blend through `this@Crossfader.cancel()`. A real-decoder regression enables crossfade after playback has started with it disabled.

## Verification

- JVM/Robolectric/Compose tests: 286 passed, zero failures, errors, or skipped tests in the completed run. Coverage includes stale sign-in generations, metadata normalization, account-cookie identity, URL restrictions, loading deadlines, large-font recovery controls, verification action gating, crossfade alignment, and outgoing continuity.
- Debug lint: zero errors, 83 existing warnings, 5 hints.
- Release lint: zero errors, 90 existing warnings, 5 hints.
- Debug, signed release, stress, and Android test APK builds passed during implementation. After the account-chooser follow-up, the signed release, stress, and Android test APKs were rebuilt successfully. The signed evaluation APK is `artifacts/sign-in-crossfade/riff-0.9.0-rc20-signin-crossfade.apk` (27,551,038 bytes). `apksigner verify` passed. Its release certificate SHA-256 is `ac70342323a39eb9cbd1fe80a9a5b060e22c01ba9a9102a9f698367bca0bf6e1`; APK SHA-256 is `3ff61bd9ba4a156b4aeae8c9709b440228a97b7aa7d2e6f1425d1c199abff218`. Final build output: `artifacts/sign-in-crossfade/selected-account-2026-10-07-build.log` (`BUILD SUCCESSFUL`). The first final release build exhausted the JVM code cache during R8; rerunning with a larger build-only code cache succeeded without changing app configuration.
- Android 12 / API 31 emulator: all 13 instrumentation tests passed in the final decoder/service run. Nine isolated-player cases cover the complete handoff, pause, disabling, manual skip before/during handoff, an early outgoing end, outgoing pause/resume, consecutive transitions, and enabling crossfade after playback starts with it disabled. Four actual-service cases cover boost off/on, manual skip, and crossfade off. Local PCM fixtures exercise real Android renderers and volume/progress signals; they do not establish subjective streamed-song sound quality. The disposable emulator data was removed after validation; the original AVD data was preserved. Full output: `artifacts/sign-in-crossfade/final-crossfade-device-tests.log` (`OK (13 tests)`).
- Connected physical phone (model I2217, Android 16 / API 36): all 14 decoder/service instrumentation tests passed, including the actual streamed-song crossfade with boost and live progress. Full output: `artifacts/sign-in-crossfade/phone-crossfade-tests.log` (`OK (14 tests)`). The streamed handoff lasted 5,023 ms with 217 progress samples. Preferences were backed up locally before testing and restored afterward; media volume was temporarily muted and restored to its original index of 1. The signed release APK was reinstalled after testing, launched successfully, and pulled back for verification: its SHA-256 matched that initial evaluation build. The account-chooser follow-up release uses the new hash above. The original connected account was preserved. The user confirmed existing-session reconnect returned to Riff. After the follow-up fix, the user and a phone screenshot confirmed that Change account opens Google's account chooser. Manual completion through Continue to Riff remains unverified; the user requested pausing further tests until later.
- First-time Google sign-in/profile-picture setup, two-factor authentication, multi-account switching, subjective streamed-song sound quality, and battery impact still need hands-on validation. The phone's account was already connected before these tests, so the automated playback checks did not reproduce first-time account setup.

The source changes do not alter the app's package ID, version code, or signing configuration. Evaluation APKs use the existing local release key; historical release artifacts are preserved.



## Rerun on 2026-10-07

- Fresh JVM/Robolectric/Compose execution: 286 tests across 54 suites; zero failures, errors, or skipped tests. Gradle completed successfully in 6m 48s. Log: `artifacts/sign-in-crossfade/rerun-2026-10-07-build-tests.log`.
- Debug and release lint completed successfully with zero errors (83 debug warnings; 90 release warnings).
- All 14 physical-phone decoder/service tests passed in 190.930 seconds. The streamed-song crossfade produced 197 progress samples over 4,655 ms. Log: `artifacts/sign-in-crossfade/rerun-2026-10-07-phone-tests.log`.
- The first phone run was interrupted by USB disconnection during its second case. After reconnection, the complete suite was restarted and passed. The interrupted log is retained separately as `rerun-2026-10-07-phone-interrupted.log`.
- The preferences saved before this rerun were restored, the signed release was reinstalled successfully, and media volume was restored and checked at its original index of 7. No source changes were needed.
- Manual selection of the same Google account followed by Continue to Riff is awaiting the user's result. First-time profile setup, two-factor authentication, a different-account switch, subjective audio quality, and battery impact remain unverified.

## Secondary-account handoff follow-up (2026-10-07)

The user tested two existing Google accounts and reported that choosing the secondary account still opened YouTube Music as the previously active account. This means the chooser display alone did not validate account switching.

The chooser had continued directly to the Music homepage. It now requests `service=youtube` and continues through YouTube's `/signin?action_handle_signin=true` handler before reaching the homepage. A read-only request to YouTube Music's own `/signin?next=%2F` endpoint on this date returned a Google ServiceLogin redirect using that service and handler, confirming the missing handoff pattern. The handler is hosted on Music and returns to `/` on that same host.

YouTube redirect account indexes are retained for the explicit Continue action and checked against the loaded page's SESSION_INDEX. Config extraction now accepts current `ytcfg.data_` as well as `yt.config_`, and never substitutes index 0 for a missing SESSION_INDEX. Automatic verification excludes intermediate sign-in/error paths. Regression cases cover nested service continuation, secondary-account index retention, and rejecting missing or mismatched indexes. Actual two-account switching requires another phone check after installation.

The follow-up automated run passed all 288 tests across 54 suites with zero failures, errors, or skipped tests. Debug lint completed with zero errors and 83 existing warnings. Build output is retained in `artifacts/sign-in-crossfade/selected-account-2026-10-07-build.log`; The signed release build subsequently passed in 12m 6s. Release lint also passed with zero errors and 90 existing warnings. APK signature verification passed with the original release certificate; the new APK hash is recorded above. Physical account-switch verification remains pending.


The follow-up signed release was installed on the connected phone with adb install -r and MainActivity opened successfully. The existing app data was retained. The user has been asked to select the secondary account and confirm the account shown both in YouTube Music and after Continue to Riff.

## Restart, Google Skip, and intermittent crossfade follow-up (2026-10-07)

- The user reproduced a blank white browser after Restart sign-in. `remember(generation)` constructed a new WebView, but AndroidView retained its original composition identity and therefore kept the old factory result. SignInWebView now keys AndroidView by the actual browser instance. A Compose/Robolectric regression verifies that repeated restarts detach the previous view and attach the new one; that test passed.
- The user reproduced Google's profile-picture Skip failure. A phone screenshot showed Riff's own blocked-navigation message above Google's optional setup page. Navigation now supports approved HTTPS destinations encoded in Android intent links, without launching the intent. Subframe about:blank is permitted; blocked subframes no longer put the entire sign-in screen in a failed state. A diagnostic logs only the scheme, host, and main-frame flag when navigation is blocked, never the full URL or query. The exact blocked destination and physical Skip outcome remain under investigation.
- The user also reported crossfade failing again. Live logs showed some actual release transitions still started (2,412 ms and 2,304 ms), so the symptom is intermittent. Code review found that main-player buffering before muted warm-up abandoned the prepared tail and permanently marked that song handled. The warm-up wait now retains its silent prepared tail through a transient main-player stall, with a bounded 15-second stall allowance and explicit diagnostics. Pause, item changes, errors, and sleep still abort. A new real-decoder test blocks the main data source to verify genuine buffering and subsequent crossfade recovery; physical execution is pending.
- Full JVM/Robolectric/Compose run: 290 passed across 54 suites, zero failures/errors/skips. Stress and instrumentation APK builds passed. Logs: `restart-regression-2026-10-07.log`, `restart-buffering-build-2026-10-07.log`, `skip-navigation-build-2026-10-07.log` under `artifacts/sign-in-crossfade`.
- The diagnostic stress build is currently installed on the phone for reproducing Skip. A final signed release still needs to be built and installed after the remaining diagnostics and decoder test complete.


## Playlist sorting and podcast channel metadata (2026-10-07)

The user accepted the current sign-in behavior and explicitly ended further sign-in investigation. Subsequent implementation and phone checks focus on playback, playlist sorting, and podcast labels.

- Playlist menus now offer Top voted, Manual ordering, Newest first, Oldest first, Title, Artist, and Album. Album sorting keeps missing album names last. Authored row indexes remain distinct, including duplicate entries; playback uses the selected full collection order, while search only filters the displayed rows.
- Vote/date sorting follows commands supplied in the playlist's provider response, including reload continuation tokens and browse parameters. Date metadata remains a local fallback. Missing vote commands or dates produce disabled choices with an explanation, rather than using view counts or reversing manual order. Sorted collection requests load all continuation pages and do not overwrite the cache or partial results for the authored collection. Errors keep the previous track list and selected sort.
- Podcast and video listings now prefer linked channel bylines across subtitle/owner/flex-column fields, with an unlinked byline fallback. Modern attributed-text tiles retain channel links too. Player artist labels reject listing counts and relative dates. Lazy stream resolution uses its authoritative author/channel for podcasts and missing creator metadata; music keeps its artist attribution. Deferred source metadata follows its resolved child so the player can display the corrected channel.
- New parser, author fallback, album ordering, and Compose menu regressions passed as part of all 299 JVM/Robolectric/Compose tests across 55 suites: zero failures, errors, or skipped tests. The new real-player podcast test checks that deferred resolution updates both player metadata and the current media item.
- The corrected transient-buffering fixture previously passed on the physical phone (39.139 seconds, `buffering-fixture-retry-phone-2026-10-07.log`). It throttles reads before injecting its five-second stall so the WAV cannot be fully preloaded first. Fourteen existing decoder/service cases also passed in the preceding full attempt; the earlier fixture failure was failure to induce buffering, not a failed audio handoff assertion.

Sort command format reference: the primary [ytmusicapi browsing implementation](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/mixins/browsing.py) follows provider-supplied multi-select sort commands and reload continuation data. Actual availability remains specific to the provider response for each playlist.


### Additional physical crossfade failure found during verification

The combined phone run reproduced a separate handoff race: the outgoing decoder was accepted near the 120 ms alignment boundary, then the next position sample moved to -130 ms and `handoff_drift` abandoned that song's fade. Muted synchronization now targets 80 ms with correction ending near 20 ms and a 200 ms stable window. The audible handoff still enforces the original 120 ms cutoff. A state-machine regression verifies that the observed -110 ms preparation sample starts correction instead of being accepted, and that renewed drift or buffering resets stability. A new physical test also exercises the user's three-second fade duration. Final verification results follow below when complete.


The initial tighter 60 ms target made some three-second overlaps too short. Preparation now uses an 80 ms target, retaining a 40 ms margin inside the audible cutoff and the same correction hysteresis/stability window. Incoming media position on this phone was also observed roughly 350–400 ms behind the monotonic ramp clock: tests comparing those different clocks produced false duration failures. The decoder test now measures elapsed ramp time against its declared overlap, checks incoming playback advances, and explicitly requires the three-second fixture to retain at least two seconds of overlap. Exact gain curves, continuity, volume restoration, genuine buffering, and pause/skip cancellation remain asserted.

The Home-only local Favorites shelf directly below Speed dial was removed at the user's request. Library favourites, playlist cards, and provider discovery shelves were retained. New Album and Top voted choices are confined to playlist menus.

The first phone test helper stalled while restoring one preference through an interactive stdin command. The saved bytes remained available. All four preference files were restored through a bounded file transfer/pipe and verified byte for byte, and volume was restored to 14. The helper now uses that verified restoration path with command timeouts.


### Decoder timing diagnosis and final playback verification

A full phone run still shortened the three-second fade to 1,548 ms after moving muted warm-up earlier. A focused reproduction captured repeated alignment oscillation: the 20% speed correction continued affecting queued PCM for roughly 500–700 ms after returning to normal speed. Drift repeatedly crossed the opposite threshold, postponing handoff. The captured trace is in `decoder-clock-repeat-1-phone-2026-10-07.log`.

Muted correction is now 5% in either direction, limiting the queued-audio overshoot while retaining the 80 ms alignment target, 200 ms stability window, and unchanged 120 ms audible cutoff. Warm-up begins up to 12 seconds before the fade boundary, with a 14-second synchronization timeout. A regression simulates 700 ms of queued audio in both directions and verifies alignment settles without repeated corrections. The device fixture retains a bounded timing trace on short-fade failure. A separate late-seek case checks safe preparation when less lead time is available.

- Final full JVM/Robolectric/Compose run: **301 tests across 55 suites**, zero failures/errors/skips.
- Focused phone verification: **2 passed**, normal three-second overlap 2,980 ms and late-seek overlap 2,974 ms (`buffered-speed-short-phone-2026-10-07.log`).
- Final complete phone verification: **18 passed**, 257.903 seconds (`final-playback-phone-2026-10-07.log`). This includes 12 real-decoder cases, five playback-service cases including streamed songs and boost, and deferred podcast channel metadata. The three-second case retained 2,996 ms of overlap in the combined run. Gain envelopes, pause/skip cancellation, buffering recovery, outgoing decoder retirement, consecutive transitions, and volume restoration passed.
- After the earlier interruption, the user explicitly requested restoration of crossfade enabled at three seconds and boost enabled at 300%. Both preference files were restored and verified. The final phone test backed up the current queue/settings and restored their exact bytes. Current media volume was restored to index 4; no stale queue or earlier volume snapshot was reinstated.
- Final source whitespace check passed. Release lint/build and signed installation results are recorded below after completion.


### Final release delivery

The final build completed successfully in 9m 9s (`final-buffered-speed-release-2026-10-07.log`). Debug lint reported zero errors/83 warnings; release lint zero errors/90 warnings. The APK signature was verified against the existing release certificate.

- Release certificate SHA-256: `ac70342323a39eb9cbd1fe80a9a5b060e22c01ba9a9102a9f698367bca0bf6e1`.
- Artifact: `artifacts/sign-in-crossfade/riff-0.9.0-rc20-playback-playlists.apk` (27,551,038 bytes).
- APK SHA-256: `5384DE7F2730DE06ECED34B33C53F7D462A201513C3FE302624FFA45C37A767C`.
- Installed with `adb install -r` at 16:36:27 on 2026-10-07, retaining app data. The installed base APK was pulled and its hash matched the delivered artifact exactly.
- Riff MainActivity was resumed with a live process after installation; package is the non-debuggable release, version 0.9.0-rc20/code 21. Media volume remained index 4. Crossfade enabled at three seconds and boost enabled at 300% were verified immediately before replacing the stress build.

Automated phone playback checks use real decoders/renderers and inspect gain/continuity behavior. They do not constitute a subjective listening assessment. Vote/date sort availability still depends on commands or dates supplied for the individual playlist; unavailable choices explain why they are disabled.


## Short fades after a seven-second late seek (2026-10-07)

The user reported that settings of three seconds or less failed while four seconds and above worked, and clarified that they were seeking with about seven seconds left. The earlier real-player three-second test did not cover this compressed-stream/late-seek combination. New service regressions reproduced missing fades at all three short durations.

Two issues were found. The fixed 1,500 ms eligibility floor excluded the one-second setting altogether; the floor now scales down for short settings. More significantly, compressed streamed audio on the phone began roughly 350–410 ms behind the main decoder. A 5% speed correction required more time than the seven seconds remaining, so playback advanced naturally without crossfading. `seven-second-sync-phone-2026-10-07.log` and the captured RiffPlayback events document this reproduction.

Muted preparation now measures startup lag after the clocks begin settling. Once the lag is stable for 200 ms, it can seek the outgoing copy ahead once, including its measured startup lag and a 40 ms centering margin. It then settles again and must pass the existing 80 ms synchronization target and unchanged 120 ms audible cutoff. Coarse adjustment only occurs while muted and cannot restart or jump the audible outgoing song. Normal gentle correction remains available for residual drift. A duration change also cancels/restarts silent preparation so it does not retain the old setting captured earlier in the song.

The first coarse-seek attempt restored all fades, but a full run caught a truncated 1,734 ms three-second overlap when residual drift triggered another slow correction. Centering the seek target fixed that captured case. Focused streamed verification of the final playback code passed all three seven-second seeks: 999 ms, 1,999 ms, and 2,995 ms of overlap (`centered-lag-short-phone-2026-10-07.log`). The tests require overlap within 250 ms of the selected short duration.

Direct one- and two-second renderer tests were added alongside the existing three-second test, checking the actual gain curves and continuity. Controller notifications can coalesce multiple updates; service tests check their monotonicity separately from the per-renderer envelope. A subsequent broad run had a test-packaging mistake (the verification activity source was omitted when the stress build property was absent), and the one-second visual sampler needed elapsed-time-aware bounds. These test failures are distinct from missing crossfade; final corrected verification is recorded below.


### Final short-fade verification and installation

- **302 local tests across 55 suites passed**, zero failures/errors/skips. Debug lint: zero errors/83 warnings; release lint: zero errors/90 warnings. The corrected phone-test source was subsequently linted successfully (`short-test-final-lint-2026-10-07.log`).
- **All 23 corrected phone tests passed**, 334.777 seconds (`short-fade-corrected-verification-phone-2026-10-07.log`). This includes 14 actual-decoder cases, eight service cases, and the podcast metadata regression. Streamed fades after seeking with seven seconds left measured **993 ms, 1,973 ms, and 2,993 ms** for the one-, two-, and three-second settings. Existing longer fades, gain curves, buffering, outgoing retirement, and pause/skip behavior also passed.
- The final signed release build passed in 11m 21s (`short-fade-verified-release-2026-10-07.log`). Test-only sampling/package corrections after that build did not change release code.
- Artifact: `artifacts/sign-in-crossfade/riff-0.9.0-rc20-short-crossfade.apk`, 27,551,038 bytes. SHA-256: `40DE0E5E64C68F07975D4FDB3B872019B06B2B7F335BA55B9700ADC2B07CB200`.
- Signing certificate matches the existing release: SHA-256 `ac70342323a39eb9cbd1fe80a9a5b060e22c01ba9a9102a9f698367bca0bf6e1`.
- Installed with `adb install -r` at 17:40:21 on 2026-10-07. The installed APK was pulled and its SHA-256 matched the saved artifact. Riff MainActivity resumed with a live process, and package flags confirm the non-debuggable release.
- The latest phone preferences, verified after testing and before release installation, were crossfade enabled at **two seconds** and boost enabled at **300%**. These current settings were preserved rather than replacing them with the older three-second snapshot. Queue/history/preferences were restored byte for byte; media volume remained at the pre-test index **15**.


## Native YouTube Music playlist sorting (2026-10-07)

The Favourites playlist supplies `sortFilterSubMenuRenderer.subMenuItems`, whose `serviceEndpoint.playlistEditEndpoint` actions change the playlist sort preference. The previous parser only handled music multi-select commands and browse/reload endpoints, so it discarded all seven native submenu actions and showed a misleading Manual ordering label even when YouTube Music selected Newest first.

Riff now reads these submenu actions, opaque parameters, and selected state. Preference writes use the existing immutable signed-in session, a fixed YouTube Music endpoint, and a whitelist of sorting actions. Commands are bound to the displayed playlist, serialized, checked for HTTP/application success, and never automatically retried. Account changes reject stale results. A successful write reloads every collection continuation and replaces the cached collection. Manual ordering follows its native command too, so it can restore the provider's ordering after another preference was applied. Existing browse/continuation sorting remains supported; no synthetic dates or vote totals are created.

The parser regression suite passed 17 tests, including real submenu fixtures, opaque parameters, selected state, cross-playlist rejection, and rejection of unrelated/compound edits. The initial background-only instrumentation stalled; the foreground test adds progress reporting and bounded requests. A missing activity-launch flag was corrected before the successful run.

The connected-phone verification (`playlist-sort-progress-phone-2026-10-07.log`) passed in 62.061 seconds. It exercised Top voted, Manual ordering, Oldest first, Newest first, Title, Artist, and Album using the actual supplied commands. For each, YouTube Music confirmed the selected preference, all 284 playlist entries and their entry IDs were preserved, and the cache matched. The test restored the original pre-test Newest first preference after an interrupted run had left Album selected. Local playback preferences and media volume index 15 were restored by the test helper. The reusable test captures its initial selection for future runs.


### Playlist-sort release delivery

All 304 local tests across 55 suites passed, with zero failures, errors, or skips. Debug lint reported zero errors/83 warnings and release lint zero errors/90 warnings. The signed release build passed in 9m 14s (`playlist-sort-release-build-2026-10-07.log`).

- Artifact: `artifacts/sign-in-crossfade/riff-0.9.0-rc20-playlist-sorting.apk`, 27,567,422 bytes.
- SHA-256: `14B5D72610BF367EAF875D78E9F9B983960EAD3401EE241C15B4E8A0852D0640`.
- Signing certificate matches the existing release: SHA-256 `ac70342323a39eb9cbd1fe80a9a5b060e22c01ba9a9102a9f698367bca0bf6e1`.
- Installed using `adb install -r`, retaining app data. The installed APK was pulled and its SHA-256 matched the delivered artifact. Riff was launched afterward. Media volume remained index 15.
- The phone's latest settings before release installation were crossfade enabled at four seconds and boost enabled at 300%; no settings were overwritten during release installation. The native playlist preference was restored to Newest first and verified by the successful phone test.
