package com.musicglass.personal

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal suspend fun requestSongDownload(context:Context,track:ProviderTrack,onNotice:(String)->Unit) {
    try {
        if(!OfflineMusicStore.get(context).canSave) {uiHaptic(HapticCue.Reject);onNotice(VERIFY_TO_SAVE_NOTICE);return}
        withContext(Dispatchers.IO) {OfflineMusicStore.get(context).enqueue(track)}
        DownloadCoordinator.start(context);uiHaptic(HapticCue.Confirm);onNotice(string(R.string.downloading_highest_available_audio_artwork))
    } catch(e:CancellationException) {throw e} catch(_:Exception) {uiHaptic(HapticCue.Reject);onNotice(string(R.string.could_not_start_download_retry_from_download))}
}
/** What a song's menu offers for its download (Q16B): to remove one that is ready or on its way, else to download it. */
internal fun songDownloadOffer(song:OfflineSong?,audioReady:Boolean)=
    if(audioReady || song?.status?.pending==true) "Remove download" else "Download song"
internal suspend fun requestSongRemoval(context:Context,track:ProviderTrack,onNotice:(String)->Unit) {
    try {
        val store=OfflineMusicStore.get(context)
        withContext(Dispatchers.IO) {store.remove(store.currentScope,track.videoId)}
        uiHaptic(HapticCue.Confirm);onNotice(string(R.string.download_removed))
    } catch(e:CancellationException) {throw e} catch(_:Exception) {uiHaptic(HapticCue.Reject);onNotice(string(R.string.could_not_change_this_download))}
}
/**
 * "Download song", or "Remove download" (pink, with a trash can) for a song already downloaded or on its way. Every
 * song menu uses this one. The store is read off the main thread, so the item appears once it is known, and it
 * follows the store while the menu is open. [scope] should outlive the menu when the caller has one.
 */
@Composable internal fun DownloadSongAction(track:ProviderTrack,onNotice:(String)->Unit={},onDismiss:()->Unit={},scope:CoroutineScope=rememberCoroutineScope()) {
    if(track.isLive || !track.isMusic) return
    val context=LocalContext.current;val store=remember {OfflineMusicStore.get(context)}
    val revision by store.revision.collectAsStateWithLifecycle()
    val offer by produceState<String?>(null,track.videoId,revision) {
        value=withContext(Dispatchers.IO) {
            val scopeName=store.currentScope
            songDownloadOffer(store.song(scopeName,track.videoId),store.audio(scopeName,track.videoId)!=null)
        }
    }
    when(offer) {
        "Remove download"->SongActionItem("Remove download",onClick={onDismiss();scope.launch {requestSongRemoval(context,track,onNotice)}})
        "Download song"->SongActionItem("Download song",onClick={onDismiss();scope.launch {requestSongDownload(context,track,onNotice)}})
    }
}
internal fun storageLabel(bytes:Long)=if(bytes>=1024L*1024*1024) "%.1f GB".format(bytes/(1024.0*1024*1024)) else "%.1f MB".format(bytes/(1024.0*1024))
internal val downloadBudgets=(1..16).map {it*1073741824L}+listOf(24L,32L,64L,128L,256L).map {it*1073741824L}+Long.MAX_VALUE
internal fun downloadBudgetIndex(bytes:Long)=downloadBudgets.indexOfFirst {it>=bytes}.let {if(it<0) downloadBudgets.lastIndex else it}
internal fun downloadBudgetLabel(bytes:Long)=if(bytes==Long.MAX_VALUE) string(R.string.unlimited) else "${bytes/1073741824L} GB"

