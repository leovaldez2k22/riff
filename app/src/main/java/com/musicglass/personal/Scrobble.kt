package com.musicglass.personal

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * Feature 2 (0.30.0, owner Q2): plays sent to ListenBrainz and Last.fm. A play is counted by its own listening meter,
 * kept as a pending row per service once it has been heard enough, and removed only when the service confirms it.
 * Each service is off until connected; its token is sealed with a key in Android Keystore and never leaves the phone
 * (not in the export file, the backup or diagnostics). Nothing is sent while listening history is off.
 */

internal enum class ScrobbleService(val key: String) { ListenBrainz("listenbrainz"), LastFm("lastfm") }

/** One play as it is sent. [id] is Riff's own, so a row is kept once; [device] is a file on this phone. */
internal data class ScrobblePlay(val id: String, val scope: String, val videoId: String?, val title: String, val artist: String,
    val album: String, val durationMs: Long, val startedAtMs: Long, val device: Boolean)

/** Last.fm's rule, which ListenBrainz follows: a track over 30 s, heard for half its length or four minutes. */
internal const val SCROBBLE_MIN_TRACK_MS = 30_000L
internal const val SCROBBLE_ENOUGH_MS = 240_000L
/** The most one tick may earn, and how far the clock may run ahead of the position (a late tick). */
internal const val SCROBBLE_TICK_CAP_MS = 5_000L
internal const val SCROBBLE_SLACK_MS = 1_500L
/** Rows kept per service, and for how long (owner, Q2: seven days). */
internal const val SCROBBLE_PENDING_LIMIT = 500
internal const val SCROBBLE_KEEP_MS = 7L * 24 * 60 * 60 * 1000
internal const val SCROBBLE_BATCH = 50

internal fun scrobbleThresholdMs(durationMs: Long): Long? =
    if (durationMs <= SCROBBLE_MIN_TRACK_MS) null else minOf(durationMs / 2, SCROBBLE_ENOUGH_MS)

/**
 * Heard time for the play that is on now. The clock is counted only while playing, never more than the position
 * moved: a seek forward earns nothing, buffering earns nothing, a late tick earns at most [SCROBBLE_TICK_CAP_MS].
 * A play is handed back once, when it has been heard enough; a repeat is a new play ([start] again).
 */
internal class ScrobbleMeter {
    var play: ScrobblePlay? = null
        private set
    var heardMs = 0L
        private set
    private var lastPosition = -1L
    private var done = false

    fun start(play: ScrobblePlay?, positionMs: Long) { this.play = play; heardMs = 0; lastPosition = positionMs; done = false }
    fun stop() = start(null, -1)

    fun heard(deltaMs: Long, playing: Boolean, positionMs: Long): ScrobblePlay? {
        val current = play ?: return null
        val previous = lastPosition
        lastPosition = positionMs
        if (!playing || done) return null
        var credit = deltaMs.coerceIn(0, SCROBBLE_TICK_CAP_MS)
        if (previous >= 0 && positionMs >= 0) credit = if (positionMs >= previous) minOf(credit, positionMs - previous + SCROBBLE_SLACK_MS)
            else minOf(credit, positionMs) // Back to an earlier point: only what was heard since.
        heardMs += credit
        val need = scrobbleThresholdMs(current.durationMs) ?: return null
        if (heardMs < need) return null
        done = true
        return current
    }
}

/** A pending row: tried [attempts] times, not to be tried again before [notBefore]. */
internal data class PendingScrobble(val play: ScrobblePlay, val attempts: Int = 0, val notBefore: Long = 0)

internal fun encodePending(rows: List<PendingScrobble>): String = JSONArray().also { array ->
    rows.forEach { row -> val p = row.play
        array.put(JSONObject().put("id", p.id).put("scope", p.scope).put("video", p.videoId ?: "").put("title", p.title).put("artist", p.artist)
            .put("album", p.album).put("ms", p.durationMs).put("at", p.startedAtMs).put("device", p.device)
            .put("tries", row.attempts).put("after", row.notBefore))
    }
}.toString()

