package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 25: lyrics in Latin letters, from the source when it has them, otherwise made on the phone. */
class LyricsRomanizeTest {
    @Test fun aTtmlRomanizationIsKeptBesideTheSungLine() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
            <p begin="2s" end="4s"><span begin="2s" end="4s">तुम ही हो</span><span ttm:role="x-roman">tum hi ho</span></p>
            </div></body></tt>"""
        val line = parseTtml(ttml, 10_000)!!.lines.single()
        assertEquals("तुम ही हो", line.text.trim())
        assertEquals("tum hi ho", line.romanized)
    }

    @Test fun aTranslationIsStillLeftOut() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
            <p begin="2s" end="4s">こんにちは<span ttm:role="x-translation">hello</span></p></div></body></tt>"""
        val line = parseTtml(ttml, 10_000)!!.lines.single()
        assertEquals("こんにちは", line.text.trim())
        assertNull(line.romanized)
    }

    @Test fun onlyNonLatinScriptsNeedIt() {
        assertTrue(lyricNeedsRomanizing("तुम ही हो"))
        assertTrue(lyricNeedsRomanizing("ありがとう"))
        assertTrue(lyricNeedsRomanizing("사랑해"))
        assertTrue(lyricNeedsRomanizing("Привет"))
        assertFalse(lyricNeedsRomanizing("Je ne regrette rien"))
        assertFalse(lyricNeedsRomanizing("I'm 22 ♪"))
    }

    @Test fun theSourcesRomanizationWinsAndThePhoneFillsTheRest() {
        val phone: (String) -> String? = { "auto" }
        assertEquals("tum hi ho", lyricRomanized(LyricLine(0, 1, "तुम ही हो", romanized = "tum hi ho"), phone))
        assertEquals("auto", lyricRomanized(LyricLine(0, 1, "ありがとう"), phone))
        assertNull("a Latin line is already readable", lyricRomanized(LyricLine(0, 1, "Hello"), phone))
        assertNull("no ICU, no auto form", lyricRomanized(LyricLine(0, 1, "ありがとう")) { null })
    }

    @Test fun theChipShowsOnlyForLyricsWorthRomanizing() {
        assertTrue(lyricsCanRomanize(listOf(LyricLine(0, 1, "Hello"), LyricLine(1, 2, "사랑해"))))
        assertFalse(lyricsCanRomanize(listOf(LyricLine(0, 1, "Hello"))))
    }

    @Test fun theModesCycleAndAreReadBack() {
        assertEquals(LyricsRomanMode.Romanized, LyricsRomanMode.Original.next)
        assertEquals(LyricsRomanMode.Both, LyricsRomanMode.Romanized.next)
        assertEquals(LyricsRomanMode.Original, LyricsRomanMode.Both.next)
        assertEquals(LyricsRomanMode.Both, lyricsRomanModeFrom("both"))
        assertEquals(LyricsRomanMode.Original, lyricsRomanModeFrom(null))
    }

    @Test fun aRomanizationSurvivesTheCache() {
        val doc = LyricsDocument("Better Lyrics", "https://betterlyrics.org", listOf(LyricLine(0, 1000, "사랑해", romanized = "saranghae", knownEnd = true)))
        val back = decodeLyricsDocument(encodeLyricsDocument(doc))
        assertEquals("saranghae", back.lines.single().romanized)
        assertTrue(back.lines.single().knownEnd)
    }
}
