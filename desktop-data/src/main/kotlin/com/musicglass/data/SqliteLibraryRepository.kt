package com.musicglass.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.musicglass.data.db.LibraryDb
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/** One serialized connection owns real relational SQLite. Flows invalidate only after a committed transaction. */
enum class LibraryMigrationCheckpoint { BEFORE_DDL, AFTER_DDL, BEFORE_COMMIT, AFTER_COMMIT }

class SqliteLibraryRepository(
    val directory:File,
    private val migrationObserver:(LibraryMigrationCheckpoint)->Unit={}
):AutoCloseable {
    private val connection:Connection
    private val statements=linkedMapOf<String,PreparedStatement>()
    private val revision=MutableStateFlow(0L)
    val revisions:StateFlow<Long> = revision
    init {
        check(directory.mkdirs()||directory.isDirectory)
        val url="jdbc:sqlite:${File(directory,"library.sqlite").absolutePath}"
        JdbcSqliteDriver(url).use {driver->
            val version=driver.executeQuery(null,"PRAGMA user_version",{c->c.next();app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)?:0)},0).value
            check(version in 0..8){"Library was written by a newer app"}
            if(version==0L){LibraryDb(driver).transaction{LibraryDb.Schema.create(driver);driver.execute(null,"PRAGMA user_version=8",0)}}
            if(version in 1L..7L){LibraryDb(driver).transaction{
                val collisions=driver.executeQuery(null,"SELECT count(*) FROM(SELECT scope,occurrence FROM history_span GROUP BY scope,occurrence HAVING count(DISTINCT track_id)>1)",{c->c.next();app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)?:0)},0).value
                check(collisions==0L){"Legacy listening occurrence contains multiple recording identities; database preserved for review"}
                migrationObserver(LibraryMigrationCheckpoint.BEFORE_DDL)
                LibraryDb.Schema.migrate(driver,version,8)
                migrationObserver(LibraryMigrationCheckpoint.AFTER_DDL)
                driver.execute(null,"PRAGMA user_version=8",0)
                migrationObserver(LibraryMigrationCheckpoint.BEFORE_COMMIT)
            };migrationObserver(LibraryMigrationCheckpoint.AFTER_COMMIT)}
        }
        connection=DriverManager.getConnection(url)
        connection.createStatement().use{statement->
            statement.execute("PRAGMA journal_mode=WAL");statement.execute("PRAGMA synchronous=NORMAL");statement.execute("PRAGMA foreign_keys=ON");statement.execute("PRAGMA busy_timeout=5000")
            val complete=statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE name IN('track_fts','track_ai','track_ad','track_au')").use{it.next();it.getInt(1)==4}
            connection.autoCommit=false
            try{
            statement.execute("CREATE INDEX IF NOT EXISTS encore_repeat ON encore_daily(kind,day,key,scope,plays,title)")
            statement.execute("CREATE VIRTUAL TABLE IF NOT EXISTS track_fts USING fts5(title,artist,album,scope UNINDEXED,content='track',content_rowid='row_id',tokenize='unicode61 remove_diacritics 2')")
            statement.execute("CREATE TRIGGER IF NOT EXISTS track_ai AFTER INSERT ON track BEGIN INSERT INTO track_fts(rowid,title,artist,album,scope) VALUES(new.row_id,new.title,new.artist,new.album,new.scope); END")
            statement.execute("CREATE TRIGGER IF NOT EXISTS track_ad AFTER DELETE ON track BEGIN INSERT INTO track_fts(track_fts,rowid,title,artist,album,scope) VALUES('delete',old.row_id,old.title,old.artist,old.album,old.scope); END")
            statement.execute("CREATE TRIGGER IF NOT EXISTS track_au AFTER UPDATE OF title,artist,album ON track BEGIN INSERT INTO track_fts(track_fts,rowid,title,artist,album,scope) VALUES('delete',old.row_id,old.title,old.artist,old.album,old.scope); INSERT INTO track_fts(rowid,title,artist,album,scope) VALUES(new.row_id,new.title,new.artist,new.album,new.scope); END")
            if(!complete)statement.execute("INSERT INTO track_fts(track_fts) VALUES('rebuild')")
            connection.commit()
            }catch(error:Throwable){connection.rollback();connection.close();throw error}
            finally{if(!connection.isClosed)connection.autoCommit=true}
        }
    }
    @Synchronized fun <T> transaction(durable:Boolean=false,action:SqliteLibraryRepository.()->T):T {
        if(!connection.autoCommit)return action()
        if(durable)connection.createStatement().use{it.execute("PRAGMA synchronous=FULL")}
        connection.autoCommit=false
        try {val result=action();connection.commit();revision.value++;return result}
        catch(error:Throwable){connection.rollback();throw error}
        finally{connection.autoCommit=true;if(durable)connection.createStatement().use{it.execute("PRAGMA synchronous=NORMAL")}}
    }
    @Synchronized internal fun <T> savepoint(action:()->T):T {
        val point=connection.setSavepoint()
        try {val result=action();connection.releaseSavepoint(point);return result}
        catch(error:Throwable){connection.rollback(point);connection.releaseSavepoint(point);throw error}
    }
    private fun statement(sql:String,args:Array<out Any?>):PreparedStatement=statements.getOrPut(sql){connection.prepareStatement(sql)}.also{statement->
        statement.clearParameters();args.forEachIndexed{index,value->statement.setObject(index+1,value)}
    }
    @Synchronized internal fun execute(sql:String,vararg args:Any?):Int=statement(sql,args).executeUpdate()
    @Synchronized internal fun <T> query(sql:String,vararg args:Any?,map:(ResultSet)->T):List<T> = statement(sql,args).executeQuery().use{rows->buildList{while(rows.next())add(map(rows))}}
    /** Large history rebuilds retain day totals, not every listening span, in JVM memory. */
    @Synchronized internal fun forEachRow(sql:String,vararg args:Any?,read:(ResultSet)->Unit){
        statement(sql,args).executeQuery().use{rows->while(rows.next())read(rows)}
    }
    @Synchronized fun upsert(track:TrackRecord)=transaction {
        Scopes.validate(track.scope);require(track.id.length in 1..32768 && track.title.length<=16384 && track.durationMs>=0)
        require(track.sourceKind in setOf("local","youtube"))
        val artistId=track.artistId?:track.artist.takeIf{it.isNotBlank()&&it!="Unknown artist"}?.let{"local-artist:${digest(it)}"}
        val albumId=track.albumId?:track.album.takeIf{it.isNotBlank()}?.let{"local-album:${digest(track.artist+"\u0000"+it)}"}
        artistId?.let{execute("INSERT INTO artist(scope,id,name,artwork_url) VALUES(?,?,?,?) ON CONFLICT(scope,id) DO UPDATE SET name=excluded.name,artwork_url=COALESCE(excluded.artwork_url,artist.artwork_url)",track.scope,it,track.artist,track.artworkUrl)}
        albumId?.let{execute("INSERT INTO album(scope,id,title,artist,artist_id,artwork_url) VALUES(?,?,?,?,?,?) ON CONFLICT(scope,id) DO UPDATE SET title=excluded.title,artist=excluded.artist,artist_id=excluded.artist_id,artwork_url=COALESCE(excluded.artwork_url,album.artwork_url)",track.scope,it,track.album,track.artist,artistId,track.artworkUrl)}
        execute("""INSERT INTO track(scope,id,title,artist,album,duration_ms,source_kind,source_path,file_identity,artist_id,album_id,artwork_key,artwork_url,available,codec,added_ms,is_episode)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(scope,id) DO UPDATE SET title=excluded.title,artist=excluded.artist,album=excluded.album,duration_ms=excluded.duration_ms,
            source_path=excluded.source_path,file_identity=excluded.file_identity,artist_id=excluded.artist_id,album_id=excluded.album_id,
            artwork_key=COALESCE(excluded.artwork_key,track.artwork_key),artwork_url=COALESCE(excluded.artwork_url,track.artwork_url),available=excluded.available,codec=excluded.codec,is_episode=MAX(track.is_episode,excluded.is_episode)""",
            track.scope,track.id,track.title,track.artist,track.album,track.durationMs,track.sourceKind,track.sourcePath,track.fileIdentity,artistId,albumId,track.artworkKey,track.artworkUrl,track.available,track.codec,track.addedMs,track.isEpisode)
    }
    fun upsertAll(tracks:Sequence<TrackRecord>)=transaction{tracks.forEach{upsert(it)}}
    fun tracks(scope:String,limit:Int=500,offset:Long=0):List<TrackRecord>{Scopes.validate(scope);require(limit in 1..5000&&offset>=0);return query("SELECT * FROM track WHERE scope=? ORDER BY title COLLATE NOCASE,id LIMIT ? OFFSET ?",scope,limit,offset,map=::track)}
    fun get(scope:String,id:String):TrackRecord?=query("SELECT * FROM track WHERE scope=? AND id=?",Scopes.validate(scope),id,map=::track).singleOrNull()
    fun byIdentity(identity:String):TrackRecord?=query("SELECT * FROM track WHERE scope='local' AND file_identity=? LIMIT 1",identity,map=::track).singleOrNull()
    fun count(scope:String)=query("SELECT count(*) AS n FROM track WHERE scope=?",Scopes.validate(scope)){it.getLong("n")}.single()
    fun search(scope:String,text:String,limit:Int=100,offset:Long=0):List<TrackRecord>{
        Scopes.validate(scope);require(text.length<=500&&limit in 1..5000&&offset>=0)
        val words=Regex("[\\p{L}\\p{N}]+").findAll(text).take(12).map{it.value}.toList()
        val tokens=words.mapIndexed{index,word->"\"$word\""+if(index==words.lastIndex)"*"else""}.joinToString(" AND ")
        if(tokens.isEmpty())return tracks(scope,limit,offset)
        return query("SELECT t.* FROM track_fts CROSS JOIN track t ON t.row_id=track_fts.rowid WHERE track_fts MATCH ? AND t.scope=? LIMIT ? OFFSET ?",tokens,scope,limit,offset,map=::track)
    }
    fun observe(scope:String,text:String="",limit:Int=500):Flow<List<TrackRecord>> = revisions.mapLatest{withContext(Dispatchers.IO){if(text.isBlank())tracks(scope,limit)else search(scope,text,limit)}}.distinctUntilChanged()
    fun collection(scope:String,kind:String,id:String,limit:Int=500,offset:Long=0):List<TrackRecord>{
        require(kind in setOf("album","artist"));require(limit in 1..5000&&offset>=0)
        return query("SELECT * FROM track WHERE scope=? AND ${kind}_id=? ORDER BY added_ms,id LIMIT ? OFFSET ?",Scopes.validate(scope),id,limit,offset,map=::track)
    }
    fun collectionCount(scope:String,kind:String,id:String):Long {
        require(kind in setOf("album","artist"));return query("SELECT count(*) FROM track WHERE scope=? AND ${kind}_id=?",Scopes.validate(scope),id){it.getLong(1)}.single()
    }
    fun setArtwork(scope:String,id:String,key:String)=transaction{execute("UPDATE track SET artwork_key=? WHERE scope=? AND id=?",key,Scopes.validate(scope),id)}
    fun registerRoot(root:LocalRoot)=transaction{execute("INSERT INTO local_root(id,path,recursive,generation) VALUES(?,?,?,0) ON CONFLICT(id) DO UPDATE SET path=excluded.path,recursive=excluded.recursive",root.id,root.path,root.recursive)}
    fun roots():List<LocalRoot> =query("SELECT * FROM local_root ORDER BY path"){LocalRoot(it.getString("id"),it.getString("path"),it.getBoolean("recursive"))}
    fun scanIssue(root:String,path:String,reason:String)=transaction{
        execute("INSERT INTO scan_issue VALUES(?,?,?,?) ON CONFLICT(root_id,path) DO UPDATE SET reason=excluded.reason,observed_ms=excluded.observed_ms",root,path.take(32768),reason.take(300),System.currentTimeMillis())
        execute("DELETE FROM scan_issue WHERE root_id=? AND rowid IN(SELECT rowid FROM scan_issue WHERE root_id=? ORDER BY observed_ms DESC LIMIT -1 OFFSET 5000)",root,root)
    }
    fun scanIssues():List<Pair<String,String>> =query("SELECT path,reason FROM scan_issue ORDER BY observed_ms DESC LIMIT 100"){it.getString(1) to it.getString(2)}
    fun nextScan(root:String):Long=transaction{execute("DELETE FROM scan_issue WHERE root_id=?",root);execute("UPDATE local_root SET generation=generation+1 WHERE id=?",root);query("SELECT generation FROM local_root WHERE id=?",root){it.getLong(1)}.single()}
    fun location(path:String):Triple<String,Long,Long>?=query("SELECT track_id,modified_ms,size_bytes FROM file_location WHERE path=? AND available=1",path){Triple(it.getString(1),it.getLong(2),it.getLong(3))}.singleOrNull()
    fun recordLocation(path:String,root:String?,trackId:String,identity:String,modified:Long,bytes:Long,generation:Long)=transaction{
        val previous=query("SELECT track_id FROM file_location WHERE path=?",path){it.getString(1)}.singleOrNull()
        execute("INSERT INTO file_location(path,root_id,track_id,file_identity,modified_ms,size_bytes,generation,available) VALUES(?,?,?,?,?,?,?,1) ON CONFLICT(path) DO UPDATE SET root_id=COALESCE(excluded.root_id,file_location.root_id),track_id=excluded.track_id,file_identity=excluded.file_identity,modified_ms=excluded.modified_ms,size_bytes=excluded.size_bytes,generation=CASE WHEN excluded.root_id IS NULL THEN file_location.generation ELSE excluded.generation END,available=1",path,root,trackId,identity,modified,bytes,generation)
        if(previous!=null&&previous!=trackId)execute("UPDATE track SET available=EXISTS(SELECT 1 FROM file_location f WHERE f.track_id=track.id AND f.available=1) WHERE scope='local' AND id=?",previous)
    }
    fun markRootUnavailable(root:String)=transaction{
        execute("UPDATE file_location SET available=0 WHERE root_id=?",root)
        execute("UPDATE track SET available=EXISTS(SELECT 1 FROM file_location f WHERE f.track_id=track.id AND f.available=1) WHERE scope='local' AND id IN(SELECT track_id FROM file_location WHERE root_id=?)",root)
    }
    fun reconcile(root:String,generation:Long)=transaction {
        execute("UPDATE file_location SET available=0 WHERE root_id=? AND generation<?",root,generation)
        execute("UPDATE track SET available=EXISTS(SELECT 1 FROM file_location f WHERE f.track_id=track.id AND f.available=1),source_path=COALESCE((SELECT path FROM file_location f WHERE f.track_id=track.id AND f.available=1 ORDER BY path LIMIT 1),source_path) WHERE scope='local' AND id IN(SELECT track_id FROM file_location WHERE root_id=?)",root)
    }
    fun queryHistoryEpoch(scope:String):Long=query("SELECT epoch FROM history_epoch WHERE scope=?",Scopes.validate(scope)){it.getLong(1)}.singleOrNull()?:0L
    fun privacy(scope:String):PrivacyPreferences=query("SELECT * FROM privacy_preference WHERE scope=?",Scopes.validate(scope)){PrivacyPreferences(it.getBoolean("save_history"),it.getInt("retention_days"),it.getBoolean("keep_totals"),it.getBoolean("genre_lookup"))}.singleOrNull()?:PrivacyPreferences()
    fun setPrivacy(scope:String,value:PrivacyPreferences)=transaction{
        require(value.retentionDays in setOf(0,30,90,365))
        val prior=privacy(scope)
        if(prior.saveHistory!=value.saveHistory)execute("INSERT INTO history_epoch VALUES(?,1) ON CONFLICT(scope) DO UPDATE SET epoch=epoch+1",scope)
        if(prior.keepTotals!=value.keepTotals){execute("DELETE FROM history_rollup_version WHERE scope=?",scope);execute("DELETE FROM encore_rollup_version WHERE scope=?",scope)}
        execute("INSERT OR REPLACE INTO privacy_preference VALUES(?,?,?,?,?)",Scopes.validate(scope),value.saveHistory,value.retentionDays,value.keepTotals,value.genreLookup)
    }
    fun integrity():String=query("PRAGMA integrity_check"){it.getString(1)}.single()
    fun backup(destination:File){require(!destination.exists());execute("VACUUM INTO ?",destination.absolutePath)}
    @Synchronized override fun close(){statements.values.forEach{it.close()};statements.clear();connection.close()}
    companion object {
        fun digest(text:String):String {
            val bytes=MessageDigest.getInstance("SHA-256").digest(text.toByteArray());val hex="0123456789abcdef"
            return buildString(bytes.size*2){for(value in bytes){val n=value.toInt() and 255;append(hex[n ushr 4]);append(hex[n and 15])}}
        }
        internal fun track(row:ResultSet)=TrackRecord(row.getString("scope"),row.getString("id"),row.getString("title"),row.getString("artist"),row.getString("album"),row.getLong("duration_ms"),row.getString("source_kind"),row.getString("source_path"),row.getString("file_identity"),row.getString("artist_id"),row.getString("album_id"),row.getString("artwork_key"),row.getString("artwork_url"),row.getBoolean("available"),row.getString("codec"),row.getLong("added_ms"),row.getBoolean("is_episode"))
    }
}
