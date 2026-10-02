package com.musicglass.personal

internal fun ProviderTrack.homeCard() = ProviderHomeCard(title, artist, artworkUrl, videoId = videoId,
    kind = when { isLive -> CatalogKind.Live; !isMusic -> CatalogKind.Episode; else -> CatalogKind.Song },
    artistId = artistId, albumId = albumId, album = album)

/** Song-first mix: some Listen again picks, fresh/recent songs and at most three collections. */
internal fun homeSpeedDialCandidates(recent: List<ProviderHomeCard>, shelves: List<ProviderHomeSection>): List<ProviderHomeCard> {
    val listen = shelves.filter { it.title.contains("listen again", true) }.flatMap { it.cards }
        .filter { it.kind == CatalogKind.Song && it.videoId != null }.distinctBy { it.key() }.take(6)
    val listenKeys = shelves.filter { it.title.contains("listen again", true) }.flatMap { it.cards }.map { it.key() }.toSet()
    val other = shelves.filterNot { it.title.contains("listen again", true) }.flatMap { it.cards }
    val songs = (recent + other).filter { it.kind == CatalogKind.Song && it.videoId != null && it.key() !in listenKeys }
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
    return result
}
