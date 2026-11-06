# M1 provider and native dependency feasibility

Verified 1 November 2026 with :desktop:verifyProviderJvm: InnertubeX 0.7.2 resolves its desktop artifact and complete JVM runtime graph without Android AARs. The generated artifact list is desktop/build/reports/provider-jvm.txt. This is an isolated configuration, not a runtime dependency of the UI preview. QuickJS JVM is in that graph; resolving it does not establish successful attestation or playback.

Desktop adaptation strategy:
- Preserve account/scope generation and cancellation semantics behind a provider interface.
- Replace Android CookieManager/Context/preferences with an app-owned session store protected by Windows user-bound credentials. Do not import the owner's browser profile.
- Keep authentication outside privileged application rendering. A new isolated session flow needs a deliberate provider-compatible design, not an assumed OAuth grant.
- Replace AndroidTokenRuntime/WebView attestation with an isolated desktop provider implementation. This remains an M3 implementation dependency and a source-compatibility risk. No account login, token minting, provider mutation or live stream request was performed by this spike.
- Retain the existing resolver's expiry, single-flight and per-scope invalidation behavior. Provider refusals, SABR and Cast capabilities remain distinct.

Native packaging strategy: the M1 bundled JDK successfully loads the Windows x64 Skiko/JNA rendering and Win32 adapters. The future audio worker remains a separate x64 executable with a private versioned control channel, linked decoder DLLs and CPAL WASAPI output. No Rust or FFmpeg decoder worker is claimed built in M1. M3 must pin the Rust toolchain, FFmpeg configuration, architecture and DLL import set, exercise clean-machine DLL loading and codec delay/padding fixtures, and record licensing before distribution. A Symphonia substitution must pass the same format/seek/gapless matrix.

The desktop glass adapter uses Compose's BlurEffect conversion from radius to Skia sigma, matching Android semantics; passing the radius straight to Skia.makeBlur would overblur the reference.
