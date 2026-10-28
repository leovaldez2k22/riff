package com.musicglass.personal

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants

/**
 * Feature 14: the library Android Auto browses. Four tabs (Home, Library, Downloads, Recent), each a list of
 * folders or songs; choosing a song plays its whole folder from that song, the same queue the phone would make.
 * Everything comes from the repositories the screens use, through [CarSource], so the tree can be tested alone.
 */
internal interface CarSource {
    val signedIn: Boolean
    fun online(): Boolean
    suspend fun home(): List<ProviderHomeSection>?
    /** "Playlists", "Albums" or "Artists" from the account's library. */
    suspend fun library(kind: String): List<ProviderHomeCard>?
    suspend fun liked(): List<ProviderTrack>?
    /** An album's or playlist's songs, or an artist's top songs. */
    suspend fun collection(browseId: String): List<ProviderTrack>?
    suspend fun downloadedSongs(): List<ProviderTrack>
    suspend fun recent(limit: Int): List<ProviderTrack>
    suspend fun search(query: String): List<ProviderTrack>?
}

/** What a choice in the car plays: these songs, from [index]. */
internal data class CarQueue(val tracks: List<ProviderTrack>, val index: Int)

internal const val CAR_ROOT = "riff:root"
internal const val CAR_HOME = "riff:home"
internal const val CAR_LIBRARY = "riff:library"
internal const val CAR_DOWNLOADS = "riff:downloads"
internal const val CAR_RECENT = "riff:recent"
private const val CAR_SECTION = "riff:home/"
private const val CAR_LIB = "riff:lib/"
private const val CAR_LIKED = "riff:lib/liked"
private const val CAR_COLLECTION = "riff:c/"
private const val CAR_SEARCH = "riff:search/"
private const val CAR_SIGN_IN = "riff:signin"
/** A song in a folder: `riff:t/<videoId>|<folder id>`. */
private const val CAR_TRACK = "riff:t/"
private const val CAR_KEEP_MS = 5 * 60_000L
internal const val CAR_RECENT_SIZE = 50
private const val CAR_SEARCH_SIZE = 50
private const val CAR_LIBRARY_KINDS = "Playlists,Albums,Artists"

internal class CarLibrary(private val source: CarSource, private val clock: () -> Long) {
    private class Folder(val at: Long, val items: List<MediaItem>, val tracks: List<ProviderTrack>)
    private val folders = java.util.concurrent.ConcurrentHashMap<String, Folder>()

    fun root(): MediaItem = folder(CAR_ROOT, string(R.string.app_name), grid = false)

