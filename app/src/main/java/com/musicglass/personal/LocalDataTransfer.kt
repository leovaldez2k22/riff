package com.musicglass.personal

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

/** The only schema this build writes. A file with a higher number is refused whole. */
internal const val LOCAL_DATA_SCHEMA = 1
/** Ceiling for a file, written or read. */
internal const val LOCAL_DATA_MAX_BYTES = 128L * 1024 * 1024
internal const val LOCAL_DATA_MAX_LINE = 16 * 1024
/** Most records of one list that are read from one section. The stores keep their own, lower limits. */
internal const val LOCAL_DATA_LIST_CAP = 2_000

internal enum class OwnerKind(val word: String) { Device("device"), Guest("guest"), Provider("provider") }

/** How much of each kind of data. The same shape counts what is kept, what a file holds and what was added. */
internal data class SectionCounts(val settings: Int = 0, val hidden: Int = 0, val saved: Int = 0, val recents: Int = 0,
    val searches: Int = 0, val plays: Int = 0, val downloads: Int = 0, val pins: Int = 0) {
    val total get() = settings + hidden + saved + recents + searches + plays + downloads + pins
    operator fun plus(o: SectionCounts) = SectionCounts(settings + o.settings, hidden + o.hidden, saved + o.saved, recents + o.recents,
        searches + o.searches, plays + o.plays, downloads + o.downloads, pins + o.pins)
}

/** One owner whose data can be put in a file. [key] is where the stores keep it; it is not written to the file. */
internal data class ExportOwner(val key: String, val kind: OwnerKind, val counts: SectionCounts)

internal sealed interface ExportOutcome {
    data class Done(val records: Int, val bytes: Long) : ExportOutcome
    /** Stopped at the user's request. The chosen document holds nothing usable. */
    data object Cancelled : ExportOutcome
    /** The file would pass the ceiling. Fewer owners must be chosen. */
    data object TooLarge : ExportOutcome
    /** [kind] is a fixed word or a class name, never content. */
    data class Failed(val kind: String) : ExportOutcome
}

internal enum class RejectReason { NotRiff, Newer, Truncated, TooLarge, Unreadable, Storage }

/** Where a section of a file would go on this phone. */
internal enum class Destination {
    /** The device's own data, or signed-out listening: the same owner on every phone. */
    Direct,
    /** The account signed in here is the one the section was exported from. */
    Account,
    /** Another account's data, or nobody verified is signed in. Imported only if the user says so. */
    Other
}

internal data class ImportSection(val index: Int, val kind: OwnerKind, val counts: SectionCounts, val destination: Destination)

/** A file that was read through and can be imported. [file] is a private copy, removed by [LocalDataTransfer.discard]. */
internal data class ImportPlan(val file: File, val exportedAt: Long, val sections: List<ImportSection>, val malformed: Int,
    val overLimit: Int, /** A verified account is signed in, so another account's section can be added to it. */ val canAdopt: Boolean) {
    val total get() = sections.sumOf { it.counts.total }
}

internal sealed interface ImportOpen {
    data class Ready(val plan: ImportPlan) : ImportOpen
    data class Rejected(val reason: RejectReason) : ImportOpen
    data object Cancelled : ImportOpen
}

/**
 * What an import did. [added] is new on this phone; [present] was already here; [malformed] records
 * could not be read; [overLimit] did not fit a limit; [expired] plays are older than the history
 * period; [settingsKept] settings were left as they are; [sectionsLeft] sections were not imported;
 * [notStored] names the kinds of data that could not be written.
 */
internal data class ImportReport(val added: SectionCounts = SectionCounts(), val present: Int = 0, val malformed: Int = 0,
    val overLimit: Int = 0, val expired: Int = 0, val settingsKept: Int = 0, val sectionsLeft: Int = 0,
    val notStored: List<String> = emptyList())

internal sealed interface ImportOutcome {
    data class Finished(val report: ImportReport) : ImportOutcome
    /** Stopped before anything was written. */
    data object Cancelled : ImportOutcome
    /** The file was refused at the second reading. Nothing was changed. */
    data class Rejected(val reason: RejectReason) : ImportOutcome
    /** Nothing was changed. [kind] is a fixed word or a class name, never content. */
    data class Failed(val kind: String) : ImportOutcome
}

/** The owner active now. [digests] is empty unless a verified account is signed in. */
internal data class CurrentOwner(val key: String, val digests: Set<String> = emptySet()) {
    val verified get() = digests.isNotEmpty() && key != UNVERIFIED_SCOPE_KEY && key != GUEST_SCOPE_KEY
}

internal sealed interface DataRecord {
    data class Setting(val name: String, val value: Any) : DataRecord
    data class Hidden(val entry: NotInterestedEntry) : DataRecord
    /** [list] is the name the collection store uses. */
    data class Saved(val list: String, val track: ProviderTrack) : DataRecord
    data class Search(val query: String) : DataRecord
    data class Play(val entry: HistoryEntry) : DataRecord
    data class Download(val track: ProviderTrack) : DataRecord
    data class Pin(val card: ProviderHomeCard) : DataRecord // A speed-dial pin: the device's, like hidden items.
}

