package com.musicglass.personal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LYR02 and UX06 (Q18-034): a translation that fails or is cancelled, at the model download or part-way through the
 * lines, leaves the original timed lyrics as they were, shows no English and caches nothing as translated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LyricsTranslationTest {
    @get:Rule val folder = TemporaryFolder()

    private val lines = listOf(
        LyricLine(1_000, 2_400, "Hola mundo", listOf(LyricPart(1_000, 1_600, 0, 5), LyricPart(1_600, 2_400, 5, 10))),
        LyricLine(2_400, 4_000, "Buenas noches", listOf(LyricPart(2_400, 3_100, 0, 7), LyricPart(3_100, 4_000, 7, 13))),
        LyricLine(4_000, 5_500, "Hola mundo"),
    )
    private val original = lines.toList()

    private class Fake(val prepare: suspend () -> Unit = {}, val line: suspend (String) -> String = { "EN $it" }) : LineTranslator {
        var closed = false; val asked = mutableListOf<String>()
        override suspend fun prepare() = prepare.invoke()
        override suspend fun translate(text: String): String { asked += text; return line(text) }
        override fun close() { closed = true }
    }

    private fun cacheFiles() = folder.root.listFiles()?.toList().orEmpty()
    private fun assertNothingCachedAndLinesUntouched() {
        assertEquals("Nothing is cached as translated", emptyList<java.io.File>(), cacheFiles())
        assertEquals("The timed lines, their timing and their words are as they were", original, lines)
    }

    @Test fun aCompleteTranslationIsLineAlignedAndCachedOnce() = runBlocking {
        val fake = Fake()
        val english = translateLyricLines(folder.root, "es", lines, {}) { fake }
        assertEquals(listOf("EN Hola mundo", "EN Buenas noches", "EN Hola mundo"), english)
        assertEquals("A repeated line is translated once", listOf("Hola mundo", "Buenas noches"), fake.asked)
        assertTrue(fake.closed)
        assertEquals(1, cacheFiles().size)
        assertEquals("The second time comes from the cache, without a model", english,
            translateLyricLines(folder.root, "es", lines, {}) { error("no model needed") })
    }

    @Test fun aModelDownloadRefusedOfflineCachesNothing() = runBlocking {
        val fake = Fake(prepare = { throw IOException("Model download needs a connection") })
        val failure = runCatching { translateLyricLines(folder.root, "es", lines, {}) { fake } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue("The model is closed", fake.closed)
        assertEquals("No line was sent", emptyList<String>(), fake.asked)
        assertNothingCachedAndLinesUntouched()
    }

    @Test fun aLineThatFailsPartWayCachesNothing() = runBlocking {
        val fake = Fake(line = { if (it == "Buenas noches") throw IllegalStateException("Translator failed") else "EN $it" })
        assertTrue(runCatching { translateLyricLines(folder.root, "es", lines, {}) { fake } }.exceptionOrNull() is IllegalStateException)
        assertTrue(fake.closed)
        assertNothingCachedAndLinesUntouched()
    }

    @Test fun aTranslationCancelledPartWayCachesNothing() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val never = CompletableDeferred<String>()
        val fake = Fake(line = { if (it == "Buenas noches") { reached.complete(Unit); never.await() } else "EN $it" })
        val job = async { translateLyricLines(folder.root, "es", lines, {}) { fake } }
        reached.await(); job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertTrue(fake.closed)
        assertNothingCachedAndLinesUntouched()
    }

    // ---- what the panel shows

    @Test fun aFailedTranslationShowsNoEnglishAndSaysWhy() = runBlocking {
        val run = LyricTranslationRun { _, _, status -> status("Translating 2 of 3 lines"); throw IllegalStateException("Translation unavailable. Try again.") }
        run.run(true, lines, null)
        assertEquals(emptyList<String>(), run.english)
        assertEquals("Translation unavailable. Try again.", run.status)
        assertEquals(original, lines)
    }

    @Test fun aCancelledTranslationShowsNoEnglishAndNoStaleProgress() = runBlocking {
        val run = LyricTranslationRun { _, _, status -> status("Translating 2 of 3 lines"); yield(); throw CancellationException("Back") }
        assertTrue(runCatching { run.run(true, lines, null) }.exceptionOrNull() is CancellationException)
        assertEquals(emptyList<String>(), run.english)
        assertEquals("No progress line is left behind", "", run.status)
        assertEquals(original, lines)
    }

    @Test fun anEarlierEnglishLayerIsDroppedWhenTheNextTryFails() = runBlocking {
        var fail = false
        val run = LyricTranslationRun { l, _, _ -> if (fail) throw IOException("offline") else l.map { "EN ${it.text}" } }
        run.run(true, lines, null); assertEquals(3, run.english.size)
        fail = true; run.run(true, lines, "es")
        assertEquals("A changed source language that fails shows no old English under new lines", emptyList<String>(), run.english)
        run.run(false, lines, null); assertEquals("", run.status); assertEquals(emptyList<String>(), run.english)
    }
}