internal fun decodePending(text: String?): List<PendingScrobble> = runCatching {
    val array = JSONArray(text ?: return emptyList())
    (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { o ->
        val play = ScrobblePlay(o.optString("id"), o.optString("scope"), o.optString("video").takeIf(::isVideoId), o.optString("title"),
            o.optString("artist"), o.optString("album"), o.optLong("ms"), o.optLong("at"), o.optBoolean("device"))
        if (play.id.isBlank() || play.title.isBlank() || play.artist.isBlank()) null
        else PendingScrobble(play, o.optInt("tries").coerceAtLeast(0), o.optLong("after").coerceAtLeast(0))
    } }
}.getOrDefault(emptyList())

/** Pending rows per service, in prefs of their own (not in the export file or the backup). */
internal class ScrobbleOutbox internal constructor(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private fun key(service: ScrobbleService) = "pending_" + service.key
    private fun read(service: ScrobbleService) = decodePending(prefs.getString(key(service), null))
        .filter { now() - it.play.startedAtMs in -SCROBBLE_KEEP_MS..SCROBBLE_KEEP_MS }
    private fun write(service: ScrobbleService, rows: List<PendingScrobble>) =
        prefs.edit().apply { if (rows.isEmpty()) remove(key(service)) else putString(key(service), encodePending(rows)) }.commit()

    fun rows(service: ScrobbleService): List<PendingScrobble> = synchronized(lock) { read(service) }

    /** False when the row is there already. The oldest rows give way past [SCROBBLE_PENDING_LIMIT]. */
    fun add(service: ScrobbleService, play: ScrobblePlay): Boolean = synchronized(lock) {
        val rows = read(service)
        if (rows.any { it.play.id == play.id }) return false
        write(service, (rows + PendingScrobble(play)).takeLast(SCROBBLE_PENDING_LIMIT))
    }

    fun due(service: ScrobbleService, limit: Int = SCROBBLE_BATCH): List<PendingScrobble> = synchronized(lock) {
        val all = decodePending(prefs.getString(key(service), null))
        val kept = read(service)
        if (kept.size != all.size) write(service, kept) // Past seven days: dropped, unsent.
        kept.filter { it.notBefore <= now() }.take(limit)
    }

    /** Sent and confirmed, or refused for good: either way not sent again. */
    fun remove(service: ScrobbleService, ids: Collection<String>) = synchronized(lock) {
        if (ids.isNotEmpty()) write(service, read(service).filterNot { it.play.id in ids })
    }

    fun later(service: ScrobbleService, ids: Collection<String>, waitMs: Long) = synchronized(lock) {
        if (ids.isNotEmpty()) write(service, read(service).map {
            if (it.play.id in ids) it.copy(attempts = it.attempts + 1, notBefore = now() + waitMs) else it })
    }

    fun clear(service: ScrobbleService) = synchronized(lock) { write(service, emptyList()) }

    /** An owner's data is deleted: their unsent plays go too. The number removed. */
    fun clearScope(scope: String): Int = synchronized(lock) {
        ScrobbleService.entries.sumOf { service ->
            val rows = read(service); val kept = rows.filterNot { it.play.scope == scope }
            if (kept.size != rows.size) write(service, kept)
            rows.size - kept.size
        }
    }

    companion object {
        @Volatile private var shared: ScrobbleOutbox? = null
        fun get(context: Context) = shared ?: synchronized(this) {
            shared ?: ScrobbleOutbox(context.applicationContext.getSharedPreferences("scrobble_outbox", Context.MODE_PRIVATE)).also { shared = it }
        }
    }
}

/** Waits after a failed try: 30 s, doubling, at most six hours. */
internal fun scrobbleBackoffMs(attempts: Int): Long = (30_000L shl attempts.coerceIn(0, 10)).coerceAtMost(6 * 60 * 60 * 1000L)

/** Seals a token so the prefs file alone does not give it away. */
internal interface TokenSeal {
    fun seal(plain: String): String?
    fun open(sealed: String): String?
}

