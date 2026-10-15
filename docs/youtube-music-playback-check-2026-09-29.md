# YouTube Music playback check — 29 September 2026

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

The updated debug APK was installed over the existing app on the connected iQOO I2217 (Android 16), preserving its signed-in session. Authenticated YouTube Music search returned public song rows. Tapping Play for two results entered the Media3 service, then failed before any audio bytes were delivered.

Privacy-safe playback events recorded the automatically selected `WEB_REMIX_SABR__nopo` profile and four HTTP 200 SABR responses for each track. Each response reported `RELOAD_PLAYER`, with no initialization segment and zero selected media bytes. Media3 then reported `ERROR_CODE_IO_UNSPECIFIED`. The new SABR byte adapter therefore reached the endpoint, but the provider rejected the stream before the adapter could supply audio. A controlled `VISIONOS_SABR` override did not yield a playable stream and was removed.

The app now shows a clear stream-start failure message instead of the old unsupported-transport warning or a raw Media3 error. The real YouTube Music playback gate remains open. Do not claim this device plays provider tracks until a full song starts, advances, seeks, and survives background playback on the phone.
