package com.musicglass.personal

/**
 * The Better Lyrics endpoints answer by their own matching and give no score when they are sure, and they answer
 * "Everything Is Romantic (feat. caroline polachek)" with the song without its guest (5 Nov). LRCLIB, NetEase, LRC Red and
 * YouTube Music have checked the title or belong to the track itself.
 */
internal val LYRICS_UNCHECKED_SOURCES = setOf("Better Lyrics", "Better Lyrics \u00B7 QQ", "Better Lyrics \u00B7 Kugou")

private val GUEST_WORD = Regex("\\b(feat\\.?|ft\\.?|featuring)(?=\\s|$)", RegexOption.IGNORE_CASE)
private val LYRIC_WORD = Regex("[\\p{L}\\p{N}']+")
private val TITLE_SEPARATOR = Regex("\\s+[-\u2013\u2014]\\s+")
private val BRACKETED = Regex("[(\uFF08\\[][^)\uFF09\\]]*[)\uFF09\\]]")

/** Below this, two sets of lyrics are different versions: measured 5 Nov at 0.60 for the remix, 0.81 to 0.92 for five same songs. */
internal const val LYRICS_SAME_VERSION_SHARE = .70

internal fun lyricsTitleNamesGuest(title: String): Boolean = GUEST_WORD.containsMatchIn(title)

private fun lyricWords(doc: LyricsDocument): Set<String> =
    doc.lines.flatMap { line -> LYRIC_WORD.findAll(line.text.lowercase()).map { it.value }.toList() }.toSet()

/** The larger of the two shares, so a version that only adds a verse to the other still counts as the same. */
internal fun lyricsOtherVersion(a: LyricsDocument, b: LyricsDocument): Boolean {
    val first = lyricWords(a); val second = lyricWords(b)
    if (first.isEmpty() || second.isEmpty()) return false
    val shared = first.intersect(second).size.toDouble()
    return maxOf(shared / first.size, shared / second.size) < LYRICS_SAME_VERSION_SHARE
}

/** As [lyricsOutranks], except that for a title naming a guest a checked source decides between two different versions. */
internal fun lyricsReplaces(doc: LyricsDocument, shown: LyricsDocument?, shownForMs: Long, swapWindowMs: Long, guestTitle: Boolean): Boolean {
    if (guestTitle && shown != null && (doc.source in LYRICS_UNCHECKED_SOURCES) != (shown.source in LYRICS_UNCHECKED_SOURCES) &&
        lyricsOtherVersion(doc, shown)) return doc.source !in LYRICS_UNCHECKED_SOURCES
    return lyricsOutranks(doc, shown, shownForMs, swapWindowMs)
}

/** QQ opens some songs with a heading such as "Ribs - Lorde (\u6D1B\u5FB7)", timed as if it were sung. */
internal fun lyricsIsTitleLine(line: String, title: String, artist: String): Boolean {
    val wanted = lyricsNormalize(title.replace(BRACKETED, ""))
    val singer = lyricsNormalize(playerArtistLabel(artist).removeSuffix(" - Topic"))
    if (wanted.isEmpty() || singer.isEmpty()) return false
    return TITLE_SEPARATOR.findAll(line).any { separator ->
        val named = lyricsNormalize(line.substring(separator.range.last + 1).replace(BRACKETED, ""))
        lyricsNormalize(line.substring(0, separator.range.first).replace(BRACKETED, "")) == wanted &&
            named.isNotEmpty() && (singer.contains(named) || named.contains(singer))
    }
}

/** Copies saved before QQ's heading was left out lose it when they are read. */
internal fun LyricsDocument.withoutTitleLine(track: LyricsTrack): LyricsDocument {
    if (source != "Better Lyrics \u00B7 QQ") return this
    val kept = lines.filterNot { it.startMs < 15_000 && lyricsIsTitleLine(it.text, track.title, track.artist) }
    return if (kept.size == lines.size) this else copy(lines = kept)
}