/** AES-GCM with a key that lives in Android Keystore and never leaves it. */
internal object KeystoreSeal : TokenSeal {
    private const val ALIAS = "riff_scrobble_tokens"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    override fun seal(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(body, Base64.NO_WRAP)
    }.getOrNull()
    override fun open(sealed: String): String? = runCatching {
        val (iv, body) = sealed.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()
}

/** A connected service: the name it gave and the sealed token. [broken] means the service refused the token. */
internal data class ScrobbleAccount(val user: String, val token: String, val broken: Boolean = false)

/** The connections and their switches. The phone's, not an account's: a connection is the listener's own. */
internal class ScrobbleSettings internal constructor(private val prefs: SharedPreferences, private val seal: TokenSeal) {
    /** Opened once: the listen tick asks every few seconds, and Keystore is slow. */
    private val opened = java.util.concurrent.ConcurrentHashMap<ScrobbleService, Pair<String, ScrobbleAccount>>()
    fun account(service: ScrobbleService): ScrobbleAccount? {
        val sealed0 = prefs.getString(service.key + "_token", null) ?: return null
        val broken = prefs.getBoolean(service.key + "_broken", false)
        opened[service]?.takeIf { it.first == sealed0 }?.let { return it.second.copy(broken = broken) }
        val sealed = prefs.getString(service.key + "_token", null) ?: return null
        val token = seal.open(sealed) ?: return null
        return ScrobbleAccount(prefs.getString(service.key + "_user", null).orEmpty(), token, broken).also { opened[service] = sealed to it }
    }
    /** False when the token could not be sealed: nothing is kept then. */
    fun connect(service: ScrobbleService, user: String, token: String): Boolean {
        val sealed = seal.seal(token) ?: return false
        return prefs.edit().putString(service.key + "_token", sealed).putString(service.key + "_user", user.take(80))
            .putBoolean(service.key + "_broken", false).putBoolean(service.key + "_on", true).commit()
    }
    fun disconnect(service: ScrobbleService) {
        opened.remove(service)
        prefs.edit().remove(service.key + "_token").remove(service.key + "_user").remove(service.key + "_broken").remove(service.key + "_on").commit()
    }
    fun markBroken(service: ScrobbleService) { prefs.edit().putBoolean(service.key + "_broken", true).apply() }
    fun enabled(service: ScrobbleService) = prefs.getBoolean(service.key + "_on", false)
    fun setEnabled(service: ScrobbleService, on: Boolean) { prefs.edit().putBoolean(service.key + "_on", on).apply() }
    /** Sends to a service: connected, switched on and its token not refused. */
    fun active(service: ScrobbleService): ScrobbleAccount? = if (enabled(service)) account(service)?.takeUnless { it.broken } else null
    var nowPlaying: Boolean
        get() = prefs.getBoolean("now_playing", false)
        set(value) { prefs.edit().putBoolean("now_playing", value).apply() }
    var phoneFiles: Boolean
        get() = prefs.getBoolean("phone_files", false)
        set(value) { prefs.edit().putBoolean("phone_files", value).apply() }

    companion object {
        @Volatile private var shared: ScrobbleSettings? = null
        fun get(context: Context) = shared ?: synchronized(this) {
            shared ?: ScrobbleSettings(context.applicationContext.getSharedPreferences("scrobble_accounts", Context.MODE_PRIVATE), KeystoreSeal).also { shared = it }
        }
    }
}

/** An HTTP answer, cut short; [waitS] is the service's own "try again in". */
internal data class ScrobbleReply(val code: Int, val body: String, val waitS: Long? = null)

/** The network, as the clients see it. Throws [IOException] when nothing came back. */
internal fun interface ScrobbleHttp {
    suspend fun call(url: String, headers: Map<String, String>, body: String?, contentType: String?): ScrobbleReply
}

internal object OkScrobbleHttp : ScrobbleHttp {
    private val client by lazy { PlaybackHttp.base.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build() }
    override suspend fun call(url: String, headers: Map<String, String>, body: String?, contentType: String?) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "Riff/${BuildConfig.VERSION_NAME}")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .apply { if (body != null) post(body.toRequestBody((contentType ?: "application/json").toMediaType())) }.build()
        client.newCall(request).execute().use { response ->
            val wait = (response.header("X-RateLimit-Reset-In") ?: response.header("Retry-After"))?.trim()?.toLongOrNull()
            ScrobbleReply(response.code, response.body.string().take(64_000), wait)
        }
    }
}

/** What a send came to, by row id. [unauthorized]: the token was refused; [waitMs]: the service asked to wait. */
internal data class ScrobbleOutcome(val sent: Set<String> = emptySet(), val refused: Set<String> = emptySet(), val retry: Set<String> = emptySet(),
    val unauthorized: Boolean = false, val waitMs: Long? = null)