internal suspend fun requestCollectionDownload(context:Context,tracks:List<ProviderTrack>,onNotice:(String)->Unit) {
    try {
        if(!OfflineMusicStore.get(context).canSave) {uiHaptic(HapticCue.Reject);onNotice(VERIFY_TO_SAVE_NOTICE);return}
        val count=withContext(Dispatchers.IO) {OfflineMusicStore.get(context).enqueueSongs(tracks)}
        if(count==0) {onNotice(string(R.string.no_downloadable_songs_in_this_collection));return}
        DownloadCoordinator.start(context);uiHaptic(HapticCue.Confirm)
        onNotice(quantity(R.plurals.n_songs_queued_for_download, count))
    } catch(e:CancellationException) {throw e} catch(_:Exception) {uiHaptic(HapticCue.Reject);onNotice(string(R.string.could_not_start_playlist_download_retry_from))}
}
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@Composable internal fun DownloadSettings(model:ShellModel,onNotice:(String)->Unit) {
    val context=LocalContext.current;val store=remember {OfflineMusicStore.get(context)}
    val revision by store.revision.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    var enabled by remember {mutableStateOf(store.smartEnabled)}
    var mobile by remember {mutableStateOf(store.allowMobileData)}
    var budgetIndex by remember {mutableFloatStateOf(downloadBudgetIndex(store.budgetBytes).toFloat())}
    var dragging by remember {mutableStateOf(false)}
    var cacheBytes by remember {mutableLongStateOf(0)}
    var confirmClearCache by remember {mutableStateOf(false)}
    var clearingCache by remember {mutableStateOf(false)}
    // A failure is said in the dialog that asked, and stays there until it is tried again or closed.
    var cacheFailure by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(revision) {
        enabled=store.smartEnabled;mobile=store.allowMobileData
        if(!dragging) budgetIndex=downloadBudgetIndex(store.budgetBytes).toFloat()
        cacheBytes=withContext(Dispatchers.IO) {MusicDiskCache.get(context).cacheSpace}
    }
    SettingsToggleRow(string(R.string.smart_downloads),enabled,{value->enabled=value;uiHaptic(if(value) HapticCue.ToggleOn else HapticCue.ToggleOff)
        scope.launch {withContext(Dispatchers.IO) {store.setSmart(value)}}},Icons.Rounded.AutoAwesome,"settings_smart_downloads",
        string(R.string.up_to_250_recently_played_songs))
    SettingsDivider()
    SettingsToggleRow(string(R.string.allow_mobile_data),mobile,{value->mobile=value;uiHaptic(if(value) HapticCue.ToggleOn else HapticCue.ToggleOff)
        scope.launch {withContext(Dispatchers.IO) {store.setAllowMobileData(value)}}},Icons.Rounded.DataUsage,"settings_download_mobile_data",
        if(mobile) string(R.string.wi_fi_and_mobile_data) else string(R.string.unmetered_wi_fi_only))
    SettingsDivider()
    Column(Modifier.padding(vertical=12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(string(R.string.storage_budget),color=White,fontSize=15.sp,modifier=Modifier.weight(1f))
            Text(downloadBudgetLabel(downloadBudgets[budgetIndex.roundToInt()]),color=White,fontSize=14.sp,fontWeight=FontWeight.Medium,
                modifier=Modifier.testTag("settings_download_budget_value"))
        }
        CapsuleSlider(budgetIndex,{dragging=true;budgetIndex=it},valueRange=0f..downloadBudgets.lastIndex.toFloat(),steps=downloadBudgets.size-2,
            onValueChangeFinished={val bytes=downloadBudgets[budgetIndex.roundToInt()]
                scope.launch {withContext(Dispatchers.IO) {store.setBudget(bytes)};dragging=false}},
            modifier=Modifier.fillMaxWidth().testTag("settings_download_budget").semantics {contentDescription=string(R.string.download_storage_budget);stateDescription=downloadBudgetLabel(downloadBudgets[budgetIndex.roundToInt()])})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(string(R.string.n_1_gb),color=Muted,fontSize=11.sp);Text(string(R.string.unlimited),color=Muted,fontSize=11.sp)
        }
    }
    SettingsDivider()
    SettingsLinkRow(string(R.string.downloaded_songs),icon=Icons.Rounded.DownloadDone,tag="settings_downloads") {model.push(Route.Downloads)}
    SettingsDivider()
    SettingsLinkRow(string(R.string.clear_playback_cache),storageLabel(cacheBytes),Icons.Rounded.CleaningServices,"settings_clear_cache") {
        confirmClearCache=true
    }
    if(confirmClearCache) FrostedAlertDialog(onDismissRequest={if(!clearingCache) {confirmClearCache=false;cacheFailure=null}},
        title={Text(string(R.string.clear_playback_cache_2))},text={Text(cacheFailure?:string(R.string.remove_cached_streaming_audio_x_downloaded_s, storageLabel(cacheBytes)),
            modifier=Modifier.testTag(if(cacheFailure==null) "clear_cache_prompt" else "clear_cache_failure"))},
        confirmButton={TextButton(enabled=!clearingCache,onClick={clearingCache=true;cacheFailure=null;scope.launch {
            try {withContext(Dispatchers.IO) {MusicDiskCache.clear(context)};cacheBytes=0;confirmClearCache=false;uiHaptic(HapticCue.Confirm);onNotice(string(R.string.playback_cache_cleared_downloads_kept))}
            catch(e:CancellationException) {throw e} catch(_:Exception) {cacheFailure=string(R.string.the_playback_cache_could_not_be_cleared)}
            finally {clearingCache=false}
        }},modifier=Modifier.testTag("confirm_clear_cache")) {Text(if(cacheFailure==null) string(R.string.clear_cache) else string(R.string.try_again),color=Pink)}},
        dismissButton={TextButton(enabled=!clearingCache,onClick={confirmClearCache=false;cacheFailure=null}) {Text(if(cacheFailure==null) string(R.string.cancel) else string(R.string.close))}})
}

