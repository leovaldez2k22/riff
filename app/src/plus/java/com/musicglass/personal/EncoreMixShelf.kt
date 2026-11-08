package com.musicglass.personal

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The mixes last for the day and the part of it (Q13: rebuilt on opening at most daily; Time of day follows the
 * clock). The key also holds the owner and where their history starts, so a cleared history or another account
 * rebuilds them. Only the derived song lists are kept, in memory.
 */
internal object EncoreMixCache {
    private var key: String? = null
    private var mixes: List<EncoreMix> = emptyList()

    fun cacheKey(scopes: List<String>, today: LocalDate, part: DayPart, historyStart: LocalDate?, genresOn: Boolean) =
        "${scopes.joinToString("|")}#$today#$part#$historyStart#$genresOn"

    @Synchronized fun get(key: String): List<EncoreMix>? = mixes.takeIf { this.key == key }
    @Synchronized fun put(key: String, value: List<EncoreMix>) { this.key = key; mixes = value }
    @Synchronized fun clear() { key = null; mixes = emptyList() }
}

internal fun buildEncoreMixes(context: Context, process: AppContainer, scopes: List<String>, today: LocalDate, hour: Int): List<EncoreMix> {
    val zone = ZoneId.systemDefault()
    val genresOn = encoreGenresOn(context)
    val key = EncoreMixCache.cacheKey(scopes, today, DayPart.of(hour), process.encore.historyStart(scopes), genresOn)
    EncoreMixCache.get(key)?.let { return it }
    val never = NeverPlayStore.get(context).set(scopes.first())
    val mixes = EncoreMixer(process.libraryState.readableDatabase, scopes, today, hour, zone, { names -> process.encoreGenres.cached(names) },
        { track -> never.blocks(track.videoId, null) }).build()
    EncoreMixCache.put(key, mixes)
    return mixes
}

/** Feature 13 (Riff+): "Made from your listening", below speed dial on Home; nothing while there is too little history. */
@Composable internal fun EncoreMixShelf(provider: YouTubeMusicProvider, playback: PlaybackGateway) {
    val context = LocalContext.current
    val process = remember(context) { AppContainer.get(context) }
    val accountKey = remember(provider.authRevision, provider.storageRevision) { provider.storageScopeKey() }
    val scopes = remember(accountKey) { listOfNotNull(accountKey.takeIf { it != UNVERIFIED_SCOPE_KEY }, DEVICE_LOCAL_SCOPE_KEY) }
    var mixes by remember(scopes) { mutableStateOf<List<EncoreMix>>(emptyList()) }
    LaunchedEffect(scopes) {
        mixes = withContext(Dispatchers.IO) { suspendCatching { buildEncoreMixes(context, process, scopes, LocalDate.now(), LocalTime.now().hour) }.getOrDefault(emptyList()) }
    }
    if (mixes.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("encore_mixes"), verticalArrangement = Arrangement.spacedBy(RiffSpace.rowGap)) {
        Text(string(R.string.mix_shelf), color = White, style = RiffText.sectionHeading, modifier = Modifier.padding(horizontal = RiffSpace.screenEdge))
        LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = RiffSpace.screenEdge), horizontalArrangement = Arrangement.spacedBy(RiffSpace.rowGap)) {
            items(mixes, key = { it.id }) { mix -> MixCard(mix) { uiHaptic(); playback.playCollection(mix.tracks, 0, mix.title) } }
        }
    }
}

@Composable private fun MixCard(mix: EncoreMix, onPlay: () -> Unit) {
    val spoken = string(R.string.mix_play, mix.title, mix.tracks.size)
    Column(Modifier.width(RiffMixes.card).clip(RoundedCornerShape(RiffRadius.card)).clickable(onClick = onPlay).semantics { contentDescription = spoken }.testTag("encore_mix")) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(RiffRadius.card))) {
            // Four covers from the mix, so cards differ even when one song leads two of them.
            val covers = mix.tracks.map { it.artworkUrl to it.videoId }.distinctBy { it.first }.take(4)
            if (covers.size < 4) CatalogArtwork(covers.first().first, covers.first().second, Modifier.fillMaxSize())
            else Column(Modifier.fillMaxSize()) {
                covers.chunked(2).forEach { row -> androidx.compose.foundation.layout.Row(Modifier.weight(1f)) {
                    row.forEach { (url, key) -> CatalogArtwork(url, key, Modifier.weight(1f).fillMaxSize()) } } }
            }
        }
        Spacer(Modifier.height(RiffSpace.item))
        Text(mix.title, color = White, fontSize = RiffMixes.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(mix.detail, color = Muted, fontSize = RiffType.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