internal interface ScrobbleClient {
    suspend fun submit(token: String, rows: List<ScrobblePlay>): ScrobbleOutcome
    /** Best effort, never retried. */
    suspend fun nowPlaying(token: String, play: ScrobblePlay)
}

private fun originUrl(play: ScrobblePlay) = play.videoId?.let { "https://music.youtube.com/watch?v=$it" }

/** ListenBrainz: a user token, JSON listens to api.listenbrainz.org. */
internal class ListenBrainzClient(private val http: ScrobbleHttp = OkScrobbleHttp) : ScrobbleClient {
    private val base = "https://api.listenbrainz.org/1/"
    private fun auth(token: String) = mapOf("Authorization" to "Token $token")

    /** The user's name when the token is valid, null when it is not; throws when the service could not be asked. */
    suspend fun validate(token: String): String? {
        val reply = http.call(base + "validate-token", auth(token), null, null)
        if (reply.code != 200) { if (reply.code == 401 || reply.code == 400) return null; throw IOException("http ${reply.code}") }
        val o = runCatching { JSONObject(reply.body) }.getOrNull() ?: throw IOException("unreadable")
        return if (o.optBoolean("valid")) o.optString("user_name").ifBlank { "ListenBrainz" } else null
    }

    internal fun listen(play: ScrobblePlay, at: Boolean) = JSONObject().apply {
        if (at) put("listened_at", play.startedAtMs / 1000)
        put("track_metadata", JSONObject().put("artist_name", play.artist).put("track_name", play.title).apply {
            if (play.album.isNotBlank()) put("release_name", play.album)
            put("additional_info", JSONObject().put("duration_ms", play.durationMs).put("media_player", "Riff")
                .put("submission_client", "Riff").put("submission_client_version", BuildConfig.VERSION_NAME)
                .apply { originUrl(play)?.let { put("origin_url", it); put("music_service", "music.youtube.com") } })
        })
    }

    private suspend fun send(token: String, type: String, listens: List<JSONObject>) =
        http.call(base + "submit-listens", auth(token), JSONObject().put("listen_type", type).put("payload", JSONArray(listens)).toString(), "application/json")

    override suspend fun submit(token: String, rows: List<ScrobblePlay>): ScrobbleOutcome {
        if (rows.isEmpty()) return ScrobbleOutcome()
        val ids = rows.map { it.id }.toSet()
        val reply = try { send(token, if (rows.size == 1) "single" else "import", rows.map { listen(it, true) }) }
            catch (_: IOException) { return ScrobbleOutcome(retry = ids) } // Lost on the way, or its answer was: it may have arrived.
        return when {
            reply.code == 200 -> ScrobbleOutcome(sent = ids)
            reply.code == 401 -> ScrobbleOutcome(retry = ids, unauthorized = true)
            reply.code == 429 -> ScrobbleOutcome(retry = ids, waitMs = (reply.waitS ?: 60).coerceIn(1, 3600) * 1000)
            // A batch is all or nothing: one at a time finds the row it refused.
            reply.code == 400 && rows.size > 1 -> rows.fold(ScrobbleOutcome()) { sum, row ->
                if (sum.unauthorized || sum.waitMs != null) sum.copy(retry = sum.retry + row.id) else {
                    val one = submit(token, listOf(row))
                    ScrobbleOutcome(sum.sent + one.sent, sum.refused + one.refused, sum.retry + one.retry, one.unauthorized, one.waitMs)
                }
            }
            reply.code in 400..499 -> ScrobbleOutcome(refused = ids)
            else -> ScrobbleOutcome(retry = ids)
        }
    }

    override suspend fun nowPlaying(token: String, play: ScrobblePlay) {
        runCatching { send(token, "playing_now", listOf(listen(play, false))) }
    }
}

