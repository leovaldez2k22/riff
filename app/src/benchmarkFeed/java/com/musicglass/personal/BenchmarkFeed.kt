package com.musicglass.personal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.webkit.CookieManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Q14, benchmark build types only (benchmarkRelease, nonMinifiedRelease): the app is fed invented content
 * instead of a provider, so that what a benchmark measures is the app and never a network. It is not in a
 * release, a stress or a debug build.
 *
 * - Every provider request is answered from the invented fixtures of src/test/resources/quality/provider.
 * - Every song resolves to one generated 30 second tone, served from this process on 127.0.0.1.
 * - Every artwork address in a fixture is pointed at the same server, which draws a plain picture for it.
 * - Lyrics and other plain HTTP calls made through the runtime fail at once, unless they are for that server.
 * - The session is synthetic ("fixture-sync-B"); no account is involved.
 *
 * The provider is exported to the shell alone (it asks for DUMP, which an app cannot hold) for three
 * switches, each read at the next process start:
 *   content call --uri content://<package>.benchmark-feed --method strict  --arg on|off
 *   content call --uri content://<package>.benchmark-feed --method latency --arg <ms before each audio answer>
 *   content call --uri content://<package>.benchmark-feed --method rate    --arg <audio bytes per second, 0 = unlimited>
 * and one read at each request:
 *   content call --uri content://<package>.benchmark-feed --method tracks  --arg <rows in every playlist, 0 = as the fixture>
 */
class BenchmarkFeed : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = checkNotNull(context).applicationContext
        val switches = app.getSharedPreferences(SWITCHES, Context.MODE_PRIVATE)
        if (switches.getBoolean("strict", false)) watchTheMainThread()
        if (!switches.getBoolean("seeded", false)) {
            // Once per install, as ProviderWorld does for the JVM tests.
            app.getSharedPreferences("provider_session", Context.MODE_PRIVATE).edit().clear()
                .putString("sync", "fixture-sync-B").putString("account", "0").putString("visitor", "fixture-visitor").commit()
            // Written through before the marker: a start that is stopped at once must not leave a session without it.
            CookieManager.getInstance().apply { setCookie("https://music.youtube.com", "SAPISID=fixture"); flush() }
            switches.edit().putBoolean("seeded", true).commit()
        }
        val audio = ToneServer(switches.getLong("latency", 0), switches.getLong("rate", 0))
        AppContainer.runtimeFor = { context ->
            RuntimeDependencies.android(context).copy(
                // A download fetches its audio and artwork through this client: the loopback server and nothing else.
                httpCalls = OkHttpClient.Builder().addInterceptor { chain ->
                    if (chain.request().url.host != "127.0.0.1") throw IOException("The benchmark feed has no network")
                    chain.proceed(chain.request())
                }.build(),
                providerTransport = { OriginalAudioEngine.create { config { addInterceptor { chain ->
                    val request = chain.request()
                    val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    val fields = runCatching { Json.parseToJsonElement(body) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
                    fun field(name: String) = (fields[name] as? JsonPrimitive)?.contentOrNull
                    val continuation = field("continuation") ?: request.url.queryParameter("continuation") ?: request.url.queryParameter("ctoken")
                    val name = fixtureFor(request.url.encodedPath.substringAfter("/youtubei/v1/"), field("browseId"), continuation)
                    val page = name?.let { app.assets.open(it).use { stream -> stream.readBytes().toString(Charsets.UTF_8) } } ?: "{}"
                    val tracks = switches.getInt("tracks", 0)
                    val text = (if (name == "playlist-owned.json" && tracks > 0) longPlaylist(page, tracks) else page)
                        .replace(ARTWORK_HOSTS, "http://127.0.0.1:${audio.port}/art/")
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                        .body(text.toResponseBody("application/json".toMediaType())).build()
                } } } },
                resolver = StreamResolver { request ->
                    Result.success(ProviderStream("http://127.0.0.1:${audio.port}/tone.wav", emptyMap(), "audio/wav",
                        sourceVideoId = request.videoId, contentLength = audio.tone.size.toLong(), durationMs = TONE_SECONDS * 1000L, expiresAtMs = Long.MAX_VALUE))
                },
                startProviderBackgroundWork = false,
                // The tone arrives as a WAV file, which the real assembler does not rebuild and a bundle may not be.
                assembler = AudioAssembler { source, output, _, check -> toneToMp4(source, output, check) },
                downloadLyrics = { kotlinx.coroutines.flow.emptyFlow() },
            )
        }
        return true
    }

    /** The stress build's thread policy, for the audit run of a journey. It costs time, so it is off unless asked for. */
    private fun watchTheMainThread() =
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val edit = checkNotNull(context).getSharedPreferences(SWITCHES, Context.MODE_PRIVATE).edit()
        when (method) {
            "strict" -> edit.putBoolean("strict", arg == "on")
            "latency" -> edit.putLong("latency", arg?.toLongOrNull()?.coerceIn(0, 5_000) ?: 0)
            "rate" -> edit.putLong("rate", arg?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
            "tracks" -> edit.putInt("tracks", arg?.toIntOrNull()?.coerceIn(0, 2_000) ?: 0)
            else -> return null
        }
        edit.commit()
        return Bundle().apply { putString("set", "$method=$arg") }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val SWITCHES = "benchmark_feed"
        val ARTWORK_HOSTS = Regex("https://(lh3\\.googleusercontent\\.com|yt3\\.ggpht\\.com)/")
    }
}

