package com.musicglass.data

import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaylistRepository(private val store:SqliteLibraryRepository) {
    companion object { const val SAVED_PLAYLIST="riff-local-saved" }
    fun isSaved(trackId:String):Boolean=store.query("SELECT 1 FROM playlist_entry WHERE scope='local' AND playlist_id=? AND entry_id=?",SAVED_PLAYLIST,SqliteLibraryRepository.digest(trackId)){true}.isNotEmpty()
    /** Explicit device-local membership; never a YouTube Music account write. */
    fun toggleSaved(track:TrackRecord):Boolean=store.transaction(durable=true){
        require(track.scope in setOf(Scopes.LOCAL,Scopes.GUEST)){"Saving this account scope is not supported"}
        execute("INSERT INTO playlist VALUES('local',?,'Saved locally','Saved on this device. No account synchronization.',?,NULL) ON CONFLICT(scope,id) DO NOTHING",SAVED_PLAYLIST,System.currentTimeMillis())
        check(query("SELECT remote_id FROM playlist WHERE scope='local' AND id=?",SAVED_PLAYLIST){it.getString(1)}.single()==null){"Local saved playlist cannot be bound to a remote account"}
        val entry=SqliteLibraryRepository.digest(track.id);val present=isSaved(track.id)
        if(present)execute("DELETE FROM playlist_entry WHERE scope='local' AND playlist_id=? AND entry_id=?",SAVED_PLAYLIST,entry)
        else{
            upsert(track.copy(scope=Scopes.LOCAL))
            val ordinal=query("SELECT COALESCE(max(ordinal),-1)+1 FROM playlist_entry WHERE scope='local' AND playlist_id=?",SAVED_PLAYLIST){it.getLong(1)}.single()
            execute("INSERT INTO playlist_entry VALUES('local',?,?,?,?)",SAVED_PLAYLIST,entry,ordinal,track.id)
        }
        execute("UPDATE playlist SET updated_ms=? WHERE scope='local' AND id=?",System.currentTimeMillis(),SAVED_PLAYLIST)
        !present
    }

    fun create(scope:String,title:String,description:String=""):String=store.transaction(durable=true){
        require(title.isNotBlank()&&title.length<=300);val id=UUID.randomUUID().toString()
        execute("INSERT INTO playlist VALUES(?,?,?,?,?,NULL)",Scopes.validate(scope),id,title,description,System.currentTimeMillis());id
    }
    fun list(scope:String):List<PlaylistRecord> = store.query("SELECT * FROM playlist WHERE scope=? ORDER BY updated_ms DESC,id",Scopes.validate(scope)){PlaylistRecord(it.getString("scope"),it.getString("id"),it.getString("title"),it.getString("description"),it.getLong("updated_ms"),it.getString("remote_id"))}
    fun entries(scope:String,playlist:String,limit:Int=500,offset:Long=0):List<PlaylistEntry> {require(limit in 1..5000&&offset>=0);return store.query("SELECT e.entry_id,e.ordinal,t.* FROM playlist_entry e JOIN track t ON t.scope=e.scope AND t.id=e.track_id WHERE e.scope=? AND e.playlist_id=? ORDER BY e.ordinal LIMIT ? OFFSET ?",Scopes.validate(scope),playlist,limit,offset){PlaylistEntry(it.getString("entry_id"),it.getLong("ordinal"),SqliteLibraryRepository.track(it))}}
    fun add(scope:String,playlist:String,track:TrackRecord):String=store.transaction(durable=true){
        check(list(scope).any{it.id==playlist&&it.remoteId==null}){"A local playlist is required"}
        if(get(scope,track.id)==null)upsert(track.copy(scope=scope));val entry=UUID.randomUUID().toString()
        val ordinal=query("SELECT COALESCE(max(ordinal),-1)+1 FROM playlist_entry WHERE scope=? AND playlist_id=?",scope,playlist){it.getLong(1)}.single()
        execute("INSERT INTO playlist_entry VALUES(?,?,?,?,?)",scope,playlist,entry,ordinal,track.id)
        execute("UPDATE playlist SET updated_ms=? WHERE scope=? AND id=?",System.currentTimeMillis(),scope,playlist);entry
    }
    fun count(scope:String,playlist:String):Long=store.query("SELECT count(*) FROM playlist_entry WHERE scope=? AND playlist_id=?",Scopes.validate(scope),playlist){it.getLong(1)}.single()
    fun moveBy(scope:String,playlist:String,entry:String,up:Boolean)=store.transaction(durable=true){
        Scopes.validate(scope)
        val ordinal=query("SELECT ordinal FROM playlist_entry WHERE scope=? AND playlist_id=? AND entry_id=?",scope,playlist,entry){it.getLong(1)}.singleOrNull()?:return@transaction
        val adjacent=query("SELECT entry_id,ordinal FROM playlist_entry WHERE scope=? AND playlist_id=? AND ordinal ${if(up)"<"else">"} ? ORDER BY ordinal ${if(up)"DESC"else"ASC"} LIMIT 1",scope,playlist,ordinal){it.getString(1) to it.getLong(2)}.singleOrNull()?:return@transaction
        execute("UPDATE playlist_entry SET ordinal=? WHERE scope=? AND playlist_id=? AND entry_id=?",adjacent.second,scope,playlist,entry)
        execute("UPDATE playlist_entry SET ordinal=? WHERE scope=? AND playlist_id=? AND entry_id=?",ordinal,scope,playlist,adjacent.first)
    }
    fun remove(scope:String,playlist:String,entry:String)=store.transaction(durable=true){execute("DELETE FROM playlist_entry WHERE scope=? AND playlist_id=? AND entry_id=?",Scopes.validate(scope),playlist,entry)}
    fun reorder(scope:String,playlist:String,entryIds:List<String>)=store.transaction(durable=true){
        require(count(scope,playlist)<=5000){"Use occurrence moves for a large playlist"};val current=entries(scope,playlist,5000).map{it.entryId};require(current.size==entryIds.size&&current.toSet()==entryIds.toSet()&&entryIds.distinct().size==entryIds.size)
        entryIds.forEachIndexed{index,id->execute("UPDATE playlist_entry SET ordinal=? WHERE scope=? AND playlist_id=? AND entry_id=?",index,scope,playlist,id)}
    }
    fun rename(scope:String,id:String,title:String)=store.transaction(durable=true){require(title.isNotBlank()&&title.length<=300);execute("UPDATE playlist SET title=?,updated_ms=? WHERE scope=? AND id=? AND remote_id IS NULL",title,System.currentTimeMillis(),Scopes.validate(scope),id)}
    fun delete(scope:String,id:String)=store.transaction(durable=true){execute("DELETE FROM playlist WHERE scope=? AND id=? AND remote_id IS NULL",Scopes.validate(scope),id)}
    fun observe(scope:String):Flow<List<PlaylistRecord>> =store.revisions.mapLatest{withContext(Dispatchers.IO){list(scope)}}.distinctUntilChanged()
}

