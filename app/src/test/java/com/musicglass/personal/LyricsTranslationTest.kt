package com.musicglass.personal

import java.util.Locale
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Feature 1 (owner, Q1): translations the lyrics source gives, tied to their line by its key or its time and never
 * by guess. The fixtures copy the shape of Better Lyrics' TTML head (seen 8 Nov 2026) and NetEase's tlyric with
 * made-up words.
 */
class LyricsTranslationTest {
    private fun ttml(head: String, body: String, keyAttr: String = "lrc:key") = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:lang="ko"><head><metadata>$head</metadata></head><body><div>$body</div></body></tt>""".replace("KEY", keyAttr)
    private val twoLines = """<p begin="1s" end="2s" KEY="L1"><span begin="1s" end="2s">하늘</span></p><p begin="3s" end="4s" KEY="L2"><span begin="3s" end="4s">바다 노래</span></p>"""
    private val head = """<translations><translation type="subtitle" xml:lang="en-US"><text for="L2">Sea song</text><text for="L1">Sky</text></translation><translation type="subtitle" xml:lang="es"><text for="L1">Cielo</text></translation></translations><transliterations><transliteration xml:lang="ko-Latn"><text for="L1"><span begin="1s" end="2s">ha  neul</span></text></transliteration></transliterations>"""

    @Test fun headTranslationsJoinTheirLineByKeyInEitherNamespace() {
        for (attr in listOf("lrc:key", "itunes:key")) {
            val doc = parseTtml(ttml(head, twoLines, attr), 10_000)!!
            assertEquals(mapOf("en-US" to "Sky", "es" to "Cielo"), doc.lines[0].translations)
            assertEquals(mapOf("en-US" to "Sea song"), doc.lines[1].translations)
            assertEquals(listOf("en-US", "es"), doc.translationLanguages)
            // The head's transliteration fills a missing romanization, spaces tidied.
            assertEquals("ha neul", doc.lines[0].romanized)
            assertNull(doc.lines[1].romanized)
        }
    }

    @Test fun theSungTextAndItsTimingAreUntouched() {
        val translated = parseTtml(ttml(head, twoLines), 10_000)!!
        val plain = parseTtml(ttml("", twoLines), 10_000)!!
        assertEquals(plain.lines.map { it.copy(romanized = null) }, translated.lines.map { it.copy(translations = emptyMap(), romanized = null) })
        for (at in listOf(0L, 1_500L, 2_500L, 3_500L)) assertEquals(activeLyricLines(plain.lines, at), activeLyricLines(translated.lines, at))
    }

    @Test fun aKeySharedByTwoLinesOrMissingGivesNoTranslation() {
        val shared = """<p begin="1s" end="2s" lrc:key="L1">하늘</p><p begin="3s" end="4s" lrc:key="L1">바다</p><p begin="5s" end="6s">구름</p>"""
        val doc = parseTtml(ttml(head, shared), 10_000)!!
        assertTrue(doc.lines.all { it.translations.isEmpty() })
    }

    @Test fun inlineTranslationsNeedALanguageAndAreNeverSung() {
        val body = """<p begin="1s" end="2s">하늘<span ttm:role="x-translation" xml:lang="en">Sky</span></p><p begin="3s" end="4s">바다<span ttm:role="x-translation">Sea</span></p>"""
        val doc = parseTtml(ttml("", body), 10_000)!!
        assertEquals("하늘", doc.lines[0].text); assertEquals(mapOf("en" to "Sky"), doc.lines[0].translations)
        assertEquals("바다", doc.lines[1].text); assertTrue(doc.lines[1].translations.isEmpty())
    }

    @Test fun aTranslationTheSameAsTheLineAndBadLanguageTagsAreDropped() {
        val body = """<p begin="1s" end="2s" lrc:key="L1">Hello there</p>"""
        val same = """<translations><translation xml:lang="en"><text for="L1">Hello, there</text></translation><translation xml:lang="not a tag"><text for="L1">x</text></translation></translations>"""
        assertTrue(parseTtml(ttml(same, body), 10_000)!!.lines[0].translations.isEmpty())
    }

    @Test fun neteaseTranslationsJoinOnlyTheLineAtTheirTime() {
        val row = buildJsonObject {
            put("lrc", buildJsonObject { put("lyric", "[00:01.00]하늘\n[00:03.00]바다\n[00:03.10]바람\n[00:06.00]구름") })
            put("tlyric", buildJsonObject { put("lyric", "[00:01.05]天空\n[00:03.05]大海\n[00:06.00]云\n[00:06.10]云朵") })
        }
        val doc = netEaseLyrics(row, 10_000, "7")!!
        assertEquals(mapOf(NETEASE_TRANSLATION_LANGUAGE to "天空"), doc.lines[0].translations)
        // Two lines start within the window of 大海; two translations reach 구름: both stay empty.
        assertTrue(doc.lines[1].translations.isEmpty()); assertTrue(doc.lines[2].translations.isEmpty())
        assertTrue(doc.lines[3].translations.isEmpty())
        // Word-timed YRC lines take the translation at their own time.
        val yrc = buildJsonObject {
            put("yrc", buildJsonObject { put("lyric", "[1000,1000](1000,1000,0)하늘") })
            put("tlyric", buildJsonObject { put("lyric", "[00:01.20]天空") })
        }
        assertEquals("天空", netEaseLyrics(yrc, 10_000, "7")!!.lines.single().translations[NETEASE_TRANSLATION_LANGUAGE])
        // No tlyric, no translations.
        assertTrue(netEaseLyrics(buildJsonObject { put("lrc", buildJsonObject { put("lyric", "[00:01.00]하늘") }) }, 10_000, "7")!!
            .translationLanguages.isEmpty())
    }

    @Test fun savedLyricsKeepTranslationsAndOldFilesStillRead() {
        val doc = parseTtml(ttml(head, twoLines), 10_000)!!
        assertEquals(doc, decodeLyricsDocument(encodeLyricsDocument(doc)))
        val old = Json.parseToJsonElement("""{"source":"LRCLIB","url":"https://lrclib.net","lines":[{"start":1000,"end":2000,"text":"Hi","parts":[]}]}""").jsonObject
        assertTrue(decodeLyricsDocument(old).lines.single().translations.isEmpty())
        val odd = Json.parseToJsonElement("""{"source":"x","url":"https://x","lines":[{"start":1,"end":2,"text":"Hi","parts":[],"translations":{"en":"Yo","bad tag":"no","fr":""}}]}""").jsonObject
        assertEquals(mapOf("en" to "Yo"), decodeLyricsDocument(odd).lines.single().translations)
    }

    @Test fun theAppsLanguageComesFirstThenTheSourcesOrder() {
        val available = listOf("zh-Hans", "en-US", "es")
        assertEquals(listOf("en-US", "zh-Hans", "es"), lyricsTranslationOrder(available, listOf(Locale.UK)))
        assertEquals(listOf("es", "zh-Hans", "en-US"), lyricsTranslationOrder(available, listOf(Locale.forLanguageTag("es-MX"), Locale.CHINA)))
        assertEquals(available, lyricsTranslationOrder(available, listOf(Locale.GERMANY)))
    }
}
