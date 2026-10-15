# Riff 0.9.0-rc17

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

Volume booster is in Settings > Audio, with an on/off switch and a 100–300% capsule slider. It is disabled by default and remembers its selected level. Settings changes reach the playback service through a retained preference listener. Decoded native audio receives 1–3x PCM amplitude gain, a 30 ms gain ramp and a continuous soft limiter for loud peaks. At 100% the samples are bit-exact. System media volume is unchanged. Downloads, native music/video and native live radio use the same processing chain; the sleep timer still fades AudioTrack output and restores its previous volume after pausing.

The player's options panel unfolds from the tapped three-dot button, including the button in the inline queue. A rounded 40dp footprint grows into the floating panel with a damped spring; labels and icons fade in as it opens. Shape changes happen in a graphics layer, while the menu rows keep their layout. Closing reverses the reveal. The popup respects status/navigation insets, scrolls when needed, consumes its gestures independently of player dismissal, supports Back/outside dismissal, and snaps when Reduce motion or disabled platform animations are in use. The player remains visible underneath.

Listen again is removed as a separate Home shelf. All eligible songs from it are included in Speed dial, preserving the existing paged grid and fresh/recent-song mix. Entries are unique, podcasts/live streams are excluded, and collection cards remain capped at three. Additional pages accommodate every Listen again song rather than truncating the source to six.

Home waits for a response owned by the current cache generation before displaying personal history/favorite cards. A cold start no longer briefly fills Speed dial with persisted history before the fresh Home request completes. Mood/account changes and the scheduled cache refresh also reset that UI state. In-process tab switches still reuse the feed cache. Task removal clears the provider's auxiliary Home/podcast state as well as its page caches; saved listening position, queue, downloads and history are retained.

## Verification

- All 155 unit tests pass, with zero failures/errors. Coverage includes the entire PCM16 range at unity, stereo gain ramping, 300% quiet signals, bounded peaks, invalid preferences, empty buffers, Media3 stream reconfiguration, menu placement, all Listen again songs (including a 70-song shelf), unique cards, removed Home shelf, process cache replacement and the existing lyrics/queue/timer checks.
- Full production release lint reports zero errors/fatal issues and 77 existing warnings.
- Physical phone: 300% PCM processing, live preference changes/persistence and seeking passed on an existing download with muted output. The final download check processed 122,304 PCM frames; original preferences were restored.
- Real Home response: 14 Listen again songs all present in 35 unique Speed dial entries; the separate shelf is removed. Repeated visits reuse the same response, while discarding memory fetches a new response.
- A controlled pending request exercised the actual Home composable with saved history present: Loading was visible, old Speed dial was absent, then fresh Speed dial appeared after the response completed.
- Official Numb video played natively and continued after seeking. Lofi Girl live video continued while the app was in the background. The final sleep fade had 29 decreasing volume samples, reaching 0.00113 before pausing and restoring baseline volume. The original seven-song queue and position were restored paused.
- The first native video check caught an empty-buffer self-copy in the booster. The PCM processor now handles empty pipeline/end-of-stream buffers without copying, with focused regression coverage. The corrected full native video/radio/sleep checks passed; the failed diagnostic is retained as evidence.
- Player and inline queue menus were reviewed on the phone; Back and outside dismissal worked. Production Settings controls and larger-font scrolling were checked, with user preferences restored afterward.
- The signed production APK contains no preview activity, fixture playback or instrumentation. It retains Android 12 support (minSdk 31), version code 18, all four existing ABIs and the same signing certificate. SHA-verified APK copies are in artifacts/release and Downloads.

Evidence is in artifacts/features-rc17. The embedded YouTube fallback has its own audio path and does not pass through the native PCM booster. 300% describes signal gain; peak limiting prevents digital overflow.

## API references

- [Media3 custom audio processors](https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink.Builder)
- [BaseAudioProcessor buffer and stream lifecycle](https://developer.android.com/reference/androidx/media3/common/audio/BaseAudioProcessor)
- [DefaultRenderersFactory audio sink](https://developer.android.com/reference/kotlin/androidx/media3/exoplayer/DefaultRenderersFactory)
