package com.musicglass.personal

import kotlinx.serialization.json.*

/** Saves identifiers and metadata, never expiring stream URLs, tokens or request headers. */
internal data class SavedQueueItem(val mediaId: String, val title: String, val artist: String,
    val artworkUrl: String? = null, val album: String = "", val artistId: String? = null,
    val albumId: String? = null, val live: Boolean = false, val localUri: String? = null,val music:Boolean=true)
internal data class PlaybackSnapshot(val queue: List<SavedQueueItem>, val index: Int,
    val positionMs: Long, val shuffle: Boolean = false, val repeat: Int = 0,val radioSeed:String?=null,val collectionTitle:String="",
    /** The selected song's length, so a restored queue that is not prepared yet can show where it is (Q18-019). 0 when unknown. */
    val durationMs: Long = 0)

/** One ceiling for a queue sent to the service and for a queue kept on disk, measured in UTF-8 bytes. */
internal const val MAX_SNAPSHOT_BYTES = 6 * 1024 * 1024
internal const val MAX_SNAPSHOT_ITEMS = 2000
/** Longer than any song; a saved length past it is not believed. */
internal const val MAX_SAVED_DURATION_MS = 24*3600_000L
internal const val SNAPSHOT_FORMAT = "riff.playback-snapshot"
/** Version 1 had no format name. It is still read; only version 2 is written. */
internal const val SNAPSHOT_VERSION = 2
private const val MAX_TEXT = 180
private val CATALOG_ID = Regex("[A-Za-z0-9_-]{1,128}")

/** UTF-8 size without building the byte array. Unpaired surrogates count as the three-byte replacement. */
internal fun utf8Size(text: String): Long {
    var bytes = 0L; var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.code < 0x80 -> bytes += 1
            c.code < 0x800 -> bytes += 2
            Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { bytes += 4; i++ }
            else -> bytes += 3
        }
        i++
    }
    return bytes
}

internal fun encodePlaybackSnapshot(s: PlaybackSnapshot): String = buildJsonObject {
    put("format",SNAPSHOT_FORMAT);put("version",SNAPSHOT_VERSION)
    put("index",s.index);put("position",s.positionMs);put("shuffle",s.shuffle);put("repeat",s.repeat);put("radioSeed",s.radioSeed);put("collectionTitle",s.collectionTitle.take(MAX_TEXT))
    if(s.durationMs>0) put("duration",s.durationMs)
    putJsonArray("queue") {s.queue.take(MAX_SNAPSHOT_ITEMS).forEach {q->add(buildJsonObject {
        put("id",q.mediaId);put("title",q.title.take(MAX_TEXT));put("artist",q.artist.take(MAX_TEXT));put("art",q.artworkUrl)
        put("album",q.album.take(MAX_TEXT));put("artistId",q.artistId);put("albumId",q.albumId);put("live",q.live);put("local",q.localUri);put("music",q.music)
    })}}
}.toString()

/** Null when the queue cannot be sent or stored within the shared ceiling. Check before writing or sending. */
internal fun encodePlaybackSnapshotBounded(s: PlaybackSnapshot): String? =
    encodePlaybackSnapshot(s).takeIf { utf8Size(it) <= MAX_SNAPSHOT_BYTES }

internal sealed interface SnapshotDecode {
    data class Ok(val snapshot: PlaybackSnapshot) : SnapshotDecode
    /** Written by a newer build. It must be left alone, not treated as damage. */
    data class Newer(val version: Int) : SnapshotDecode
    /** [reason] is a fixed word, never content: size, json, version, item, empty. */
    data class Invalid(val reason: String) : SnapshotDecode
}

