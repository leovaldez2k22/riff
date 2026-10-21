# Q19B overnight plan (21 to 22 October 2026)

The phone is handed over at 23:00 and stays with me until the plan is finished, even past 06:00. Bugs found are
logged with their C10, B13 and A9 checks and fixed afterwards, not during the night.

## Builds on the phone

- **Riff Preview** stays the signed rc6 (a9d3ab7a...ea9e), signed in to the dummy account. Every check that is
  about the candidate runs on it.
- **Riff Debug** gets a debug build of the current commit (rc6 plus the Q18-066 download fix), updated in place over
  rc6 Debug, whose data is already cleared. Downloads are refused on rc6 (Q18-066), so every check that needs a
  finished download runs on Riff Debug instead. The device lane (B1) uses the same build.
- **Riff Benchmark** and its runner are updated in place to rc6's fixture builds for B6.
- The app labelled "Riff" (rc20) is never touched.

## Order (rough times)

| Time | Step | What | Phone changes |
|---|---|---|---|
| 23:00 | Start | Settings recorded (done once already: airplane off, rotation off, stay-awake 15, media volume 15, Wi-Fi on); volume to 0; Documents folder listed | volume |
| 23:10 | B1 | Device lane on Riff Debug (every instrumented test, all of them now that Debug holds no account); then the three network-off tests again with airplane mode on | install Debug and its test APK; airplane mode about 10 min |
| 00:30 | B6 | Benchmark lane once (Q18-023: it runs or is BLOCKED after 15 silent minutes); by hand, 15 cold starts with and without the Baseline Profile; Home scroll (gfxinfo) with frosted glass at 20 % and off, at 120 Hz; `check_performance.py summary --tier R` | install Riff Benchmark rc6; airplane mode on (fixture build) |
| 02:30 | B3a | Fix checks on Riff Preview, Wi-Fi on: Q18-003 (Delicate word synced), Q18-011 and Q18-033 (screen-tree names), Q18-018 (Already in the queue), Q18-019 (saved time after force-stop), Q18-032 (widget Play/Pause names), Q18-051 (ten cold starts, five offline), Q18-058 (store page), Q18-022 (resolver unavailable), Q18-014 (three cold starts timed from the log, no recording); F1 (tap opens the player), F3 (Previous after 10 s) | airplane mode for Q18-051 |
| 04:00 | B3b | Network and download checks on Riff Debug: Q18-020 (session states through wait, pause and reconnect, airplane mode), Q18-021 (kill while downloading and while saving artwork and lyrics; then one reboot), Q18-035 (pause a download from its notification, then another), Q18-005 download half, Q18-066 (a download with about 10 GB free) | airplane mode; downloads (removed after); one reboot |
| 05:15 | B3c | Q18-034 translation: the model download with Wi-Fi off partway, Back while translating; Q18-031 (announcements while an album downloads, from accessibility events, no TalkBack); A11Y02 largest font and landscape on Home, player, queue, lyrics, Settings | translation model (about 30 MB); font size and rotation, put back |
| 06:15 | B7 | Live checks on the dummy account, reading only: search, Home, albums, a large playlist, canary stream phase, loudness, a real download (Riff Debug). No like, dislike, playlist edit or feedback | none |
| 07:30 | B11 | F7 pins with Home offline (airplane mode); F8 a fresh install's Settings reads frosted 20 % (Riff Debug, data cleared); F9 the backup on Riff Debug: Back up now, three files listed by name, uninstall and reinstall Riff Debug, the restore offer, the picker, data back, then the files removed with the backup's Off switch | airplane mode; uninstall and reinstall Riff Debug (no account in it) |
| 08:30 | B10 | Restore: settings and volume back, every file the night made removed and listed (downloads, Documents/Riff Backup (debug) files made tonight, translation model kept or removed as you say), test apps stopped, the two failed Preview downloads removed | |
| 09:00 | Report | Ledger entry, bug rows, case map; a short summary for you | |

If something takes longer, the later steps move later; nothing is skipped to save time.

## Not in the overnight plan, and why

| Item | Why | When |
|---|---|---|
| B7: Not interested / dislike (F10), likes, playlist edits | Each is asked first | Morning, with you |
| B8 battery (three 2-hour runs unplugged) | The phone has to be unplugged; adb then cannot reach it | A separate night, or BLOCKED |
| B9 4-hour live soak | You start the streaming | A separate night |
| B5 Q18-040 sign-in capture | It is a capture of the screen, which the phone rule forbids; your A2 sign-in covered the welcome row | Recorded as covered by A2 unless you want it |
| Q18-017 | Needs your VPN connected | Only if you use a VPN on this phone |
| Q18-001 screen recording | Screen capture; you checked it by eye in A1 | Done |
| F4 slider haptics, F2 and F6 by feel | Only you can feel them; F2 and F6 you checked in A3 | F4 with you |
| 60 Hz frames (Q18-023, Q18-030) | You set Standard refresh | If you set it before handing over |
| F9 on Riff Preview (uninstall deletes its data and the dummy account's sign-in) | Done on Riff Debug instead | After rc7, if you want it on Preview |
| Downloads on Riff Preview | Refused by Q18-066 on rc6 | rc7 |

## Approvals this plan needs (one yes covers the night)

1. Airplane mode on and off as listed, always back to Wi-Fi at the end.
2. Riff Debug, its test APK, Riff Benchmark and its runner updated in place; Riff Debug uninstalled and reinstalled
   once for F9 (it holds no account and no data of yours).
3. Song downloads on Riff Debug, removed afterwards.
4. One reboot.
5. The ML Kit translation model download (about 30 MB) in Riff Preview; kept or removed as you say.
6. Font size to largest and landscape, put back afterwards.
7. Reading-only live use of the dummy account in Riff Preview (searches and plays; the searches removed after).

Never: screenshots or screen recordings, the app labelled "Riff", your own account, likes, dislikes, playlist edits,
anything sent to YouTube beyond plays and searches, B8 or B9.
