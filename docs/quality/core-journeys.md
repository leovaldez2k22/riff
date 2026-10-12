# Core journeys (Q12)

What the ten core journeys must do, where each decision is tested, and what still needs a phone in a
hand. All content in the tests is invented; no account, no network and no real artwork is involved.

## What a screen shows in place of content

Three different things, never drawn alike (`ContentState.kt`, `ShelfSkeleton.kt`):

| State | Component | Rule |
| --- | --- | --- |
| Loading, nothing to show yet | `ListSkeleton` | Fixed size: six rows of 60 dp. Shown only while nothing is on screen; a refresh of content that is already shown keeps the content. Announced once. |
| Nothing here, nothing wrong | `ContentEmpty` | One sentence. Never shown while a failure is shown. |
| Failed | `ContentProblem` | The sentence from the provider layer and one action. Stays until retried or left. Never a snackbar only, never an empty list. |

The action of a failure follows its kind (`problemKind`):

| Kind | From | Action |
| --- | --- | --- |
| Offline | no connection, or no answer in time | Retry |
| Session expired | the sign-in is no longer accepted | Sign in (opens Account). Retry cannot help. |
| Unavailable | anything else | Retry |

A next page that fails does not replace the list. The rows stay, the sentence is shown beside **Load more**
(`load_more_problem`), and the same button tries again.

Search, collection detail, Radio and Podcasts, Library and On device use these. Home keeps its own shelf
skeletons (`ShelfSkeleton`, already fixed size) and Explore gained Retry.

## Tokens

`DesignTokens.kt` holds spacing (`RiffSpace`), corner radius (`RiffRadius`), type sizes (`RiffType`), the
theme (`RiffTheme`) and `contrastRatio`. Colours stay in `RiffApp.kt` (Ink, Raised, Muted, Pink, White) and
motion in `GlassMotion`.

The shared parts (screen list, screen title, back heading, rows, tool links, the state components) take
their measures from the tokens. Screen files still contain raw measures: 659 in 34 files on the day this
was written. They are not all converted, because changing them one screen at a time without a device to
look at is how spacing regressions get in. Instead the count is held:

    python scripts/quality/static_checks.py                    # fails if a screen file gains a raw dp or sp
    python scripts/quality/static_checks.py --write-measures   # after removing some, record the lower count

`scripts/quality/static_checks_measures.json` holds the count per file. A file may not exceed its count, a
new screen file may have none, and a count that is higher than the file needs is itself a finding, so the
numbers only go down.