internal const val TONE_SECONDS = 30

/** Which invented page answers a provider request; null is an empty object. A later page is known by its token. */
internal fun fixtureFor(endpoint: String, browseId: String?, continuation: String?): String? = when {
    endpoint == "account/account_menu" -> "account-menu.json"
    continuation == "fixture-home-2" -> "home-page-2.json"
    continuation == "fixture-owned-page-2" -> "playlist-owned-page-2.json"
    continuation == "fixture-owned-page-3" -> "playlist-owned-page-3.json"
    continuation == "fixture-search-songs-2" -> "search-songs-page-2.json"
    continuation != null -> null
    endpoint == "search" -> "search-songs.json"
    endpoint == "next" -> "watch-next.json"
    endpoint != "browse" -> null
    browseId == "FEmusic_home" || browseId == null -> "home.json"
    browseId.startsWith("FEmusic_li") -> "library-playlists.json"
    browseId.startsWith("VL") -> "playlist-owned.json"
    browseId.startsWith("MPRE") -> "album.json"
    else -> null
}

/**
 * The invented playlist page with [count] rows: its first row repeated under invented ids, and no continuation,
 * so that one page is a whole collection of the size the queue limit allows.
 */
internal fun longPlaylist(page: String, count: Int): String {
    val root = Json.parseToJsonElement(page) as JsonObject
    fun JsonObject.child(name: String) = getValue(name) as JsonObject
    fun JsonObject.with(name: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(this + (name to value))
    val contents = root.child("contents"); val columns = contents.child("twoColumnBrowseResultsRenderer")
    val secondary = columns.child("secondaryContents"); val list = secondary.child("sectionListRenderer")
    val section = (list.getValue("contents") as kotlinx.serialization.json.JsonArray)[0] as JsonObject
    val shelf = section.child("musicPlaylistShelfRenderer")
    val row = (shelf.getValue("contents") as kotlinx.serialization.json.JsonArray)[0].toString()
    val id = checkNotNull(Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(row)).groupValues[1]
    val rows = kotlinx.serialization.json.JsonArray(List(count) { Json.parseToJsonElement(row.replace(id, "bench%06d".format(it))) })
    val long = JsonObject(shelf - "continuations").with("contents", rows)
    return root.with("contents", contents.with("twoColumnBrowseResultsRenderer", columns.with("secondaryContents",
        secondary.with("sectionListRenderer", list.with("contents", kotlinx.serialization.json.JsonArray(listOf(section.with("musicPlaylistShelfRenderer", long)))))))).toString()
}

/** PCM16 mono 16 kHz, 440 Hz at a quarter of full scale: the same kind of tone as the device tests' fixtures. */
internal fun toneWav(seconds: Int = TONE_SECONDS, rate: Int = 16_000): ByteArray {
    val samples = seconds * rate
    val out = java.io.ByteArrayOutputStream(44 + samples * 2)
    fun le(value: Int, bytes: Int) = repeat(bytes) { out.write(value shr (8 * it) and 0xFF) }
    out.write("RIFF".toByteArray()); le(36 + samples * 2, 4); out.write("WAVEfmt ".toByteArray()); le(16, 4); le(1, 2); le(1, 2)
    le(rate, 4); le(rate * 2, 4); le(2, 2); le(16, 2); out.write("data".toByteArray()); le(samples * 2, 4)
    for (i in 0 until samples) le((sin(2 * PI * 440 * i / rate) * 8191).toInt(), 2)
    return out.toByteArray()
}

/**
 * The transferred tone as AAC in an MP4 file, the container a real download is kept in, so that playing a
 * download here takes the same path through the player as playing a real one.
 */
internal fun toneToMp4(source: java.io.File, output: java.io.File, check: () -> Unit, rate: Int = 16_000) {
    val pcm = source.readBytes().let { it.copyOfRange(44, it.size) } // after toneWav's header
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, 64_000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
    }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var track = -1; var offset = 0; var fed = false; var done = false
    val info = MediaCodec.BufferInfo()
    codec.start()
    try {
        while (!done) {
            check()
            if (!fed) codec.dequeueInputBuffer(10_000).takeIf { it >= 0 }?.let { index ->
                val buffer = checkNotNull(codec.getInputBuffer(index))
                val count = minOf(buffer.remaining(), pcm.size - offset)
                buffer.put(pcm, offset, count)
                fed = offset + count >= pcm.size
                codec.queueInputBuffer(index, 0, count, offset / 2 * 1_000_000L / rate, if (fed) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                offset += count
            }
            val index = codec.dequeueOutputBuffer(info, 10_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.outputFormat); muxer.start() }
            else if (index >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) muxer.writeSampleData(track, checkNotNull(codec.getOutputBuffer(index)), info)
                done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index, false)
            }
        }
    } finally {
        runCatching { codec.stop() }; codec.release()
        runCatching { muxer.stop() }; muxer.release()
    }
}

