# Windows screen acceptance — owner-authorized overnight run

The owner uses this laptop during the day. Do not activate windows, drive UI controls or start screen acceptance until the owner explicitly asks. Night-time by itself is not authorization to resume. Implementation, compilation, isolated database checks and muted windowless audio verification can continue.

Owner explicitly authorized the overnight screen run on 4 November 2026 while sleeping. The earlier daytime hold below is historical; current acceptance uses `artifacts/desktop/phase3/night-ui-20261104`, never the owner profile. Physical unavailable hardware and account gates remain distinct.

## Build and identity

1. Identify and close only the owned isolated preview when screen work resumes; preserve the person's normal app/profile. The earlier test profile is `artifacts/desktop/phase3/ui-search-collections`.
2. Build `:desktop:createDistributable` on the current source, then run `scripts/desktop/audit-phase1-package.py`. The existing open preview predates the schema-3 history and final typed-error changes; it does not certify the current package.
3. Record source/native/runtime hashes, Windows build, display resolution/scaling/refresh rate, profile path and exact package. Use a new isolated acceptance profile for fresh-start checks and a separate retained profile for restart checks.

## Screen journeys still requiring acceptance

| ID | Real action | Evidence needed |
|---|---|---|
| UI-01 | Launch with no focused child, press Ctrl+K, type a query containing spaces, submit Enter, reopen after process restart. | Input focus, Space remains text, real provider suggestions/results, submitted-only scoped search history persists. Provider response counts are upper-bounded, not invented. |
| UI-02 | Search Daft Punk; open the actual provider artist/album Top Result and a collection result. | Exact primary browse identity, actual artwork/content, no artist Play-button video mislabeled as a song. Only a dedicated supplied shelf becomes Top Result. |
| UI-03 | Play a real mixed local/provider queue containing repeated recordings; inspect Up next and Earlier. | Current occurrence appears only in Now playing, repeated later occurrence remains, counts match the queue. |
| UI-04 | Reorder via grip and Alt+arrows, then double-click/Enter/Play Now on a duplicate occurrence. Cancel a drag during a state change. | Exact occurrence selected, correct underlying indexes, fresh revision callbacks, no stale action changing another entry. |
| UI-05 | Remove/undo, change active track, rapidly seek/pause and change panes. | One real host/worker session remains authoritative; late completions preserve play intent, metadata and chosen queue occurrence. |
| UI-06 | Switch main → compact → expanded → main, including from maximized; restart in Mini-Player. | Compact 360×160, expanded 360×460, readable bounded title/artist, cached art reuse, original main geometry/placement retained. Earlier screenshots proved the smaller sizes after a reproduced race fix; the complete final journey remains open. |
| UI-07 | Keep Lyrics closed when a real song starts, then open inspector and expanded Mini-Player. Seek, pause, change offset and romanization; test a real candidate chooser if supplied. | Actual loaded document/hardware anchors on both surfaces, correct pause/seek behavior, source provenance, no invented plain-text timing, per-document offset and persisted mode. |
| UI-08 | Create/rename a playlist through Enter and mouse Save, retain duplicate entries, restart; import/drop real compressed files. | Actual SQLite rows and source identities; no fixture content, ignored pointer action or focus trap. |
| UI-09 | Clear history while paused, restart; toggle recording off/on while genuine playback continues. | UI matches the already-tested schema-3 epoch/retention behavior. Clear acts only on the owned acceptance data. |
| UI-10 | Play a genuinely damaged local file, inspect the error, then select a valid recording. | Structured source error, paused worker recovery and valid playback afterward. Muted windowless native/controller acceptance has passed; visible messaging remains open. |

Record PASS, FAIL or HELD per journey with a receipt. A headless/component pass cannot substitute for the screen result. Respect any confirmation rules that apply to UI deletion or system changes at the time of execution.

## Distinct gates

- Physical headphone/Bluetooth/default-device loss, suspend/resume and no-device recovery need their own real device receipts. Run audible/loopback checks in an appropriate acceptance session; muted source/progress checks do not prove human listening quality.
- The owner previously held phone comparison and the unavailable external monitor. Do not claim Android matched-capture, physical mixed-DPI or high-refresh acceptance from offscreen math tests or this single display.
- Owner-account acceptance is blocked by the missing Desktop OAuth client and the unverified private Music authentication contract. Public read-only playback is separate evidence.
- Real Windows SMTC/flyout integration belongs to Phase 4; an internal metadata adapter cannot pass it. Signed installation/updater/rollback belong to Phase 5.
- Complete downloads, Encore charts/stories, Automix, Cast and other explicit parity-register items remain open until actually implemented and accepted or the owner changes scope. This checklist does not remove those requirements.


## Added download screen journey

UI-11: From a genuine provider track's + menu select Save offline; inspect actual progress, pause/resume/retry, then restart and use Play offline, seek and queue controls. Distinguish Downloads from imported Songs. Confirm the virtualized list, focused keyboard actions, four-second Remove/Undo, quit during Undo keeping the file, Windows-locked pending deletion and final post-release removal. Real windowless/controller receipts have passed; none substitutes for this screen check. The hold remains owner-triggered.


## Added current-source journeys (still held)

