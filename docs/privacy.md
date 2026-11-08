# Privacy and permissions

Updated 30 October 2026 for Riff 0.20.0 (version code 34) as built from this repository. External providers have their own terms and privacy policies. Detailed designs: [account scope](quality/account-scope.md), [privacy and data controls](quality/privacy-controls.md), [state and migration](quality/state-and-migration.md), [export and import](quality/local-export-import.md), [exit diagnostics](quality/exit-diagnostics.md), [update notice](quality/update-notice.md), [security review](quality/security-review.md).

Riff has no server of its own, no analytics and no crash-reporting service. Nothing on the phone is sent to the developer. The network requests it makes are listed below.

## Whose data it is

Everything Riff keeps belongs to one owner:

| Owner | When |
|---|---|
| A YouTube Music account | Signed in, and the provider returned a stable account identifier. Data is stored under a key derived from that identifier (a SHA-256 digest), never the identifier itself |
| Signed-out listening (Guest) | Signed out |
| Unverified | Signed in, but no stable identifier came back. Nothing is saved or downloaded for it; the Account screen offers **Verify account** |
| This phone (DeviceLocal) | Audio files in the phone's own library: their queue and history, whichever account is signed in |

Signing out or switching accounts hides the other owner's queue, history, searches, saved tracks and downloads; returning to the same account shows them again. Raw account identifiers, names and email addresses are never written to logs or diagnostics.

## What stays on the phone

