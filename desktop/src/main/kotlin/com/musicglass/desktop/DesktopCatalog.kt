package com.musicglass.desktop

import androidx.compose.runtime.staticCompositionLocalOf

/** Genuine imported metadata and provider search results; no built-in catalog records. */
internal class DesktopCatalog(val all:List<SongItem> = emptyList(), val searchResults:List<SongItem> = emptyList(), val playlists:List<PlaylistItem> = emptyList(),detailTracks:List<SongItem> = emptyList(),detailKind:String?=null,detailId:String?=null) {
    private val albumTracks=if(detailKind=="album")all.filterNot{it.albumId==detailId}+detailTracks else all
    val albums:List<AlbumItem> = albumTracks.filter{it.album.isNotBlank()}.groupBy{it.album to it.artist}.map{(key,tracks)->
        AlbumItem(tracks.first().albumId ?: "album:${key.first}:${key.second}",key.first,key.second,0,tracks.first().dominantColor,tracks)
    }
    private val artistTracks=if(detailKind=="artist")all.filterNot{it.artistId==detailId}+detailTracks else all
    val artists:List<ArtistItem> = artistTracks.filter{it.artist.isNotBlank()&&it.artist!="Unknown artist"}.groupBy{it.artist}.map{(name,tracks)->
        ArtistItem(tracks.first().artistId ?: "artist:$name",name,"",tracks.first().dominantColor,tracks,albums.filter{it.artist==name}.map{it.id})
    }
    val podcasts:List<PodcastItem> = emptyList()
    val radioStations:List<RadioStationItem> = emptyList()
}
internal val LocalDesktopCatalog = staticCompositionLocalOf { DesktopCatalog() }