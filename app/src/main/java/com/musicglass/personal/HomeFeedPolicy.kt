package com.musicglass.personal

internal fun ProviderTrack.homeCard() = ProviderHomeCard(title, artist, artworkUrl, videoId = videoId,
    kind = when { isLive -> CatalogKind.Live; !isMusic -> CatalogKind.Episode; else -> CatalogKind.Song },
    artistId = artistId, albumId = albumId, album = album)

internal fun ProviderHomeCard.podcastLike() =
    kind in setOf(CatalogKind.Podcast, CatalogKind.Episode) || subtitle.contains("podcast", true)

internal fun listenAgainShelf(shelf: ProviderHomeSection) = shelf.title.contains("listen again", true)
internal fun homeDiscoveryShelves(shelves: List<ProviderHomeSection>) = shelves.filterNot(::listenAgainShelf)

/** Speed dial pages: three grids of nine. */
internal const val SPEED_DIAL_SIZE = 27

/** Pinned cards come first, in the order they were pinned; the rest of speed dial fills in behind them. */
internal fun withSpeedDialPins(pins: List<ProviderHomeCard>, candidates: List<ProviderHomeCard>) =
    (pins + candidates).distinctBy { it.key() }.take(SPEED_DIAL_SIZE)

/** Three pages of Listen again songs; a short shelf is topped up with other songs, never podcasts or collections. */
internal fun homeSpeedDialCandidates(recent: List<ProviderHomeCard>, shelves: List<ProviderHomeSection>): List<ProviderHomeCard> {
    fun ProviderHomeCard.playableSong() = kind == CatalogKind.Song && videoId != null && !podcastLike()
    val musicShelves = shelves.filterNot(::podcastShelf)
    val listen = musicShelves.filter(::listenAgainShelf).flatMap { it.cards }.filter { it.playableSong() }
    val other = recent + musicShelves.filterNot(::listenAgainShelf).flatMap { it.cards }
    return (listen + other.filter { it.playableSong() }).distinctBy { it.key() }.take(SPEED_DIAL_SIZE)
}