// ---- settings that travel

private class SettingSpec(val prefs: String, val key: String, val accept: (Any?) -> Any?) { val name = "$prefs.$key" }
private val acceptBool = { v: Any? -> v as? Boolean }
private fun acceptInt(allowed: (Int) -> Boolean) = { v: Any? ->
    (v as? Number)?.takeIf { it !is Float && it !is Double || it.toDouble() == it.toInt().toDouble() }?.toInt()?.takeIf(allowed)
}
private fun acceptFloat(range: ClosedFloatingPointRange<Float>) = { v: Any? -> (v as? Number)?.toFloat()?.takeIf { it in range } }
private fun acceptWord(allowed: Set<String>) = { v: Any? -> (v as? String)?.takeIf { it in allowed } }

/**
 * Device settings that are exported and imported. Automatic downloads, downloading on mobile data
 * and the download budget are left out on purpose: importing them could start transfers, use
 * mobile data or make room by removing downloads.
 */
private val SETTINGS = listOf(
    SettingSpec("accessibility_display", "haptic_feedback", acceptBool), SettingSpec("accessibility_display", "solid_controls", acceptBool),
    SettingSpec("accessibility_display", "reduce_motion", acceptBool), SettingSpec("accessibility_display", "frosted_glass", acceptBool),
    SettingSpec("accessibility_display", "glass_intensity", acceptFloat(0f..1.5f)), SettingSpec("accessibility_display", "frosted_intensity", acceptFloat(0f..1.5f)),
    SettingSpec(AUDIO_BOOST_PREFS, "enabled", acceptBool), SettingSpec(AUDIO_BOOST_PREFS, "percent", acceptInt { it in 100..300 }), SettingSpec(AUDIO_BOOST_PREFS, NORMALIZE_KEY, acceptBool), SettingSpec(AUDIO_BOOST_PREFS, SKIP_END_SILENCE_KEY, acceptBool), SettingSpec(AUDIO_BOOST_PREFS, SKIP_ALL_SILENCE_KEY, acceptBool),
    *equalizerSettingSpecs().map { (key, accept) -> SettingSpec(EQUALIZER_PREFS, key, accept) }.toTypedArray(),
    SettingSpec(CROSSFADE_PREFS, "enabled", acceptBool), SettingSpec(CROSSFADE_PREFS, "seconds", acceptInt { it in 1..12 }), SettingSpec(CROSSFADE_PREFS, SMART_CROSSFADE_KEY, acceptBool), SettingSpec(CROSSFADE_PREFS, BLEND_MODE_KEY, acceptWord(BlendMode.entries.map { it.wire }.toSet())),
    SettingSpec(DATA_SAVER_PREFS, "enabled", acceptBool), SettingSpec(DATA_SAVER_PREFS, SONG_QUALITY_KEY, acceptWord(SongQuality.entries.map { it.key }.toSet())), *listOf(SONG_QUALITY_UNMETERED_KEY, SONG_QUALITY_METERED_KEY).map { SettingSpec(DATA_SAVER_PREFS, it, acceptWord(SongQuality.entries.map { q -> q.key }.toSet())) }.toTypedArray(),
    SettingSpec("music_collections", "history_enabled", acceptBool), SettingSpec("music_collections", RETENTION_KEY, acceptInt { it in setOf(30, 90, 365) }),
).associateBy { it.name }
private const val RETENTION_KEY = "history_retention_days"
private const val RETENTION_NAME = "music_collections.$RETENTION_KEY"

// ---- the file

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private val PROVIDER_ID = Regex("[A-Za-z0-9_-]{1,64}")
private val HIDDEN_KEY = Regex("[A-Za-z0-9_-]{1,128}")
private val SCOPE_DIGEST = Regex("s2_[0-9a-f]{64}|[0-9a-f]{32}")
private val LISTS = mapOf("favorites" to "online_favorites", "playlist" to "online_playlist", "recents" to "online_history")
private const val DAY_MS = 86_400_000L

/**
 * Artwork addresses are written only when they are plain addresses of the provider's image hosts.
 * One with a query could carry a signature, so it is left out and the default image is used.
 */
internal fun exportableArtwork(url: String?): String? =
    validProviderArtworkUrl(url)?.takeIf { it.length <= 512 && '?' !in it && '#' !in it }

private fun headerLine(exportedAt: Long) = JSONObject().put("riff", "local-data").put("schema", LOCAL_DATA_SCHEMA)
    .put("exported_at", exportedAt).put("app", BuildConfig.VERSION_NAME).toString()

private fun ownerLine(kind: OwnerKind, digest: String?) =
    JSONObject().put("t", "owner").put("kind", kind.word).also { if (digest != null) it.put("id", digest) }.toString()

