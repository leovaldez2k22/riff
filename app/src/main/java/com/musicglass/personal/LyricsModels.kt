package com.musicglass.personal

import java.io.StringReader
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToLong
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import kotlinx.serialization.json.*

internal data class LyricsTrack(val mediaId: String, val title: String, val artist: String,
    val album: String, val durationMs: Long)
internal data class LyricPart(val startMs: Long, val endMs: Long, val from: Int, val to: Int)
internal data class LyricLine(val startMs: Long, val endMs: Long, val text: String,
    val parts: List<LyricPart> = emptyList())
internal data class LyricsDocument(val source: String, val sourceUrl: String,
    val lines: List<LyricLine>, val instrumental: Boolean = false) {
    val timed: Boolean get() = lines.any { it.startMs >= 0 }
    val wordTimed: Boolean get() = lines.any { it.parts.isNotEmpty() }
    val quality: Int get() = when { wordTimed -> 3; timed -> 2; instrumental -> 1; else -> 0 }
}

internal fun lyricsTimeMs(raw: String?): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val seconds = when {
        value.endsWith("ms") -> value.dropLast(2).toDoubleOrNull()?.div(1000)
        value.endsWith("s") -> value.dropLast(1).toDoubleOrNull()
        value.endsWith("m") -> value.dropLast(1).toDoubleOrNull()?.times(60)
        value.endsWith("h") -> value.dropLast(1).toDoubleOrNull()?.times(3600)
        else -> {
            val chunks = value.split(':')
            if (chunks.size > 3) return null
            var total = 0.0
            for (chunk in chunks) total = total * 60 + (chunk.toDoubleOrNull() ?: return null)
            total
        }
    } ?: return null
    return if (seconds.isFinite() && seconds >= 0 && seconds <= 86400) (seconds * 1000).roundToLong() else null
}

private fun xml(raw: String): org.w3c.dom.Document? = runCatching {
    if (raw.length > 1_000_000 || raw.contains("<!DOCTYPE", true) || raw.contains("<!ENTITY", true)) return null
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
        .parse(InputSource(StringReader(raw)))
}.getOrNull()

internal fun finishLyricLines(input: List<LyricLine>, durationMs: Long): List<LyricLine> {
    val sorted = input.filter { it.startMs >= 0 && it.startMs <= durationMs + 2000 }
        .sortedBy { it.startMs }.distinctBy { it.startMs to it.text }.take(2000)
    return sorted.mapIndexed { index, line ->
        val next = sorted.getOrNull(index + 1)?.startMs ?: durationMs
        val end = if (line.endMs > line.startMs) line.endMs.coerceAtMost(durationMs + 2000)
            else next.coerceAtLeast(line.startMs + 1)
        line.copy(endMs = end, parts = line.parts.filter {
            it.from >= 0 && it.to > it.from && it.to <= line.text.length &&
                it.startMs >= line.startMs && it.endMs > it.startMs && it.endMs <= end + 500
        })
    }
}

internal fun parseTtml(raw: String, durationMs: Long, source: String = "Better Lyrics"): LyricsDocument? {
    val doc = xml(raw) ?: return null
    val paragraphs = doc.getElementsByTagNameNS("*", "p")
    val lines = mutableListOf<LyricLine>()
    for (index in 0 until paragraphs.length.coerceAtMost(2000)) {
        val p = paragraphs.item(index) as Element
        val start = lyricsTimeMs(p.getAttribute("begin")) ?: continue
        val end = lyricsTimeMs(p.getAttribute("end")) ?: 0
        val text = StringBuilder()
        val parts = mutableListOf<LyricPart>()
        fun walk(node: Node, depth: Int = 0) {
            if (depth > 24) return
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                val value = node.nodeValue
                if (!(value.isBlank() && (value.contains('\n') || value.contains('\r')))) text.append(value)
                return
            }
            if (node is Element) {
                val role = node.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role")
                // Translation/romanization is not the sung text. Retain background vocals.
                if (role in setOf("x-translation", "x-roman", "x-romanization")) return
                if (node.localName == "br") { text.append('\n'); return }
            }
            val from = text.length
            val count = parts.size
            for (child in 0 until node.childNodes.length) walk(node.childNodes.item(child), depth + 1)
            if (node is Element && node.localName == "span" && parts.size == count && text.length > from) {
                val wordStart = lyricsTimeMs(node.getAttribute("begin"))
                val wordEnd = lyricsTimeMs(node.getAttribute("end"))
                    ?: wordStart?.let { a -> lyricsTimeMs(node.getAttribute("dur"))?.plus(a) }
                if (wordStart != null && wordEnd != null && wordEnd > wordStart)
                    parts += LyricPart(wordStart, wordEnd, from, text.length)
            }
        }
        walk(p)
        if (text.isNotBlank()) lines += LyricLine(start, end, text.toString(), parts)
    }
    val result = finishLyricLines(lines, durationMs)
    return result.takeIf { it.isNotEmpty() }?.let { LyricsDocument(source, "https://betterlyrics.org", it) }
}

