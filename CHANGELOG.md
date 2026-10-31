# Changelog

Newest first. Release candidates before 0.9.0-rc16 were recorded only in the README; their text is kept here unchanged. Detailed implementation and verification records are in `docs/`.

## Unreleased (branch `feat/next-features`)

The features planned in `docs/feasibility-glass-font-lyrics-artwork-2026-10-27.md`; its implementation log records what each one differs from the plan and what is still to check on a phone. The "What's new" sheet (`res/raw/whats_new.json`) is written for version code 34, so it first shows when the release is numbered. Since 0.20.0's new signing key makes every install a fresh one, a fresh install of it shows the release's cards too, with the tips after them.

- Look: Liquid Glass written in Riff, on the shell and every control (Features 1, 2); Inter tuned as Riff's type (3); a dark fade at the screen's edges (11); calmer, slightly slower transitions (35); the player redesigned (34); status bar icons that follow the cover (33); loading, empty and offline states (28); predictive back (22); a side rail and a player pane on wide screens (30).
- Listening: Encore, statistics for day, week, month, year and lifetime with charts, stories and genres (12); an equalizer (10); skipping the silence at a song's end (9); smart crossfade that blends where songs wind down and, when both have a beat, on the downbeat (20, stages 1 and 2); playback speed (15); a sleep timer by songs or time (18); song quality per network (17); stats for nerds (16).
- Lyrics: lrc.red as a source (4); other lines blurred and faded (5); dots for instrumental parts (32); romanized lyrics (25).
- Settings: redesigned around an opening page of quick switches and a glass page per section; Moods (Party, Focus, Commute, Night, and your own); switch icons that change between off and on; a note in Look and feel when the phone's heat has cut the glass back.
- Automix: the Crossfade / Smart / Automix choice; an on-device beat model (Beat This! small, int8 ONNX, on the standard ONNX Runtime) for the beat grid (20, stage 4), which makes the APK about 50 MB.
- Elsewhere: Android Auto (14); Cast to Chromecasts and Google speakers, which brings Google Play services (19); YouTube Music links (13); search suggestions and a top result (24); artist "See all" (8); a scrolling title (7); Undo for queue and playlist removals (26); lock-screen Shuffle and Like (23); app shortcuts and a play/pause tile (27); a "What's new" sheet and first-run tips (29).
- Fixes from the owner's checks on 31 Oct: every pop-out (song and player menus, sort menus, and every dialog, such as Lyrics timing, Sleep timer, Playback speed and the playlist dialogs) takes one look after the owner's reference: neutral grey glass with large corners and a hairline rim, rows led by their icon, regular Inter type. What each one offers is unchanged. In smart and Automix blends, the outgoing song no longer jumps in volume as the second player takes it over. A dialog's glass follows the page under it (Lyrics timing over moving lyrics kept the line it opened on).

## 0.10.0 (code 33), final candidate, prepared, not published

Release notes: `docs/release-notes-0.10.0.md`. Built from the final commit of the excellence plan and checked locally; nothing has been published. Everything since 0.10.0-rc1, from the Q18 and Q19 rounds (bug numbers from `docs/quality/q18-bugs.md`):

