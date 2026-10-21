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
    /** How long one provider (search plus candidates) may take before the next is asked. */
    private val providerLimitMs: Long = 15_000,
    private val transport: (suspend (String,Map<String,String>) -> JsonElement?)? = null) {
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
    private val gates = listOf("lrclib.net", "api.betterlyrics.org", "music.163.com", "api.lyrics.ovh").associateWith { Mutex() }
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
        var errors = 0
        var answered = 0
        suspend fun offer(doc: LyricsDocument?) {
            if (doc == null || (!doc.instrumental && doc.lines.isEmpty())) return
            // Providers run in priority order. Only a better timing tier replaces a result.
            if (best == null || doc.quality > best!!.quality ||
                (doc.quality == best!!.quality && lyricsProviderPriority(doc.source) < lyricsProviderPriority(best!!.source))) {
                best = doc; send(doc)
            }
        }
        suspend fun request(url: String, params: Map<String,String>): JsonElement? = try {
            get(url,params).also { answered++ }
        } catch (e: CancellationException) { throw e }
        catch (_: LyricsCacheMiss) { answered++; null }
        catch (_: Exception) { errors++; null }
        /** Runs [block] unless word timing is already found; true when it ran out of time. */
        suspend fun provider(block: suspend () -> Unit): Boolean {
            if (best?.wordTimed == true) return false
            // Bound a whole provider (search plus candidates), not just each HTTP call.
            return try {
                (withTimeoutOrNull(providerLimitMs) { block(); true } == null).also { if (it) errors++ }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { errors++; false }
        }
        val title = lyricsQueryTitle(track.title)
        val artist = playerArtistLabel(track.artist).removeSuffix(" - Topic")
        val params = mapOf("s" to title, "a" to artist, "al" to track.album,
            "d" to ((track.durationMs + 500)/1000).toString())
        val wordSources: List<suspend () -> Unit> = listOf({
            // A miss is asked once more the way the Better Lyrics extension fills its cache (Q18-065).
            val row = (request("https://api.betterlyrics.org/getLyrics",params)
                ?: betterLyricsLooserQuery(params)?.let { request("https://api.betterlyrics.org/getLyrics",it) }) as? JsonObject
            val score = row?.get("score")?.jsonPrimitive?.doubleOrNull
            offer(row?.get("ttml")?.jsonPrimitive?.contentOrNull?.takeIf { score == null || score >= .8 }
                ?.let { parseTtml(it,track.durationMs) })
        },{
            val row = request("https://api.betterlyrics.org/qq/getLyrics",params) as? JsonObject
            val score = row?.get("score")?.jsonPrimitive?.doubleOrNull
            offer(row?.get("lyrics")?.jsonPrimitive?.contentOrNull?.takeIf { score == null || score >= .8 }
                ?.let { parseQrc(it,track.durationMs,track.title,track.artist) })
        },{ offer(netEase(track,::request)) })
        val slow = wordSources.filter { provider(it) }
        if (!upgrading) {
            provider {
                val row = request("https://api.betterlyrics.org/kugou/getLyrics",params) as? JsonObject
                val score = row?.get("score")?.jsonPrimitive?.doubleOrNull
                offer(row?.get("lyrics")?.jsonPrimitive?.contentOrNull?.takeIf { score == null || score >= .8 }
                    ?.let { parseLrc(it,track.durationMs,"Better Lyrics · Kugou","https://betterlyrics.org") })
            }
            provider {
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
            }
            if (track.mediaId.startsWith("yt:")) provider {
                try { offer(youtube()); answered++ } catch (e: CancellationException) { throw e }
                catch (_: Exception) { errors++ }
            }
            // This provider supplies plain text, so there is no benefit in requesting it after synced lyrics.
            if (best?.timed != true && best?.instrumental != true) provider {
                offer(ovh(track,::request))
            }
        }
        // A word-timed source that answered too slowly the first time (BetterLyrics on a song's first request can
        // take longer than the limit) gets one more turn; its answer replaces the line timing already shown.
        if (best?.wordTimed != true) slow.forEach { provider(it) }
        if (upgrading) {
            withContext(Dispatchers.IO) { if (best !== known) writeCache(key,best!!) else markWordRetry(key) }
            return@channelFlow
        }
        val found = best
        if (found != null) withContext(Dispatchers.IO) { writeCache(key,found) }
        else if (lyricsUnreachable(errors,answered)) throw IOException("Lyrics providers could not be reached")
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
        return gates.getValue(url.host).withLock {
            val wait = synchronized(nextRequest) { (nextRequest[url.host] ?: 0)-(System.nanoTime()/1_000_000) }
            if (wait > 30_000) throw IOException("Lyrics source is cooling down")
            if (wait > 0) delay(wait)
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
