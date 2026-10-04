# Riff 0.9.0-rc20

Release candidate published 4 October 2026. Standard ARM64 build for Android 12+.

## Download and install

Download **riff-0.9.0-rc20.apk** from Assets below and open it on your phone. Allow installation from your browser or file manager if Android asks. Existing same-certificate Riff installations update in place; uninstalling deletes app-private data. Older Lite installations use a separate package and are not updated by this standard APK.

- **Android:** 12+ (API 31+)
- **Architecture:** arm64-v8a, 64-bit ARM phones
- **APK size:** about 26.2 MiB
- **Version code:** 21
- **Application ID:** com.musicglass.personal

## Changes

- **Playback recovery:** rejected streams are resolved and replaced, with shared prefetched stream invalidation after attestation refresh. HTTP 403/410 gets a fresh stream; repeated SABR failures try bounded alternate profiles. Recovery preserves queue order, position, and a user pause or song change.
- **Queue removal:** visible remove buttons on History and Up next rows. Removing the final song clears the queue and pauses playback.
- **Data saver:** Settings → Data usage → Data saver requests lower-bitrate audio and up to 360p video for newly resolved streams. The setting persists across restarts; current streams and download quality stay unchanged.
- **Lyrics:** NetEase Cloud Music and Lyrics.ovh join the fallback chain. Lower-quality results continue searching for better timing. Cached plain lyrics display immediately while synced providers retry.

Lyrics order: **Better Lyrics TTML → QQ → NetEase Cloud Music → Kugou → LRCLIB → YouTube Music → Lyrics.ovh**. Word timing wins over line timing, then plain text. Provider order breaks ties.

## Validation

- 253 unit/Compose tests passed with no failures or skips.
- Eight phone tests passed: six UI regressions, live Lyrics.ovh, and NetEase verification/fallback behavior.
- Twelve distinct songs played in one phone session, including six with Data saver enabled.
- Six-song playback and word-synced lyrics passed on the signed release.
- Queue removal and Data saver persistence checked on the signed release.
- A real SABR attestation failure recovered automatically by refreshing attestation and switching stream profile.
- Release lint: zero errors; 90 existing warnings and five hints remain.
- GitHub CI passed for the app-code commit [8408e18](https://github.com/leovaldez2k20/riff/actions/runs/37199936181).
- Original signing certificate verified; 233 resolved dependency licenses audited.

The release tag includes documentation and download-page improvements on top of the tested app-code commit. See the [verification record](https://github.com/leovaldez2k20/riff/blob/v0.9.0-rc20/docs/app-revamp-rc20.md).

## Known limits

NetEase returned anonymous-client verification code -462 on the connected test phone, so matched live NetEase lyrics were not verified there. Riff treats the response as a provider failure and automatically continues; fixture parsing/matching and challenge fallback passed. Lyrics.ovh supplies plain text. Megalobiz and Spicy Lyrics are excluded from this release. Online content and playback availability remain provider-dependent. This release does not include a new Lite APK.

## Assets, source, and authenticity

- **riff-0.9.0-rc20.apk:** signed installable app.
- **riff-0.9.0-rc20-source.zip:** corresponding source, Gradle project, scripts, documentation, licenses, and third-party inventory.
- **SHA256SUMS.txt:** SHA-256 checksums of the APK and source archive.
- **LICENSE / NOTICE.md:** GPL v3 license and third-party notices, also included in the source archive.

APK SHA-256: `0963425347f5abeca63711f665edc566c9eae3a510e43ab4a9e91e253ca2491e`

Release certificate SHA-256: `ac70342323a39eb9cbd1fe80a9a5b060e22c01ba9a9102a9f698367bca0bf6e1`

Compare downloaded files with SHA256SUMS.txt. On Windows: `Get-FileHash .\riff-0.9.0-rc20.apk -Algorithm SHA256`. Download only from this repository's Releases. GitHub's automatic source archives also refer to this tag.

## Support and privacy

Report ordinary bugs through [GitHub Issues](https://github.com/leovaldez2k20/riff/issues/new/choose). Include version, phone/Android details, reproduction steps, and sanitized diagnostics from Settings → About → Copy playback diagnostics. Report security concerns [privately](https://github.com/leovaldez2k20/riff/security/advisories/new).

See [privacy and permissions](https://github.com/leovaldez2k20/riff/blob/v0.9.0-rc20/docs/privacy.md), [contribution guidance](https://github.com/leovaldez2k20/riff/blob/v0.9.0-rc20/CONTRIBUTING.md), and [CHANGELOG](https://github.com/leovaldez2k20/riff/blob/v0.9.0-rc20/CHANGELOG.md). Riff is a personal independent project, not affiliated with Google or YouTube. No support deadline is guaranteed. App-private local saves and downloads remain on this installation; uninstalling removes them.
