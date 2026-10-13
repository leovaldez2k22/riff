# Ownership, structure and static checks (Q11)

Riff is one Gradle module, `:app`, in one package (ADR-Q01). Structure is kept by who owns what and by the
checks below, not by module walls. Entry-point class names (`MainActivity`, `MusicPlaybackService`,
`OfflineDownloadService`, `OfflineDownloadJob`, `SolverService`, the widgets) do not change.

## Who owns what

| Owner | Lives as long as | Owns | Closes or cancels |
| --- | --- | --- | --- |
| `AppContainer` | the app process | the provider facade and its four parts, `SessionCoordinator`, `LibraryStateDb`, history, playback snapshots, alias journal, legacy migration, local data control and transfer, the playback event log, one state scope on the IO dispatcher | nothing: the process ends. Tests build their own container and close what they made. |
| `MusicPlaybackService` | the Media3 service | the player and tail player, the media session, the service scope, the queue, recovery, persistence and audio-transition coordinators, the sleep timer, the SABR HTTP client | `onDestroy`: sleep timer, audio transitions, session registration, a bounded final save, players and session, the queue's stream state, then the service scope and the HTTP client. `onTaskRemoved` saves first. |
| `MainActivity` and `RiffApp` | the activity | the controller connection (`PlaybackGateway`), shell navigation state, player presentation state, haptics binding, its session registration | each in the `onDispose` of the effect that made it. Screens collect flows with `collectAsStateWithLifecycle`, so a stopped activity stops collecting. |
| `SolverService` | the solver process | the WebView solver and its Binder interface | its own `onDestroy`. |

Rules that follow from the table:

- A screen never constructs a store, an HTTP client, a player or a provider part. It asks the container
  (`AppContainer.get(context)`) or receives what it needs as a parameter.
- The service never reaches into screen state. It talks to screens through the media session and custom commands.
- Anything a screen starts is tied to its composition and ends with it. Anything the
  service starts is a child of the service scope. Work that must outlive both (state migration, pruning)
  belongs to the container's state scope.

## The provider

`YouTubeMusicProvider` is a migration facade: every member forwards to one of four parts, and it still
implements `StreamResolver`, `ProviderSession`, `ProviderCatalogReader` and `ProviderMutations`, so callers and
tests did not change. New code should take the narrow interface it needs rather than the facade.

| Part | Responsibility |
| --- | --- |
| `ProviderSessionManager` | the HTTP and InnerTube clients, cookies, sign-in and sign-out, the session and its scope keys, `providerResult` (one place that turns failures into typed results), reset listeners |
| `ProviderCatalogRepository` | read-only catalog: home, search, suggestions, albums, artists, playlists, moods, lyrics sources, radio continuations |
| `ProviderLibraryRepository` | the account's library: likes, saved collections, playlist changes and their confirmation, collection sorting |
| `ProviderStreamResolver` | stream resolution, player configuration, prewarming |

The parts depend on the session manager and not on each other, except that the library reads collections
through the catalog. The facade can be removed once no caller names it; that is not part of Q11.

## The playback service

`MusicPlaybackService` keeps the Media3 wiring: the session, its callbacks, custom commands, lifecycle, the
sleep timer and history recording. The rest is in classes that take the player and scope they need:

| Class | Responsibility |
| --- | --- |
| `QueueCoordinator` | builds, restores, replaces and extends queues; stream requests per entry; entry identity; generation tickets for stale requests |
| `RecoveryCoordinator` (`PlaybackRecovery.kt`) | the single retry budget, waiting for a connection, skip rules |
| `PlaybackPersistence` | captures and saves the queue snapshot, adopts a restored one, the bounded final save |
| `AudioTransitionCoordinator` | boost, normalization, crossfade and the tail player |
| `PlaybackSources`, `PlaybackFaults` | the media source factory; mapping a player error to a typed fault |

## The shell

- `ShellNavigation.kt`: typed routes (`Route`), validation of every argument that comes from saved state or an
  intent, a bounded stack per tab, and `ShellSavedState` with the format name `riff-shell/2`. The positional
  list written by earlier builds is still read; a route this build does not have, or one whose arguments do
  not validate, is dropped.
