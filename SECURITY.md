# Security policy

## Supported version

Reports should target the newest published release candidate and the current `main` branch. Older candidates may be superseded rather than patched individually. This personal project does not promise a response or patch deadline.

## Report privately

Use [GitHub private vulnerability reporting](https://github.com/leovaldez2k20/riff/security/advisories/new). Include the affected version, steps to reproduce, impact, and a minimal example without real account credentials.

Do not post a suspected security vulnerability, account cookie, signing key, password, or signed playback URL in a public issue. Ordinary playback/provider availability bugs belong in the public bug report form.

## Downloads and signing

Download APKs from [this repository's Releases](https://github.com/leovaldez2k20/riff/releases). Compare their SHA-256 hashes with the attached `SHA256SUMS.txt`. Existing releases use the certificate documented in [release signing](docs/release-signing.md). Checksums detect changed downloads; they do not replace checking the download's origin or Android's signature verification.

The signing key is kept outside Git and CI. CI does not produce signed distributable APKs. Android backup and device transfer are disabled for app data. For the app's provider access and local storage, see [privacy and permissions](docs/privacy.md).
