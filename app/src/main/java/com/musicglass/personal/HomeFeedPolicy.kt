package com.musicglass.personal

internal fun ProviderTrack.homeCard() = ProviderHomeCard(title, artist, artworkUrl, videoId = videoId,
    kind = when { isLive -> CatalogKind.Live; !isMusic -> CatalogKind.Episode; else -> CatalogKind.Song },
    artistId = artistId, albumId = albumId, album = album)

internal fun ProviderHomeCard.podcastLike() =
    kind in setOf(CatalogKind.Podcast, CatalogKind.Episode) || subtitle.contains("podcast", true)

internal fun listenAgainShelf(shelf: ProviderHomeSection) = shelf.title.contains("listen again", true)
internal fun homeDiscoveryShelves(shelves: List<ProviderHomeSection>) = shelves.filterNot(::listenAgainShelf)

/** Keep the current mix and include every Listen again song, without duplicate cards or podcasts. */
internal fun homeSpeedDialCandidates(recent: List<ProviderHomeCard>, shelves: List<ProviderHomeSection>): List<ProviderHomeCard> {
    val musicShelves = shelves.filterNot(::podcastShelf)
    val listen = musicShelves.filter(::listenAgainShelf).flatMap { it.cards }
        .filter { it.kind == CatalogKind.Song && it.videoId != null && !it.podcastLike() }.distinctBy { it.key() }
    val listenKeys = musicShelves.filter(::listenAgainShelf).flatMap { it.cards }.map { it.key() }.toSet()
    val other = musicShelves.filterNot(::listenAgainShelf).flatMap { it.cards }.filterNot { it.podcastLike() }
    val songs = (recent.filterNot { it.podcastLike() } + other).filter { it.kind == CatalogKind.Song && it.videoId != null && it.key() !in listenKeys }
        .distinctBy { it.key() }.toMutableList()
    val repeats = listen.toMutableList()
    val collections = other.filter { it.kind in setOf(CatalogKind.Album, CatalogKind.Playlist, CatalogKind.Artist) }
        .distinctBy { it.key() }.take(3).toMutableList()
    val result = mutableListOf<ProviderHomeCard>()
    while (result.size < 27) {
        val pool = when {
            result.size % 9 == 8 && collections.isNotEmpty() -> collections
            result.size % 3 == 2 && repeats.isNotEmpty() -> repeats
            songs.isNotEmpty() -> songs
            repeats.isNotEmpty() -> repeats
            collections.isNotEmpty() -> collections
            else -> break
        }
        val card = pool.removeAt(0)
        if (result.none { it.key() == card.key() }) result += card
    }
    return (result + listen).distinctBy { it.key() }
}