    /** The children of [parentId], one page of them; null for an id that is not in the tree. */
    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem>? {
        val all = folderOf(parentId)?.items ?: return null
        if (page < 0 || pageSize <= 0) return all
        return all.drop((page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(pageSize)
    }

    suspend fun item(mediaId: String): MediaItem? = when (mediaId) {
        CAR_ROOT -> root()
        else -> tabs().firstOrNull { it.mediaId == mediaId }
            ?: trackParts(mediaId)?.let { (_, parent) -> folderOf(parent)?.items?.firstOrNull { it.mediaId == mediaId } }
    }

    /** Searches the catalog, songs first; the answer is kept for [searchResult]. Returns how many were found. */
    suspend fun search(query: String): Int = folderOf(CAR_SEARCH + query.trim())?.items?.size ?: 0

    suspend fun searchResult(query: String, page: Int, pageSize: Int) = children(CAR_SEARCH + query.trim(), page, pageSize).orEmpty()

    /**
     * The queue for what the car asked to play: a song from the tree plays its folder from that song; a spoken
     * request ("play … on Riff") plays the search's songs; an empty one plays what was heard last. Null when
     * nothing in the request is Riff's.
     */
    suspend fun queueFor(items: List<MediaItem>, startIndex: Int): CarQueue? {
        val chosen = items.getOrNull(startIndex.coerceAtLeast(0)) ?: items.firstOrNull() ?: return null
        val spoken = chosen.requestMetadata.searchQuery
        if (chosen.mediaId.isEmpty() && spoken != null) {
            val tracks = if (spoken.isBlank()) source.recent(CAR_RECENT_SIZE) else folderOf(CAR_SEARCH + spoken.trim())?.tracks.orEmpty()
            return tracks.takeIf { it.isNotEmpty() }?.let { CarQueue(it, 0) }
        }
        val (videoId, parent) = trackParts(chosen.mediaId) ?: return null
        val tracks = folderOf(parent)?.tracks.orEmpty()
        val index = tracks.indexOfFirst { it.videoId == videoId }
        return if (index >= 0) CarQueue(tracks, index) else null
    }

    private fun tabs() = listOf(
        folder(CAR_HOME, string(R.string.car_home), grid = true),
        folder(CAR_LIBRARY, string(R.string.car_library), grid = false),
        folder(CAR_DOWNLOADS, string(R.string.car_downloads), grid = false),
        folder(CAR_RECENT, string(R.string.car_recent), grid = false))

    private suspend fun folderOf(id: String): Folder? {
        if (id == CAR_ROOT) return Folder(clock(), tabs(), emptyList())
        folders[id]?.takeIf { clock() - it.at < CAR_KEEP_MS }?.let { return it }
        val loaded = load(id) ?: return folders[id]
        folders[id] = loaded
        return loaded
    }

    private fun songs(parent: String, tracks: List<ProviderTrack>, empty: Int? = null): Folder {
        val shown = tracks.filter { it.videoId.matches(PLAYABLE_ID) }.distinctBy { it.videoId }
        val items = shown.map { song(parent, it) }.ifEmpty { listOfNotNull(empty?.let { note(string(it)) }) }
        return Folder(clock(), items, shown)
    }

    private suspend fun load(id: String): Folder? = when {
        id == CAR_HOME -> if (!source.online()) songs(id, source.downloadedSongs(), R.string.car_no_downloads) else source.home()?.let { sections ->
            Folder(clock(), sections.withIndex().filter { (_, s) -> s.cards.any(::shown) }.map { (i, s) ->
                folder(CAR_SECTION + i, s.title, art = s.cards.firstOrNull()?.artworkUrl, grid = s.cards.none { it.track() != null }) }, emptyList())
        }
        id.startsWith(CAR_SECTION) -> source.home()?.getOrNull(id.removePrefix(CAR_SECTION).toIntOrNull() ?: -1)?.let { section ->
            val cards = section.cards.filter(::shown)
            val tracks = cards.mapNotNull { it.track() }
            Folder(clock(), cards.map { card -> card.track()?.let { song(id, it) } ?: collection(card) }, tracks)
        }
        id == CAR_LIBRARY -> if (!source.signedIn) Folder(clock(), listOf(note(string(R.string.car_sign_in))), emptyList()) else Folder(clock(),
            listOf(folder(CAR_LIKED, string(R.string.car_liked), grid = false)) +
                CAR_LIBRARY_KINDS.split(',').map { folder(CAR_LIB + it, libraryLabel(it), grid = true) }, emptyList())
        id == CAR_LIKED -> source.liked()?.let { songs(id, it) }
        id.startsWith(CAR_LIB) -> id.removePrefix(CAR_LIB).takeIf { it in CAR_LIBRARY_KINDS.split(',') }?.let { kind ->
            source.library(kind)?.let { cards -> Folder(clock(), cards.filter { it.browseId != null }.map(::collection), emptyList()) }
        }
        id.startsWith(CAR_COLLECTION) -> source.collection(id.removePrefix(CAR_COLLECTION))?.let { songs(id, it) }
        id == CAR_DOWNLOADS -> songs(id, source.downloadedSongs(), R.string.car_no_downloads)
        id == CAR_RECENT -> songs(id, source.recent(CAR_RECENT_SIZE))
        id.startsWith(CAR_SEARCH) -> source.search(id.removePrefix(CAR_SEARCH))?.let { songs(id, it.take(CAR_SEARCH_SIZE)) }
        else -> null
    }

    private fun libraryLabel(kind: String) = string(when (kind) { "Albums" -> R.string.car_albums; "Artists" -> R.string.car_artists; else -> R.string.car_playlists })

    /** Songs, albums, playlists and artists; podcasts and live radio are left to the phone. */
    private fun shown(card: ProviderHomeCard) = card.kind in setOf(CatalogKind.Song, CatalogKind.Album, CatalogKind.Playlist, CatalogKind.Artist) &&
        (card.track() != null || card.browseId != null) && !card.podcastLike()

    private fun collection(card: ProviderHomeCard) = folder(CAR_COLLECTION + card.browseId, card.title, card.subtitle, card.artworkUrl, grid = false,
        type = when (card.kind) { CatalogKind.Album -> MediaMetadata.MEDIA_TYPE_ALBUM; CatalogKind.Artist -> MediaMetadata.MEDIA_TYPE_ARTIST; else -> MediaMetadata.MEDIA_TYPE_PLAYLIST })

    private fun song(parent: String, track: ProviderTrack) = MediaItem.Builder().setMediaId("$CAR_TRACK${track.videoId}|$parent")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album.ifBlank { null })
            .setArtworkUri(carArtwork(track.artworkUrl)).setIsBrowsable(false).setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()

    private fun folder(id: String, title: String, subtitle: String? = null, art: String? = null, grid: Boolean,
        type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle?.ifBlank { null }).setArtworkUri(carArtwork(art))
            .setIsBrowsable(true).setIsPlayable(false).setMediaType(type).setExtras(Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, if (grid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }).build()).build()

    /** A line of text the car shows but can't open or play. */
    private fun note(text: String) = MediaItem.Builder().setMediaId(CAR_SIGN_IN)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(text).setIsBrowsable(false).setIsPlayable(false).build()).build()
}

private fun trackParts(mediaId: String): Pair<String, String>? {
    if (!mediaId.startsWith(CAR_TRACK)) return null
    val rest = mediaId.removePrefix(CAR_TRACK)
    val bar = rest.indexOf('|').takeIf { it > 0 } ?: return null
    return rest.substring(0, bar) to rest.substring(bar + 1)
}

/** The car loads artwork itself, over https, at about 400 px. */
private fun carArtwork(url: String?): Uri? = validProviderArtworkUrl(url)?.let { Uri.parse(highQualityArtworkUrl(it, CAR_ARTWORK_PX)) }
private const val CAR_ARTWORK_PX = 400

/** Root hints: Riff answers searches, and lists are the default style. */
internal fun carRootExtras() = Bundle().apply {
    putBoolean(CAR_SEARCH_SUPPORTED, true)
    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
}
/** androidx.media's BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED; Media3 has no constant for it. */
private const val CAR_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