private data class DownloadDisplay(val song:OfflineSong,val ready:Boolean,val artwork:String?)
private data class DownloadListing(val entries:List<DownloadDisplay>,val used:Long)
@Composable internal fun DownloadsScreen(model:ShellModel,bottom:Dp,playback:PlaybackGateway,onNotice:(String)->Unit) {
    val context=LocalContext.current;val store=remember {OfflineMusicStore.get(context)}
    val provider=remember {YouTubeMusicProvider.shared(context)};val revision by store.revision.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope();val listState=rememberLazyListState()
    // Null until the first load. One value, so what the list shows and whether it has loaded change together; read
    // into plain values below, so the lazy list's items change only when this screen recomposes.
    var listing by remember {mutableStateOf<DownloadListing?>(null)}
    val entries=listing?.entries.orEmpty();val loaded=listing!=null;val used=listing?.used?:0L
    var filter by rememberSaveable {mutableStateOf("All")}
    var query by rememberSaveable {mutableStateOf("")}
    var sortName by rememberSaveable {mutableStateOf(CollectionSort.Newest.name)}
    var playlistTrack by remember {mutableStateOf<ProviderTrack?>(null)}
    playlistTrack?.let {PlaylistPicker(it,{playlistTrack=null},onNotice)}
    var detailsId by rememberSaveable {mutableStateOf<String?>(null)}
    var deleteScope by remember {mutableStateOf<String?>(null)}
    var deleting by remember {mutableStateOf(false)}
    var deleteFailure by remember {mutableStateOf<String?>(null)}
    deleteScope?.let {account->FrostedAlertDialog(onDismissRequest={if(!deleting) {deleteScope=null;deleteFailure=null}},title={Text(string(R.string.delete_all_downloads))},
        text={Text(deleteFailure?:string(R.string.remove_all_downloaded_songs_artwork_and_lyri),
            modifier=Modifier.testTag(if(deleteFailure==null) "delete_downloads_prompt" else "delete_downloads_failure"))},
        confirmButton={TextButton(enabled=!deleting,onClick={deleting=true;deleteFailure=null;scope.launch {
            try {withContext(Dispatchers.IO) {store.removeAll(account)};deleteScope=null;uiHaptic(HapticCue.Confirm);onNotice(string(R.string.all_downloads_deleted))}
            catch(e:CancellationException) {throw e} catch(_:Exception) {deleteFailure=string(R.string.not_every_download_could_be_deleted_some)}
            finally {deleting=false}
        }},modifier=Modifier.testTag("confirm_delete_downloads")) {Text(if(deleteFailure==null) string(R.string.delete_all_downloads_2) else string(R.string.try_again),color=Pink)}},
        dismissButton={TextButton(enabled=!deleting,onClick={deleteScope=null;deleteFailure=null}) {Text(if(deleteFailure==null) string(R.string.cancel) else string(R.string.close))}})}
    LaunchedEffect(revision,provider.authRevision) {
        val scopeKey=store.currentScope
        val result=withContext(Dispatchers.IO) {
            // A finished download whose files have gone is shown as needing a new download, not as downloaded.
            store.verifyComplete(scopeKey)
            val songs=store.rows(scopeKey).filter {it.status!=DownloadState.Metadata && it.status!=DownloadState.Cancelled}
                .sortedWith(compareByDescending<OfflineSong> {it.downloadedAt}.thenBy {it.track.title.lowercase(java.util.Locale.ROOT)}.thenBy {it.track.videoId})
            songs.map {song->DownloadDisplay(song,store.audio(scopeKey,song.track.videoId)!=null,
                store.artwork(scopeKey,song.track.videoId)?.toURI()?.toString()?:song.track.artworkUrl)}.let {DownloadListing(it,store.usedBytes(scopeKey))}
        }
        // Written on the main thread: an effect's dispatcher need not bring it back there (a test's unconfined one
        // does not), and a write from elsewhere can land while the list is being measured ("Index 3, size 3").
        withContext(Dispatchers.Main.immediate) {listing=result}
    }
    val ready=entries.filter {it.ready};val tracks=ready.map {it.song.track}
    val filtered=entries.filter {filter=="All" || if(filter=="Downloaded") it.ready else !it.ready}
    val sort=CollectionSort.valueOf(sortName)
    val visible=collectionView(filtered,query,sort,{it.song.track.title},{it.song.track.artist},{it.song.downloadedAt.takeIf {date->date>0}}).map {it.value}
    val cover=ready.maxByOrNull {it.song.downloadedAt}?.artwork
    val tone=rememberArtworkColor(cover).readableArtworkTone()
    val height=(LocalConfiguration.current.screenHeightDp*.57f).dp
    fun play(id:String?=null,shuffle:Boolean=false) {
        if(tracks.isEmpty()) return
        val index=if(shuffle) tracks.indices.random() else if(id==null) 0 else tracks.indexOfFirst {it.videoId==id}
        if(index>=0) {uiHaptic();if(id!=null && !shuffle) SongTaps.tapped("yt:$id");playback.playCollection(tracks,index,string(R.string.downloads),shuffle)}
    }
    LazyColumn(state=listState,modifier=Modifier.fillMaxSize().background(tone).testTag("downloads_detail"),
        contentPadding=PaddingValues(bottom=bottom)) {
        item {
            Box(Modifier.fillMaxWidth().height(height)) {
                if(cover==null) Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF253C53),Color(0xFF292B46)))),contentAlignment=Alignment.Center) {
                    Icon(Icons.Rounded.OfflinePin,null,tint=White.copy(alpha=.2f),modifier=Modifier.size(90.dp))
                } else AsyncImage(cover,null,contentScale=ContentScale.Crop,
                    modifier=Modifier.fillMaxSize().testTag("downloads_artwork"))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha=.32f),.42f to Color.Transparent,1f to tone)))
                Box(Modifier.statusBarsPadding().padding(horizontal=16.dp)) {BackHeading(string(R.string.library),model::back,overPicture=true)}
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(string(R.string.downloads),color=White,fontSize=28.sp,fontWeight=FontWeight.SemiBold)
                    Text(if(!loaded) string(R.string.loading_2) else quantity(R.plurals.n_songs_n, ready.size, storageLabel(used)),
                        color=White.copy(alpha=.78f),fontSize=15.sp,modifier=Modifier.padding(top=6.dp))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                FilledTonalIconButton({play(shuffle=true)},enabled=tracks.isNotEmpty(),modifier=Modifier.size(52.dp).testTag("downloads_shuffle"),
                    colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=White.copy(alpha=.12f))) {Icon(Icons.Rounded.Shuffle,string(R.string.shuffle_downloads),tint=White)}
                Button({play()},enabled=tracks.isNotEmpty(),modifier=Modifier.weight(1f).height(56.dp).testTag("downloads_play"),shape=RoundedCornerShape(50),
                    colors=ButtonDefaults.buttonColors(containerColor=White,contentColor=tone)) {
                    Icon(Icons.Rounded.PlayArrow,null);Spacer(Modifier.width(8.dp));Text(string(R.string.play),fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                }
                FilledTonalIconButton({uiHaptic();deleteScope=store.currentScope},enabled=entries.isNotEmpty() && !deleting,modifier=Modifier.size(52.dp).testTag("downloads_delete_all"),
                    colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=White.copy(alpha=.12f))) {Icon(Icons.Rounded.DeleteOutline,string(R.string.delete_all_downloads_2),tint=White)}
            }
        }
        item {CollectionTools(string(R.string.downloaded_songs_2),query,{query=it},sort,{sortName=it.name},true)}
        if(entries.isNotEmpty()) item {
            Row(Modifier.padding(horizontal=20.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("All","Downloaded","Pending").forEach {name->FilterChip(filter==name,{uiHaptic(HapticCue.Selection);filter=name},
                    label={Text(name,fontSize=12.sp)},modifier=Modifier.testTag("downloads_filter_${name.lowercase()}"))}
            }
        }
        // Feature 28: the shared empty look, with the reason in its title.
        if(loaded && visible.isEmpty()) item {
            EmptyState(Icons.Rounded.DownloadDone,if(query.isNotBlank()) string(R.string.no_songs_match_this_search) else if(entries.isEmpty()) string(R.string.download_a_song_from_its_menu_or)
                else if(filter=="Pending") string(R.string.all_downloads_are_ready) else string(R.string.no_downloaded_songs_yet))
        }
        itemsIndexed(visible,key={_,entry->entry.song.track.videoId}) {index,entry->
            val song=entry.song;var menu by remember(song.track.videoId) {mutableStateOf(false)}
            val active=song.status?.pending==true
            Column(Modifier.padding(horizontal=20.dp).testTag("download_${song.track.videoId}")) {
                HorizontalDivider(color=White.copy(alpha=.1f))
                Row(Modifier.fillMaxWidth().heightIn(min=60.dp).clickable {if(entry.ready) play(song.track.videoId) else {uiHaptic();detailsId=song.track.videoId}}
                    .testTag("download_track_${song.track.videoId}"),verticalAlignment=Alignment.CenterVertically) {
                    CatalogArtwork(entry.artwork,song.track.videoId,Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).testTag("download_art_${song.track.videoId}"))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).padding(vertical=8.dp)) {
                        Text(song.track.title,color=White,fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(playerArtistLabel(song.track.artist),color=White.copy(alpha=.6f),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(active || !entry.ready) DownloadPhaseLine(song,entry.ready)
                    }
                    if(entry.ready && !active && song.durationMs>0) Text("${song.durationMs/60000}:${((song.durationMs/1000)%60).toString().padStart(2,'0')}",color=White.copy(alpha=.6f),fontSize=12.sp)
                    Box {
                        IconButton({uiHaptic();menu=true},modifier=Modifier.testTag("download_menu_${song.track.videoId}")) {Icon(Icons.Rounded.MoreVert,string(R.string.download_options),tint=White.copy(alpha=.65f))}
                        SongActionMenu(menu,{menu=false}) {
                            AddToPlaylistAction(song.track,{menu=false}) {playlistTrack=it}
                            SongActionItem("Download details",onClick={menu=false;detailsId=song.track.videoId})
                            if(song.status==DownloadState.Failed || song.status==DownloadState.Paused || song.status==null || song.artworkState=="failed" || song.lyricsState=="failed")
                                SongActionItem(downloadRetryLabel(entry.ready),onClick={menu=false;scope.launch {requestSongDownload(context,song.track,onNotice)}})
                            if(!song.pinned && entry.ready) SongActionItem("Keep downloaded",onClick={menu=false;scope.launch {requestSongDownload(context,song.track,onNotice)}})
                            if(active) SongActionItem("Pause download",onClick={menu=false;scope.launch {withContext(Dispatchers.IO) {store.pause(song.scope,song.track.videoId)}}})
                            SongActionItem("Remove download",onClick={menu=false;scope.launch {withContext(Dispatchers.IO) {store.remove(song.scope,song.track.videoId)};onNotice(string(R.string.download_removed))}})
                        }
                    }
                }
                if(song.status==DownloadState.Transferring) {
                    if(song.total>0) LinearProgressIndicator(progress={(song.bytes.toFloat()/song.total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(2.dp))
                    else LinearProgressIndicator(modifier=Modifier.fillMaxWidth().height(2.dp))
                }
            }
        }
    }
    val details=entries.firstOrNull {it.song.track.videoId==detailsId}
    if(details!=null) AlertDialog(onDismissRequest={detailsId=null},title={Text(details.song.track.title,color=White)},text={
        val song=details.song
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(playerArtistLabel(song.track.artist),color=Muted)
            Text(if(details.ready) string(R.string.ready_offline_x, storageLabel(song.bytes)) else downloadStatus(song,false),color=White)
            if(song.quality.isNotBlank()) Text(song.quality.replace("audio/webm","WebM").replace("audio/mp4","AAC"),color=Muted,fontSize=13.sp)
            Text(if(song.pinned) string(R.string.kept_on_this_phone) else string(R.string.managed_by_smart_downloads),color=Muted,fontSize=13.sp)
            Text(string(R.string.artwork_x, assetStatus(song.artworkState)),color=Muted,fontSize=13.sp)
            Text(string(R.string.lyrics_x, assetStatus(song.lyricsState)),color=Muted,fontSize=13.sp)
            if(song.error.isNotBlank()) Text(song.error,color=Pink,fontSize=13.sp)
        }
    },confirmButton={TextButton({uiHaptic();detailsId=null}) {Text(string(R.string.done),color=Pink)}},containerColor=Raised)
}
/**
 * A row's phase line. It is a polite live region whose spoken text is the phase alone, so TalkBack hears each phase
 * once when it changes and nothing as the percentage climbs (Q18-031); the percentage stays on screen.
 */