- UI-12: Change the canonical Dynamic tone pad by pointer and arrows/Home, Broad/Focused and all Manual faders/presets; verify response/balance, actual sound and persistence. The independent Android PCM and muted WASAPI receipts are separate evidence.
- UI-13: Open the dock speed chip/popover, use 0.05× slider steps and preset chips, close with Esc, change track during a pending change, then restart. Music resets to 1×; a genuinely typed episode uses the shared remembered episode speed. Known recordings beyond the explicit four-hour tempo capability remain at 1×.
- UI-14: Save a real local/public provider recording using the Android heart; inspect Saved locally, restart, then remove the save. Local-save state must match SQLite, and must never imply a remote account write.
- UI-15: Build Encore from actual listening, change Day/Week/Month/Year/All time, step periods, select chart/clock/heatmap/calendar cells, sort top lists and play an actual retained track. Check semantics/keyboard, resize behavior, clear/retention/privacy and visible error/loading states. New Encore components are not screen-accepted by compilation or statistics fixtures.

- UI-16: Open real Encore stories, use pointer hold/tap/swipe and Esc/arrows/Space, change period, verify reduced motion and the restored seen marker. Save an actual slide to PNG, inspect 1080×1920 artwork/Inter/text/branding and final animation state; compare with the Android reference. Exercise CSV/JSON native picker, explicit overwrite, cancel and a Windows-locked destination. Offscreen encoding/unit checks do not certify actual layer capture.
- UI-17: Open “Your history” from a real song, album and artist; navigate its real period bars. Keep music playing while loading a large Encore period, then clear only the acceptance profile's history and verify immediate/settled presentation. The held-read regression proves writer isolation, not visible focus/latency acceptance.


## 4 November overnight observations (in progress)

Current packaged schema-8 source launched successfully and passed package separation/native/license audit. Actual typed `Daft Punk` returned provider suggestions and a dedicated Top Result. The subsequent pointer selection occurred while the results were still scrolling; it opened The Weeknd, so this is **not** accepted as a correct Daft Punk Top Result selection. Actual Starboy stream played beyond three minutes, with LRCLIB timed lyrics automatically loaded before opening Lyrics; compact and expanded Mini-Player used the real artwork and moving lyric. Dimensions observed 360×160 and 360×460. Muting changed Riff only.

UI-06 **FAIL, fix under test**: returning to Main restored 800×600 rather than the original 1280×800. Increasing AWT minimum posts a delayed resize; the mode effect now allows that event before restoring the saved size. A rebuilt screen rerun is required. UI-07 bounded playback/mini lyrics was observed, but offsets, mode changes and final-source rerun remain. No complete journey is inferred from these observations.

Windows UI Automation exposed only a window and pane in this run; screenshot-based pointer operation does not establish screen-reader accessibility. The isolated preview exited through Ctrl+Q before rebuilding.


The rebuilt UI-06 bounded rerun restored the observed 1280×800 main frame after compact mode (a transient 800×600 frame preceded the settled 1280×800). Full maximize/expanded/restart variants remain. The retained real provider session restored paused at 3:43, then hit a genuine network seek timeout; recovery deadline changes require another package rerun.

Encore displayed the actual 3m42s heard time and one retained Starboy occurrence after restart. Stories rendered actual title/artist/artwork; Space visibly changed Pause to Resume, holding progress. The PNG action captured a layer and opened a picker, but Swing's shared hidden-frame owner let the Compose popup obscure that modal after activation; **no PNG save was accepted**. The blocked isolated preview was terminated by verified profile-specific process selection. App-owned native save dialogs replace the unowned picker in source. The square story canvas is changed to 9:16 and the decorative dock cover is replaced with the shared keyed actual artwork path; both require rebuilt screen acceptance.


The corrected package visibly renders a 450×800 portrait story inside the 1280×800 frame, and the dock now shows the real cached Starboy artwork. NVDA 2026.3 with a silent isolated config actually used Java Access Bridge and announced Encore as a selected tab, Watch Encore as a button, Save slide PNG as a button and the native save dialog/filename field. This is genuine screen-reader evidence; the Windows UI Automation tree's lack of Compose descendants is not an accessibility failure by itself.

Native picker typing remains **HELD by automation targeting**: the helper lists only the main Riff frame; its text-input activation returns focus from the owned modal to that disabled parent, obscuring the dialog. The filename did not change and no PNG file was saved. Owner-aware native dialogs improve app ownership, but this tool limitation persisted. No save is accepted. The blocked isolated preview was terminated and relaunched so unrelated screen journeys can continue.


## Morning stop (4 November, 08:04 IST)

The overnight authorization window has ended; no further screen interaction is performed while the owner uses the laptop. Phase 4 was **not** started because the requested before-07:00 condition was no longer met. File Explorer inspection's app-approval request timed out. File import/drop screen acceptance is held by that external approval restriction; no alternate input path bypassed it. The separate native-picker targeting restriction remains.

A later Ctrl+K rerun genuinely focused an editable blank field (NVDA/JAB), but typing did not settle in it and playback controls received focus; input logs around 08:02 also contain volume-key activity. This is an uncontrolled/interleaved morning run, **not accepted as a keyboard pass or a diagnosed app defect**. The isolated silent NVDA process was stopped immediately after recognizing the morning boundary. The local raw speech/debug log is private acceptance material and must not be published.
