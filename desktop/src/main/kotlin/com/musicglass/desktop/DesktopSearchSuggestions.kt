package com.musicglass.desktop

import kotlinx.serialization.json.*

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
    var nodes=0
    fun visit(node: JsonElement,depth:Int=0) {
        require(depth<=32&&++nodes<=10000){"Suggestions response exceeds bounds"}
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
                node.values.forEach{visit(it,depth+1)}
            }
            is JsonArray -> node.forEach{visit(it,depth+1)}
            else -> Unit
        }
    }
    visit(root)
    return SearchSuggestions(queries.take(SEARCH_SUGGESTIONS_LIMIT),
        parseCatalogCards(JsonArray(resultNodes)).take(SEARCH_QUICK_RESULTS_LIMIT))
}

