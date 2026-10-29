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
    catch(error:CancellationException) {ensureActive();Result.failure(IllegalStateException(string(R.string.youtube_music_refreshed_its_session_retry_th)))}
    catch(error:MutationUnconfirmed) {Result.failure(error)}
    catch(error:Exception) {Result.failure(IllegalStateException(error.message?.takeIf {!it.contains("http",true)}?:string(R.string.youtube_music_could_not_update_this_playlist)))}
}

/**
 * One result for each destination. [unconfirmed] are among [failures]: sent, with no answer that could be
 * read back. They stay selected like any other failure; the next attempt reads the playlist before sending.
 */
internal data class PlaylistBatchResult(val completed:Set<String>,val failures:Map<String,String>,val unconfirmed:Set<String> = emptySet()) {
    fun summary():String = listOfNotNull(string(R.string.x_saved_x_could_not_be_updated, completed.size, failures.size),
        if(unconfirmed.isNotEmpty()) quantity(R.plurals.n_not_confirmed_by_youtube_music, unconfirmed.size) else null,
        string(R.string.x_retry_the_remaining_selections, failures.values.first().trimEnd('.'))).joinToString(" ")
}
internal suspend fun addToSelectedPlaylists(ids:Set<String>,add:suspend (String)->Result<Unit>):PlaylistBatchResult {
    val completed=linkedSetOf<String>();val failures=linkedMapOf<String,String>();val unconfirmed=linkedSetOf<String>()
    ids.forEach {id->add(id).fold(onSuccess={completed+=id},onFailure={error->
        if(error is kotlinx.coroutines.CancellationException) throw error
        failures[id]=error.message?:string(R.string.could_not_update_playlist)
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
    var naming by remember {mutableStateOf(false)};var newName by remember {mutableStateOf("")}
    fun create() {
        val name=newName.trim();if(name.isEmpty() || busy) return
        busy=true;error=null;progress=name;val revision=provider.authRevision
        scope.launch {
            val result=if(provider.authRevision!=revision) Result.failure(IllegalStateException(string(R.string.account_changed)))
                else newPlaylistWith(provider,name,track)
            busy=false;provider.refreshLibraryIndex()
            result.fold(onSuccess={uiHaptic(HapticCue.Confirm);onNotice(string(R.string.added_to_new_playlist_x,name));onDismiss()},
                onFailure={uiHaptic(HapticCue.Reject);error=it.message;naming=false;newName="";retry++})
        }
    }
    LaunchedEffect(provider.authRevision,retry) {
        loading=true;error=null
        provider.editablePlaylists().fold(onSuccess={pages=it},onFailure={error=it.message});loading=false
    }
    AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={Text(string(R.string.add_to_playlists))},
        text={Column(Modifier.testTag("playlist_picker")) {
            Text(track.title,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if(loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(busy) Text(progress)
            error?.let {Text(it,color=Pink);if(pages.isEmpty()) TextButton({retry++}) {Text(string(R.string.retry))}}
            if(!loading && error==null && pages.isEmpty() && !naming) Text(string(R.string.no_playlists_yet_make_one))
            LazyColumn(Modifier.heightIn(max=360.dp)) {items(pages,key={it.key()}) {card->
                val id=card.browseId?:return@items
                Row(Modifier.fillMaxWidth().clickable(enabled=!busy) {selected=if(id in selected) selected-id else selected+id;uiHaptic(HapticCue.Selection)}.padding(vertical=6.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(id in selected,onCheckedChange=if(busy) null else {value->selected=if(value) selected+id else selected-id;uiHaptic(HapticCue.Selection)})
                    Text(card.title,maxLines=2,modifier=Modifier.weight(1f),overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }}
            if(!loading && provider.signedIn) {
                if(naming) Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(newName,{newName=it.take(PLAYLIST_TITLE_LIMIT)},enabled=!busy,singleLine=true,
                        label={Text(string(R.string.playlist_name))},modifier=Modifier.weight(1f).testTag("new_playlist_name"))
                    TextButton(enabled=newName.isNotBlank() && !busy,onClick={create()},modifier=Modifier.testTag("create_playlist")) {Text(string(R.string.create))}
                } else TextButton(enabled=!busy,onClick={naming=true;uiHaptic(HapticCue.Selection)},modifier=Modifier.testTag("new_playlist")) {Text(string(R.string.new_playlist))}
                if(naming) Text(string(R.string.new_playlists_are_private),color=Muted)
            }
        }},confirmButton={TextButton(enabled=selected.isNotEmpty() && !busy && !loading,onClick={
            busy=true;error=null;val revision=provider.authRevision;val requested=selected
            scope.launch {
                val result=addToSelectedPlaylists(requested) {id->
                    progress=pages.firstOrNull {it.browseId==id}?.title.orEmpty()
                    if(provider.authRevision!=revision) Result.failure(IllegalStateException(string(R.string.account_changed)))
                    else addTrackToPlaylist(provider,provider,id,progress,track)
                }
                selected=selected-result.completed;busy=false
                if(result.completed.isNotEmpty()) provider.refreshLibraryIndex()
                if(result.failures.isEmpty()) {uiHaptic(HapticCue.Confirm);onNotice(string(R.string.song_saved_in_x_playlists, result.completed.size));onDismiss()}
                else {uiHaptic(HapticCue.Reject);error=result.summary()}
            }
        }) {Text(if(busy) string(R.string.saving) else string(R.string.add_to_x_playlists, selected.size))}},
        dismissButton={TextButton(enabled=!busy,onClick=onDismiss) {Text(string(R.string.cancel))}})
}

/**
 * Q18-081: a new playlist with [track] in it. A playlist made a moment ago cannot hold the song yet, so it is
 * added without reading first. When the add fails the playlist is still made; the message says so.
 */
internal suspend fun newPlaylistWith(mutations:ProviderMutations,name:String,track:ProviderTrack):Result<String> {
    val id=mutations.createPlaylist(name).getOrElse {return Result.failure(it)}
    return mutations.addPlaylistSong(id,track.videoId).fold(onSuccess={Result.success(id)},
        onFailure={Result.failure(IllegalStateException(string(R.string.x_was_made_the_song_was_not_added,name.trim(),it.message.orEmpty().trimEnd('.'))))})
}

/**
 * Feature 12: a new private playlist holding [tracks] in their order, added one at a time ([added] hears each count).
 * Once made, the playlist stays even when some songs could not be added; the result says how many were.
 */
internal suspend fun newPlaylistWithAll(mutations:ProviderMutations,name:String,tracks:List<ProviderTrack>,added:(Int)->Unit={}):Result<Int> {
    val id=mutations.createPlaylist(name).getOrElse {return Result.failure(it)}
    var count=0
    for(track in tracks) if(mutations.addPlaylistSong(id,track.videoId).isSuccess) added(++count)
    return Result.success(count)
}

/** Feature 12, "Save to YouTube Music": Encore's top songs as a new private playlist, named as the owner likes. */
@Composable internal fun SaveTracksPlaylistDialog(provider:YouTubeMusicProvider,suggested:String,tracks:List<ProviderTrack>,onDismiss:()->Unit,onSaved:(String,Int)->Unit) {
    val scope=rememberCoroutineScope()
    var name by remember {mutableStateOf(suggested.take(PLAYLIST_TITLE_LIMIT))};var busy by remember {mutableStateOf(false)}
    var done by remember {mutableStateOf(0)};var error by remember {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={Text(string(R.string.encore_save_playlist))},
        text={Column(Modifier.testTag("encore_save_dialog")) {
            OutlinedTextField(name,{name=it.take(PLAYLIST_TITLE_LIMIT)},enabled=!busy,singleLine=true,
                label={Text(string(R.string.playlist_name))},modifier=Modifier.fillMaxWidth().testTag("encore_save_name"))
            if(busy) LinearProgressIndicator({done.toFloat()/tracks.size.coerceAtLeast(1)},Modifier.fillMaxWidth().padding(top=RiffSpace.item))
            Text(string(R.string.encore_save_playlist_detail,tracks.size),color=Muted,modifier=Modifier.padding(top=RiffSpace.item))
            error?.let {Text(it,color=Pink,modifier=Modifier.padding(top=RiffSpace.item))}
        }},
        confirmButton={TextButton(enabled=name.isNotBlank() && !busy && tracks.isNotEmpty(),modifier=Modifier.testTag("encore_save_confirm"),onClick={
            busy=true;error=null;done=0;val title=name.trim();val revision=provider.authRevision
            scope.launch {
                newPlaylistWithAll(provider,title,tracks) {done=it}.fold(onSuccess={count->
                    provider.refreshLibraryIndex()
                    if(provider.authRevision==revision) {uiHaptic(HapticCue.Confirm);onSaved(title,count)}
                },onFailure={uiHaptic(HapticCue.Reject);error=it.message;busy=false})
            }
        }) {Text(if(busy) string(R.string.saving) else string(R.string.save))}},
        dismissButton={TextButton(enabled=!busy,onClick=onDismiss) {Text(string(R.string.cancel))}})
}

/** Q18-081: Library's New playlist. An empty private playlist on the account; [onCreated] hears its name. */
@Composable internal fun NewPlaylistDialog(provider:YouTubeMusicProvider,onDismiss:()->Unit,onCreated:(String)->Unit) {
    val scope=rememberCoroutineScope()
    var name by remember {mutableStateOf("")};var busy by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={Text(string(R.string.new_playlist))},
        text={Column(Modifier.testTag("new_playlist_dialog")) {
            OutlinedTextField(name,{name=it.take(PLAYLIST_TITLE_LIMIT)},enabled=!busy,singleLine=true,
                label={Text(string(R.string.playlist_name))},modifier=Modifier.fillMaxWidth().testTag("new_playlist_name"))
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=RiffSpace.item))
            Text(string(R.string.new_playlists_are_private),color=Muted,modifier=Modifier.padding(top=RiffSpace.item))
            error?.let {Text(it,color=Pink,modifier=Modifier.padding(top=RiffSpace.item))}
        }},
        confirmButton={TextButton(enabled=name.isNotBlank() && !busy,modifier=Modifier.testTag("create_playlist"),onClick={
            busy=true;error=null;val title=name.trim()
            scope.launch {
                provider.createPlaylist(title).fold(onSuccess={uiHaptic(HapticCue.Confirm);provider.refreshLibraryIndex();onCreated(title)},
                    onFailure={uiHaptic(HapticCue.Reject);error=it.message;busy=false})
            }
        }) {Text(string(R.string.create))}},
        dismissButton={TextButton(enabled=!busy,onClick=onDismiss) {Text(string(R.string.cancel))}})
}

/** Q18-104: a new name for a playlist the account can edit. */
@Composable internal fun RenamePlaylistDialog(provider:YouTubeMusicProvider,id:String,current:String,onDismiss:()->Unit,onRenamed:(String)->Unit) {
    val scope=rememberCoroutineScope()
    var name by remember {mutableStateOf(current)};var busy by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={Text(string(R.string.rename_playlist))},
        text={Column(Modifier.testTag("rename_playlist_dialog")) {
            OutlinedTextField(name,{name=it.take(PLAYLIST_TITLE_LIMIT)},enabled=!busy,singleLine=true,
                label={Text(string(R.string.playlist_name))},modifier=Modifier.fillMaxWidth().testTag("rename_playlist_name"))
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=RiffSpace.item))
            error?.let {Text(it,color=Pink,modifier=Modifier.padding(top=RiffSpace.item))}
        }},
        confirmButton={TextButton(enabled=name.isNotBlank() && name.trim()!=current && !busy,modifier=Modifier.testTag("rename_playlist_confirm"),onClick={
            busy=true;error=null;val title=name.trim()
            scope.launch {
                provider.renamePlaylist(id,title).fold(onSuccess={uiHaptic(HapticCue.Confirm);provider.refreshLibraryIndex();onRenamed(title)},
                    onFailure={uiHaptic(HapticCue.Reject);error=it.message;busy=false})
            }
        }) {Text(string(R.string.save))}},
        dismissButton={TextButton(enabled=!busy,onClick=onDismiss,modifier=Modifier.testTag("rename_playlist_cancel")) {Text(string(R.string.cancel))}})
}