/** A 512 pixel square in two colours taken from [name], as a JPEG: enough for the app to decode and scale. */
internal fun pictureFor(name: String): ByteArray {
    val seed = name.hashCode()
    val bitmap = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
    val paint = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, 512f, 512f, 0xFF202020.toInt() or (seed and 0x7F7F7F),
            0xFF404040.toInt() or (seed.rotateLeft(11) and 0x7F7F7F), android.graphics.Shader.TileMode.CLAMP)
    }
    android.graphics.Canvas(bitmap).drawPaint(paint)
    return java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it); bitmap.recycle() }.toByteArray()
}

/**
 * The tone at any path but /art/, and a picture under /art/, over HTTP on the loopback address, with single byte
 * ranges. [latencyMs] waits before each tone answer and [bytesPerSecond] paces its body, for the "controlled
 * online" measurement (100 ms and 625 000 for 5 Mbit/s).
 */
internal class ToneServer(private val latencyMs: Long, private val bytesPerSecond: Long) {
    val tone: ByteArray by lazy { toneWav() }
    private val pictures = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    private val workers = Executors.newCachedThreadPool { Thread(it, "benchmark-tone").apply { isDaemon = true } }

    init { workers.execute { while (!socket.isClosed) runCatching { socket.accept() }.onSuccess { client -> workers.execute { runCatching { client.use(::answer) } } } } }

    private fun answer(client: Socket) {
        client.soTimeout = 5_000
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine()?.split(' ') ?: return
        var range: String? = null
        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim() }
        val path = request.getOrNull(1).orEmpty()
        if (path.startsWith("/art/")) {
            val picture = pictures.getOrPut(path.substringBefore('=')) { pictureFor(path.substringBefore('=')) }
            client.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nCache-Control: max-age=86400\r\nContent-Length: ${picture.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                if (request.firstOrNull() != "HEAD") write(picture)
            }
            return
        }
        if (latencyMs > 0) Thread.sleep(latencyMs)
        val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val from = match?.groupValues?.get(1)?.toIntOrNull()?.coerceAtMost(tone.size) ?: 0
        val to = match?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(tone.size - 1) ?: (tone.size - 1)
        val length = (to - from + 1).coerceAtLeast(0)
        val out = client.getOutputStream()
        val head = (if (match != null) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$to/${tone.size}\r\n" else "HTTP/1.1 200 OK\r\n") +
            "Content-Type: audio/wav\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (request.firstOrNull() == "HEAD") return
        if (bytesPerSecond <= 0) { out.write(tone, from, length); return }
        // A tenth of a second of the allowance at a time.
        val step = (bytesPerSecond / 10).toInt().coerceAtLeast(1)
        var sent = 0
        while (sent < length) { val n = minOf(step, length - sent); out.write(tone, from + sent, n); out.flush(); sent += n; if (sent < length) Thread.sleep(100) }
    }
}