private fun trackObject(type: String, track: ProviderTrack) = JSONObject().put("t", type).put("id", track.videoId)
    .put("title", track.title.take(180)).put("artist", track.artist.take(180)).also { o ->
        exportableArtwork(track.artworkUrl)?.let { o.put("artwork", it) }
        if (track.isLive) o.put("live", true)
        if (!track.isMusic) o.put("music", false)
    }

internal fun encodeRecord(record: DataRecord): String = when (record) {
    is DataRecord.Setting -> JSONObject().put("t", "setting").put("k", record.name).put("v", record.value)
    is DataRecord.Hidden -> JSONObject().put("t", "hidden").put("key", record.entry.key).put("title", record.entry.title.take(180))
        .put("subtitle", record.entry.subtitle.take(180)).put("song", record.entry.song).put("at", record.entry.at.coerceAtLeast(0))
        .also { o -> exportableArtwork(record.entry.artworkUrl)?.let { o.put("artwork", it) } }
    is DataRecord.Saved -> trackObject("track", record.track).put("list", LISTS.entries.first { it.value == record.list }.key)
    is DataRecord.Search -> JSONObject().put("t", "search").put("q", record.query.take(160))
    is DataRecord.Play -> JSONObject().put("t", "play").put("e", record.entry.eventId).put("id", record.entry.mediaId)
        .put("title", record.entry.title.take(180)).put("artist", record.entry.artist.take(180)).put("at", record.entry.playedAt).also { o ->
            exportableArtwork(record.entry.artworkUrl)?.let { o.put("artwork", it) }
            record.entry.durationMs?.takeIf { it >= 0 }?.let { o.put("dur", it) }
            if (!record.entry.music) o.put("music", false)
        }
    is DataRecord.Pin -> pinRecord(record.card)
    is DataRecord.Download -> trackObject("download", record.track).also { o ->
        record.track.artistId?.takeIf(PROVIDER_ID::matches)?.let { o.put("artistId", it) }
        record.track.albumId?.takeIf(PROVIDER_ID::matches)?.let { o.put("albumId", it) }
        if (record.track.album.isNotBlank()) o.put("album", record.track.album.take(180))
    }
}.toString()

private fun JSONObject.text(name: String, max: Int = 180) = (opt(name) as? String).orEmpty().take(max)
private fun JSONObject.whole(name: String): Long? =
    (opt(name) as? Number)?.takeIf { it !is Float && it !is Double || it.toDouble() == it.toLong().toDouble() }?.toLong()
private fun JSONObject.flag(name: String, default: Boolean) = opt(name) as? Boolean ?: default

private fun parseTrack(o: JSONObject): ProviderTrack? {
    val id = o.text("id").takeIf(VIDEO_ID::matches) ?: return null
    return ProviderTrack(id, o.text("title"), o.text("artist"), exportableArtwork(o.opt("artwork") as? String) ?: providerArtworkUrl(id),
        o.text("artistId", 64).takeIf(PROVIDER_ID::matches), o.text("albumId", 64).takeIf(PROVIDER_ID::matches), o.text("album"),
        o.flag("live", false), o.flag("music", true))
}

/**
 * One record of a section owned by [kind], or null when it cannot be used. Every field is checked
 * here, with the limits the stores apply to their own writes; nothing from the file is trusted.
 */
internal fun parseRecord(kind: OwnerKind, o: JSONObject, now: Long): DataRecord? = when (o.opt("t")) {
    "setting" -> if (kind != OwnerKind.Device) null else SETTINGS[o.text("k")]?.let { spec -> spec.accept(o.opt("v"))?.let { DataRecord.Setting(spec.name, it) } }
    "hidden" -> if (kind != OwnerKind.Device) null else o.text("key", 200).takeIf(HIDDEN_KEY::matches)?.let { key ->
        DataRecord.Hidden(NotInterestedEntry(key, o.text("title"), o.text("subtitle"), exportableArtwork(o.opt("artwork") as? String),
            o.flag("song", true), (o.whole("at") ?: 0L).coerceIn(0L, now + DAY_MS)))
    }
    "pin" -> if (kind != OwnerKind.Device) null else parsePinRecord(o)?.let(DataRecord::Pin)
    // Only an account saves tracks; signed-out listening and the device have no such lists.
    "track" -> if (kind != OwnerKind.Provider) null else LISTS[o.text("list")]?.let { list -> parseTrack(o)?.let { DataRecord.Saved(list, it) } }
    "search" -> if (kind == OwnerKind.Device) null else (o.opt("q") as? String)?.takeIf { it.length <= 160 }
        ?.trim()?.replace(Regex("\\s+"), " ")?.takeIf(String::isNotBlank)?.let(DataRecord::Search)
    "play" -> {
        val at = o.whole("at")
        val entry = HistoryEntry(o.text("id", 64), o.text("title"), o.text("artist"),
            // An image address of the phone's own library means nothing on another phone.
            if (kind == OwnerKind.Device) null else exportableArtwork(o.opt("artwork") as? String),
            at ?: 0L, o.flag("music", true), o.text("e", 100), o.whole("dur")?.takeIf { it in 0..DAY_MS * 7 })
        val ownPrefix = if (kind == OwnerKind.Device) "local:" else "yt:"
        if (at != null && at in 1..now + DAY_MS && validHistoryEvent(entry) && entry.mediaId.startsWith(ownPrefix)) DataRecord.Play(entry) else null
    }
    "download" -> parseTrack(o)?.takeIf { kind != OwnerKind.Device && !it.isLive }?.let(DataRecord::Download)
    else -> null
}

