# Owner's bug batch, 5 November 2026

Eight bugs the owner reported from the phone. Paths are relative to `app/src/main/java/com/musicglass/personal/`.

Unit tests cover the logic below. The phone checks on 5 November are listed under "Checked on the phone", and "Still to check" says what is left.

## What was wrong, and the change

| Bug | Cause | Change |
|---|---|---|
| Search leaves out the actual song ("all the stars", "slipping through my fingers abba", "everything is romantic") | Not reproduced. Signed out, "all the stars" has the song first and "everything is romantic" lacks Charli's. The owner's signed-in answers could not be captured. | An "All" search also asks the Songs filter. The first of its top three rows holding every word of the query goes first (`searchWithBestSong`). An artist or album that is exactly what was typed keeps the top. |
| Encore's artists all show the placeholder | A play records no artist picture: `encoreRows` writes artist rows with no artwork. On the owner's phone all 19 artists had none, while albums and songs all had one. | `EncoreArtistPhotos.kt` finds each top artist's photo and saves it with the artist's rows. Until then, or if there is none, the row shows the cover of the artist's top song. |
| An artist listed twice (ABBA, 19 m and 11 m) | An artist's first credit is keyed by channel and later credits by name, so one artist had two keys. | Top lists, counts, rank movement, first-heard days and an artist's history treat one name as one artist. The stored rows are unchanged. |
| Encore's pill sits low and left | The pill's layer could read the lens before it was placed, which is the bar's left edge and halfway down, and nothing told it to read again. | The lens is placed during composition, not in an effect (Encore's control and the tab bar). A bar that changes size takes a resting pill with it. |
| Title and artist invisible on an ultra-white cover | White words over a near-white backdrop. | Adaptive fade, the owner's choice over a shadow. See below. |
| The queue on an ultra-white cover | The same. | The same measure darkens the whole panel behind the open queue. |
| Sleep timer: order, scrolling, corners | The list opened with the song options, nothing showed that it scrolls, and the boxes used the default corners. | Minutes come first. The list stops at four and a half rows, with a scrollbar that stays and a fade at any edge with more. The chips and the custom field have rounder corners. |
| "Everything Is Romantic (feat. caroline polachek)" shows the original's lyrics | Better Lyrics, QQ and Kugou answer that title with the original and give no score. LRCLIB has the remix under the exact title. | See "Lyrics for a title naming a guest". |
| QQ's "Ribs - Lorde (洛德)" shown as a lyric | QQ opens some songs with a timed heading, and the old check only compared the whole line with the title. | `lyricsIsTitleLine` drops a line in the first 15 s that reads title, dash, artist. Saved copies lose it when read. QQ keeps its rank, as the owner asked. |

## Adaptive fade

The measure is the lightest cell of the backdrop's mesh: the lower half behind the title, and all of it behind the queue. `backdropScrimAlpha` turns that into a black layer:

- Up to relative luminance 0.35 there is no layer, so ordinary covers are drawn as before.
- From 0.35 to 0.6 the layer ramps up, so nothing jumps.
- From 0.6 on the layer brings the backdrop down to luminance 0.10. A pure white cover gets about 65% black, and white words on the result are above 4.5:1.

The layer is drawn in the backdrop, under the art. The art itself is unchanged (Q18-002), and only what it fades into gets darker. The player's layer starts 8% of the panel above the seam, so the star, the menu and the faint buttons are covered too.

## Lyrics for a title naming a guest

This applies only when the title has feat., ft. or featuring. Ordinary songs rank as before.

The Better Lyrics endpoints count as unchecked sources. LRCLIB, NetEase, LRC Red and YouTube Music count as checked. When one of each disagrees, the checked source wins and word timing is given up for that song.

They disagree when neither set of lyrics shares at least 70% of its words with the other. Measured on 5 November, a small sample of six songs:

| Song | Share of words |
|---|---|
| The remix (different words) | 0.60 |
| Dracula | 0.87 |
| Are You Bored Yet | 0.91 |
| Fortnight | 0.81 |
| LOVE | 0.92 |
| 30 For 30 | 0.84 |

Checked sources keep running for such a song while an unchecked answer is shown. A saved answer from an unchecked source is compared once in 12 hours.

## Choices worth knowing

