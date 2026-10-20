# Privacy and data controls (Q05)

What Riff keeps on the phone, who it belongs to, and how the user removes it. Nothing described here sends a request to YouTube Music: every action changes only what is stored on this phone.

## Where

**Settings → Privacy and data**. The History screen also has **Clear** for the list it is showing.

Each row names the owner it acts on (the account's name, **Signed-out listening**, or **This phone**) and shows a count before anything is removed.

| Control | Acts on | Removes |
|---|---|---|
| Save listening history | Whole phone, labelled as such | Nothing. Off stops new plays being written from the next tick of the player; plays already kept stay until cleared |
| History retention | Whole phone, every owner | Plays older than 30, 90 or 365 days. **Keep until cleared** is the default |
| Clear account listening history | The active account or signed-out use | That owner's plays, its recently played list, and the play counts kept with its downloads |
| Clear device listening history | The phone | Plays of audio stored on the phone, whichever account was signed in |
| Clear search history | The active owner | That owner's recent searches |
| Delete downloads | The active owner | Its downloaded songs, artwork and lyrics, and its waiting transfers |
| Delete local data for this account | The active owner | Its plays, searches, saved queue, tracks saved in the app and downloads |
| Earlier data | Data from builds before per-account storage | See [state and migration](state-and-migration.md) |
| Export local data, Import local data | The owners the user chooses | Nothing. See [export and import](local-export-import.md) |

Never removed by any of these: appearance, audio and history-saving settings; the sign-in (signing out is a separate action on the Account screen); audio files in the phone's own library; another owner's data; anything in the YouTube Music account.

## Confirmation and results

- Every clear and delete asks first. The prompt says whose data it is, how much, that it cannot be undone, and that the YouTube Music account is not changed.
- Cancel changes nothing and reports nothing.
- A retention period that would remove plays at once asks first with the number of plays. One that removes nothing is applied directly.
- The result is one of: done, with a count; partly done, naming what is still on the phone; or failed, with nothing changed. Partly done and failed offer **Retry**, which runs the same action again. Nothing is reported as removed unless it was.
- An unverified account has no storage of its own, so its rows are disabled and say why. The device rows still work.

## Limits

- History: 100,000 plays per owner. The oldest are dropped beyond that. The screen says so.
- Searches: 30 per owner. Searches typed in an unverified session are kept in memory only and are gone when the owner changes.
- Retention has no alarm. It is applied after each recorded play, when the period is changed, and at most once a day when the app starts. Plays can outlive the period until Riff next runs. Plays dated in the future are not removed.

## How a clear stays cleared

- All actions run one at a time, off the main thread, through `LocalDataController`.
- Clearing history or deleting a saved queue raises that owner's stored counter in the same transaction as the delete. A write prepared earlier carries the old counter and is refused, also after a restart.
- **Delete local data** first stops and empties the owner's online queue in the player and waits for it to go, then deletes. On-device audio keeps playing.
- Download rows are removed before files. A folder is removed only when its canonical parent is the downloads root. A transfer that finishes after its row was removed discards its file instead of listing it again.
- A play in progress when history is cleared is not written back. The next play started is recorded normally.

## Searches from earlier builds

Earlier builds kept one search list for every account. It is not shown to any owner. **Earlier data** offers to import it into the active owner's list, after the owner's own searches and without duplicates, or to delete it. An unverified account can only delete it.

## Diagnostics

One line per action: `local_data action=<name> result=done|partial|failed` with counts only. No queries, titles or owner keys.

## Known limits

- Data stored under an older key of the same account that has not yet been merged is not covered by **Delete local data**; the background merge moves it to the account's key first.
- **Not interested**, speed-dial pins and podcast progress are device-wide and are not part of these actions.
- Deleting local data removes tracks saved in the app for that owner, not likes or playlists held by YouTube Music.

## Verification

JVM and Robolectric: `LocalDataControllerTest`, `SearchHistoryOwnerTest`, `PrivacyDataScreenTest`, `HistoryScreensTest`, with `LibraryStateTest` for the counters.

Emulator, real playback service and database, network off, synthetic identities: `LocalDataDeviceTest`.

Not verified here: these actions on a physical phone with real downloads and a real account.
