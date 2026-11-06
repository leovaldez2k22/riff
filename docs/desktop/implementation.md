# Desktop M1 implementation

The opt-in `:desktop` module is the Windows 11 Compose feasibility implementation. It uses Kotlin 2.4.10, Compose Multiplatform 1.12.2 and JDK 21; Android modules retain their existing boundaries. Run `./gradlew -PriffDesktop=true :desktop:run` or package with `./gradlew -PriffDesktop=true :desktop:createDistributable`. Font bytes and the OFL license are copied from the Android assets.

The packaged preview contains a custom frameless window, native Windows hit testing, an application-owned lifecycle, and a live gallery. The gallery ports the Android glass shader and role equations, Inter typography, ambient palettes, artwork math, karaoke renderer, switch and segmented lens. It uses a deterministic fixture clock and does not play audio. Closing exits while tray support is unavailable.

The provider feasibility task `:desktop:verifyProviderJvm` resolves the desktop JVM dependency graph without Android AARs. It does not verify live authentication or playback; see [provider feasibility](provider-feasibility.md). The preview bundles Java Access Bridge for screen-reader testing.

M1 remains in progress. The current test results, performance measurements, accessibility defect and unverified hardware and visual gates are in [M1 acceptance evidence](m1-acceptance.md). A complete native dependency license audit is required before desktop distribution. The full design and roadmap are in the [desktop architecture dossier](../../reports/Riff%20Windows%20desktop%20architecture.md).
