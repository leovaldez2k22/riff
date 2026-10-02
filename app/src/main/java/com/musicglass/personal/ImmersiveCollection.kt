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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable internal fun ImmersiveCollectionDetail(model:ShellModel,bottom:Dp,data:CatalogPage,
    provider:YouTubeMusicProvider,playback:PlaybackGateway,onNotice:(String)->Unit) {
    val artist=model.selectedPlaylistId.startsWith("UC")
    val playlist=model.selectedPlaylistId.startsWith("VL")
    val scope=rememberCoroutineScope();val tone=rememberArtworkColor(data.artworkUrl).readableArtworkTone()
    val height=(LocalConfiguration.current.screenHeightDp*(if(artist) .5f else .57f)).dp
    var playlistTrack by remember {mutableStateOf<ProviderTrack?>(null)}
    playlistTrack?.let {PlaylistPicker(it,{playlistTrack=null},onNotice)}
    var removing by remember {mutableStateOf<ProviderHomeCard?>(null)}
    var removeBusy by remember {mutableStateOf(false)}
    removing?.let {card->AlertDialog(onDismissRequest={if(!removeBusy) removing=null},title={Text("Remove from playlist?")},text={Text(card.title)},
        confirmButton={TextButton(enabled=!removeBusy,onClick={removeBusy=true;scope.launch {provider.removePlaylistSong(model.selectedPlaylistId,card).fold(onSuccess={uiHaptic(HapticCue.Confirm);removing=null;onNotice("Removed from playlist");provider.refreshLibraryIndex()},onFailure={onNotice(it.message?:"Could not remove song")});removeBusy=false}}) {Text("Remove")}},
        dismissButton={TextButton(enabled=!removeBusy,onClick={removing=null}) {Text("Cancel")}})}
    var expanded by remember(model.selectedPlaylistId) {mutableStateOf(false)}
    val songs=if(data.albumTracks.isNotEmpty()) data.albumTracks else data.cards.filter {it.kind==CatalogKind.Song}
    var query by rememberSaveable(model.selectedPlaylistId) {mutableStateOf("")}
    var sortName by rememberSaveable(model.selectedPlaylistId) {mutableStateOf(CollectionSort.Original.name)}
    val sort=CollectionSort.valueOf(sortName)
    val visible=remember(songs,query,sort) {collectionView(songs,query,sort,{it.title},{it.subtitle},{it.addedAt})}
    val tracks=songs.mapNotNull {it.track()}.map {it.copy(albumId=if(playlist) it.albumId else model.selectedPlaylistId,album=if(playlist) it.album else data.title,
        artworkUrl=if(artist || playlist) it.artworkUrl else data.artworkUrl?:it.artworkUrl,artist=it.artist.ifBlank {if(playlist) "" else data.artistName},artistId=it.artistId?:data.artistId.takeUnless {playlist})}
    fun play(index:Int,shuffle:Boolean=false) {
        if(tracks.isEmpty()) return
        playback.playCollection(tracks,index,data.title,shuffle)
    }
    LazyColumn(Modifier.fillMaxSize().background(tone).testTag(if(artist) "artist_detail" else if(playlist) "playlist_detail" else "album_detail"),
        contentPadding=PaddingValues(bottom=bottom)) {
        item {
            Box(Modifier.fillMaxWidth().height(height)) {
                CatalogArtwork(data.artworkUrl,model.selectedPlaylistId,Modifier.fillMaxSize().testTag("collection_artwork"))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha=.18f),.46f to Color.Transparent,1f to tone)))
                Box(Modifier.statusBarsPadding().padding(horizontal=16.dp)) {BackHeading("Back",model::back)}
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
                    horizontalAlignment=if(artist) Alignment.Start else Alignment.CenterHorizontally) {
                    Text(data.title.ifBlank {model.selectedCollectionTitle},color=White,fontSize=if(artist) 34.sp else 28.sp,
                        fontWeight=FontWeight.SemiBold,maxLines=3,overflow=TextOverflow.Ellipsis)
                    if(!artist && data.artistName.isNotBlank()) Text(data.artistName,color=White.copy(alpha=.8f),fontSize=22.sp,
                        modifier=Modifier.padding(top=6.dp).then(if(data.artistId!=null) Modifier.clickable { uiHaptic(); model.openCollection(data.artistId,data.artistName)} else Modifier))
                    if(data.subtitle.isNotBlank() && data.subtitle.length<200) Text(data.subtitle,color=White.copy(alpha=.76f),
                        fontSize=16.sp,modifier=Modifier.padding(top=6.dp),maxLines=3)
                }
            }
        }
        if(!artist && tracks.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                FilledTonalIconButton({ uiHaptic(); play(tracks.indices.random(),true)},modifier=Modifier.size(52.dp),
                    colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=White.copy(alpha=.12f))) {Icon(Icons.Rounded.Shuffle,if(playlist) "Shuffle playlist" else "Shuffle album",tint=White)}
                Button({ uiHaptic(); play(0)},modifier=Modifier.weight(1f).height(56.dp).testTag("album_play"),shape=RoundedCornerShape(50),
                    colors=ButtonDefaults.buttonColors(containerColor=White,contentColor=tone)) {
                    Icon(Icons.Rounded.PlayArrow,null);Spacer(Modifier.width(8.dp));Text("Play",fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                }
                FilledTonalIconButton({scope.launch {
                    provider.saveAlbum(model.selectedPlaylistId).fold(onSuccess={uiHaptic(HapticCue.Confirm);onNotice(if(playlist) "Playlist saved to YouTube Music" else "Album saved to YouTube Music")},onFailure={uiHaptic(HapticCue.Reject);onNotice(it.message?:"Could not save collection")})
                }},modifier=Modifier.size(52.dp).testTag("album_save"),colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=White.copy(alpha=.12f))) {
                    Icon(Icons.Rounded.Add,if(playlist) "Save playlist" else "Save album",tint=White)
                }
            }
        }
        if(data.description.isNotBlank()) item {
            Column(Modifier.padding(horizontal=20.dp,vertical=12.dp)) {
                Text(data.description,color=White.copy(alpha=.78f),fontSize=16.sp,maxLines=if(expanded) Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis)
                TextButton({ uiHaptic(); expanded=!expanded}) {Text(if(expanded) "Less" else "More",color=White)}
            }
        }
        if(!artist) item {CollectionTools(if(playlist) "this playlist" else "this album",query,{query=it},sort,{sortName=it.name},songs.any {it.addedAt!=null})}
        if(!artist && query.isNotBlank() && visible.isEmpty()) item {Text("No songs match this search.",color=Muted,modifier=Modifier.padding(20.dp))}
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
                    Box {IconButton({ uiHaptic(); menu=true}) {Icon(Icons.Rounded.MoreVert,"Song options",tint=White.copy(alpha=.6f))}
                        SongActionMenu(menu,{menu=false}) {
                            card.track()?.let {DownloadSongAction(it,onNotice,onDismiss={menu=false});AddToPlaylistAction(it,{menu=false}) {playlistTrack=it}}
                            if(playlist && data.editable && card.setVideoId!=null) SongActionItem("Remove from playlist") {menu=false;removing=card}
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
