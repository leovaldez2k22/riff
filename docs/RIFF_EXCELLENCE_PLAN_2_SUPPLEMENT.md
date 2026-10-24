# Riff excellence plan 2: supplement for uncovered issues

Version: 1.0. Date: 11 October 2026 (Asia/Kolkata).
Status: implementation specification. Nothing here is implemented or authorized.
Companion to: docs/RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md v1.1 ("plan 1"). Plan 1 stays the governing document; this plan adds packages for issues plan 1 does not own.

## 1. Objective and relationship to plan 1

A project review on 11 October 2026 compared its findings with plan 1. Most were covered. Four were not covered at all, and three were covered only in part. This plan gives each of those seven an owner, actions and an acceptance test.

Rules of precedence:

1. The plan 1 execution contract (section 1.1), ledger rules (1.2), invariants (3), completion requirements (5) and device protocol (17) apply unchanged to every package here.
2. Where this plan is more specific than plan 1, use this plan. It never weakens a plan 1 gate.
3. Two packages amend a plan 1 decision: S05 amends ADR-Q03 and S06 extends ADR-Q01. Each needs explicit human approval of the amendment, separate from approval to implement.
4. The 9 October authorization for Q03-Q18 does not cover any package here. Obtain approval before implementing.
5. The gap list came from reading plan 1 sections 4, 8 (Q08), 10 (Q11), 11, 13, 14 and 15 in full and keyword-searching the rest. S00 re-checks it against the whole of plan 1 before any work starts.

### 1.1 Gaps and owners

| Gap | State in plan 1 | Owner here |
|---|---|---|
| R8 runs with Kotlin metadata warnings on the pinned toolchain | Recorded in the Q00 baseline as needing "a separately approved change"; no package owns it | S01 |
| Player config fetched at runtime from a third-party GitHub URL and fed to the solver | Not mentioned | S02 |
| innertubex resolved only from JitPack | Not mentioned; Q15 action 10 covers verification, not availability | S03 |
| Terms-of-service and distribution exposure | Not mentioned, including in Q05R | S04 |
| Preferences storage spread over 22 files | ADR-Q03 moves history, snapshots and scope registry only | S05 |
| Compose state held in provider, store and settings classes | ADR-Q01 and Q11 split the classes but do not state the rule | S06 |
| Documentation sprawl and stale root plans | Q17 labels old reports as historical; nothing is moved | S07 |

### 1.2 Recorded non-goals

- The internal namespace com.musicglass.personal stays. Plan 1 section 3 keeps the application ID for the upgrade path, and renaming the source package alone is churn without user benefit.
- No extra Gradle modules, Hilt, Room or DataStore. Plan 1 section 3 stands.
- The nine UseTomlInstead lint warnings stay with the Q15/Q16 lint triage that the Q00 baseline already assigned.
- The dependency on an unofficial provider cannot be removed. S02-S04 reduce and disclose the risk; Q10 and Q10A in plan 1 handle detection and response.

## 2. Execution order

| Package | Depends on | Must finish before |
|---|---|---|
| S00 reconcile | Plan 1 Q03 committed | Any other S package |
| S01 shrinker toolchain | S00 | Q05R (first release cut from a shrunk build) |
| S04 distribution decision | S00 | Q05R publication |
| S02 player-config boundary | S00, Q08 (fail-closed solver) | Q15 security review |
| S03 dependency availability | S00 | Q16, Q17 packaging |
| S05 preferences ownership | Q04, Q05 PASS | Q11 |
| S06 state ownership | Q11 package split under way | Q11 PASS |
| S07 documentation layout | S00 | Q17 |

S01 and S04 are the only packages that block a plan 1 package that is close. If Q05R is authorized before they pass, Q05R is BLOCKED for publication, not for preparation.

## 3. Packages

### S00: Reconcile with plan 1

