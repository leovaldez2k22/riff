package com.musicglass.personal

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Feature 12, section 12.3: what Encore is drawn from. Each play keeps how long it was really heard (summed from the
 * player's 5 s ticks, which stop while paused), its album, its artist's channel, how it ended and where it came
 * from; and daily totals per song, album and artist, plus listening per hour, are kept beside the plays so a
 * lifetime question never reads 100,000 rows. Totals follow the plays: clearing history clears them, and history
 * retention removes their days too unless the owner keeps Encore totals (Settings → Privacy and data).
 */

/** How a play ended, as stored in history_events.ended. */
internal object EncoreEnded { const val PLAYING = 0; const val FINISHED = 1; const val SKIPPED = 2; const val STOPPED = 3 }

/** Where a play's audio came from. */
internal object EncoreSource { const val STREAM = "stream"; const val DOWNLOAD = "download"; const val DEVICE = "device" }

internal object EncoreKind { const val SONG = "song"; const val ALBUM = "album"; const val ARTIST = "artist" }

/** Version 2 of library-state.db: the columns and tables above. Runs on a fresh database and on an upgrade. */
internal fun encoreSchema(db: SQLiteDatabase) {
    for (column in listOf("listened_ms INTEGER NOT NULL DEFAULT 0", "album TEXT", "album_id TEXT", "artist_id TEXT",
        "ended INTEGER NOT NULL DEFAULT 0", "source TEXT")) db.execSQL("ALTER TABLE history_events ADD COLUMN $column")
    db.execSQL("CREATE TABLE stats_daily(scope TEXT NOT NULL,day TEXT NOT NULL,kind TEXT NOT NULL,key TEXT NOT NULL,title TEXT NOT NULL,subtitle TEXT NOT NULL,artwork TEXT,plays INTEGER NOT NULL DEFAULT 0,listened_ms INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,day,kind,key))")
    db.execSQL("CREATE INDEX stats_daily_day ON stats_daily(scope,day)")
    db.execSQL("CREATE TABLE stats_hourly(scope TEXT NOT NULL,day TEXT NOT NULL,hour INTEGER NOT NULL,listened_ms INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,day,hour))")
}

/** A local date, "2026-10-28", the way days are keyed: a tick near midnight is filed where it fell. */
internal fun encoreDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

internal fun encoreHour(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int = Instant.ofEpochMilli(epochMs).atZone(zone).hour

/**
 * The artists a play credits, each counted with the play's full time (as Last.fm does): "A, B & C feat. D" is four.
 * The channel id keys the first artist when known, so one artist under two spellings is counted once.
 */
internal fun encoreArtists(artist: String, artistId: String?): List<Pair<String, String>> {
    val names = artist.split(", ", " & ", " feat. ", " ft. ", " x ", " X ", " with ").map(String::trim).filter(String::isNotEmpty).distinct()
    return names.mapIndexed { index, name ->
        val key = if (index == 0 && !artistId.isNullOrBlank()) "id:$artistId" else "name:" + name.lowercase(Locale.ROOT)
        key to name
    }
}

private class EncoreRow(val kind: String, val key: String, val title: String, val subtitle: String, val artwork: String?)

/** The song, album and artist rows one play adds to. An album is counted only when it is known. */
private fun encoreRows(mediaId: String, title: String, artist: String, artwork: String?, album: String?, albumId: String?, artistId: String?) =
    buildList {
        add(EncoreRow(EncoreKind.SONG, mediaId, title, artist, artwork))
        if (!album.isNullOrBlank()) add(EncoreRow(EncoreKind.ALBUM, albumId?.takeIf(String::isNotBlank) ?: "name:" + album.lowercase(Locale.ROOT) + "\u0000" + artist.lowercase(Locale.ROOT),
            album, artist, artwork))
        encoreArtists(artist, artistId).forEach { (key, name) -> add(EncoreRow(EncoreKind.ARTIST, key, name, "", null)) }
    }

private fun upsertDaily(db: SQLiteDatabase, scope: String, day: String, row: EncoreRow, plays: Int, listenedMs: Long) {
    db.execSQL("INSERT INTO stats_daily(scope,day,kind,key,title,subtitle,artwork,plays,listened_ms) VALUES(?,?,?,?,?,?,?,?,?) " +
        "ON CONFLICT(scope,day,kind,key) DO UPDATE SET plays=plays+excluded.plays,listened_ms=listened_ms+excluded.listened_ms," +
        "title=excluded.title,subtitle=excluded.subtitle,artwork=COALESCE(excluded.artwork,artwork)",
        arrayOf<Any?>(scope, day, row.kind, row.key, row.title.take(180), row.subtitle.take(180), row.artwork?.takeIf { it.length <= 2048 }, plays, listenedMs))
}

/** A play was recorded: one play for its song, album and artists on its day. Inside the caller's transaction. */
internal fun encoreRecordPlay(db: SQLiteDatabase, scope: String, entry: HistoryEntry, zone: ZoneId = ZoneId.systemDefault()) {
    if (!entry.music) return
    val day = encoreDay(entry.playedAt, zone)
    encoreRows(entry.mediaId, entry.title, entry.artist, entry.artworkUrl, entry.album, entry.albumId, entry.artistId)
        .forEach { upsertDaily(db, scope, day, it, plays = 1, listenedMs = 0) }
}

/**
 * [deltaMs] more of play [eventId] was heard, ending at [now]. Adds it to the play, its day's totals and its hour.
 * False, and nothing written, when the play is gone (history cleared or the play pruned since).
 */
internal fun encoreAddListened(db: SQLiteDatabase, scope: String, eventId: String, deltaMs: Long, now: Long,
    zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (deltaMs <= 0) return true
    val row = db.query("history_events", arrayOf("media_id", "title", "artist", "artwork", "album", "album_id", "artist_id", "music"),
        "scope=? AND event_id=?", arrayOf(scope, eventId), null, null, null).use { c ->
        if (!c.moveToFirst()) return false
        listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7).toString())
    }
    db.execSQL("UPDATE history_events SET listened_ms=listened_ms+? WHERE scope=? AND event_id=?", arrayOf<Any>(deltaMs, scope, eventId))
    if (row[7] == "0") return true
    val day = encoreDay(now, zone)
    encoreRows(row[0]!!, row[1]!!, row[2]!!, row[3], row[4], row[5], row[6]).forEach { upsertDaily(db, scope, day, it, plays = 0, listenedMs = deltaMs) }
    db.execSQL("INSERT INTO stats_hourly(scope,day,hour,listened_ms) VALUES(?,?,?,?) ON CONFLICT(scope,day,hour) DO UPDATE SET listened_ms=listened_ms+excluded.listened_ms",
        arrayOf<Any>(scope, day, encoreHour(now, zone), deltaMs))
    return true
}