- `ShellScene.kt`: the screen host. It maps a route to a screen and nothing else. Each open screen has a key
  (`ShellScreen.key`: root, depth, and a collection's id) under which its scroll position and other
  remembered state are saved; a closed screen's state is dropped. A collection screen reads its own
  collection from `LocalCollectionRoute`, not from whichever screen is on top.
- `ContentState.kt` and `ShelfSkeleton.kt`: what a screen shows in place of content. Loading, empty and
  failed are three components and a failure is never drawn as an empty list. `DesignTokens.kt`: spacing,
  radius, type sizes and the theme. See `core-journeys.md`.
- `PlayerPresentation.kt` and `ShellChrome.kt`: the mini and full player, the bars and the sheet.
- The playback position and the player's drag position change every frame. They are read through lambdas,
  `derivedStateOf` or `snapshotFlow`, so a drag or a tick redraws the player and not the screens under it.
  Every place a screen file reads one is on the reviewed list.

## Cancellation

- A cancelled caller stays cancelled. Code that turns failures into results uses `suspendCatching`
  (`Cancellation.kt`), which rethrows when the calling coroutine is no longer active. `runCatching` remains
  for work that cannot suspend.
- A cancellation that comes from inside while the caller is still wanted (a timeout, a request the provider
  stopped when it refreshed its session) is a failure to report, and for provider calls a retryable one. That
  is what `providerResult` and `suspendCatching` return.
- No Binder call, file open or network call on the main thread. The local-file readability check for
  "play this file" now runs on the IO dispatcher inside the queue command.
- `runBlocking` exists at three reviewed places: the bounded final save when the service is destroyed, the
  player's loader thread in the SABR data source, and the solver process's Binder threads.

## The benchmark module

`:benchmark` (Q14) is a `com.android.test` module: Macrobenchmark tests and the Baseline Profile generator.
It is the one module beside `:app`, it holds tests only, and it drives the app from outside its process, so it
sees nothing internal. The app's side of it is three things. `PerfTrace` writes trace sections that cost one
check when nothing is tracing. `AppContainer.runtimeFor` is the function that builds the runtime; only the
benchmark build types replace it. `app/src/benchmarkFeed` is a source set that exists only in the
`benchmarkRelease` and `nonMinifiedRelease` build types (package id `com.musicglass.personal.benchmark`) and
feeds the app invented content from its own process. `scripts/quality/check_performance.py apk` fails a
release APK that holds any of it. See `docs/quality/performance.md`.

## Boundaries with the outside (Q15)

Three files hold the rules for what crosses the app's edge, as plain functions with tests of their own.
`DiagnosticEvent.kt`: every line of the diagnostic ring, of logcat and of the copied report is reduced to a
name, numbers and short words; write events as `name key=value`, and add a new key to the list there.
`WebBoundaries.kt`: which addresses the embedded browsers may load and the user's browser may be sent to,
the one `WebViewClient` base class (certificate errors end the load), who counts as the app's own media
controller and what any other controller may do. `scripts/quality/check_release_content.py`: what a release
APK may contain. See `docs/quality/security-review.md`.

## Static checks

    python scripts/quality/static_checks.py            # exit 1 on any finding
    python scripts/quality/static_checks.py --list     # also print every reviewed site and its reason
    python -m unittest discover -s scripts/quality -p "test_*.py"

The script needs only Python and reads `app/src/main/java`. Rules: `cancellation`, `blocking`, `direction`,
`lifecycle`, `position`, `size` (700 lines), `measures`, `strings`. They are described at the top of the script.

`strings` (Q13): words the app shows or speaks come from `res/values/strings.xml` through `string()` and
`quantity()` (`Strings.kt`), which read the application's resources so that text made outside a screen
follows the language too. A quoted literal with a word in it, handed straight to `Text`, a content or state
description, a notification title or a notice, is a finding. Words the code looks things up by (a tab, a
filter, a menu action) stay literals and are shown through `keyLabel()` (`KeyLabels.kt`). An enum that
carries a label holds the resource id and reads the text when asked, never when the class loads. See
`docs/quality/accessibility.md`.

`measures` (Q12) counts the raw `dp` and `sp` values each screen file writes instead of taking them from
`DesignTokens.kt`. `static_checks_measures.json` holds the count per file; a file may not exceed it, a new
screen file may have none, and a count that is too high must be lowered (`--write-measures`).

`scripts/quality/static_checks_reviewed.json` lists the sites that were read and accepted, each with a file,
a fragment of the line and a reason. There is no way to switch off a rule or a file. An entry that no longer
matches a line is a finding, so the list shrinks when the code does. Add an entry only with a reason a
reviewer can check against the code.

## Formatting

`.editorconfig` records the conventions the sources already follow. No Gradle formatter or analyzer (ktlint,
detekt, Spotless) is wired in: none is present in the project's offline Gradle home, and adding one needs a
network fetch and a dependency decision outside Q11. When one is added it must be pinned, run as a separate
mechanical commit, and carry no blanket suppression.
