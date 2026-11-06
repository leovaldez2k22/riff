package com.musicglass.data

import kotlinx.coroutines.flow.StateFlow

object Scopes {
    const val LOCAL="local"
    const val GUEST="guest"
    fun validate(scope:String):String=scope.also{require(it.matches(Regex("[a-z][a-z0-9_-]{0,96}"))){"Invalid opaque storage scope"}}
}
data class TrackRecord(
    val scope:String,val id:String,val title:String,val artist:String="",val album:String="",val durationMs:Long=0,
    val sourceKind:String="local",val sourcePath:String?=null,val fileIdentity:String?=null,
    val artistId:String?=null,val albumId:String?=null,val artworkKey:String?=null,val artworkUrl:String?=null,
    val available:Boolean=true,val codec:String?=null,val addedMs:Long=System.currentTimeMillis(),val isEpisode:Boolean=false
)
data class PlaylistRecord(val scope:String,val id:String,val title:String,val description:String,val updatedMs:Long,val remoteId:String?=null)
data class PlaylistEntry(val entryId:String,val ordinal:Long,val track:TrackRecord)
data class LocalRoot(val id:String,val path:String,val recursive:Boolean=true)
data class ScanProgress(val rootId:String,val discovered:Long,val imported:Long,val rejected:Long,val reconciliationRequired:Boolean=false,val complete:Boolean=false,val error:String?=null)
data class ImportAudit(val digest:String,val imported:Long,val quarantined:Long,val backupPath:String,val reportPath:String)
data class ListeningSpan(val scope:String,val id:String,val occurrence:String,val trackId:String,val startedMs:Long,val listenedMs:Long,val completed:Boolean=false)
data class EncoreSummary(val listenedMs:Long,val plays:Long,val distinctTracks:Long,val distinctArtists:Long,val topTracks:List<Pair<TrackRecord,Long>>)
data class PrivacyPreferences(val saveHistory:Boolean=true,val retentionDays:Int=90,val keepTotals:Boolean=false,val genreLookup:Boolean=false)
data class SyncOperation(val scope:String,val id:String,val kind:String,val target:String,val state:String,val createdMs:Long)
sealed interface PageState<out T> {
    data object Loading:PageState<Nothing>
    data object Empty:PageState<Nothing>
    data class Ready<T>(val items:List<T>,val cursor:String?=null,val cached:Boolean=false):PageState<T>
    data class Failed(val message:String,val retryable:Boolean):PageState<Nothing>
    data object NotFound:PageState<Nothing>
    data class Unsupported(val reason:String):PageState<Nothing>
}