Contrast, checked in `JourneyDecisionsTest`: White, Muted and Pink on Ink and on Raised are all at least
4.5:1. A filled button is Pink with Ink text (was Material's default dark purple).

## Navigation state

Every open screen has a key: root, depth, and for a collection its id (`ShellScreen.key`). What a screen
remembers (scroll position, expanded parts) is saved under that key.

- A collection opened from another collection is a second screen. Back returns to the first where it was
  left. Before Q12 both shared one key, so the first lost its place.
- A screen that is closed has its saved state dropped. Opening the same collection again starts at the top.
- A screen draws the collection it was opened for (`LocalCollectionRoute`), also while it slides away.
- Search keeps its last results in memory (`ShellModel.searchSnapshot`: query, filter, account revision,
  rows, next page). Coming back from an album shows them at once without asking again. It is never saved to
  disk and never shown for another account or another query.
- A double tap on a tab still resets that tab to its first screen and its top.

## Position and volume

- The position bar says `Position, 1:05 of 3:20` and can be set directly by an accessibility service.
- For live radio it is disabled and says, in words on screen and to a screen reader, `Live radio can't be
  rewound`.
- The volume bar says `Volume, 40 percent` or `Volume, Muted`.

## Confirmations

- The confirm button names the operation: **Clear cache**, **Delete all downloads**. Not "Clear", "OK".
- A failure is said inside the dialog that asked, which stays open with **Try again** and **Close**. It does
  not vanish into a snackbar.
- The cache prompt says that downloaded songs are kept. Manual downloads, Smart Downloads ("Managed by Smart
  Downloads") and the playback cache are three separate things in the text and in storage (Q08).

## Journeys and their tests

JVM tests run on Robolectric (`:app:testDebugUnitTest`); device tests are named as such.

| Journey | Decision | Test |
| --- | --- | --- |
| UX01 first launch, sign-in | Signed out, Library is useful at once, asks the provider nothing, offers sign-in | `JourneyDecisionsTest.signedOutTheLibraryIsUsefulAtOnceAndWaitsForNothing` |
| | Sign-in never spins for ever; slow message then recovery | `SignInFlowTest.navigationGetsASlowMessageThenRecoveryInsteadOfAnInfiniteSpinner`, `SignInActionsTest` |
| | Abandoned or cancelled sign-in is rejected | `SignInFlowTest.abandonedBrowserAndVerificationCallbacksAreRejected` |
| | Unverified account says so and offers Verify | `AccountScreenSessionTest` |
| | Local audio needs no account | `HistoryScreensTest.unverifiedAccountShowsNoAccountHistoryButStillShowsThePhones`, `LocalDataDeviceTest` (device) |
| UX02 search, play, queue | Loading, empty and failed are distinct; Retry asks again | `ContentStateUiTest` |
| | Clear empties field and results | `ContentStateUiTest.clearingTheQueryEmptiesTheFieldAndTheResults` |
| | Back to Search shows the same results without a new request | `NavigationStateUiTest.comingBackToSearchShowsTheSameResultsWithoutSearchingAgain` |
| | Several quick taps play only the last | `QueueCoordinatorTest` |
| UX03 filter, sort, play | Sorted or filtered play keeps authored position and repeated entries | `CollectionSortMenuTest`, `QueueRegressionTest`, `PlaybackOrderDeviceTest` (device) |
| | A cut-off collection says so and disables whole-collection actions | `CollectionTruncationUiTest` |
| | Nested collections keep their own place | `NavigationStateUiTest.backFromACollectionOpenedInsideAnotherReturnsToTheFirstWhereItWasLeft` |
| UX04 download, offline, play | Phases are truthful; a complete download plays without extraction | `DownloadEngineTest`, `DownloadReconcileTest`, `DownloadLifecycleDeviceTest` (device) |
| | Clearing the cache keeps downloads; button and prompt say so | `DownloadControlsTest.clearingPlaybackCacheRequiresConfirmationAndCancelKeepsTheAudio` |
| | Seek says its value and can be set | `PlayerControlSemanticsTest` |
| UX05 mini, full, queue, Home | Drag and fling thresholds; Back closes the player before a screen | `PlayerPresentationStateTest`, `PlayerGestureTest`, `ShellNavigationTest.backClosesThePlayerBeforeItClosesAScreen` |
| | Queue thumbnail returns to the player without changing playback | `DownloadControlsTest.tappingQueueThumbnailReturnsToPlayerWithoutChangingPlayback` |
| UX06 lyrics | Default +0.2 s, steps, limits, Reset returns to the default | `JourneyDecisionsTest.lyricsTiming*` |
| | Timed, plain and none are told apart; failure is not "no lyrics" | `LyricsTest`, `LyricsPreloadTest` |
| UX07 account switch | Outgoing provider audio stops; nothing of the old account is shown or lands late | `AccountSessionTest`, `AccountScopeProviderTest`, `DownloadOwnerSwitchTest`, `AccountSwitchDeviceTest` (device) |
| UX08 deletion | Scope and consequence are named; Cancel changes nothing; partial is never success | `PrivacyDataScreenTest`, `HistoryScreensTest` |
| UX09 network, session error | The action fits the failure; expired session offers Sign in | `ContentStateUiTest.anExpiredSessionOffersSignInBecauseRetryCannotHelp` and neighbours |
| | A failed next page keeps the rows | `ContentStateUiTest.aNextPageThatFailsKeepsTheRowsAndSaysSoBesideTheButton` |
| | Playback failures: Retry, Skip, Sign in | `PlaybackRecoveryTest`, `PlaybackStateTest`, `RecoveryDeviceTest` (device) |
| UX10 widgets, lock screen, timer | A widget shows the selected track, play or pause as it is, and nothing invented | `JourneyDecisionsTest.aWidget*` |
| | Timer: one deadline, set by the service, read by the UI | `SleepTimerControllerTest`, `ComponentUiTest`, `FeaturePolicyTest` |

## Pictures

`ScreenBaselineTest` draws nine states off-device at 360 x 640 dp and compares them with the PNG files in
`app/src/test/resources/quality/screens`:

    search-empty, search-loading, search-offline, search-error, search-results,
    search-results-large-text (font scale 2), mini-playing, mini-paused, mini-paused-large-text

A picture fails when more than 0.2 % of its pixels differ by more than 8 in any colour channel. The picture
that was drawn is written to `app/build/reports/screens/<name>-actual.png`. After an intended change:

    gradlew --offline :app:testDebugUnitTest --tests "*ScreenBaselineTest" -PriffRecordScreens

and look at every changed PNG before committing it.

Limits, stated plainly:

- The pictures were recorded on Windows with Robolectric's own renderer and fonts. Whether Linux or macOS
  produce the same pixels has not been checked. If CI on another system differs, record there and keep one
  set per system rather than widening the tolerance.
- They are not what a phone draws. Blur, real artwork, system fonts, insets and the refresh rate are absent.
  They catch a moved, missing or recoloured element; they do not show that a screen looks right.
- The full player is not pictured. It needs the shared-transition and player hosts and is covered by the
  device walk-through below.

## Device walk-through: BLOCKED

Q12 asks for a walk-through of every journey on a physical phone. No phone was connected and installing on
one needs approval, so this was not done and no result is claimed. To do it, with a signed-in test account
that holds nothing personal:

1. Install the debug build. Do not uninstall the main app.
2. UX01: fresh data. Open the app signed out; Home, Search and Library must each show something useful. Open
   On device and deny the permission: an explanation, not a spinner. Start sign-in and press Back half way.
3. UX02: search, clear with the X, search again, open an album, press Back: the results and scroll position
   are there at once. Play a row: the row shows as selected only when it is the one playing.
4. UX03: open a playlist, scroll, open an artist from a row, press Back: same scroll position. Sort, then
   play the fifth row: the fifth row plays.
5. UX04: download a song, watch the phases, force-stop, turn on flight mode, play it, seek.
6. UX05: mini to full by drag and by tap, open the queue, tap the thumbnail, Back to Home. Artwork must not
   jump or double.
7. UX06: lyrics for a timed song, a plain one and one with none. Timing shows +0.2s; Reset returns to it.
8. UX07: switch account while a song plays: it pauses, and nothing of the first account flashes.
9. UX08: clear cache, clear history, delete downloads; Cancel each once first.
10. UX09: flight mode on Search, Library and a collection: the offline sentence with Retry, and content that
    was already shown stays.
11. UX10: add a widget, play, pause, skip from it and from the lock screen; set a 5 minute timer and compare
    the countdown in the player with when playback stops.
12. With TalkBack on: the position bar reads a time, the volume bar a percentage, live radio says why it
    cannot be moved; closing a dialog returns focus to the button that opened it.
13. At font scale 2.0 repeat steps 3 and 6: no clipped controls, transport not covered.

Record the device, Android version, build identity from About, and one line per step.