internal fun decodePlaybackSnapshotDetailed(text: String): SnapshotDecode {
    // A character is at least one byte, so this rejects without scanning anything larger than the ceiling.
    if(text.length>MAX_SNAPSHOT_BYTES || utf8Size(text)>MAX_SNAPSHOT_BYTES) return SnapshotDecode.Invalid("size")
    val o=runCatching {Json.parseToJsonElement(text).jsonObject}.getOrNull()?:return SnapshotDecode.Invalid("json")
    val version=runCatching {o["version"]?.jsonPrimitive?.intOrNull}.getOrNull()?:return SnapshotDecode.Invalid("version")
    if(version>SNAPSHOT_VERSION) return SnapshotDecode.Newer(version)
    val format=runCatching {o["format"]?.jsonPrimitive?.contentOrNull}.getOrNull()
    if(version<1 || (version==1 && format!=null) || (version==2 && format!=SNAPSHOT_FORMAT)) return SnapshotDecode.Invalid("version")
    val queue=runCatching {o.getValue("queue").jsonArray.take(MAX_SNAPSHOT_ITEMS).map {n->val q=n.jsonObject
        fun str(k:String)=q[k]?.jsonPrimitive?.contentOrNull
        val id=str("id").orEmpty()
        require(id.matches(Regex("yt:[A-Za-z0-9_-]{11}|local:[0-9]{1,19}")))
        // A saved item can only point at the MediaStore row its own identifier names.
        val local=str("local")?.takeIf {id.startsWith("local:") && it=="content://media/external/audio/media/${id.removePrefix("local:")}"}
        require(!id.startsWith("local:") || local!=null)
        SavedQueueItem(id,str("title").orEmpty().take(MAX_TEXT),str("artist").orEmpty().take(MAX_TEXT),
            validProviderArtworkUrl(str("art")),str("album").orEmpty().take(MAX_TEXT),str("artistId")?.takeIf(CATALOG_ID::matches),str("albumId")?.takeIf(CATALOG_ID::matches),
            q["live"]?.jsonPrimitive?.booleanOrNull==true,local,q["music"]?.jsonPrimitive?.booleanOrNull?:true)
    }}.getOrNull()?:return SnapshotDecode.Invalid("item")
    if(queue.isEmpty()) return SnapshotDecode.Invalid("empty")
    return runCatching {
        val index=o.getValue("index").jsonPrimitive.int.coerceIn(queue.indices)
        SnapshotDecode.Ok(deduplicateRadioSnapshot(PlaybackSnapshot(queue,index,if(queue[index].live) 0 else o.getValue("position").jsonPrimitive.long.coerceAtLeast(0),
            o["shuffle"]?.jsonPrimitive?.booleanOrNull==true,o["repeat"]?.jsonPrimitive?.intOrNull?.coerceIn(0,2)?:0,o["radioSeed"]?.jsonPrimitive?.contentOrNull?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{11}"))},o["collectionTitle"]?.jsonPrimitive?.contentOrNull.orEmpty().take(MAX_TEXT),
            if(queue[index].live) 0 else o["duration"]?.jsonPrimitive?.longOrNull?.takeIf {it in 1..MAX_SAVED_DURATION_MS}?:0)))
    }.getOrElse {SnapshotDecode.Invalid("item")}
}

internal fun decodePlaybackSnapshot(text: String): PlaybackSnapshot? = (decodePlaybackSnapshotDetailed(text) as? SnapshotDecode.Ok)?.snapshot

/** Cleans old automatically generated queues without changing an authored album/playlist. */
internal fun deduplicateRadioSnapshot(snapshot:PlaybackSnapshot):PlaybackSnapshot {
    if(snapshot.radioSeed==null) return snapshot
    val selected=snapshot.queue[snapshot.index]
    val selectedKey=queueRecordingKey(selected.title,selected.artist)
    val seen=mutableSetOf<String>()
    val kept=snapshot.queue.withIndex().filter {entry->
        val key=queueRecordingKey(entry.value.title,entry.value.artist)
        if(key==selectedKey && entry.index!=snapshot.index) false else seen.add(key)
    }
    return snapshot.copy(queue=kept.map {it.value},index=kept.indexOfFirst {it.index==snapshot.index}.coerceAtLeast(0))
}
