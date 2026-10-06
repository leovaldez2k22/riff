# Privacy and permissions

Updated 6 October 2026 for the current `main` implementation. The published 4 October rc20 APK predates the latest download and cache controls; see [update notes](update-2026-10-06.md). External providers have their own terms and privacy policies.

## Local data

Riff stores playback position and queue, settings, account-scoped local saves and listening history, catalog caches, downloaded audio/artwork/lyrics, and a small playback diagnostic ring on the phone. Downloads are app-private. Signing out hides account-scoped local collections; returning to the same account can make them available again. Uninstalling deletes app-private data. Android cloud backup and device transfer are disabled. These local collections do not provide cross-device synchronization.

Downloads → Delete all downloads asks for confirmation, removes the current account's downloaded audio, artwork and lyrics, and cancels pending transfers. Clear playback cache also asks for confirmation and retains permanent downloaded bundles. Unlimited removes the app's configured download-budget cap; the device free-space reserve remains in force. These actions do not remove songs or playlists from the provider account.

The diagnostics ring is bounded and avoids track names, signed URLs, and credentials. Copy playback diagnostics additionally scrubs URLs and token-like fields. Sharing the copied report is a user action; inspect reports and screenshots before posting publicly.

## Network requests and accounts

Search, playback, recommendations, catalog images, and provider-library actions contact YouTube Music/YouTube and their delivery services. Sign-in uses an in-app WebView; Riff retains provider session information on this installation to make authenticated requests. Account actions such as liking a song or editing a provider playlist are sent to the provider when requested.

Lyrics matching sends public song metadata such as title, artist, album, and recording duration to Better Lyrics, NetEase, LRCLIB, and Lyrics.ovh as needed. The separate lyrics HTTP client does not receive YouTube/account cookies. Native YouTube lyrics use the YouTube integration. NetEase can require verification; Riff falls through instead of providing a verification bypass. Providers can observe ordinary connection information such as IP address and user agent.

Mood images are discovered through Openverse and Wikimedia Commons and loaded from image hosts. Artist/album artwork is loaded from provider URLs. Internet access is needed for online content and initial translation-model downloads; previously downloaded songs can play offline.

## Translation and voice search

ML Kit translates lyrics on the device. Google supplies downloadable language models and receives SDK performance/usage metrics. See [ML Kit terms and privacy](https://developers.google.com/ml-kit/terms).

The short translation metrics sentence was removed from the lyrics screen in the latest UI update. Translation processing and model/metrics behavior remain as described here.

Voice search uses Android's configured speech-recognition service when invoked. That service may process audio remotely according to its own settings and policies; Riff does not promise offline recognition. Microphone permission is requested for voice search.

## Permissions

| Permission | Use |
|---|---|
| Internet and network state | Online content, provider requests, and network-aware work |
| Audio/media library access | On-device audio browsing, requested on that screen; legacy storage access on Android 12 |
| Microphone | Optional voice search |
| Notifications | Playback/download notifications when allowed |
| Foreground media playback/data sync | Background audio and download work |
| Receive boot completed | Re-establish scheduled app work after restart |

App data and provider-account data are separate. Removing a local download does not delete a provider-library song. To manage Google-held account data, use Google's account controls. For app questions, use [GitHub Issues](https://github.com/leovaldez2k20/riff/issues/new/choose); for security concerns, follow [SECURITY](../SECURITY.md).
