# Music artwork fix — 30 September 2026

The installed rc3 APK lacked Coil's network fetcher service entry. Rebuilding with the existing coil-network-okhttp 3.3.0 dependency restored fetching. MainActivity now configures one shared ImageLoader with an explicitly registered HTTP fetcher, so network artwork does not depend on service-loader packaging. Failure diagnostics include only host, exception type, and HTTP status; success logs and the temporary Home diagnostic text were removed.

The original parser chose the first URL in each thumbnail array, often a 60px rendition. Home, profile, and song parsing now use a shared selector that checks the CDN host and chooses the largest thumbnail by pixel area without assuming array order. A Coil mapper upgrades Google image size parameters to at least 1024px and requests YouTube max-resolution video covers. The mapper also handles older locally saved URLs and changes the cache identity before loading, so tiny cached covers are not stretched. A missing max-resolution YouTube cover falls back to hqdefault. Image quality remains limited by the source artwork supplied by the provider.

Validation:

- 14 unit tests passed, including four artwork selection and URL regression tests.
- Debug and same-key release builds passed. Release lint reported zero errors; existing project warnings remain.
- Release APK signature verified; installed with adb install -r on the iQOO I2217 without clearing account or collection data.
- Home cards, quick picks, mini-player, expanded player, and fresh Tum Hi Ho search results render real artwork. The final Home and player screenshots visibly show sharper artwork than the initial small renditions.
- An online Disco Elysium, Pt. 2 track entered PLAYING with an advancing position and buffered audio on the final build. This is a smoke check, not a new complete streaming acceptance test.

Evidence: artifacts/device-checks/artwork-20260930/home-loaded.png, player.png, and search-results.png. The updated signed APK is artifacts/release/youtube-music-glass-artwork-fix.apk. Package name, signing key, dependency versions, and version code/name remain unchanged.

Files changed: MainActivity.kt (loader initialization), ArtworkImageLoader.kt (explicit networking, cache-aware URL mapper, missing-cover fallback, sanitized errors), ProviderArtwork.kt (thumbnail selection and resolution policy), YouTubeMusicProvider.kt (reuse shared thumbnail selection), HomeFeed.kt (remove visible diagnostics), and ProviderArtworkTest.kt (regression cases).