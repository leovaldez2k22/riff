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
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

internal suspend fun requestSongDownload(context:Context,track:ProviderTrack,onNotice:(String)->Unit) {
    try {
        withContext(Dispatchers.IO) {OfflineMusicStore.get(context).enqueue(track)}
        context.startForegroundService(Intent(context,OfflineDownloadService::class.java))
        OfflineDownloadScheduler.resumeManualLater(context);uiHaptic(HapticCue.Confirm);onNotice("Downloading highest available audio, artwork and lyrics")
    } catch(e:CancellationException) {throw e} catch(_:Exception) {uiHaptic(HapticCue.Reject);onNotice("Could not start download. Retry from Downloads.")}
}
@Composable internal fun DownloadSongAction(track:ProviderTrack,onNotice:(String)->Unit={},onDismiss:()->Unit={}) {
    if(track.isLive || !track.isMusic) return
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    SongActionItem("Download song",onClick={onDismiss();scope.launch {requestSongDownload(context,track,onNotice)}})
}
internal fun storageLabel(bytes:Long)=if(bytes>=1024L*1024*1024) "%.1f GB".format(bytes/(1024.0*1024*1024)) else "%.1f MB".format(bytes/(1024.0*1024))
internal val downloadBudgets=(1..16).map {it*1073741824L}+listOf(24L,32L,64L,128L,256L).map {it*1073741824L}+Long.MAX_VALUE
internal fun downloadBudgetIndex(bytes:Long)=downloadBudgets.indexOfFirst {it>=bytes}.let {if(it<0) downloadBudgets.lastIndex else it}
internal fun downloadBudgetLabel(bytes:Long)=if(bytes==Long.MAX_VALUE) "Unlimited" else "${bytes/1073741824L} GB"

