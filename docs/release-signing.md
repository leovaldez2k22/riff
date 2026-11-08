# Release signing

Every Riff release from **0.20.0** on is signed with one key, made on 30 October 2026. Android installs an update over an existing app **only if it has the same signing certificate**. If this key is lost, every installed copy has to be uninstalled before a new build will install, and uninstalling deletes downloads, local saves and settings. **There is no recovery from losing the key except a backup.**

## Two-edition candidates from 0.30.0

0.30.0 is unreleased. Both editions use the existing key described below, the same package `com.musicglass.personal`, version name and code. Release asset names are a contract with the update check: `riff-0.30.0.apk` and `riff-plus-0.30.0.apk`. Each needs its mapping and checksum. Publish both in one release only after the owner authorizes it; users of Automix or app EQ in 0.21.0 should choose Riff+.

`scripts/build-release-candidate.ps1` builds both releases, checks each compiled payload and pinned certificate, and compares their version/code/source identity. `-Preview` also produces `riff-0.30.0-preview.apk` and `riff-plus-0.30.0-preview.apk`, checked against their releases. Both previews share `com.musicglass.personal.preview`, so they replace each other and sit beside the release. Riff keeps its launcher icon; Plus starts with the A/Rose icon.

Use `-OutDir artifacts/quality/stage7-candidate -AllowDirty` for local development candidates. Dirty candidates are development evidence. Public release builds require a clean commit. One `riff-0.30.0-source.zip` serves both editions; `package-source.ps1` refuses missing or different APK fingerprint inputs, including newly moved flavor sources. Include new implementation files in Git before packaging. The packaging lane binds the signed APK to that source and tests the 0.21.0 → Plus → Riff → Plus path with the signed test-only probe. Installation and phone evidence are recorded separately from the build.

### Compiler connection recovery on this laptop

The four-variant local build encountered Kotlin daemon connection failures. A retry produced checked signed releases using one worker and in-process compilation. Set these only in the shell running that build:

```powershell
$env:GRADLE_OPTS = ($env:GRADLE_OPTS + ' -Dorg.gradle.workers.max=1 -Dorg.gradle.parallel=false').Trim()
[Environment]::SetEnvironmentVariable('ORG_GRADLE_PROJECT_kotlin.compiler.execution.strategy', 'in-process', 'Process')
```

