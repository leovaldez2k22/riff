package com.musicglass.personal

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DeterministicHarnessTest {
    @Test fun policyTimeAdvancesIndependentlyOfRealTime() {
        val clock=FakeClock()
        val real=System.nanoTime()
        clock.advance(600_000)
        assertEquals(600_000,clock.monotonicMillis())
        assertEquals(1_800_000_600_000L,clock.wallTimeMillis())
        assertTrue(System.nanoTime()-real<1_000_000_000)
    }

    @Test fun delayedAResultCannotReplaceBFeed()=runBlocking {
        withTimeout(5000) {
            val owner=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            try {
                val session=FakeSession();val catalog=FakeCatalog();val cache=PageMemoryCache(owner,false)
                val a=catalog.enqueue(session.current,"home")
                val old=async {runCatching {cache.get("home") {withContext(NonCancellable) {catalog.load(a)}}}}
                yield()
                session.switchTo("fixture-B");cache.clear()
                val b=catalog.enqueue(session.current,"home")
                val current=async {cache.get("home") {catalog.load(b)}}
                val trackB=ProviderTrack("fixtureB001","B track","Synthetic B")
                b.response.complete(listOf(trackB));assertEquals(listOf(trackB),current.await())
                a.response.complete(listOf(ProviderTrack("fixtureA001","A track","Synthetic A")))
                assertTrue(old.await().exceptionOrNull() is CancellationException)
                assertEquals(listOf(trackB),cache.peek<List<ProviderTrack>>("home"))
                assertEquals(listOf("fixture-A","fixture-B"),catalog.recorded().map {it.identity.scope})
                assertFalse(session.switchTo("fixture-C",verified=false).verified)
            } finally {owner.cancel()}
        }
    }

    @Test fun resolverCompletesOutOfOrderAndNeverInventsFallback()=runBlocking {
        withTimeout(5000) {
            val resolver=ScriptedResolver();val a=resolver.enqueue();val b=resolver.enqueue()
            val first=async(start=CoroutineStart.UNDISPATCHED) {resolver.resolve(StreamRequest("fixtureA001"))}
            val second=async(start=CoroutineStart.UNDISPATCHED) {resolver.resolve(StreamRequest("fixtureB001"))}
            b.complete(Result.success(fixtureStream("fixtureB001")))
            assertEquals("fixtureB001",second.await().getOrThrow().sourceVideoId)
            assertFalse(first.isCompleted)
            a.complete(Result.success(fixtureStream("fixtureA001")))
            assertEquals("fixtureA001",first.await().getOrThrow().sourceVideoId)
            assertTrue(runCatching {resolver.resolve(StreamRequest("unscripted1"))}.isFailure)
        }
    }

    @Test fun everyFailureAndExpiredStreamCanBeScripted()=runBlocking {
        val resolver=ScriptedResolver()
        for(failure in FixtureFailure.entries) {
            resolver.fail(failure)
            assertEquals(failure,(resolver.resolve(StreamRequest("fixtureA001")).exceptionOrNull() as FixtureException).failure)
        }
        val clock=FakeClock();resolver.enqueue().complete(Result.success(fixtureStream(expiresAt=clock.wall-1)))
        val expired=resolver.resolve(StreamRequest("fixtureA001")).getOrThrow()
        assertFalse(reusableStream(expired.expiresAtMs,clock.wall,false))
    }

    @Test fun httpSupportsExactRangesAndRejectsUnsatisfiableRanges() {
        FixtureHttpServer(mapOf("/audio" to FixtureHttpServer.Reply(ByteArray(100) {it.toByte()}))).use {server->
            fun open(range:String)= (URL(server.baseUrl+"/audio").openConnection() as HttpURLConnection).apply {
                connectTimeout=2000;readTimeout=2000;setRequestProperty("Range",range)
            }
            open("bytes=10-19").let {connection->try {
                assertEquals(206,connection.responseCode)
                assertEquals("bytes 10-19/100",connection.getHeaderField("Content-Range"))
                assertArrayEquals(ByteArray(10) {(it+10).toByte()},connection.inputStream.use {it.readBytes()})
            } finally {connection.disconnect()}}
            open("bytes=100-101").let {connection->try {assertEquals(416,connection.responseCode)} finally {connection.disconnect()}}
        }
    }

    @Test fun httpPreservesProviderFailuresAndMalformedPayload() {
        FixtureHttpServer(mapOf("/403" to FixtureHttpServer.Reply(byteArrayOf(),403),
            "/429" to FixtureHttpServer.Reply(byteArrayOf(),429),
            "/503" to FixtureHttpServer.Reply(byteArrayOf(),503),
            "/malformed" to FixtureHttpServer.Reply("{broken".toByteArray(),200,"application/json"))).use {server->
            for(status in listOf(403,429,503)) {
                val connection=URL(server.baseUrl+"/$status").openConnection() as HttpURLConnection
                try {connection.connectTimeout=2000;connection.readTimeout=2000;assertEquals(status,connection.responseCode)}
                finally {connection.disconnect()}
            }
            val connection=URL(server.baseUrl+"/malformed").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout=2000;connection.readTimeout=2000
                val body=connection.inputStream.use {it.readBytes().toString(Charsets.UTF_8)}
                assertTrue(runCatching {kotlinx.serialization.json.Json.parseToJsonElement(body)}.isFailure)
            } finally {connection.disconnect()}
        }
    }

    @Test fun serverShutdownClosesAnUnfinishedClientAndInvalidConfigurationDoesNotBind() {
        assertTrue(runCatching {FixtureHttpServer(emptyMap())}.isFailure)
        val server=FixtureHttpServer(mapOf("/audio" to FixtureHttpServer.Reply(ByteArray(100))))
        java.net.Socket("127.0.0.1",URL(server.baseUrl).port).use {client->
            client.getOutputStream().apply {write("GET /audio HTTP/1.1\r\n".toByteArray());flush()}
            server.close()
        }
    }

    @Test fun originalAudioHasExactFramesAndDistinctStereoChannels() {
        for((name,seconds,channels) in listOf(Triple("tone-a",30,1),Triple("tone-b",30,1),
            Triple("long",300,1),Triple("short",5,1),Triple("stereo",30,2),Triple("silence",5,1))) {
            val bytes=checkNotNull(javaClass.classLoader!!.getResourceAsStream("quality/audio/$name.wav")).use {it.readBytes()}
            val header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF",bytes.copyOfRange(0,4).toString(Charsets.US_ASCII))
            assertEquals(channels,header.getShort(22).toInt());assertEquals(16000,header.getInt(24))
            assertEquals(seconds*16000*channels*2,header.getInt(40))
            assertEquals(44+seconds*16000*channels*2,bytes.size)
            if(name=="silence") assertTrue(bytes.drop(44).all {it==0.toByte()})
            if(name=="stereo") assertNotEquals(header.getShort(48),header.getShort(50))
        }
        fun hash(name:String)=javaClass.classLoader!!.getResourceAsStream("quality/audio/$name.wav")!!.use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
        }
        assertNotEquals(hash("tone-a"),hash("tone-b"))
    }
}