Actions:
1. Read plan 1 completely. For each row of section 1.1, confirm no plan 1 action already owns it. Drop or narrow any S package that plan 1 turns out to cover, and record the reason.
2. Add S00-S07 to docs/quality/execution-ledger.md as TODO with dependencies and "authorization: none".
3. Add one line to plan 1 section 22 pointing at this document. Change nothing else in plan 1.
4. Record the two ADR amendments (S05, S06) as pending human decisions.

Acceptance: ledger lists every S package; each gap has exactly one owner across both plans; no application source changed.

### S01: Shrinker and Kotlin toolchain compatibility

Background: gradle/libs.versions.toml pins Kotlin 2.4.10 with AGP 8.13.2. The Q00 mapping header shows bundled R8 8.13.19, and the Q02 release log has 172 lines of Kotlin metadata warnings. The Q00 baseline cites Android's Kotlin support page as listing R8 9.1.29 for Kotlin 2.4. A release build that assembles is not proof that the shrunk app runs correctly.

Actions:
1. Re-read the official Kotlin/AGP/R8 compatibility table on the day of work and record the URL, date and the rows used.
2. Choose the smallest correction, in this order of preference:
   a. Pin a compatible R8 through the documented buildscript classpath override, leaving AGP and Kotlin unchanged.
   b. Move AGP to the lowest version that bundles a compatible R8, if (a) is unsupported or still warns.
   c. Lower Kotlin to the newest version the bundled R8 supports. Last resort; check the Compose compiler plugin and innertubex metadata still load.
3. Change one variable per attempt. Record each attempt, including failures, in the ledger.
4. Rebuild release, lite and stress. Compare warning counts with the Q02 log. Triage every remaining warning individually; no blanket -dontwarn.
5. Diff the R8 mapping and the APK entry list against the Q02 release. Explain any class or resource that newly disappears.
6. Confirm the keep rule for InnerTubeExtractor.cipherService still holds and CipherBridgeTest passes against the shrunk classes.
7. Run instrumentation against the shrunk release with -PreleaseSmoke=true on the disposable emulator (fixture playback at minimum). A physical run stays BLOCKED until a device is authorized.
8. Update the toolchain table in README and plan 1 section 2 only if a version changed.

Acceptance: zero Kotlin metadata warnings, or each remaining one triaged with a written reason; release smoke passes on the shrunk APK; APK stays under 40 MiB; no unrelated dependency moved.

### S02: Player-config trust boundary

Background: AppPlayerConfigRepository in YouTubeMusicProvider.kt sets sourceUrl to a raw.githubusercontent.com file on the master branch of a third-party repository. RemotePlayerConfigStore refreshes it about every six hours and after failures, caches it in preferences, and the library has a second fallback URL on another third party's GitHub releases. Two stores exist: one in the main process (YouTubeMusicProvider.kt, built on the provider's HTTP client) and one in the :solver process. The config steers the code that solves stream challenges in QuickJS. A change to that file, or a compromise of that repository, therefore reaches every install within hours without a Riff release.

Pinning the URL to a commit is rejected as the default: it freezes the table, and playback then breaks at the next provider player rotation.

Actions:
1. Read the innertubex source at the exact pinned tag (0.7.2; the local .toolchain checkout is v0.7.1 and must be refreshed first). Write down what each config field can cause: which strings are evaluated as JavaScript, in which process, with what host access.
2. Verify what the main-process store sends. It is built on the provider client, which has the cookie-rotation plugin installed. Prove with a fixture-server test that requests to the config hosts carry no cookies, authorization headers, visitor data or account identifiers. If they can, give the store its own credential-free client, as the lyrics client already has.
3. Once Q08 makes the solver fail closed, remove or disable the main-process store's evaluation path so config-derived code runs only in :solver. If the library offers no switch, record that and keep the Q08 guarantee as the control.
4. Validate before caching: HTTPS only, host allowlist (the two known hosts), no cross-host redirect, existing 4 MiB bound kept, strict schema parse, player-hash format check. A rejected response keeps the last known good table and logs a sanitized event.
5. Keep last-known-good across restarts and process death. A corrupt cache falls back to it, then to the library's built-in behavior, never to a crash loop.
6. Assess isolating evaluation further: an isolatedProcess service has no network or app-data access, so fetch would stay in :solver and only evaluation would move. Implement only if the pinned library allows fetch and evaluation to be separated without a fork; otherwise record the finding.
7. Confirm the :solver process holds no cookies, no provider session and no WebView. SolverService documents this; add a test that fails if a provider or CookieManager is created there.
8. Human decision, recorded in the ledger: keep the upstream URL (default), or point sourceUrl at an owner-controlled mirror updated by a reviewed job. The mirror narrows trust to the owner but adds a maintenance duty and a delay after each provider rotation.
9. Add the config hosts to docs/privacy.md (they see IP address and user agent) and to the Q15 security-review.md attack cases and the Q10A runbook.

