# Desktop runtime and rendering research

## Runtime selection for the actual Riff codebase

### Takeaway

**Propose Compose Multiplatform Desktop as the primary track, with a mandatory M1 feasibility gate. Use Electron as the fallback if that gate fails and a UI rewrite is accepted.** Tauri v2 is a viable web application shell, but Riff has no web frontend to reuse. Flutter introduces a comparable UI rewrite plus a new application language.

This recommendation is an inference from the inspected source and official platform capabilities, not a measured performance result. The user’s clarified Windows 11 initial scope, fidelity/playback priority, and lack of a hard 100 MB limit strengthen the Compose choice.

### Cited findings

- Compose Multiplatform extends Jetpack Compose and exposes common Kotlin composables, state, layouts, modifiers and animations. Android-specific APIs require platform implementations. Therefore some Riff UI source can move rather than be translated into another toolkit. [JetBrains relationship documentation](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-and-jetpack-compose.html)
- Compose Desktop targets the JVM; Skiko exposes Skia graphics and rendering-context integration. This preserves Kotlin and Compose expertise but retains a JVM distribution and native graphics libraries. [Compose repository](https://github.com/JetBrains/compose-multiplatform), [Skiko repository](https://github.com/JetBrains/skiko)
- **Riff’s glass is not platform independent today.** `RiffGlass.kt` imports Android `RuntimeShader`, `RenderEffect`, `Build`, and `LocalView`; its captured `GraphicsLayer` backdrop, invalidation, clipping and effect plumbing need desktop adapters. The preserved implementation contract is its math, uniforms, roles, geometry and appearance. [RiffGlass.kt](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/RiffGlass.kt:3>)
- The desktop Skiko API exposes `RuntimeEffect` and `RuntimeShaderBuilder`, including child shaders. This provides a plausible Skia implementation path for Riff’s runtime effect; it is not proof that the existing AGSL string and render-effect chain run unchanged. [RuntimeEffect](https://jetbrains.github.io/skiko/skiko/org.jetbrains.skia/-runtime-effect/index.html), [RuntimeShaderBuilder](https://jetbrains.github.io/skiko/skiko/org.jetbrains.skia/-runtime-shader-builder/index.html)
- Electron uses a main Node process and a renderer for each browser window. Destroying a window terminates its renderer. Native playback must therefore be hosted outside a destructible UI renderer if music must survive true window destruction. [Electron process model](https://www.electronjs.org/docs/latest/tutorial/process-model)
- Tauri v2 uses WebView2/Chromium on Windows, WKWebView on macOS and WebKitGTK on Linux. The installed engines and their versions are not a single cross-platform rendering target. Its small installer does not establish a specific runtime RAM saving. [Tauri webview versions](https://v2.tauri.app/reference/webview-versions/), [Tauri architecture](https://v2.tauri.app/concept/architecture/)
- Flutter provides Windows, macOS and Linux desktop targets and release compilation to machine code. It renders through its own framework and engine rather than preserving Compose component source. [Flutter desktop support](https://docs.flutter.dev/platform-integration/desktop), [Flutter architectural overview](https://docs.flutter.dev/resources/architectural-overview)

**Decision comparison**

| Criterion | Compose Desktop | Electron | Tauri v2 | Flutter Desktop |
|---|---|---|---|---|
| Existing Riff source reuse | Highest potential: common Compose/Kotlin; isolate Android APIs | Rewrite Compose as web UI | Rewrite Compose as web UI | Rewrite Compose as Dart widgets |
| Rendering control | Skia/Skiko effects; platform graphics backend | Bundled Chromium version controlled by release | Installed WebView; Windows controlled by WebView2 servicing | Own engine; backend/version must be pinned |
| Main fidelity risk | Android capture/effect adapters; desktop fonts; native window integration | Rebuilding every custom component; DOM backdrop capture for refraction | Same rewrite plus engine differences for future OS targets | Widget/shader rewrite; backend-specific filtering |
| Startup forces | JVM initialization/JIT, resource/font/effect initialization | Chromium/process startup, JS parse/load | Rust core plus platform WebView creation and JS | Engine initialization and AOT application startup |
| Memory forces | JVM heap + native Skia/GPU + caches + worker | Main/render/GPU processes + native worker/caches | Core/WebView/GPU processes + native worker/caches | Dart/engine/native/GPU + worker/caches |
| IPC model | Typed local API; named-pipe/socket boundary to worker | Async IPC; MessagePorts; native child/utility integration | Commands/channels; Rust core | Platform channels and FFI |
| Riff measurements available | None | None | None | None |

Do not fill this table with generic “20 MB / 100 MB / 300 MB” claims. Official documentation describes architecture; it does not provide an equivalent Riff scene, workload, process-tree accounting or hardware baseline.

### Inferences and benchmark contract

- A trimmed Rust core may have less host-runtime overhead than a JVM or Electron main process, but WebView processes, image caches, decoder workers, ONNX analysis and GPU resources still count. An installer size is not RAM.
- Native calls or FFI avoid a process boundary but sacrifice isolation. There is no useful universal IPC throughput ranking without identical payloads, dispatch paths, backpressure and serialization.
- Benchmark signed/release builds on the same Windows 11 hardware: integrated GPU, same power mode and display scaling, local fixtures and identical cache state. Measure:
  - process-tree private committed memory and working set separately;
  - GPU dedicated/shared memory separately;
  - main window, mini-player, hidden-to-tray, and active Automix analysis;
  - cold first usable frame versus warm reopen and first audio separately;
  - p50/p95/p99 command round trip while idle and while rendering;
  - bounded spectrum delivery under UI load, reporting frame age, drops and allocation rate.
- Preserve a **single application contract**, so the native backend survives a shell fallback. Avoid designing renderer-specific playback ownership.

### Gaps

No desktop port or equivalent fixture benchmark exists in the inspected source. No exact memory, startup, throughput, accessibility or 120 Hz claims are established for Riff.

## Rendering parity and the custom window

### Takeaway

**Preserve the rendering recipe rather than substituting a generic “glass effect.”** Riff’s lens refracts a sampled application backdrop; OS Mica/Acrylic and ordinary CSS blur are different materials. Fidelity must be a versioned visual contract with acceptance evidence, not a runtime promise.

### Cited findings

- Riff records underlying page content, then applies blur, rounded-rectangle distance-field rim bending, dispersion, tint and highlights. Its outward lens is deliberately different from the bar and button lenses. [RiffGlass.kt](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/RiffGlass.kt:45>)
- Android AGSL operates within Android’s graphics pipeline, including clipping, blending and color conversion; sharing syntax with GLSL does not make it a drop-in WebGL or desktop shader. [Android AGSL](https://developer.android.com/develop/ui/views/graphics/agsl)
- Riff’s player mesh is deterministic artwork processing: 6×6 colors, seam-specific reordering, vibrance 1.12, floor .045, a 128-pixel image and three box-blur passes. It is generated once per cover rather than blurred live. Keep this exact algorithm and cache derived results. [PlayerBackdrop.kt](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/PlayerBackdrop.kt:37>)
- WebKit officially supports `backdrop-filter`, including its prefixed form. This establishes filter availability, not Riff’s refraction parity or equivalent costs on every WebKitGTK distribution. [WebKit CSS status](https://webkit.org/css-status/)
- Flutter supports custom fragment shaders. However, custom shaders used as `ImageFilter.shader` over rendered content are documented as **Impeller only**; that operation cannot be assumed on every backend. [Flutter fragment shader documentation](https://docs.flutter.dev/ui/design/graphics/fragment-shaders)
- Electron draggable regions ignore pointer events unless excluded with `app-region: no-drag`. This CSS is an Electron integration convention; Compose uses `WindowDraggableArea`, and Tauri has its own drag-region mechanism. [Electron custom interactions](https://www.electronjs.org/docs/latest/tutorial/custom-window-interactions), [Compose window management](https://kotlinlang.org/docs/multiplatform/compose-desktop-top-level-windows-management.html), [Tauri window customization](https://v2.tauri.app/learn/window-customization/)
- Windows 11 Snap Layouts can fail with custom title bars. Microsoft specifies native caption support or correct `WM_NCHITTEST` responses, including `HTMAXBUTTON` for maximize. Merely drawing a maximize icon is insufficient. [Microsoft Snap Layout guidance](https://learn.microsoft.com/windows/apps/desktop/modernize/apply-snap-layout-menu)
- Documented DWM system backdrop types require Windows 11 build 22621. `MAINWINDOW` corresponds to Mica; `TRANSIENTWINDOW` to Desktop Acrylic. Unsupported or unavailable materials need the approved Riff opaque/frost fallback. [DWM backdrop types](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/ne-dwmapi-dwm_systembackdrop_type)
- Electron exposes macOS vibrancy and Windows background materials; its documented `setBackgroundMaterial` support is Windows 11 22H2+. Those capabilities do not turn them into Riff’s internal glass. [Electron BrowserWindow](https://www.electronjs.org/docs/latest/api/browser-window)

**Effect-by-effect evaluation**

| Effect | Electron | Tauri v2 | Flutter | Compose Desktop |
|---|---|---|---|---|
| Blur/tint glass | CSS filters; controlled Chromium | WebView filters; per-engine validation | Backdrop/image filters | Skia image/effect adapter |
| Riff rim lens/refraction | Custom shader and explicit backdrop texture strategy | Same; platform engine constraints | Shader port and backend validation | Preserve shader math, replace Android plumbing |
| Artwork mesh | Port deterministic CPU algorithm; render texture | Same | Port to Dart/native | Pure Kotlin algorithm reusable after bitmap adapter |
| Audio visualizer | Canvas/WebGL, bounded data channel | Canvas/WebGL, channel | CustomPainter/shader | Canvas/Skia drawing |
| Typography | Bundle exact Inter; verify metrics/rasterization per OS | Same plus engine variance | Bundle Inter; verify engine metrics | Reuse type tokens/resources; verify desktop metrics |
| Scrollbars | Themed CSS scrollbar; engine support differs | Same, larger matrix | Themed scrollbar widget | Themed Compose scrollbar |
| 120 Hz+ | Measure actual vsync/presentation; no shell guarantee | Measure actual WebView/platform behavior | Designed for high refresh; workload still decisive | Measure Skiko frame-clock/presentation under actual backend |

### Inferences and design requirements

- A DOM web rewrite cannot assume a fragment shader can directly sample arbitrary rendered DOM beneath a control. It needs an explicit composited scene/texture strategy. Per-frame screenshot/readback-and-upload would threaten latency and image quality.
- Preserve alpha conventions, color-space conversions, blur reach, edge clamping, coordinate origins and device-pixel ratios in the shader port. Many “almost the same” glass ports differ here rather than in their lens formula.
- Treat native backdrop materials as a restrained window-level treatment, preferably confined to titlebar/navigation chrome. Keep artwork-driven content and Riff’s lens rendering authoritative.
- Prefer an opaque top-level window with native DWM shadows/corners over a fully transparent window simply to obtain rounded corners. If transparency is required, validate resize, hit testing, maximized state and remote-desktop behavior.
- M1 needs a dedicated Windows chrome adapter with OS move/resize, eight-edge hit testing, double-click maximize/restore, restore-drag, Alt+Space, Win+Arrow, Snap flyout and system-menu behavior. Controls/search/seek areas must be excluded from dragging.
- Use logical units for layouts, convert only at graphics/native boundaries. Test 100/125/150/200% scaling and moving a live window across monitors. Do not multiply fonts or density tokens twice.
- Bundle the same Inter files and weights. Matching font family is insufficient: verify letter spacing, baseline, line height, fallback shaping, ligatures and text bounds. Pixel-identical antialiasing across Android and Windows cannot be guaranteed.
- Animation timing is wall-clock based: the same 520 ms transition should contain more frames at 120 Hz, not run twice as fast. Budget approximately 16.67 ms at 60 Hz and 8.33 ms at 120 Hz. Validate presented frames and stalled UI, not average FPS alone.
- Stop visualizer/backdrop frame production when no visible window consumes it. Keep audio/clock progression independent. A paused scene should not force continuous redraw.

### Gaps and mandatory gate

**Compose accessibility is a material concern.** Official documentation currently says Windows uses Java Access Bridge, disabled by default; package `jdk.accessibility`. It documents Linux desktop accessibility as unsupported. These must not be hidden behind a general “cross-platform support” claim. [Compose desktop accessibility](https://kotlinlang.org/docs/multiplatform/compose-desktop-accessibility.html)

Before committing to the primary track, verify a packaged Windows install with NVDA/JAWS, normal keyboard operation, custom sliders, focus order, menus and window controls. Determine whether the installation/user setup needed for Java Access Bridge is acceptable. Linux accessibility is an explicit future-release blocker under this track, not a Windows launch requirement.

M1 passes only if the shader/backdrop fixture, packaged accessibility, native chrome and mixed-DPI tests pass on target hardware. If they fail after a bounded spike, choose Electron and preserve the same contracts and reference fixtures; do not silently simplify the visual design.

## IPC and runtime implications for the blueprint

### Takeaway

Use low-frequency typed commands/state events and a separately bounded visualization channel. Native playback ownership matters more than the shell’s IPC microbenchmark.

### Cited findings

- Tauri explicitly says its event system is unsuitable for low latency or high throughput; channels are designed for fast ordered streaming. Events are JSON strings and lack the same strong typing/capability granularity as commands. [Tauri calling the frontend](https://v2.tauri.app/develop/calling-frontend/)
- Tauri supports raw byte payloads and channels for streamed responses. [Tauri calling Rust](https://v2.tauri.app/develop/calling-rust/)
- Electron offers MessagePorts for communication and utility-process channels. Ordinary renderer APIs should be exposed through a narrow preload bridge rather than granting the page arbitrary Node privileges. [Electron MessagePorts](https://www.electronjs.org/docs/latest/tutorial/message-ports), [Electron process model](https://www.electronjs.org/docs/latest/tutorial/process-model)
- Flutter supports both message-oriented platform channels and C FFI. They serve different boundaries; FFI itself does not imply process isolation. [Flutter architecture](https://docs.flutter.dev/resources/architectural-overview)

### Inferences for the proposed Compose track

- UI process owns Compose windows and rendering; a native worker owns decoder/output timing and remains alive while windows are hidden/disposed according to explicit lifecycle policy.
- Use an authenticated per-user named pipe on Windows for typed control messages. Future Unix-domain sockets can implement the same logical contract. A versioned schema should generate Kotlin/native types; do not base contracts on an assumption of TypeScript.
- Publish position anchors, not a 120 Hz position event stream. Anchor includes media identity, monotonic timestamp, position, rate, state and discontinuity revision. UI interpolates using its frame clock; seek/pause/resume/track change reset the anchor.
- Send precomputed lyric timing and offsets once; the UI uses the same interpolated clock for word sweep. Native audio remains the time authority.
- Start with 64–256 spectrum bins at 30–60 Hz when visible. At 128 float32 bins ×60 Hz, bins alone are about 30 KiB/s before headers; raw PCM crossing IPC is unnecessary.
- Use a capacity-one/latest-frame buffer for spectrum. Old frames are dropped, never queued until the UI falls behind. Commands and playback state must not share a backlog with visualization.
- Define sequence numbers, sample timestamp, media revision, format, channel count and unsubscribe semantics. Do FFT/downsampling outside the real-time audio callback.
- A named pipe can carry this modest spectrum load without shared memory initially. Shared memory is an optimization requiring evidence, not an M1 dependency.
- Reliability validation must include renderer hangs/recreation, mini-player/main-window coexistence, tray playback and control reconnection without duplicate audio devices or play commands.
- No claim that Compose, Tauri, Electron or Flutter inherently guarantees gapless playback is justified: decoder trimming, prebuffering, mixer scheduling, output-device continuity and clock ownership decide it.
