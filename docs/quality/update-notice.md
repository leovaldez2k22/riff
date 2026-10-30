# Update notice (Q12A)

Riff is installed from a file, so no store tells its users that a fix exists. The update notice asks the public release feed, once a day while the app is open, for the newest version, and says so when it is newer than the installed one.

## Where releases are published

The plan asks for the feed releases are actually published to. As of 14 October 2026 there is none:

- README: "No APK release has been published from this new repository yet."
- The repository named in the README and CI badge, `github.com/leovaldez2k21/riff`, and the earlier `github.com/leovaldez2k20/riff` both answer 404 to an unauthenticated request (the page and `api.github.com/repos/.../releases`). Neither is public.
- `scripts/build-release-candidate.ps1` and `scripts/package-source.ps1` build and package; neither publishes.

So no address is built in. The build reads it from a Gradle property, and without one the check does not exist in the app: no setting is shown and no request is ever made. Inventing an endpoint was ruled out by the plan.

**To turn it on** once releases are published on GitHub and the repository is public, put these in `gradle.properties` (so the source digest covers them) and rebuild:

~~~properties
riffUpdateFeed=https://api.github.com/repos/leovaldez2k21/riff/releases?per_page=10
riffUpdatePages=github.com
~~~

`riffUpdatePages` is the comma-separated list of hosts a release page may be on. Then check UPD01 against the real feed: a release tagged one version above the installed build must produce the notice, and the About row must open its page.

**Turned on for 0.20.0 (30 October 2026):** the repository `github.com/leovaldez2k22/riff` is public, the owner chose to publish 0.20.0 there and to have Riff check it, so `gradle.properties` now holds `riffUpdateFeed=https://api.github.com/repos/leovaldez2k22/riff/releases?per_page=10` and `riffUpdatePages=github.com`.

## What it does

| | |
|---|---|
| When | When the app comes to the front, at most once a day, off the main thread. Check for updates now in Settings, About asks immediately. |
| Held back | The automatic check on a metered network while Data saver is on. The manual check is not held back. |
| Setting | Settings, About, Check for updates. On by default. Off means no request at all, the manual check included (the button is hidden). |
| Request | One GET of the feed address, exactly as built in, with `Accept` and `User-Agent: Riff-update-check` and nothing else: no cookie, account, device, install or version detail. The provider HTTP client and its cookies are not used. |
| Answer | GitHub's release list or single latest release, at most 256 KiB, parsed strictly. An entry counts only when it is not a draft, its tag is a version (`v0.11.0`, `v0.11.0-rc2`) and its page is an HTTPS address on an allowed host (the Q15 `httpsAddressOn` check). A prerelease counts only for a prerelease install. 404 means no release has been published. |
| Result | A strictly newer release: one notice in the app with View and a close button, once per version, and the About row reads "Riff 0.11.0 is available" and opens the release page. Same or older: nothing automatic; the manual check says Riff is up to date. A debug, modified or unknown-source build is never called up to date: it says it is a local build and names the newest release. |
| Failure | Offline, unreachable, refused, rate-limited, oversized, malformed or redirected to plain HTTP: no notice, and the next automatic attempt is a day later (the attempt time is recorded before asking). The manual check says what happened. |
| Never | No system notification, no download, no install request, no install permission. The only action opens the release page in the browser. |
| Diagnostics | One ring event per check: `update_check result=<kind> count=<entries>` or `reason=<failure>`. |

The once-a-day rule uses two clocks. The saved date covers restarts; the monotonic clock covers a date moved back and forth while the app runs. A saved date in the future (the date was moved back) makes the check due once.

## Evidence

`UpdateNoticeTest` (Robolectric, scripted feed, clock and network):

- UPD01: newer, same, older, prerelease (stable and candidate installs), draft, malformed, a JSON value that is not a list, oversized, 403, 429, 500, unreachable, offline, a redirect to plain HTTP, page lookalikes (`http:`, `github.com.evil.example`, `evilgithub.com`, user info, a port, `javascript:`, `intent:`, a percent-encoded `@`), a lookalike page stored on the phone, one notice per version across a restart, a local build.
- UPD02: setting off, no feed, an `http:` feed and a feed that is not an address all send nothing, the manual check included.
- Throttle: a second check an hour later is skipped; a day later it asks; the date moved forward a year or back 400 days asks at most once per process; a failure waits a day; the manual check always asks.
- The request: exactly the feed address, GET, two headers.
- No code path downloads or installs: the source has no installer, download manager, install action, `.apk`, file writing or activity start; the manifest has no install permission; both places that open the page go through the HTTPS host check.

Not verified: a live request, because there is no feed (BLOCKED, above), and the notice and About rows on a device, because a build without a feed shows neither.
