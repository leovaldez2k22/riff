package com.musicglass.desktop

import androidx.compose.ui.graphics.Color

/** Route for main workspace navigation. */
internal enum class WorkspaceRoute(val label: String) {
    Home("Home"),
    Discover("Discover"),
    Search("Search"),
    Library("Library"),
    Podcasts("Podcasts"),
    Radio("Radio"),
    AlbumDetail("Album"),
    ArtistDetail("Artist"),
    PlaylistDetail("Playlist"),
    FocusedPlayer("Now Playing"),
    Settings("Settings"),
    Encore("Encore")
}

/** Stable queue entry identity supporting duplicate tracks and deterministic reorder operations. */
internal data class QueueEntry(
    val queueId: String = java.util.UUID.randomUUID().toString(),
    val song: SongItem
)

/** Album container model. */
internal data class AlbumItem(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int,
    val dominantColor: Color,
    val tracks: List<SongItem>
)

/** Artist container model. */
internal data class ArtistItem(
    val id: String,
    val name: String,
    val monthlyListeners: String,
    val dominantColor: Color,
    val topTracks: List<SongItem>,
    val albums: List<String>
)

/** Playlist container model. */
internal data class PlaylistItem(
    val id: String,
    val name: String,
    val description: String,
    val dominantColor: Color,
    val tracks: List<SongItem>,
    val entryIds:List<String> = emptyList(),
    val trackCount:Long=tracks.size.toLong(),
    val entryOffset:Long=0
)

/** Podcast show model. */
internal data class PodcastItem(
    val id: String,
    val title: String,
    val publisher: String,
    val description: String,
    val episodeCount: Int,
    val dominantColor: Color
)

/** Radio station model. */
internal data class RadioStationItem(
    val id: String,
    val name: String,
    val genre: String,
    val dominantColor: Color,
    val seedTrack: SongItem
)

/** Open tab in the right-hand Inspector pane. */
internal enum class InspectorTab {
    None,
    Queue,
    Lyrics
}

/** Top-level desktop window display mode. */
internal enum class WindowMode {
    Main,
    MiniCompact,
    MiniExpanded
}

/** Repeat modes for playback. */
internal enum class RepeatMode {
    Off,
    All,
    One
}

/** Immutable display metadata; source identity is separate from queue occurrence identity. */
internal data class SongItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val dominantColor: Color,
    val deckMesh: IntArray,
    val lyrics: List<LyricLine>,
    val scope: String = "local",
    val sourcePath: String? = null,
    val artworkKey: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val available: Boolean = true,
    val artworkUrl:String?=null,
    val isEpisode:Boolean=false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SongItem) return false
        return id == other.id && title == other.title && artist == other.artist && album == other.album && durationMs == other.durationMs && dominantColor == other.dominantColor && deckMesh.contentEquals(other.deckMesh) && lyrics == other.lyrics && scope == other.scope && sourcePath == other.sourcePath && artworkKey == other.artworkKey && albumId == other.albumId && artistId == other.artistId && available == other.available && artworkUrl == other.artworkUrl && isEpisode==other.isEpisode
    }
    val playbackSource: String get() = sourcePath ?: id
    override fun hashCode(): Int = 31 * scope.hashCode() + id.hashCode()
}

/** Formats milliseconds as mm:ss or m:ss with tabular numeral spacing. */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