/** True when [line] nests objects or arrays deeper than a record ever does. Checked before parsing, which recurses. */
private fun nestsTooDeep(line: String): Boolean {
    var depth = 0; var quoted = false; var escaped = false
    for (c in line) when {
        escaped -> escaped = false
        quoted -> if (c == '\\') escaped = true else if (c == '"') quoted = false
        c == '"' -> quoted = true
        c == '{' || c == '[' -> if (++depth > 2) return true
        c == '}' || c == ']' -> depth--
    }
    return false
}

private fun parseLine(line: String): JSONObject? =
    if (line === TOO_LONG || nestsTooDeep(line)) null else runCatching { JSONObject(line) }.getOrNull()

private val TOO_LONG = String(charArrayOf('!'))

/** Lines of at most [LOCAL_DATA_MAX_LINE] characters; a longer one is read past and reported as [TOO_LONG]. */
private class BoundedLines(stream: InputStream) : Closeable {
    private val reader = InputStreamReader(BufferedInputStream(stream, 1 shl 16), Charsets.UTF_8)
    private val buffer = CharArray(1 shl 15); private var at = 0; private var filled = 0
    private val line = StringBuilder()
    private fun read(): Int {
        if (at == filled) { filled = reader.read(buffer); at = 0; if (filled <= 0) { filled = 0; return -1 } }
        return buffer[at++].code
    }
    fun next(): String? {
        line.setLength(0); var long = false; var any = false
        while (true) {
            val c = read()
            if (c < 0) { if (!any) return null; break }
            any = true
            if (c == '\n'.code) break
            if (line.length < LOCAL_DATA_MAX_LINE) line.append(c.toChar()) else long = true
        }
        return if (long) TOO_LONG else line.toString().removeSuffix("\r")
    }
    override fun close() = reader.close()
}

private class FileRejected(val reason: RejectReason) : Exception()
private class Stopped : Exception()

/** Sections are numbered in the order their owners first appear, the same at both readings of a file. */
private class Sections {
    val owners = mutableListOf<Pair<OwnerKind, String?>>()
    fun index(kind: OwnerKind, digest: String?): Int =
        owners.indexOf(kind to digest).takeIf { it >= 0 } ?: owners.size.also { owners += kind to digest }
}

/** Counts the records of each list in each section, and refuses those past [LOCAL_DATA_LIST_CAP] or the history ceiling. */
private class Admission {
    private val seen = HashMap<String, Int>()
    var refused = 0; private set
    fun admit(section: Int, record: DataRecord): Boolean {
        val (name, cap) = when (record) {
            is DataRecord.Play -> "play" to HISTORY_ROW_CAP
            is DataRecord.Saved -> record.list to LOCAL_DATA_LIST_CAP
            is DataRecord.Search -> "search" to LOCAL_DATA_LIST_CAP
            is DataRecord.Download -> "download" to LOCAL_DATA_LIST_CAP
            is DataRecord.Hidden -> "hidden" to LOCAL_DATA_LIST_CAP
            is DataRecord.Pin -> "pin" to SPEED_DIAL_PIN_LIMIT
            is DataRecord.Setting -> "setting" to SETTINGS.size * 4
        }
        val count = (seen["$section/$name"] ?: 0) + 1
        seen["$section/$name"] = count
        return (count <= cap).also { if (!it) refused++ }
    }
}

private class Walked(val exportedAt: Long, val malformed: Int)

/**
 * Reads a file once, from its header to its end mark. Throws [FileRejected] when the file as a whole
 * cannot be trusted: not a Riff file, a newer schema, or no end mark. A record that cannot be read is
 * counted and passed over without affecting the others.
 */
