package com.musicglass.data

/** Persisted explicit user intent; opportunistic range-cache coverage is never a download. */
enum class DownloadState { Queued,Resolving,Transferring,Paused,Verifying,Complete,Failed,Removing }
data class DownloadJob(val scope:String,val trackId:String,val state:DownloadState,val bytes:Long=0,val total:Long=-1,val sha256:String?=null,val errorCode:String?=null,val updatedMs:Long=System.currentTimeMillis())
class DownloadRepository(private val store:SqliteLibraryRepository) {
    fun list():List<DownloadJob> =store.query("SELECT * FROM download_job ORDER BY updated_ms,scope,track_id"){row->DownloadJob(row.getString("scope"),row.getString("track_id"),DownloadState.valueOf(row.getString("state")),row.getLong("bytes"),row.getLong("total"),row.getString("sha256"),row.getString("error_code"),row.getLong("updated_ms"))}
    fun get(scope:String,id:String):DownloadJob?=store.query("SELECT * FROM download_job WHERE scope=? AND track_id=?",Scopes.validate(scope),id){row->DownloadJob(row.getString("scope"),row.getString("track_id"),DownloadState.valueOf(row.getString("state")),row.getLong("bytes"),row.getLong("total"),row.getString("sha256"),row.getString("error_code"),row.getLong("updated_ms"))}.singleOrNull()
    fun request(track:TrackRecord)=store.transaction(durable=true){
        require(track.scope==Scopes.GUEST&&track.sourceKind=="youtube"){"Only the verified public provider scope can download"}
        val old=this@DownloadRepository.get(track.scope,track.id)
        require(old!=null||list().size<1000){"Download queue limit reached"}
        upsert(track)
        if(old==null)execute("INSERT INTO download_job VALUES(?,?,?,0,-1,NULL,NULL,?)",track.scope,track.id,DownloadState.Queued.name,System.currentTimeMillis())
        else if(old.state in setOf(DownloadState.Paused,DownloadState.Failed))change(old.copy(state=DownloadState.Queued,errorCode=null),setOf(old.state))
    }
    /** The expected-state predicate stops late transfer completion from undoing Pause or Remove. */
    fun change(job:DownloadJob,expected:Set<DownloadState>,durable:Boolean=true):Boolean=store.transaction(durable){
        Scopes.validate(job.scope);require(expected.isNotEmpty());require(job.bytes>=0&&job.total>=-1)
        if(job.state==DownloadState.Complete)require(job.bytes>0&&job.bytes==job.total&&job.sha256!=null){"Complete requires a verified byte receipt"}
        require(job.sha256==null||job.sha256.matches(Regex("[a-f0-9]{64}")))
        require(job.errorCode==null||job.errorCode.matches(Regex("[A-Z_]{1,64}")))
        val old=this@DownloadRepository.get(job.scope,job.trackId)?:return@transaction false
        if(old.state !in expected)return@transaction false
        execute("UPDATE download_job SET state=?,bytes=?,total=?,sha256=?,error_code=?,updated_ms=? WHERE scope=? AND track_id=?",job.state.name,job.bytes,job.total,job.sha256,job.errorCode,System.currentTimeMillis(),job.scope,job.trackId)
        true
    }
    fun forget(scope:String,id:String)=store.transaction(durable=true){execute("DELETE FROM download_job WHERE scope=? AND track_id=? AND state='Removing'",Scopes.validate(scope),id)}
}
