# Phase 1 Glass Lab review

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

Updated 2026-09-27. **M1 passed for View/Compose optics with a documented video fallback.** The separate YouTube Music feasibility gate remains open. No Phase 2 app shell has been started.

## Implementation and reference comparison

The lab draws a rounded rectangle, circle, capsule, and bottom bar. An AGSL `RuntimeShader` receives the rendered backdrop through `RenderEffect`. The shader keeps the center unchanged and refracts only a tunable rim. Foreground diagnostic labels are drawn afterward and remain sharp. A switch shows the no-glass baseline; sliders change refraction and bevel.

| Reference | Comparison with the lab | Next design use |
|---|---|---|
| [R02 bottom chrome](../references/R02-mini-player-and-navigation.png) | The live bar/capsule and clear center echo the floating shapes and readable content beneath them. The reference has thinner white edge treatment, a mini-player above a separate navigation capsule, and an independent Search control. The lab is an optical fixture, not that layout. | Preserve separation and rounded ends when building the actual bottom components; tune the edge highlight over bright and dark artwork. |
| [R01 player/sheet](../references/R01-player-and-library-sheet.png) | The lab proves rounded glass geometry but has no full-player layout or sheet. | Use later for player and Library sheet composition. |
| [R03 album](../references/R03-album-detail.png) | The color-field fixture exercises adaptive-looking backdrops but has no album layout. | Use later for artwork-led pages. |
| [R04 lyrics](../references/R04-lyrics.png) | No lyric timing or immersive lyrics UI exists yet. | Use only after a lyric source is proved. |

The lab deliberately keeps centers unfrosted as the user requested. The references include some diffusion; this is a visual direction, not a command to blur the app's underlying content.

## Phone evidence

The iQOO I2217 runs Android 16/API 36 at 1080×2400 and 440 dpi; 60 Hz was active. The reviewed APK and captures are in the [final M01 check](../artifacts/device-checks/M01/20260927-141205/report.md). The View checker and color field visibly update behind the shapes. A Compose fixture scrolls under fixed glass while foreground labels stay separate. The generated, silent video fixture is local test media, not YouTube Music playback.

The video experiment exposed an intermittent ADB capture defect: parts of the surrounding Compose UI vanish for some recorded frames while video continues. It appears with the shader disabled and with either `TextureView` or `SurfaceView`. The user checked the physical phone and reported that the title and controls remain stable during playback. The current video path uses `SurfaceView` and a clear outlined rim with no shader refraction. This is a supported fallback for this prototype, not proof of refraction over arbitrary video.

A short debug checker sample after the four-shape shader optimization recorded 611 frames with glass on and 609 with glass off. Both had 0 Android-reported janky frames; the on/off 95th percentiles were 18/16 ms. Repeated ADB-driven Compose scrolling in debug exceeded the provisional 2% target even with glass off, so debug results were not used for M1 acceptance.

A locally signed, non-debug release build of the same source was warmed with six swipes and measured over twenty alternating Compose scroll swipes per mode. `dumpsys gfxinfo` reported **1/600 janky frames (0.17%) with glass on** and **0/615 with glass off**. The on/off 95th percentiles were **14/12 ms**, and 99th percentiles **15/14 ms** at 60 Hz. The exact build hash and raw dumps are in the final M01 evidence folder. This clears the plan's provisional <2% deadline-miss target for this representative fixture, but is not a thermal soak or a product-wide performance claim. Capture-to-display lag and overdraw are not numerically measured; the checker recording shows no visible phase freeze between backdrop and rim.

## Architecture decision and open gate

- Use the AGSL `RenderEffect` path only for regular Android View and Compose content that renders into the sampled View hierarchy. Keep foreground controls in a separate layer.
- Do not apply that shader to video surfaces, protected media, or arbitrary external windows. The video fixture uses a clear rim fallback until a stable path is proved.
- Keep the default lab values at 3 dp refraction, 15 dp bevel, and 0.65 highlight while tuning against R02. These are experiment values, not final product tokens.
- Carry the video fallback and ADB capture limitation into later player and Samples work. Recheck actual content over bright and dark artwork, large text, overdraw, and thermal behavior as those screens exist. Phase 2 still requires explicit user approval before implementation.
