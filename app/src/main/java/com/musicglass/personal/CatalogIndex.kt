package com.musicglass.personal


/** Searchable metadata only. The index never opens or bundles media files. */
internal data class CatalogEntry(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artwork: String? = null,
)

internal class CatalogIndex(entries: List<CatalogEntry>) {
    private data class Indexed(val position: Int, val title: String, val searchable: String)
    private val rows = entries.mapIndexed { position, entry ->
        Indexed(position, entry.title.lowercase(),
            "${entry.title} ${entry.artist} ${entry.album}".lowercase())
    }

    fun search(query: String, sort: String = "Relevance"): List<Int> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matches = if (words.isEmpty()) rows else rows.filter { row ->
            words.all(row.searchable::contains)
        }
        return (if (sort == "Title") matches.sortedWith(compareBy(Indexed::title,
            Indexed::position)) else matches).map(Indexed::position)
    }
}

