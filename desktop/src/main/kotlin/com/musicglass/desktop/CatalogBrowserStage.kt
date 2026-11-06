package com.musicglass.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.musicglass.data.*

@Composable internal fun CatalogBrowserStage(font:FontFamily){
    val host=LocalDesktopHost.current?:return
    val snapshot by host.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Label(snapshot.catalogTitle,font,Color.White,size=34)}
        when(val state=snapshot.catalogPage){
            PageState.Loading->item{Label("Loading catalog…",font,RiffDesign.muted)}
            PageState.Empty->item{Label("The provider returned no items for this page.",font,RiffDesign.muted)}
            PageState.NotFound->item{Label("This collection is unavailable.",font,RiffDesign.muted)}
            is PageState.Unsupported->item{Column{Label(state.reason,font,RiffDesign.muted);DataAction("Search public YouTube music",font){host.dispatch(DesktopCommand.BrowseProvider(query="music"))}}}
            is PageState.Failed->item{Column{Label(state.message,font,RiffDesign.muted);DataAction("Retry",font){host.dispatch(snapshot.catalogRequest)}}}
            is PageState.Ready->{
                if(snapshot.catalogRequest.id!="FEmusic_home" && snapshot.catalogRequest.query==null)item{
                    val playable=state.items.filter{it.videoId!=null}.map{it.song()}
                    if(playable.isNotEmpty())DataAction("Play loaded tracks (${playable.size})",font){host.dispatch(DesktopCommand.PlayCollection(playable))}
                }
                if(state.cached)item{Label("Offline · showing a cached provider page",font,RiffDesign.muted)}
                itemsIndexed(state.items){_,card->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha=.05f)).clickable{
                        if(card.videoId!=null)host.dispatch(DesktopCommand.PlayTrack(card.song()))
                        else card.browseId?.let{host.dispatch(DesktopCommand.BrowseProvider(it))}
                    }.padding(14.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                        val song=card.song()
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).trackArtwork(song))
                        Column(Modifier.weight(1f)){Label(card.title,font,Color.White,size=16);Label("${card.kind.name} · ${card.subtitle}",font,RiffDesign.muted,size=13)}
                        if(card.videoId!=null)DataAction("Radio",font){host.dispatch(DesktopCommand.BrowseProvider(radioVideoId=card.videoId))}
                    }
                }
                if(state.cursor!=null)item{DataAction("Load more",font){host.dispatch(snapshot.catalogRequest.copy(cursor=state.cursor))}}
            }
        }
    }
}
internal fun ProviderHomeCard.song()=SongItem(videoId?.let{"youtube:$it"}?:browseId.orEmpty(),title,subtitle,album,durationMs?:0,Color(0xFF181A22),IntArray(36){0xFF181A22.toInt()},emptyList(),scope=Scopes.GUEST,albumId=albumId,artistId=artistId,artworkUrl=artworkUrl,isEpisode=kind==CatalogKind.Episode)
