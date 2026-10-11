# Export and import of local data (Q05A)

Android backup and device transfer are disabled for Riff. This is the way to carry what Riff keeps on a phone to another install: one file, written and read where the user chooses.

Code: `LocalDataTransfer.kt` (the file, export, import), `LocalDataTransferScreen.kt` (the steps the user sees). Store additions: `addToCollections`, `SearchHistory.importEntries`, `OfflineMusicStore.importMetadata`, `NotInterestedStore.addAll`.

## Where

**Settings → Privacy and data → Move to another phone**: **Export local data** and **Import local data**.

Both use the system document picker. Riff requests no storage permission and can reach only the one document the user picked. Nothing is written anywhere else, apart from a private working copy in the app's cache folder that is removed when the step ends.

## What the file holds

| Owner | Contents |
|---|---|
| This phone | Device settings listed below; items marked **Not interested**; history of audio stored on the phone |
| Signed-out listening | Searches; listening history; download names |
| Each account | Tracks saved in Riff (favorites and the app playlist); recently played; searches; listening history; download names |

The user chooses which owners to include and sees a count for each. The device, signed-out listening and the account signed in are preselected. Other accounts that used the phone are offered but not preselected.

Settings that travel: haptic feedback, solid controls, reduce motion, frosted glass and the two intensity values, audio boost and its level, crossfade and its length, data saver, save listening history, and the history period. Only settings the user changed are written; a default is not.

Never in the file:

- Cookies, tokens, session identifiers, account names or raw account identifiers.
- Stream addresses, audio, image bytes, lyrics.
- Diagnostics.
- The saved queue.
- Automatic downloads, download on mobile data, and the download budget. Importing them could start transfers, use mobile data or make room by removing downloads.
- The storage key of an account. An account's section is named by an opaque digest of its identity (`s2_` and 64 hexadecimal digits), or by its older 32-digit key when this phone never recorded which identity that key belongs to.

Artwork is written as an address only when it is a plain address on one of the provider's image hosts, without a query. Any other address is left out and the default image for the song is used after import. Image addresses of the phone's own library are never written.

A download is written as the song's identifier, name, artist and album. Its files, quality and state are not.

### The file is not encrypted

Before the picker opens, the export step says:

> The file is not encrypted. It contains your listening history and searches, and anyone who has the file can read it. It holds no passwords or sign-in data, and no audio.

## Format

JSON Lines, UTF-8, one record per line, at most 16,384 characters per line and 128 MiB per file. Suggested name: `riff-local-data-<date>.jsonl`.

```
{"riff":"local-data","schema":1,"exported_at":<ms>,"app":"<version>"}
{"t":"owner","kind":"device"}
{"t":"setting","k":"crossfade.seconds","v":9}
{"t":"hidden","key":"<id>","title":"…","subtitle":"…","song":true,"at":<ms>}
{"t":"play","e":"<event id>","id":"local:<n>","title":"…","artist":"…","at":<ms>,"dur":<ms>}
{"t":"owner","kind":"guest"}
{"t":"search","q":"…"}
{"t":"owner","kind":"provider","id":"s2_<64 hex>"}
{"t":"track","list":"favorites|playlist|recents","id":"<video id>","title":"…","artist":"…","artwork":"https://…"}
{"t":"download","id":"<video id>","title":"…","artist":"…","album":"…"}
{"t":"play","e":"<event id>","id":"yt:<video id>","title":"…","artist":"…","at":<ms>}
{"t":"end","records":<n>}
```

Plays are written newest first. The schema number is 1. A later incompatible format gets a higher number.

## Import

1. The chosen document is copied to private storage, up to the size limit, and read through once. Nothing Riff keeps is changed.
2. A preview lists each section, what it holds and where it would go. Records that cannot be read are counted.
3. On **Import** the file is read a second time and stored.
4. A summary states what was added, what was already there, what was skipped and why, and anything that could not be stored.

### A file that is refused whole

| Reason | Message |
|---|---|
| Not a Riff data file (no header, other content, empty) | This is not a Riff data file. |
| Schema number higher than this build knows | This file was made by a newer version of Riff. Update Riff to import it. |
| No end mark: cut short or damaged | This file is incomplete or damaged. |
| Over 128 MiB | This file is larger than 128 MB and was not read. |
| The document could not be read | Riff could not read this file. |
| No room for the private copy | There is not enough free space on this phone to read the file. |

Each ends with "Nothing was changed."

### Records that are skipped one by one

A record is passed over, and counted, when it is not valid JSON, is longer than a line may be, nests deeper than a record does, has an unknown type, comes before any owner or after an owner line that is not valid, does not belong to its owner (a setting outside the device section, a saved track outside an account, a provider song in the device's history or a phone file in an account's), or has a field out of range. Identifiers are checked against the same patterns the stores use; titles are cut to 180 characters and searches refused over 160; a play needs a time between the epoch and one day ahead of now.

Limits per section: 100,000 plays and 2,000 of every other kind. Records beyond them are counted and not read. The stores then apply their own limits: 200 favorites, 200 playlist tracks, 50 recently played, 30 searches, 2,000 hidden items, 100,000 plays per owner.

