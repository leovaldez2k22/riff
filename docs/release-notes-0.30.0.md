# Riff 0.30.0 (version code 36)

**Status: draft for the owner's review, not published.** Written on the night of 9 November 2026 from the [implementation plan's log](feature-implementation-plan-0.30.0-2026-11-07.md#7-implementation-log). The candidate table below is filled in once signed candidates are built. The latest published release is 0.21.0.

Android 12 or later, 64-bit ARM phones. Both editions use the same certificate as 0.20.0 and 0.21.0 (SHA-256 `e45ac43b9d1c658c03ace002abb4c482cc3d63214a5f8509c801e404c91ff798`), so either **updates 0.21.0 in place**: downloads, the queue, history and settings are kept.

## Two editions

Riff now comes in two free editions, built from the same source:

- **Riff** is the lighter everyday player: playback, Smart crossfade, lyrics, downloads, Cast, Android Auto and all of Encore.
- **Riff+** has everything in Riff, plus Automix, the in-app equalizer, Stats for nerds and the Riff+ features listed below. It has no size limit and bundles its models, so nothing is downloaded later.

**If you use Automix or the equalizer in 0.21.0, choose Riff+.** Installing one replaces the other. Your saved settings stay when you switch, and Riff+ settings come back when you return to Riff+. In Riff, Automix becomes Smart. Settings → About offers the other edition at the same version.

## What's new in both editions

**Updates inside the app.** When update checks are on and a new version is out, Riff can download and install it itself. It shows you the size first and warns on mobile data, then checks the file's signature and edition before Android asks you to install. Switching between Riff and Riff+ works the same way.

**Lyrics.** Translations appear under the lines when the lyrics source sends them. A chip turns them off or picks the language. Tap the source's name to choose which source to use for a song. Search has a **Lyrics** chip that finds a phrase in the lyrics of your downloads, on the phone only. An instrumental that NetEase labelled as lyrics now shows as an instrumental.

**Never play.** Block a song or an artist from a song's menu. Radio and the queue skip a blocked song. A blocked song is also reported to YouTube Music as not interested, while an artist stays on Riff's list only. Settings → Library → Not interested lists them, each with Undo.

**Scrobbling.** Send what you play to ListenBrainz or Last.fm, under Settings → Library and privacy. A song longer than 30 s counts once you've heard half of it or 4 minutes, whichever comes first. Skipping ahead doesn't add to that. Unsent plays wait up to 7 days. Every service is off until you connect it.

**Select several songs.** Albums, playlists, search, Library and history let you pick songs, in the order you tap them, to queue, download or add to a playlist. A tick appears beside each song, and a glass bar holds the actions.

**Import playlists.** Import a CSV (Spotify exports included), M3U or Spotify account-data file into **Phone playlists**. You review each match, and you can copy the playlist to YouTube Music, with a confirmation every time.

**Also new:**

- Share song asks which link to send: YouTube Music, or song.link, which opens the song in other music apps.
- Set a downloaded song, or a part of it, as your ringtone.
- Resume on Bluetooth, and Pause on mute, which plays again when you turn the volume back up. Both are off by default, in Settings → Sound.
- New haptics: play, pause, skip, favourite, queue, download, shuffle, dragging and selecting each have their own feel, with a Haptic strength setting (Light, Normal, Strong).
- Speakers and TVs opens as a glass pop-out from the output button, and What's new is glass too.
- New installs start with the glass at 150 % and the frost at 10 %. Settings you've already changed are kept.

**Streaming.** While a streamed song plays, Riff now loads the whole song, so jumping ahead is instant and starting a song is no slower. Data saver keeps the old one-minute buffer. The playback cache no longer has a size limit: the oldest audio is removed only when the phone gets below 2 GB free.

**A tidier player menu.** The menu now shows About this song (credits included), Add to playlist, Share song, View album, View artist, Never play and Pin to speed dial. Sleep timer, Playback speed and the equalizer are grouped under **Playback tools**. The library has its own tab, so the menu no longer repeats it.

## Only in Riff+

- **Sing** removes the vocals from a song on the phone, with Deezer's Spleeter model, bundled and MIT-licensed. It works on downloads, files on the phone, and streamed songs once they are fully loaded. Settings → Downloads → Clear Sing instrumentals frees the space.
- **Blend with a friend.** Each phone shows a QR code of your 100 most-played songs, you scan each other's, and both get the same blend in Phone playlists. Nothing goes through a server.
- **Mixes from Encore** on Home: On repeat, Time of day, Heating up, New finds that stuck, Artist mixes, Your weekday, Season throwback and Genre mixes. They are made on the phone from your own listening.
- **DJ order** reorders the songs to come by tempo and key: Steady, Build up or Wind down. It previews first and can be undone. It works on songs downloaded or on the phone.
- **Most replayed.** A pill above the seek bar jumps to the part of a song people replay most.
- **Headphone profiles.** Search for your headphones among 8,827 AutoEq profiles, or import an AutoEq file, and fit it to the equalizer. Revert goes back to your own curve.
- **Key** shifts a song up or down by up to six semitones without changing its speed. It is on the Equalizer screen.
- **Visualizer.** Bars that follow Riff's own sound, never the microphone. It is off by default; turn it on in Appearance.
- **About this song.** The song's and artist's story from Wikipedia, fetched only when you open it.
- **Accent colours and launcher icons**: three styles in eight colours, with the app tinted to match.
- **Export a download as an M4A file**, tagged with its cover.

## Known limits

To be checked with the owner before publishing:

- **Not yet tried on a phone:** a real in-app update or edition switch, which needs published signed files.
- **Not yet measured:** the install over 0.21.0. On the tier R phone, Riff's cold start misses its 2 s budget by 12 ms (p95 2,012 ms), and scrolling misses its frame budget in both editions (13–16 % of frames late, against 2 %).
- **Riff+ is much larger than Riff**, because it bundles Sing's model, Automix's model and the headphone profiles.
- **DJ order and Sing only use audio already on the phone.** For streamed songs, Sing waits until Riff has loaded all of the song, and Data saver stops that loading.
- **Sing's instrumentals can grow large**, about 10 MB per minute of music. Clear them in Settings → Downloads.
- **The media notification is not tinted** with the accent colour. Android colours it from the cover.
- **Last.fm is offered only when the build has its API key.**
- The limits listed for [0.21.0](release-notes-0.21.0.md) still apply.

## Candidate

To be filled in after the signed build: file names `riff-0.30.0.apk` and `riff-plus-0.30.0.apk`, packages, sizes, SHA-256 and the source archive.

## Verifying a package

Check the files against `SHA256SUMS.txt`, and the APK's certificate with `apksigner verify --print-certs` against the value above. The source archive builds with the commands in the [README](../README.md).
