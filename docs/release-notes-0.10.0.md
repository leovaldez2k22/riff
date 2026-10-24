# Riff 0.10.0 (version code 33)

**Status: final candidate, not published.** Built from the final commit of the excellence plan, signed with the release key and checked locally. Nothing has been uploaded or announced, and publishing needs the owner's explicit decision. Until then there is no download link.

Android 12 or later, 64-bit ARM phones. Signed with the same certificate as every Riff release since 0.9.0-rc4 (SHA-256 `ac70342323a39eb9cbd1fe80a9a5b060e22c01ba9a9102a9f698367bca0bf6e1`), so it installs over 0.9.0-rc20 and keeps its data. Never uninstall to get past an install error: uninstalling deletes downloads, saves and settings.

This replaces the [0.10.0-rc1 notes](release-notes-0.10.0-rc1.md); everything listed there is in 0.10.0 too, except lyrics translation, which was removed.

## What is new since 0.9.0-rc20

**Your data, per account.** Each YouTube Music account, signed-out listening and the audio on the phone keep their own queue, history, searches, saved tracks and downloads. Switching accounts saves and stops the outgoing queue and restores the other account's queue paused. A sign-in that cannot be confirmed is shown as unverified.

**A new sign-in.** Riff's own frosted screens around Google's unchanged page: Welcome back with the accounts used on this phone, Checking your account, and You're in with your liked songs and playlists counted. Picking another saved account switches to it directly when Google still holds it in the session, and otherwise opens Google's own account page; Riff never commits an account other than the one you picked. Sign out asks first.

**Privacy and data.** Settings → Library & privacy → Privacy and data: stop saving history, keep plays for 30, 90 or 365 days or until cleared, clear history or searches, delete downloads, or delete everything Riff keeps for the account in use; every action asks first. **Move to another phone** exports to and imports from one file. **Backup on this phone** (on by default) keeps one unencrypted file per account in Documents/Riff Backup, offered for restoring after a reinstall. **Earlier data** holds what older builds shared between accounts until you import or delete it.

**Speed dial pins.** Pin songs, episodes, podcasts, albums, playlists and artists to Home's speed dial (up to 27); pins come first, carry a badge, and travel in the export file and the backup.

**Share lyrics.** Hold a lyric line, pick up to five lines, and save or share them as a picture in one of four looks.

**Playback.** Normalize volume (off by default); **Song quality** High, Medium (default) or Low, with Data saver taking priority while on; Previous restarts a song past 10 seconds; tapping a song opens the full player; the clock counts hours. The next song's stream is looked up while one plays, streams keep their connection across seeks, and a song waiting for a connection shows Pause. A crash in the stream helper no longer stops the music; after an app crash the queue returns paused; playback resumes when the network returns.

**Queue and library.** Scrolling to the end of a song's queue loads more recommendations, up to 200. **New playlist** makes a real private YouTube Music playlist; playlists the account can edit offer Rename and Delete. A collection shows its song count and length. **Save on this phone** is separate from the like and can be undone. **Not interested** also tells YouTube Music: its own Not interested where offered, otherwise a dislike, which Undo removes.

**Lyrics.** Sources are asked together and the best-timed answer wins. Overlapping lines light together; background vocals have their own smaller line; held words grow, rise and glow pink; punctuation lights with its word; credit lines are left out.

**Look and feel.** Frosted glass by default at 20 %, bending at the rim. Settings in compartments that open one at a time. Graded haptics on the boost, crossfade and glass sliders. Artwork colours and fades as in 0.9.0-rc20 (the darker title blocks of 0.10.0-rc1 are gone).

**Downloads.** Seeking in downloaded songs is fast (Riff's own WebM, existing downloads stored again); downloads ask YouTube Music's own client first; a download left waiting starts again when Riff comes to the front; the space kept free on the phone is at most 2 GB, so a large phone with plenty free still downloads; Remove download in every song menu.

**Diagnostics.** Settings → About → Diagnostics shows how Riff last closed and the errors it recorded, and shares a cleaned report only when you ask.

**Security.** Other apps on the media session cannot edit the queue; sign-in stays on Google's own hosts; another sign-in's cookies are never sent under the committed account; the diagnostic log keeps only names and numbers.

The full list is in the [changelog](../CHANGELOG.md).

## After updating from rc20

- Plays from YouTube Music recorded before this version are not shown in History until you import them from **Earlier data**, because the old list was shared by every account on the phone. Plays of on-device audio moved automatically.
- The queue saved by rc20 is offered for restoring in Earlier data instead of loading by itself.
- Lyrics cached by rc20 are fetched once more; downloaded songs are stored again once, in Riff's own WebM, for fast seeking.
- The backup starts writing to Documents/Riff Backup; turn it off in Privacy and data if you do not want it.

## Known limits

Checked on the owner's phone (vivo I2217, Android 16): the device suite (63 of 63), the start and first-audio budgets, memory over 30 journeys, and every fixed bug the owner kept in scope. Left as they are, by the owner's decision of 24 October 2026:

- A song tapped within about 3 s of a cold start can take several seconds to sound (Q18-098, Q18-109); streamed songs take about 3 s from tap to sound (Q18-015).
- Scrolling misses the 2 % frame budget at 60 Hz (Home 8 %, a collection 7 %, player and queue 17 % in the benchmark lane; Q18-030).
- The bitrate depends on which YouTube client answers (Q18-089); compressed copies are dropped only when YouTube marks them (Q18-091); the booster works on 16-bit samples (Q18-092).
- No TalkBack walk-through was done, so the download-phase announcements (Q18-031) and the screen-reader actions are tested in code only.
- On a pale or bright cover, pink and grey words on the artwork colour fall below 4.5:1 contrast: the owner chose rc20's colours ([accessibility](quality/accessibility.md)).
- The pinned YouTube library may download a pre-processed YouTube player from jsDelivr and run it in the stream helper ([privacy](privacy.md)).
- The update check is built in but inactive: no release feed exists.

## Verifying a package

Check the files against `SHA256SUMS.txt`, and the APK's certificate with `apksigner verify --print-certs` against the value above. The source archive builds with the commands in the [README](../README.md).