Acceptance: fixture tests prove no credential reaches a config host; malformed, oversized, redirected and wrong-host responses are rejected with last-known-good retained; config-derived code is shown to run only in :solver or the limitation is written down; the trust decision is recorded.

### S03: Dependency availability and provenance

Background: innertubex 0.7.2 is GPL-3.0 and resolves only from JitPack (settings.gradle.kts scopes JitPack to that one group, which is correct). JitPack builds from the upstream repository on demand. If the tag, the repository or JitPack goes away, Riff cannot be built from a clean machine, and a GPL distributor must be able to supply the corresponding source of the exact version shipped.

Actions:
1. Record the upstream commit SHA behind tag 0.7.2 and the SHA-256 of every resolved innertubex artifact (AAR/JAR, POM, module metadata) in docs/dependency-license-inventory.md.
2. Refresh the local source checkout to the 0.7.2 tag and verify it builds. Keep a source archive of that tag with each release package (Q17 packaging list).
3. Add an opt-in build path that substitutes the dependency from source: a Gradle property naming a local checkout, wired through includeBuild with dependency substitution. Default builds are unchanged.
4. Prove the fallback once: build debug with the substitution on, run the JVM suite, and compare the public API used by Riff (CipherBridgeTest covers the reflective field).
5. Coordinate with plan 1 Q15 action 10: the verification metadata must include the JitPack artifacts, so a rebuilt or replaced artifact fails the build instead of being accepted silently.
6. List every runtime third-party endpoint the app or the library contacts (config hosts from S02, lyrics hosts, image hosts) in the provider-compatibility document plan 1 Q17 creates, with what breaks if each disappears.
7. Do not vendor binaries into Git. Do not fork the library unless S02 or Q08 proves a seam is needed.

Acceptance: a clean checkout builds with JitPack blocked, using the documented source path; artifact hashes are recorded and enforced; the source archive for the shipped version exists beside the candidate package.

### S04: Distribution and terms decision

This package records facts and a human decision. It is engineering documentation, not legal advice, and it must say so.

Background: Riff is an unofficial client. It signs in through a WebView, solves stream challenges, plays without the provider's own client and stores downloads. README and NOTICE.md state non-affiliation. Nothing tells a user what signing in with a personal account may risk, and no document records how the owner intends to distribute builds. Q05R prepares the first release from this repository.

Actions:
1. Write docs/quality/distribution-risk.md: what the app does that an official client does not; which provider and Google terms are relevant (links and access dates, no paraphrased legal conclusions); what could plausibly follow (playback breakage, account restriction, takedown request for a repository or release); which choices reduce exposure.
2. Record one human decision in the ledger before any publication: personal use only; source published without binaries; or source plus signed APKs on GitHub Releases. Q05R and Q18 packaging follow that decision.
3. Add a short notice on the sign-in screen, shown before the WebView opens: this is an unofficial client, signing in is optional, and using a personal account is at the user's own risk. Guest and on-device use must stay fully usable without it. Externalize the string with Q13.
4. Review name, icon, screenshots and store-style text for anything that could read as an official product. NOTICE.md already carries the trademark line; keep it in the packaged notices.
5. Add a takedown/response section to the Q10A runbook: who decides, what is unpublished, how users are told.
6. Add no feature whose purpose is to defeat a provider block of an account or device. Q10A already treats a block as a block, not as app breakage.