- Playback position and queue (per owner), settings, tracks saved in Riff, listening history (up to 100,000 plays per owner, each with how long it was heard, its album and how it ended, and daily listening totals for Encore that follow the history and its retention; with Encore genres on, the genres found per artist name, kept 90 days and cleared with history), recent searches (30 per owner), catalog caches.
- Downloaded audio, artwork and lyrics, app-private and per owner. Uninstalling deletes them.
- A 256 MB playback cache of streamed audio with a small list beside it (up to 512 songs: an account-derived key, song id, quality and format details; no addresses or cookies), so a song whose start is on disk plays without a lookup. It goes with **Clear playback cache**, and stays through sign-out like the audio, where another account can't use it. The last successful mood photograph per mood.
- With a smart crossfade (Smart or Automix) or the end-silence skip on, each song's analysed end and start: its loudness every 100 ms and its beats, kept by its id for up to 3,000 songs, so a song is analysed once. No audio, titles or account details; computed on the phone from audio it already has.
- Provider sign-in cookies, in WebView's cookie store, used only for YouTube Music and YouTube requests.
- The accounts signed in on this phone (name, email address, when each was last used, its position in Google's own list of the accounts in the web session, and a digest of its account scope; at most 20), so the sign-in screen can list them and switch between them (Q18-013, Q18-087). Never their cookies: switching uses the one Google web session already on the phone, and when Google no longer holds an account there, picking it opens Google's own page. Each can be removed with **Settings → profile → Change account → See all → ⋮ → Remove from this phone**, which does not touch the Google account.
- Cached copies of the public YouTube player details the stream helper uses, the public player address and versions YouTube named last (so the first lookup after a restart is quicker), and up to three copies of YouTube's player script as the stream helper prepares it, so a restarted helper does not prepare it again. These hold YouTube's public code only, live in the app's cache folder and go when Android clears it.
- Diagnostics: a 200-line event ring, the last 12 process exits as Android reports them, and the last 4 kinds of error per process (exception class, count, hour and positions in Riff's own code). No error messages, titles, addresses, tokens, cookies or account details.

Android cloud backup and device transfer are disabled (`allowBackup=false` and data-extraction rules that exclude everything). There is no cross-device synchronization.

### Data from earlier builds

Builds before per-account storage kept one listening history, one queue and one search list for every account on the phone. On upgrade these are not given to whoever signs in next. Plays of on-device audio go to This phone; the rest is held apart, shown to no owner, until the user decides in **Settings → Privacy and data → Earlier data**: import the plays, the queue or the searches into the owner active now (after a warning that they may include other accounts' listening), or delete them.

## Controls and retention

**Settings → Privacy and data** names the owner each action applies to and shows a count first:

- **Save listening history** (on by default) and **History retention**: keep until cleared (default), or 30, 90 or 365 days. Retention is applied after each recorded play, when the period changes and at most once a day at start, so plays can outlive the period until Riff next runs.
- **Clear account listening history**, **Clear device listening history**, **Clear search history**, **Delete downloads**, and **Delete local data for this account** (plays, searches, saved queue, tracks saved in Riff, the never-play list, playlists on this phone and downloads of the active owner).
- **Earlier data**, above, and **Move to another phone** (export and import), below.

Every clear asks first, says it cannot be undone, and reports what was removed, partly removed or not removed. None of them changes the YouTube Music account; likes, playlists and Google-held history are managed in Google's own account controls. Downloads → **Delete all downloads** and **Clear playback cache** (which keeps downloads) also ask first.

Settings → About → Diagnostics shows the exit and error records, can clear them, and can share or copy a plain-text report that is cleaned again line by line. Nothing is sent automatically; sharing is your action, so read a report before posting it publicly.

## Export file

**Settings → Privacy and data → Move to another phone → Export local data** writes one file to a place you choose through the system picker. You choose which owners to include. It can hold: settings you changed (haptics, solid controls, reduce motion, glass, audio boost, normalize volume, crossfade, data saver, song quality, history saving and period), tracks saved in Riff, recently played, searches, listening history, items marked Not interested, speed-dial pins (title, subtitle, artwork address and the identifiers that open them), download names (song identifier, title, artist, album), and (unreleased 0.30.0) each account's never-play list and playlists on this phone (names, song identifiers, titles and artists). Each account is named by an opaque digest.

It never holds cookies, tokens, account names or identifiers, stream addresses, audio, image bytes, lyrics, diagnostics, the saved queue, or download settings. **The file is not encrypted**: anyone who has it can read the listening history and searches in it. **Import local data** adds to what is on the phone and removes nothing. Riff requests no storage permission for either.

## Backup on this phone

**Settings → Privacy and data → Backup on this phone** (on by default) keeps one file per owner, in the same format as the export file, in `Documents/Riff Backup`, so it stays when Riff is uninstalled. After a reinstall Riff offers to restore it; you pick the folder once in the system picker. It holds what the export file holds and nothing more. **It is not encrypted**: an app with access to all files, or someone using the unlocked phone, can read it. Turning the backup off removes the files this install wrote; files an earlier install left are listed there while it is off, for you to remove. Riff Preview and debug builds use their own folders, `Riff Backup (preview)` and `Riff Backup (debug)`.

## Not interested

When signed in, **Not interested** tells YouTube Music too: its own "Not interested" where YouTube Music offered one for that card, otherwise a **dislike** on the song, which YouTube Music uses to keep it out of mixes and radio. **Undo** removes that dislike on the same account.

## Network requests

Providers can observe ordinary connection information such as IP address, time and user agent.

| Destination | When | What is sent |
|---|---|---|
| YouTube Music and YouTube (`music.youtube.com`, `www.youtube.com`, `*.googlevideo.com`, `i.ytimg.com`), Google sign-in (`accounts.google.com`) | Browsing, search, playback, recommendations, artwork, library actions, sign-in in an in-app WebView, the list of Google accounts signed in there (`accounts.google.com/ListAccounts`, asked with a POST as Chrome does; to show each account's address and switch between them) | Provider requests with the session cookies when signed in. Likes and playlist edits are sent only when you make them (from the player, or from the Like button on the notification, lock screen or car). Search suggestions: the words typed in Search are sent as you type them, 200 ms after the last key; turn this off in **Settings → Privacy and data → Search suggestions** |
| Lyrics: Better Lyrics (`api.betterlyrics.org`, including its QQ and Kugou routes), NetEase (`music.163.com`), lrc.red (`lrc.red`), LRCLIB (`lrclib.net`), Lyrics.ovh (`api.lyrics.ovh`) | When a song's lyrics are needed | Public song metadata: title, artist, album and duration. A separate HTTP client without YouTube cookies. NetEase verification is never bypassed; Riff moves to the next provider. **Lyrics → Source for this song** (unreleased 0.30.0) sends the same metadata to the one source you pick, then, if it has nothing or can't be reached, to the usual sources. Translations come in the same answers; no lyric text is sent anywhere to be translated |
| Encore artist photos: YouTube Music (`music.youtube.com`) | When Encore opens, for up to 20 top artists without a photo, each at most once a run | An artist's channel id (a page read as the rest of the app reads it, signed in when you are), or an artist's name in a signed-out search |
| Encore genres: MusicBrainz (`musicbrainz.org`) | Only with **Encore → Find genres for your artists** on (off by default), on Wi-Fi, at most one request a second, once per artist every 90 days, for your top 200 artists | Artist names and a user agent naming Riff, its version and its source repository. No account, song or play details. Turning the setting off, or clearing history, deletes the answers kept |
| Mood photographs: Openverse (`api.openverse.org`), Wikimedia Commons (`commons.wikimedia.org`) and the image hosts they name | Home mood backgrounds, refreshed daily | Mood search words and a user agent naming Riff and its version |
| Cast speakers and TVs on your Wi-Fi, and Google Play services | Only while you cast (Feature 19): the speaker list is searched only while the speaker picker is open | The speaker is told each song's title, artist, artwork address and a direct YouTube stream address, and fetches the audio and artwork from YouTube itself, from your network. Riff's own session and notification stay the only ones on the phone. Play services runs the connection; the reports its Cast library would send Google about its own use are dropped on the phone, because Riff's manifest unregisters their uploader. Songs on the phone, and streams a speaker can't fetch by itself, are skipped, not relayed |
| About this song (Riff+, unreleased 0.30.0): MusicBrainz (`musicbrainz.org`), Wikidata (`www.wikidata.org`), Wikipedia (`<language>.wikipedia.org`) | Only when you open **About this song** in Now Playing's menu, and again only with **Try again**; MusicBrainz at most one request a second, shared with Encore genres | The song's title and artist (to MusicBrainz), then the MusicBrainz and Wikidata identifiers and article titles those answers name, and a user agent naming Riff, its version and its source repository. No account, history or play details. Answers are kept in the app's cache for 30 days, at most 200 of them, and are not exported or backed up |
| Android's speech-recognition service | Only when you tap voice search | Your speech, handled by whichever service the phone is configured with, under its own settings; it may be processed remotely |

**Update check.** A build made with a release feed address asks that feed once a day while the app is open (Settings → About → Check for updates; off sends nothing). The request carries only `Accept` and `User-Agent: Riff-update-check`: no cookies, account, device, install or version details. The check itself downloads and installs nothing. From 0.20.0 the feed is this repository's GitHub releases (`api.github.com/repos/leovaldez2k22/riff/releases`), and release pages open on `github.com`. Builds made without a feed address show no such setting and make no such request. The unreleased 0.30.0 split also reads exact edition APK names and sizes from that same response. It keeps public version/page/asset metadata privately for notices and an optional same-version About switch. The installed edition is not sent. **In-app install (feature 3, unreleased 0.30.0).** Only when you tap Update or the About switch and then Download, a release build streams that one APK from its GitHub release path (`github.com/.../releases/download/...`) and the GitHub download host it redirects to (`*.githubusercontent.com`, any other address stops it), with `User-Agent: Riff-update` and no cookies or account details. When every release the feed lists is newer than the installed one, the same feed host is asked once for the installed version's tag so the switch can find it. The APK stays in Riff's private storage until it is checked (size, digest when GitHub lists one, package, version, signer and edition) and handed to Android's installer, which asks you to confirm; then it is deleted. Riff keeps only the pending install's number, kind, edition and version, and forgets them once Android answers. Android asks you once to allow Riff to install apps; you can turn that off in Android's settings. Local and debug builds keep opening the release page.

Internet access is needed for online content; downloaded songs play offline.

**Search by lyrics and song.link (unreleased 0.30.0).** Search → Lyrics looks only through the lyrics saved with this account's downloads, on the phone. The words you type are sent nowhere, are kept out of recent searches and suggestions, and the index of them lives in memory only, rebuilt when downloads or the account change. **Share song.link** builds a `https://song.link/…` address for the song's public YouTube Music link on the phone, without contacting song.link; whoever opens it shows that song to song.link. Phone-only files have nothing to share.

**Most replayed (Riff+, unreleased 0.30.0).** Once the song playing has reached 5 seconds, Riff+ asks YouTube (`www.youtube.com/youtubei/v1/next`, as its web player does) for that song's public video id, with no cookies or account and its own HTTP client. The answer (about 450 KB) is read only for the replay heatmap; the most replayed moment is kept in memory for up to 64 songs, and a song without one is not asked again for 10 minutes. Nothing is asked for phone-only files, live streams, or while offline. Riff makes no such request.

**Never play, Bluetooth resume and pause on mute (unreleased 0.30.0).** **Never play this song / artist** keeps a list of song and artist ids (with a name to show) on the phone, apart for each account and the guest, and tells nobody: YouTube Music is not told, unlike **Not interested**. It is listed, with Undo, in Settings → Library → Not interested. **Resume on Bluetooth** and **Pause on mute** (Settings → Sound, both off by default) watch only which audio outputs Android reports and the media volume, while Riff plays; Riff asks for no Bluetooth permission, keeps the output it waits for only in memory for up to 10 minutes, and records nothing about it.

**Select several songs (unreleased 0.30.0).** Picking songs is kept in memory while the list is open. Play next, Download and Add to playlist then do what they do for one song, for each pick in the order tapped; only Add to playlist writes to the YouTube Music account, and only to the playlists you tick.

**Playlists on this phone and importing (unreleased 0.30.0).** Library → Playlists → **Playlists on this phone** imports a file you pick in the system picker (CSV such as a Spotify export, M3U/M3U8, or the playlist file of Spotify's account-data download; at most 2 MB and 5,000 rows). The file is read once and not kept; no address in it is fetched and no path in it is opened. Each row without a YouTube link is looked up with a YouTube Music song search (its title and first artist, one search at a time, signed in when you are), so YouTube Music sees those searches. The playlist you save is kept on the phone for that account. **Copy to YouTube Music** writes to the account only after you confirm it, each time: it makes one private playlist and adds the songs, keeping that playlist's id and which songs went in, so a copy that stopped carries on without making a second one. Riff does not connect to Spotify. Phone playlists and the never-play list travel in the export file and the backup on this phone with their account (a playlist without its YouTube Music copy, which names that account's playlist), and **Delete local data for this account** removes both; a copy already on YouTube Music stays there.

**ListenBrainz and Last.fm (unreleased 0.30.0).** Settings → Library and privacy → **ListenBrainz and Last.fm**. Each service is off until you connect it, and the connection belongs to the phone, not to a YouTube Music account. Once a song longer than 30 seconds has been heard for half its length or four minutes (whichever comes first; time skipped by seeking does not count), Riff keeps a pending row on the phone (title, artist, album, length, the time it started and, for ListenBrainz, its YouTube Music address) and sends it to `api.listenbrainz.org` with your user token or to `ws.audioscrobbler.com` with Last.fm's session key. A row is removed when the service confirms it or refuses it for good, and unsent rows are dropped after seven days. Only YouTube Music songs are sent, unless **Include files on this phone** is on; **Show what is playing now** (off by default) also tells the service about a song as it starts, without keeping anything. Nothing is kept or sent while listening history is off, and earlier history is never sent. Tokens are kept in their own file, locked with an AES key held in Android Keystore, and are never in the export file, the backup or diagnostics (diagnostics record only how many rows were sent). **Disconnect** forgets the token and that service's unsent rows; **Delete local data for this account** removes that account's unsent rows. Connecting Last.fm opens `www.last.fm` in your browser to allow Riff; Last.fm is offered only in builds made with a registered Last.fm app's key, whose secret, like every app's, can be read from the APK.

**Ringtone and M4A export (unreleased 0.30.0).** **Set as ringtone** (a finished download's menu) copies the song, whole or a 5–60 second part, without re-encoding, into `Ringtones/Riff` on the phone, where it stays if the download is removed. Only then does Riff ask for Android's **Modify system settings** access, which it uses only to set that file as the ringtone; without it nothing changes. **Export as M4A** (Riff+) writes the download to a file you choose in the system picker, with its title, artist, album, cover and, if you ask, plain lyrics as tags; Opus is converted to AAC on the phone first, after a warning. Nothing is sent anywhere for either.

**Visualizer, headphone profiles and pitch (Riff+, unreleased 0.30.0).** **Show visualizer** (Now Playing's menu, off by default) draws bars from the audio Riff itself is playing, after Riff+'s equalizer; it never uses the microphone, keeps no more than the last 2,048 samples in memory while Now Playing is on screen, and records, stores and sends nothing. A **headphone profile** is found in the list of AutoEq profiles built into Riff+ (searching it reads the app's own copy; nothing is fetched or sent) or read from an AutoEq text file you pick; Riff+ keeps only the seven gains it fitted, the headphone's name and the curve to revert to, on the phone. **Pitch** lasts until you reset it or Riff restarts; nothing is kept.

**Beat model (Riff+ from the unreleased 0.30.0 split).** Riff+ bundles Automix's beat model and ONNX Runtime; the light Riff edition bundles neither. The model runs on the phone; nothing about a song leaves the phone for it. ONNX Runtime 1.31.1 for Android comes with its own telemetry (a starting provider and an HTTP client for Microsoft's usage events). Riff+ removes that provider from its flavor manifest and turns the runtime's telemetry off, so it sends nothing. In debug builds since 30 October 2026, and in the release from 0.20.0.

**Mixes, About this song and Appearance (Riff+, unreleased 0.30.0).** The **Made from your listening** mixes on Home are worked out on the phone from Encore's own history and totals, and from Never play; nothing is sent, and the mixes are kept only in memory until the day, the part of the day or the history changes. **About this song** is described in the table above; Wikipedia text is shown with its CC BY-SA attribution and a link to the article. **Appearance** (icon style, colour and "Tint the app to match the icon") is a setting on the phone; switching the icon only changes which of Riff+'s own launcher entries Android shows. Nothing is sent.

**DJ order (Riff+, unreleased 0.30.0).** The queue's **DJ order** reads tempo and key only from audio already on the phone (a finished download or a file from the phone's music) and only when you press **Analyse**: 30 seconds of each song, one song at a time, stopping under battery saver or when the phone is warm. A streamed song is never fetched for it; it can use the tempo Automix already measured from its cached audio. The results (tempo, key and when they were made, for at most 3,000 songs) stay in Riff's own files on the phone; they are not exported, backed up or sent.

**Sing (Riff+, unreleased 0.30.0).** Sing separates a song's vocals on the phone, with a model bundled in Riff+ (Spleeter 2 stems, MIT): nothing is downloaded and no audio leaves the phone. It works only on audio already there (a finished download or a file from the phone's music) and only when you press **Prepare this song**. The instrumental it makes is kept in Riff's own files (about 10 MB a minute of music; the least recently used are removed past 600 MB), with the song's id hashed in its file name; it is not exported, backed up or sent. Whether Sing is on is a setting kept on the phone.

**Blend with a friend (Riff+, unreleased 0.30.0).** On Phone playlists, **Blend with a friend** shows a QR code of your top 100 songs played from YouTube Music in the past year (by listening time; Never play left out; files from the phone cannot be shared), after a preview of exactly those songs. The code holds only the songs' YouTube ids in order: no name, account, play counts, times or files, and anyone who sees it can read those songs. Scanning a friend's code uses the camera (asked for only then, used only while the scanner is open; no frame is kept) and makes the same playlist on both phones without any server: it is kept in Phone playlists like an imported one, and is never saved to your YouTube Music account. Afterwards Riff looks up the titles of the friend's songs on YouTube Music, as it would to play them. A scanned code is only read, never opened as a link.

## Permissions

| Permission | Use |
|---|---|
| Internet, network state | Online content and network-aware work (Data saver, downloads on Wi-Fi only) |
| Read media audio (Android 13+), read external storage (Android 12 only) | On-device audio, requested on that screen; files stay on the phone |
| Record audio | Voice search, requested when you first tap it |
| Post notifications | Playback and download notifications, if allowed |
| Foreground service (media playback, data sync) | Background audio and downloads |
| Wake lock | Media3 keeps CPU/network available while loading or playing audio; it does not hold playback wake locks while paused |
| Receive boot completed | Lets Android's scheduled-work library re-arm Smart Downloads and refresh jobs after a restart |
| Modify system settings (unreleased 0.30.0) | Only to set a song as the ringtone; Android asks you to allow it then, and it stays off otherwise |

The only install permission is Android's per-app "Install unknown apps" access, used for the in-app install above and off until you allow it. There is no location, contacts, camera or account-list access, and cleartext HTTP is disabled.

## Contact

For app questions, use [GitHub Issues](https://github.com/leovaldez2k22/riff/issues/new/choose) once the repository is public; for security concerns, follow [SECURITY](../SECURITY.md).

## Windows desktop lyrics (owner decision, 3 November 2026)

The owner approved automatic lyric lookup when a song starts playing. Riff may send its title, artist, album and duration to `https://lrclib.net` and `https://lrc.red`; requests are bounded and use HTTPS without authentication tokens or audio uploads. Local sidecars and scoped cached documents are checked first. Returned source text and timing, provenance and manual offset are cached on this Windows profile. Plain text receives no fabricated timings.


### Windows search suggestions and submitted searches (3 November 2026)

The desktop Search suggestions setting defaults on, following the approved Android behavior. After 200 ms of inactivity, up to the first 100 characters typed are sent to YouTube Music's existing suggestion endpoint through the anonymous read-only provider. Disabling the setting cancels requests and clears suggestions; normal search is separate and runs after 450 ms. Up to 20 explicitly submitted queries are retained locally per authority scope (maximum 20 scope histories); partial keystrokes are not saved as recent searches. No private Music account authentication is claimed. Pasted YouTube links are parsed locally: only recognized YouTube IDs and an optional bounded timestamp are used by the existing provider; arbitrary link destinations are never fetched.


### Windows listening-history authority (3 November 2026)

Desktop history records actual presented audio time. The default is on with 90-day retention, a maximum of 100,000 playback occurrences, preserved totals off and external genre lookup off. Multiple measured spans of one play share one occurrence; the cap does not treat each one-second span as a different play. Clearing local/guest history atomically deletes its spans, occurrence records and totals and advances a durable scope epoch. Pending writes captured before that clear are rejected. Switching history recording off or on also advances the epoch, so an earlier pending write cannot be accepted after a rapid off/on toggle. Pausing flushes the current measured span, and a span across midnight is attributed to the actual local days. Schema-3 migration preserves surviving older rows; it cannot recover detail already pruned by an older version. Complete Encore period charts, stories and exports still have separate acceptance gates.


### Windows explicit offline downloads (3 November 2026)

Save offline is explicit public-provider download intent, separate from opportunistic streaming cache and imported files. The same provider resolves the selected video ID. No account writes are made; private-account downloads are unavailable without a verified authentication contract. Signed playback URLs and transport headers are not stored in download records. SQLite stores scope/track identity, state, byte counts, SHA-256 and bounded failure codes; audio is stored under hashed names in the app's download directory. Defaults are a separate 2 GiB audio budget, 128 MiB per track and a four-hour decoded-duration bound. Partial copies are retained for checked resume; mismatched resume bytes force a fresh copy. Complete requires actual decode and digest validation. Removing deletes owned partial/complete audio after Undo expires; Windows file locks can defer final deletion. Quitting before Undo expires retains the completed copy. These features do not enable new lyrics destinations or change the owner's existing automatic-lookup choice.


### Windows local Encore and saves (3 November 2026)

Encore's music statistics are derived from real presented-time history in the local SQLite database. Its daily/hour/lifetime rollups follow history clearing and the existing retention/keep-totals preference; episodes are excluded from music statistics. Local CSV, JSON and PNG exports are written only to the destination chosen in the app. They do not upload history, images or audio. Story-seen markers are local session settings and clear with Encore. The desktop heart saves to “Saved locally”; it does not write to a YouTube Music account. Public provider entries in local playlists can reuse their verified guest offline download identity; private scopes cannot use that anonymous route. Desktop genre lookup defaults off. When explicitly enabled and Windows reports an unmetered connection, up to 200 retained artist names per refresh can be queried through HTTPS to musicbrainz.org. Requests carry the artist name and application User-Agent, never audio, account tokens or playback URLs. Ambiguous/fuzzy names receive no genre assignment. Scoped results are cached for 90 days with a 1,000-artist bound per scope; clear removes them, disabling hides them and fences pending writes. Windows unknown/metered/roaming/congested cost defers lookup. No telemetry is sent.


### Windows normalization (4 November 2026)

Normalize volume defaults off and uses the provider loudness value returned by the existing stream resolution. It makes no additional network lookup and never estimates or uploads audio. It attenuates by at most 12 dB, never boosts quiet or unknown recordings, and applies independently to both decks. Completed downloads can retain a small SHA-bound loudness receipt beside the owned audio; removing the download removes its receipt. Imports without such a verified receipt stay at unity.