private fun walk(file: File, now: Long, sections: Sections, check: () -> Unit, record: (Int, DataRecord) -> Unit): Walked =
    BoundedLines(file.inputStream()).use { lines ->
        val head = lines.next()?.removePrefix("\uFEFF")?.let(::parseLine) ?: throw FileRejected(RejectReason.NotRiff)
        if (head.opt("riff") != "local-data") throw FileRejected(RejectReason.NotRiff)
        val schema = head.whole("schema")?.takeIf { it >= 1 } ?: throw FileRejected(RejectReason.NotRiff)
        if (schema > LOCAL_DATA_SCHEMA) throw FileRejected(RejectReason.Newer)
        var kind: OwnerKind? = null; var section = -1; var malformed = 0; var read = 0
        var done: Walked? = null
        while (done == null) {
            val line = lines.next() ?: throw FileRejected(RejectReason.Truncated)
            if (++read % 256 == 0) check()
            if (line.isBlank()) continue
            val o = parseLine(line)
            when (o?.opt("t")) {
                null -> malformed++
                "end" -> done = Walked(head.whole("exported_at") ?: 0L, malformed)
                "owner" -> {
                    val named = OwnerKind.entries.firstOrNull { it.word == o.opt("kind") }
                    val digest = (o.opt("id") as? String)?.takeIf { named == OwnerKind.Provider }
                    if (named == null || (named == OwnerKind.Provider && (digest == null || !SCOPE_DIGEST.matches(digest)))) {
                        // The records that follow have no owner that can be trusted, so none of them is used.
                        kind = null; malformed++
                    } else { kind = named; section = sections.index(named, digest) }
                }
                else -> {
                    val parsed = kind?.let { parseRecord(it, o, now) }
                    if (parsed == null) { if (o.opt("t") !in ENCORE_RECORDS) malformed++ } else record(section, parsed)
                }
            }
        }
        checkNotNull(done)
    }

private fun SectionCounts.with(record: DataRecord) = when (record) {
    is DataRecord.Setting -> copy(settings = settings + 1)
    is DataRecord.Hidden -> copy(hidden = hidden + 1)
    is DataRecord.Pin -> copy(pins = pins + 1)
    is DataRecord.Saved -> if (record.list == "online_history") copy(recents = recents + 1) else copy(saved = saved + 1)
    is DataRecord.Search -> copy(searches = searches + 1)
    is DataRecord.Play -> copy(plays = plays + 1)
    is DataRecord.Download -> copy(downloads = downloads + 1)
}

/**
 * Export and import of what Riff keeps on the phone (Q05A): device settings, saved tracks, searches,
 * listening history and the list of downloads, as one file the user chooses the place of.
 *
 * The file never holds credentials, stream addresses, audio, images or diagnostics. An account's
 * data is filed under an opaque digest. The file is not encrypted.
 *
 * Import adds and never removes. Each kind of data is written in one step, so a failure leaves that
 * kind as it was. Nothing imported is played, queued, downloaded or sent to YouTube Music.
 */
