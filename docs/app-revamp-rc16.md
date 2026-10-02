# Riff rc16 — synced lyrics and Speed dial

## Behavior

YouTube Music fallback lyrics now request the native mobile browse identity at the music endpoint, using a minimal public-metadata body, before falling back to the signed web response. The parser accepts the current `timedLyricsData` rows as well as older `lyricLineRenderer` wrappers, preserving supplied cue starts and ends. Plain lyrics remain readable when no timestamps exist; mixed or malformed timing does not silently drop a verse. No timestamps are invented.

Better Lyrics remains the first choice, including its line-synced results. Independent providers are contacted only when Better Lyrics has no usable document. Within fallback results, exact-song YouTube Music timing can replace equal-quality metadata-matched LRCLIB timing. Existing plain lyric caches are rechecked and upgraded; plain lyrics stored with offline songs can be upgraded when a provider is reachable. Supplied word timing, English translation, manual timing adjustment, seeking and playback-first background preload are retained.

Speed dial retains its three-column paged layout and fresh/recent song mix. It interleaves up to six songs from Listen again, caps album/playlist/artist collections at three across 27 cards, and excludes podcast/live items. Each entry is unique. New listening-history records retain song/live/podcast type across restarts instead of turning every recording into a song card. Older history rows remain readable. The Listen again shelf itself is unchanged. Smaller feeds show available entries without padding with podcast content.

## Primary references

- [ytmusicapi mobile timestamp request](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/mixins/browsing.py)
- [Mobile client context](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/ytmusic.py)
- [Native cue-range model](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/models/lyrics.py)

The native request contains the exact lyrics browse ID, mobile client identity and current language/region. It needs no API key and receives no account cookies or authorization headers. Signed song lookup and signed web fallback retain their existing behavior. Shared account state and concurrent playback/catalog client identities are not mutated.

## Verification

- All 142 unit tests pass, including native/legacy lyric payloads, supplied timestamp seeking and gaps, malformed/untimed verse handling, stale plain-cache upgrades, exact-song fallback priority, small/large Speed dial mixes and song/podcast/live type preservation.
- Full release lint: zero errors, zero fatal issues, 77 existing warnings. Production DEX excludes preview activities and test instrumentation.
- Physical-device provider checks: Numb returned 35 timed rows, Billie Jean 66 and BIRDS OF A FEATHER 39. A fourth music-video ID had no lyrics; the app correctly treats that as unavailable rather than inventing timings. Native cues were unavailable through the previous web request.
- Real Home feed/cache checks: 12 shelves, 27 unique Speed dial entries, 24 songs and three collections, with six songs from Listen again. The cached Home feed is reused. Its paged grid and Listen again shelf were visually reviewed on the phone.
- The first mobile probe found the general-endpoint routing issue; a second showed the shared web/account payload returning HTTP 400. The minimal key-free request returned HTTP 200 with current `timedLyricsData` and passed the real-song retest. Earlier failed probes are retained as diagnostic evidence and are not counted as passing tests.
- Signed production build: Riff 0.9.0-rc16, version code 17, Android 12 minimum, same upgrade-compatible signing certificate. Verified copies are in `artifacts/release` and the user's Downloads directory.
- Final production APK installed over the existing app. The current seven-item queue and paused position (60,371 ms) were retained. No new production fatal exception appeared. The temporary instrumentation package was uninstalled, debugging screenshot/XML files were absent, and the phone was locked afterward. Always-on-display dozing counts as locked; the keyguard reports `showing=true`.

Evidence is saved under `artifacts/bugfix-rc16`, notably `release-checks.json`, `phone-provider-3.txt`, `phone-lyrics-probe-3.txt` and `home-speed-dial-loaded.png`.

## Limits

Timed lyrics still depend on the exact recording and upstream coverage. Better Lyrics keeps its requested priority; YouTube Music timing is used when Better Lyrics has no usable result. Public native lyrics requests respect the app's current language/region. Older untyped listening-history records remain compatible; newly recorded entries preserve their actual type.
