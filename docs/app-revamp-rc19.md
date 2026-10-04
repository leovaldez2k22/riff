# Riff 0.9.0-rc19

## Requested changes

The reported symptom is that, after several songs, a new song shows "YouTube Music could not start this track. Try another track." The supplied screenshot contains the app's generic player-error text; it does not identify the underlying transport failure. No Android device was connected during this change, so that exact listening-session failure could not be reproduced.

A concrete recovery defect was found: refreshing attestation and calling prepare could leave an already resolved MediaItem pointing to the rejected bootstrap or URL. Recovery now resolves fresh transport details and replaces the failed source. It also invalidates other cached bootstraps from the old attestation, gives HTTP 403/410 a fresh-stream attempt, and excludes failing SABR profiles with a bounded retry budget. It preserves queue order and position, retains the user's pause, and discards a late result after another song is selected. Official-video source identity is preserved during recovery. The player suppresses the generic error while this bounded recovery is active and shows it again if recovery fails.

Queue rows now show a visible remove button beside the reorder handle, both in Up next and History. Swipe and accessibility removal remain available. Deleting the last queue item pauses playback and empties the queue. Removing one occurrence does not delete other copies of that recording.

Settings -> Data usage -> Data saver is off by default and persists across reopening. It requests LOW audio quality and a 360p video-height preference on newly resolved streams, with a separate cache key for each quality. Existing streams are not interrupted by changing the setting. Provider availability determines the formats offered. Downloads continue to request HIGH audio quality.

## Validation

- Full debug unit/Compose suite: **244 passed, 0 failed, 0 skipped**.
- A real Media3 ExoPlayer test verifies replacement keeps the entire queue, current index/position, shuffle, repeat, and pause state.
- Recovery-budget tests cover attestation refresh, HTTP 403/410, protocol/reload failures, exhausted or repeated profiles, and unrelated errors.
- Delayed-recovery tests cover user pause, queue reordering, and another selection of the same recording.
- Compose UI test clicks the visible remove button and verifies only the selected duplicate occurrence is removed.
- Final-item removal test verifies pause and an empty queue.
- Data-saver persistence test verifies the next streaming-quality choice and unchanged download quality.
- git diff --check: passed.
- Release lint: **0 errors** (90 warnings, 5 hints).
- Signed, shrunk arm64 release: **26.2 MiB**, under the 40 MiB release budget.
- APK signature verified; certificate matches the existing release certificate in docs/release-signing.md.
- Release dependency license audit: 233 dependencies verified.
- APK: artifacts/release/riff-0.9.0-rc19.apk.
- SHA-256: ef8744399c4d723eeae4ef730995d234f4df0dbbd3347fee5ce1b42d714e7ea5.
- Built from d693469 plus the uncommitted rc19 changes in this workspace.

## Device follow-up

Install rc19 over the current release and play several new songs in sequence, including after changing networks. If playback still stops, use Settings -> About -> Copy playback diagnostics to identify the actual remaining error category. This build has not been installed or tested on a physical device in this session.