internal fun parseLrc(raw: String, durationMs: Long, source: String,
    sourceUrl: String): LyricsDocument? {
    val tag = Regex("\\[(\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?)\\]")
    val offset = Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)
        .find(raw)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-60_000, 60_000) ?: 0
    val wordTag = Regex("<(\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?)>")
    val lines = mutableListOf<LyricLine>()
    for (row in raw.lineSequence().take(2000)) {
        val stamps = tag.findAll(row).toList()
        if (stamps.isEmpty()) continue
        val body = row.substring(stamps.last().range.last + 1)
        val wordStamps = wordTag.findAll(body).toList()
        val text = StringBuilder()
        val parts = mutableListOf<LyricPart>()
        if (wordStamps.isEmpty()) text.append(body.trim()) else {
            text.append(body.substring(0, wordStamps.first().range.first))
            for ((i, stamp) in wordStamps.withIndex()) {
                val next = wordStamps.getOrNull(i + 1)
                val from = text.length
                text.append(body.substring(stamp.range.last + 1, next?.range?.first ?: body.length))
                val start = lyricsTimeMs(stamp.groupValues[1])?.plus(offset) ?: continue
                val end = next?.let { lyricsTimeMs(it.groupValues[1]) }?.plus(offset)
                // A final unbounded word stays unhighlighted rather than receiving invented timing.
                if (end != null && end > start && text.length > from)
                    parts += LyricPart(start.coerceAtLeast(0), end, from, text.length)
            }
        }
        fun timeOf(value: String): Long? = lyricsTimeMs(if (value.count { it == ':' } == 2)
            value.substringBeforeLast(':') + "." + value.substringAfterLast(':') else value)
        val first = timeOf(stamps.first().groupValues[1]) ?: continue
        for (stamp in stamps) {
            val time = timeOf(stamp.groupValues[1]) ?: continue
            val shift = time - first
            lines += LyricLine((time + offset).coerceAtLeast(0), 0, text.toString(),
                parts.map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) })
        }
    }
    val result = finishLyricLines(lines, durationMs)
    return result.takeIf { rows -> rows.any { it.text.isNotBlank() } }
        ?.let { LyricsDocument(source, sourceUrl, it) }
}

internal fun parseQrc(raw: String, durationMs: Long, title: String = "", artist: String = ""): LyricsDocument? {
    // Preserve newlines in XML attributes: DOM would normalize literal line breaks to spaces.
    val attribute = Regex("LyricContent=\"([\\s\\S]*?)\"", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
    val body = if (attribute != null) {
        val safe = attribute.replace("\r", "&#13;").replace("\n", "&#10;")
        xml("<lyrics value=\"$safe\"/>")?.documentElement?.getAttribute("value") ?: return null
    } else raw
    val lineTag = Regex("^\\[(\\d+),(\\d+)\\]")
    val wordTag = Regex("\\((\\d+),(\\d+)\\)")
    val offset = Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)
        .find(body)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-60_000,60_000) ?: 0
    val lines = mutableListOf<LyricLine>()
    for (row in body.lineSequence().take(2000)) {
        val header = lineTag.find(row.trimStart()) ?: continue
        val rest = row.trimStart().substring(header.range.last + 1)
        val start = header.groupValues[1].toLongOrNull() ?: continue
        val length = header.groupValues[2].toLongOrNull() ?: continue
        val text = StringBuilder()
        val parts = mutableListOf<LyricPart>()
        var previous = 0
        for (token in wordTag.findAll(rest)) {
            val from = text.length
            text.append(rest.substring(previous, token.range.first))
            val a = token.groupValues[1].toLongOrNull() ?: continue
            val d = token.groupValues[2].toLongOrNull() ?: continue
            if (d > 0 && text.length > from) parts += LyricPart((a + offset).coerceAtLeast(0), a + offset + d, from, text.length)
            previous = token.range.last + 1
        }
        text.append(rest.substring(previous))
        val value = text.toString()
        if (Regex("^(lyrics?(?: by)?|composed(?: by)?|arranged(?: by)?|producer|作词|作曲|编曲|制作人)\\s*[:：]",RegexOption.IGNORE_CASE).containsMatchIn(value)) continue
        if (start < 15_000 && (lyricsNormalize(value) == lyricsNormalize(title) ||
            lyricsNormalize(value) == lyricsNormalize("$title $artist"))) continue
        if (value.isNotBlank()) lines += LyricLine((start + offset).coerceAtLeast(0),
            if (length > 0) start + offset + length else 0, value, parts)
    }
    return finishLyricLines(lines,durationMs).takeIf { it.isNotEmpty() }
        ?.let { LyricsDocument("Better Lyrics \u00B7 QQ", "https://betterlyrics.org", it) }
}

