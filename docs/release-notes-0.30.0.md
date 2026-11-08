# Riff 0.30.0 development notes

**Unreleased.** This records the implemented edition split while the 24-feature plan remains in progress. It is not a completed release announcement. The [implementation log](feature-implementation-plan-0.30.0-2026-11-07.md#7-implementation-log) records the remaining work and measured gates. The latest published release is 0.21.0.

Riff now builds as two free editions. Riff is the smaller everyday player; Riff+ adds Automix, the in-app equalizer and Stats for nerds. **If you use Automix or the equalizer in 0.21.0, choose Riff+ when 0.30.0 is released.** All of Encore, its stories, genres and widget, remains in both.

Both support Android 12 (API 31) and later and use version 0.30.0 / code 36, the same package and the existing release signing key. Installing one replaces the other. Riff keeps Plus settings and reads Automix as Smart; Riff+ reads the saved settings again when you switch back. About offers the other edition at the same version when the release feed lists its APK. The current action opens the release page.

The release asset names are `riff-0.30.0.apk` and `riff-plus-0.30.0.apk`; previews are separate from the installed release and replace each other. No 0.30.0 assets are published by this work. Candidate sizes, certificate checks and installation evidence belong to the [split log](riff-plus-split-plan-2026-11-07.md#implementation-log); final release notes follow whole-release verification.
