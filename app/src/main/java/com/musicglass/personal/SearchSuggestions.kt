package com.musicglass.personal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Feature 24: what YouTube Music suggests for the words typed so far. Up to six searches to finish the words, and
 * up to three songs, artists or albums that match already. Asked 200 ms after the last key; each key cancels the
 * request before it. The setting "Search suggestions" turns them off (docs/privacy.md says what they send).
 */
internal data class SearchSuggestions(val queries: List<String> = emptyList(), val results: List<ProviderHomeCard> = emptyList()) {
    val isEmpty get() = queries.isEmpty() && results.isEmpty()
}

internal const val SEARCH_SUGGESTIONS_LIMIT = 6
internal const val SEARCH_QUICK_RESULTS_LIMIT = 3
internal const val SEARCH_SUGGESTIONS_DEBOUNCE_MS = 200L

/** Reads the suggestions response: the text section first, then the section of ready results. */
internal fun parseSearchSuggestions(root: JsonElement, typed: String): SearchSuggestions {
    val queries = linkedSetOf<String>()
    val resultNodes = mutableListOf<JsonElement>()
    fun visit(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                val suggestion = node["searchSuggestionRenderer"] as? JsonObject
                    ?: node["historySuggestionRenderer"] as? JsonObject
                if (suggestion != null) {
                    val query = catalogValue(suggestion["navigationEndpoint"], "query")
                        ?: catalogText(suggestion["suggestion"])
                    query.trim().takeIf { it.isNotEmpty() && !it.equals(typed.trim(), true) }?.let(queries::add)
                    return
                }
                if (node["musicResponsiveListItemRenderer"] != null) { resultNodes += node; return }
                node.values.forEach(::visit)
            }
            is JsonArray -> node.forEach(::visit)
            else -> Unit
        }
    }
    visit(root)
    return SearchSuggestions(queries.take(SEARCH_SUGGESTIONS_LIMIT),
        parseCatalogCards(JsonArray(resultNodes)).take(SEARCH_QUICK_RESULTS_LIMIT))
}

/**
 * The first result of an "All" search is shown as a large card when it is an artist or an album: YouTube puts its
 * top result first, and a song in that place already plays from its row.
 */
internal fun searchTopResult(cards: List<ProviderHomeCard>, filter: String): ProviderHomeCard? =
    cards.firstOrNull()?.takeIf { filter == "All" && it.kind in setOf(CatalogKind.Artist, CatalogKind.Album) }

private const val SUGGESTION_PREFS = "search_history"
internal fun searchSuggestionsOn(context: Context) = context.applicationContext
    .getSharedPreferences(SUGGESTION_PREFS, Context.MODE_PRIVATE).getBoolean("suggestions", true)
internal fun setSearchSuggestionsOn(context: Context, on: Boolean) {
    context.applicationContext.getSharedPreferences(SUGGESTION_PREFS, Context.MODE_PRIVATE).edit().putBoolean("suggestions", on).apply()
}

/** A suggested search. Tapping searches it; the arrow puts it in the field to keep typing. */
@Composable
internal fun SearchSuggestionRow(query: String, onSearch: () -> Unit, onFill: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = RiffSpace.touch).clickable { uiHaptic(); onSearch() }.testTag("search_suggestion_row"),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, tint = Muted)
        Text(query, color = White, fontSize = RiffType.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = RiffSpace.rowGap))
        IconButton({ uiHaptic(); onFill() }) { Icon(Icons.Rounded.NorthWest, string(R.string.put_x_in_search, query), tint = Muted) }
    }
}

/** YouTube's top result, when it is an artist or an album, drawn larger above the list. */
@Composable
internal fun SearchTopResultCard(card: ProviderHomeCard, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(RiffRadius.card)).background(Raised).clickable { uiHaptic(); onOpen() }
        .padding(RiffSpace.block).testTag("search_top_result"), verticalAlignment = Alignment.CenterVertically) {
        CatalogArtwork(card.artworkUrl, card.key(), Modifier.size(RiffTopResult.artwork)
            .clip(if (card.kind == CatalogKind.Artist) CircleShape else RoundedCornerShape(RiffRadius.artwork)))
        Column(Modifier.weight(1f).padding(start = RiffSpace.block)) {
            Text(string(R.string.top_result), color = Muted, fontSize = RiffType.detail)
            Text(card.title, color = White, fontSize = RiffTopResult.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(catalogRowLabel(card.kind, card.subtitle), color = Muted, fontSize = RiffType.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