internal fun encoreMarkEnded(db: SQLiteDatabase, scope: String, eventId: String, ended: Int) {
    db.update("history_events", ContentValues().apply { put("ended", ended) }, "scope=? AND event_id=? AND ended=0", arrayOf(scope, eventId))
}

/** Clearing an owner's history clears their Encore. */
internal fun encoreClear(db: SQLiteDatabase, scope: String) {
    db.delete("stats_daily", "scope=?", arrayOf(scope)); db.delete("stats_hourly", "scope=?", arrayOf(scope))
}

/** History retention reaches Encore's days too (unless the owner keeps Encore totals). */
internal fun encorePruneBefore(db: SQLiteDatabase, scope: String, day: String) {
    db.delete("stats_daily", "scope=? AND day<?", arrayOf(scope, day)); db.delete("stats_hourly", "scope=? AND day<?", arrayOf(scope, day))
}

/**
 * Builds an owner's totals again from their plays, after an import or a migration. Plays kept from before version
 * 2 have no listened time; they count as plays only, and Encore says their totals are estimated.
 */
internal fun encoreRebuild(db: SQLiteDatabase, scope: String, zone: ZoneId = ZoneId.systemDefault()) {
    encoreClear(db, scope)
    db.query("history_events", arrayOf("event_id", "media_id", "title", "artist", "artwork", "played_at", "music", "album", "album_id", "artist_id", "listened_ms"),
        "scope=?", arrayOf(scope), null, null, null).use { c ->
        while (c.moveToNext()) {
            if (c.getInt(6) == 0) continue
            val playedAt = c.getLong(5); val day = encoreDay(playedAt, zone); val listened = c.getLong(10)
            encoreRows(c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(7), c.getString(8), c.getString(9))
                .forEach { upsertDaily(db, scope, day, it, plays = 1, listenedMs = listened) }
            if (listened > 0) db.execSQL("INSERT INTO stats_hourly(scope,day,hour,listened_ms) VALUES(?,?,?,?) ON CONFLICT(scope,day,hour) DO UPDATE SET listened_ms=listened_ms+excluded.listened_ms",
                arrayOf<Any>(scope, day, encoreHour(playedAt, zone), listened))
        }
    }
}

/** Settings → Privacy and data: keep Encore's daily totals when history retention removes the plays. Off by default. */
internal fun keepEncoreTotals(context: android.content.Context) = context.applicationContext
    .getSharedPreferences("music_collections", android.content.Context.MODE_PRIVATE).getBoolean("encore_keep_totals", false)

internal fun setKeepEncoreTotals(context: android.content.Context, keep: Boolean) {
    context.applicationContext.getSharedPreferences("music_collections", android.content.Context.MODE_PRIVATE)
        .edit().putBoolean("encore_keep_totals", keep).apply()
}

/** Listened time is written at most this often, and whenever a play ends; a crash loses at most this much. */
internal const val ENCORE_FLUSH_MS = 30_000L

/**
 * Feature 12: the play being heard and the listened time waiting to be written. Fed the service's 5 s ticks,
 * which stop while paused and are capped at 5 s, so a pause or a seek never adds time.
 */
internal class EncoreListening(private val write: (scope: String, eventId: String, ms: Long) -> Unit,
    private val end: (scope: String, eventId: String, how: Int) -> Unit) {
    private var scope: String? = null
    private var eventId: String? = null
    private var pending = 0L
    var current: String? = null; private set

    /** A new play was recorded; whatever the last one had heard is written first. */
    fun started(scope: String, eventId: String) { ended(EncoreEnded.SKIPPED); this.scope = scope; this.eventId = eventId; current = eventId }

    fun heard(deltaMs: Long) {
        if (eventId == null || deltaMs <= 0) return
        pending += deltaMs
        if (pending >= ENCORE_FLUSH_MS) flush()
    }

    fun flush() {
        val s = scope; val e = eventId
        if (s != null && e != null && pending > 0) write(s, e, pending)
        pending = 0
    }

    /** The play is over: its time is written and how it ended recorded. Does nothing when no play is open. */
    fun ended(how: Int) {
        val s = scope ?: return; val e = eventId ?: return
        flush(); end(s, e, how)
        scope = null; eventId = null; current = null
    }
}

/** Feature 12: how a play ended, from why the player moved on. */
internal fun encoreEndedBy(transitionReason: Int): Int = when (transitionReason) {
    androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> EncoreEnded.FINISHED
    else -> EncoreEnded.SKIPPED
}
