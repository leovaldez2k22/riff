# Update notice and edition switch

Updated 8 November 2026 for feature 3, the in-app installer (unreleased 0.30.0; see "In-app install" below). The published 0.21.0 binary retains its existing notice and browser action.

## Feed and release contract

The owner enabled the public feed for 0.20.0 on 30 October 2026. Current configuration in gradle.properties:

~~~properties
riffUpdateFeed=https://api.github.com/repos/leovaldez2k22/riff/releases?per_page=10
riffUpdatePages=github.com
~~~

Before that activation the project had no public release feed. The older unavailable leovaldez2k20/leovaldez2k21 addresses are historical; do not configure them. An absent or invalid feed still disables the check entirely.

From 0.30.0 a release has both `riff-<version>.apk` and `riff-plus-<version>.apk`. Tags use the existing release-version parser; the expected APK name uses that canonical version. The parser reads the two exact names and positive numeric byte sizes from the existing assets array. It bounds the feed at 256 KiB, releases at 30 and assets at 32 per release. Unsupported names, invalid sizes, assets above 512 MiB and duplicate matching names cannot advertise that edition. Feature 3 also keeps an asset's `browser_download_url` when it is exactly `https://github.com/<owner>/<repo>/releases/download/<tag>/<name>` for that tag and name, and its `digest` when it is `sha256:` and 64 hex digits; anything else is dropped and the release page stays the way.

## Checks and notices

| Behavior | Contract |
|---|---|
| Automatic | On foreground, at most once a day, off the main thread. Metered data with Data saver holds it back. |
| Manual | About's Check for updates asks immediately when enabled, including on metered data. |
| Off | No new request, including manual checks. The pending notice and edition-switch row are hidden. |
| Request | Existing feed GET with Accept and User-Agent: Riff-update-check. No cookie, account, device, install, version or edition detail. Provider cookies are not used. |
| Eligibility | Non-draft, valid tag, allowed HTTPS release page and a unique exact asset for the installed edition. Prereleases count only for prerelease installs. |
| Result | Only a strictly newer eligible release produces a notice. Text names Riff or Riff+ as installed. Same/older releases produce no automatic notice. A local build is never called up to date; it can still hear about a newer release as before. |
| Missing edition | A release without this edition's asset is not an available update. The manual message says no release is available for this edition. |
| Failure | Existing offline, HTTP, redirect-to-cleartext, size and malformed-response protections remain. An attempt is recorded before asking; automatic failure retries wait a day. |
| Action | In a clean release build, when this edition's asset has a checked address, the notice says Update and About's row offers the in-app install. Otherwise View opens the release page through the HTTPS host check. |
| Diagnostics | Existing bounded update_check result/count or failure event, and update_install result/reason/kind. No asset URL, signed address, credentials or new personal fields. |

The saved date covers restarts; the monotonic clock prevents repeated automatic checks after wall-clock changes. A saved future date is due once. Notice acknowledgement remains once per version.

## About's edition switch

Below the update controls, Riff offers Get Riff+ and Riff+ offers Switch to Riff. The row describes the three current extras and displays the other APK's reported size. The down-switch explains data preservation and restoration of saved choices in Riff+; install-over evidence is a separate packaging/device gate.

The candidate must be the exact installed version and have the opposite edition's unique valid asset. A newer version in the same feed never substitutes for it. If every release in the bounded feed is newer than the installed one, a clean release build asks the same host once for `…/releases/tags/v<installed>`; a failure there only hides the row. If neither has the matching version, the row is hidden. Dirty, unknown, debug and preview builds do not offer the switch and make no tag lookup. The row appears only in About, with no price or repeated prompt.

The switch uses the in-app install below when the opposite asset has a checked address, and the allowed-HTTPS release page otherwise. Turning checks off hides the switch immediately and the click handler rechecks availability.

## In-app install (feature 3)

`UpdateInstaller.kt`, shared by both editions. Owner rules (Q3): edition and size before each download, a warning on metered data, a clean cancel and a manual retry, progress while it runs, no automatic install and no retry nobody asked for.

