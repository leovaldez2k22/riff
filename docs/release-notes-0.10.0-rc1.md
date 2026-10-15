# Riff 0.10.0-rc1 (version code 22)

**Status: prepared, not published.** A signed candidate APK and its source archive can be built and checked locally. Nothing has been uploaded or announced, and publishing needs the owner's explicit decision. Until then there is no download link.

Android 12 or later, 64-bit ARM phones. Signed with the same certificate as every Riff release since 0.9.0-rc4 (SHA-256 `ac70342323a39eb9cbd1fe80a9a5b060e22c01ba9a9102a9f698367bca0bf6e1`), so it installs over 0.9.0-rc20 and keeps its data. Never uninstall to get past an install error: uninstalling deletes downloads, saves and settings.

## What is new since 0.9.0-rc20

**Your data, per account.** Each YouTube Music account, signed-out listening and the audio on the phone now keep their own queue, history, searches, saved tracks and downloads. Switching accounts saves and stops the outgoing queue and restores the other account's queue paused. A sign-in that cannot be confirmed is shown as unverified instead of being treated as signed out.

**Privacy and data.** Settings → Privacy and data: stop saving history, keep plays for 30, 90 or 365 days or until cleared, clear an account's or the phone's history, clear searches, delete downloads, or delete everything Riff keeps for the account in use. **Move to another phone** exports to and imports from one file you place yourself. **Earlier data** holds what older builds shared between accounts until you import or delete it.

**Lyrics.** Overlapping lines light together; background vocals have their own smaller line; held words grow, rise and glow pink; each letter pops up 1 dp as it is sung and the letters of a held word shimmer slightly. With Android's animations off, nothing moves.

**Playback.** Normalize volume (off by default). A crash in the stream helper no longer stops the music; after an app crash the queue returns paused with a one-time note; playback resumes by itself when the network returns. Recovery, crossfade and queue edits were reworked to keep the queue and refuse stale requests.

**Downloads.** Song menus offer **Remove download** in pink for a downloaded song, everywhere. Downloads restart safely, are checked before playing and are marked damaged only on proof.

**Diagnostics.** Settings → About → Diagnostics shows how Riff last closed and the errors it recorded, and shares a cleaned report only when you ask.

**Accessibility.** Screen-reader actions for every queue row, 48 dp touch areas, darker scrims over bright artwork, and layouts checked at large text sizes.

**Security.** Other apps connected to the media session can no longer edit the queue; photo links open only HTTPS pages; the diagnostic log keeps only names and numbers.

The full list is in the [changelog](../CHANGELOG.md).

## After updating from rc20

- Plays from YouTube Music recorded before this version are not shown in History until you import them from **Settings → Privacy and data → Earlier data**, because the old list was shared by every account on the phone. Plays of on-device audio moved automatically.
- The queue saved by rc20 is offered for restoring in Earlier data instead of loading by itself.
- Lyrics cached by rc20 are fetched once more.

## Known limits

- Not yet tested on a phone: this candidate has been checked with JVM tests, an emulator (API 31, network off) and release-content checks only. A full pass on a physical phone, including accessibility walk-throughs and performance budgets, is still to be done.
- No performance budget has been measured on a phone; the start-up logo is expected to miss the cold-start budget.
- The pinned YouTube library may download a pre-processed YouTube player from jsDelivr and run it in the stream helper ([privacy](privacy.md), [security review](quality/security-review.md)).
- Lyrics translation uses Google ML Kit, which is under Google's own terms, not an open-source license ([inventory](dependency-license-inventory.md)).
- The update check is built in but inactive: no release feed exists.

## Verifying a package

A candidate package holds the APK, the source archive, `SHA256SUMS`, build metadata and a validation summary. Check the files against `SHA256SUMS`, and the APK's certificate with `apksigner verify --print-certs` against the value above. The source archive builds with the commands in the [README](../README.md).