- Sign-in rebuilt to the agreed design (Q18-013): Riff's frosted Welcome back, All accounts, Checking your account and You're in screens around Google's unchanged page, which stays on Google's own hosts, country domains included (Q18-009). The saved accounts show one row each with the full address (Q18-085, Q18-088); Sign out asks first and clears the list (Q18-086); the sign-in animation always plays in full (Q18-039); You're in shows real counts (Q18-038).
- Switching to a saved account without Google's page when Google still holds it (Q18-087), with Google's account list asked by POST as Chrome does (Q18-100). An account picked from the list is committed only when it is the one reached; otherwise nothing is committed and Google's own account page opens (Q18-110). Another sign-in's cookies are never sent under the committed account (Q18-010); a search on screen is dropped when the account changes (Q18-012).
- Backup on this phone (F9, on by default): one unencrypted file per owner in Documents/Riff Backup, offered for restoring after a reinstall and per account at its sign-in; numbered copies tidied and the backup refreshed after a restore (Q18-078); an empty new install never overwrites the real backup (Q18-053); an update is never offered a restore (Q18-054).
- Not interested reaches YouTube Music (F10): its own Not interested where offered, otherwise a dislike, which Undo removes on the same account.
- Speed dial pins (F7): songs, episodes, podcasts, albums, playlists and artists, up to 27, first with a badge, carried in the export file and the backup.
- Share lyrics (F2): hold a line, pick up to five, and save or share a picture in a cover, dark, Riff pink or Aurora look (Q18-036); each card its own file (Q18-048); no first letter clipped (Q18-037).
- Player: tapping a song opens the full player (F1); Previous restarts a song past 10 seconds (F3), while a swipe back on the artwork always goes to the song before; the clock counts hours (F6); nothing moves while the mini player hands over (Q18-001, Q18-055, Q18-068); the open queue fills the player and opens at its top (Q18-079); Play after the end plays again (Q18-063); Pause shows while a song waits for a connection (Q18-076).
- Playback: Song quality High, Medium (default) or Low, with the Premium hint on High once Premium is confirmed (Q18-099); the next song's stream looked up while one plays, the session's visitor ready before the prewarm, and the signed-out player prepared when needed (Q18-059, Q18-067); the second player fetched beside the first at start (Q18-098); streams over OkHttp so the connection holds across seeks (Q18-057); one more try after reconnecting (Q18-064); a lookup past its deadline waits for a connection instead of buffering (Q18-103); both players keep five minutes behind the playhead (Q18-005); a song still being recovered is not an error to Android (Q18-020); a solver failure reaches recovery (Q18-022); a VPN with no validated network counts as offline (Q18-017).
- Audio: compressed (DRC) copies dropped when the normal one is offered; downloads ask YouTube Music's own client first; Premium passed once confirmed; a limiter instead of the 80 % knee (Q18-077, Q18-089 to Q18-092); loudness written once per track (Q18-106).
- Queue and library: more recommendations at the end of a song's queue, up to 200 (Q18-096); New playlist makes a real private YouTube Music playlist and Liked songs is Liked music (Q18-080, Q18-081); Rename and Delete for playlists the account can edit (Q18-104); a collection shows its song count and length, and a search row names its kind once (Q18-093, Q18-094); Save on this phone apart from the like, and undoable (Q18-083); Add to queue on a queued song says so (Q18-018).
- Lyrics: sources asked together and ranked by timing, then source, with a 2 s window for a higher-ranked source of the same timing (Q18-082); a Better Lyrics cache miss asked once more (Q18-065); lines drawn for the moment they are seen (Q18-056); punctuation lights with its word (Q18-061); credit lines left out (Q18-062); held words measured against their line and grown in place (Q18-004, Q18-084); line timing no longer ends the search for word timing (Q18-003).
- Removed: lyrics translation, with ML Kit and the Play services parts it brought (Q18-101).
- Look and feel: frosted glass by default at 20 %, to the edges and bending at the rim (F8, Q18-049), blurring only strips around the glass at half size (Q18-030); settings in compartments (F5); graded slider haptics (F4); no fade or patch over artwork, the controls carry shadows (Q18-002, Q18-008); artwork colours and fades back to 0.9.0-rc20's at the owner's request, without the title block and the extra darkening 0.10.0-rc1 added.
- Downloads: fast seeking in Riff's own WebM, existing downloads stored again (Q18-057); hand-chosen downloads left waiting start again when Riff comes to the front (Q18-021); Android 15's daily download limit handled (Q18-026); a cancelled run lets go of the engine (Q18-035); the space kept free is at most 2 GB (Q18-066); the Downloads list race fixed.
- Privacy and data: every action asks first (Q18-102).
- Accessibility: download phases announced once, never percentages (Q18-031, not checked by TalkBack); sliders named with spoken values (Q18-033); the widget play button named by state (Q18-032).
- Build: AGP 9.4.1 with R8 9.4 on Gradle 9.8.2, 179 R8 metadata warnings now 0 (Q18-024); the benchmark lane installs its own APKs (Q18-050) and stops a silent run (Q18-023).
- Fixed: a start-up crash reading a snapshot (Q18-051); Home's fresh feed asked for as the app opens (Q18-014).

## 0.10.0-rc1 (code 22), prepared, not published

Release notes: `docs/release-notes-0.10.0-rc1.md`. Built and checked locally; nothing has been published.