/** Q18-104: asks first, naming the playlist and that YouTube Music loses it too. */
@Composable internal fun DeletePlaylistDialog(provider:YouTubeMusicProvider,id:String,title:String,onDismiss:()->Unit,onDeleted:()->Unit) {
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={Text(string(R.string.delete_x_q,title))},
        text={Column(Modifier.testTag("delete_playlist_dialog")) {
            Text(string(R.string.delete_playlist_body))
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=RiffSpace.item))
            error?.let {Text(it,color=Pink,modifier=Modifier.padding(top=RiffSpace.item))}
        }},
        confirmButton={TextButton(enabled=!busy,modifier=Modifier.testTag("delete_playlist_confirm"),onClick={
            busy=true;error=null
            scope.launch {
                provider.deletePlaylist(id).fold(onSuccess={uiHaptic(HapticCue.Confirm);provider.refreshLibraryIndex();onDeleted()},
                    onFailure={uiHaptic(HapticCue.Reject);error=it.message;busy=false})
            }
        }) {Text(string(R.string.delete),color=Pink)}},
        dismissButton={TextButton(enabled=!busy,onClick=onDismiss,modifier=Modifier.testTag("delete_playlist_cancel")) {Text(string(R.string.cancel))}})
}

/** Shared action used by player, search, album and playlist rows. */
@Composable internal fun AddToPlaylistAction(track:ProviderTrack,onDismiss:()->Unit,onSelect:(ProviderTrack)->Unit) {
    if(track.isMusic && !track.isLive) SongActionItem("Add to playlist") {onDismiss();uiHaptic();onSelect(track)}
}
