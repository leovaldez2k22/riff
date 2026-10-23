package com.musicglass.personal

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable internal fun ImmersiveCollectionDetail(model:ShellModel,bottom:Dp,data:CatalogPage,
    provider:YouTubeMusicProvider,playback:PlaybackGateway,onNotice:(String)->Unit,complete:Boolean=true,
    loadingError:String?=null,onRetry:()->Unit={}) {
    // This screen's own collection; the model's is whichever screen is on top.
    val shown=LocalCollectionRoute.current
    val collectionId=shown?.id?:model.selectedPlaylistId;val collectionTitle=shown?.title?:model.selectedCollectionTitle
    val artist=collectionId.startsWith("UC")
    val playlist=collectionId.startsWith("VL")
    val scope=rememberCoroutineScope();val tone=rememberArtworkColor(data.artworkUrl).readableArtworkTone()
    val context=LocalContext.current
    val height=(LocalConfiguration.current.screenHeightDp*(if(artist) .5f else .57f)).dp
    var playlistTrack by remember {mutableStateOf<ProviderTrack?>(null)}
    playlistTrack?.let {PlaylistPicker(it,{playlistTrack=null},onNotice)}
    var removing by remember {mutableStateOf<ProviderHomeCard?>(null)}
    var removeBusy by remember {mutableStateOf(false)}
    removing?.let {card->AlertDialog(onDismissRequest={if(!removeBusy) removing=null},title={Text(string(R.string.remove_from_playlist))},text={Text(card.title)},
        confirmButton={TextButton(enabled=!removeBusy,onClick={removeBusy=true;scope.launch {provider.removePlaylistSong(collectionId,card).fold(onSuccess={uiHaptic(HapticCue.Confirm);removing=null;onNotice(string(R.string.removed_from_playlist));provider.refreshLibraryIndex()},onFailure={onNotice(it.message?:string(R.string.could_not_remove_song))});removeBusy=false}}) {Text(string(R.string.remove))}},
        dismissButton={TextButton(enabled=!removeBusy,onClick={removing=null}) {Text(string(R.string.cancel))}})}
    var expanded by remember(collectionId) {mutableStateOf(false)}
    var sortedPage by remember(data) {mutableStateOf<CatalogPage?>(null)}
    var remoteSort by remember(data) {mutableStateOf<CollectionSort?>(data.selectedSort)}
    var sorting by remember(collectionId) {mutableStateOf(false)}
    val source=sortedPage?:data
    // What can be offered for this list as it stands: the session, whether it is editable, and whether it is the whole collection.
    val caps=collectionCapabilities(source,complete,playlist,provider.capabilities)
    val songs=if(source.albumTracks.isNotEmpty()) source.albumTracks else source.cards.filter {it.kind in setOf(CatalogKind.Song,CatalogKind.Episode)}
    var query by rememberSaveable(collectionId) {mutableStateOf("")}
    var sortName by rememberSaveable(collectionId) {mutableStateOf((data.selectedSort?:CollectionSort.Original).name)}
    val sort=CollectionSort.valueOf(sortName)
    LaunchedEffect(data) {
        data.selectedSort?.let {sortName=it.name;remoteSort=it} ?: run {
            if(sort==CollectionSort.TopVoted || sort in setOf(CollectionSort.Newest,CollectionSort.Oldest) && data.albumTracks.none {it.addedAt!=null}) sortName=CollectionSort.Original.name
        }
    }
    val ordered=remember(songs,sort,remoteSort) {collectionView(songs,"",if(remoteSort==sort) CollectionSort.Original else sort,{it.title},{it.subtitle},{it.addedAt},{it.album})}
    val visible=remember(songs,query,sort,remoteSort) {collectionView(songs,query,if(remoteSort==sort) CollectionSort.Original else sort,{it.title},{it.subtitle},{it.addedAt},{it.album})}
    val tracks=songs.mapNotNull {it.track()}.map {it.copy(albumId=if(playlist) it.albumId else collectionId,album=if(playlist) it.album else data.title,
        artworkUrl=if(artist || playlist) it.artworkUrl else data.artworkUrl?:it.artworkUrl,artist=it.artist.ifBlank {if(playlist) "" else data.artistName},artistId=it.artistId?:data.artistId.takeUnless {playlist})}
    // While later pages are still loading, hold the request so the queue gets the whole collection.
    var pendingPlay by remember(collectionId) {mutableStateOf<Pair<Int?,Boolean>?>(null)}
    var pendingDownload by remember(collectionId) {mutableStateOf(false)}
    var downloading by remember(collectionId) {mutableStateOf(false)}
    LaunchedEffect(complete,pendingDownload) {
        if(!complete || !pendingDownload) return@LaunchedEffect
        try {requestCollectionDownload(context,tracks,onNotice)}
        finally {pendingDownload=false;downloading=false}
    }
    fun play(index:Int?=null,shuffle:Boolean=false) {
        if(tracks.isEmpty()) return
        // Play and Shuffle mean the whole collection. One row of a list that is labelled as partial plays from what is loaded.
        if(index==null || shuffle) caps.playAll.reason?.let {onNotice(it);return}
        if(!complete) {pendingPlay=index to shuffle;onNotice(string(R.string.starting_once_all_songs_load));return}
        val queue=ordered.mapNotNull {tracks.getOrNull(it.index)}
        val start=if(index==null) 0 else ordered.indexOfFirst {it.index==index}.coerceAtLeast(0)
        if(index!=null && !shuffle) queue.getOrNull(start)?.let {SongTaps.tapped("yt:${it.videoId}")}
        playback.playCollection(queue,start,data.title,shuffle)
    }
    LaunchedEffect(complete,pendingPlay) {
        val (index,shuffle)=pendingPlay?:return@LaunchedEffect
        if(!complete || tracks.isEmpty()) return@LaunchedEffect
        pendingPlay=null
        play(index,shuffle)
    }
    LazyColumn(Modifier.fillMaxSize().background(tone).testTag(if(artist) "artist_detail" else if(playlist) "playlist_detail" else "album_detail"),
        contentPadding=PaddingValues(bottom=bottom)) {
        item {
            Box(Modifier.fillMaxWidth().height(height)) {
                CatalogArtwork(data.artworkUrl,collectionId,Modifier.fillMaxSize().testTag("collection_artwork"))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha=.18f),.46f to Color.Transparent,1f to tone)))
                Box(Modifier.statusBarsPadding().padding(horizontal=16.dp)) {BackHeading(string(R.string.back),model::back,overPicture=true)}
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().wordsOverPicture(tone).padding(24.dp),
                    horizontalAlignment=if(artist) Alignment.Start else Alignment.CenterHorizontally) {
                    Text(data.title.ifBlank {collectionTitle},color=White,fontSize=if(artist) 34.sp else 28.sp,
                        fontWeight=FontWeight.SemiBold,maxLines=3,overflow=TextOverflow.Ellipsis)
                    if(!artist && data.artistName.isNotBlank()) Text(data.artistName,color=White.copy(alpha=.8f),fontSize=22.sp,
                        modifier=Modifier.padding(top=6.dp).then(if(data.artistId!=null) Modifier.clickable { uiHaptic(); model.openCollection(data.artistId,data.artistName)} else Modifier))
                    if(data.subtitle.isNotBlank() && data.subtitle.length<200) Text(data.subtitle,color=White.copy(alpha=.76f),
                        fontSize=16.sp,modifier=Modifier.padding(top=6.dp),maxLines=3)
                    if(!artist) collectionSummary(data.headerSongCount,songs,complete)?.let {
                        Text(it,color=White.copy(alpha=.76f),fontSize=16.sp,modifier=Modifier.padding(top=6.dp).testTag("collection_summary"))
                    }
                }
            }
        }
        if(!artist && tracks.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                FilledTonalIconButton({ uiHaptic(); play(tracks.indices.random(),true)},enabled=caps.playAll !is Capability.Unavailable,modifier=Modifier.size(52.dp).testTag("album_shuffle"),
                    colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=White.copy(alpha=.12f))) {Icon(Icons.Rounded.Shuffle,if(playlist) string(R.string.shuffle_playlist) else string(R.string.shuffle_album),tint=White)}
                Button({ uiHaptic(); play()},enabled=caps.playAll !is Capability.Unavailable,modifier=Modifier.weight(1f).height(56.dp).testTag("album_play"),shape=RoundedCornerShape(50),
                    colors=ButtonDefaults.buttonColors(containerColor=White,contentColor=tone)) {
                    Icon(Icons.Rounded.PlayArrow,null);Spacer(Modifier.width(8.dp));Text(string(R.string.play),fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                }
                FilledTonalIconButton({if(!caps.save.available) onNotice(caps.save.reason?:CHECKING_ACCOUNT_NOTICE) else scope.launch {
                    provider.saveAlbum(collectionId).fold(onSuccess={uiHaptic(HapticCue.Confirm);onNotice(if(playlist) string(R.string.playlist_saved_to_youtube_music) else string(R.string.album_saved_to_youtube_music))},onFailure={uiHaptic(HapticCue.Reject);onNotice(it.message?:string(R.string.could_not_save_collection))})
                }},modifier=Modifier.size(52.dp).testTag("album_save"),colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=White.copy(alpha=.12f))) {
                    Icon(Icons.Rounded.Add,if(playlist) string(R.string.save_playlist) else string(R.string.save_album),tint=White)
                }
            }
        }
        if(!artist && tracks.isNotEmpty()) item {
            OutlinedButton(onClick={uiHaptic();downloading=true;pendingDownload=true
                if(!complete) onNotice(string(R.string.download_will_start_once_all_songs_load))},enabled=!downloading && caps.downloadAll !is Capability.Unavailable,
                modifier=Modifier.fillMaxWidth().padding(horizontal=28.dp).testTag("collection_download_all"),shape=RoundedCornerShape(50)) {
                Icon(Icons.Rounded.Download,null);Spacer(Modifier.width(8.dp))
                Text(if(downloading && !complete) string(R.string.waiting_for_all_songs) else if(downloading) string(R.string.queuing_downloads) else string(R.string.download_all_songs),color=White)
            }
        }
        source.truncation?.let {cut->item {
            Text(cut.label,color=White.copy(alpha=.82f),fontSize=14.sp,modifier=Modifier.padding(horizontal=20.dp,vertical=8.dp).testTag("collection_truncated"))
        }}
        if(loadingError!=null) item {
            Column(Modifier.padding(horizontal=20.dp)) {
                Text(loadingError,color=Pink)
                TextButton(onClick=hapticClick(onRetry)) {Text(string(R.string.retry_loading_songs))}
            }
        }
        if(data.description.isNotBlank()) item {
            Column(Modifier.padding(horizontal=20.dp,vertical=12.dp)) {
                Text(data.description,color=White.copy(alpha=.78f),fontSize=16.sp,maxLines=if(expanded) Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis)
                TextButton({ uiHaptic(); expanded=!expanded}) {Text(if(expanded) string(R.string.less) else string(R.string.more),color=White)}
            }
        }
        if(!artist) item {CollectionTools(if(playlist) string(R.string.this_playlist) else string(R.string.this_album),query,{query=it},sort,{option->
            val endpoint=source.sortEndpoints[option]
            if(endpoint!=null) {
                sorting=true
                scope.launch {
                    try {provider.sortedCollection(collectionId,endpoint).fold(onSuccess={
                        sortedPage=it;remoteSort=option;sortName=option.name
                    },onFailure={onNotice(it.message?:string(R.string.could_not_sort_playlist))})} finally {sorting=false}
                }
            } else {sortedPage=null;remoteSort=null;sortName=option.name}
        },songs.any {it.addedAt!=null},playlist,source.sortEndpoints.keys,sorting || !caps.sort.available)}
        if(sorting) item {Text(string(R.string.sorting_playlist),color=Muted,modifier=Modifier.padding(horizontal=20.dp))}
        if(!complete) item {Text(string(R.string.loading_more_songs_x_so_far, songs.size),color=White.copy(alpha=.7f),fontSize=14.sp,
            modifier=Modifier.padding(horizontal=20.dp,vertical=8.dp).testTag("collection_loading_more"))}
        if(!artist && query.isNotBlank() && visible.isEmpty()) item {Text(string(R.string.no_songs_match_this_search),color=Muted,modifier=Modifier.padding(20.dp))}
        if(!artist) itemsIndexed(visible,key={_,entry->"album:${entry.index}:${entry.value.key()}"}) {_,entry->
            val index=entry.index;val card=entry.value
            var menu by remember {mutableStateOf(false)}
            Column(Modifier.padding(horizontal=20.dp)) {
                HorizontalDivider(color=White.copy(alpha=.1f))
                Row(Modifier.fillMaxWidth().heightIn(min=if(playlist) 58.dp else 48.dp).clickable { uiHaptic(); play(index)}.testTag("album_track_$index"),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(playback.state.mediaId=="yt:${card.videoId}") "\u2022" else if(provider.songLikes[card.videoId]==true) "★" else "",
                        color=White.copy(alpha=.7f),modifier=Modifier.width(if(playlist) 0.dp else 18.dp))
                    if(!playlist) Text("${index+1}",color=White.copy(alpha=.65f),fontSize=18.sp,modifier=Modifier.width(34.dp))
                    if(playlist) {
                        CatalogArtwork(card.artworkUrl,card.key(),Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).testTag("playlist_art_$index"))
                        Column(Modifier.weight(1f).padding(start=10.dp,top=6.dp,bottom=6.dp)) {
                            Text(card.title,color=White,fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(playerArtistLabel(tracks.getOrNull(index)?.artist?:card.subtitle),color=White.copy(alpha=.64f),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("playlist_artist_$index"))
                        }
                    } else Text(card.title,color=White,fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                    Box {IconButton({ uiHaptic(); menu=true}) {Icon(Icons.Rounded.MoreVert,string(R.string.song_options),tint=White.copy(alpha=.6f))}
                        SongActionMenu(menu,{menu=false}) {
                            card.track()?.let {DownloadSongAction(it,onNotice,onDismiss={menu=false});AddToPlaylistAction(it,{menu=false}) {playlistTrack=it}}
                            if(caps.removeEntries.available && card.setVideoId!=null) SongActionItem(string(R.string.remove_from_playlist_2)) {menu=false;removing=card}
                            SongActionItem("Play next",onClick={ menu=false;scope.launch {enqueueRealTrack(provider,playback,card,true,onNotice)}})
                            SongActionItem("Add to queue",onClick={ menu=false;scope.launch {enqueueRealTrack(provider,playback,card,false,onNotice)}})
                        }
                    }
                }
            }
        } else data.shelves.forEachIndexed {i,shelf->item(key="artist-shelf:$i") {
            Box(Modifier.padding(vertical=14.dp)) {HomeCardShelf(shelf.title,shelf.cards,154.dp) {card->
                openCatalogCard(model,card) {t->scope.launch {playRealTrack(provider,playback,t,onNotice)}}}
            }
        }}
    }
}