Acceptance: the document exists and is linked from README; the decision is recorded with a date; the sign-in notice is covered by a Compose test; no release artifact is published without the decision.

### S05: Preferences ownership (amends ADR-Q03; needs approval)

Background: production code calls getSharedPreferences in 34 places across 22 files of preferences. Several hold account-scoped JSON lists (music_collections), several are device-wide settings, three exist only so ContentMigration can clear retired data. Eight writes use synchronous commit(). ADR-Q03 keeps local collection preferences as they are.

Amendment requested: after Q04 and Q05 pass, move account-scoped collection data into library-state.db. Device-wide settings stay in preferences.

Actions:
1. Inventory every preferences file: owner class, keys, scope (device-wide, account-scoped, cache, diagnostics, retired), size bound, and whether Q05 deletion already covers it. Publish the table in docs/quality/architecture.md.
2. One owner per file. Only that class opens it; file names live in one constants object. Other callers go through the owner.
3. Add a JVM test that scans production sources and fails on getSharedPreferences outside the allowlisted owners. This is the regression barrier; it replaces reviewer vigilance.
4. If the amendment is approved: migrate music_collections favorites, playlist and history rows into library-state.db using the Q04 checkpoint and rollback rules (plan 1 section 20.4). Keep the source preferences until the migration record is durable; never delete on a failed verify. The in-progress mergeCollectionScope alias merge must run before or inside this migration, not concurrently with it.
5. If the amendment is declined: keep the data in preferences behind the single owner, add a size bound per list and a corrupt-JSON recovery test, and record the decision.
6. Move every commit() off the main thread or justify it in a comment where durability is required before the next step (migrations).
7. Remove the retired files (demo_collections, offline_demo_copies, podcast_progress) from the code only after ContentMigration's one-time clear has shipped in a published release; until then keep them in the inventory as retired.

Acceptance: the scan test passes with one owner per file; no main-thread commit(); upgrade from a fixture of today's preferences keeps every saved track, playlist entry and history row in order; Q05 deletion still removes exactly the selected owner's data.

### S06: State ownership (extends ADR-Q01; needs approval)

Background: Compose snapshot state (mutableStateOf and relatives) appears in non-UI classes: YouTubeMusicProvider (signedIn, sessionState, accountProfile, authRevision, homeMoods, library snapshots), LocalCollections, SearchHistory, NotInterested, the settings holders, PlaybackGateway and ServiceStopDiagnostics. Some of it is written from IO coroutines. The provider already publishes its session as a StateFlow, so two mechanisms now coexist. Q11 splits these classes but does not say which mechanism the pieces use.

Rule to adopt: code outside the ui package exposes immutable values through StateFlow or suspend functions. Compose state lives only in ui and in screen state holders.

Actions:
1. List every snapshot-state property outside UI files, with its writers and the thread each writes from.
2. Convert each to StateFlow as its owning class is extracted in Q11, in the same change as the extraction and never as a separate sweep through a file Q11 has not reached. UI collects with lifecycle-aware collection.
3. Introduce plain screen state holders for Account, Library, Search, Downloads and Settings, constructed from AppContainer. The activity already handles configuration changes itself, so lifecycle ViewModels add nothing here; do not add them.
4. Keep ShellModel as UI state; Q11 item 6 already replaces its saver.
5. Extend the Q11 item 11 dependency check: files outside ui may not import androidx.compose.runtime or androidx.compose.ui. Start with an allowlist of today's violations and shrink it to empty.
6. For each converted property add or keep a test that covers a stale-generation write (the Q03 token rule) so the conversion cannot reintroduce cross-account publication.

