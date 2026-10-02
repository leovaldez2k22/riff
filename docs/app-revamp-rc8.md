# Riff — 0.9.0-rc8

This release implements the supplied album, queue-video and frosted-glass references, plus the requested catalog and playback changes. It preserves the account session and upgrades the installed app with the same signing key.

## Requested changes

| Request | Result |
| --- | --- |
| Album layout | Full-bleed cover and artwork-color gradient, centered album/artist metadata, shuffle, large Play button, authenticated album save, description and numbered tracks with menus. Both responsive and older detail headers retain artist links. |
| Reference font | Licensed Inter remains bundled, with revised sizes and weights. The exact font cannot be established from the screenshot. Apple’s SF distribution terms do not permit bundling it in this Android app. |
| Live radio | Actual YouTube channel live discovery opens YouTube’s visible embedded player. Its app Referer is supplied; player controls remain available. Leaving the screen or backgrounding pauses the WebView. A normal Open on YouTube fallback is available. |
| Forgotten favourites | Home reads the actual account’s Forgotten favorites shelf, fetching available homepage continuations. The shelf is omitted if the service does not supply it. |
| Podcast categories | Tech, true crime, politics, science, history, comedy, business, health, education and sports request real podcast-filter results. For you retains account recommendations. |
| Artist hero | Artist/band imagery fills half the viewport and gradients into the rest of the page. |
| Player color | Cached artwork is sampled into a deterministic clustered palette; nearby hues of a gradient are combined. The dominant color is darkened for text readability and animated across track changes. |
| Carousel edges | Reusable symmetric narrow alpha fades replace hard clipping on horizontal shelves, search/library filters and category rows. |
| Album queue | Every album track is staged in its original order; tapping track four selects index three with previous tracks retained. Metadata is immediately visible, while each stream is resolved when Media3 prepares it. |
| Startup | Cold launch animates the Riff logo’s scale, slight rotation and fade. Reduce motion skips it. |
| Frosted glass | Settings switches the same AGSL glass geometry to a hardware-blurred, softly tinted backdrop with bright rims. Android RenderEffect blur is masked to the glass shapes and composited over the unchanged page. |
| Glass intensity | Persistent 0–150% setting scales refraction, highlights and frosted blur. |
| Search double tap | Double-tapping Search resets its root, focuses the rounder search field and opens the keyboard. |
| Search tags | Filter tags report their geometry to the shared glass shader and use translucent surfaces and borders. |
| Star sync | Existing authenticated YouTube Music like/unlike mutations remain; successful changes refresh Liked Songs and remote library data. Local saves remain separately labeled. |
| Song video | A video option below the player’s three bottom actions obtains the paired or matching video from YouTube Music. Native Media3 audio/video sources share the current queue item, retaining queue order, current position and pause intent. The surface preserves video aspect ratio. |
| Better Lyrics priority | Better Lyrics TTML/QQ/Kugou are tried first. Any usable Better Lyrics result, including line timing, ends provider lookup. Other providers and cached fallback lyrics are considered only after Better Lyrics has no usable result. Real supplied word timing drives highlighting. |
| Queue reference | Artwork morphs into a compact header; Continue Playing, album context, shuffle/repeat, history, cover rows and drag handles occupy the upper half. Seek, transport and bottom actions remain fixed. Long-press drag reorders and left swipe removes; accessibility actions offer the same edits. |
| Remember last playback | The service saves the complete queue metadata, current index, position, shuffle/repeat and recommendation seed on changes, periodically and before task removal. Next launch restores the paused mini player without resolving or playing streams until requested. Live items resume at the live edge. |

## Verification

The signed release passed 54 unit tests, zero test failures and Android lint with zero errors (44 warnings). APK v2 signature verification passed. The release was installed over the existing app using USB debugging, preserving its account and preferences.

On the connected iQOO phone, the Graduation page showed artwork, Kanye West’s linked name and numbered songs. Selecting I Wonder at track four created the complete 14-track album queue. The artist hero measured exactly half of the 2400-pixel viewport. History showed Good Morning, Champion and Stronger before I Wonder, and opening the inline queue retained the transport bounds. Long-press dragging moved Good Life from index four to five; swiping removed one item and reduced the queue to 13. The unmodified album queue was reloaded after those edits.

Native song video rendered real changing video frames and advanced from 8.346 to 11.342 seconds while retaining all 14 queue entries. YouTube’s embedded Lofi Girl broadcast reported playing=true, readyState=4 and live time advancing from 46803 to 46808 seconds. These observations concern the tested content; individual videos may be unavailable by account or region.

Home displayed the real Forgotten favorites shelf. Tech returned Fwdays Tech Talks and Tech Connect Live. Double-tapping Search opened the keyboard and focused its input. Earlier force-stop/reopen checks restored I Wonder and all 14 queue entries paused, with position retained within 100 milliseconds. Removing Riff’s own Recents task while playing removed its media session and stopped playback. A second check with playback paused before task removal restored I Wonder, the complete 14-track queue and the mini player without autoplay; the restored position differed by 101 milliseconds. These checks and final visual evidence are recorded under artifacts/design-revamp.

The initial live WebView had a zero-height document. Explicit match-parent layout fixed its viewport and enabled playback. Early frosted sampling produced repeated text; the final implementation uses Android’s native hardware blur instead. An AGSL uniform-array indexing error found on the phone was corrected using constant-index selection.

Saved playback contains public track IDs and display metadata, not credentials, cookies, signed stream URLs or SABR bootstrap state. On-demand resolution prevents restoring expired stream URLs. Automatic frame scheduling still targets 120 Hz where supported; neither a 120-Hz display nor the setting guarantees 120 rendered frames per second.

## References

- [Apple font distribution](https://developer.apple.com/fonts/)
- [YouTube embedded player parameters](https://developers.google.com/youtube/player_parameters)
- [YouTube embedded-player requirements](https://developers.google.com/youtube/terms/required-minimum-functionality)
- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback)
- [Compose shared elements](https://developer.android.com/develop/ui/compose/animation/shared-elements)
