# Phase 8 — self-contained scope review

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

The user chose to complete self-contained Phase 8 features now and show explicit unavailable states for features that require services or devices this project does not have. This is the completed scope of this pass. The original plan's external-service and device gates are not represented as passed.

| Track | Status in this build | Verification boundary |
|---|---|---|
| Accounts, sync, uploads | In-app sign-in, read-only liked songs, account-scoped app saves and sign-out cleanup are implemented. Cloud sync, uploads and remote mutations are explicitly unavailable. | Prior phone checks cover account browse and same-account local restore. Two-device conflict and second-account switching are not verified. |
| Recommendations, activity | Quick picks rank original demos using recorded plays, existing history, favorites, pins and followed demo artists. Follow/unfollow, track exclusion with Undo, history pause, clear with Undo and persisted preferences are implemented. | Ranking action/exclusion unit test passes. No live release feed is present, so release alerts are unavailable. |
| Comments, collaboration | Unavailable state is shown in Connected features. No comments or shared playlist controls are offered without a backend and authorization contract. | No network mutation or concurrency gate is claimed. |
| Voice, recognition | Search requests microphone permission and uses Android's speech recognizer when available. It handles cancel, no match, unavailable language, unavailable model, permission and network errors. Humming recognition is explicitly unavailable. | Build/lint verify integration; microphone, language and provider behavior still need a device check. |
| Casting | Local and Bluetooth output remain available through the existing system audio picker. The Connected features screen clearly states that Google Cast is unavailable. | No Cast receiver or handoff has been tested. |
| AI features | A rule-based demo mix creates a persisted playlist containing only known original-demo track IDs. The app does not call it AI. AI generation and artwork are explicitly unavailable. | Catalog IDs are bounded to the known fixture list by the mix builder; no AI service is configured. |
| Recap, badges | Recap counts actual recorded demo play starts in rolling 7- and 30-day windows, ranks tracks/artists and derives simple badges. Pausing history stops new recording; clearing history removes prior counts and can be undone. | Window-boundary and future-event unit test passes. No invented minutes or YouTube Music statistics are displayed. |
| Premium, Upgrade | Unavailable state is shown. Original demo playback is not restricted. | No billing or entitlement environment is configured. |

## No-USB verification

- `:app:testDebugUnitTest`: 2 tests, 0 failures, 0 errors.
- `:app:assembleDebug` and `:app:lintDebug`: pass.
- No USB debugging, installation or phone interaction was used for this pass, as requested. A workspace-local emulator image was not present, so new UI and speech input were not device checked. The APK is at `app/build/outputs/apk/debug/app-debug.apk` and is not yet installed on the phone.

The plan's M8 external-service and device checks remain unpassed by design. This build completes the user-selected self-contained Phase 8 scope and visibly identifies unavailable connected features rather than presenting them as working demos.

The voice integration follows Android's [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) and [RecognitionListener](https://developer.android.com/reference/android/speech/RecognitionListener) contracts. Speech-to-text may use the device's selected recognition service; it is not music identification.
