package com.musicglass.personal

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Public metadata only. This client never receives YouTube/account cookies. */
internal class LyricsRepository(private val directory: File,
    private val transport: (suspend (String,Map<String,String>) -> JsonElement?)? = null) {
    private constructor(context: Context): this(File(context.cacheDir,"timed-lyrics-v1")) {
        offlineReader={track->if(track.mediaId.startsWith("yt:")) OfflineMusicStore.get(context).let {it.lyrics(it.currentScope,track.mediaId.removePrefix("yt:"))} else null}
    }
    companion object {
        @Volatile private var shared: LyricsRepository? = null
        /**
         * One per process. Building it (an OkHttp client) on every activity recreation (rotation, dark
         * mode, font size) cost main-thread time and threw away in-flight lyrics loads.
         */
        fun get(context: Context): LyricsRepository = shared ?: synchronized(this) {
            shared ?: LyricsRepository(context.applicationContext).also { shared = it }
        }
    }
    private val client = OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS)
        .readTimeout(8,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).build()
    private val gates = mapOf("lrclib.net" to Mutex(),"api.betterlyrics.org" to Mutex())
    private val nextRequest = mutableMapOf<String,Long>()
    private val missing = mutableMapOf<String,Long>()
    private val json = Json { ignoreUnknownKeys = true }

    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private data class Entry(val state: kotlinx.coroutines.flow.MutableStateFlow<LyricsLoadState>, var job: Job? = null)
    private val loads = linkedMapOf<String,Entry>()
    private var offlineReader:((LyricsTrack)->LyricsDocument?)?=null
    fun observe(track: LyricsTrack, force: Boolean = false, youtube: suspend () -> LyricsDocument?): kotlinx.coroutines.flow.StateFlow<LyricsLoadState> = synchronized(loads) {
        val key = cacheKey(track)
        val entry = loads.getOrPut(key) { Entry(kotlinx.coroutines.flow.MutableStateFlow(LyricsLoadState())) }
        if (force || entry.job == null) {
            entry.job?.cancel()
            val generation = entry.state.value.generation+1
            entry.state.value = LyricsLoadState(entry.state.value.document, loading=true, generation=generation)
            entry.job = scope.launch {
                try {
                    lyrics(track,force,youtube).collect { doc ->
                        entry.state.update {current -> if(current.generation==generation) LyricsLoadState(doc,loading=true,generation=generation) else current}
                    }
                    entry.state.update {current -> if(current.generation==generation) current.copy(loading=false) else current}
                } catch(e: CancellationException) { throw e }
                catch(_: Exception) { entry.state.update {current -> if(current.generation==generation) current.copy(loading=false,failed=true) else current} }
            }
        }
        if(loads.size>8) loads.entries.firstOrNull {it.key!=key && it.value.job?.isActive!=true}?.let {loads.remove(it.key)}
        entry.state
    }
    fun preload(track: LyricsTrack, youtube: suspend () -> LyricsDocument?) { observe(track,youtube=youtube) }

    fun lyrics(track: LyricsTrack, force: Boolean = false,
        youtube: suspend () -> LyricsDocument?): Flow<LyricsDocument> = channelFlow {
        if (track.title.isBlank() || track.artist.isBlank() || track.durationMs <= 0) return@channelFlow
        val offline = offlineReader?.invoke(track)
        offline?.let {
            send(it)
            if (it.timed || it.instrumental) return@channelFlow
        }
        val key = cacheKey(track)
        var cachedFallback: LyricsDocument?=offline
        if (!force) {
            val cached = withContext(Dispatchers.IO) { readCache(key) }
            if (cached != null && cached.source.startsWith("Better Lyrics")) { send(cached); return@channelFlow }
            cachedFallback=cached ?: offline
            if (synchronized(missing) { (missing[key] ?: 0) > (System.nanoTime()/1_000_000) }) return@channelFlow
        }
        val selection = Mutex()
        var best: LyricsDocument? = null
        var errors = 0
        suspend fun offer(doc: LyricsDocument?) {
            if (doc == null || (!doc.instrumental && doc.lines.isEmpty())) return
            selection.withLock {
                if (best == null || doc.quality > best!!.quality ||
                    (doc.quality == best!!.quality && doc.timed && doc.source == "YouTube Music" && best!!.source != "YouTube Music")) { best = doc; send(doc) }
            }
        }
        suspend fun request(url: String, params: Map<String,String>): JsonElement? = try { get(url,params) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { selection.withLock { errors++ }; null }
        run {
                val params = mapOf("s" to lyricsQueryTitle(track.title),
                    "a" to playerArtistLabel(track.artist).removeSuffix(" - Topic"),"al" to track.album,
                    "d" to ((track.durationMs + 500)/1000).toString())
                val ttml = request("https://api.betterlyrics.org/getLyrics",params) as? JsonObject
                val score = ttml?.get("score")?.jsonPrimitive?.doubleOrNull
                val doc = ttml?.get("ttml")?.jsonPrimitive?.contentOrNull?.takeIf { score == null || score >= .8 }
                    ?.let { parseTtml(it,track.durationMs) }
                offer(doc)
                if (doc?.wordTimed != true) {
                    val qq = request("https://api.betterlyrics.org/qq/getLyrics",params) as? JsonObject
                    val qScore = qq?.get("score")?.jsonPrimitive?.doubleOrNull
                    val words = qq?.get("lyrics")?.jsonPrimitive?.contentOrNull?.takeIf { qScore == null || qScore >= .8 }
                        ?.let { parseQrc(it,track.durationMs,track.title,track.artist) }
                    offer(words)
                    if (words?.wordTimed != true && selection.withLock { best?.timed != true }) {
                        val kugou = request("https://api.betterlyrics.org/kugou/getLyrics",params) as? JsonObject
                        offer(kugou?.get("lyrics")?.jsonPrimitive?.contentOrNull?.let {
                            parseLrc(it,track.durationMs,"Better Lyrics \u00B7 Kugou","https://betterlyrics.org") })
                    }
                }
        }
        // Better Lyrics has exclusive priority, including its line-timed/plain documents.
        if(best==null) coroutineScope {
            offer(cachedFallback)
            launch {
                val params = mutableMapOf("track_name" to lyricsQueryTitle(track.title),
                    "artist_name" to playerArtistLabel(track.artist).removeSuffix(" - Topic"),
                    "duration" to (track.durationMs/1000.0).toString())
                if (track.album.isNotBlank()) params["album_name"] = track.album
                val exact = request("https://lrclib.net/api/get",params) as? JsonObject
                val doc = exact?.let { fromLrclib(it,track) }
                offer(doc)
                if (doc?.timed != true && doc?.instrumental != true) {
                    val results = request("https://lrclib.net/api/search",params.filterKeys { it != "duration" }) as? JsonArray
                    results.orEmpty().mapNotNull { it as? JsonObject }.sortedByDescending { score(it,track) }
                        .filter { score(it,track) > 0 }.forEach { offer(fromLrclib(it,track)) }
                }
            }
            if (track.mediaId.startsWith("yt:")) launch {
                try { offer(youtube()) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { selection.withLock { errors++ } }
            }
        }
        val found = best
        if (found != null) withContext(Dispatchers.IO) { writeCache(key,found) }
        else if (errors > 0) throw IOException("Lyrics providers could not be reached")
        else synchronized(missing) {
            if (missing.size > 100) missing.clear()
            missing[key] = (System.nanoTime()/1_000_000)+5*60_000
        }
    }
    private fun score(row: JsonObject, track: LyricsTrack): Double = lyricsMatchScore(track,
        row["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty(),row["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        ((row["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0)*1000).toLong())
    private fun fromLrclib(row: JsonObject, track: LyricsTrack): LyricsDocument? {
        if (score(row,track) <= 0) return null
        val id = row["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.all(Char::isDigit) }
        val url = if (id != null) "https://lrclib.net/lyrics/$id" else "https://lrclib.net"
        row["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.let {
            parseLrc(it,track.durationMs,"LRCLIB",url)?.let { doc -> return doc }
        }
        if (row["instrumental"]?.jsonPrimitive?.booleanOrNull == true) return LyricsDocument("LRCLIB",url,emptyList(),true)
        return row["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
            LyricsDocument("LRCLIB",url,it.lineSequence().take(2000).map { text -> LyricLine(-1,-1,text) }.toList()) }
    }
    private suspend fun get(endpoint: String, params: Map<String,String>): JsonElement? {
        transport?.let { return it(endpoint,params) }
        val url = endpoint.toHttpUrl().newBuilder().apply {
            params.forEach { (name,value) -> addQueryParameter(name,value.take(220)) }
        }.build()
        return gates.getValue(url.host).withLock {
            val wait = synchronized(nextRequest) { (nextRequest[url.host] ?: 0)-(System.nanoTime()/1_000_000) }
            if (wait > 30_000) throw IOException("Lyrics source is cooling down")
            if (wait > 0) delay(wait)
            val request = Request.Builder().url(url).header("Accept","application/json")
                .header("User-Agent","Riff/${BuildConfig.VERSION_NAME} (Android app)").build()
            val result: Pair<Int,String?> = suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try { response.use {
                            val cooldown = if (it.code == 429) retryAfterMs(it.header("Retry-After")) else 400L
                            synchronized(nextRequest) { nextRequest[url.host] = (System.nanoTime()/1_000_000)+cooldown }
                            val text = if (it.isSuccessful) {
                                val body = it.body
                                if (body.contentLength() > 1_000_000) throw IOException("Lyrics response too large")
                                val bytes = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                val stream = body.byteStream()
                                while (true) {
                                    val n = stream.read(buffer)
                                    if (n < 0) break
                                    if (bytes.size()+n > 1_000_000) throw IOException("Lyrics response too large")
                                    bytes.write(buffer,0,n)
                                }
                                bytes.toString("UTF-8")
                            } else null
                            if (continuation.isActive) continuation.resume(it.code to text)
                        } } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                })
            }
            if (result.first >= 500 || result.first == 429) throw IOException("Lyrics source temporarily unavailable")
            result.second?.let { json.parseToJsonElement(it) }
        }
    }
    private fun retryAfterMs(value: String?): Long {
        val seconds = value?.toLongOrNull()
        val millis = if (seconds != null) seconds.coerceIn(0,86400)*1000 else runCatching {
            ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis()
        }.getOrDefault(60_000)
        return millis.coerceIn(1000,86400_000)
    }
    private fun cacheKey(track: LyricsTrack): String = MessageDigest.getInstance("SHA-256")
        .digest("${track.mediaId}\u0000${track.title}\u0000${track.artist}\u0000${track.album}\u0000${track.durationMs}".toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun readCache(key: String): LyricsDocument? = runCatching {
        val file = File(directory,"$key.json")
        if (!file.exists() || System.currentTimeMillis()-file.lastModified() > 7*86400_000L || file.length() > 1_000_000) return null
        decodeLyricsDocument(json.parseToJsonElement(file.readText()) as JsonObject)
    }.getOrNull()
    private fun writeCache(key: String, doc: LyricsDocument) = runCatching {
        directory.mkdirs()
        val target = File(directory,"$key.json"); val temporary = File(directory,"$key.tmp")
        temporary.writeText(encodeLyricsDocument(doc).toString())
        if (!temporary.renameTo(target)) { target.writeText(temporary.readText()); temporary.delete() }
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending(File::lastModified)?.drop(80)?.forEach(File::delete)
    }
    // Called from onDispose on the main thread (every activity recreation). Evicting closes TLS
    // sockets, which writes close_notify to the network, so it runs off the main thread.
    /** For tests and short-lived instances only; the shared instance lives as long as the process. */
    fun close() { scope.cancel(); client.dispatcher.cancelAll(); kotlin.concurrent.thread(name="lyrics-close") { client.connectionPool.evictAll() } }
}
internal fun encodeLyricsDocument(doc: LyricsDocument): JsonObject = buildJsonObject {
    put("source",doc.source); put("url",doc.sourceUrl); put("instrumental",doc.instrumental)
    putJsonArray("lines") { doc.lines.forEach { line -> add(buildJsonObject {
        put("start",line.startMs); put("end",line.endMs); put("text",line.text)
        putJsonArray("parts") { line.parts.forEach { part -> add(buildJsonObject {
            put("start",part.startMs); put("end",part.endMs); put("from",part.from); put("to",part.to)
        }) } }
    }) } }
}
internal fun decodeLyricsDocument(raw: JsonObject): LyricsDocument = LyricsDocument(
    raw.getValue("source").jsonPrimitive.content,raw.getValue("url").jsonPrimitive.content,
    (raw["lines"] as JsonArray).take(2000).map { node -> val line = node.jsonObject
        LyricLine(line.getValue("start").jsonPrimitive.long,line.getValue("end").jsonPrimitive.long,
            line.getValue("text").jsonPrimitive.content,(line["parts"] as? JsonArray).orEmpty().map { partNode ->
                val p = partNode.jsonObject
                LyricPart(p.getValue("start").jsonPrimitive.long,p.getValue("end").jsonPrimitive.long,
                    p.getValue("from").jsonPrimitive.int,p.getValue("to").jsonPrimitive.int)
            })
    },raw["instrumental"]?.jsonPrimitive?.booleanOrNull ?: false)

internal data class LyricsLoadState(val document: LyricsDocument? = null,val loading: Boolean = true,val failed: Boolean = false,val generation: Long = 0)
