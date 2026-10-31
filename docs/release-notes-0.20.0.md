# Riff 0.20.0 (version code 34)

**Status: prepared, not published.** Built from commit b859a40, signed with the release key and checked locally. Nothing has been uploaded or announced, and publishing needs the owner's explicit decision.

Android 12 or later, 64-bit ARM phones. The APK is about 45 MB (0.10.0 was about 10 MB): most of the growth is the on-device beat model behind Automix and the runtime it needs.

## Uninstall the old Riff first

0.20.0 is signed with a **new certificate** (SHA-256 `e45ac43b9d1c658c03ace002abb4c482cc3d63214a5f8509c801e404c91ff798`). The earlier key could no longer be opened, so Android will refuse to install 0.20.0 over 0.9.0-rc20 or 0.10.0, saying the signatures don't match. The old app has to be uninstalled first, and uninstalling deletes what it kept on the phone.

- **Kept:** everything stored in your YouTube Music account: liked songs, playlists, subscriptions and YouTube's own history. Sign in again after installing.
- **Lost:** downloads, the queue, Riff's own play history and searches, speed dial pins and settings.
- **From 0.10.0** (a build that was prepared but never published, so few phones have it): **Backup on this phone** keeps a file in Documents/Riff Backup that survives the uninstall, and 0.20.0 offers to restore it on first start. Downloads are still lost. Before uninstalling, you can also use Settings → Library & privacy → Privacy and data → **Move to another phone** to export a file.
- **From 0.9.0-rc20** (the last build that was handed out): rc20 has no backup or export, so only the account data above carries over.

Later releases are signed with the new certificate and update 0.20.0 in place.

## What's new

0.10.0 was prepared but never published; [its notes](release-notes-0.10.0.md) list everything it added since 0.9.0-rc20, and all of it is in 0.20.0. On top of that:

**A new look.** Liquid Glass, written in Riff, on the shell and every control; Inter as Riff's type; a redesigned player; a dark fade at the screen's edges; calmer transitions; status bar icons that follow the cover; predictive back; a side rail and player pane on wide screens. Glass is at 125 % and frosted glass at 25 % by default. Every pop-out and dialog shares one look: neutral grey glass, large corners, rows led by their icon.

**Automix.** Choose Crossfade, Smart or Automix. Smart blends where songs wind down and, when both have a beat, on the downbeat. Automix adds an on-device beat model (Beat This!, run with ONNX Runtime) that finds each song's beat grid: blends start on phrase lines, the bass passes from one song to the other on a downbeat without dropping out, two singers are kept apart where synced lyrics are known, songs whose tempos can't meet echo out instead, and the level is held steady through the blend. The beat model runs only when Automix is chosen; Smart and the silence skip use a much lighter beat tracker.

**Encore.** Your listening statistics for the day, week, month, year and lifetime, with charts, stories and genres.

**Listening.** An equalizer; skipping the silence at a song's end; playback speed; a sleep timer by songs or by time; song quality per network; stats for nerds.

**Lyrics.** lrc.red as a source; lines other than the current one blurred and faded; dots for instrumental parts; romanized lyrics.

**Settings.** Redesigned around a first page of quick switches and a glass page per section. **Moods** (Party, Focus, Commute, Night, and your own) set several things at once.

**Home screen widgets.** Redesigned in glass, with the blurred cover behind each one. Wide, Player and Large have Like; Player and Large have Shuffle; Large shows the time and the next two songs; progress moves while playing. The Encore widget shows the last seven days.

**Elsewhere.** Android Auto; Cast to Chromecasts and Google speakers (this brings Google Play services); opening YouTube Music links; search suggestions and a top result; artist "See all"; a scrolling title; Undo for queue and playlist removals; Shuffle and Like on the lock screen; app shortcuts and a play/pause quick settings tile; a "What's new" sheet and first-run tips.

**Update check.** Riff asks this repository's GitHub releases once a day whether a newer version exists, and shows it in Settings → About → Check for updates. The request carries no account, device or version details, and Riff never downloads or installs anything itself; turning the check off sends nothing ([privacy](privacy.md)).

**Fixed.** Radio stations start again (a tap seemed to do nothing). Songs play again when signed out (see the limits below). Google's sign-in page no longer hides the password being typed behind dark boxes. A download cut short is marked for downloading again when you skip into its missing part.

The full list is in the [changelog](../CHANGELOG.md).

## Known limits

Checked on the owner's phone (vivo I2217, Android 16): the device suite (64 of 64, offline), the release build by hand ("smooth, no lag"), and the emulator suite on Android 12, 13 and 16 (each run had at most one timing-related test failure, which passed when rerun).

- **Sign in for reliable playback.** Signed out, YouTube now refuses some streams partway through. On the owner's phone, 5 of 6 test songs played signed out; one stopped about 20 seconds in. Signed in, songs play normally.
- **Cast and Android Auto are untested on real hardware.** Cast was tried on an emulator (the picker opens, no speakers were found); Android Auto has unit and device tests only. A song that reaches Riff over SABR (most often when signed out) can't be cast.
- **Starting up and scrolling are slower than Riff's targets.** In the benchmark lane, a cold start took 2.1 s at the 95th percentile (target 2.0 s), and scrolling missed 13 to 16 % of frames at 60 Hz (target 2 %). Player and queue actions, a 2,000-song queue and first audio are all within their targets.
- The bitrate depends on which YouTube client answers; no TalkBack walk-through was done; the pinned YouTube library may download a pre-processed YouTube player from jsDelivr and run it in the stream helper ([privacy](privacy.md)). These are unchanged from 0.10.0.

## Verifying a package

Check the files against `SHA256SUMS.txt`, and the APK's certificate with `apksigner verify --print-certs` against the value above. The source archive builds with the commands in the [README](../README.md).