/** Last.fm's request signature: every parameter but format and callback, sorted, then the secret, as MD5. */
internal fun lastFmSignature(params: Map<String, String>, secret: String): String {
    val text = params.filterKeys { it != "format" && it != "callback" }.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
    return MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Last.fm: the user allows Riff in the browser, then signed calls to ws.audioscrobbler.com. */
internal class LastFmClient(private val key: String = BuildConfig.LASTFM_KEY, private val secret: String = BuildConfig.LASTFM_SECRET,
    private val http: ScrobbleHttp = OkScrobbleHttp) : ScrobbleClient {
    val available get() = key.isNotBlank() && secret.isNotBlank()

    private suspend fun call(params: Map<String, String>): Pair<ScrobbleReply, JSONObject?> {
        val all = params + ("api_key" to key)
        val signed = all + ("api_sig" to lastFmSignature(all, secret)) + ("format" to "json")
        val body = signed.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val reply = http.call("https://ws.audioscrobbler.com/2.0/", emptyMap(), body, "application/x-www-form-urlencoded")
        return reply to runCatching { JSONObject(reply.body) }.getOrNull()
    }

    /** A request token, to be allowed at [authorizeUrl]. Null when Last.fm refused. */
    suspend fun requestToken(): String? = call(mapOf("method" to "auth.getToken")).second?.optString("token")?.takeIf { it.isNotBlank() }
    fun authorizeUrl(token: String) = "https://www.last.fm/api/auth/?api_key=${URLEncoder.encode(key, "UTF-8")}&token=${URLEncoder.encode(token, "UTF-8")}"

    /** The user's name and session key once they allowed Riff; null while they have not. */
    suspend fun session(token: String): Pair<String, String>? {
        val session = call(mapOf("method" to "auth.getSession", "token" to token)).second?.optJSONObject("session") ?: return null
        val sk = session.optString("key").takeIf { it.isNotBlank() } ?: return null
        return session.optString("name").ifBlank { "Last.fm" } to sk
    }

    override suspend fun submit(token: String, rows: List<ScrobblePlay>): ScrobbleOutcome {
        if (rows.isEmpty()) return ScrobbleOutcome()
        val ids = rows.map { it.id }.toSet()
        val params = linkedMapOf("method" to "track.scrobble", "sk" to token)
        rows.take(SCROBBLE_BATCH).forEachIndexed { i, p ->
            params["artist[$i]"] = p.artist; params["track[$i]"] = p.title; params["timestamp[$i]"] = (p.startedAtMs / 1000).toString()
            params["duration[$i]"] = (p.durationMs / 1000).toString(); if (p.album.isNotBlank()) params["album[$i]"] = p.album
        }
        val (reply, o) = try { call(params) } catch (_: IOException) { return ScrobbleOutcome(retry = ids) }
        val error = o?.optInt("error", 0) ?: 0
        return when {
            error == 9 || error == 4 -> ScrobbleOutcome(retry = ids, unauthorized = true)
            error == 29 -> ScrobbleOutcome(retry = ids, waitMs = 5 * 60_000L)
            error != 0 -> ScrobbleOutcome(retry = ids, waitMs = if (error == 11 || error == 16) null else 60 * 60_000L)
            reply.code != 200 -> ScrobbleOutcome(retry = ids)
            else -> {
                // One answer per row, in the order sent; one Last.fm ignored is refused for good.
                val list = o?.optJSONObject("scrobbles")?.let { s -> s.optJSONArray("scrobble") ?: s.optJSONObject("scrobble")?.let { JSONArray().put(it) } }
                val ignored = rows.indices.filter { i -> list?.optJSONObject(i)?.optJSONObject("ignoredMessage")?.optString("code", "0").let { it != null && it != "0" } }
                    .map { rows[it].id }.toSet()
                ScrobbleOutcome(sent = ids - ignored, refused = ignored)
            }
        }
    }

    override suspend fun nowPlaying(token: String, play: ScrobblePlay) {
        suspendCatching { call(mapOf("method" to "track.updateNowPlaying", "sk" to token, "artist" to play.artist, "track" to play.title,
            "duration" to (play.durationMs / 1000).toString()) + (if (play.album.isNotBlank()) mapOf("album" to play.album) else emptyMap())) }
    }
}

/** Whether a service can be offered: Last.fm needs the app's key and secret in the build. */
internal fun scrobbleOffered(service: ScrobbleService) = service != ScrobbleService.LastFm || LastFmClient().available

/**
 * The service's side: the meter, the rows and the sends. One send at a time; rows are tried again after a wait that
 * grows, and the whole service waits when it asks to. Nothing is kept or sent while listening history is off.
 */
internal class Scrobbler(private val settings: ScrobbleSettings, private val outbox: ScrobbleOutbox, private val scope: CoroutineScope,
    private val historyOn: () -> Boolean, private val events: (String) -> Unit, private val wallNow: () -> Long = System::currentTimeMillis,
    private val clients: Map<ScrobbleService, ScrobbleClient> = mapOf(ScrobbleService.ListenBrainz to ListenBrainzClient(), ScrobbleService.LastFm to LastFmClient())) {
    val meter = ScrobbleMeter()
    private var fresh = true
    private var announced: String? = null
    private val sending = Mutex()
    private val pausedUntil = HashMap<ScrobbleService, Long>()
    private var lastFlush = 0L

    private fun active() = ScrobbleService.entries.filter { scrobbleOffered(it) }.mapNotNull { s -> settings.active(s)?.let { s to it } }

    /** A new item, or the same one again: the next tick starts a new play. */
    fun restart() { fresh = true; meter.stop() }

    /** Every listen tick. [play] builds the play on now when a new one starts; null when it is not one Riff sends. */
    fun tick(deltaMs: Long, playing: Boolean, positionMs: Long, play: () -> ScrobblePlay?) {
        if (fresh && playing) {
            fresh = false
            val candidate = play()?.takeIf { (!it.device || settings.phoneFiles) && scrobbleThresholdMs(it.durationMs) != null }
            meter.start(candidate?.copy(startedAtMs = wallNow() - deltaMs.coerceAtLeast(0)), (positionMs - deltaMs).coerceAtLeast(0))
        }
        val current = meter.play
        if (current != null && playing && announced != current.id && settings.nowPlaying && historyOn()) {
            announced = current.id
            active().forEach { (service, account) -> clients[service]?.let { client -> scope.launch(Dispatchers.IO) { client.nowPlaying(account.token, current) } } }
        }
        val ready = meter.heard(deltaMs, playing, positionMs)
        if (ready != null && historyOn()) {
            var added = 0
            active().forEach { (service, _) -> if (outbox.add(service, ready)) added++ }
            if (added > 0) flush(force = true)
        } else if (wallNow() - lastFlush > 60_000) flush()
    }

    /** Sends what is due, at most once a minute unless [force]. */
    fun flush(force: Boolean = false) {
        if (!force && wallNow() - lastFlush < 60_000) return
        lastFlush = wallNow()
        if (sending.isLocked) return
        scope.launch(Dispatchers.IO) { sending.withLock { sendDue() } }
    }

    internal suspend fun sendDue() {
        if (!historyOn()) return
        for ((service, account) in active()) {
            if ((pausedUntil[service] ?: 0) > wallNow()) continue
            val client = clients[service] ?: continue
            val rows = outbox.due(service)
            if (rows.isEmpty()) continue
            val outcome = client.submit(account.token, rows.map { it.play })
            outbox.remove(service, outcome.sent + outcome.refused)
            val attempts = rows.filter { it.play.id in outcome.retry }.maxOfOrNull { it.attempts } ?: 0
            outbox.later(service, outcome.retry, outcome.waitMs ?: scrobbleBackoffMs(attempts))
            outcome.waitMs?.let { pausedUntil[service] = wallNow() + it }
            if (outcome.unauthorized) settings.markBroken(service)
            events("scrobble_send kind=${service.key} count=${outcome.sent.size} rejected=${outcome.refused.size} waiting=${outcome.retry.size}")
        }
    }
}

/** The play on now, as Riff would send it; null for a live stream, a video or an owner Riff cannot vouch for. */
internal fun scrobblePlay(item: MediaItem?, durationMs: Long, ownerScope: String, id: String = java.util.UUID.randomUUID().toString()): ScrobblePlay? {
    item ?: return null
    val m = item.mediaMetadata
    if (m.extras?.getBoolean("live") == true || m.extras?.getBoolean("music", true) == false) return null
    val device = item.mediaId.startsWith("local:")
    if (!device && !item.mediaId.startsWith("yt:")) return null
    val scope = historyScopeKey(item.mediaId, ownerScope) ?: return null
    val title = m.title?.toString().orEmpty().trim(); val artist = m.artist?.toString().orEmpty().trim()
    if (title.isEmpty() || artist.isEmpty()) return null
    return ScrobblePlay(id, scope, item.mediaId.removePrefix("yt:").takeIf { !device && isVideoId(it) }, title.take(300), artist.take(300),
        m.albumTitle?.toString().orEmpty().take(300), durationMs, 0, device)
}
