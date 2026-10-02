# Riff 0.9.0-rc1

Personal release candidate for offline handoff and later phone validation. It is not approved for public distribution.

## Included

- Home, Podcasts, Radio, Library and Search with original demo tracks, art, podcast progress and Radio visual previews.
- Compact immersive player, persistent demo queue, original timed lyrics, local playlists, on-device audio access and verified offline copies of original demos.
- In-app YouTube Music sign-in for search and read-only liked-songs browsing; app-saved online items remain local and account separated.
- Local activity-based demo recommendations, recap, history controls, a rule-based demo mix and Android speech-to-text search.
- Explicit unavailable states for connected features that have no backend, receiver, billing or recognition integration.
- Offline Phase 9 settings for solid, high-contrast controls and reduced motion; safer saved-state restoration and RTL tab hit testing. These require real-phone accessibility validation.

## Known limits

- Real YouTube Music song playback is unavailable: the tested SABR route returned `RELOAD_PLAYER` before any audio bytes. Search and browsing still require a network connection and may change with the unofficial provider API.
- Phase 8's new activity and voice-search screens passed build and lint but have not been checked on a phone. Phase 9's offline work passes logic/build checks; its accessibility, performance and resilience device gate is pending.
- This candidate has a new signing certificate. It cannot directly upgrade the current debug-signed installation while preserving app data. Do not uninstall that installation without a verified migration plan and approval.
- The Phase 10 fresh-install and same-key upgrade smoke suite is pending. The provided SHA-256 identifies the candidate for that test.

See `phase-10-release-review.md` for the device matrix, handoff and follow-up backlog.
