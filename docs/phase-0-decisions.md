# Phase 0 decision record

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

Updated 2026-09-27. Status: **M0 and the scoped M1 optical gate passed on the iQOO phone**; Phase 0Y provider proof remains open. Phase 2 awaits user approval.

| Item | Decision or evidence |
|---|---|
| Product | Personal APK named **Riff**, application ID `com.musicglass.personal` per user direction. |
| Navigation target | Home / Samples / Search / Library. The app shell is scheduled after the optical proof. |
| Android range | Minimum API 33; compile/target API 36 for the reported Android 16 phone. |
| Build versions | AGP 8.13.2, Gradle 8.13, Kotlin 2.3.21, Compose BOM 2026.06.01. The newer September BOM requires API 37 and AGP 9.1+, so June's 1.11.4 Compose libraries are pinned for API 36. Debug build and lint pass. |
| Current host | Java 20.0.1. Android SDK platform 36, build tools, ADB and Gradle 8.13 installed project-locally in ignored `.toolchain`; the user approved the Android SDK license. |
| Device | ADB sees authorized serial `10BE1D18C0000E5`; product/model `I2217`, Android 16/API 36, build `BP2A.250605.031.A3/compiler260710173526`, Adreno GPU, 1080×2400 at 440 dpi. Display reports 60/90/120 Hz (60 Hz active during inspection). The signed debug APK installed, cold launched, and respected safe insets; see [device evidence](../artifacts/device-checks/M01/). |
| References | The user supplied four images; they are mapped in [the reference manifest](../references/manifest.md). No reference art is bundled in the APK. |
| Glass scope | Phase 1 uses an AGSL `RenderEffect` on live View and scrolling Compose content with foreground labels on a separate layer. Video uses a clear outline fallback. ADB captures intermittently lose surrounding UI frames, while the user reports a stable physical display. See [the Phase 1 review](phase-1-glass-review.md). |
| Music source | User confirmed unofficial YouTube Music direction for personal use. No library is selected until catalog and playback are proved separately on the phone. |

## Preliminary provider capability matrix

Here **documented** means a project's own documentation or source describes a capability. It does not mean this app has reproduced it. All phone proof is pending.

| Capability | Current evidence | Gate state |
|---|---|---|
| Anonymous Music search, albums, artists, Home | `ytmusicapi` documents Music browsing; Metrolist claims a native Android Music client. | UNTESTED on phone |
| Personalized library and account | `ytmusicapi` documents authenticated library requests through emulated web sessions. Credential lifecycle and app login are unproved. | UNTESTED |
| Playlist read/write | `ytmusicapi` documents playlist operations; ordinary YouTube Data API playlists are a different resource model. | UNTESTED |
| Lyrics and recommendations | Existing clients expose some forms; source, timing, rights and stability are unproved here. | UNTESTED |
| Samples | No confirmed source/contract for short clips. | UNTESTED |
| Full audio, seek, queue, background | Metrolist release notes report playback and recovery work. This is evidence of active development, not a reproducible integration proof for this app. | UNTESTED |
| Offline tracks and casting | Source-specific and rights-dependent. | UNTESTED |

## Candidate review

- [Metrolist](https://github.com/MetrolistGroup/Metrolist) is a Kotlin/Android client in maintenance mode and licensed GPL-3.0. Its [13.7.0 release](https://github.com/MetrolistGroup/Metrolist/releases/tag/v13.7.0) reports networking/client fallback, account and playback fixes. Review the exact dependency boundary and authentication handling before reuse. No code copied yet; any later reuse must meet its license obligations.
- [ytmusicapi](https://ytmusicapi.readthedocs.io/en/stable/) documents Music catalog and account operations in Python. It is not a Kotlin playback engine. Its web emulation is subject to upstream changes; a successful search would not prove streaming or durable login.
- [OuterTune](https://github.com/OuterTune/OuterTune/releases) is unsuitable as a current Music source: its maintainer says 0.11.x removed YouTube Music support. Earlier YTM branches are no longer actively maintained, per the [maintainer discussion](https://github.com/OuterTune/OuterTune/discussions/1116).
- The [YouTube Data API](https://developers.google.com/youtube/v3) covers general YouTube data, not a supported custom YouTube Music audio player. Its [search response](https://developers.google.com/youtube/v3/docs/search/list) concerns video/channel/playlist resources. Do not relabel it as a Music catalog.

## Screen map and requirement routing

| Requested area | Planned phase | Dependency |
|---|---|---|
| Home, Samples, Search, Library navigation | 2 | M1 glass architecture and accessible bottom controls |
| Music catalog, account, playlists | 0Y, 3, 5 | Reproducible YouTube Music capability proof |
| Mini-player and full player | 2, 4 | M1 optics; provider playback for real controls |
| Album detail, adaptive colors | 2, 5 | Catalog artwork and metadata |
| Lyrics and recommendations | 4, 8 | Verified source and timing/quality |
| Downloads, casting, offline | 6, 8 | Individual source and rights feasibility |
| Samples and video | 7, 8 | Source contract and stable video rendering |
| Accessibility, performance, release | 2, 9, 10 | Device and content gates |

## Next proof

1. Request user approval before Phase 2, then build the navigable visual shell over deterministic fixture content.
2. Continue Phase 0Y with an isolated anonymous catalog adapter and no credentials in logs, after separate phase approval.
3. Prove multiple playable resources, seeking, track changes, lifecycle/background behavior, expiration and error handling before selecting a playback engine or claiming M0Y.
