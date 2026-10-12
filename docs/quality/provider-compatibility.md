# Provider compatibility (Q10)

Riff reads YouTube Music through one pinned library and its own parsers. This file records what that depends on, what is tested without the provider, what has and has not been checked against the provider, and what to do when the provider changes.

## Dependency

- `com.github.MetrolistGroup.innertubex:innertubex:0.7.2` (`gradle/libs.versions.toml`). Requests are made as the `WEB_REMIX` client. The version is exact, not a range.
- The library builds and signs the requests. Riff's own code parses every answer (`ProviderCatalog.kt`, the parsers in `YouTubeMusicProvider.kt`, `SessionHealth.kt`, `PlaylistEditing.kt`, `PlaylistSortRequest.kt`).
- Changing the version means rerunning everything under "Tests" and the live checks under "Live result".

## Interfaces

`ProviderContracts.kt` names the four things the app asks of the provider. `YouTubeMusicProvider` implements all four; nothing else does.

| Interface | What it covers |
|---|---|
| `ProviderSession` | signed-in state, session state, account scope, storage scope key, session check |
| `ProviderCatalogReader` | search, browse, whole collection, library, Home, Up next, video pairing, lyrics, profile, like state |
| `ProviderMutations` | like, save to library, add to and remove from a playlist, sort preference, "not interested" |
| `StreamResolver` (existing) | stream resolution |

## Assumptions

Each of these is a reading of the provider's answers. None is a published contract. The ones marked *not live-checked* come from the parsers and from the library, not from an answer observed in this package.

| Assumption | Where | Status |
|---|---|---|
| A playlist edit succeeded only if the answer is JSON with `status: "STATUS_SUCCEEDED"` and no `error` | `playlistEditOutcome` | carried over from earlier builds |
| A like or a library save succeeded only if the answer is a JSON object with `responseContext` and no `error` | `likeOutcome` | *not live-checked.* If the provider ever answers a successful like with a body that has no `responseContext`, the like is read back from the watch queue and still ends correctly; the cost is one extra request |
| HTTP 4xx other than 408 means the request was refused and not applied | `playlistEditOutcome`, `likeOutcome` | *not live-checked* |
| HTTP 408 and 5xx, a timeout, or a connection dropped after sending may have been applied | same, `transportOutcome` | conservative by design |
| A request that could not connect (`UnknownHostException`, `ConnectException`) was not applied | `transportOutcome` | follows from never having been sent |
| A playlist read straight after an edit shows that edit | read-back in `playlistMutation` | *not live-checked.* If the provider is slow to show an edit, a read-back can report "not saved" for an edit that then appears; trying again would add the song a second time. See "Known limits" |
| An error can arrive inside HTTP 200 as `{"error":{"code","status"}}` | `providerEnvelopeError` | the 401 `UNAUTHENTICATED` form was captured on a device on 3 October 2026 (see `SessionHealth.kt`); other codes are *not live-checked* |
| A sort menu entry is a sort only if its command is `ACTION_SET_PLAYLIST_VIDEO_ORDER` with a known order and nothing else | `validPlaylistSortAction` | carried over |
| An official video is one the provider marks `MUSIC_VIDEO_TYPE_OMV` | `musicVideoPair` | carried over |
| No answer Riff reads says that something is unavailable in the user's country | `classifyProviderFailure` | so nothing is called a region restriction |

## What the user is told

`classifyProviderFailure` sorts a failure by what was observed. The sentence is Riff's own; the provider's text, the address and the status line are never shown.

| Observed | Class | Sentence |
|---|---|---|
| no route, DNS failure | Offline | You're offline. Check your connection and try again. |
| timeout | Timeout | (the existing timeout sentence) |
| HTTP 401, or `UNAUTHENTICATED` inside 200 | Auth | handled by the session check; the session-expired sentence |
| HTTP 429, or `RESOURCE_EXHAUSTED` | RateLimited | YouTube Music is limiting requests. Try again shortly. |
| HTTP 404 or 410, or `NOT_FOUND` | NotFound | This is not available on YouTube Music. |
| HTTP 403, or `PERMISSION_DENIED` | Refused | YouTube Music refused this request. |
| HTTP 5xx | Provider | YouTube Music is having trouble. Try again later. |
| anything else | Unknown | YouTube Music could not load this content |

"Not available" does not say why. Riff cannot tell a removed item from one withheld in a country, so it does not guess.

## Changes sent to the provider

Every change ends in one of three outcomes (`MutationOutcome`).

