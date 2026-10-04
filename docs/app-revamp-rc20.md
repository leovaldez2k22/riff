# Riff rc20: lyrics expansion and playback fixes

## Scope

Includes rc19's stream replacement recovery, queue remove buttons and persistent Data saver.

Approved lyrics order: Better Lyrics TTML, Better Lyrics QQ, NetEase Cloud Music, Better Lyrics Kugou, LRCLIB, YouTube Music, Lyrics.ovh. Timing quality (word > line > plain) takes precedence; source order breaks ties. Spicy Lyrics is excluded. Megalobiz was deferred by the user after live checks found the old HTML search route returns 404 and the replacement API returns 403 requiring an application token.

NetEase uses anonymous public search, exact normalized title/artist matching and a three-second recording-duration tolerance. Up to three matched candidates are checked. LRC is supported; YRC supplied word durations are preserved when returned. No user/account cookies or copied site tokens are sent. NetEase is a best-effort source: on the connected phone it returned verification code -462, so live matched NetEase lyrics were not obtained. The app treats that response as a provider failure and continues down the hierarchy. Desktop requests returned matched LRC for Coldplay’s Yellow. Lyrics.ovh is requested only as a plain-text fallback. Responses retain provider labels and source links.

Each source has a 15-second total budget, cancellable HTTP requests, rate-limit cooldowns, and a one-megabyte response cap. Failure of one source permits the remaining sources. Completed results share the existing process preload and seven-day disk cache. Cached plain lyrics are shown immediately while providers are retried for synced results. The cache identity is revised so old exclusive-priority entries do not bypass the new hierarchy. Refresh retries the hierarchy.

## Verification

- JVM unit and Compose tests: 253 passed, zero failed or skipped in the initial full rc20 build. Final build recheck also passed: 253 tests, zero failed or skipped.
- Release lint: zero errors, 90 warnings and five hints in the initial full rc20 build.
- Original release certificate verified; 233 dependency licenses audited; shrunk APK 26.2 MiB.
- Installed signed rc20 release on the connected I2217 phone, preserving app data.
- Signed release UI: visible queue deletion removed the chosen row; Data saver persisted through a force-stop/relaunch and its original setting was restored.
- Phone playback acceptance: 12 distinct real songs played and advanced in one session, six with Data saver enabled; queue restored paused. Checks used the existing same-certificate stress variant for instrumentation.
- Lyrics.ovh live phone request returned plain lyrics for Coldplay’s Yellow.
- NetEase live phone request returned a verification challenge. This is explicitly a provider limitation, not a successful live integration check. A phone regression test exercises fallthrough with the real verification error and a controlled LRCLIB response; JVM tests cover matching and parsing with supplied fixtures.
- Phone UI suite: all eight tests passed (six UI regressions, live Lyrics.ovh, and documented NetEase verification/fallback behavior). The tab-loop test measured 22.2 MiB before and 19.5 MiB after; final release was reinstalled successfully. Final signed release played six distinct queue songs with more than six seconds of measured advancement per song; word-synced Better Lyrics displayed on the first song. The queue was returned to its first item and paused. Final signed APK SHA-256: 69d6a6829688d66f566674c6b8000cdee3ff1cb8d23c6ab6689f389996b50d2b.

The initial playback harness assumed twelve saved songs but found six, then filled the remaining entries with real search results. Its first cleanup used a filename rejected by the queue command's validation. The harness now uses the supported queue-request UUID format and a durable queue backup; the rerun passed playback and cleanup. These initial harness failures were not marked as acceptance passes.

## Source references

- NetEase public response schema and requests: https://music.163.com/api/search/get and https://music.163.com/api/song/lyric
- Lyrics.ovh API: https://github.com/NTag/lyrics.ovh
- Prior NetEase integration reference: https://github.com/moehmeni/syncedlyrics/blob/main/syncedlyrics/providers/netease.py
- Megalobiz live site and API: https://www.megalobiz.com and https://api.megalobiz.com/api/v1/lrcs

The first Data saver Compose instrumentation used performScrollTo while the test clock was paused and stalled. That run was explicitly terminated; it is not counted as a pass. The harness now scrolls with real phone swipes. The signed-release Data saver check had already passed independently.

The signed-release resume encountered real SABR attestation failures. Logs show the new recovery first refreshed attestation, then excluded the failing profile and replaced it with a working direct stream; playback resumed automatically. A first manual harness stopped on the transient platform ERROR state before recovery completed. Its UI dump also reused an old XML file when Android could not obtain an idle snapshot. Fresh screenshots confirmed the screen followed the current track. The final playback check allows bounded recovery and measures advancement using the media session clock rather than stale dump positions.
