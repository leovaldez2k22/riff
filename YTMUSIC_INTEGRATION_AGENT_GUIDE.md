# YouTube Music Integration — Agent Execution Guide

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](docs/README.md). Current plan: [Riff excellence implementation plan](docs/RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

> **For:** an AI coding agent (Claude Code, Cursor, Copilot Agent, etc.) working inside an existing Android project.
> **Human owner:** will review your changes. Follow steps in order. Do not skip verification steps.

---

## 0. Mission

The Android app in this repository is already built and working.
**Add YouTube Music playback** so the user can:

1. Search YouTube Music for songs.
2. Tap a result and hear it play.
3. Keep playing with screen off / app in background, with notification + lock-screen controls.

Use **NewPipeExtractor** (open-source Java library) for both search and audio stream URLs.
Use **AndroidX Media3 (ExoPlayer + MediaSession)** for playback.

### Hard rules

- **Do NOT rewrite or delete existing features.** Only add. Change existing code only where needed to hook in.
- **Do NOT change package name, applicationId, signing config, or keystore.**
- **Reuse what exists.** If app already has ExoPlayer, a player service, an `Application` class, a search screen, or OkHttp — extend those, don't duplicate.
- **Match existing code style** (Compose vs XML views, Hilt vs manual DI, coroutines vs RxJava, package structure).
- **Check latest dependency versions** before adding (Maven Central / JitPack / GitHub releases). Versions below are known-good as of Sept 2026, not guaranteed latest.
- **All network calls off main thread.** Always.
- **Never store stream URLs** in DB or cache — they expire after a few hours. Resolve at play time.
- This app is for **sideload/personal use only**. Do not add Play Store publishing config for it.
- After each phase, **build must pass** before moving on.

### Stop and ask the human if

- Project is **not native Android** (Flutter, React Native, Xamarin, KMP-shared UI) → stop, report stack, ask how to proceed.
- Project uses **Java only** with no Kotlin support → ask whether to add Kotlin or write Java versions.
- `minSdk` < 21 → ask.
- Existing player is **not** ExoPlayer/Media3 (e.g. `android.media.MediaPlayer`) → report and ask: migrate to Media3, or adapt with plain `MediaPlayer.setDataSource(url)`.
- Build fails 3 times on same error and fix is unclear.

---

## Phase 1 — Discovery (read-only, no edits)

Run and record answers before editing anything.

1. Find build files:
   - `settings.gradle` or `settings.gradle.kts`
   - root `build.gradle(.kts)`
   - `app/build.gradle(.kts)` (app module may have different name — find module with `com.android.application` plugin)
   - `gradle/libs.versions.toml` (version catalog — if present, add deps there, not inline)
2. Record:
   - Language: Kotlin / Java / mixed
   - UI: Jetpack Compose / XML views
   - `minSdk`, `targetSdk`, `compileSdk`
   - AGP version, Kotlin version, Java target
   - DI: Hilt / Koin / manual
   - Async: coroutines / RxJava
3. Search codebase for existing pieces:
   ```
   grep -rn "ExoPlayer\|media3\|exoplayer" --include=*.kt --include=*.java --include=*.gradle* --include=*.toml .
   grep -rn "MediaSessionService\|MediaLibraryService\|MediaBrowserService" --include=*.kt --include=*.java .
   grep -rn "class .* : Application()\|extends Application" --include=*.kt --include=*.java .
   grep -rn "okhttp" --include=*.gradle* --include=*.toml .
   grep -rn "MediaItem\|setMediaItem\|addMediaItem" --include=*.kt --include=*.java .
   ```
4. Read `AndroidManifest.xml` of app module: note `application android:name`, existing services, permissions.
5. Identify **where the app currently decides what to play** (the function that builds `MediaItem` or calls `play()`). This is the main hook point.
6. Run baseline build:
   ```
   ./gradlew assembleDebug
   ```
   If baseline fails → stop and report. Don't fix unrelated breakage without asking.

**Output of Phase 1:** short summary to human with the recorded facts + planned hook points. Then continue.

---

## Phase 2 — Dependencies

### 2.1 Repositories

In `settings.gradle(.kts)` → `dependencyResolutionManagement.repositories`, ensure:

```kotlin
google()
mavenCentral()
maven("https://jitpack.io")
```

(Groovy: `maven { url 'https://jitpack.io' }`)

### 2.2 Desugaring

NewPipeExtractor v0.26.x uses Java 11 NIO APIs. Enable core library desugaring in app module:

```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        // keep existing source/targetCompatibility; if lower than 11, raise to 17
    }
}
```

### 2.3 Dependencies

Add only what's missing. If a version catalog exists, add entries there.

```kotlin
dependencies {
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")      // check latest release tag
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")  // check latest

    // only if not already present:
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-datasource:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

- If app already uses Media3, **use its existing version** for all media3 artifacts (they must match).
- If app uses OkHttp 4.x already, keep 4.x (see note in Phase 3 about `res.body`).

### 2.4 Verify

```
./gradlew assembleDebug
```
Must pass. Fix dependency conflicts before continuing.

---

## Phase 3 — Networking bridge for NewPipe

Create file in a new package `<app-base-package>.ytmusic`.

### `NpDownloader.kt`

```kotlin
package <app-base-package>.ytmusic

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

class NpDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) : Downloader() {

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)

        request.headers().forEach { (key, values) ->
            builder.removeHeader(key)
            values.forEach { builder.addHeader(key, it) }
        }

        client.newCall(builder.build()).execute().use { res ->
            if (res.code == 429) {
                throw ReCaptchaException("reCaptcha challenge requested", request.url())
            }
            return Response(
                res.code,
                res.message,
                res.headers.toMultimap(),
                res.body.string(),          // OkHttp 4.x: use res.body?.string()
                res.request.url.toString()
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
```

If app already has a shared `OkHttpClient` (e.g. provided via DI), pass that instance in instead of creating a new one.

### Init once at app start

- If `Application` subclass exists → add to its `onCreate()`.
- If none → create `YtApp : Application()` and set `android:name=".ytmusic.YtApp"` (or correct path) on `<application>` in manifest.

```kotlin
override fun onCreate() {
    super.onCreate()
    org.schabi.newpipe.extractor.NewPipe.init(NpDownloader())
}
```

Verify: `./gradlew assembleDebug` passes.

---

## Phase 4 — Repository (search + stream resolving)

### `YtMusicRepo.kt`

```kotlin
package <app-base-package>.ytmusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class YtSong(
    val url: String,          // https://www.youtube.com/watch?v=...
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val durationSec: Long
)

object YtMusicRepo {
    private val yt get() = ServiceList.YouTube

    /** Search YouTube Music songs. Call from coroutine. */
    suspend fun searchSongs(query: String): List<YtSong> = withContext(Dispatchers.IO) {
        val handler = yt.searchQHFactory.fromQuery(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
            ""
        )
        SearchInfo.getInfo(yt, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map { it.toYtSong() }
    }

    /** Related / "up next" songs for a song. */
    suspend fun related(songUrl: String): List<YtSong> = withContext(Dispatchers.IO) {
        StreamInfo.getInfo(yt, songUrl).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map { it.toYtSong() }
    }

    /** Real audio stream URL. Expires in hours — never persist. */
    fun resolveAudioUrlBlocking(songUrl: String): String {
        val info = StreamInfo.getInfo(yt, songUrl)
        val best = info.audioStreams.maxByOrNull { it.averageBitrate }
            ?: error("No audio stream for $songUrl")
        return best.content
    }

    suspend fun resolveAudioUrl(songUrl: String): String =
        withContext(Dispatchers.IO) { resolveAudioUrlBlocking(songUrl) }

    private fun StreamInfoItem.toYtSong() = YtSong(
        url = url,
        title = name,
        artist = uploaderName ?: "",
        thumbnail = thumbnails.maxByOrNull { it.width }?.url,
        durationSec = duration
    )
}
```

Notes for agent:
- If the NewPipeExtractor version you installed has different method names (API drift), open the library sources (Android Studio "Go to declaration" or the GitHub tag) and adapt. Don't guess.
- Other search filters exist on `YoutubeSearchQueryHandlerFactory`: `MUSIC_ALBUMS`, `MUSIC_ARTISTS`, `MUSIC_PLAYLISTS`, `MUSIC_VIDEOS`. Only songs are required for this task.

### Quick sanity test (required)

Add an instrumented test or a temporary debug-only button/log that runs:

```kotlin
val songs = YtMusicRepo.searchSongs("Arijit Singh Tum Hi Ho")
Log.d("YtTest", "found ${songs.size}: ${songs.firstOrNull()}")
val url = YtMusicRepo.resolveAudioUrl(songs.first().url)
Log.d("YtTest", "stream ok: ${url.take(80)}")
```

Must log non-empty results and a stream URL. Remove temporary debug UI after verifying (keep tests).

---

## Phase 5 — Player integration

Goal: player accepts a `MediaItem` whose URI is the **YouTube watch URL**, and resolves the real audio URL **right before loading**. This avoids expired-link failures in long queues.

### 5.1 Resolving data source

Add to wherever the app builds its `ExoPlayer` (existing service or player holder):

```kotlin
import androidx.core.net.toUri
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import <app-base-package>.ytmusic.NpDownloader
import <app-base-package>.ytmusic.YtMusicRepo

private fun isYoutubeUrl(uri: String) =
    uri.startsWith("https://www.youtube.com/watch") ||
    uri.startsWith("https://music.youtube.com/watch") ||
    uri.startsWith("https://youtu.be/")

val httpFactory = DefaultHttpDataSource.Factory()
    .setUserAgent(NpDownloader.USER_AGENT)
    .setAllowCrossProtocolRedirects(true)

val resolvingFactory = ResolvingDataSource.Factory(httpFactory) { spec ->
    val uri = spec.uri.toString()
    if (isYoutubeUrl(uri)) {
        // Runs on ExoPlayer's loader thread (not main) — blocking is OK here.
        spec.withUri(YtMusicRepo.resolveAudioUrlBlocking(uri).toUri())
    } else spec
}

val player = ExoPlayer.Builder(context)
    .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
    // keep any existing builder options (audio attributes, handleAudioBecomingNoisy, etc.)
    .build()
```

- If the app already sets a custom `MediaSourceFactory` / `DataSource.Factory` (e.g. for caching), **wrap** it: pass the existing upstream factory into `ResolvingDataSource.Factory` instead of `httpFactory`. Do not remove existing caching.
- Non-YouTube URIs (local files, other sources) must keep working unchanged.

### 5.2 Building MediaItems for YouTube songs

```kotlin
fun YtSong.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(url)
        .setUri(url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(thumbnail?.toUri())
                .build()
        )
        .build()
```

Play:
```kotlin
controllerOrPlayer.setMediaItem(song.toMediaItem())
controllerOrPlayer.prepare()
controllerOrPlayer.play()
```

Optional (do it if app has a queue concept): after starting a song, fetch `YtMusicRepo.related(song.url)` and `addMediaItems(...)`.

### 5.3 Background playback

**If app already has `MediaSessionService` / `MediaLibraryService`:** just ensure it uses the player from 5.1. Skip rest of 5.3.

**If not:** add one.

`PlaybackService.kt`:
```kotlin
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = /* player built exactly as in 5.1 */
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
```

Manifest additions (inside `<manifest>` and `<application>`):
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".PlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

UI side: connect with `MediaController`:
```kotlin
val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
val future = MediaController.Builder(context, token).buildAsync()
future.addListener({ val controller = future.get() /* store, use for play */ }, MoreExecutors.directExecutor())
```
Request `POST_NOTIFICATIONS` at runtime on Android 13+ if app doesn't already.

Verify: `./gradlew assembleDebug` passes.

---

## Phase 6 — UI hook

Match existing UI toolkit and design.

1. **Search entry point.** If app has a search screen → add a "YouTube Music" source/tab/toggle there. If not → add a new screen reachable from main navigation.
2. **Search input** → debounce ~300 ms → call `YtMusicRepo.searchSongs(query)` in a ViewModel coroutine (`viewModelScope`).
3. **Results list** row: thumbnail, title, artist, duration (`mm:ss`).
4. **States:** loading spinner, empty ("No results"), error with retry button. Catch exceptions from repo; never crash.
5. **Tap row** → build `MediaItem` (5.2) → send to player/controller.
6. Mini player / now-playing UI: if app already has one, it should update automatically via player metadata. Verify title/artist/art show correctly.

---

## Phase 7 — Error handling

Wrap repo calls; map errors to user-friendly messages:

| Exception (NewPipe) | Meaning | Message to user |
|---|---|---|
| `ReCaptchaException` | rate-limited (HTTP 429) | "YouTube is limiting requests. Try again in a bit." |
| `ContentNotAvailableException`, `AgeRestrictedContentException`, `GeographicRestrictionException` | this song can't play | "This song isn't available." → skip to next in queue |
| `IOException` | network | "Check your internet connection." |
| `ExtractionException` / `ParsingException` | YouTube changed something | "Playback broke — app update needed." Log full stack trace. |

In the player, add a `Player.Listener.onPlayerError` handler: log error, show message, call `seekToNextMediaItem()` if queue has more items.

---

## Phase 8 — Release build (R8/ProGuard)

Append to app module `proguard-rules.pro`:

```
# NewPipeExtractor + Rhino JS engine
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
```

Build release with the project's existing signing config:
```
./gradlew assembleRelease
```

If R8 reports missing classes, add matching `-dontwarn` lines **only** for the reported classes.

---

## Phase 9 — Verification checklist (all must pass)

Build:
- [ ] `./gradlew assembleDebug` succeeds
- [ ] `./gradlew assembleRelease` succeeds
- [ ] No existing tests broken: `./gradlew test`

On device/emulator (install **release** APK too, not only debug):
- [ ] Search "Tum Hi Ho" → song results appear with thumbnails
- [ ] Tap result → audio starts within a few seconds
- [ ] Lock screen → playback continues
- [ ] Notification shows title/artist/art with working play/pause/next
- [ ] Bluetooth/headphone play-pause works
- [ ] Queue of 3+ songs plays through in order
- [ ] Airplane mode → friendly error, no crash
- [ ] Existing (non-YouTube) features of app still work exactly as before

If device testing isn't possible from agent environment, clearly tell human which checklist items remain unverified.

---

## Phase 10 — Report back to human

Give a short summary:
1. Files created (list).
2. Files modified (list + one line each on what changed).
3. Dependency versions added.
4. Checklist results (passed / not verified).
5. Known limitations:
   - Stream links come from unofficial extraction; YouTube changes can break playback. Fix = bump `NewPipeExtractor` to newest release.
   - Sideload only — not Play Store compliant.
   - No Google account login / personal YT Music library (not in scope).

---

## Maintenance notes (for future agents)

- **Playback suddenly fails for all songs** → update NewPipeExtractor to latest tag on JitPack, rebuild. Check its GitHub releases for YouTube fixes.
- **Search returns nothing / parsing errors** → same fix as above.
- **Frequent 429 errors** → reduce request rate (debounce search, don't prefetch too many related lists).
- Reference implementations if deeper YouTube Music features are needed later (account login, lyrics, home feed): open-source apps **Metrolist** and **InnerTune** on GitHub (GPL-3.0 — copying code makes this app GPL-3.0 too; ask human first).
