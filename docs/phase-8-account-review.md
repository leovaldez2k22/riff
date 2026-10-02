# Phase 8 account track: current status

Phase 8 is split into independent tracks by the implementation plan. This review covers the accounts, sync, and uploads track only. The user chose to keep app-saved online items on the phone for the same account's next sign-in.

## Implemented

- The existing in-app YouTube Music WebView sign-in is used for a read-only liked-songs screen. The screen requests `VLLM`, shows artwork and metadata, and offers continuation paging. A phone check with the signed-in account returned live rows. No song names or account identifiers are recorded here.
- The screen's **Keep** action changes only this app's saved-track list. It does not like or unlike a song on YouTube Music. The temporary test save was removed immediately afterward, and the button returned to **Keep**.
- App-saved online favorites, mix, and history are stored under an opaque account-specific local namespace. Sign-out hides them; signing into the same account can load them again. Older unscoped rows are quarantined until the user explicitly imports them into the current account. Demo collections remain local and separate.
- Sign-out clears the provider session and search cache, blocks cookie-based automatic reconnection until a new explicit sign-in, and removes provider media from the player. In-flight liked-songs, search, and stream-resolution results are rejected after an account change.
- Android cloud backup and device transfer are disabled for this app, so these local account collections are retained on this installation, not synced across devices.

## Verification and open gates

- `:app:assembleDebug` and `:app:lintDebug` pass on the final source.
- The first Phase 8 APK was installed and its signed-in liked-songs page and app-only Keep toggle were checked on the iQOO Neo 7 Pro. An in-place update to the final build then returned `INSTALL_FAILED_UPDATE_INCOMPATIBLE` because its debug signing certificate differed from the installed APK's certificate.
- The final build initially could not update in place because its debug signing certificate differed from the installed APK. The user did not have the old key and authorized a data-preserving reinstall. Four app-owned files (`demo_collections.xml`, `offline_demo_copies.xml`, `playback.xml`, and `podcast_progress.xml`) were archived and verified against SHA-256 hashes before uninstall. The new APK was installed, the four files were restored with matching hashes, and Home and Library opened without a crash. The original-demo copy directory was empty. Browser cookies and provider session fields were not transferred. The user then signed back into the same account; an in-memory comparison confirmed that its account scope matched a restored saved collection. No account ID or song data was written to this review. The temporary migration archive and verification copy were deleted.
- On the final reinstalled build, the signed-in liked-songs screen displayed seven visible live rows without a provider error, and Back returned to Library. No remote like or playlist mutation was made.
- The Phase 8 account-switch isolation gate still needs a device check with two accounts or a safe controlled account switch. It has not passed. A second device is also required to verify two-device conflicts.
- Remote playlist/like mutation, offline mutation queue, conflict resolution, provider uploads, and cross-device sync are not implemented. The current UI labels YouTube Music liked songs read-only and app-saved tracks local.
- Subsequent Phase 8 work added local recommendations/activity, speech-to-text search, a rule-based demo mix and recap/badges. Service-dependent tracks show unavailable states under the user-selected self-contained scope. See [the full Phase 8 scope review](phase-8-scope-review.md) for each track and its verification boundary.

The final APK is at `app/build/outputs/apk/debug/app-debug.apk`. The old app was uninstalled only after the user approved a selective backup and its contents were verified. The temporary backup was removed after restoration and the same-account re-sign-in check.
