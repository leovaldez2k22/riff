package com.musicglass.personal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** How many artists one page asks about: more than the lists show unopened, without walking a hundred. */
private const val ENCORE_PHOTO_LOOKUPS = 20

/** Asked once a run: an artist with no photo, or a lookup that failed, isn't asked again each time the page loads. */
private val encorePhotosTried = ConcurrentHashMap.newKeySet<String>()

/**
 * A play records no artist picture, so every artist row used to show the placeholder (5 Nov). Each top artist's photo
 * comes from their channel page when the key has the channel, or else from a signed-out artist search, and is saved
 * with the artist's rows. A map of lowercase names to photos, which fills in as they arrive.
 */
@Composable internal fun rememberEncoreArtistPhotos(summary: EncoreSummary?, scopes: List<String>, provider: YouTubeMusicProvider,
    process: AppContainer): Map<String, String> {
    var photos by remember(scopes) { mutableStateOf(emptyMap<String, String>()) }
    val artists = remember(summary) { summary?.let { current -> EncoreSort.entries.flatMap { current.top(EncoreKind.ARTIST, it) }.distinctBy(EncoreItem::key) }.orEmpty() }
    LaunchedEffect(artists, scopes) {
        artists.asSequence().filter { it.artwork == null }.take(ENCORE_PHOTO_LOOKUPS).forEach { artist ->
            val name = artist.title.lowercase(Locale.ROOT)
            if (name in photos) return@forEach
            val found = withContext(process.runtime.dispatchers.io) {
                suspendCatching { process.encore.artistPhoto(scopes, artist.title) }.getOrNull()
                    ?: if (!encorePhotosTried.add(name)) null else encoreLookUpPhoto(artist, provider)?.also { url ->
                        suspendCatching { process.encore.saveArtistPhoto(scopes, artist.title, url) }
                    }
            }
            if (found != null) photos = photos + (name to found)
        }
    }
    return photos
}

private suspend fun encoreLookUpPhoto(artist: EncoreItem, provider: YouTubeMusicProvider): String? {
    val channel = artist.key.takeIf { it.startsWith("id:") }?.removePrefix("id:")
    return channel?.let { provider.browseCatalog(it).getOrNull()?.artworkUrl } ?: provider.artistPhotoByName(artist.title).getOrNull()
}

/** The saved photo, or the one just found, or until then the cover of the artist's top song. */
internal fun List<EncoreItem>.withArtistPhotos(photos: Map<String, String>, songs: List<EncoreItem>): List<EncoreItem> = map { artist ->
    if (artist.artwork != null) artist else artist.copy(artwork = photos[artist.title.lowercase(Locale.ROOT)]
        ?: songs.firstOrNull { it.subtitle.contains(artist.title, ignoreCase = true) }?.artwork)
}
