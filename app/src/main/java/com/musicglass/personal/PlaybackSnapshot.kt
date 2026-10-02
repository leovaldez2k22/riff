package com.musicglass.personal

import kotlinx.serialization.json.*

/** Saves identifiers and metadata, never expiring stream URLs, tokens or request headers. */
internal data class SavedQueueItem(val mediaId: String, val title: String, val artist: String,
    val artworkUrl: String? = null, val album: String = "", val artistId: String? = null,
    val albumId: String? = null, val live: Boolean = false, val localUri: String? = null,val music:Boolean=true)
internal data class PlaybackSnapshot(val queue: List<SavedQueueItem>, val index: Int,
    val positionMs: Long, val shuffle: Boolean = false, val repeat: Int = 0,val radioSeed:String?=null,val collectionTitle:String="")
internal fun encodePlaybackSnapshot(s: PlaybackSnapshot): String = buildJsonObject {
    put("version",1);put("index",s.index);put("position",s.positionMs);put("shuffle",s.shuffle);put("repeat",s.repeat);put("radioSeed",s.radioSeed);put("collectionTitle",s.collectionTitle)
    putJsonArray("queue") {s.queue.take(2000).forEach {q->add(buildJsonObject {
        put("id",q.mediaId);put("title",q.title);put("artist",q.artist);put("art",q.artworkUrl)
        put("album",q.album);put("artistId",q.artistId);put("albumId",q.albumId);put("live",q.live);put("local",q.localUri);put("music",q.music)
    })}}
}.toString()
internal fun decodePlaybackSnapshot(text: String): PlaybackSnapshot? = runCatching {
    require(text.length<=3_000_000)
    val o=Json.parseToJsonElement(text).jsonObject
    require(o["version"]?.jsonPrimitive?.int==1)
    val queue=o.getValue("queue").jsonArray.take(2000).map {n->val q=n.jsonObject
        fun str(k:String)=q[k]?.jsonPrimitive?.contentOrNull
        val id=str("id").orEmpty()
        require(id.matches(Regex("yt:[A-Za-z0-9_-]{11}|local:[0-9]+")))
        val local=str("local")?.takeIf {it.startsWith("content://media/")}
        require(!id.startsWith("local:") || local!=null)
        SavedQueueItem(id,str("title").orEmpty().take(180),str("artist").orEmpty().take(180),
            validProviderArtworkUrl(str("art")),str("album").orEmpty().take(180),str("artistId"),str("albumId"),
            q["live"]?.jsonPrimitive?.booleanOrNull==true,local,q["music"]?.jsonPrimitive?.booleanOrNull?:true)
    }
    require(queue.isNotEmpty())
    val index=o.getValue("index").jsonPrimitive.int.coerceIn(queue.indices)
    PlaybackSnapshot(queue,index,if(queue[index].live) 0 else o.getValue("position").jsonPrimitive.long.coerceAtLeast(0),
        o["shuffle"]?.jsonPrimitive?.booleanOrNull==true,o["repeat"]?.jsonPrimitive?.intOrNull?.coerceIn(0,2)?:0,o["radioSeed"]?.jsonPrimitive?.contentOrNull?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{11}"))},o["collectionTitle"]?.jsonPrimitive?.contentOrNull.orEmpty().take(180))
}.getOrNull()?.let(::deduplicateRadioSnapshot)

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
