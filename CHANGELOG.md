# Changelog

## Unreleased — 0.10.0-rc1 (code 22)

- Adds source revision, content digest, dirty/local state and variant to build metadata, About and copied diagnostics.
- Adds local evidence manifests with APK/source/report hashes and strict rejection of incomplete test runs. No new release has been published.
- Account data no longer depends on the account's position in the Google account chooser. Existing downloads and saved tracks stay with their account; data split across older keys is merged in the background without deleting validated audio.
- Switching account or signing in now saves the current online queue for the account that made it, stops it, and restores the other account's queue paused. Sign-out does the same. On-device audio keeps playing through a switch.
- A signed-in session that cannot be verified is shown as such: saving and downloading say why they are unavailable and the Account screen offers Verify account. It is no longer treated as signed out.
- Requests made for a previous account (queue, stream, radio continuation, downloads) are dropped instead of landing in the new account. A download interrupted by a switch waits, queued, for its own account.
- History is kept separately for each account and for signed-out listening. Plays of audio stored on the phone are listed under **On this phone**, whichever account was signed in. History loads in pages and search ignores accents.
- History and saved queues moved to a database. A queue too large to store or send is refused with a message instead of failing silently.
- Known change: plays from YouTube Music recorded before this update are not shown in History, because the old list was shared by every account on the phone. **Settings → Privacy and data → Earlier data** lets you import them into the account you are using, or delete them. Plays of on-device audio were moved automatically.
- Known change: the queue saved by an earlier build is not restored automatically after updating. Earlier data offers to restore it, paused, or delete it.
- New **Settings → Privacy and data** screen: turn history saving off, choose how long plays are kept (30, 90 or 365 days, or until cleared), clear an account's or the phone's listening history, clear search history, delete downloads, or delete everything Riff keeps on the phone for the account in use. Each action says whose data it removes and how much, asks first, and reports what could not be removed. None of them changes the YouTube Music account or signs out.
- The History screen has **Clear** for the list shown.
- Recent searches are kept separately for each account and for signed-out use, 30 each.
- Tapping several albums, songs or playlists in quick succession opens only the last one; earlier ones no longer start briefly or land late.
- Pressing pause while a queue is still opening leaves it loaded and silent.
- Adding several songs to the queue quickly keeps all of them, in order. Before, a second add could drop the first.
- Queue rows act on the entry you touched even if the queue changed underneath, and the same song queued twice is treated as two entries everywhere.
- Changed: removing the playing song when it is last in the queue now selects the song before it, paused. Before, the player jumped to the first song.
- A switch between a song's audio and video no longer lands on a different song if you skipped in the meantime.
- Known change: searches saved before this update are not shown, because the old list was shared by every account on the phone. Earlier data offers to import them or delete them.

### Historical October 6 update

Merged into `main` and verified 6 October 2026. The local tested APK retains version `0.9.0-rc20` / code 21; the published 4 October rc20 APK does not include this section. See [the update notes](docs/update-2026-10-06.md) and [device verification](docs/downloads-crossfade-verification-2026-10-06.md). All 273 JVM/Compose tests and 17 distinct phone tests passed; lint completed with zero errors.

- Ordinary online audio now uses direct transport to address the short playback interruption. Cache identities distinguish transport, format and bitrate so different byte layouts or qualities do not share entries. See [the playback verification](docs/playback-fix-2026-10-05.md).
- Downloads replaces its settings shortcut with Delete all downloads, with confirmation and cancellation of pending transfers. Playlist and album pages offer Download all songs after the complete collection loads; repeated songs and existing downloads are retained once.
- Search has a clear-text cross icon. Download storage budgets extend through 24, 32, 64, 128 and 256 GB to Unlimited; device free-space reserves still apply. Clearing playback cache requires confirmation.
- Crossfade accepts audio metadata with a null video URI, prepares the outgoing copy early, preloads the next song, and blends with equal-power gains. Pauses, seeks, live/video items and sleep timers remain excluded or cancel the blend.
- Lyrics timing starts and resets at +0.2s. Timing, player, song, long-press, sort and language menus have frosted backgrounds with sharp text. Appearance has an independent Frosted glass intensity slider. The translation metrics text is removed from the lyrics page.
- Tapping the queue's current-song thumbnail returns to the player through its existing artwork transition.
- Artwork, title and artist fade only during an actual automatic audio crossfade. The full player, mini player and queue header follow the audio engine's live progress for the entire blend; manual song changes and crossfade-off playback replace content immediately, respecting reduced motion.
- Both crossfade volumes use a smooth monotonic clock. Decoder startup and buffering no longer compress the fade, timestamp jumps cannot jump either gain, and an early outgoing end cannot snap the incoming volume to full. The outgoing copy attaches the same loudness processing as the main player when its audio session becomes ready.

