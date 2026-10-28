# Privacy and permissions

Updated 24 October 2026 for Riff 0.10.0 (version code 33) as built from this repository. External providers have their own terms and privacy policies. Detailed designs: [account scope](quality/account-scope.md), [privacy and data controls](quality/privacy-controls.md), [state and migration](quality/state-and-migration.md), [export and import](quality/local-export-import.md), [exit diagnostics](quality/exit-diagnostics.md), [update notice](quality/update-notice.md), [security review](quality/security-review.md).

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
- A 256 MB playback cache of streamed audio, and the last successful mood photograph per mood.
- Provider sign-in cookies, in WebView's cookie store, used only for YouTube Music and YouTube requests.
- The accounts signed in on this phone (name, email address, when each was last used, its position in Google's own list of the accounts in the web session, and a digest of its account scope; at most 20), so the sign-in screen can list them and switch between them (Q18-013, Q18-087). Never their cookies: switching uses the one Google web session already on the phone, and when Google no longer holds an account there, picking it opens Google's own page. Each can be removed with **Settings → profile → Change account → See all → ⋮ → Remove from this phone**, which does not touch the Google account.
- Cached copies of the public YouTube player details the stream helper uses (below), and up to three copies of YouTube's player script as the stream helper prepares it, so a restarted helper does not prepare it again. These hold YouTube's public code only, live in the app's cache folder and go when Android clears it.
- Diagnostics: a 200-line event ring, the last 12 process exits as Android reports them, and the last 4 kinds of error per process (exception class, count, hour and positions in Riff's own code). No error messages, titles, addresses, tokens, cookies or account details.

Android cloud backup and device transfer are disabled (`allowBackup=false` and data-extraction rules that exclude everything). There is no cross-device synchronization.

### Data from earlier builds

Builds before per-account storage kept one listening history, one queue and one search list for every account on the phone. On upgrade these are not given to whoever signs in next. Plays of on-device audio go to This phone; the rest is held apart, shown to no owner, until the user decides in **Settings → Privacy and data → Earlier data**: import the plays, the queue or the searches into the owner active now (after a warning that they may include other accounts' listening), or delete them.

## Controls and retention

**Settings → Privacy and data** names the owner each action applies to and shows a count first:

- **Save listening history** (on by default) and **History retention**: keep until cleared (default), or 30, 90 or 365 days. Retention is applied after each recorded play, when the period changes and at most once a day at start, so plays can outlive the period until Riff next runs.
- **Clear account listening history**, **Clear device listening history**, **Clear search history**, **Delete downloads**, and **Delete local data for this account** (plays, searches, saved queue, tracks saved in Riff and downloads of the active owner).
- **Earlier data**, above, and **Move to another phone** (export and import), below.

Every clear asks first, says it cannot be undone, and reports what was removed, partly removed or not removed. None of them changes the YouTube Music account; likes, playlists and Google-held history are managed in Google's own account controls. Downloads → **Delete all downloads** and **Clear playback cache** (which keeps downloads) also ask first.

Settings → About → Diagnostics shows the exit and error records, can clear them, and can share or copy a plain-text report that is cleaned again line by line. Nothing is sent automatically; sharing is your action, so read a report before posting it publicly.

## Export file

**Settings → Privacy and data → Move to another phone → Export local data** writes one file to a place you choose through the system picker. You choose which owners to include. It can hold: settings you changed (haptics, solid controls, reduce motion, glass, audio boost, normalize volume, crossfade, data saver, song quality, history saving and period), tracks saved in Riff, recently played, searches, listening history, items marked Not interested, speed-dial pins (title, subtitle, artwork address and the identifiers that open them), and download names (song identifier, title, artist, album). Each account is named by an opaque digest.

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
| Pre-processed YouTube player on jsDelivr (`cdn.jsdelivr.net/gh/MetrolistGroup/faraday@.../<player>.json`), requested by the pinned YouTube library | Only in the stream helper process, when a playback challenge was not solved otherwise; cached for a day | A plain GET naming YouTube's current player version. No cookies, account, song or device details |
| Lyrics: Better Lyrics (`api.betterlyrics.org`, including its QQ and Kugou routes), NetEase (`music.163.com`), lrc.red (`lrc.red`), LRCLIB (`lrclib.net`), Lyrics.ovh (`api.lyrics.ovh`) | When a song's lyrics are needed | Public song metadata: title, artist, album and duration. A separate HTTP client without YouTube cookies. NetEase verification is never bypassed; Riff moves to the next provider |
| Encore genres: MusicBrainz (`musicbrainz.org`) | Only with **Encore → Find genres for your artists** on (off by default), on Wi-Fi, at most one request a second, once per artist every 90 days, for your top 200 artists | Artist names and a user agent naming Riff, its version and its source repository. No account, song or play details. Turning the setting off, or clearing history, deletes the answers kept |
| Mood photographs: Openverse (`api.openverse.org`), Wikimedia Commons (`commons.wikimedia.org`) and the image hosts they name | Home mood backgrounds, refreshed daily | Mood search words and a user agent naming Riff and its version |
| Cast speakers and TVs on your Wi-Fi, and Google Play services | Only while you cast (Feature 19): the speaker list is searched only while the speaker picker is open | The speaker is told each song's title, artist, artwork address and a direct YouTube stream address, and fetches the audio and artwork from YouTube itself, from your network. Riff's own session and notification stay the only ones on the phone. Play services runs the connection; the reports its Cast library would send Google about its own use are dropped on the phone, because Riff's manifest unregisters their uploader. Songs on the phone, and streams a speaker can't fetch by itself, are skipped, not relayed |
| Android's speech-recognition service | Only when you tap voice search | Your speech, handled by whichever service the phone is configured with, under its own settings; it may be processed remotely |

**Update check.** A build made with a release feed address asks that feed once a day while the app is open (Settings → About → Check for updates; off sends nothing). The request carries only `Accept` and `User-Agent: Riff-update-check`: no cookies, account, device, install or version details. Riff never downloads or installs anything; it only opens the release page when you ask. **This build has no feed address, so it shows no such setting and makes no such request.**

Internet access is needed for online content; downloaded songs play offline.

## Permissions

| Permission | Use |
|---|---|
| Internet, network state | Online content and network-aware work (Data saver, downloads on Wi-Fi only) |
| Read media audio (Android 13+), read external storage (Android 12 only) | On-device audio, requested on that screen; files stay on the phone |
| Record audio | Voice search, requested when you first tap it |
| Post notifications | Playback and download notifications, if allowed |
| Foreground service (media playback, data sync) | Background audio and downloads |
| Receive boot completed | Lets Android's scheduled-work library re-arm Smart Downloads and refresh jobs after a restart |

There is no install permission, no location, contacts, camera or account-list access, and cleartext HTTP is disabled.

## Contact

For app questions, use [GitHub Issues](https://github.com/leovaldez2k21/riff/issues/new/choose) once the repository is public; for security concerns, follow [SECURITY](../SECURITY.md).