| Outcome | Meaning | What happens |
|---|---|---|
| Acknowledged | the provider said it was done | reported as done; cached copies of what changed are dropped |
| Rejected | the provider refused, or the request never left | reported as failed; sending again is safe |
| Unknown | sent, with no answer that says either | settled by reading, below |

- **Add to or remove from a playlist.** On Unknown the whole playlist is read. If the edit is there, it is reported as done. If it is not, it is reported as not saved and may be tried again. If the playlist cannot be read, or is too large to read whole and the edit is not in the part that was read, the result is "YouTube Music did not confirm this change. Riff will check before trying again." The edit is remembered for that account generation; the next attempt at the same edit reads the playlist first and sends only if the edit is still absent. An edit is never sent a second time on a guess.
- **Like.** On Unknown the song's like state is read from the watch queue, and that decides. If it cannot be read, the result is unconfirmed and the heart is not changed.
- **Save to library.** Saving twice is the same as saving once, so nothing is read; an Unknown save is reported as unconfirmed and safe to try again.
- **Sort preference.** Sent once, never retried, and only with the command and parameters the provider itself offered for that playlist.
- **Several playlists at once.** One result per playlist. Those that succeeded are not sent again; a retry covers only the rest, and each unconfirmed one is read first.
- **Before any add**, the playlist is read whole. A read-only playlist is refused; a song already present is left alone; a playlist too large to read whole is refused, because the song could be past the part that was read.
- **Allowlists.** Only `VL` playlist ids other than Liked Music (`VLLM`) and episodes (`VLSE`), and only 11-character video ids, are ever sent. The account generation is captured before sending and checked after; an answer for an account that has since changed is not a success.
- Dates, vote counts and sort orders are never inferred. A sort the provider did not offer is not shown.

## Collections

- A collection is read page by page up to **2,000 tracks** and **64 pages**.
- A page token is followed once. A token seen before, two pages in a row that add no new entry, or the page limit ends the read.
- A list that is not the whole collection is returned with `CatalogPage.truncation` set and is shown with a label: "Showing the first 2,000 songs. This collection has more than Riff can load." or "YouTube Music stopped sending new songs. This list may be incomplete."
- On a truncated list Play, Shuffle, Download all and Sort are disabled. Tapping a song plays from the part that is loaded. Earlier builds failed a collection over the limit with "This collection exceeds the supported 2,000-song queue" and showed nothing, and presented a list cut short by a repeated token as if it were whole.
- Search "Load more" and Up next follow the same once-only token rule (`PageTokens`).

## Capabilities

`ProviderCapabilities` (session-wide) and `CollectionCapabilities` (one collection) say whether an action is available, loading, or unavailable with a reason. The collection screen and the like buttons read them; the reason shown is the capability's own.

| Session | Browse, play | Library | Like, save, edit, feedback |
|---|---|---|---|
| signed out or guest | available | sign in | sign in (one sentence per action) |
| valid, verified | available | available | available |
| being checked | available | available | loading |
| expired | available | session expired | session expired |
| signed in, account not verified | available | available | cannot verify this account |

Playback is unavailable, with a reason, only when no resolver is ready.

## Cached provider data

All of it is in memory only. Every key begins with the account generation, so a page read for one account is never served to the next.

| Cache | Lifetime | Dropped when |
|---|---|---|
| Home, moods, profile, video pairing (`feedCache`) | until the next 06:00 or 18:00 local time, or a time-zone change | sign-in, sign-out, account switch |
| Collections and library lists (`collectionCache`, 96 entries) | the process | sign-in, sign-out, account switch; one playlist when it is edited or sorted, before the request is sent and again after; library lists when the library changes |
| Search pages (24) | 5 minutes | sign-in, sign-out, account switch |
| Stream addresses (32) | until one minute before the address expires; never for live | sign-in, sign-out, account switch; one song when its stream is rejected; all when attestation is renewed |
| Like state | the process | sign-in, sign-out |

A load that is in flight when its entry is dropped is cancelled and cannot put its result back (`PageMemoryCache.invalidate`, `PageMemoryCache.get`). Stream addresses are never written to disk.

## Tests

JVM and Robolectric, no network, no account.