Newest first. Release candidates before 0.9.0-rc16 were recorded only in the README; their text is kept here unchanged. Detailed implementation and verification records are in `docs/`.

## 0.9.0-rc20

- **Lyrics:** adds anonymous NetEase Cloud Music matching with synced lyrics and supplied YRC word timing when available, plus Lyrics.ovh as the final plain-text fallback. Priority is Better Lyrics TTML, QQ, NetEase, Kugou, LRCLIB, YouTube Music, Lyrics.ovh. Word timing beats line timing, which beats plain text; provider order breaks ties. Lower-quality results keep searching, and complete results share the existing preload/cache pipeline.
- **Provider limits:** Spicy Lyrics is excluded by request. Megalobiz is deferred by request after live checks found its old search route gone and its new API requires an application token.
- NetEase may require anonymous-client verification; those responses automatically fall through to later sources. Matched NetEase lyrics could not be verified live on the connected phone.
- Includes rc19 playback recovery, visible queue removal and Data saver changes.

## 0.9.0-rc19

- **Playback recovery:** credential refresh now resolves and replaces the rejected stream instead of preparing the same source again. Prefetched stream cache entries are invalidated together after attestation refresh. HTTP 403/410 gets a fresh stream, and repeated SABR failures use bounded alternate-profile recovery. Queue order, position, and a pause or song change during recovery are respected.
- **Queue:** every Up next and History row has a visible remove button alongside its drag handle. The last song can also be removed, leaving playback paused and the queue empty.
- **Data saver:** Settings -> Data usage offers a persistent toggle that requests lower-bitrate audio and up to 360p video for new streams. The setting has its own stream-cache identity, so a previously resolved higher-quality stream isn't reused. Downloads retain their existing high-quality selection.

## 0.9.0-rc18

Work from [the 2026-10-03 improvement plan](docs/improvement-plan-2026-10-03.md), on top of rc17.