class HistoryRepository(private val store:SqliteLibraryRepository,private val zone:ZoneId=ZoneId.systemDefault(),private val maximumOccurrences:Int=100000) {
    private val writes=mutableMapOf<String,Int>()
    private val encore=EncoreRollup(store,zone)
    init{
        require(maximumOccurrences in 1..100000)
        store.transaction{
            val scopes=(listOf(Scopes.LOCAL,Scopes.GUEST)+query("SELECT DISTINCT scope FROM history_span UNION SELECT scope FROM daily_total"){it.getString(1)}).distinct()
            scopes.forEach{ensureDaily(it);encore.ensure(it)}
        }
    }
    fun epoch(scope:String):Long=store.query("SELECT epoch FROM history_epoch WHERE scope=?",Scopes.validate(scope)){it.getLong(1)}.singleOrNull()?:0L
    private fun ensureDaily(scope:String){
        if(store.query("SELECT version FROM history_rollup_version WHERE scope=?",scope){it.getInt(1)}.singleOrNull()==3)return
        if(!store.privacy(scope).keepTotals)rebuildDaily(scope)
        store.execute("INSERT OR REPLACE INTO history_rollup_version VALUES(?,3)",scope)
    }
    private fun rebuildDaily(scope:String){
        val days=linkedMapOf<String,Long>()
        store.forEachRow("SELECT started_ms,listened_ms FROM history_span WHERE scope=?",scope){row->
            historyDayParts(row.getLong(1),row.getLong(2),zone).forEach{(day,heard)->days[day]=(days[day]?:0)+heard}
        }
        store.execute("DELETE FROM daily_total WHERE scope=?",scope)
        days.forEach{(day,heard)->store.execute("INSERT INTO daily_total VALUES(?,?,?)",scope,day,heard)}
    }
    fun record(span:ListeningSpan,expectedEpoch:Long=epoch(span.scope))=store.transaction {
        require(span.listenedMs in 0..5000 && span.startedMs>=0 && span.startedMs<=Long.MAX_VALUE-span.listenedMs)
        val scope=Scopes.validate(span.scope)
        if(!privacy(scope).saveHistory||span.listenedMs==0L||epoch(scope)!=expectedEpoch)return@transaction
        ensureDaily(scope);encore.ensure(scope)
        val exists=query("SELECT 1 FROM history_span WHERE scope=? AND id=?",scope,span.id){it.getInt(1)}.isNotEmpty()
        if(exists)return@transaction
        val prior=query("SELECT track_id,listened_ms,started_ms FROM history_occurrence WHERE scope=? AND occurrence=?",scope,span.occurrence){Triple(it.getString(1),it.getLong(2),it.getLong(3))}.singleOrNull()
        require(prior==null||prior.first==span.trackId){"A listening occurrence cannot change recording identity"}
        execute("INSERT INTO history_span VALUES(?,?,?,?,?,?,?)",scope,span.id,span.occurrence,span.trackId,span.startedMs,span.listenedMs,span.completed)
        execute("""INSERT INTO history_occurrence VALUES(?,?,?,?,?,?,?) ON CONFLICT(scope,occurrence) DO UPDATE SET
            started_ms=min(started_ms,excluded.started_ms),updated_ms=max(updated_ms,excluded.updated_ms),
            listened_ms=listened_ms+excluded.listened_ms,completed=max(completed,excluded.completed)""",scope,span.occurrence,span.trackId,span.startedMs,span.startedMs+span.listenedMs,span.listenedMs,span.completed)
        historyDayParts(span.startedMs,span.listenedMs,zone).forEach{(day,heard)->
            execute("INSERT INTO daily_total VALUES(?,?,?) ON CONFLICT(scope,day) DO UPDATE SET listened_ms=listened_ms+excluded.listened_ms",scope,day,heard)
        }
        encore.add(checkNotNull(get(scope,span.trackId)),span.startedMs,span.listenedMs,if((prior?.second?:0)<5000&&(prior?.second?:0)+span.listenedMs>=5000)minOf(prior?.third?:span.startedMs,span.startedMs)else null)
        val overCap=prior==null&&query("SELECT count(*) FROM history_occurrence WHERE scope=?",scope){it.getLong(1)}.single()>maximumOccurrences
        val scopeWrites=((writes[scope]?:0)+1)%100;writes[scope]=scopeWrites
        if(overCap||scopeWrites==0)retain(scope)
    }
    fun clear(scope:String)=store.transaction(durable=true){
        Scopes.validate(scope)
        execute("INSERT INTO history_epoch VALUES(?,1) ON CONFLICT(scope) DO UPDATE SET epoch=epoch+1",scope)
        execute("DELETE FROM history_span WHERE scope=?",scope);execute("DELETE FROM history_occurrence WHERE scope=?",scope);execute("DELETE FROM daily_total WHERE scope=?",scope);encore.clear(scope);execute("DELETE FROM encore_artist_genre WHERE scope=?",scope)
    }
    fun retain(scope:String,now:Long=System.currentTimeMillis())=store.transaction {
        Scopes.validate(scope);ensureDaily(scope);encore.ensure(scope)
        val prefs=privacy(scope);val cutoff=now-prefs.retentionDays*86_400_000L
        var removed=if(prefs.retentionDays>0)execute("DELETE FROM history_span WHERE scope=? AND started_ms<?",scope,cutoff)else 0
        execute("DELETE FROM history_occurrence WHERE scope=? AND NOT EXISTS(SELECT 1 FROM history_span s WHERE s.scope=history_occurrence.scope AND s.occurrence=history_occurrence.occurrence)",scope)
        if(removed>0)execute("""UPDATE history_occurrence SET (listened_ms,started_ms,updated_ms,completed)=
            (SELECT sum(s.listened_ms),min(s.started_ms),max(s.started_ms+s.listened_ms),max(s.completed) FROM history_span s WHERE s.scope=history_occurrence.scope AND s.occurrence=history_occurrence.occurrence)
            WHERE scope=? AND started_ms<?""",scope,cutoff)
        val overflow=query("SELECT occurrence FROM history_occurrence WHERE scope=? ORDER BY updated_ms DESC,occurrence LIMIT -1 OFFSET ?",scope,maximumOccurrences){it.getString(1)}
        overflow.forEach{id->removed+=execute("DELETE FROM history_span WHERE scope=? AND occurrence=?",scope,id);execute("DELETE FROM history_occurrence WHERE scope=? AND occurrence=?",scope,id)}
        if(removed>0&&!prefs.keepTotals){rebuildDaily(scope);encore.rebuild(scope)}
    }
    fun summary(scope:String,from:Long=0,until:Long=Long.MAX_VALUE):EncoreSummary=summary(listOf(scope),from,until)
    fun summary(scopes:List<String>,from:Long=0,until:Long=Long.MAX_VALUE):EncoreSummary {
        require(scopes.size in 1..3 && scopes.distinct().size==scopes.size);scopes.forEach(Scopes::validate)
        val placeholders=scopes.joinToString(","){"?"};val args=arrayOf<Any>(*scopes.toTypedArray(),from,until)
        val allTime=from==0L&&until==Long.MAX_VALUE
        val source=if(allTime)"history_occurrence"else"history_span"
        val totals=store.query("SELECT COALESCE(sum(listened_ms),0),count(DISTINCT scope||char(0)||track_id) FROM $source WHERE scope IN($placeholders) AND started_ms>=? AND started_ms<?",*args){it.getLong(1) to it.getLong(2)}.single()
        val plays=store.query("SELECT count(*) FROM(SELECT occurrence FROM $source WHERE scope IN($placeholders) AND started_ms>=? AND started_ms<? GROUP BY scope,occurrence HAVING sum(listened_ms)>=5000)",*args){it.getLong(1)}.single()
        val artists=store.query("SELECT count(DISTINCT t.artist) FROM $source h JOIN track t ON t.scope=h.scope AND t.id=h.track_id WHERE h.scope IN($placeholders) AND h.started_ms>=? AND h.started_ms<? AND t.artist!='' AND t.artist!='Unknown artist'",*args){it.getLong(1)}.single()
        val top=store.query("SELECT t.*,sum(h.listened_ms) AS heard FROM $source h JOIN track t ON t.scope=h.scope AND t.id=h.track_id WHERE h.scope IN($placeholders) AND h.started_ms>=? AND h.started_ms<? GROUP BY t.row_id ORDER BY heard DESC LIMIT 100",*args){SqliteLibraryRepository.track(it) to it.getLong("heard")}
        val lifetime=if(from==0L&&until==Long.MAX_VALUE&&scopes.all{store.privacy(it).keepTotals})store.query("SELECT COALESCE(sum(listened_ms),0) FROM daily_total WHERE scope IN($placeholders)",*scopes.toTypedArray()){it.getLong(1)}.single()else totals.first
        return EncoreSummary(lifetime,plays,totals.second,artists,top)
    }

}

