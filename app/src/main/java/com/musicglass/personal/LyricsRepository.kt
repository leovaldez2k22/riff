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
import kotlinx.coroutines.flow.first
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
    /** How long one provider (search plus candidates) may take. */
    private val providerLimitMs: Long = 15_000,
    /** Q18-082: how long the word-timed sources have to themselves before the others are asked too. */
    private val staggerMs: Long = 1_000,
    /** Q18-082: a same-tier answer from a higher-ranked source replaces what is shown only this soon after it was shown. */
    private val swapWindowMs: Long = 2_000,
    private val transport: (suspend (String,Map<String,String>) -> JsonElement?)? = null) {
    private val clock: () -> Long = { System.nanoTime()/1_000_000 }
    private constructor(context: Context): this(File(context.cacheDir,"timed-lyrics-v1")) {
        offlineReader={track->if(track.mediaId.startsWith("yt:")) OfflineMusicStore.get(context).let {it.lyrics(it.currentScope,track.mediaId.removePrefix("yt:"))} else null}
    }
    companion object {
        internal const val WORD_RETRY_MS = 12*3600_000L
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
    private val gates = LyricsGates()
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
        val key = cacheKey(track)
        val offline = offlineReader?.invoke(track)
        offline?.let { send(it) }
        var known: LyricsDocument? = offline
        if (!force && offline?.timed != true && offline?.instrumental != true) {
            val cached = withContext(Dispatchers.IO) { readCache(key) }
            if (cached != null) { send(cached); known = cached }
        } else if (!force && offline?.timed == true) {
            // A download's line-timed lyrics may have been bettered since (Q18-003).
            withContext(Dispatchers.IO) { readCache(key) }?.takeIf { it.quality > offline.quality }?.let { send(it); known = it }
        }
        if (known?.wordTimed == true || known?.instrumental == true) return@channelFlow
        // Line timing is shown at once, but word timing may exist and have been slow or missing then: ask the
        // word-timed sources again, no more often than every 12 hours (Q18-003).
        val upgrading = known?.timed == true
        if (upgrading && !force && !withContext(Dispatchers.IO) { wordRetryDue(key) }) return@channelFlow
        if (!force && !upgrading && synchronized(missing) { (missing[key] ?: 0) > (System.nanoTime()/1_000_000) }) return@channelFlow
        var best: LyricsDocument? = known
        var shownAt = clock()
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val answered = java.util.concurrent.atomic.AtomicInteger()
        val ranking = Mutex()
        // Q18-082: the sources are asked together, not one after another. Each one running is a job; one that can no
        // longer give anything better than what is shown is cancelled.
        class Source(val name: String, val bestTier: Int, val run: suspend () -> Unit) { var job: Job? = null; var timedOut = false }
        val running = mutableListOf<Source>()
        val settled = kotlinx.coroutines.flow.MutableStateFlow(0)
        fun canStillOutrank(source: Source): Boolean {
            val shown = best ?: return true
            return source.bestTier > shown.quality || (source.bestTier == shown.quality &&
                lyricsProviderPriority(source.name) < lyricsProviderPriority(shown.source) && clock() - shownAt <= swapWindowMs)
        }
        fun cancelOutranked() = synchronized(running) { running.filter { it.job?.isActive == true && !canStillOutrank(it) }.forEach { it.job?.cancel() } }
        suspend fun offer(doc: LyricsDocument?) {
            if (doc == null || (!doc.instrumental && doc.lines.isEmpty())) return
            ranking.withLock {
                if (lyricsOutranks(doc, best, clock() - shownAt, swapWindowMs)) { best = doc; shownAt = clock(); send(doc) }
            }
            cancelOutranked(); settled.update { it + 1 }
        }
        suspend fun request(url: String, params: Map<String,String>): JsonElement? = try {
            get(url,params).also { answered.incrementAndGet() }
        } catch (e: CancellationException) { throw e }
        catch (_: LyricsCacheMiss) { answered.incrementAndGet(); null }
        catch (_: Exception) { errors.incrementAndGet(); null }
        /** Starts [source] unless nothing it can find would be shown. A whole source (search plus candidates) is bounded. */
        fun CoroutineScope.start(source: Source) {
            if (!canStillOutrank(source)) return
            synchronized(running) { running += source }
            source.timedOut = false
            source.job = launch {
                try {
                    if (withTimeoutOrNull(providerLimitMs) { source.run(); true } == null) { source.timedOut = true; errors.incrementAndGet() }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { errors.incrementAndGet() }
                finally { settled.update { it + 1 } }
            }
        }
        /** Waits for every source started, cancelling those the swap window has put out of reach as it closes. */
        suspend fun CoroutineScope.awaitSources() {
            val watch = launch { while (true) { delay(LYRICS_WATCH_MS); cancelOutranked() } }
            synchronized(running) { running.mapNotNull { it.job } }.joinAll(); watch.cancel()
        }
        val title = lyricsQueryTitle(track.title)
        val artist = playerArtistLabel(track.artist).removeSuffix(" - Topic")
        val params = mapOf("s" to title, "a" to artist, "al" to track.album,
            "d" to ((track.durationMs + 500)/1000).toString())
        val wordSources = listOf(Source("Better Lyrics", 3) {
            // A miss is asked once more the way the Better Lyrics extension fills its cache (Q18-065).
            val row = (request("https://api.betterlyrics.org/getLyrics",params)
                ?: betterLyricsLooserQuery(params)?.let { request("https://api.betterlyrics.org/getLyrics",it) }) as? JsonObject
            val score = row?.get("score")?.jsonPrimitive?.doubleOrNull
            offer(row?.get("ttml")?.jsonPrimitive?.contentOrNull?.takeIf { score == null || score >= .8 }
                ?.let { parseTtml(it,track.durationMs) })
        }, Source("Better Lyrics · QQ", 3) {
            val row = request("https://api.betterlyrics.org/qq/getLyrics",params) as? JsonObject
            val score = row?.get("score")?.jsonPrimitive?.doubleOrNull
            offer(row?.get("lyrics")?.jsonPrimitive?.contentOrNull?.takeIf { score == null || score >= .8 }
                ?.let { parseQrc(it,track.durationMs,track.title,track.artist) })
        }, Source("NetEase Cloud Music", 3) { offer(netEase(track,::request)) })
        val lineSources = listOf(Source("Better Lyrics · Kugou", 2) {
            val row = request("https://api.betterlyrics.org/kugou/getLyrics",params) as? JsonObject
            val score = row?.get("score")?.jsonPrimitive?.doubleOrNull
            offer(row?.get("lyrics")?.jsonPrimitive?.contentOrNull?.takeIf { score == null || score >= .8 }
                ?.let { parseLrc(it,track.durationMs,"Better Lyrics · Kugou","https://betterlyrics.org") })
        }, Source("LRCLIB", 2) {
            val query = mutableMapOf("track_name" to title, "artist_name" to artist,
                "duration" to (track.durationMs/1000.0).toString())
            if (track.album.isNotBlank()) query["album_name"] = track.album
            val exact = request("https://lrclib.net/api/get",query) as? JsonObject
            val doc = exact?.let { fromLrclib(it,track) }
            offer(doc)
            if (doc?.wordTimed != true && doc?.instrumental != true) {
                val results = request("https://lrclib.net/api/search",query.filterKeys { it != "duration" }) as? JsonArray
                results.orEmpty().mapNotNull { it as? JsonObject }.sortedByDescending { score(it,track) }
                    .filter { score(it,track) > 0 }.forEach { offer(fromLrclib(it,track)) }
            }
        }) + listOfNotNull(if (track.mediaId.startsWith("yt:")) Source("YouTube Music", 2) {
            try { offer(youtube()); answered.incrementAndGet() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { errors.incrementAndGet() }
        } else null)
        // This provider supplies plain text, so it is asked only when nothing synced was found.
        val plain = Source("Lyrics.ovh", 0) { offer(ovh(track,::request)) }
        coroutineScope {
            wordSources.forEach { start(it) }
            if (!upgrading) {
                // The others start a moment later, and only while no word timing has come: the three that may have it
                // answer first without sharing the connection.
                withTimeoutOrNull(staggerMs) {
                    settled.first { best?.wordTimed == true || wordSources.none { it.job?.isActive == true } }
                }
                lineSources.forEach { start(it) }
            }
            awaitSources()
        }
        if (!upgrading && best?.timed != true && best?.instrumental != true) coroutineScope { start(plain); awaitSources() }
        // A word-timed source that answered too slowly the first time (BetterLyrics on a song's first request can
        // take longer than the limit) gets one more turn; its answer replaces the line timing already shown.
        if (best?.wordTimed != true) coroutineScope { wordSources.filter { it.timedOut }.forEach { start(it) }; awaitSources() }
        if (upgrading) {
            withContext(Dispatchers.IO) { if (best !== known) writeCache(key,best!!) else markWordRetry(key) }
            return@channelFlow
        }
        val found = best
        if (found != null) withContext(Dispatchers.IO) { writeCache(key,found) }
        else if (lyricsUnreachable(errors.get(),answered.get())) throw IOException("Lyrics providers could not be reached")
        else synchronized(missing) {
            if (missing.size > 100) missing.clear()
            missing[key] = (System.nanoTime()/1_000_000)+5*60_000
        }
    }
    internal suspend fun netEase(track: LyricsTrack,
        request: suspend (String,Map<String,String>) -> JsonElement? = ::get): LyricsDocument? {
        val title = lyricsQueryTitle(track.title)
        val artist = playerArtistLabel(track.artist).removeSuffix(" - Topic")
        val search = request("https://music.163.com/api/search/get",
            mapOf("s" to "$title $artist", "type" to "1", "limit" to "10", "offset" to "0")) as? JsonObject
        checkNetEaseResponse(search)
        val songs = (search?.get("result") as? JsonObject)?.get("songs") as? JsonArray
        var best: LyricsDocument? = null
        for ((song,_) in songs.orEmpty().mapNotNull { it as? JsonObject }
            .map { it to netEaseMatchScore(it,track) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(3)) {
            val id = song["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[0-9]+")) } ?: continue
            val row = request("https://music.163.com/api/song/lyric",
                mapOf("id" to id, "lv" to "-1", "kv" to "-1", "tv" to "-1", "yv" to "-1")) as? JsonObject
            checkNetEaseResponse(row)
            val doc = netEaseLyrics(row,track.durationMs,id)
            if (doc != null && (best == null || doc.quality > best.quality)) best = doc
            if (best?.wordTimed == true) break
        }
        return best
    }
    internal suspend fun ovh(track: LyricsTrack,
        request: suspend (String,Map<String,String>) -> JsonElement? = ::get): LyricsDocument? {
        val artist = playerArtistLabel(track.artist).removeSuffix(" - Topic")
        val url = "https://api.lyrics.ovh".toHttpUrl().newBuilder().addPathSegment("v1")
            .addPathSegment(artist.take(220)).addPathSegment(lyricsQueryTitle(track.title).take(220)).build()
        val row = request(url.toString(),emptyMap()) as? JsonObject
        return row?.get("lyrics")?.jsonPrimitive?.contentOrNull?.let { plainLyrics(it,"Lyrics.ovh","https://lyrics.ovh") }
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
        val queue = lyricsQueueKey(url.host, url.encodedPath)
        return gates.run(url.host, queue) {
            val asked = System.nanoTime()
            val request = Request.Builder().url(url).header("Accept","application/json")
                .header("User-Agent","Riff/${BuildConfig.VERSION_NAME} (Android app)")
                .apply { if (url.host == "music.163.com") header("Referer","https://music.163.com/") }
                .build()
            val result: Pair<Int,String?> = suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try { response.use {
                            gates.answered(url.host, queue, it.code, if (it.code == 429) retryAfterMs(it.header("Retry-After")) else 0L,
                                it.header("X-RateLimit-Remaining")?.trim()?.toIntOrNull())
                            // Q18-075: a slow answer and a miss in Better Lyrics' cache look alike without this.
                            if (BuildConfig.DEBUG && url.host == "api.betterlyrics.org") diagnosticLogcat("RiffLyrics",
                                "lyrics_answer source=${betterLyricsSource(url.encodedPath)} status=${it.code} cache=${it.header("X-Cache-Status") ?: "none"} " +
                                "ms=${(System.nanoTime() - asked)/1_000_000}")
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
            if (result.first == 401 && url.host == "api.betterlyrics.org") throw LyricsCacheMiss()
            if (result.first >= 500 || result.first == 429 || result.first == 401 || result.first == 403) throw IOException("Lyrics source temporarily unavailable")
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
    // v3 (Q16A): background vocals are now parsed apart, so lyrics cached before are fetched once more.
    private fun cacheKey(track: LyricsTrack): String = MessageDigest.getInstance("SHA-256")
        .digest("hierarchy-v3\u0000${track.mediaId}\u0000${track.title}\u0000${track.artist}\u0000${track.album}\u0000${track.durationMs}".toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun readCache(key: String): LyricsDocument? = runCatching {
        val file = File(directory,"$key.json")
        if (!file.exists() || System.currentTimeMillis()-file.lastModified() > 7*86400_000L || file.length() > 1_000_000) return null
        decodeLyricsDocument(json.parseToJsonElement(file.readText()) as JsonObject)
    }.getOrNull()
    /** Word timing is asked for again once 12 hours have passed since the last search for this song. */
    private fun wordRetryDue(key: String): Boolean {
        val last = maxOf(File(directory,"$key.retry").lastModified(),File(directory,"$key.json").lastModified())
        return System.currentTimeMillis()-last >= WORD_RETRY_MS
    }
    private fun markWordRetry(key: String) = runCatching {
        directory.mkdirs()
        val marker = File(directory,"$key.retry")
        if (!marker.createNewFile()) marker.setLastModified(System.currentTimeMillis())
        directory.listFiles()?.filter { it.extension == "retry" }?.sortedByDescending(File::lastModified)?.drop(80)?.forEach(File::delete)
    }
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
private fun encodeLyricParts(parts: List<LyricPart>) = buildJsonArray { parts.forEach { part -> add(buildJsonObject {
    put("start",part.startMs); put("end",part.endMs); put("from",part.from); put("to",part.to)
}) } }
private fun decodeLyricParts(raw: JsonElement?) = (raw as? JsonArray).orEmpty().map { partNode ->
    val p = partNode.jsonObject
    LyricPart(p.getValue("start").jsonPrimitive.long,p.getValue("end").jsonPrimitive.long,
        p.getValue("from").jsonPrimitive.int,p.getValue("to").jsonPrimitive.int)
}
/** A line without a background vocal is written exactly as before Q16A, and files without "backing" still read. */
internal fun encodeLyricsDocument(doc: LyricsDocument): JsonObject = buildJsonObject {
    put("source",doc.source); put("url",doc.sourceUrl); put("instrumental",doc.instrumental)
    putJsonArray("lines") { doc.lines.forEach { line -> add(buildJsonObject {
        put("start",line.startMs); put("end",line.endMs); put("text",line.text)
        put("parts",encodeLyricParts(line.parts))
        line.backing?.let { backing -> put("backing",buildJsonObject {
            put("start",backing.startMs); put("end",backing.endMs); put("text",backing.text)
            put("parts",encodeLyricParts(backing.parts))
        }) }
    }) } }
}
internal fun decodeLyricsDocument(raw: JsonObject): LyricsDocument = LyricsDocument(
    raw.getValue("source").jsonPrimitive.content,raw.getValue("url").jsonPrimitive.content,
    (raw["lines"] as JsonArray).take(2000).map { node -> val line = node.jsonObject
        LyricLine(line.getValue("start").jsonPrimitive.long,line.getValue("end").jsonPrimitive.long,
            line.getValue("text").jsonPrimitive.content,decodeLyricParts(line["parts"]),
            (line["backing"] as? JsonObject)?.let { backing -> LyricBacking(backing.getValue("start").jsonPrimitive.long,
                backing.getValue("end").jsonPrimitive.long,backing.getValue("text").jsonPrimitive.content,
                decodeLyricParts(backing["parts"])) })
    },raw["instrumental"]?.jsonPrimitive?.booleanOrNull ?: false)

internal data class LyricsLoadState(val document: LyricsDocument? = null,val loading: Boolean = true,val failed: Boolean = false,val generation: Long = 0)

/** Better Lyrics answers lookups without a key from its cache only; a song not in it answers 401 "API key required". */
internal class LyricsCacheMiss : IOException("Not in the lyrics cache")

/**
 * The extension that fills Better Lyrics' cache names every linked artist, joined by ", ", and often no album; a
 * stricter key misses (Q18-065). Null when the looser query would be the same one.
 */
internal fun betterLyricsLooserQuery(params: Map<String,String>): Map<String,String>? {
    val artist = params["a"].orEmpty().replace(Regex("\\s*,?\\s*&\\s*"),", ")
    val looser = (params - "al") + ("a" to artist)
    return looser.takeIf { it != params.filterValues(String::isNotBlank) }
}

/** "Couldn't load lyrics" only when no source answered; otherwise the song simply has none. */
internal fun lyricsUnreachable(errors: Int, answered: Int): Boolean = errors > 0 && answered == 0

/** Q18-082: a better timing tier always replaces what is shown; the same tier from a higher-ranked source only soon after. */
internal fun lyricsOutranks(doc: LyricsDocument, shown: LyricsDocument?, shownForMs: Long, swapWindowMs: Long): Boolean =
    shown == null || doc.quality > shown.quality || (doc.quality == shown.quality &&
        lyricsProviderPriority(doc.source) < lyricsProviderPriority(shown.source) && shownForMs <= swapWindowMs)

/** A Better Lyrics endpoint as one word for the diagnostic log. */
internal fun betterLyricsSource(path: String): String = when { path.startsWith("/qq/") -> "qq"; path.startsWith("/kugou/") -> "kugou"; else -> "main" }

/** Each Better Lyrics endpoint has its own queue; any other site has one. */
internal fun lyricsQueueKey(host: String, path: String): String = if (host == "api.betterlyrics.org") host + path else host

/**
 * Q18-082 (7): requests to a lyrics site. One at a time in each queue, [spacingMs] apart; a 429 from any queue pauses
 * every queue of that site for its Retry-After, and while the site says few requests are left its queues go one at a
 * time together. Better Lyrics' rate limit is per address and shared by its endpoints.
 */
internal class LyricsGates(private val spacingMs: Long = 400, private val clock: () -> Long = { System.nanoTime()/1_000_000 }) {
    private val queues = java.util.concurrent.ConcurrentHashMap<String,Mutex>()
    private val together = java.util.concurrent.ConcurrentHashMap<String,Mutex>()
    private val nextAt = mutableMapOf<String,Long>()
    private val pausedUntil = mutableMapOf<String,Long>()
    private val low = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun waitMs(host: String, queue: String): Long = synchronized(this) { maxOf(nextAt[queue] ?: 0, pausedUntil[host] ?: 0) - clock() }
    suspend fun <T> run(host: String, queue: String, block: suspend () -> T): T {
        suspend fun inQueue() = queues.getOrPut(queue) { Mutex() }.withLock {
            val wait = waitMs(host, queue)
            if (wait > 30_000) throw IOException("Lyrics source is cooling down")
            if (wait > 0) delay(wait)
            block()
        }
        return if (host in low) together.getOrPut(host) { Mutex() }.withLock { inQueue() } else inQueue()
    }
    fun answered(host: String, queue: String, code: Int, retryAfterMs: Long, remaining: Int?) {
        synchronized(this) {
            nextAt[queue] = clock() + spacingMs
            if (code == 429) pausedUntil[host] = clock() + retryAfterMs
        }
        when {
            remaining == null -> Unit
            remaining <= LYRICS_FEW_REQUESTS_LEFT -> low += host
            else -> low -= host
        }
    }
}
internal const val LYRICS_FEW_REQUESTS_LEFT = 5
/** How often a search checks whether a running source has been put out of reach by the swap window closing. */
private const val LYRICS_WATCH_MS = 100L