Acceptance: the import check passes with an empty allowlist; no state is written off the main thread except through a flow; existing UI tests pass unchanged; recomposition counts on Home and Now Playing are not worse than the Q14 baseline.

### S07: Documentation layout

Background: docs/ holds 57 files, more than 30 of them dated release-candidate, phase and fix reports. Two superseded documents sit at the repository root: MUSIC_APP_IMPLEMENTATION_PLAN.md (27 September, written before any code) and YTMUSIC_INTEGRATION_AGENT_GUIDE.md. Plan 1 Q17 keeps old reports labeled historical and adds a pointer, which leaves a newcomer to work out which of 57 files is current.

Actions:
1. Create docs/history/ and move every dated or superseded report into it with git mv, in one mechanical commit with no content edits. Include the two root documents.
2. Put a one-line banner at the top of each moved file: historical, the date it describes, and a link to the current document that replaces it.
3. Add docs/README.md as the index of current documents: features, privacy, release signing, the quality directory, both excellence plans. One line each.
4. Fix inbound links in README, CHANGELOG, CONTRIBUTING, NOTICE and inside docs. Add scripts/quality/check_links.py for relative Markdown links and run it in the deterministic CI lane.
5. Keep docs/quality/ as the only place for current evidence. Evidence paths recorded in the ledger must not change; the ledger and baseline do not move.
6. Leave .toolchain/ and artifacts/ alone. They are ignored local working directories; the existing deletable-files reports list candidates, and deleting any of them needs explicit authorization.

Acceptance: the root holds only current top-level documents; link check passes; no file content changed apart from banners and link targets; git log --follow still shows each moved file's history.

## 4. Acceptance matrix additions

| ID | Case | Expected |
|---|---|---|
| TC01 | Shrunk release, fixture playback on emulator | Passes; mapping/APK diff explained |
| TC02 | Release build log | No untriaged Kotlin metadata warning |
| PC01 | Config response from wrong host, redirected, oversized, malformed | Rejected; last-known-good kept; sanitized event |
| PC02 | Config request while signed in | No cookie, auth header or account identifier sent |
| PC03 | Provider or CookieManager created in :solver | Test fails |
| DA01 | Clean build with JitPack unreachable, source path set | Builds; JVM suite passes |
| DA02 | innertubex artifact hash differs from recorded | Build fails |
| DR01 | Sign-in opened first time | Notice shown before WebView; Guest path unaffected |
| PO01 | getSharedPreferences outside an owner | Scan test fails |
| PO02 | Upgrade from current preferences fixture | Collections identical in content and order |
| SO01 | Compose runtime import outside ui | Check fails |
| DL01 | Relative link to a moved document | Link check fails until fixed |

## 5. Gate effects on plan 1 section 18

These add criteria to existing gates; they create no new gate letters.

| Gate | Added criterion |
|---|---|
| G01 | Counts S00-S07, or records each as declined by an explicit human decision |
| G07 | PC01-PC03 pass; config trust decision recorded |
| G08 | TC02: shrinker warnings triaged alongside lint |
| G10 | TC01 on the exact candidate; DA02 enforced |
| G11 | distribution-risk.md, docs index and runtime-endpoint list current; DL01 passes |

A declined package does not block Q18, but the final report (plan 1 section 19, item 6) must name it as a known limitation. S01 and the S04 decision cannot be declined for a published release.

## 6. Handoff

First session: run S00 only. Then ask for approval of S01 and S04, because Q05R depends on them, and for the two ADR amendments in S05 and S06.

Copyable executor prompt:

Read docs/RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md completely. Plan 1 governs; the supplement adds packages S00-S07. Start with S00 and update docs/quality/execution-ledger.md. Implement an S package only after it is authorized, in the order of supplement section 2, under the plan 1 execution contract. Do not publish, install on a device, mutate a provider account or delete data without authorization. Mark anything that needs unavailable hardware, accounts or a human decision as BLOCKED with exact rerun steps.
