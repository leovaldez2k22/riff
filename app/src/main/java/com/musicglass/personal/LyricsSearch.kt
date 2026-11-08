package com.musicglass.personal

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/**
 * Feature 7 (owner, Q7): search by a remembered phrase in the lyrics Riff already holds: those saved with the
 * current account's downloads. Nothing is sent anywhere, and the phrase stays out of search history and suggestions.
 * This is not a search of the whole catalog: no online provider has shown coverage of unknown songs by their words.
 */
internal const val LYRICS_SEARCH_FILTER = "Lyrics"
internal const val LYRICS_SEARCH_MAX_HITS = 50
private const val LYRICS_SEARCH_MAX_SONGS = 2000

internal data class LyricsSearchHit(val track: ProviderTrack, val excerpt: String, val line: Int)
/** A song's lines, normalized for matching, beside the original text they came from. */
internal class LyricsIndexedSong(val track: ProviderTrack, val lines: List<String>, val keys: List<String>)

private val MARKS = Regex("\\p{M}+")
private val NOT_LETTER = Regex("[^\\p{L}\\p{N}]")
/** Case, accents, width, spaces and punctuation don't count: "Corazón," matches "corazon"; scripts without spaces still match. */
internal fun lyricsSearchKey(text: String): String = Normalizer.normalize(
    Normalizer.normalize(text, Normalizer.Form.NFKD).replace(MARKS, ""), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(NOT_LETTER, "")

/** Long enough to mean something: four letters, or two in a script whose letters are words or syllables. */
internal fun lyricsSearchUsable(key: String): Boolean = key.length >= 4 || (key.length >= 2 && lyricNeedsRomanizing(key))

internal fun lyricsIndexSong(track: ProviderTrack, doc: LyricsDocument?): LyricsIndexedSong? {
    val lines = doc?.lines.orEmpty().filter { !it.gap && it.text.isNotBlank() }.map { it.text.trim() }
    if (lines.isEmpty()) return null
    return LyricsIndexedSong(track, lines, lines.map(::lyricsSearchKey))
}

/** One hit a song, at its first line that holds the phrase, or the first two neighbouring lines that hold it between them. */
internal fun lyricsSearch(index: List<LyricsIndexedSong>, query: String): List<LyricsSearchHit> {
    val key = lyricsSearchKey(query)
    if (!lyricsSearchUsable(key)) return emptyList()
    return index.asSequence().mapNotNull { song ->
        song.keys.indexOfFirst { key in it }.takeIf { it >= 0 }?.let { LyricsSearchHit(song.track, song.lines[it], it) }
            ?: (0 until song.keys.size - 1).firstOrNull { key in song.keys[it] + song.keys[it + 1] }
                ?.let { LyricsSearchHit(song.track, song.lines[it] + " / " + song.lines[it + 1], it) }
    }.take(LYRICS_SEARCH_MAX_HITS).toList()
}

/**
 * The index lives in memory only and is rebuilt when the downloads change (a removal raises the store's revision)
 * or the account does; nothing derived from the lyrics is written to disk.
 */
internal object LyricsSearchIndex {
    private var built: Pair<String, List<LyricsIndexedSong>>? = null
    fun search(context: Context, query: String): List<LyricsSearchHit> {
        val store = OfflineMusicStore.get(context)
        val scope = store.currentScope
        val stamp = "$scope\u0000${store.revision.value}"
        val index = synchronized(this) { built?.takeIf { it.first == stamp }?.second } ?: store.shown(scope)
            .filter { it.status == DownloadState.Complete && it.lyricsState == "available" }
            .take(LYRICS_SEARCH_MAX_SONGS)
            .mapNotNull { lyricsIndexSong(it.track, store.lyrics(scope, it.track.videoId)) }
            .also { synchronized(this) { built = stamp to it } }
        return lyricsSearch(index, query)
    }
}
