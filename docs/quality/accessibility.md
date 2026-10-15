# Accessibility and text (Q13)

What is checked, how, and what still needs a person with a phone. Nothing here is a certification: no audit
against WCAG or any other standard has been done, and none is claimed. The figures below are the app's own
targets, checked by its own tests.

## What is checked on every build

| What | How | Where |
| --- | --- | --- |
| Touch targets | Every enabled pressable node takes presses in at least 48 x 48 dp, and a small control that borrows space does not share it with a neighbour | `AccessibilitySweepTest` and its two siblings |
| Labels | Every pressable node says what it is; nothing pressable is outside the screen | same |
| Text sizes and windows | 15 screens x font scale 1.0, 1.3, 1.6, 2.0 x left-to-right and right-to-left, on a 360 x 640 dp phone, the same phone on its side, and an 840 x 900 dp window | same |
| Longer text | The same 15 screens with every string a third longer and marked; the layout checks run again, and any words on screen that are not marked fail the test | `PseudoLocaleSweepTest` |
| Queue without dragging | Each row offers Move up, Move down, Play next and Remove as actions, only where they change something, and says its position ("3 of 6", "Playing now, 2 of 6") | `AccessibilitySemanticsTest` |
| State | Like says Liked or Not liked and keeps one name; exactly one tab is selected and each is a tab; the seek bar says its position and length | `AccessibilitySemanticsTest`, `PlayerControlSemanticsTest` |
| Keyboard | A tab and a filter chip answer Enter and Space | `AccessibilitySemanticsTest` |
| Reduced motion | Motion is reduced by the app's setting or by the system animator scale being 0; reduced motion makes every timed animation instant | `AccessibilitySemanticsTest` |
| Touch feedback | Follows the app's setting, the system setting and whether the app is in front; every cue has a constant that exists on the oldest supported phone (the threshold cue falls back below API 34) | `AccessibilitySemanticsTest` |
| Contrast | See below | `ContrastTest` |
| Text comes from resources | See below | `StringResourcesTest`, `PseudoLocaleSweepTest`, the `strings` static check |

The sweep draws real screens off-device with synthetic content (Robolectric, native graphics, so text is
measured at its real size). Screens in it: search results, search failure, Library signed out, Settings,
Account, Downloads, Not interested, a collection, the player, the queue, the mini player, position and
volume, lyrics timing, the session banner with problem states, and the tab bar.

### Pictures

`AccessibilitySweepTest` saves each screen at 100% and 200% text in both directions, and
`PseudoLocaleSweepTest` saves each with the longer text, to `app/build/reports/q13-screens/` (90 PNG files).
They are for a person to look at. They are not compared with a baseline, and they are drawn without the
glass shader and without real artwork.

## Contrast

Targets: 4.5:1 for ordinary text, 3:1 for large text (24 sp, or 19 sp bold) and for controls that have to be
seen to be used. The ratio is the WCAG 2 relative-luminance formula (`contrastRatio` in `DesignTokens.kt`).

The method is arithmetic, not a measurement of pixels: `ContrastTest` works out the colour that ends up
behind the words from the layers the screen draws, for a white picture (the worst case), a black one and
ten loud ones (yellow, cyan, green, magenta, red, blue, grey, pink, cream, slate), and holds the result to
the target.

