# Phase 2: genuine library, accounts, catalog and storage migration

Updated 3 November 2026. **Not fully accepted:** the production library/catalog journey has evidence; owner-account authentication acceptance remains blocked by missing OAuth configuration and an unverified private Music authentication contract. This is not a claim that the entire Android account experience is ported.

| Task | Production wiring and evidence | Remaining gate |
|---|---|---|
| P2-01 relational persistence | `:desktop-data`, SQLDelight 2.1.0 / SQLite JDBC 3.51.3.0, WAL, serialized JDBC, scoped schema, FTS5, playlists with duplicate entry IDs, lyrics/catalog caches, history spans and sync journals. No custom-file fallback. | Latest incremental hardening is being rechecked in `artifacts/desktop/phase3/closure-tests.log`. |
| P2-02 account authority | Stable scope generations, real system-browser PKCE loopback and DPAPI-backed token storage; configured YouTube Data API read-only identity is separate from anonymous Music access. | Owner has no registered Desktop OAuth client. Actual login, refresh/revocation/account isolation against a real account and decryption under a different Windows user are unaccepted. No browser cookie harvesting or private Music auth is fabricated. |
| P2-03 provider catalog | Real public search, Music Home, Explore, exact album endpoints, radio and continuations. Generation checks reject stale searches/account results. Actual pages persist in SQLite. | Provider behavior is volatile; authenticated Music library pages have no supported verified session. Podcast discovery is public video search, not an invented podcast account API. |
| P2-04 local library | Native FFmpeg metadata/art probe workers, Windows FileIdInfo identity, WatchService reconciliation, rename/replacement/missing-root handling, bounded queues and persisted per-file errors. | Large physical-library daily-driver acceptance remains distinct from corpus-based end-to-end automation. |
| P2-05 reactive routes | Real library/album/artist/playlist pages and counts, bounded paging, query states, provider loading/failure/offline cache, playback dock and Mini-Player share one host. | Final packaged pointer/keyboard/drop journey must be rerun after the current Popup and asynchronous-audio changes. |
| P2-06 art | Actual encoded art acquisition, bounded decoding, persisted content key and palette, SQLite metadata, bitmap reuse and track-generation guards. | Android matched visual captures remain held by the earlier owner decision; component palette parity is not a replacement. |
| P2-07 playlists/history/privacy | Real local playlist operations and duplicate persistence; actual WASAPI-presented listening spans; history defaults on, 90 days, 100k cap, keep-totals off, external genres off. Scope preserved. | Authenticated remote writes are not implemented or claimed. Repeat-cycle occurrence and retention-cap fixes are in the current regression run. |
| P2-08 import/performance | Immutable SHA backups, FULL receipt transactions/savepoints, quarantine, unknown schemas, process-halt recovery, explicit storage-full/failure behavior, real 100k-row FTS benchmark. | Torn-final-record and atomic FTS-rebuild regressions are in the current run. |

## Existing end-to-end evidence

`artifacts/desktop/phase2/full-journey.log` and `acceptance/acceptance-profile-1793647373055.json` prove actual MP3/FLAC/M4A scan/decoding, persisted duplicate playlist occurrences, native rename with stable identity, playback/seek, WASAPI-presented listening, paused session restart, live Music pages, and reopening previously acquired cover/palette with zero network fetches. Offline acceptance injects a transport failure only; returned content comes from the actual earlier request.

`catalog-after-vpn.log` and `music-continuation-2.log` prove successful provider connectivity after the owner disabled the VPN. Country restriction is not retained as a current blocker. `sqlite-100k.json` records warm p95 about 1.48 ms, first query about 2.67 ms; OS caches were not flushed, so the first query is not called a physical cold-cache measurement.

The required nine-gate PR lane passed in `artifacts/desktop/phase3/pr-lane.log`. Desktop integration tests and physical/account acceptance have separate authority; Android release gates passing does not certify desktop playback.

## Authentication boundary

Google's installed-app contract requires a registered Desktop client, system browser and PKCE for documented YouTube Data API grants: https://developers.google.com/youtube/data/guides/auth/installed-apps . It does not establish private YouTube Music authentication. The owner reported that no Desktop client is configured. Provisioning that identity and completing the real consent/login journey remains an external prerequisite; no test fixture can close it.


### Resumed verification

The final relational/library journey passed again in `artifacts/desktop/phase3/final-package-journeys-2.log`; the atomic FTS initialization, torn-final-record importer, retention cap and repeated-play fixes passed in the post-render-correction suite. The required PR lane passed all nine gates in `pr-resumed.log`. Packaged playlist naming now accepts focused typing, mouse Save and Enter and persists through restart (`ui-keyboard-evidence.json`). First-launch Ctrl+K/search/Enter persistence passed interactively (`ui-search-evidence.json`). Typed full search, actual collection IDs and a real provider artist Top Result passed the live Host journey in `search-collections-package.log`. These receipts supersede the earlier “current regression run” wording above; owner-account and physical acceptance remain open.


### Daytime background history hardening

The owner placed all screen acceptance on hold until explicitly requested. No automated screen control is permitted during that hold. Implementation and non-UI acceptance continue.

Library schema 3 adds occurrence-level retention, persisted history epochs and transactional rollup receipts. Clear/history-consent changes reject older queued writes; midnight/DST attribution is exact for new spans, and rebuilds stream rows into day totals. A mixed-recording legacy occurrence is rejected before schema/version mutation; valid v1/v2 databases upgrade while preserving surviving rows. The earlier raw-span cap could truncate roughly 28 hours of continuous history and cannot reconstruct already-pruned detail. See the Feature 12 correction in the feasibility plan.

`history-authority-verification.log` passed desktop/data regressions and the real compressed import/seek/paused-restart library journey after these changes. `history-migration-guard.log` passed the additional v2 rejection/preservation test. `history-real-playback.log` passed actual muted WASAPI playback, paused clear and queued-writer drain (0 ms restored), process restart (still 0 ms), continued hardware playback while history was off (stored total unchanged), and resumption of recording when re-enabled. These are actual SQLite/worker journeys, not an injected audio runtime. The final receipt compares totals after graceful writer drain. Complete Encore charts/stories and account acceptance remain separate unfinished work.