- `ProviderFixtureTest` (25): what the parsers make of each kind of answer; the three change outcomes for every body and status; failure classes and sentences; unknown renderers and fields of the wrong type; the fixtures' own digests and a scan for credentials, account values, signed addresses and real-looking ids.
- `ProviderContractTest` (24): `YouTubeMusicProvider` itself on the pinned library, with the HTTP transport replaced so that every request is recorded and answered by the test. Collection paging, a repeated token, pages going round under new tokens, the page limit, the 2,000-track limit, an error inside 200, each HTTP status and how often it is retried, an answer arriving after the account changed, each search kind, profile, video pairing, Up next, like state, lyrics, and the cache rules.
- `ProviderMutationTest` (18): each outcome for add, remove, like, save and sort, including the unconfirmed add that is found present, found absent, and unreadable; allowlists; a batch and its retry.
- `ProviderCapabilitiesTest` (11): every session state and scope; a whole, loading, truncated and read-only collection.
- `CollectionTruncationUiTest` (3): the label is on screen and the whole-collection buttons are disabled.

### Fixtures

`app/src/test/resources/quality/provider/` holds 30 JSON files and `index.json` with their SHA-256 digests.

**They are invented, not recorded.** `scripts/quality/generate_provider_fixtures.py` writes them in the shapes the parsers read. Every id, name, token and address in them is made up (`fxsong00001`, `UCfixtureArtist00000001`, `listener@example.invalid`, `https://lh3.googleusercontent.com/fixture-...`). They hold no credential, no account value and no signed address, and a test fails if one appears. They show what Riff does with an answer of a given shape. They do not show that YouTube Music sends that shape today.

| Area | Fixtures |
|---|---|
| Search | songs (two pages), videos, albums, artists, playlists, podcasts, mixed with filter chips, empty |
| Home | two pages, with a mood and a podcast filter |
| Album | one |
| Playlist | owned (three pages, both continuation forms, sort menu with one entry that is not a sort), owned with the same song twice, read-only |
| Library | playlists including Liked Music |
| Account | menu, signed-out error |
| Watch queue | with an official video, with a fan upload only |
| Lyrics | timed, plain |
| Changes | edit succeeded, edit failed, like acknowledged |
| Errors | 403 and 404 inside 200 |
| Change in the provider | unknown renderers, fields of the wrong type, a page token that is not text |

To change a fixture, change the generator and run it; the digest test fails on a hand edit.

## Live result

**None in this package.** Q10 was built and tested with the network off and no account. Nothing above was checked against YouTube Music, and the fixture tests are not a substitute.

Earlier dated observations that this package relies on: the account-menu shapes for a valid and an expired session, captured on a device on 3 October 2026 (`SessionHealth.kt`).

To be run on a dedicated test account when one is authorized, and recorded here with the date, the build, and pass or fail for each:

1. Search, each of the six kinds, and "Load more".
2. Home, and a second page of it.
3. An album; a playlist of more than 100 songs (continuation); a playlist of more than 2,000 (the label and the disabled buttons).
4. An owned playlist and someone else's: which is editable.
5. Playback of a song, and the switch to its official video.
6. Like and unlike a song, and what the answer body contains (the `responseContext` assumption).
7. On a disposable playlist: add a song, remove it, change the sort, and how soon a read shows each (the read-back assumption).
8. Save and remove an album.
9. With the session expired: what a read and a change answer.

## When the provider changes

1. Find which class of failure users see (`ProviderFault`), and whether it is every request or one kind.
2. Every kind fails with Auth: the session. With Offline or Timeout: the network. Nothing more to do here.
3. One kind returns nothing, or less than it should, with no error: a renderer has changed. The parsers skip what they do not recognise, so the symptom is missing rows, not a crash. Capture one answer on the test account, remove everything listed under "Fixtures" as never stored, reduce it to the fields the parser reads, and add that shape to the generator with a failing test before changing the parser.
4. Changes come back unconfirmed although they were applied: the acknowledgement has changed. Check the two assumptions about answer bodies above.
5. Collections end early with "stopped sending new songs": the continuation form has changed.
6. Check whether a newer version of the pinned library addresses it. Change the version only together with a rerun of the tests and the live checks.

## Known limits

- The read-back after an unanswered edit trusts the provider to show a new edit at once. This has not been measured.
- An unconfirmed edit is remembered in memory for the account generation. After the app is restarted it is not remembered; the check made before every add (the playlist is read and a song already there is not added) still applies, but a remove is sent again, which is harmless if the entry is already gone.
- Radio, podcast and live-station answers have no fixtures here.
- A collection of more than 2,000 tracks cannot be played, shuffled, downloaded or sorted as a whole.
- "Not interested" is reported as sent on any answer below HTTP 400; its body is not read.
