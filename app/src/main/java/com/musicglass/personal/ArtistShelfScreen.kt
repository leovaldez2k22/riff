package com.musicglass.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

/** Load the next page when the list is this close to its end. */
internal const val SHELF_PREFETCH_ITEMS = 6

/**
 * Feature 8: an artist shelf's "See all": every album, single or video YouTube lists behind it, as a grid that
 * loads more as it scrolls. Song lists ("VL…") open as an ordinary collection instead (ShellModel.openArtistShelf).
 */
@Composable
internal fun ArtistShelfScreen(model: ShellModel, bottom: Dp, route: Route.ArtistShelf, provider: YouTubeMusicProvider,
    playback: PlaybackGateway, onNotice: (String) -> Unit) {
    val more = remember(route) { ProviderMoreEndpoint(route.browseId, route.params) }
    val scope = rememberCoroutineScope()
    var cards by remember(route) { mutableStateOf<List<ProviderHomeCard>>(emptyList()) }
    var next by remember(route) { mutableStateOf<String?>(null) }
    var loading by remember(route) { mutableStateOf(true) }
    var error by remember(route) { mutableStateOf<String?>(null) }
    var retry by remember(route) { mutableIntStateOf(0) }
    var tokens by remember(route) { mutableStateOf(PageTokens()) }

    suspend fun load(token: String?) {
        loading = true; error = null
        provider.artistShelfPage(more, token).fold(onSuccess = { page ->
            val seen = cards.mapTo(HashSet()) { it.key() }
            cards = cards + page.cards.filter { seen.add(it.key()) }
            next = tokens.next(page.next)
        }, onFailure = { error = it.message ?: string(R.string.collection_is_unavailable) })
        loading = false
    }
    LaunchedEffect(route, provider.authRevision, retry) { cards = emptyList(); next = null; tokens = PageTokens(); load(null) }

    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf {
        val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        last >= grid.layoutInfo.totalItemsCount - SHELF_PREFETCH_ITEMS
    } }
    LaunchedEffect(route) {
        snapshotFlow { nearEnd }.collect { if (it && !loading && next != null && error == null) load(next) }
    }

    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    fun open(card: ProviderHomeCard) = openCatalogCard(model, card) { scope.launch { playRealTrack(provider, playback, it, onNotice) } }
    LazyVerticalGrid(GridCells.Adaptive(RiffGrid.cell), state = grid,
        modifier = Modifier.fillMaxSize().testTag("artist_shelf_grid"),
        contentPadding = PaddingValues(start = RiffSpace.screenEdge, end = RiffSpace.screenEdge,
            top = statusInset + RiffSpace.tight, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(RiffSpace.rowGap),
        verticalArrangement = Arrangement.spacedBy(RiffSpace.block)) {
        item(span = { GridItemSpan(maxLineSpan) }) { BackHeading(route.title, model::back) }
        itemsIndexed(cards, key = { index, card -> "$index:${card.key()}" }) { _, card ->
            Column(Modifier.cardPeek(card, RiffRadius.card) { open(card) }.testTag("artist_shelf_card")) {
                CatalogArtwork(card.artworkUrl, card.key(), Modifier.fillMaxWidth()
                    .aspectRatio(if (card.kind == CatalogKind.Song) 16f / 9f else 1f).clip(RoundedCornerShape(RiffRadius.card)))
                Spacer(Modifier.height(RiffSpace.item))
                Text(card.title, color = White, fontSize = RiffType.body, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(card.subtitle, color = Muted, fontSize = RiffType.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                error != null -> Column {
                    Text(error.orEmpty(), color = Muted, fontSize = RiffType.body)
                    TextButton(onClick = hapticClick { if (cards.isEmpty()) retry++ else scope.launch { load(next) } },
                        modifier = Modifier.testTag("artist_shelf_retry")) { Text(string(R.string.try_again), color = Pink) }
                }
                loading -> ListSkeleton(string(R.string.loading_from_youtube_music), rows = if (cards.isEmpty()) 6 else 1)
                cards.isEmpty() -> Text(string(R.string.nothing_more_here), color = Muted, fontSize = RiffType.body)
                else -> Spacer(Modifier.height(RiffSpace.block))
            }
        }
    }
}

/** "Albums · Artist name", so the screen says whose albums they are; just the shelf's title when the artist is unknown. */
internal fun artistShelfTitle(shelfTitle: String, artist: String): String =
    if (artist.isBlank() || shelfTitle.contains(artist, ignoreCase = true)) shelfTitle else "$shelfTitle · $artist"