internal suspend fun requestCollectionDownload(context:Context,tracks:List<ProviderTrack>,onNotice:(String)->Unit) {
    try {
        val count=withContext(Dispatchers.IO) {OfflineMusicStore.get(context).enqueueSongs(tracks)}
        if(count==0) {onNotice("No downloadable songs in this collection");return}
        context.startForegroundService(Intent(context,OfflineDownloadService::class.java))
        OfflineDownloadScheduler.resumeManualLater(context);uiHaptic(HapticCue.Confirm)
        onNotice("$count ${if(count==1) "song" else "songs"} queued for download")
    } catch(e:CancellationException) {throw e} catch(_:Exception) {uiHaptic(HapticCue.Reject);onNotice("Could not start playlist download. Retry from Downloads.")}
}
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@Composable internal fun DownloadSettings(model:ShellModel,onNotice:(String)->Unit) {
    val context=LocalContext.current;val store=remember {OfflineMusicStore.get(context)}
    val revision by store.revision.collectAsState();val scope=rememberCoroutineScope()
    var enabled by remember {mutableStateOf(store.smartEnabled)}
    var mobile by remember {mutableStateOf(store.allowMobileData)}
    var budgetIndex by remember {mutableFloatStateOf(downloadBudgetIndex(store.budgetBytes).toFloat())}
    var dragging by remember {mutableStateOf(false)}
    var cacheBytes by remember {mutableLongStateOf(0)}
    var confirmClearCache by remember {mutableStateOf(false)}
    var clearingCache by remember {mutableStateOf(false)}
    LaunchedEffect(revision) {
        enabled=store.smartEnabled;mobile=store.allowMobileData
        if(!dragging) budgetIndex=downloadBudgetIndex(store.budgetBytes).toFloat()
        cacheBytes=withContext(Dispatchers.IO) {MusicDiskCache.get(context).cacheSpace}
    }
    SettingsToggleRow("Smart Downloads",enabled,{value->enabled=value;uiHaptic(if(value) HapticCue.ToggleOn else HapticCue.ToggleOff)
        scope.launch {withContext(Dispatchers.IO) {store.setSmart(value)}}},Icons.Rounded.AutoAwesome,"settings_smart_downloads",
        "Up to 250 recently played songs")
    SettingsDivider()
    SettingsToggleRow("Allow mobile data",mobile,{value->mobile=value;uiHaptic(if(value) HapticCue.ToggleOn else HapticCue.ToggleOff)
        scope.launch {withContext(Dispatchers.IO) {store.setAllowMobileData(value)}}},Icons.Rounded.DataUsage,"settings_download_mobile_data",
        if(mobile) "Wi-Fi and mobile data" else "Unmetered Wi-Fi only")
    SettingsDivider()
    Column(Modifier.padding(vertical=12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Storage budget",color=White,fontSize=15.sp,modifier=Modifier.weight(1f))
            Text(downloadBudgetLabel(downloadBudgets[budgetIndex.roundToInt()]),color=White,fontSize=14.sp,fontWeight=FontWeight.Medium,
                modifier=Modifier.testTag("settings_download_budget_value"))
        }
        CapsuleSlider(budgetIndex,{dragging=true;budgetIndex=it},valueRange=0f..downloadBudgets.lastIndex.toFloat(),steps=downloadBudgets.size-2,
            onValueChangeFinished={val bytes=downloadBudgets[budgetIndex.roundToInt()]
                scope.launch {withContext(Dispatchers.IO) {store.setBudget(bytes)};dragging=false}},
            modifier=Modifier.fillMaxWidth().testTag("settings_download_budget").semantics {contentDescription="Download storage budget";stateDescription=downloadBudgetLabel(downloadBudgets[budgetIndex.roundToInt()])})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("1 GB",color=Muted,fontSize=11.sp);Text("Unlimited",color=Muted,fontSize=11.sp)
        }
    }
    SettingsDivider()
    SettingsLinkRow("Downloaded songs",icon=Icons.Rounded.DownloadDone,tag="settings_downloads") {model.push("downloads")}
    SettingsDivider()
    SettingsLinkRow("Clear playback cache",storageLabel(cacheBytes),Icons.Rounded.CleaningServices,"settings_clear_cache") {
        confirmClearCache=true
    }
    if(confirmClearCache) FrostedAlertDialog(onDismissRequest={if(!clearingCache) confirmClearCache=false},
        title={Text("Clear playback cache?")},text={Text("Remove cached streaming audio (${storageLabel(cacheBytes)})? Downloaded songs will be kept.")},
        confirmButton={TextButton(enabled=!clearingCache,onClick={clearingCache=true;scope.launch {
            try {withContext(Dispatchers.IO) {MusicDiskCache.clear(context)};cacheBytes=0;confirmClearCache=false;uiHaptic(HapticCue.Confirm);onNotice("Playback cache cleared; downloads kept")}
            catch(e:CancellationException) {throw e} catch(_:Exception) {onNotice("Could not clear playback cache. Try again.")}
            finally {clearingCache=false}
        }},modifier=Modifier.testTag("confirm_clear_cache")) {Text("Clear")}},
        dismissButton={TextButton(enabled=!clearingCache,onClick={confirmClearCache=false}) {Text("Cancel")}})
}

