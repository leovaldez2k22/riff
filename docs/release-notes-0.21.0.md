# Riff 0.21.0 (version code 35)

**Status: prepared, not published.** Signed with the release key and checked locally. Nothing has been uploaded or announced, and publishing needs the owner's explicit decision.

Android 12 or later, 64-bit ARM phones. Signed with the same certificate as 0.20.0 (SHA-256 `e45ac43b9d1c658c03ace002abb4c482cc3d63214a5f8509c801e404c91ff798`), so it **updates 0.20.0 in place**: downloads, the queue, history and settings are kept.

## What's new

**The queue.** Shuffle mixes the queue once, with the chosen song first, so the list shows the real play order. The queue's infinity button is now **Reshuffle**: the song playing carries on and everything else is mixed again, songs already played included. **Add to queue** puts a song next, after any songs added before it, shuffled or not; **Play next** is gone, since this does its job. Dragging a song works the same in both directions, including up to the very top of a scrolled queue.

**Faster starts.** Songs start in about 2 s from an empty cache (6 to 12 s in 0.20.0) and play without the pauses some had. A song whose start is on the phone plays from there at once. When you open Riff, the song you left paused is readied, so Play starts in about 0.3 s instead of up to 1.4 s.

**The player.** On a white or off-white cover, a soft gradient darkens what the art fades into, so the title and buttons read. Other covers are unchanged. The open queue darkens on any very bright cover.

**Fixed.** An album's songs play as the album's songs, not their music video's audio with its intro. A Play key can bring Riff back after Android has closed it. Playback recovers after a long idle. Encore shows artist photos, lists an artist credited in several ways once, and its pill sits where it should. A song with a guest ("feat.") gets its own lyrics, and QQ's heading no longer shows as a lyric. An "All" search puts the song matching every word first. The sleep timer lists minutes first.

The full list is in the [changelog](../CHANGELOG.md).

## Known limits

Checked on the owner's phone (vivo I2217, Android 16), on Debug builds, mostly by script: the queue changes, the drag in both directions, the cover darkening, and the start timings. Records: [owner's bug batch](quality/owner-bug-batch-2026-11-05.md) and [playback start](quality/playback-startup-warm-resume-2026-11-04.md).

- **Opening Riff can use a little data.** If the paused song is only partly on the phone, Riff fetches what the player buffers ahead while readying it, an estimated 1 to 2 MB once per song. The media notification also appears as Riff opens.
- **Search ranking on one account.** On one of the owner's accounts, YouTube's signed-in answer to a Songs search left the original songs out (seen for "electric love" and "slipping through my fingers"). The owner's main account gets them, and Riff shows what YouTube sends.
- **Not yet tested on the new stream library:** signed-out playback, SABR and age-restricted songs.
- The limits listed for [0.20.0](release-notes-0.20.0.md) still apply.

## Verifying a package

Check the files against `SHA256SUMS.txt`, and the APK's certificate with `apksigner verify --print-certs` against the value above. The source archive builds with the commands in the [README](../README.md).