internal fun lyricsNormalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
internal fun lyricsQueryTitle(value: String): String = value
    .replace(Regex("\\s*[\\[(](?:official (?:music )?(?:video|audio)|lyrics?|lyric video|audio|visuali[sz]er)[\\])]", RegexOption.IGNORE_CASE), "")
    .trim()
internal fun lyricsMatchScore(track: LyricsTrack, title: String, artist: String, durationMs: Long): Double {
    val t = lyricsNormalize(lyricsQueryTitle(track.title))
    val a = lyricsNormalize(playerArtistLabel(track.artist).removeSuffix(" - Topic"))
    val rt = lyricsNormalize(lyricsQueryTitle(title))
    val ra = lyricsNormalize(artist)
    if (t.isEmpty() || a.isEmpty() || t != rt || a != ra) return 0.0
    if (track.durationMs > 0 && kotlin.math.abs(track.durationMs - durationMs) > 3000) return 0.0
    return 1.0 - (kotlin.math.abs(track.durationMs - durationMs).toDouble() / 100_000).coerceAtMost(.03)
}

internal fun startedLyricLine(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0; var high = lines.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (lines[mid].startMs <= positionMs) low = mid + 1 else high = mid
    }
    val index = low - 1
    return index.takeIf { it >= 0 && lines[it].startMs >= 0 } ?: -1
}
internal fun activeLyricLine(lines: List<LyricLine>, positionMs: Long): Int {
    val index = startedLyricLine(lines,positionMs)
    return index.takeIf { it >= 0 && positionMs < lines[it].endMs && lines[it].text.isNotBlank() } ?: -1
}

/** Mobile browse returns raw timedLyricsData rows; older clients wrap lyricLineRenderer. */
internal fun parseYoutubeLyrics(root: JsonElement, durationMs: Long): LyricsDocument? {
    val lines = mutableListOf<LyricLine>()
    var plain = ""
    fun line(row: JsonObject): LyricLine? {
        val range = row["cueRange"] as? JsonObject ?: return null
        val start = (range["startTimeMilliseconds"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?.takeIf { it >= 0 && it <= durationMs + 2000 } ?: return null
        val end = (range["endTimeMilliseconds"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
        return LyricLine(start, end, catalogText(row["lyricLine"]))
    }
    fun walk(node: JsonElement, depth: Int = 0) {
        if (depth > 32) return
        when (node) {
            is JsonObject -> {
                val data = node["timedLyricsData"] as? JsonArray
                if (data != null) {
                    val rows = data.take(2000).mapNotNull { it as? JsonObject }
                    val parsed = rows.map(::line)
                    // Never silently hide a non-timed verse from a mixed or malformed response.
                    if (rows.isNotEmpty() && parsed.all { it != null }) lines += parsed.filterNotNull()
                    else if (plain.isBlank()) plain = rows.joinToString("\n") { catalogText(it["lyricLine"]) }
                }
                (node["lyricLineRenderer"] as? JsonObject)?.let { line(it)?.let(lines::add) }
                (node["musicDescriptionShelfRenderer"] as? JsonObject)?.let {
                    val description = catalogText(it["description"])
                    if (description.isNotBlank()) plain = description
                }
                node.values.forEach { walk(it, depth + 1) }
            }
            is JsonArray -> node.forEach { walk(it, depth + 1) }
            else -> Unit
        }
    }
    walk(root)
    val timed = finishLyricLines(lines, durationMs)
    if (timed.any { it.text.isNotBlank() }) return LyricsDocument("YouTube Music", "https://music.youtube.com", timed)
    return plain.takeIf { it.isNotBlank() }?.let { LyricsDocument("YouTube Music", "https://music.youtube.com",
        it.lineSequence().take(2000).map { text -> LyricLine(-1, -1, text) }.toList()) }
}