private data class DownloadDisplay(val song:OfflineSong,val ready:Boolean,val artwork:String?)
@Composable internal fun DownloadsScreen(model:ShellModel,bottom:Dp,playback:PlaybackGateway,onNotice:(String)->Unit) {
    val context=LocalContext.current;val store=remember {OfflineMusicStore.get(context)}
    val provider=remember {YouTubeMusicProvider.shared(context)};val revision by store.revision.collectAsState()
    val scope=rememberCoroutineScope();val listState=rememberLazyListState()
    var entries by remember {mutableStateOf<List<DownloadDisplay>>(emptyList())}
    var loaded by remember {mutableStateOf(false)};var used by remember {mutableLongStateOf(0)}
    var filter by rememberSaveable {mutableStateOf("All")}
    var query by rememberSaveable {mutableStateOf("")}
    var sortName by rememberSaveable {mutableStateOf(CollectionSort.Newest.name)}
    var playlistTrack by remember {mutableStateOf<ProviderTrack?>(null)}
    playlistTrack?.let {PlaylistPicker(it,{playlistTrack=null},onNotice)}
    var detailsId by rememberSaveable {mutableStateOf<String?>(null)}
    var deleteScope by remember {mutableStateOf<String?>(null)}
    var deleting by remember {mutableStateOf(false)}
    deleteScope?.let {account->FrostedAlertDialog(onDismissRequest={if(!deleting) deleteScope=null},title={Text("Delete all downloads?")},
        text={Text("Remove all downloaded songs, artwork and lyrics from this account on this phone, and cancel its pending downloads?")},
        confirmButton={TextButton(enabled=!deleting,onClick={deleting=true;scope.launch {
            try {withContext(Dispatchers.IO) {store.removeAll(account)};deleteScope=null;uiHaptic(HapticCue.Confirm);onNotice("All downloads deleted")}
            catch(e:CancellationException) {throw e} catch(_:Exception) {onNotice("Could not delete all downloads. Try again.")}
            finally {deleting=false}
        }},modifier=Modifier.testTag("confirm_delete_downloads")) {Text("Delete all",color=Pink)}},
        dismissButton={TextButton(enabled=!deleting,onClick={deleteScope=null}) {Text("Cancel")}})}
    LaunchedEffect(revision,provider.authRevision) {
        val scopeKey=store.currentScope
        val result=withContext(Dispatchers.IO) {
            val songs=store.rows(scopeKey).filter {it.state!="metadata" && it.state!="cancelled"}
                .sortedWith(compareByDescending<OfflineSong> {it.downloadedAt}.thenBy {it.track.title.lowercase(java.util.Locale.ROOT)}.thenBy {it.track.videoId})
            songs.map {song->DownloadDisplay(song,store.audio(scopeKey,song.track.videoId)!=null,
                store.artwork(scopeKey,song.track.videoId)?.toURI()?.toString()?:song.track.artworkUrl)} to store.usedBytes(scopeKey)
        }
        entries=result.first;used=result.second;loaded=true
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
        if(index>=0) {uiHaptic();playback.playCollection(tracks,index,"Downloads",shuffle)}
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
                Box(Modifier.statusBarsPadding().padding(horizontal=16.dp)) {BackHeading("Library",model::back)}
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("Downloads",color=White,fontSize=28.sp,fontWeight=FontWeight.SemiBold)
                    Text(if(!loaded) "Loading\u2026" else "${ready.size} ${if(ready.size==1) "song" else "songs"} \u00B7 ${storageLabel(used)}",
                        color=White.copy(alpha=.78f),fontSize=15.sp,modifier=Modifier.padding(top=6.dp))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                FilledTonalIconButton({play(shuffle=true)},enabled=tracks.isNotEmpty(),modifier=Modifier.size(52.dp).testTag("downloads_shuffle"),
                    colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=White.copy(alpha=.12f))) {Icon(Icons.Rounded.Shuffle,"Shuffle downloads",tint=White)}
                Button({play()},enabled=tracks.isNotEmpty(),modifier=Modifier.weight(1f).height(56.dp).testTag("downloads_play"),shape=RoundedCornerShape(50),
                    colors=ButtonDefaults.buttonColors(containerColor=White,contentColor=tone)) {
                    Icon(Icons.Rounded.PlayArrow,null);Spacer(Modifier.width(8.dp));Text("Play",fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                }
                FilledTonalIconButton({uiHaptic();deleteScope=store.currentScope},enabled=entries.isNotEmpty() && !deleting,modifier=Modifier.size(52.dp).testTag("downloads_delete_all"),
                    colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=White.copy(alpha=.12f))) {Icon(Icons.Rounded.DeleteOutline,"Delete all downloads",tint=White)}
            }
        }
        item {CollectionTools("downloaded songs",query,{query=it},sort,{sortName=it.name},true)}
        if(entries.isNotEmpty()) item {
            Row(Modifier.padding(horizontal=20.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("All","Downloaded","Pending").forEach {name->FilterChip(filter==name,{uiHaptic(HapticCue.Selection);filter=name},
                    label={Text(name,fontSize=12.sp)},modifier=Modifier.testTag("downloads_filter_${name.lowercase()}"))}
            }
        }
        if(loaded && visible.isEmpty()) item {
            Text(if(query.isNotBlank()) "No songs match this search." else if(entries.isEmpty()) "Download a song from its menu, or turn on Smart Downloads in Settings."
                else if(filter=="Pending") "All downloads are ready." else "No downloaded songs yet.",color=White.copy(alpha=.7f),fontSize=14.sp,
                modifier=Modifier.padding(horizontal=24.dp,vertical=20.dp))
        }
        itemsIndexed(visible,key={_,entry->entry.song.track.videoId}) {index,entry->
            val song=entry.song;var menu by remember(song.track.videoId) {mutableStateOf(false)}
            val active=song.state in setOf("queued","downloading","assets")
            Column(Modifier.padding(horizontal=20.dp).testTag("download_${song.track.videoId}")) {
                HorizontalDivider(color=White.copy(alpha=.1f))
                Row(Modifier.fillMaxWidth().heightIn(min=60.dp).clickable {if(entry.ready) play(song.track.videoId) else {uiHaptic();detailsId=song.track.videoId}}
                    .testTag("download_track_${song.track.videoId}"),verticalAlignment=Alignment.CenterVertically) {
                    CatalogArtwork(entry.artwork,song.track.videoId,Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).testTag("download_art_${song.track.videoId}"))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).padding(vertical=8.dp)) {
                        Text(song.track.title,color=White,fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(playerArtistLabel(song.track.artist),color=White.copy(alpha=.6f),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(active || !entry.ready) Text(downloadStatus(song),color=if(song.state=="failed") Pink else White.copy(alpha=.6f),fontSize=11.sp,maxLines=1)
                    }
                    if(entry.ready && !active && song.durationMs>0) Text("${song.durationMs/60000}:${((song.durationMs/1000)%60).toString().padStart(2,'0')}",color=White.copy(alpha=.55f),fontSize=12.sp)
                    Box {
                        IconButton({uiHaptic();menu=true},modifier=Modifier.testTag("download_menu_${song.track.videoId}")) {Icon(Icons.Rounded.MoreVert,"Download options",tint=White.copy(alpha=.65f))}
                        SongActionMenu(menu,{menu=false}) {
                            AddToPlaylistAction(song.track,{menu=false}) {playlistTrack=it}
                            SongActionItem("Download details",onClick={menu=false;detailsId=song.track.videoId})
                            if(song.state in setOf("failed","paused") || song.artworkState=="failed" || song.lyricsState=="failed")
                                SongActionItem(if(entry.ready) "Retry missing assets" else "Resume download",onClick={menu=false;scope.launch {requestSongDownload(context,song.track,onNotice)}})
                            if(!song.pinned && entry.ready) SongActionItem("Keep downloaded",onClick={menu=false;scope.launch {requestSongDownload(context,song.track,onNotice)}})
                            if(active) SongActionItem("Pause download",onClick={menu=false;scope.launch {withContext(Dispatchers.IO) {store.update(song.scope,song.track.videoId,"state" to "paused")}}})
                            SongActionItem("Remove download",onClick={menu=false;scope.launch {withContext(Dispatchers.IO) {store.remove(song.scope,song.track.videoId)};onNotice("Download removed")}})
                        }
                    }
                }
                if(song.state=="downloading") {
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
            Text(if(details.ready) "Ready offline \u00B7 ${storageLabel(song.bytes)}" else downloadStatus(song),color=White)
            if(song.quality.isNotBlank()) Text(song.quality.replace("audio/webm","WebM").replace("audio/mp4","AAC"),color=Muted,fontSize=13.sp)
            Text(if(song.pinned) "Kept on this phone" else "Managed by Smart Downloads",color=Muted,fontSize=13.sp)
            Text("Artwork: ${assetStatus(song.artworkState)}",color=Muted,fontSize=13.sp)
            Text("Lyrics: ${assetStatus(song.lyricsState)}",color=Muted,fontSize=13.sp)
            if(song.error.isNotBlank()) Text(song.error,color=Pink,fontSize=13.sp)
        }
    },confirmButton={TextButton({uiHaptic();detailsId=null}) {Text("Done",color=Pink)}},containerColor=Raised)
}
private fun downloadStatus(song:OfflineSong)=when(song.state) {
    "queued"->"Waiting to download";"downloading"->if(song.total>0) "Downloading ${(song.bytes*100/song.total).coerceIn(0,100)}%" else "Downloading\u2026"
    "assets"->"Saving artwork and lyrics";"paused"->"Paused";"failed"->"Download failed";"complete"->"Downloaded";else->"Not downloaded"
}
private fun assetStatus(state:String)=when(state) {"available"->"Saved";"instrumental"->"Instrumental";"unavailable"->"Not available";"failed"->"Retry needed";else->"Pending"}