- **Playback reliability (W1):** QuickJS challenge solving runs in a separate `:solver` process, so a native crash no longer stops playback (reported upstream as [quickjs-kt#244](https://github.com/dokar3/quickjs-kt/issues/244) and [innertubex#23](https://github.com/MetrolistGroup/innertubex/issues/23)). SABR attestation recovery is tested and was verified on a device. The app records why the playback service stops.
- **Crash notice (W1.5):** after an app crash the queue is restored paused, as before, and the mini player says once "Stopped unexpectedly" (TalkBack: "Playback stopped unexpectedly. Tap play to continue."), with a dismiss button
- **Playback diagnostics (W1.4):** Settings → About → Copy playback diagnostics copies the last 200 playback events, with URLs and tokens removed.
- **Smoother rotation, dark mode and font changes (W2):** no main-thread disk reads during recreation, and MainActivity handles configuration changes in place (frames skipped in the churn test: 703 → 68).
- **Home loading (W3):** skeleton shelves, sized like the real ones, replace the "Loading YouTube Music…" text while Home waits for the fresh feed. Home still never shows an older saved feed first (rc17 behaviour, kept by choice).
- **Smaller app (W4):** 128.5 → 26.1 MiB (R8, resource shrinking, arm64-only release). Riff Lite keeps 32-bit ARM.
- **Session expiry (W5):** detects when YouTube Music no longer accepts the sign-in, shows "Session expired · Sign in again", and gives a clear message where account features fail. Google's rotating session cookies from successful responses are now saved, so the sign-in should last longer.
- **Sleep timer (W6):** keeps its deadline when Android kills the app.
- **Tests and CI (W7):** Robolectric Compose UI tests, GitHub Actions CI with an unsigned release build and a 40 MiB size budget.
- **Found on the phone during the overnight check (2026-10-03):** an instrumental no longer says "Couldn't load lyrics" when one lyrics source fails but the others answer; offline, screens say "You're offline. Check your connection and try again." instead of a raw DNS error; the crash notice is shortened to fit the mini player; Riff no longer stacks extra copies of its main screen (a launcher tap after opening it from the notification or a widget added one each time; 40 copies and 710 MB in the overnight random-input run); after a network error, Play works on the first tap (it used to need two); and playback that stopped because the connection dropped now resumes by itself when the network comes back (it used to stay stopped until Play was tapped).
- **Speed dial:** now exactly three pages (27 songs) taken from YouTube Music's Listen again, in its order. If Listen again has fewer than 27 songs, recent and other Home songs fill the rest. Albums, playlists and podcasts are no longer mixed in.
- **Build hygiene (W8):** removed the retired `:core:glass` module (tag `archive/core-glass`), added the Unix `gradlew`, and moved this history out of the README.

## 0.9.0-rc17

Volume booster (Settings → Audio, 100–300%), a player options menu that unfolds from the tapped button, Listen again songs merged into Speed dial, Home waiting for a fresh feed before showing personal cards, and the Riff Lite build.

Details: [docs/app-revamp-rc17.md](docs/app-revamp-rc17.md)

## 0.9.0-rc16

Fixes native YouTube Music timed-lyrics requests/parsing and mixes a limited selection of Listen again songs into song-first Speed dial.

Details: [docs/app-revamp-rc16.md](docs/app-revamp-rc16.md)

## 0.9.0-rc15

Adds daily licensed mood photographs, 20 designed minimal cover fallbacks, compact playlist/download artwork and artist rows, a visible sleep countdown with a final 15-second fade, route-aware output icons, locally refracted navigation labels, separate Speed dial/Listen again shelves, exact YouTube Music Song/Video pairing and a much larger live-radio catalogue.

Details: [docs/app-revamp-rc15.md](docs/app-revamp-rc15.md)

## 0.9.0-rc14

Adds process-memory feeds with local 6 AM/6 PM refresh, provider-curated Home moods, fresh Library playlist membership with cached details, real playlist editing and multi-playlist saves, collection search/sorting, six widget designs, a sleep timer, immediate queue-handle dragging, 90 Hz support and stable high-contrast navigation.

Details: [docs/app-revamp-rc14.md](docs/app-revamp-rc14.md)

## 0.9.0-rc13

Reduces startup, transition, scrolling, press and palette animation work; fixes seeking in existing downloads; selects original audio before format ranking; orders Downloads newest first; and repairs broken UI punctuation.

Details: [docs/app-revamp-rc13.md](docs/app-revamp-rc13.md)

## 0.9.0-rc12

Introduces grouped Settings, a global haptic switch, optional mobile data for Smart Downloads, a 1–16 GB storage slider, and an album-style Downloads collection headed by the most recently downloaded song’s artwork.

Details: [docs/app-revamp-rc12.md](docs/app-revamp-rc12.md)

## 0.9.0-rc11

Adds verified official song videos, permanent highest-quality audio/artwork/timed-lyrics downloads, a Downloads manager, faster deferred playback, a bounded audio cache, and optional Smart Downloads for up to 250 recent songs.

Details: [docs/app-revamp-rc11.md](docs/app-revamp-rc11.md)

## 0.9.0-rc10

Adds semantic touch haptics with a saved switch, shared background lyrics preloading, a floating navigation selection pill, double-tap tab reset, and authenticated native YouTube live playback that continues when minimized and backgrounded.

Details: [docs/app-revamp-rc10.md](docs/app-revamp-rc10.md)

## 0.9.0-rc9

Adds Android 12 support, complete ordered playlist queues, matching album/playlist pages, smaller compact song rows, persistent search history, on-device English lyrics translation, in-player live/video playback with black video backgrounds, clear frosted category labels, aligned search pills, edge-to-edge scrolling shelves, cinematic startup, and duplicate/queue-scroll fixes.

Details: [docs/app-revamp-rc9.md](docs/app-revamp-rc9.md)

## 0.9.0-rc8

Adds immersive album/artist pages, ordered album queues, the animated inline queue, dominant artwork colors, hardware-blurred frosted glass and intensity settings, YouTube live broadcasts and native song video, podcast genres, Better Lyrics priority, and paused restoration of the last song, position and queue.

Details: [docs/app-revamp-rc8.md](docs/app-revamp-rc8.md)

## 0.9.0-rc7

Adds artist and album discovery, account library browsing with pagination, YouTube Music likes, automatic recommendation queues, live channel radio, Settings, rounded player icons, capsule sliders, and shared player/lyrics transitions.

Details: [docs/app-revamp-rc7.md](docs/app-revamp-rc7.md)

## 0.9.0-rc6

Adds real multi-provider lyrics, word/syllable highlighting when supplied, line-synced fallback, automatic following, lyric tap-to-seek, timing adjustment, and an offline lyrics cache.

## 0.9.0-rc5

Includes player artwork blending, a slim volume track, clean artist labels, real account names/photos, tighter top spacing and liquid-glass categories.

## 0.9.0-rc4

Removed the complete demo catalog, generated audio and artwork, demo search, demo albums and playlists, demo podcast/radio content, demo lyrics, and the prototype gallery/lab routes.

Details: [docs/no-demo-update-2026-09-30.md](docs/no-demo-update-2026-09-30.md)
