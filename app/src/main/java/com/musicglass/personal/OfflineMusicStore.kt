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
    val lastPlayed:Long,val plays:Int,val listenedMs:Long,val priority:Int=0,val excluded:Boolean=false,val downloadedAt:Long=0,
    /** Raised whenever someone other than the transfer takes the row away from it: remove, pause, a restart of the app. */
    val generation:Long=0,
    /** Runs that ended in failure since the user last asked for this song. */
    val failures:Int=0) {
    /** Null for a state no build writes. Such a row is listed, never played from, and repaired at the next start. */
    val status:DownloadState? get()=DownloadState.decode(state)
}
/** What the start-up check changed. Counts only. */
internal data class ReconcileReport(val requeued:Int=0,val removalsFinished:Int=0,val unrecognised:Int=0,val filesSwept:Int=0)
/** Bundle files are permanent app-private storage. URLs, cookies and tokens are never persisted. */
internal class OfflineMusicStore private constructor(private val context:Context) {
    companion object {
        // Lint (StaticFieldLeak): get() passes the application context, which lives as long as the process (Q15 review).
        @Volatile private var instance:OfflineMusicStore?=null
        fun get(context:Context)=instance ?: synchronized(this) {instance ?: OfflineMusicStore(context.applicationContext).also {instance=it}}
        private val VIDEO_ID=Regex("[A-Za-z0-9_-]{11}")
        /** A row whose files are gone: what is left is the name and the listening totals. */
        private val CLEARED=arrayOf<Pair<String,Any>>("state" to DownloadState.Metadata.db,"downloaded_at" to 0L,"bytes" to 0L,"total" to 0L,
            "quality" to "","artwork" to "pending","lyrics" to "pending","error" to "","failures" to 0)
    }
    private val db=object:SQLiteOpenHelper(context,"offline-music.db",null,4) {
        override fun onCreate(db:SQLiteDatabase) {db.execSQL("CREATE TABLE songs(scope TEXT NOT NULL,id TEXT NOT NULL,track TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'metadata',pinned INTEGER NOT NULL DEFAULT 0,bytes INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',duration INTEGER NOT NULL DEFAULT 0,quality TEXT NOT NULL DEFAULT '',artwork TEXT NOT NULL DEFAULT 'pending',lyrics TEXT NOT NULL DEFAULT 'pending',lastPlayed INTEGER NOT NULL DEFAULT 0,plays INTEGER NOT NULL DEFAULT 0,listened INTEGER NOT NULL DEFAULT 0,priority INTEGER NOT NULL DEFAULT 0,excluded INTEGER NOT NULL DEFAULT 0,downloaded_at INTEGER NOT NULL DEFAULT 0,generation INTEGER NOT NULL DEFAULT 0,failures INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,id))")}
        override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int) {
            if(old<2) db.execSQL("ALTER TABLE songs ADD COLUMN excluded INTEGER NOT NULL DEFAULT 0")
            if(old<3) db.execSQL("ALTER TABLE songs ADD COLUMN downloaded_at INTEGER NOT NULL DEFAULT 0")
            // Q09. Rows are kept as they are; both columns start at zero.
            if(old<4) {
                db.execSQL("ALTER TABLE songs ADD COLUMN generation INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE songs ADD COLUMN failures INTEGER NOT NULL DEFAULT 0")
            }
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
    /** What the check at start-up found; for the event log and tests. */
    val startupReport:ReconcileReport
    init {
        startupReport=reconcile()
        if(startupReport!=ReconcileReport()) PlaybackEventLog(context).append("download_reconcile requeued=${startupReport.requeued} removals=${startupReport.removalsFinished} unrecognised=${startupReport.unrecognised} swept=${startupReport.filesSwept}")
    }
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
    /** Songs asked for by hand that wait for a run in [scope]; not paused or failed ones. */
    @Synchronized fun manualWaiting(scope:String=currentScope):Int=rows(scope).count {it.pinned && it.status==DownloadState.Queued}
    @Synchronized fun rows(scope:String=currentScope,id:String?=null):List<OfflineSong> = db.readableDatabase.query("songs",null,if(id==null) "scope=?" else "scope=? AND id=?",if(id==null) arrayOf(scope) else arrayOf(scope,id),null,null,"lastPlayed DESC").use {c->buildList {
        fun str(k:String)=c.getString(c.getColumnIndexOrThrow(k)) ?: ""
        fun num(k:String)=c.getLong(c.getColumnIndexOrThrow(k))
        while(c.moveToNext()) {val j=JSONObject(str("track"));val id=str("id")
            fun opt(k:String)=j.optString(k).takeUnless {it.isBlank() || it=="null"}
            add(OfflineSong(scope,ProviderTrack(id,j.optString("title"),j.optString("artist"),opt("art"),opt("artistId"),opt("albumId"),j.optString("album"),j.optBoolean("live"),j.optBoolean("music",true)),str("state"),num("pinned")!=0L,num("bytes"),num("total"),str("error"),num("duration"),str("quality"),str("artwork"),str("lyrics"),num("lastPlayed"),num("plays").toInt(),num("listened"),num("priority").toInt(),num("excluded")!=0L,num("downloaded_at"),num("generation"),num("failures").toInt()))
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
        require(!track.isLive && track.isMusic) {string(R.string.only_songs_can_be_downloaded)}
        val pin=if(pinned || old?.pinned==true) 1 else 0
        // Already waiting or with a transfer: it keeps its place. Asking again must not restart it.
        if(old?.status?.pending==true) update(scope,track.videoId,"pinned" to pin,"excluded" to 0)
        else update(scope,track.videoId,"pinned" to pin,"state" to DownloadState.Queued.db,"excluded" to 0,"error" to "","failures" to 0)
    }
    @Synchronized fun enqueueSongs(tracks:List<ProviderTrack>,scope:String=currentScope):Int {
        if(scope==UNVERIFIED_SCOPE_KEY) return 0
        val songs=tracks.filter {!it.isLive && it.isMusic && it.videoId.matches(VIDEO_ID)}.distinctBy {it.videoId}
        songs.forEach {track->
            val old=song(scope,track.videoId)
            // A finished download is kept as it is, a waiting or running one keeps its place; both become pinned.
            if(old?.status==DownloadState.Complete && audio(scope,track.videoId)!=null) update(scope,track.videoId,"pinned" to 1,"excluded" to 0)
            else enqueue(track,scope=scope)
        }
        return songs.size
    }
    /**
     * A transfer takes the row. Returns the row's generation, which the transfer shows with every later
     * write, or null when the row is not waiting (removed, paused, finished, or already taken).
     */
    @Synchronized fun beginDownload(scope:String,id:String):Long? {
        val row=song(scope,id)?:return null
        if(row.status!=DownloadState.Queued && row.status!=DownloadState.Failed) return null
        update(scope,id,"state" to DownloadState.Resolving.db,"error" to "")
        return row.generation
    }
    /** True while the transfer that was given [generation] still has the row. */
    @Synchronized fun holds(scope:String,id:String,generation:Long):Boolean =
        song(scope,id)?.let {it.generation==generation && it.status?.inFlight==true}==true
    /** A write by the transfer that holds the row. Refused, and nothing written, once the row was taken from it. */
    @Synchronized fun updateDownload(scope:String,id:String,generation:Long,vararg fields:Pair<String,Any>):Boolean {
        if(!holds(scope,id,generation)) return false
        update(scope,id,*fields)
        return true
    }
    /** The transfer gives the row up as failed. [failures] counts runs, so a song that cannot be had is not tried for ever. */
    @Synchronized fun failDownload(scope:String,id:String,generation:Long,failure:DownloadFailure):Boolean {
        val row=song(scope,id)?:return false
        return updateDownload(scope,id,generation,"state" to DownloadState.Failed.db,"error" to failure.message,"failures" to row.failures+1)
    }
    /** Runs [commit] only while the transfer still has the row, and under this store's lock: a removal cannot fall between the two. */
    @Synchronized fun <T> commitAudio(scope:String,id:String,generation:Long,commit:()->T):T? = if(holds(scope,id,generation)) commit() else null
    private fun supersede(scope:String,id:String) {
        db.writableDatabase.execSQL("UPDATE songs SET generation=generation+1 WHERE scope=? AND id=?",arrayOf(scope,id))
    }
    /** Pauses one song. Only that song's transfer stops; the rest of the queue goes on. */
    @Synchronized fun pause(scope:String,id:String):Boolean {
        if(song(scope,id)?.status?.pending!=true) return false
        supersede(scope,id);update(scope,id,"state" to DownloadState.Paused.db)
        return true
    }
    /** Pauses every download the user asked for by hand in [scope]. Smart Downloads are not touched. */
    @Synchronized fun pauseManual(scope:String=currentScope):Int =
        rows(scope).count {it.pinned && pause(scope,it.track.videoId)}
    /** The audio if its marker vouches for it. A row without such files is never playable, whatever its state says. */
    fun audio(scope:String,id:String):File?=BundleValidator.audio(directory(scope,id))
    /**
     * A finished download turned out to be unusable. Its marker is withdrawn so it is not offered again, and
     * the row says so and can be downloaded again. Does nothing while a transfer has the row.
     */
    @Synchronized fun markDamaged(scope:String,id:String):Boolean {
        if(!id.matches(VIDEO_ID)) return false
        val row=song(scope,id)?:return false
        if(row.status?.inFlight==true) return false
        BundleValidator.revoke(directory(scope,id))
        if(row.status==DownloadState.Metadata || row.status==DownloadState.Cancelled) return true
        supersede(scope,id)
        update(scope,id,"state" to DownloadState.Failed.db,"error" to DownloadFailure.Integrity.message,"bytes" to 0L)
        return true
    }
    /** Finds finished downloads whose files are gone or no longer match their marker. Returns how many. Off the main thread. */
    @Synchronized fun verifyComplete(scope:String):Int =
        rows(scope).count {it.status==DownloadState.Complete && audio(scope,it.track.videoId)==null && markDamaged(scope,it.track.videoId)}
    fun artwork(scope:String,id:String)=File(directory(scope,id),"artwork").takeIf {it.isFile && it.length()>0}
    /** The provider's loudness value saved with the download, so it normalizes without asking the provider. Null for older bundles. */
    fun loudness(scope:String,id:String):Double?=BundleLoudness.read(directory(scope,id))

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
        // The row first: a transfer in flight finds it taken at its next check and stops. If the files cannot
        // all be removed the row stays "cancelled" and the removal is finished at the next start.
        update(scope,id,"state" to DownloadState.Cancelled.db,"pinned" to 0,"excluded" to if(exclude) 1 else 0);supersede(scope,id)
        val dir=directory(scope,id);check(dir.canonicalPath.startsWith(root.canonicalPath+File.separator));check(dir.deleteRecursively()) {"Could not remove download files"}
        markArtwork(scope,id,false)
        update(scope,id,*CLEARED)
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
            if(existing?.status?.inFlight==true || (sourceReady && targetReady)) {remaining++;continue}
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
    /** Smart Downloads was switched off: its waiting and running songs pause. Songs the user asked for by hand are not touched. */
    @Synchronized fun cancelAutomatic(scope:String) {rows(scope).filter {!it.pinned}.forEach {pause(scope,it.track.videoId)}}
    /**
     * Run once when the store opens, before any transfer. Whatever point the app stopped at, every row ends
     * in a state that is true of its files:
     * - a transfer was in flight: its unfinished files go and the song waits again. Audio that was already
     *   committed with its marker is kept, and the next run only fetches what is missing;
     * - a removal had begun: it is finished;
     * - a state no build writes: complete if the files are sound, otherwise failed with a way to download again.
     * No row is deleted. Finished downloads are not opened here; see [verifyComplete].
     */
    @Synchronized fun reconcile():ReconcileReport {
        var requeued=0;var removals=0;var unrecognised=0;var swept=0
        val open=db.readableDatabase.query("songs",arrayOf("scope","id","state"),"state NOT IN ('metadata','complete')",null,null,null,null).use {c->
            buildList {while(c.moveToNext()) add(Triple(c.getString(0),c.getString(1),c.getString(2)))}}
        for((scope,id,state) in open) {
            if(!id.matches(VIDEO_ID)) continue
            val dir=directory(scope,id)
            when(val status=DownloadState.decode(state)) {
                DownloadState.Cancelled -> if(dir.canonicalPath.startsWith(root.canonicalPath+File.separator) && (!dir.exists() || dir.deleteRecursively())) {
                    update(scope,id,*CLEARED);removals++
                }
                DownloadState.Queued,DownloadState.Paused,DownloadState.Failed -> swept+=BundleValidator.sweep(dir)
                null -> {
                    swept+=BundleValidator.sweep(dir);unrecognised++
                    if(BundleValidator.audio(dir)!=null) update(scope,id,"state" to DownloadState.Complete.db,"error" to "")
                    else update(scope,id,"state" to DownloadState.Failed.db,"error" to DownloadFailure.Unrecognised.message)
                }
                else -> if(status.inFlight) {
                    swept+=BundleValidator.sweep(dir);supersede(scope,id);requeued++
                    update(scope,id,"state" to DownloadState.Queued.db)
                }
            }
        }
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
        return ReconcileReport(requeued,removals,unrecognised,swept)
    }
    @Synchronized fun listened(track:ProviderTrack,duration:Long,millis:Long,newPlay:Boolean) {
        if(track.isLive || !track.isMusic || !context.getSharedPreferences("music_collections",Context.MODE_PRIVATE).getBoolean("history_enabled",true)) return
        val scope=currentScope;remember(track,scope);db.writableDatabase.execSQL("UPDATE songs SET lastPlayed=?,duration=?,plays=plays+?,listened=listened+? WHERE scope=? AND id=?",arrayOf<Any>(System.currentTimeMillis(),duration,if(newPlay) 1 else 0,millis.coerceIn(0,30_000),scope,track.videoId));changed()
    }
    fun usedBytes(scope:String=currentScope)=rows(scope).sumOf {r->directory(scope,r.track.videoId).listFiles()?.sumOf {it.length()}?:0}
}
