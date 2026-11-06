package com.musicglass.data

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Called only inside the history transaction: a committed span and all its music rollups are one write. */
internal class EncoreRollup(private val store:SqliteLibraryRepository,private val zone:ZoneId) {
    fun ensure(scope:String){
        if(store.query("SELECT version FROM encore_rollup_version WHERE scope=?",scope){it.getInt(1)}.singleOrNull()==1)return
        rebuild(scope)
    }
    fun clear(scope:String){
        store.execute("DELETE FROM encore_daily WHERE scope=?",scope)
        store.execute("DELETE FROM encore_lifetime WHERE scope=?",scope)
        store.execute("DELETE FROM encore_hourly WHERE scope=?",scope)
    }
    fun rebuild(scope:String){
        clear(scope)
        var occurrence:String?=null;var heard=0L;var start=0L;var track:TrackRecord?=null
        store.forEachRow("SELECT * FROM history_span WHERE scope=? ORDER BY occurrence,started_ms,id",scope){row->
            val id=row.getString("occurrence")
            if(occurrence!=id){occurrence=id;heard=0;start=row.getLong("started_ms");track=store.get(scope,row.getString("track_id"))}
            val ms=row.getLong("listened_ms");val counted=heard<5000&&heard+ms>=5000;heard+=ms
            track?.let{add(it,row.getLong("started_ms"),ms,if(counted)start else null)}
        }
        store.execute("INSERT OR REPLACE INTO encore_rollup_version VALUES(?,1)",scope)
    }
    fun add(track:TrackRecord,started:Long,heard:Long,playStarted:Long?){
        if(track.isEpisode)return
        val rows=buildList {
            add(listOf("song",track.id,track.title,track.artist,track.artworkKey?:track.artworkUrl))
            if(track.album.isNotBlank())add(listOf("album",track.albumId?:"name:"+track.album.lowercase(Locale.ROOT)+"\u0000"+track.artist.lowercase(Locale.ROOT),track.album,track.artist,track.artworkKey?:track.artworkUrl))
            val artists=track.artist.takeUnless{it=="Unknown artist"}.orEmpty().split(", "," & "," feat. "," ft. "," x "," X "," with ").map(String::trim).filter(String::isNotEmpty).distinct()
            artists.forEachIndexed{index,name->
                // Locally derived composite-credit IDs must not alias the first artist with the whole string.
                val id=track.artistId?.takeUnless{it.startsWith("local-artist:")}
                add(listOf("artist",if(index==0&&id!=null)"id:$id" else "name:"+name.lowercase(Locale.ROOT),name,"",null))
            }
        }
        fun daily(day:String,plays:Int,ms:Long){rows.forEach{row->store.execute("""INSERT INTO encore_daily VALUES(?,?,?,?,?,?,?,?,?)
            ON CONFLICT(scope,day,kind,key) DO UPDATE SET plays=plays+excluded.plays,listened_ms=listened_ms+excluded.listened_ms,
            title=excluded.title,subtitle=excluded.subtitle,artwork=COALESCE(excluded.artwork,artwork)""",track.scope,day,row[0],row[1],row[2],row[3],row[4],plays,ms)
            store.execute("""INSERT INTO encore_lifetime VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(scope,kind,key) DO UPDATE SET
                plays=plays+excluded.plays,listened_ms=listened_ms+excluded.listened_ms,first_day=min(first_day,excluded.first_day),last_day=max(last_day,excluded.last_day),
                title=excluded.title,subtitle=excluded.subtitle,artwork=COALESCE(excluded.artwork,artwork)""",track.scope,row[0],row[1],row[2],row[3],row[4],plays,ms,day,day)
        }}
        if(playStarted!=null)daily(Instant.ofEpochMilli(playStarted).atZone(zone).toLocalDate().toString(),1,0)
        historyHourParts(started,heard,zone).forEach{part->
            daily(part.day,0,part.listenedMs)
            store.execute("INSERT INTO encore_hourly VALUES(?,?,?,?) ON CONFLICT(scope,day,hour) DO UPDATE SET listened_ms=listened_ms+excluded.listened_ms",track.scope,part.day,part.hour,part.listenedMs)
        }
    }
}

internal data class HistoryHourPart(val day:String,val hour:Int,val listenedMs:Long)
/** Instant-based boundaries preserve both repeated DST hours and skipped local dates. */
internal fun historyHourParts(started:Long,heard:Long,zone:ZoneId):List<HistoryHourPart>{
    var at=started;val end=Math.addExact(started,heard);val parts=mutableListOf<HistoryHourPart>()
    while(at<end){
        val local=Instant.ofEpochMilli(at).atZone(zone)
        val boundary=local.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
        val until=minOf(end,boundary);check(until>at)
        parts+=HistoryHourPart(local.toLocalDate().toString(),local.hour,until-at);at=until
    }
    return parts
}
