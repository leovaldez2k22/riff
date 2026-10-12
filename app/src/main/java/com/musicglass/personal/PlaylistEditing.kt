package com.musicglass.personal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** HTTP 200 can still contain a failed edit. Never report an unacknowledged save. */
internal fun playlistEditAccepted(body:String):Boolean=runCatching {
    val root=Json.parseToJsonElement(body).jsonObject
    root["error"]==null && root["status"]?.jsonPrimitive?.contentOrNull=="STATUS_SUCCEEDED"
}.getOrDefault(false)

/** A provider visitor/session refresh may cancel its request, never the whole picker coroutine. */
internal suspend fun <T> sessionSafeMutation(block:suspend ()->T):Result<T> = supervisorScope {
    try {Result.success(async(Dispatchers.IO) {block()}.await())}
    catch(error:CancellationException) {ensureActive();Result.failure(IllegalStateException("YouTube Music refreshed its session. Retry the remaining selections."))}
    catch(error:MutationUnconfirmed) {Result.failure(error)}
    catch(error:Exception) {Result.failure(IllegalStateException(error.message?.takeIf {!it.contains("http",true)}?:"YouTube Music could not update this playlist"))}
}

/**
 * One result for each destination. [unconfirmed] are among [failures]: sent, with no answer that could be
 * read back. They stay selected like any other failure; the next attempt reads the playlist before sending.
 */
internal data class PlaylistBatchResult(val completed:Set<String>,val failures:Map<String,String>,val unconfirmed:Set<String> = emptySet()) {
    fun summary():String = "${completed.size} saved; ${failures.size} could not be updated. "+
        (if(unconfirmed.isNotEmpty()) "${unconfirmed.size} not confirmed by YouTube Music. " else "")+
        failures.values.first().trimEnd('.')+". Retry the remaining selections."
}
internal suspend fun addToSelectedPlaylists(ids:Set<String>,add:suspend (String)->Result<Unit>):PlaylistBatchResult {
    val completed=linkedSetOf<String>();val failures=linkedMapOf<String,String>();val unconfirmed=linkedSetOf<String>()
    ids.forEach {id->add(id).fold(onSuccess={completed+=id},onFailure={error->
        if(error is kotlinx.coroutines.CancellationException) throw error
        failures[id]=error.message?:"Could not update playlist"
        if(error is MutationUnconfirmed) unconfirmed+=id
    })}
    return PlaylistBatchResult(completed,failures,unconfirmed)
}
/**
 * Adds [track] to one playlist: read it whole, refuse a read-only or partly loaded one, leave a song that
 * is already there alone, and only then send the edit.
 */
internal suspend fun addTrackToPlaylist(reader:ProviderCatalogReader,mutations:ProviderMutations,id:String,name:String,track:ProviderTrack):Result<Unit> =
    reader.completeCollection(id).fold(onSuccess={page->
        when {
            !page.editable -> Result.failure(IllegalStateException("$name is read-only"))
            page.albumTracks.any {it.videoId==track.videoId} -> Result.success(Unit)
            // Past the part that is loaded the song may already be there, and adding it again would add it twice.
            page.truncation!=null -> Result.failure(IllegalStateException("$name is too large for Riff to check for this song"))
            else -> mutations.addPlaylistSong(id,track.videoId)
        }
    },onFailure={Result.failure(it)})
@Composable internal fun PlaylistPicker(track:ProviderTrack,onDismiss:()->Unit,onNotice:(String)->Unit) {
    val provider=YouTubeMusicProvider.shared(LocalContext.current)
    val scope=rememberCoroutineScope()
    var pages by remember {mutableStateOf<List<ProviderHomeCard>>(emptyList())}
    var selected by remember(provider.authRevision) {mutableStateOf(setOf<String>())}
    var loading by remember {mutableStateOf(true)};var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)};var progress by remember {mutableStateOf("")}
    var retry by remember {mutableIntStateOf(0)}
    LaunchedEffect(provider.authRevision,retry) {
        loading=true;error=null
        provider.editablePlaylists().fold(onSuccess={pages=it},onFailure={error=it.message});loading=false
    }
    AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={Text("Add to playlists")},
        text={Column(Modifier.testTag("playlist_picker")) {
            Text(track.title,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if(loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(busy) Text(progress)
            error?.let {Text(it,color=Pink);if(pages.isEmpty()) TextButton({retry++}) {Text("Retry")}}
            if(!loading && error==null && pages.isEmpty()) Text("Create a playlist in YouTube Music to add songs here.")
            LazyColumn(Modifier.heightIn(max=360.dp)) {items(pages,key={it.key()}) {card->
                val id=card.browseId?:return@items
                Row(Modifier.fillMaxWidth().clickable(enabled=!busy) {selected=if(id in selected) selected-id else selected+id;uiHaptic(HapticCue.Selection)}.padding(vertical=6.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(id in selected,onCheckedChange=if(busy) null else {value->selected=if(value) selected+id else selected-id;uiHaptic(HapticCue.Selection)})
                    Text(card.title,maxLines=2,modifier=Modifier.weight(1f),overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }}
        }},confirmButton={TextButton(enabled=selected.isNotEmpty() && !busy && !loading,onClick={
            busy=true;error=null;val revision=provider.authRevision;val requested=selected
            scope.launch {
                val result=addToSelectedPlaylists(requested) {id->
                    progress=pages.firstOrNull {it.browseId==id}?.title.orEmpty()
                    if(provider.authRevision!=revision) Result.failure(IllegalStateException("Account changed"))
                    else addTrackToPlaylist(provider,provider,id,progress,track)
                }
                selected=selected-result.completed;busy=false
                if(result.completed.isNotEmpty()) provider.refreshLibraryIndex()
                if(result.failures.isEmpty()) {uiHaptic(HapticCue.Confirm);onNotice("Song saved in ${result.completed.size} playlists");onDismiss()}
                else {uiHaptic(HapticCue.Reject);error=result.summary()}
            }
        }) {Text(if(busy) "Saving…" else "Add to ${selected.size} playlists")}},
        dismissButton={TextButton(enabled=!busy,onClick=onDismiss) {Text("Cancel")}})
}

/** Shared action used by player, search, album and playlist rows. */
@Composable internal fun AddToPlaylistAction(track:ProviderTrack,onDismiss:()->Unit,onSelect:(ProviderTrack)->Unit) {
    if(track.isMusic && !track.isLive) SongActionItem("Add to playlist") {onDismiss();uiHaptic();onSelect(track)}
}
