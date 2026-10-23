package com.musicglass.personal
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LyricsPreloadTest {
    private val track=LyricsTrack("yt:abcdefghijk","Morning Path","Paper Birds","",20000)
    @Test fun playbackPreloadAndMultipleOpeningsShareOneRequest() = runBlocking {
        val directory=Files.createTempDirectory("lyrics-preload").toFile();val calls=AtomicInteger()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val repo=LyricsRepository(directory) {url,_->if(url=="https://api.betterlyrics.org/getLyrics") calls.incrementAndGet();entered.complete(Unit);release.await()
            buildJsonObject {put("ttml","<tt xmlns='http://www.w3.org/ns/ttml'><body><div><p begin='2s' end='5s'><span begin='2s' end='3s'>Morning </span><span begin='3s' end='5s'>path</span></p></div></body></tt>")}}
        try {
            repo.preload(track) {error("Better Lyrics has priority")};withTimeout(2000){entered.await()}
            val a=repo.observe(track) {null};val b=repo.observe(track) {null};assertSame(a,b)
            assertTrue(a.value.loading);release.complete(Unit)
            val ready=withTimeout(3000){a.first {!it.loading}};assertTrue(ready.document!!.timed);assertFalse(ready.failed)
            assertSame(a,repo.observe(track) {null});assertEquals(1,calls.get())
        } finally {repo.close();directory.listFiles().orEmpty().forEach {it.delete()};directory.delete()}
    }
    @Test fun forcedRetryCancelsThePreviousGeneration() = runBlocking {
        val directory=Files.createTempDirectory("lyrics-retry").toFile();val calls=AtomicInteger();val entered=CompletableDeferred<Unit>()
        val repo=LyricsRepository(directory) {_,_->if(calls.incrementAndGet()==1) {entered.complete(Unit);awaitCancellation()}
            buildJsonObject {put("ttml","<tt xmlns='http://www.w3.org/ns/ttml'><body><div><p begin='2s' end='5s'>New result</p></div></body></tt>")}}
        try {
            repo.preload(track) {null};withTimeout(2000){entered.await()}
            val result=repo.observe(track,force=true) {null};val ready=withTimeout(3000){result.first {!it.loading}}
            assertEquals(2L,ready.generation);assertEquals("New result",ready.document!!.lines.single().text);assertFalse(ready.failed)
        } finally {repo.close();directory.listFiles().orEmpty().forEach {it.delete()};directory.delete()}
    }
}