/** Attribute actual heard milliseconds across local-day boundaries, including DST and date-line changes. */
internal fun historyDayParts(startedMs:Long,listenedMs:Long,zone:ZoneId):List<Pair<String,Long>>{
    if(listenedMs==0L)return emptyList()
    var at=startedMs;val end=Math.addExact(startedMs,listenedMs);val parts=mutableListOf<Pair<String,Long>>()
    while(at<end){
        val day=Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val until=minOf(end,day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        check(until>at){"Local-day boundary did not advance"}
        parts+=day.toString() to until-at;at=until
    }
    return parts
}

class SyncJournalRepository(private val store:SqliteLibraryRepository) {
    fun stage(scope:String,kind:String,target:String):String=store.transaction(durable=true){
        require(kind in setOf("local_import","remote_read","authorized_remote_write"));val id=UUID.randomUUID().toString()
        execute("INSERT INTO sync_operation VALUES(?,?,?,?,?,?)",Scopes.validate(scope),id,kind,target,"pending",System.currentTimeMillis());id
    }
    fun transition(scope:String,id:String,expected:String,next:String):Boolean=store.transaction(durable=true){
        require(next in setOf("pending","applied","uncertain","failed"));execute("UPDATE sync_operation SET state=? WHERE scope=? AND id=? AND state=?",next,Scopes.validate(scope),id,expected)==1
    }
    fun unresolved(scope:String):List<SyncOperation> = store.query("SELECT * FROM sync_operation WHERE scope=? AND state IN('pending','uncertain') ORDER BY created_ms",Scopes.validate(scope)){SyncOperation(it.getString("scope"),it.getString("id"),it.getString("kind"),it.getString("target"),it.getString("state"),it.getLong("created_ms"))}
}