- Player: the close button and **NOW PLAYING** heading no longer sit on dark patches of their own, which looked like outlines; a shade across the top of the artwork keeps them readable over white artwork, and the close button has a soft shadow.
- Player: **Play video** is gone; the player plays a song's audio only. Live radio still shows its video.
- Fixed: on the lyrics page, a tap between the controls at the bottom could reach the home page underneath and start one of its songs.
- Fixed: while the player opened from the mini player or closed into it, the title, artist, artwork and play and next buttons briefly showed twice, a few dp apart.
- Player: shuffle and repeat have round line ends and corners, like the buttons beside them.
- Faster starts: the stream helper keeps YouTube's prepared player script on the phone instead of preparing it again every time it starts, and when you come back to Riff after five minutes or more it gets the stream helper and the playback attestation ready before you choose a song. The first song after a break used to wait for both.
- Documentation: README, features, privacy, architecture, testing, ADR status, final report and the dependency inventory were brought up to date with the code; older documents are marked as historical records (`docs/README.md`).
- Privacy: `docs/privacy.md` now lists every network destination. Newly disclosed: the YouTube library used for playback can fetch a pre-processed YouTube player from jsDelivr and run it in the stream helper process (no cookies or account details are sent). The app also names a player-configuration address that the library refuses, so it is never requested.
- Licenses: the dependency inventory was regenerated (233 runtime dependencies). It no longer lists demo audio or a motion loop, which are gone, and records that ML Kit and its Play services parts are under Google's terms rather than an open-source license, an open item for any public distribution.
- Build: a `preview` build type, the release under its own package (`com.musicglass.personal.preview`, launcher name Riff Preview), to try a candidate on a phone beside an installed release. Its data is separate; uninstalling it leaves the release untouched. Digest-checked test fixtures now keep LF line endings in every checkout, so a fresh Windows clone passes `ProviderFixtureTest`.
- Build tooling: `scripts/package-source.ps1` writes exactly the files Git tracks and refuses a tree whose tracked files differ from the commit; `scripts/audit_dependency_licenses.py` takes the dependency graph as an argument.
- Lyrics: in word-synced lyrics each letter pops up slightly (1 dp) as the sweep reaches it and stays up until the line leaves the screen. The letters of a held word also shimmer very slightly for as long as it is sung. With Android's animations turned off, letters neither pop nor shake.
- Song menus: a song that is already downloaded, or still downloading, now offers **Remove download** (pink, with a trash can) instead of **Download song**. This was wrong in the home page's long-press menu and in the player's menu, and is now the same in every song menu.
- Lyrics: when two voices overlap, both lines light up together, and a line stays lit while it is still sung even after a later line has ended (before, nothing was lit then). Background vocals in word-timed lyrics are their own smaller line under the main one, lit on their own timing. A word the singer holds grows a little, rises and glows pink while it is sung, then settles. The sweep is now white. With Android's animations turned off, held words glow but do not grow. Lyrics cached before this version are fetched once more.
- Adds **Settings → About → Diagnostics**: how Riff last closed (a crash, not responding, closed by Android for memory, or an ordinary stop, which is not shown as a failure) and the errors it recorded, with **Share diagnostics report**, **Copy playback diagnostics** and **Clear exit records**. The records keep error class names and positions in Riff's own code, never error messages, titles, addresses or account details. Nothing is sent automatically (`docs/quality/exit-diagnostics.md`).
- Build tooling: one entry point for the quality lanes, `scripts/quality/verify.py` (pull request, emulator, physical device, benchmarks, packaging), and an emulator workflow for API 31, 33 and 36. CI also runs on `codex/**` branches and checks dependencies strictly. Scripts find the Android SDK from `ANDROID_HOME` or `local.properties` before the project's own copy, and `scripts/download_favourites.py` takes the device and paths as arguments (`docs/quality/ci-lanes.md`).
- Adds an update notice: once a day the app can ask the public release feed whether a newer version exists, show one dismissible notice per version and open its release page. It never downloads or installs anything, sends no account or device details, and can be turned off. This build has no release feed (none is published yet), so it shows no such setting and asks nothing (`docs/quality/update-notice.md`).
- Security: another app that connects to Riff's media session can still play, pause, seek and skip, but can no longer remove, move or replace queue entries.
- Security: the photo credit links on Home open only HTTPS pages, in the browser. Before, the link text from the photo service was handed to Android as it came.
- Privacy: the on-device diagnostic log keeps only event names, numbers and short words. Server host names, cookie names, the system's crash description and a queue entry identifier are no longer written, and a log left by an older version is cleaned on first use. "Copy playback diagnostics" cleans every line again.
- Build: the Gradle wrapper download is checked against Gradle's published checksum, CI actions are pinned to commits, and a new check reads the release APK for backup, cleartext, exported components, permissions and leftover test code. See `docs/quality/security-review.md`, which also lists what is still open.
- Fixed: opening the full player ended the app on Android 12 and 13. The output row asked the selected audio route for its type, which only Android 14 and later can answer.
- The release now ships a Baseline Profile covering the start, a collection, the player and the queue. What it is worth has not been measured: no performance budget has been measured on a phone yet, and the cold-start budget is expected to be missed while the startup logo runs for 1.15 s (`docs/quality/performance.md`).
- Adds a `:benchmark` module (Jetpack Macrobenchmark 1.5.0) and two benchmark build types with their own package id and a fixture feed. None of it is in a release build.
- Accessibility: every queue row offers **Move up**, **Move down**, **Play next** and **Remove** to a screen reader or a switch, without dragging, and says its position. The like button says whether the song is liked. Essential controls take presses in at least 48 dp.
- Changed: text and controls drawn over artwork or a photograph now sit on a darker scrim or a patch of their own (the player's close button and heading, a collection's title block, Home over a photograph, speed-dial titles), and a colour taken from very bright artwork is darkened further, so they stay readable over a white picture. Screens with bright artwork look darker than before.
- At large text sizes on a short screen the player scrolls instead of squeezing its controls.
- All text the app shows or speaks moved to string resources (English only; no translations are included). Debug builds include the `en-XA` and `ar-XB` pseudo-locales.
- The accessibility checks are the app's own; no formal accessibility audit or certification has been done. TalkBack and keyboard walk-throughs on a phone are still to be done (`docs/quality/accessibility.md`).
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
- Playback that fails is retried in one place with one budget: at most three attempts for a song, after 0, 1 and 3 seconds, within 60 seconds. Before, three separate retry paths could run for the same failure.
- Without a connection the player shows **Connection lost; waiting to reconnect** and resumes when the connection has been back for half a second, however long that takes. Pause ends the wait and nothing resumes afterwards by itself.
- Changed: failure messages. A song that cannot be played says **Could not play this item** with **Retry** and **Skip**; an expired session says **Sign in again to access account content** with **Sign in**; a missing file says **File unavailable on this device**; unreadable download files say **Download incomplete or unavailable** with **Redownload** and **Remove**. No technical error text is shown.
- Changed: **Retry** keeps the queue and the position. Before, it replaced the queue with that one song.
- Play in the notification, on a headset or on a widget now retries a song that failed. Before, it did nothing until the app was opened.
- A recommendation queue passes over at most three unavailable songs in a row. Albums, playlists and songs you queued stop at the song that failed.
- New **Settings → Privacy and data → Export local data** and **Import local data**: settings, tracks saved in Riff, searches, listening history and the names of downloaded songs, as one file you choose the place of. The file is not encrypted and holds no sign-in data or audio. Import adds to what is on the phone and removes nothing; another account's data is added only if you confirm it. Downloads are restored as names only and must be downloaded again.
- Crossfade: turning repeat-one on in the last seconds of a song now prevents the blend. Before, a blend that was already being prepared went ahead and moved to the next song.
- Crossfade: a blend that was cancelled can no longer write the player's level a second time after something else has set it.
- New **Settings → Normalize volume**, off by default. When on, songs from YouTube Music that the provider reports as louder than its reference are turned down by that amount, by at most 12 dB. Nothing is ever turned up. Songs without a value, and audio stored on the phone, play unchanged.
- Downloads keep the provider's loudness value so they normalize offline. Songs downloaded before this update have no value and play unchanged until downloaded again.
- A download interrupted at any point, by the app closing, the phone restarting or storage filling, is put back in the queue at the next start with its unfinished files removed. Audio that was already saved is kept and only missing artwork or lyrics are fetched. A song is never shown as downloaded before its audio is safely written.
- Asking for a download, changing a download setting or switching account no longer stops the transfer in progress. Before, each of these restarted the background job and with it the song being downloaded.
- When Android refuses to start the download service from the background, queued songs wait for the background job instead of being dropped.
- Changed: a download needs room for two copies of the song while it is made, on top of the free space Riff always leaves (1 GB or a tenth of storage, also with the budget set to Unlimited). Space is checked again during the transfer and before the audio is rebuilt. A download that would not fit is refused at the start with **Not enough free space**.
- Changed: download status wording. States now read Waiting to download, Preparing download, Downloading, Checking audio, Saving artwork and lyrics, Paused, Download failed and Downloaded. A paused download says it **starts again from the beginning**, and the action is **Restart download**, or **Retry missing assets** when only artwork or lyrics are missing. Failure messages say what to do next.
- Changed: a failed download is tried again by itself in at most three runs, then waits for **Restart download**. Before, it was retried on every run without limit.
- A downloaded song that turns out to be damaged or missing is marked **Download failed**, is not offered to the player again, and streams instead when a connection is present. Played from a queue, it now shows **Download incomplete or unavailable** with **Redownload** and **Remove** rather than being retried as a streaming failure.
- Pause in the download notification pauses the downloads you asked for in the current account; downloads chosen by Smart Downloads and other accounts are not touched.
- Known limit: without crossfade, the first fraction of a second after a gapless change to the next song can still be at the previous song's gain.
- The Normalize volume switch is included in **Export local data**.
- Changed: if Riff cannot hand stream solving to its separate solver process, online songs now fail with "Online playback resolver unavailable" and Retry. Before, solving silently ran inside the playing process, where a crash would stop the music. Downloads and files on the phone are unaffected.
- Changed: History replays an entry for audio stored on the phone only when the file's title still matches. Before, the entry could start a different file that had taken the same number.
- Fixed: Privacy and data could briefly show empty counts when two of its readings overlapped.
- Internal restructuring with no intended change to what screens show: navigation uses typed, validated routes; the playback service and the provider are split by responsibility. See [docs/quality/architecture.md](docs/quality/architecture.md).
- Fixed: each tab keeps its own open album, artist or playlist. Before, one was shared by every tab and switching tabs could show the wrong one.
- The saved place in each tab now carries a version. Saved places from earlier builds are still read; a screen this build does not have is skipped.
- Screens stop reading background updates while Riff is not on screen and catch up when it returns.
- Fixed: work that was cancelled (leaving a screen, switching account) could carry on briefly and report a failure instead of stopping.
- Changed: asking Riff to play an audio file that cannot be read now reports the failure after the request is queued, instead of checking the file on the main thread first.
- Removed code that no screen could reach: an older queue panel, unused saved-track toggles and unused motion constants.
- No change to the app: an account-free check, made to run once a day, asks YouTube Music for one search, one album and the start of one song, to notice when the provider changes. It runs outside the app, from the project's own tooling. See [docs/quality/provider-compatibility.md](docs/quality/provider-compatibility.md).
- A playlist or album with more than 2,000 songs now opens showing the first 2,000, with a line saying so. Before, it failed to open. **Play**, **Shuffle**, **Download all songs** and sorting are disabled on a list that is not the whole collection; tapping a song still plays from what is shown.
- A list that YouTube Music stops sending part-way is labelled as possibly incomplete. Before, it was shown as if it were whole.
- Changed: adding a song to a playlist, or removing one, is reported as done only when YouTube Music says so. If no answer arrives, Riff reads the playlist to find out; if that also fails it says **YouTube Music did not confirm this change. Riff will check before trying again**, and checks before sending anything again, so a song is not added twice.
- Changed: a like or a library save answered with an empty reply is no longer treated as saved. A like is checked against YouTube Music; a save is reported as not confirmed.
- Adding a song to several playlists reports how many were saved, how many failed and how many were not confirmed. Retrying sends only to the ones that did not succeed.
- A song cannot be added to a playlist too large for Riff to read whole, because it could not check whether the song is already there.
- Changed: failure messages for browsing and search. Riff now says when YouTube Music is limiting requests, refused a request, is having trouble, or does not have the item. It does not say why an item is unavailable, because YouTube Music does not tell it.
- An error that YouTube Music sends inside an otherwise successful reply is now shown as an error. Before, it could appear as an empty page.
- Like, save and playlist actions say **Checking your account** while the session is being verified, instead of saying you are signed out.
- A change in the shape of YouTube Music's replies now leaves out the rows Riff cannot read instead of failing the whole page.
- Not checked against YouTube Music in this build: all of the above was tested with invented replies and no account. See `docs/quality/provider-compatibility.md`.
- Opening an album or artist from inside another collection and pressing Back returns to the first one where you left it. Before, it went back to the top.
- Coming back to Search from an album shows the same results at once, at the same place, without searching again.
- A list that fails to load says what went wrong with **Retry**, or **Sign in** when the session has expired, and the message stays until you act. A list that is only loading shows placeholders of fixed size, and one that is empty says so. Before, all three were a line of text.
- A next page that fails to load keeps the rows you have and says so beside **Load more**.
- The position and volume bars tell a screen reader their value ("1:05 of 3:20", "40 percent") and can be set by one. Live radio says **Live radio can't be rewound**.
- Changed: confirmation buttons name what they do (**Clear cache**, **Delete all downloads**). If clearing or deleting fails, the dialog stays open and says so with **Try again**.
- Changed: filled buttons have black text on pink. The previous dark purple was hard to read.
- Explore has **Retry** when it cannot load.

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