These are standard [Kotlin execution strategy](https://kotlinlang.org/docs/compiler-execution-strategy.html) and [Gradle property](https://docs.gradle.org/current/userguide/build_environment.html) controls. They leave the source, compiler version and signing key unchanged. The split log records the original attempt and checked artifacts. Leave unrelated worktrees and shared services running.

A local SkipChecks invocation after a source-matched passing PR keeps its collect-only JVM/lint gates BLOCKED. Retain the passing PR and explicit signed-artifact verification as separate evidence; skipped checks are never reported as passed. Public candidates still follow the clean-commit and checked-build policy.

## A new key for 0.20.0 (30 October 2026)

Releases 0.9.0-rc4 to 0.10.0 were signed with the earlier key (certificate SHA-256 `ac70342323a39eb9cbd1fe80a9a5b060e22c01ba9a9102a9f698367bca0bf6e1`). When 0.20.0 was built, Windows could no longer decrypt that key's `password.dpapi` ("Key not valid for use in specified state", also outside the sandbox), though signing had worked on 24 October; something in this Windows account changed in between, such as a password reset. The owner had no backup to restore from and chose a new key. So **0.20.0 does not install over 0.10.0 or 0.9.0-rc20**: the old app has to be uninstalled first. Riff's backup in Documents/Riff Backup survives the uninstall and is offered after the new install; downloads are lost. The old keystore and its unreadable password file are kept, unused, in `.toolchain/signing-old-2026-10-24/`, in case the password turns up.

## The key

| | |
|---|---|
| File | `.toolchain/signing/musicglass-release.p12` (ignored by git) |
| Password file | `.toolchain/signing/password.dpapi` (ignored by git): 48 random bytes in Base64 under Windows DPAPI, readable only by this Windows user on this PC. The plaintext was deleted on 2026-10-30 after the backup was made and the DPAPI copy was checked against the keystore. |
| Keystore type | PKCS12 |
| Alias | `musicglass-release` |
| Algorithm | 3072-bit RSA, `SHA384withRSA` |
| Owner | `CN=Riff, OU=Personal Build` |
| Created | 2026-10-30 16:54:55 IST |
| Valid until | **2056-10-22 16:54:55 IST** |

The certificate digests below are public: anyone with the APK can read them. Committing them is safe.

```
Certificate SHA-256: e45ac43b9d1c658c03ace002abb4c482cc3d63214a5f8509c801e404c91ff798
Certificate SHA-1:   f85989a63e22673f20457593fc7e3f09caf4a929
```

`scripts/build-release-candidate.ps1` reads the `Certificate SHA-256:` line above and refuses to finish a release whose certificate is different.

To check an APK yourself:

```powershell
.toolchain\sdk\build-tools\36.0.0\apksigner.bat verify --print-certs <apk>
```

## Backups

The keystore and its password must always be backed up **together**. Neither is any use without the other.

Both copies are the same encrypted archive (7-Zip, or WinRAR (RAR5) when 7-Zip is not installed: AES-256 with encrypted file names), holding `musicglass-release.p12` and `password.txt`. Without the passphrase it is unreadable, so it can live in cloud storage. Two different providers mean one lost account doesn't lose the key.

| # | Where | Status |
|---|---|---|
| 1 | Google Drive, uploaded through the browser | ☑ 2026-10-30, the 0.20.0 key (the owner's word; the earlier key's ☑ 2026-10-03) |
| 2 | Gmail: an email to yourself with the archive attached | ☑ 2026-10-30, the 0.20.0 key (the owner's word; the earlier key's ☑ 2026-10-03) |
| — | **Archive passphrase:** in your password manager, plus written on paper kept at home. **Never** in the same place as the archive. | ☑ 2026-10-30, the 0.20.0 key (the owner's word) |

Make the archive and its restore drill with `scripts/backup-signing-key.ps1 -Destination $HOME\Documents\riff-key-backup`, upload that file to both places, then delete the local copy. A file left only on this PC is not a backup.

The passphrase is the one secret everything depends on. Use at least five random words or 20 random characters. If it is lost, both copies are useless.

**Never** store the key in git, unencrypted in any cloud folder, or in CI.

## Restore drill

Repeat after making a backup, and about once a year:

1. Run `scripts/backup-signing-key.ps1 -VerifyOnly -Archive <path to .7z>`. It extracts to a temporary folder, checks the keystore's certificate against the SHA-256 digest above, and deletes the temporary copy.
2. Record the date in the table below.

| Date | Backup | Result |
|---|---|---|
| 2026-10-30 | `riff-signing-key-20261030.rar` (WinRAR RAR5), made and checked by the script on the owner's terminal | Passed, as the owner reported; the archive then uploaded and the local copy removed |
| 2026-10-03 (earlier key) | `riff-signing-key-20261003.7z` (3,759 bytes, SHA-256 `8ebda4a8efb276bcd096450d9573c594563f05414556a688f85cabf939d96fac`), made by the script | Passed: extracted, keystore opened with its password, certificate matched; file names hidden without the passphrase |

## If the key is missing

The release script stops with `Signing key missing. Restore from backup (see docs/release-signing.md).` To restore:

1. Copy `musicglass-release.p12` and `password.txt` from a backup into `.toolchain/signing/`. The build reads `password.txt` when there is no `password.dpapi`. A new Windows install or user account can't read the old `password.dpapi`, so delete it there and use `password.txt`, then re-encrypt it (see the release script).
2. Run the restore drill command against that folder's files, or build. The release script checks the certificate.

Generating a new key is a last resort that breaks updates for every installed copy. The script only does it with `-NewSigningKey`, and only when no earlier signed release exists in `artifacts/release/`.