- **Signed-out search for photos:** an artist known only by name is looked up with a signed-out search, so Encore never adds to the account's search history. A channel page is read signed in, as the rest of the app does, and it writes nothing.
- **Same name, same artist:** two different artists with one name now share a row in Encore.
- **Lookup limit:** a page looks up at most 20 artists, one at a time, and asks about each artist once per run.
- **Corners:** the owner asked for corners rounded like the outer box. The chips have half the dialog's radius (14 dp) and the field 70% (about 20 dp), in proportion to their height. The owner may want these changed.

## Checked on the phone (Debug build, 5 November)

- **Player and queue:** on BRAT's bright green cover, the title, buttons and queue rows read over the darkened backdrop and the art is unchanged. A white cover was seen only in passing, in the player (below), and with its lower half still fading in. BRAT counts as bright enough to darken, so the owner should judge whether that is too much.
- **Sleep timer:** minutes come first, the half row and scrollbar show, the list scrolls to 90 minutes, and the corners are rounder.
- **Encore:** four of the top five artists show their own photos. The fifth, arentertainments, shows a song cover. No artist is listed twice in that top five. The screen's "Nothing leaves it" sentence was no longer true, so it and `docs/privacy.md` now mention the photo lookup.
- **Pill:** right in all 34 screenshots across three rounds of choosing each period, scrolling far down and back, and leaving and re-entering. The old bug was never reproduced for comparison, so this supports the fix without proving it.

- **Lyrics:** after the owner played "Everything Is Romantic (feat. Caroline Polachek)" on this build, its saved lyrics are LRCLIB's remix, opening "Fall / Fall in".

A mistake in this check: the pill script assumed it was on Library. When it drifted off Encore, its taps landed on Home's Speed dial and started music in Debug, with sound, for under a minute. They added "Guess" (22 s) and "Rewind" (31 s) to Debug's history and Encore, and changed Debug's queue. Nothing was liked or saved. Debug was then paused.

## Still to check on the phone

- **Search:** whether the Songs answer signed in has these songs, and whether the jump to the top feels wrong. The cause is not confirmed, so this is the least certain fix here. The owner runs this one, because it goes through their signed-in account.
- **Player and queue:** a settled white cover, and a light but ordinary one.
- **Lyrics:** "Ribs" on the phone.

## Second round (5 November, later)

The owner tried the build above and asked for six things; "ribs and search is fine" closed one.

| Request | What changed |
|---|---|
| Too dark on BRAT and some off-white covers | The player's darkening now needs the backdrop's lower half to be white or off-white (`backdropIsWhite`). The owner chose to count off-white as white, as a precaution. The queue is as it was |
| Gradient too weak on white covers | It runs from 26% to 54% of the panel with eased stops, in place of a shorter ramp |
| Glitch dragging a queue row | `reorder()` used to move the song in the player on every step, racing the list and its scroll anchor. Now the drag changes only the order on screen, and one `moveQueueItem` is sent when the song is let go. That alone was not enough: on the phone, the list still ran on after release by about the distance dragged (roughly 800 to 950 px in 300 ms, with the list reporting a scroll in progress), taking the song off screen. The list's own touch scrolling is now off until the move lands, its position is pinned at the handover, and a song dropped at the top edge stays in sight. What started that scroll was not identified: a focus test ruled out focus, and turning the scrolling off removed it |
| Remove Play next | Gone from every menu, row action and string |
| Shuffle once, reshuffle button | `QueueOrder.kt`. The list is the play order, with `UnshuffledShuffleOrder` under the player's shuffle flag. Reshuffle keeps the song playing and shuffles all the others |
| Outside song plays next | After the current song and after earlier added songs, until the user moves songs or reshuffles |

## Choices worth knowing, second round

- **Add to queue used to add at the end.** It now means "next, after what you added before" everywhere, not only in a shuffled queue, because it replaces Play next.
- **Turning shuffle off** brings back the order from before shuffling, with added songs after the current one. That order is kept in memory only: after Android ends the process, turning shuffle off keeps the current order.
- **Reshuffle includes songs already played**, since the owner asked for the entire queue. They can play again.
- **Radio songs** added by recommendations join a shuffled queue at the end.

## Checked, second round

Unit tests only (`QueueCoordinatorTest`, `PlayerBackdropScrimTest`, `AccessibilitySemanticsTest`, `EnqueueNoticeTest`). On the phone, paused and silent, with scripted swipes rather than a finger:

- **Drag:** recorded up to the top edge with auto-scroll and down to the bottom edge. No doubling beyond the dragged row passing over its neighbours, no run-on, and the song lands where dropped.
- **Reshuffle while paused:** same song, same position, a new order each press, and the list returns to the top.
- **Play next:** absent from the queue's song menu.
- **BRAT in the player:** no added darkening. Its lower half is 60% of the top colour (83/139), which is the older 40% gradient.

Checked later on the phone, also scripted and with the speaker at volume 0, so judged from the player's state and the screen rather than by ear:

- **Reshuffle while playing:** the song ran on without a break (43.9 s, 45.2 s, 50 s), songs already played came back, and each press gave a new order.
- **Covers:** white Escapism and the near-white Agora Hills and Spring breakers get the soft darkening. A dark cover and a pink cover don't. No true cream cover turned up in the queue, so cream rests on the unit test.
- **Shuffle from a playlist:** Liked Music (10 songs, 5 unavailable) kept the same order across three skips.
- **Outside songs:** three added songs sat right after the current one, in the order added. After moving a song to the top, a new one went straight next.
- **Shuffle off:** added songs right after the current one, then the original order, with the song not interrupted.
- **Play next:** also absent from an album row's menu.

Still for the owner: a real finger drag, and judging the white gradient by eye. The title sits at the lightest part of the gradient.

Verification: `python scripts/quality/verify.py --lane pr`, 5 November, `pr-20261105T154737Z`: **PASS_LOCAL**, all nine gates, 1,577 JVM tests with no failures or skips.

Speed option 1, timing Riff's own start steps and trimming the slow ones: done, in section 10 of `playback-startup-warm-resume-2026-11-04.md`. The lane after all of it: `pr-20261105T190026Z`, **PASS_LOCAL**, 1,586 JVM tests.

A note for anyone running tests on Windows: `:app:testDebugUnitTest -PriffSideBySide=true` fails about 7 database tests with "Can't upgrade read-only database". The longest test names pass the 260-character path limit; this is recorded in the execution ledger. Run tests without the property, and use it only for phone builds.

## Search, third look (6 November)

The owner saw the originals missing again: no ABBA for "slipping through my fingers", no BØRNS for "electric love", no Tears For Fears song rows. A Debug-only probe logged the rows YouTube sent, then was removed.

- Riff's parser and screen keep every row YouTube sends; nothing is filtered after it.
- On the account the owner was using, the signed-in Songs answer itself left the originals out. Signed out, from the same phone and code, BØRNS was near the top. The official app on that account did show them.
- Changes that did not help: the newer filter codes from YouTube Music's own buttons, sending the phone's country (`gl=IN` instead of the library's default `US`), and searching without the account id or the visitor id. All were taken back out.
- The owner's main account gets the right songs every time in Debug. It is that one account's ranking, not Riff, and nothing in Riff changed.

Left open: why the web client's signed-in ranking on that account differs from the official Android app's. Riff sends the library's default `gl=US` on every request; that was not the cause here.

Verification after the probe was removed: `pr-20261106T004612Z`, **PASS_LOCAL**, all nine gates, 1,586 JVM tests.

## Third round (6 November)

| Owner's report | Change |
|---|---|
| The white-cover darkening is a little too dark | The player's darkening is 75% of what the queue's would be (`BACKDROP_DECK_SCRIM_SHARE`). The queue's is unchanged |
| Dragging up still glitches; dragging down doesn't. Try: scroll down some, then drag a song to the very top | See below |

**Drag, cause.** Screenshots every few steps of a slow scripted drag (`input motionevent`), with temporary logging, showed it. At the top edge the song took the first row on screen each step while the list scrolled up. Keeping the list's place then pinned it to the song, which sat about 90% above the top, and the list shifted 10 to 50 px each step. The song lost its highlight and its place under the finger, and it landed rows short of the top. Dragging down never changes the first row, so it never did this.

**Drag, change.** While the list can still scroll up, the song stops at the second row on screen (`queueDragTarget`), so the row above it keeps the list's place, as when dragging down. At the very top it takes the first row. The dragged row is also kept whole inside the list at both edges (`queueDraggedTop`).

**Checked on the phone, scripted, paused:** from about row 33, held at the top edge, the list scrolled up under the song, which stayed whole and landed first. Dragging down to the bottom edge still scrolls and drops. Unit tests: `QueueDragTargetTest`. Still for the owner: the same with a finger.

Verification, third round: `pr-20261106T015329Z`, **PASS_LOCAL**, all nine gates, 1,593 JVM tests.