internal class LocalDataTransfer(context: Context, private val db: LibraryStateDb, private val history: HistoryRepository,
    private val downloads: () -> OfflineMusicStore, private val searches: () -> SearchHistory, private val hidden: () -> NotInterestedStore,
    private val owner: () -> CurrentOwner, private val io: CoroutineDispatcher, private val wallTime: () -> Long,
    private val gate: Mutex = Mutex(), private val event: (String) -> Unit = {}, private val checkpoint: (String) -> Unit = {},
    private val maxBytes: Long = LOCAL_DATA_MAX_BYTES, private val historyCap: Int = HISTORY_ROW_CAP,
    private val pins: () -> SpeedDialPins = { SpeedDialPins.get(context) }) {
    private val app = context.applicationContext
    private val work = File(app.cacheDir, "local-data-transfer")
    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** Each step its own file, so a backup leaves a waiting import's copy alone; a dead process's files go once. */
    private fun workFile(name: String): File = synchronized(work) {
        work.mkdirs(); if (!swept) { work.listFiles()?.forEach { it.delete() }; swept = true }
        File(work, name.replace(".", "-${++workSerial}."))
    }
    private var swept = false; private var workSerial = 0

    // ---- export

    private fun kindOf(key: String) = when (key) {
        DEVICE_LOCAL_SCOPE_KEY -> OwnerKind.Device
        GUEST_SCOPE_KEY -> OwnerKind.Guest
        else -> OwnerKind.Provider
    }

    private fun storedSettings(): List<DataRecord.Setting> = SETTINGS.values.mapNotNull { spec ->
        // Only what the user set: a default differs between builds and is not a choice to carry over.
        spec.accept(prefs(spec.prefs).all[spec.key])?.let { DataRecord.Setting(spec.name, it) }
    }

    private suspend fun counts(key: String): SectionCounts = when (kindOf(key)) {
        OwnerKind.Device -> SectionCounts(settings = storedSettings().size, hidden = hidden().entries.size, plays = history.count(key), pins = pins().cards.size)
        else -> SectionCounts(saved = storedCollection(app, key, "online_favorites").size + storedCollection(app, key, "online_playlist").size,
            recents = storedCollection(app, key, "online_history").size, searches = searches().count(key),
            plays = history.count(key), downloads = downloads().downloadList(key).size)
    }

    /**
     * Every owner with something to export: the device first, then signed-out listening, then the
     * active account, then other accounts that used this phone.
     */
    suspend fun inventory(): List<ExportOwner> = withContext(io) {
        val collectionKey = Regex("account_(.+)_online_(?:favorites|playlist|history)")
        val keys = history.scopes() + downloads().scopes() + searches().owners() +
            prefs("music_collections").all.keys.mapNotNull { collectionKey.matchEntire(it)?.groupValues?.get(1) }
        val active = owner().key
        val accounts = keys.filter { kindOf(it) == OwnerKind.Provider && it != UNVERIFIED_SCOPE_KEY }.distinct().sortedBy { it != active }
        (listOf(DEVICE_LOCAL_SCOPE_KEY, GUEST_SCOPE_KEY) + accounts).map { ExportOwner(it, kindOf(it), counts(it)) }
            .filter { it.kind == OwnerKind.Device || it.counts.total > 0 }
    }

    /**
     * Writes the chosen [owners] to [sink]. The file is put together privately and copied to [sink]
     * only when it is complete, so a failure or a cancel before that leaves [sink] untouched.
     */
    suspend fun export(owners: Set<String>, sink: () -> OutputStream, cancelled: () -> Boolean = { false },
        progress: (Int) -> Unit = {}): ExportOutcome = withContext(io) {
        gate.withLock {
            val file = try { workFile("export.jsonl") } catch (error: Exception) { return@withLock exported(ExportOutcome.Failed("storage")) }
            try {
                var records = 0; var bytes = 0L
                BufferedOutputStream(file.outputStream(), 1 shl 16).use { out ->
                    fun line(text: String) {
                        val encoded = (text + "\n").toByteArray(Charsets.UTF_8)
                        bytes += encoded.size
                        if (bytes > maxBytes) throw FileRejected(RejectReason.TooLarge)
                        out.write(encoded)
                    }
                    fun put(record: DataRecord) {
                        line(encodeRecord(record)); records++
                        if (records % 256 == 0) { if (cancelled()) throw Stopped(); progress(records) }
                    }
                    line(headerLine(wallTime()))
                    val chosen = inventory().filter { it.key in owners }
                    for (source in chosen) {
                        if (cancelled()) throw Stopped()
                        line(ownerLine(source.kind, if (source.kind == OwnerKind.Provider) exportScopeDigest(app, source.key) else null)); encoreExportLines(EncoreRepository(db), source.key, wallTime()).forEach(::line)
                        if (source.kind == OwnerKind.Device) {
                            storedSettings().forEach(::put)
                            hidden().entries.forEach { put(DataRecord.Hidden(it)) }
                            pins().cards.forEach { put(DataRecord.Pin(it)) }
                        } else {
                            LISTS.values.forEach { list -> storedCollection(app, source.key, list).forEach { put(DataRecord.Saved(list, it)) } }
                            searches().stored(source.key).forEach { put(DataRecord.Search(it)) }
                            downloads().downloadList(source.key).forEach { put(DataRecord.Download(it)) }
                        }
                        // Newest first, so a reader that stops at a limit keeps the most recent plays.
                        var cursor: HistoryCursor? = null
                        do {
                            val page = history.page(source.key, cursor, limit = 500)
                            page.entries.forEach { if (validHistoryEvent(it)) put(DataRecord.Play(it)) }
                            cursor = page.next
                        } while (cursor != null)
                    }
                    line(JSONObject().put("t", "end").put("records", records).toString())
                }
                checkpoint("export_assembled")
                if (cancelled()) throw Stopped()
                try { sink().use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } } }
                catch (error: IOException) { return@withLock exported(ExportOutcome.Failed("write")) }
                catch (error: SecurityException) { return@withLock exported(ExportOutcome.Failed("write")) }
                progress(records)
                exported(ExportOutcome.Done(records, bytes))
            } catch (error: Stopped) { exported(ExportOutcome.Cancelled) }
            catch (error: FileRejected) { exported(ExportOutcome.TooLarge) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { exported(ExportOutcome.Failed(error::class.simpleName ?: "error")) }
            finally { file.delete() }
        }
    }

    private fun exported(outcome: ExportOutcome) = outcome.also {
        event("local_data action=export result=" + when (it) {
            is ExportOutcome.Done -> "done records=${it.records}"
            ExportOutcome.Cancelled -> "cancelled"
            ExportOutcome.TooLarge -> "too_large"
            is ExportOutcome.Failed -> "failed kind=${it.kind}"
        })
    }

    // ---- import

    /**
     * Copies the chosen document to private storage, up to the ceiling, and reads it through. Nothing
     * kept by Riff is changed. A [ImportOpen.Ready] plan must be given to [import] or [discard].
     */
    suspend fun open(source: () -> InputStream, cancelled: () -> Boolean = { false }): ImportOpen = withContext(io) {
        gate.withLock {
            val file = try { workFile("import.jsonl") } catch (error: Exception) { return@withLock refused(RejectReason.Storage) }
            var keep = false
            try {
                val input = try { source() } catch (error: Exception) { throw FileRejected(RejectReason.Unreadable) }
                input.use { stream ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(1 shl 16); var total = 0L
                        while (true) {
                            val n = try { stream.read(buffer) } catch (error: IOException) { throw FileRejected(RejectReason.Unreadable) }
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw FileRejected(RejectReason.TooLarge)
                            if (cancelled()) throw Stopped()
                            try { checkpoint("import_copy"); out.write(buffer, 0, n) } catch (error: IOException) { throw FileRejected(RejectReason.Storage) }
                        }
                    }
                }
                val sections = Sections(); val admission = Admission(); val counted = HashMap<Int, SectionCounts>()
                val walked = walk(file, wallTime(), sections, { if (cancelled()) throw Stopped() }) { index, record ->
                    if (admission.admit(index, record)) counted[index] = (counted[index] ?: SectionCounts()).with(record)
                }
                val current = owner()
                val plan = ImportPlan(file, walked.exportedAt, sections.owners.mapIndexed { index, (kind, digest) ->
                    ImportSection(index, kind, counted[index] ?: SectionCounts(), when {
                        kind != OwnerKind.Provider -> Destination.Direct
                        current.verified && digest in current.digests -> Destination.Account
                        else -> Destination.Other
                    })
                }, walked.malformed, admission.refused, current.verified)
                keep = true
                event("local_data action=import_open result=ready sections=${plan.sections.size} records=${plan.total} malformed=${plan.malformed}")
                ImportOpen.Ready(plan)
            } catch (error: Stopped) { ImportOpen.Cancelled }
            catch (error: FileRejected) { refused(error.reason) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: IOException) { refused(RejectReason.Unreadable) }
            catch (error: Exception) { refused(RejectReason.Unreadable) }
            finally { if (!keep) file.delete() }
        }
    }

    private fun refused(reason: RejectReason): ImportOpen {
        event("local_data action=import_open result=rejected reason=${reason.name.lowercase()}")
        return ImportOpen.Rejected(reason)
    }

    /** Removes the private copy of a file that will not be imported. */
    suspend fun discard(plan: ImportPlan) { withContext(io + NonCancellable) { plan.file.delete() } }

    /** What is gathered while the history is written, then stored kind by kind. */
    private class Gathered {
        val saved = LinkedHashMap<String, MutableMap<String, MutableList<ProviderTrack>>>()
        val searches = LinkedHashMap<String, MutableList<String>>()
        val downloads = LinkedHashMap<String, MutableList<ProviderTrack>>()
        val hidden = mutableListOf<NotInterestedEntry>()
        val pins = mutableListOf<ProviderHomeCard>()
        val settings = LinkedHashMap<String, Any>()
        var plays = 0; var present = 0; var expired = 0; var full = 0; var left = 0
    }

    /**
     * Imports [plan]. Device and signed-out sections go to the same owner here. An account's section
     * goes to the account signed in now when it is the same account; another account's section is
     * imported only when its number is in [adopt], and then into the account signed in now.
     *
     * [cancelled] is honoured until the first write is committed; until then nothing is changed.
     * After it the import runs to its end, even if the caller has gone.
     */
    suspend fun import(plan: ImportPlan, adopt: Set<Int> = emptySet(), cancelled: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> }): ImportOutcome = withContext(io + NonCancellable) {
        gate.withLock {
            try { imported(run(plan, adopt, cancelled, progress)) } finally { plan.file.delete() }
        }
    }

    private fun imported(outcome: ImportOutcome) = outcome.also {
        event("local_data action=import result=" + when (it) {
            is ImportOutcome.Finished -> "${if (it.report.notStored.isEmpty()) "done" else "partial"} added=${it.report.added.total} present=${it.report.present} malformed=${it.report.malformed} over_limit=${it.report.overLimit} expired=${it.report.expired} left=${it.report.sectionsLeft} not_stored=${it.report.notStored.size}"
            ImportOutcome.Cancelled -> "cancelled"
            is ImportOutcome.Rejected -> "rejected reason=${it.reason.name.lowercase()}"
            is ImportOutcome.Failed -> "failed kind=${it.kind}"
        })
    }

    private suspend fun run(plan: ImportPlan, adopt: Set<Int>, cancelled: () -> Boolean, progress: (Int, Int) -> Unit): ImportOutcome {
        if (!plan.file.isFile) return ImportOutcome.Failed("gone")
        val now = wallTime(); val current = owner()
        val sections = Sections(); val admission = Admission(); val got = Gathered()
        val targets = HashMap<Int, String?>()
        /** Where a section goes, decided again now: the account may have changed since the file was read. */
        fun target(index: Int): String? {
            if (index in targets) return targets[index]
            val (kind, digest) = sections.owners[index]
            return when (kind) {
                OwnerKind.Device -> DEVICE_LOCAL_SCOPE_KEY
                OwnerKind.Guest -> GUEST_SCOPE_KEY
                OwnerKind.Provider -> current.key.takeIf { current.verified && (digest in current.digests || index in adopt) }
            }.also { targets[index] = it; if (it == null) got.left++ }
        }
        val oldest = historyRetentionDays(app)?.let { now - it * DAY_MS }
        val total = plan.total; var seen = 0
        val walked = try {
            // The history is written while the file is read, in one transaction. Everything else is small and is gathered.
            db.write { d ->
                val room = HashMap<String, Int>()
                walk(plan.file, now, sections, { if (cancelled()) throw Stopped() }) { index, record ->
                    if (++seen % 512 == 0) progress(seen.coerceAtMost(total), total)
                    if (!admission.admit(index, record)) return@walk
                    val to = target(index) ?: return@walk
                    when (record) {
                        is DataRecord.Play -> {
                            // Never more than the owner may keep, so nothing already here is pushed out later.
                            val free = room.getOrPut(to) { historyCap - count(d, to) }
                            when {
                                oldest != null && record.entry.playedAt < oldest -> got.expired++
                                free <= 0 -> got.full++
                                insertHistoryEvent(d, to, record.entry) -> { encoreRecordPlay(d, to, record.entry); got.plays++; room[to] = free - 1 }
                                else -> got.present++
                            }
                        }
                        is DataRecord.Saved -> got.saved.getOrPut(to) { LinkedHashMap() }.getOrPut(record.list) { mutableListOf() } += record.track
                        is DataRecord.Search -> got.searches.getOrPut(to) { mutableListOf() } += record.query
                        is DataRecord.Download -> got.downloads.getOrPut(to) { mutableListOf() } += record.track
                        is DataRecord.Hidden -> got.hidden += record.entry
                        is DataRecord.Pin -> got.pins += record.card
                        is DataRecord.Setting -> got.settings[record.name] = record.value
                    }
                }.also { checkpoint("history_written"); if (cancelled()) throw Stopped() }
            }
        } catch (error: Stopped) { return ImportOutcome.Cancelled }
        catch (error: FileRejected) { return ImportOutcome.Rejected(error.reason) }
        catch (error: Exception) { return ImportOutcome.Failed(error::class.simpleName ?: "error") }
        if (got.plays > 0) history.changed()
        checkpoint("history_committed")

        val notStored = mutableListOf<String>()
        var added = SectionCounts(plays = got.plays); var present = got.present; var full = got.full; var kept = 0
        /** One kind of data, one write. A failure is named and the kinds after it are still tried. */
        suspend fun store(name: String, wanted: Boolean, block: suspend () -> Boolean) {
            if (!wanted) return
            val done = try { checkpoint("store_$name"); block() } catch (error: Exception) { false }
            if (!done) notStored += name
        }
        store("saved tracks", got.saved.isNotEmpty()) {
            val result = addToCollections(app, got.saved) ?: return@store false
            added = added.copy(saved = result.saved, recents = result.recents); present += result.present; full += result.full
            true
        }
        store("search history", got.searches.isNotEmpty()) {
            val count = searches().importEntries(got.searches) ?: return@store false
            added = added.copy(searches = count); present += got.searches.values.sumOf { it.size } - count
            true
        }
        store("download list", got.downloads.isNotEmpty()) {
            val count = downloads().importMetadata(got.downloads)
            added = added.copy(downloads = count); present += got.downloads.values.sumOf { it.size } - count
            true
        }
        store("hidden items", got.hidden.isNotEmpty()) {
            val count = hidden().addAll(got.hidden) ?: return@store false
            added = added.copy(hidden = count); present += got.hidden.size - count
            true
        }
        store("speed dial pins", got.pins.isNotEmpty()) {
            val result = pins().addAll(got.pins) ?: return@store false
            added = added.copy(pins = result.added); present += result.present; full += result.full; true
        }
        store("settings", got.settings.isNotEmpty()) {
            val (applied, left, complete) = applySettings(got.settings)
            added = added.copy(settings = applied); kept = left
            complete
        }
        return ImportOutcome.Finished(ImportReport(added, present, walked.malformed, admission.refused + full, got.expired, kept, got.left, notStored))
    }

    @SuppressLint("Recycle") // Lint does not follow rawQuery(...).use { }; the cursor is closed by use.
    private fun count(d: SQLiteDatabase, scope: String): Int =
        d.rawQuery("SELECT COUNT(*) FROM history_events WHERE scope=?", arrayOf(scope)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Writes imported settings, one write per settings file. A history period shorter than the one
     * in force is not applied, because it would remove plays that are here now. Returns how many were
     * applied, how many were kept as they are, and whether every file was written.
     */
    @SuppressLint("UseKtx", "ApplySharedPref") // The result of commit() is reported.
    private fun applySettings(values: Map<String, Any>): Triple<Int, Int, Boolean> {
        var applied = 0; var kept = 0; var failed = false
        values.entries.groupBy { SETTINGS.getValue(it.key).prefs }.forEach { (file, entries) ->
            val editor = prefs(file).edit(); var count = 0
            for ((name, value) in entries) {
                if (name == RETENTION_NAME) {
                    val inForce = historyRetentionDays(app)
                    if (inForce == null || (value as Int) < inForce) { kept++; continue }
                }
                val key = SETTINGS.getValue(name).key
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    else -> continue
                }
                count++
            }
            if (count > 0) { if (editor.commit()) applied += count else failed = true }
        }
        return Triple(applied, kept, !failed)
    }
}