| Step | Contract |
|---|---|
| Ask | Update or the switch opens a dialog naming the edition, version and size, and a metered warning when the network is metered. Nothing is sent before Download. |
| Access | If Android's "Install unknown apps" access for Riff is off, Riff explains it and opens that setting. On return it reads the setting again: allowed continues, still off says so and offers the setting or Cancel. |
| Before sending | Offline fails at once. Free space must hold twice the asset plus 32 MiB (the staged copy and Android's session copy). |
| Transfer | GET of the checked github.com address with `User-Agent: Riff-update`, no cookies. Redirects are followed by hand, at most six hops, each HTTPS on port 443 without a user part, to `objects.githubusercontent.com`, `release-assets.githubusercontent.com` or `github-releases.githubusercontent.com`; anything else stops it. The body goes to `noBackupFilesDir/update-staging` and must be exactly the listed size; the SHA-256 must match when listed. Cancel stops the call and the coroutine and leaves no file. |
| Checks | Read from the APK itself: package `com.musicglass.personal`, the installed app's exact signer set, the `com.musicglass.personal.EDITION` manifest marker (`riff` or `plus`, which the signer cannot tell apart), and the version. An update must keep the edition and raise both the version and the version code; a switch must keep the exact version name and code and change the edition. Any mismatch fails before the installer and offers the release page. |
| Install | One PackageInstaller session (full install, user action required, install reason user), written and fsynced, then committed with Riff's own non-exported receiver. The staged file is deleted after commit or failure; no FileProvider or shared folder is involved. Android's confirmation screen is opened by the visible app. |
| Afterwards | Success, a decline and a refusal are reported; a decline or refusal offers Try again. Only the session id, kind, edition and version are kept while Android decides. At the next start a leftover session is abandoned unless the installed app is now what it asked for, and any staged file is removed. |

One request at a time: a second Update or switch waits while one is under way.

## Cache and edition changes

Availability stores the allowed page, version and bounded public APK names/sizes in private update-check preferences. On read, the current edition's exact asset must still be present; an old cache without asset proof is ignored. Switching editions re-evaluates the full asset list and never reuses an unchecked opposite-edition notice. Known metadata for a newly installed version can supply its same-version switch after an upgrade, without an extra request. A successful check refreshes or clears these derived caches; it does not rewrite saved edition-specific settings.

Runtime BuildIdentity.current uses BUILD_TYPE, so a clean runtime release is still identified as release. The archived APK evidence uses riffRelease/plusRelease. Do not confuse the two or weaken the local-build classifier.

## Verification status

The existing UpdateNoticeTest retains UPD01/UPD02, clock, failure, request-header and page-host coverage. Its synthetic releases now include both APK assets; the browser-source guard covers all three page-opening paths. New UpdateEditionTest cases cover exact asset/version selection, malformed and duplicate assets, size bounds, cached edition proof, upgrade reuse, unsafe cached pages, disabled checks and local identities. UpdateEditionUiTest checks About's opposite-edition label/size and hiding the row without another request.

Feature 3 replaced UpdateNoticeTest's "no code path downloads or installs" guard with one that keeps the feed reader free of install code and allows installing only in `UpdateInstaller.kt` through a PackageInstaller session, with the receiver unexported and the FileProvider unchanged. UpdateInstallerTest covers APK checks (package, signer, edition marker, downgrade, same-code update, switch version and edition), download addresses and digests, the transfer (allowed redirect, spoofed and cleartext redirects, a loop, short and long bodies, a wrong digest, 404 and network failure, no cookies), the flow (confirmation and metered warning, access denied and granted, offline and full storage, retry, cancel, a declined install) and start-up clean-up. UpdateEditionTest adds the tag lookup and install-target cases. A real install on a phone is not yet exercised.

Stage 6 passed `pr-20261107T090702Z` in both editions, all nine PR gates. Focused tests: 39 per edition; full tests: Riff 1594 / Riff+ 1629, zero failures/errors/skips. The implementation logs retain the artifact links. Live 0.30.0 assets, install-over and physical-device behavior remain unverified. No 0.30.0 release has been published by this work.
