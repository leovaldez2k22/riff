package com.musicglass.personal

/**
 * Feature 25: lyrics in Latin letters, to sing along to songs in Hindi, Japanese, Korean, Chinese, Russian and
 * the like. A romanization the lyrics source gives (TTML role x-roman) is used first; otherwise Android's own ICU
 * transliteration makes one on the phone, which is mechanical and labelled "Auto".
 */
internal enum class LyricsRomanMode(val key: String) {
    Original("original"), Romanized("romanized"), Both("both");
    /** The next mode the lyrics page's chip switches to. */
    val next: LyricsRomanMode get() = entries[(ordinal + 1) % entries.size]
}

internal fun lyricsRomanModeFrom(key: String?): LyricsRomanMode = LyricsRomanMode.entries.firstOrNull { it.key == key } ?: LyricsRomanMode.Original

/** Whether [text] has letters outside the Latin script (Devanagari, kana, Hangul, Han, Cyrillic, …). */
internal fun lyricNeedsRomanizing(text: String): Boolean = text.any { c ->
    Character.isLetter(c) && Character.UnicodeScript.of(c.code) !in setOf(Character.UnicodeScript.LATIN,
        Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED)
}

/** Whether a document has any line worth romanizing; the chip shows only then. */
internal fun lyricsCanRomanize(lines: List<LyricLine>): Boolean = lines.any { it.romanized != null || lyricNeedsRomanizing(it.text) }

/** ICU's "anything to Latin, then plain letters": "नमस्ते" → "namaste", "ありがとう" → "arigatou". Null where ICU is missing. */
internal var lyricTransliterator: (String) -> String? = { text ->
    runCatching { android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII").transliterate(text) }.getOrNull()
}

/** The Latin form of a line: the source's, else the phone's for non-Latin text, else null (already Latin). */
internal fun lyricRomanized(line: LyricLine, transliterate: (String) -> String? = lyricTransliterator): String? =
    line.romanized ?: line.text.takeIf(::lyricNeedsRomanizing)?.let(transliterate)?.trim()?.takeIf(String::isNotBlank)