| Place | What is behind the words | Figure |
| --- | --- | --- |
| A screen coloured from its artwork (queue, collection, Downloads) | 48% of the artwork colour, darkened further until Pink reaches 4.5:1 | Pink, Muted, White and White at 60% all at least 4.5:1; the drag handle (White 45%) at least 3:1 |
| White words over a picture | Black at 58% | 4.5:1 needs 55.1% |
| Muted words over a picture (Home over a photograph) | Black at 74% | 4.5:1 needs 73.2% |
| A white icon over a picture (back; the player's close button, on a shade at 46% across the top fifth of the artwork, not a patch of its own) | Black at 46% | 3:1 needs 43.5% |
| Title block over a cover (collection, Downloads) | The cover's tone at 90% | title at least 3:1 (it is 28 sp), artist and description at least 4.5:1 |
| Speed-dial tile title | A 42 dp gradient, 62% black by the title's top edge | at least 4.5:1 over a white cover |
| Ambient backdrop | Every palette, at 441 points of an upright and a sideways phone | Muted and White at least 4.5:1, Pink at least 3:1 |
| High-contrast controls (solid fallback) | Ink, Raised and the chip colour with nothing showing through | White, Muted and Pink at least 4.5:1 |

Each scrim is close to the least that will do, and the test fails if one is made 5 points shallower.

Not covered by arithmetic: the glass bars and chips over moving content. What is behind them is whatever
the screen is showing, bent by a shader. The **High-contrast controls** setting replaces them with solid
surfaces, and that is what is tested. The glass itself is on the device checklist.

## Text

Every sentence and label the app shows or speaks is in `app/src/main/res/values/strings.xml`: 733 strings
and 44 plurals, English only. No translation has been written, and none is claimed. The code reads them
through `string()` and `quantity()` in `Strings.kt`, which use the application's resources, so text made
outside a screen (a notification, an error from a repository) follows the language too.

- Words the code compares or looks things up by (a tab, a filter, a menu action) stay literals in the code;
  `keyLabel()` gives the text to show for one. `KeyLabels.kt` lists them.
- A debug build includes the pseudo-locales `en-XA` (longer, accented) and `ar-XB` (right to left), for
  looking at by eye on a phone.
- `StringResourcesTest` holds the text that plain JVM tests read from the file to what Android returns, for
  every string and plural.
- The `strings` rule in `scripts/quality/static_checks.py` fails the build's static check when a quoted
  literal with a word in it is handed straight to `Text`, a content or state description, a notification
  title or a notice.

Known limits:

- Some sentences are still assembled from parts in an English order: a counted phrase placed inside another
  sentence ("3 plays and a queue of 2 songs from before this update are not assigned to an account"), lists
  joined with "and", and lower-cased labels inside a sentence. Each part is a resource, but a translator
  cannot reorder across them.
- Names are not translated: YouTube Music, Riff, radio station names and their genres, lyrics providers.
- Diagnostics, log lines and text that comes from YouTube Music are as they were.
- The sweep covers 15 screens. Home, Lyrics, Privacy and data, Export and import, History and the radio
  and podcast screens are covered by the static rule and by their own tests in English, not by the
  pseudo-locale pass.

## Device checks: BLOCKED

These need a phone or an emulator with TalkBack and a keyboard, and a person to listen and look. None was
done: no phone was connected, and installing on one needs approval. No result is claimed.

To do them: install the debug build (do not uninstall the main app), sign in with a test account that holds
nothing personal, and record the screen with sound.

TalkBack on, swipe through each journey in `core-journeys.md`:

1. UX01 first launch and sign-in: every control is reached once, in reading order; the sign-in page can be
   left with Back.
2. UX02 search, play, queue: results are announced; a row is one stop with its actions in the actions menu.
3. UX03 filter, sort, play: the chosen chip says selected; the sort menu says the current order.
4. UX04 download: each phase is announced once when it changes, not continuously.
5. UX05 mini player, full player, queue: opening the player moves focus into it; closing returns focus to
   the mini player. In the queue, the actions menu offers Move up, Move down, Play next and Remove, and the
   row says its position. Playback progress is never read out on its own.
6. UX06 lyrics: the timing dialog is reached, its value is read, and it can be left.
7. UX07 account switch: the confirmation is read; focus lands somewhere sensible afterwards.
8. UX08 deletion: scope and consequence are read before the confirm button.
9. UX09 network and session errors: the message and its action are read once.
10. UX10 widgets, lock screen, sleep timer: widget buttons are named; the sleep timer dialog's field can be
    reached, filled and left. This dialog is not in the automated sweep (a text field inside a dialog never
    goes idle under Robolectric).

Keyboard (a physical or emulator keyboard):

1. Tab reaches every control on Home, Search, Library, Settings, the player and the queue; focus is visible.
2. Enter and Space activate the focused control.
3. Back or Escape closes a menu, a dialog, the queue, then the player, then leaves the screen.
4. Focus never gets stuck: from any control, Tab eventually reaches the tab bar.
5. With the on-screen keyboard open in Search and in the sleep timer dialog, the field and its action stay
   visible.

Also by eye: the glass bars over bright and busy content with High-contrast controls off; `ar-XB` and
`en-XA` chosen as the phone's language; the phone's font size at its largest.
