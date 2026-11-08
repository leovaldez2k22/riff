# What can be deleted (6 November 2026)

A list for the owner, made after 0.21.0 was published. Nothing on it has been deleted. Sizes are on disk; the laptop's C: drive has 110 GB free of 475 GB.

Three groups: **safe** (rebuilt automatically, or old copies of things kept elsewhere), **your call** (deleting loses something you might want), and **keep**.

## Laptop: safe to delete (about 6.5 GB)

| What | Size | Why it's safe |
|---|---|---|
| `artifacts/quality/lanes/` except the newest few | most of 3.4 GB | 85 old test runs. Keep `pr-20261106T021458Z` (the 0.21.0 check) if you want a record. |
| `artifacts/idle-baseline-20261101-filetrace`, `-warn`, `T091433Z` | 506 MB | The idle-playback investigation; its fix shipped in 0.21.0. |
| `artifacts/idle-forensics-20261101/` | 798 MB | The same investigation. It holds a git worktree, so remove that first: `git worktree remove --force "artifacts/idle-forensics-20261101/verify"`. |
| `artifacts/device-checks/` | 474 MB | Phone captures from September and October checks. |
| `app/build`, `build`, `.gradle`, `.kotlin` | 1.3 GB | Build output; Gradle makes it again on the next build. |
| In `artifacts/release/`: `riff-0.9.0-rc16.apk`, `riff-0.9.0-rc20.apk`, `-mapping.txt`, `-source.zip` | 249 MB | Old candidates, signed with the old key; nobody can update to them. |
| In `artifacts/release/`: `riff-0.20.0-preview.apk`, `riff-0.21.0-preview.apk` | 94 MB | Riff Preview is uninstalled, and both are made again by the release script. |
| `artifacts/github-author-cleanup-20261004`, `artifacts/github-credit-replacement-20261004` | 62 MB | One-off GitHub author fixes from 4 October, finished. |
| `artifacts/phone-cleanup`, `artifacts/morning-summary-2026-10-30.md` | under 1 MB | Finished one-offs. |
| `C:\Volume D\rc20p` | about 1 GB | The temporary copy used to build the rc20 Riff Preview; the APK is saved in `artifacts/release/0.9.0-rc20-preview`. Delete it once the build's Java processes have exited (or after a restart). |
| `C:\Users\KIIT0001\AppData\Local\Temp\claude` | 3.7 GB | Scratch files of Claude sessions. Safe once no session is running. |

## Laptop: your call

| What | Size | What you'd lose |
|---|---|---|
| `artifacts/evidence-archive-2026-10-25.zip` | 10 GB | An archive of test evidence up to 25 October. Nothing in the app needs it. |
| `.toolchain/avd` and `.toolchain/sdk/system-images` | 16 GB + 17 GB | The three test emulators (Android 12, 13 and 16). The emulator lanes need them; downloading them again takes a while. |
| `artifacts/desktop` and `.toolchain/nvda`, `desktop-native`, `presentmon`, `desktop-codec-tests` | 4.0 GB + 1.1 GB | The Windows port's test records and tools. Keep them while that work goes on. |
| `artifacts/automix` and `.toolchain/beat-this` | 709 MB + 887 MB | The 50-song test set's decoded windows and the PC reference beat model. Needed to measure Automix stage 3; the songs can be decoded again from your folder of MP3s. |
| `C:\Users\KIIT0001\.gradle` | 2.2 GB | Gradle's cache for other projects. Riff uses its own in `.toolchain/gradle-user-home`. |
| `C:\Users\KIIT0001\AppData\Local\Android\Sdk` | 1.7 GB | A second Android SDK, probably Android Studio's. Riff uses `.toolchain/sdk`. |
| `C:\Users\KIIT0001\.m2` | 204 MB | Maven's cache; not used by Riff. |
| `artifacts/release/riff-0.20.0-mapping.txt` | 98 MB | Needed only to read a crash report from 0.20.0. |

## Laptop: keep

- `.toolchain/signing` and `.toolchain/signing-old-2026-10-24`: the release key (every update must be signed with it) and the old one. Never delete these.
- `.toolchain/jdk-…`, `.toolchain/sdk` (except the system images above), `.toolchain/gradle-8.13`, `.toolchain/gradle-user-home` (8.8 GB): offline release builds need them.
- `artifacts/release/riff-0.20.0.apk`, `riff-0.21.0.apk`, `-source.zip`, `-mapping.txt`, `SHA256SUMS*.txt`, `dependencies.txt`: the published releases and what reads their crash reports.
- `artifacts/phone-backup` (53 MB): your phone data backup.
- `desktop`, `desktop-data`, `native`, `output`, `reports`, `research_notes`: the uncommitted Windows port work.

## Git

- **Merged branches on GitHub** (all already in `main`): `claude/youthful-wright-c57334`, `codex/downloads-crossfade-improvements`, `codex/fix-audio-continuity`, `codex/rc20-lyrics-playback`, `codex/riff-quality-m0`, `feat/rc18-prep`, `fix/overnight-rc18`. Deleting them on GitHub is a push.
- **Local branch** `codex/riff-quality-m0`: merged. Local `main` and `feat/next-features` are 5 commits behind GitHub; update them rather than delete them.
- **Old remotes** `old-2k21` and `previous-account` point to your earlier GitHub accounts. Removing them here (`git remote remove …`) changes nothing on GitHub.
- Tags stay: they mark what each release was built from.

## Phone

- `Documents/Riff Backup (debug)` (9 files) and `Documents/Riff Backup (preview)` (2 files): backups that Riff Debug and Riff Preview wrote. Uninstalling the apps doesn't remove them. Delete them in the Files app. Keep `Documents/Riff Backup`: it is your Riff's own.
- `/data/local/tmp/riff-*.dex` and `riff-*.xml`: four small helper files left by test scripts. Only adb can reach them: `adb shell rm /data/local/tmp/riff-*`.
- Leave `/data/local/tmp/shizuku*`: they belong to the Shizuku app.
