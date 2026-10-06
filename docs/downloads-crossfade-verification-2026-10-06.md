# Downloads, frosted menus and crossfade verification

Verified on 6 October 2026 with an iQOO I2217 running Android 16.

## Resulting behavior

Downloads supports deleting all downloads with confirmation, collections support downloading all songs after pagination completes, search has a clear button, and storage budgets extend to Unlimited. Clearing playback cache requires confirmation. Lyrics timing defaults to +0.2 seconds. Popup menus and timing controls use frosted backgrounds with a separate intensity setting, and the queue thumbnail returns to the expanded player.

Automatic crossfades use one bounded monotonic clock for both equal-power volume gains and the artwork/title/artist fade. Decoder startup and buffering hold progress without compressing the fade. An early outgoing end does not restore the incoming volume abruptly. The outgoing copy attaches matching loudness processing when its audio session becomes available. Manual song changes and crossfade-off playback replace presentation immediately; reduced motion remains respected.

## Validation

- All 273 JVM/Robolectric/Compose tests passed across 52 suites, with no failures, errors or skipped tests.
- Debug and release lint completed with zero errors. Existing warning counts were 83 and 90 respectively.
- Stress, device-test and signed release builds completed successfully.
- Seventeen distinct phone instrumentation tests passed across the recorded runs: six two-renderer crossfade cases, five playback-service cases, artwork/text rendering, popup blur, and four app UI cases.
- Complete fixture handoff sampled both gains 686 times; early outgoing end sampled them 677 times. Both gains matched the shared equal-power progress and changed gradually.
- Service tests exercised volume boost off and at 300%, manual skip and crossfade off. A streamed Numb → Blinding Lights handoff with boost enabled recorded 237 samples over a 5,780 ms blend, validating live visual progress, gradual controller-volume changes and continued playback.
- Popup backdrop-detail deviation fell from 0.17424 to 0.00794 independently of glass intensity. UI tests verified player expansion, queue-thumbnail return, search clear, separate frosted intensity, Unlimited storage and canceling cache-clear confirmation.
- Native physical taps verified the regular release's mini player → player → queue → thumbnail → player → Home flow. Its installed APK SHA-256 matched the verified artifact: `3540f192a359cbdd00050965ab89df1b0d52c107d44c33fe63f7a41904632db3`.

The fixture harness keeps an activity foreground to prevent phone process suspension and waits for the manually selected decoder to become ready. The corrected manual-skip case passed while requiring immediate visual cancellation. Production code did not change during those harness repairs.

Instrumentation ran on the matching release-signed stress variant; the regular non-debuggable release was installed afterward. Original queue, preferences and offline listening metadata were restored, downloaded files were retained, and the test runner was removed. The checks verify playback and gain behavior, not subjective differences in track mastering. Signed APKs, device logs, credentials and toolchains remain outside Git.
