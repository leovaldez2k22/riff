package com.musicglass.personal

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

internal data class OfflineSong(val scope:String,val track:ProviderTrack,val state:String,val pinned:Boolean,
    val bytes:Long,val total:Long,val error:String,val durationMs:Long,val quality:String,val artworkState:String,val lyricsState:String,
    val lastPlayed:Long,val plays:Int,val listenedMs:Long,val priority:Int=0,val excluded:Boolean=false,val downloadedAt:Long=0)
/** Bundle files are permanent app-private storage. URLs, cookies and tokens are never persisted. */
internal class OfflineMusicStore private constructor(private val context:Context) {
    companion object {
        @Volatile private var instance:OfflineMusicStore?=null
        fun get(context:Context)=instance ?: synchronized(this) {instance ?: OfflineMusicStore(context.applicationContext).also {instance=it}}
        private val VIDEO_ID=Regex("[A-Za-z0-9_-]{11}")
    }
    private val db=object:SQLiteOpenHelper(context,"offline-music.db",null,3) {
        override fun onCreate(db:SQLiteDatabase) {db.execSQL("CREATE TABLE songs(scope TEXT NOT NULL,id TEXT NOT NULL,track TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'metadata',pinned INTEGER NOT NULL DEFAULT 0,bytes INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',duration INTEGER NOT NULL DEFAULT 0,quality TEXT NOT NULL DEFAULT '',artwork TEXT NOT NULL DEFAULT 'pending',lyrics TEXT NOT NULL DEFAULT 'pending',lastPlayed INTEGER NOT NULL DEFAULT 0,plays INTEGER NOT NULL DEFAULT 0,listened INTEGER NOT NULL DEFAULT 0,priority INTEGER NOT NULL DEFAULT 0,excluded INTEGER NOT NULL DEFAULT 0,downloaded_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,id))")}
        override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int) {
            if(old<2) db.execSQL("ALTER TABLE songs ADD COLUMN excluded INTEGER NOT NULL DEFAULT 0")
            if(old<3) db.execSQL("ALTER TABLE songs ADD COLUMN downloaded_at INTEGER NOT NULL DEFAULT 0")
        }
    }.apply {setWriteAheadLoggingEnabled(true)}
    private val prefs=context.getSharedPreferences("offline_settings",Context.MODE_PRIVATE)
    val revision=MutableStateFlow(0L)
    val root=File(context.filesDir,"offline-music-v1").apply {mkdirs()}
    val smartEnabled get()=prefs.getBoolean("smart_enabled",false)
    val allowMobileData get()=prefs.getBoolean("allow_mobile_data",false)
    val budgetBytes get()=prefs.getLong("budget_bytes",4L*1024*1024*1024)
    val currentScope get()=YouTubeMusicProvider.shared(context).storageScopeKey()
    /** Every scope that owns rows; used to recognise an account's existing data without reading it. */
    @Synchronized fun scopes():Set<String> = db.readableDatabase.rawQuery("SELECT DISTINCT scope FROM songs",null).use {c->buildSet {while(c.moveToNext()) add(c.getString(0))}}
    @Volatile var playbackBusy=false
    init {recover()}
    fun setSmart(enabled:Boolean) {prefs.edit().putBoolean("smart_enabled",enabled).apply();changed();if(!enabled) cancelAutomatic(currentScope);OfflineDownloadScheduler.schedule(context,immediate=enabled)}
    fun setAllowMobileData(enabled:Boolean) {prefs.edit().putBoolean("allow_mobile_data",enabled).apply();changed();OfflineDownloadScheduler.schedule(context,immediate=smartEnabled)}
    fun setBudget(bytes:Long) {prefs.edit().putLong("budget_bytes",bytes).apply();changed();OfflineDownloadScheduler.schedule(context)}
    private fun changed() {revision.update {it+1}}
    private fun scopeDirectory(scope:String)=File(root,MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)})
    fun directory(scope:String,id:String):File {
        require(id.matches(VIDEO_ID))
        return File(scopeDirectory(scope),id)
    }
    private fun encode(t:ProviderTrack)=JSONObject().put("title",t.title).put("artist",t.artist).put("art",t.artworkUrl).put("artistId",t.artistId).put("albumId",t.albumId).put("album",t.album).put("live",t.isLive).put("music",t.isMusic).toString()
    @Synchronized fun remember(track:ProviderTrack,scope:String=currentScope) {
        if(scope==UNVERIFIED_SCOPE_KEY) return // No stable account identity: nothing is saved or adopted.
        require(track.videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        db.writableDatabase.insertWithOnConflict("songs",null,ContentValues().apply {put("scope",scope);put("id",track.videoId);put("track",encode(track))},SQLiteDatabase.CONFLICT_IGNORE)
        db.writableDatabase.update("songs",ContentValues().apply {put("track",encode(track.copy(artworkUrl=validProviderArtworkUrl(track.artworkUrl)?:song(scope,track.videoId)?.track?.artworkUrl?:providerArtworkUrl(track.videoId))))},"scope=? AND id=?",arrayOf(scope,track.videoId));changed()
    }
    @Synchronized fun rows(scope:String=currentScope,id:String?=null):List<OfflineSong> = db.readableDatabase.query("songs",null,if(id==null) "scope=?" else "scope=? AND id=?",if(id==null) arrayOf(scope) else arrayOf(scope,id),null,null,"lastPlayed DESC").use {c->buildList {
        fun str(k:String)=c.getString(c.getColumnIndexOrThrow(k)) ?: ""
        fun num(k:String)=c.getLong(c.getColumnIndexOrThrow(k))
        while(c.moveToNext()) {val j=JSONObject(str("track"));val id=str("id")
            fun opt(k:String)=j.optString(k).takeUnless {it.isBlank() || it=="null"}
            add(OfflineSong(scope,ProviderTrack(id,j.optString("title"),j.optString("artist"),opt("art"),opt("artistId"),opt("albumId"),j.optString("album"),j.optBoolean("live"),j.optBoolean("music",true)),str("state"),num("pinned")!=0L,num("bytes"),num("total"),str("error"),num("duration"),str("quality"),str("artwork"),str("lyrics"),num("lastPlayed"),num("plays").toInt(),num("listened"),num("priority").toInt(),num("excluded")!=0L,num("downloaded_at")))
        }
    }}
    fun song(scope:String,id:String)=rows(scope,id).firstOrNull()
    @Synchronized fun update(scope:String,id:String,vararg fields:Pair<String,Any>) {
        val values=ContentValues();fields.forEach {(k,v)->when(v) {is String->values.put(k,v);is Long->values.put(k,v);is Int->values.put(k,v);else->error("Unsupported field")}}
        db.writableDatabase.update("songs",values,"scope=? AND id=?",arrayOf(scope,id));changed()
    }
    @Synchronized fun enqueue(track:ProviderTrack,pinned:Boolean=true,scope:String=currentScope) {
        if(scope==UNVERIFIED_SCOPE_KEY) return
        remember(track,scope);val old=song(scope,track.videoId)
        require(!track.isLive && track.isMusic) {"Only songs can be downloaded"}
        update(scope,track.videoId,"pinned" to if(pinned || old?.pinned==true) 1 else 0,"state" to "queued","excluded" to 0,"error" to "")
    }
    @Synchronized fun enqueueSongs(tracks:List<ProviderTrack>,scope:String=currentScope):Int {
        if(scope==UNVERIFIED_SCOPE_KEY) return 0
        val songs=tracks.filter {!it.isLive && it.isMusic && it.videoId.matches(VIDEO_ID)}.distinctBy {it.videoId}
        songs.forEach {track->
            val old=song(scope,track.videoId)
            if(old?.state=="complete" && audio(scope,track.videoId)!=null) update(scope,track.videoId,"pinned" to 1,"excluded" to 0)
            else if(old?.state in setOf("queued","downloading","assets")) update(scope,track.videoId,"pinned" to 1,"excluded" to 0)
            else enqueue(track,scope=scope)
        }
        return songs.size
    }
    @Synchronized fun beginDownload(scope:String,id:String):Boolean {
        if(song(scope,id)?.state !in setOf("queued","failed")) return false
        update(scope,id,"state" to "downloading","error" to "")
        return true
    }
    @Synchronized fun updateDownload(scope:String,id:String,vararg fields:Pair<String,Any>):Boolean {
        if(song(scope,id)?.state !in setOf("downloading","assets")) return false
        update(scope,id,*fields)
        return true
    }
    fun audio(scope:String,id:String):File? {
        val dir=directory(scope,id);return runCatching {File(dir,"audio.media").takeIf {it.isFile && it.length()>0 && File(dir,"ready").let {ready->ready.isFile && (ready.readText().trim()=="1" || ready.readText().trim().toLongOrNull()==it.length())}}}.getOrNull()
    }
    fun artwork(scope:String,id:String)=File(directory(scope,id),"artwork").takeIf {it.isFile && it.length()>0}

    // Downloaded-artwork presence by account and video, so the playback service's main thread never
    // touches the disk: it builds up to 2,000 queue items at once and each used to stat a file here.
    // Kept exact: artwork is written only by OfflineDownloadEngine (markArtwork) and deleted only by remove().
    private val artworkIndex=java.util.concurrent.ConcurrentHashMap<String,Boolean>()
    private val indexedScopes=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val indexingScopes=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val artworkIndexer=java.util.concurrent.Executors.newSingleThreadExecutor {Thread(it,"offline-artwork-index").apply {isDaemon=true}}
    private fun artworkKey(scope:String,id:String)="$scope/$id"
    /**
     * Downloaded artwork from memory only. Null when there is none, or while this account's folders
     * are first being indexed in the background (the provider artwork URL is used meanwhile).
     */
    fun indexedArtwork(scope:String,id:String):File? {
        if(!id.matches(VIDEO_ID)) return null
        if(scope !in indexedScopes) {
            if(indexingScopes.add(scope)) artworkIndexer.execute {indexArtwork(scope)}
            return null
        }
        return if(artworkIndex[artworkKey(scope,id)]==true) File(directory(scope,id),"artwork") else null
    }
    /** One pass over an account's download folders, off the main thread. */
    fun indexArtwork(scope:String) {
        scopeDirectory(scope).listFiles()?.forEach {dir->
            if(dir.isDirectory && dir.name.matches(VIDEO_ID)) artworkIndex.putIfAbsent(artworkKey(scope,dir.name),File(dir,"artwork").let {it.isFile && it.length()>0})
        }
        indexedScopes.add(scope)
    }
    fun markArtwork(scope:String,id:String,present:Boolean) {artworkIndex[artworkKey(scope,id)]=present}
    fun lyrics(scope:String,id:String):LyricsDocument?=runCatching {
        val f=File(directory(scope,id),"lyrics.json");if(!f.isFile || f.length()>1_000_000) return null
        decodeLyricsDocument(kotlinx.serialization.json.Json.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonObject)
    }.getOrNull()
    @Synchronized fun remove(scope:String,id:String,exclude:Boolean=true) {
        update(scope,id,"state" to "cancelled","pinned" to 0,"excluded" to if(exclude) 1 else 0)
        val dir=directory(scope,id);check(dir.canonicalPath.startsWith(root.canonicalPath+File.separator));check(dir.deleteRecursively()) {"Could not remove download files"}
        markArtwork(scope,id,false)
        update(scope,id,"state" to "metadata","downloaded_at" to 0L,"bytes" to 0L,"total" to 0L,"quality" to "","artwork" to "pending","lyrics" to "pending")
    }
    @Synchronized fun removeAll(scope:String=currentScope) {
        // Preserve listening metadata and exclude removed songs from automatic re-downloads.
        rows(scope).filter {it.state!="metadata" || it.pinned || it.bytes>0}.forEach {remove(scope,it.track.videoId)}
    }
    /** The songs [downloadCount] counts, as identifiers and names only. For the export. */
    fun downloadList(scope:String):List<ProviderTrack> =rows(scope).filter {it.state!="metadata" || it.pinned || it.bytes>0}.map {it.track}
    /**
     * Notes songs from an imported download list, by owner, as rows that hold a name and nothing else:
     * nothing is queued or fetched, and a song already listed is left exactly as it is. One
     * transaction for every owner. Returns how many rows were added.
     */
    @Synchronized fun importMetadata(additions:Map<String,List<ProviderTrack>>):Int {
        var added=0
        val database=db.writableDatabase;database.beginTransaction()
        try {
            for((scope,tracks) in additions) {
                if(scope==UNVERIFIED_SCOPE_KEY) continue
                for(track in tracks) {
                    if(!track.videoId.matches(VIDEO_ID)) continue
                    val safe=track.copy(artworkUrl=validProviderArtworkUrl(track.artworkUrl)?:providerArtworkUrl(track.videoId))
                    if(database.insertWithOnConflict("songs",null,ContentValues().apply {put("scope",scope);put("id",track.videoId);put("track",encode(safe))},SQLiteDatabase.CONFLICT_IGNORE)!=-1L) added++
                }
            }
            database.setTransactionSuccessful()
        } finally {database.endTransaction()}
        if(added>0) changed()
        return added
    }
    /** Songs that [removeAll] would remove: downloaded, downloading or waiting. */
    fun downloadCount(scope:String):Int=rows(scope).count {it.state!="metadata" || it.pinned || it.bytes>0}
    /** As [removeAll], but one song that cannot be removed does not stop the rest. Returns removed to still present. */
    @Synchronized fun removeAllCounted(scope:String):Pair<Int,Int> {
        var removed=0;var failed=0
        rows(scope).filter {it.state!="metadata" || it.pinned || it.bytes>0}.forEach {
            if(runCatching {remove(scope,it.track.videoId)}.isSuccess) removed++ else failed++
        }
        return removed to failed
    }
    /** Forgets when and how often this owner's songs were played. Downloads stay. */
    @Synchronized fun resetListening(scope:String):Boolean=runCatching {
        db.writableDatabase.execSQL("UPDATE songs SET lastPlayed=0,plays=0,listened=0 WHERE scope=?",arrayOf(scope));changed()
    }.isSuccess
    /**
     * Removes everything kept for [scope]: rows first, so a transfer in flight finds its row gone at its
     * next check and stops, then the owner's folder. Returns rows removed to files still on disk; calling
     * again retries the files.
     */
    @Synchronized fun deleteScope(scope:String):Pair<Int,Int> {
        val dir=scopeDirectory(scope)
        check(dir.canonicalFile.parentFile==root.canonicalFile) {"Unexpected download folder"}
        val removed=db.writableDatabase.delete("songs","scope=?",arrayOf(scope))
        artworkIndex.keys.removeAll {it.startsWith("$scope/")};indexedScopes.remove(scope);indexingScopes.remove(scope)
        val left=if(!dir.exists() || dir.deleteRecursively()) 0 else dir.walkTopDown().count {it.isFile}.coerceAtLeast(1)
        changed();return removed to left
    }
    /** A transfer found its song deleted: whatever it had written goes too. Does nothing while the song is still listed. */
    @Synchronized fun discardUnlisted(scope:String,id:String) {
        if(!id.matches(VIDEO_ID) || song(scope,id)!=null) return
        val dir=directory(scope,id)
        if(dir.canonicalPath.startsWith(root.canonicalPath+File.separator)) dir.deleteRecursively()
        scopeDirectory(scope).let {if(it.isDirectory && it.list().isNullOrEmpty()) it.delete()}
        artworkIndex.remove(artworkKey(scope,id))
    }
    /**
     * Folds a verified alias of the same account into [to]. Rows and bundle folders move; nothing is
     * copied and no validated audio is deleted. Safe to run again after an interruption. Returns the
     * rows left behind: a song both keys downloaded in full, or one the target is transferring now.
     */
    /** False while the signed-in account has no stable identity: nothing is queued or adopted. */
    val canSave:Boolean get()=currentScope!=UNVERIFIED_SCOPE_KEY
    @Synchronized fun mergeScope(from:String,to:String):Int {
        if(from==to || from==UNVERIFIED_SCOPE_KEY || to==UNVERIFIED_SCOPE_KEY) return 0
        var remaining=0
        for(row in rows(from)) {
            val id=row.track.videoId;val source=directory(from,id);val target=directory(to,id);val existing=song(to,id)
            val sourceReady=audio(from,id)!=null;val targetReady=audio(to,id)!=null
            if(existing?.state in setOf("downloading","assets") || (sourceReady && targetReady)) {remaining++;continue}
            // Files move before the row: an interruption leaves an unlisted folder that the next pass adopts.
            if(sourceReady || (!targetReady && existing==null)) {
                if(source.exists()) {
                    // The target folder holds no validated audio here: only partial transfer files.
                    if(target.exists() && !target.deleteRecursively()) {remaining++;continue}
                    target.parentFile?.mkdirs();if(!source.renameTo(target)) {remaining++;continue}
                }
            } else source.deleteRecursively() // No validated audio on the alias side: only partial transfer files.
            val kept=if(sourceReady || existing==null) row else existing
            val database=db.writableDatabase;database.beginTransaction()
            try {
                if(existing==null) database.update("songs",ContentValues().apply {put("scope",to)},"scope=? AND id=?",arrayOf(from,id))
                else {
                    // A validated bundle beats queued metadata, a manual pin wins, listening totals add up.
                    database.update("songs",ContentValues().apply {
                        put("state",kept.state);put("bytes",kept.bytes);put("total",kept.total);put("error",kept.error);put("duration",kept.durationMs)
                        put("quality",kept.quality);put("artwork",kept.artworkState);put("lyrics",kept.lyricsState);put("downloaded_at",kept.downloadedAt)
                        put("pinned",if(row.pinned || existing.pinned) 1 else 0);put("excluded",if(row.excluded && existing.excluded) 1 else 0)
                        put("plays",row.plays+existing.plays);put("listened",row.listenedMs+existing.listenedMs)
                        put("lastPlayed",maxOf(row.lastPlayed,existing.lastPlayed));put("priority",maxOf(row.priority,existing.priority))
                    },"scope=? AND id=?",arrayOf(to,id))
                    database.delete("songs","scope=? AND id=?",arrayOf(from,id))
                }
                database.setTransactionSuccessful()
            } finally {database.endTransaction()}
            artworkIndex.remove(artworkKey(from,id));if(to in indexedScopes) markArtwork(to,id,artwork(to,id)!=null)
        }
        changed();return remaining
    }
    @Synchronized fun cancelAutomatic(scope:String) {rows(scope).filter {!it.pinned && it.state in setOf("queued","downloading","assets")}.forEach {update(scope,it.track.videoId,"state" to "paused")}}
    @Synchronized fun recover() {
        db.writableDatabase.execSQL("UPDATE songs SET state='queued' WHERE state IN ('downloading','assets')")
        // Existing rc11 bundles keep their original file commit date during the schema upgrade.
        db.readableDatabase.query("songs",arrayOf("scope","id"),"downloaded_at=0",null,null,null,null).use {cursor->
            while(cursor.moveToNext()) {
                val scope=cursor.getString(0);val id=cursor.getString(1)
                audio(scope,id)?.let {file->db.writableDatabase.update("songs",ContentValues().apply {
                    put("downloaded_at",file.lastModified().coerceAtLeast(1L))
                },"scope=? AND id=?",arrayOf(scope,id))}
            }
        }
        changed()
    }
    @Synchronized fun listened(track:ProviderTrack,duration:Long,millis:Long,newPlay:Boolean) {
        if(track.isLive || !track.isMusic || !context.getSharedPreferences("music_collections",Context.MODE_PRIVATE).getBoolean("history_enabled",true)) return
        val scope=currentScope;remember(track,scope);db.writableDatabase.execSQL("UPDATE songs SET lastPlayed=?,duration=?,plays=plays+?,listened=listened+? WHERE scope=? AND id=?",arrayOf<Any>(System.currentTimeMillis(),duration,if(newPlay) 1 else 0,millis.coerceIn(0,30_000),scope,track.videoId));changed()
    }
    fun usedBytes(scope:String=currentScope)=rows(scope).sumOf {r->directory(scope,r.track.videoId).listFiles()?.sumOf {it.length()}?:0}
}