@Composable
internal fun DownloadPhaseLine(song:OfflineSong,ready:Boolean) {
    val spoken=downloadPhaseSpoken(song,ready)
    Text(downloadStatus(song,ready),color=if(song.status==DownloadState.Failed) Pink else White.copy(alpha=.6f),fontSize=11.sp,maxLines=1,
        modifier=Modifier.testTag("download_phase_${song.track.videoId}").clearAndSetSemantics {contentDescription=spoken;liveRegion=LiveRegionMode.Polite})
}
/** What is spoken for a phase: [downloadStatus] without the percentage, which would otherwise be read at every step. */
internal fun downloadPhaseSpoken(song:OfflineSong,ready:Boolean)=
    if(song.status==DownloadState.Transferring) string(R.string.downloading) else downloadStatus(song,ready)
/** [ready] is whether the audio itself is on the phone and playable; artwork and lyrics may still be wanted. */
internal fun downloadStatus(song:OfflineSong,ready:Boolean)=when(song.status) {
    DownloadState.Queued->if(ready) string(R.string.waiting_to_save_artwork_and_lyrics) else string(R.string.waiting_to_download)
    DownloadState.Resolving->if(ready) string(R.string.saving_artwork_and_lyrics) else string(R.string.preparing_download)
    DownloadState.Transferring->if(song.total>0) string(R.string.downloading_x, (song.bytes*100/song.total).coerceIn(0,100)) else string(R.string.downloading)
    DownloadState.Validating->string(R.string.checking_audio)
    DownloadState.Assets->string(R.string.saving_artwork_and_lyrics)
    // A transfer never continues from where it stopped: it begins again with a fresh stream.
    DownloadState.Paused->if(ready) string(R.string.paused) else string(R.string.paused_starts_again_from_the_beginning)
    DownloadState.Failed->if(ready) string(R.string.artwork_or_lyrics_missing) else string(R.string.download_failed)
    DownloadState.Complete->if(ready) "Downloaded" else string(R.string.download_missing)
    DownloadState.Metadata,DownloadState.Cancelled->string(R.string.not_downloaded)
    null->string(R.string.needs_downloading_again)
}
/** Honest about what the action does: audio is fetched again from the start; only missing artwork or lyrics are added to audio that is there. */
internal fun downloadRetryLabel(ready:Boolean)=if(ready) "Retry missing assets" else string(R.string.restart_download)
private fun assetStatus(state:String)=when(state) {"available"->string(R.string.saved);"instrumental"->string(R.string.instrumental);"unavailable"->string(R.string.not_available);"failed"->string(R.string.retry_needed);else->string(R.string.pending)}
