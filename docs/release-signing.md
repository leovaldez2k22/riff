# Release signing

Every Riff release since 0.9.0-rc4 (when the app was still called YouTube Music Glass) is signed with one key. Android installs an update over an existing app **only if it has the same signing certificate**. If this key is lost, every installed copy has to be uninstalled before a new build will install, and uninstalling deletes downloads, local saves and settings. **There is no recovery from losing the key except a backup.**

## The key

| | |
|---|---|
| File | `.toolchain/signing/musicglass-release.p12` (ignored by git) |
| Password file | `.toolchain/signing/password.dpapi` (ignored by git): Windows DPAPI, readable only by this Windows user on this PC. The plaintext `password.txt` was deleted on 2026-10-03 after the backups were made. |
| Keystore type | PKCS12 |
| Alias | `musicglass-release` |
| Algorithm | 3072-bit RSA, `SHA384withRSA` |
| Owner | `CN=Youtube Music Glass, OU=Personal Build` |
| Created | 2026-09-29 15:08:59 IST |
| Valid until | **2036-09-26 15:08:59 IST** |

The certificate digests below are public: anyone with the APK can read them. Committing them is safe.

```
Certificate SHA-256: ac70342323a39eb9cbd1fe80a9a5b060e22c01ba9a9102a9f698367bca0bf6e1
Certificate SHA-1:   46cdc7dddcae0ea5d2ffe6ba60d54b6e87b28229
```

Checked on 2026-10-03: the keystore on this PC and the APKs `riff-0.9.0-rc16.apk` and `youtube-music-glass-0.9.0-rc9.apk` all show the SHA-256 digest above. Checked again on 2026-10-15 (Q17): `riff-0.9.0-rc20.apk`, the last distributed APK, shows the same digest.

`scripts/build-release-candidate.ps1` reads the `Certificate SHA-256:` line above and refuses to finish a release whose certificate is different.

To check an APK yourself:

```powershell
.toolchain\sdk\build-tools\36.0.0\apksigner.bat verify --print-certs <apk>
```

## Backups

The keystore and its password must always be backed up **together**. Neither is any use without the other.

Both copies are the same encrypted archive (7-Zip AES-256 with encrypted file names), holding `musicglass-release.p12` and `password.txt`. Without the passphrase it is unreadable, so it can live in cloud storage. Two different providers mean one lost account doesn't lose the key.

| # | Where | Status |
|---|---|---|
| 1 | Google Drive, uploaded through the browser | ☑ 2026-10-03 |
| 2 | Gmail: an email to yourself with the archive attached | ☑ 2026-10-03 |
| — | **Archive passphrase:** in your password manager, plus written on paper kept at home. **Never** in the same place as the archive. | ☑ 2026-10-03 |

Make the archive and its restore drill with `scripts/backup-signing-key.ps1 -Destination $HOME\Documents\riff-key-backup`, upload that file to both places, then delete the local copy. A file left only on this PC is not a backup.

The passphrase is the one secret everything depends on. Use at least five random words or 20 random characters. If it is lost, both copies are useless.

**Never** store the key in git, unencrypted in any cloud folder, or in CI.

## Restore drill

Repeat after making a backup, and about once a year:

1. Run `scripts/backup-signing-key.ps1 -VerifyOnly -Archive <path to .7z>`. It extracts to a temporary folder, checks the keystore's certificate against the SHA-256 digest above, and deletes the temporary copy.
2. Record the date in the table below.

| Date | Backup | Result |
|---|---|---|
| 2026-10-03 | `riff-signing-key-20261003.7z` (3,759 bytes, SHA-256 `8ebda4a8efb276bcd096450d9573c594563f05414556a688f85cabf939d96fac`), made by the script | Passed: extracted, keystore opened with its password, certificate matched; file names hidden without the passphrase |

## If the key is missing

The release script stops with `Signing key missing. Restore from backup (see docs/release-signing.md).` To restore:

1. Copy `musicglass-release.p12` and `password.txt` from a backup into `.toolchain/signing/`. The build reads `password.txt` when there is no `password.dpapi`. A new Windows install or user account can't read the old `password.dpapi`, so delete it there and use `password.txt`, then re-encrypt it (see the release script).
2. Run the restore drill command against that folder's files, or build. The release script checks the certificate.

Generating a new key is a last resort that breaks updates for every installed copy. The script only does it with `-NewSigningKey`, and only when no earlier signed release exists in `artifacts/release/`.