### Whose data goes where

| Section | Destination |
|---|---|
| This phone | The device's own data |
| Signed-out listening | Signed-out listening |
| An account, and the account signed in now is the same one | That account. Matched by digest, also when its data here is stored under an older key |
| Another account, and a verified account is signed in | Not imported unless the user ticks it and confirms a second prompt. Then it is added to the account signed in now |
| An account, and nobody verified is signed in | Not imported. The preview says to sign in to that account first |

The second prompt reads: "They are added to the data of *owner* on this phone. This part of the file comes from another account, so its plays, searches and saved tracks are included, and they cannot be separated afterwards."

The destination is decided again when the import runs. If the account changed while the preview was open, the section is left out.

### Import adds and never removes

- History: a play whose event identifier is already kept for the owner is not added twice. No more plays are added than the owner may keep, so nothing already there is pushed out. Plays older than the history period in force are not added.
- Saved tracks and recently played: what is already there stays first and in order; a track already in a list is not added again; a full list takes no more.
- Searches: the owner's own stay first; duplicates are ignored without regard to case.
- Downloads: each becomes a row holding the song's name. Nothing is queued or fetched. A song already listed is left exactly as it is.
- Hidden items: entries already there are kept; none is dropped to make room.
- Settings: written as they are in the file, with one exception. A history period shorter than the one in force, or any period when plays are kept until cleared, is not applied, because it would remove plays. The summary says so.

Nothing imported is played or queued, and nothing is sent to YouTube Music.

### One step per kind of data

History is written in one database transaction while the file is read. Saved tracks, searches, downloads and hidden items are each one write. Settings are one write per settings file.

- **Cancel** is honoured until the history transaction commits. Until then nothing is changed. After it the import runs to its end, also if the screen is left.
- A failure while the history is written rolls it back; the import stops and nothing is changed.
- A failure in a later kind leaves that kind as it was, is named in the summary, and does not stop the kinds after it. Importing the same file again completes it without adding anything twice.

Export and import run off the main thread and share one lock with the clear and delete actions of this screen, so none of them runs alongside another.

## Export

The file is put together in private storage and copied to the chosen document only when it is complete. A cancel, a failure or a file over the size limit before that leaves the document untouched; the empty document is then removed. If the copy itself fails, the export is reported as failed and the document is removed.

## Events

```
local_data action=export result=done records=<n> | cancelled | too_large | failed kind=<word>
local_data action=import_open result=ready sections=<n> records=<n> malformed=<n> | rejected reason=<word>
local_data action=import result=done|partial added=<n> present=<n> malformed=<n> over_limit=<n> expired=<n> left=<n> not_stored=<n> | cancelled | rejected reason=<word> | failed kind=<word>
```

Counts and fixed words only. No digest, title, search or file name.

## Verification

JVM, Robolectric (`LocalDataTransferTest`, 22 tests): round trip to a clean install; downloads as names only; the file scanned for credentials, stream addresses, storage keys and any address that is not a plain image address; chosen owners only; another account's section left out, added on request, and unavailable when signed out or unverified; the same account under an older key; the account changing during the preview; files refused whole (not a Riff file, newer, too large, unreadable); a file cut at every 97th byte; 25 kinds of unreadable record; the same file twice; limits without pushing out what is there; the history period; a section over its limit; cancel before and after the first write; a full disk while copying, while writing history and in a later kind; an export that fails, is cancelled or is too large; events.

JVM, Compose (`PrivacyDataScreenTest`, 5 tests): the export step shows owners, counts and the warning before anything is created; backing out writes nothing; the preview and the summary of an import; the second confirmation for another account's data; no offer without a verified account; refusal messages.

Emulator, real stores, network off, synthetic identities (`LocalDataTransferDeviceTest`, 2 tests): export, delete the data, import, compare, with nothing queued or played; another account's file, damaged files, and adoption on request.

Not verified here:

- The system document picker itself: creating a document, picking one, and removing an empty one. The tests replace the picker. Needs a person at a device or UI automation.
- A file moved between two physical phones.
- A file near the size limit on a phone. The limit is exercised with a lower value in tests.

## Known limits

- The file is not encrypted and not signed. Anyone who has it can read it, and a file that was edited is imported like any other, within the checks above.
- Imported download names are not shown on the Downloads screen and are not downloaded. They are a record only; the songs must be downloaded again by the user.
- History of audio stored on the phone names a file by the number the phone gave it. On another phone that number can belong to a different file. History now replays such an entry only when the file's title also matches; otherwise it says the file is no longer on the phone.
- The digest that names an account is the same on every phone for the same account. Someone who has the file and already knows an account's identifier can check whether a section belongs to it.
- The saved queue, lyrics, caches and podcast progress are not exported.
- While a large import writes history, a play that ends at that moment waits for the import before it is recorded.
- A document provider that does not allow deletion keeps the empty document after a failed export.
- Leaving the screen during an export stops it; the empty document stays.
