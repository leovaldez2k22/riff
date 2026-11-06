package com.musicglass.desktop

import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import kotlin.test.*

class PhaseOneStorageAndRangeTest {
    @Test fun sqliteIsRealFullSynchronousAndRejectsRevisionRegression() {
        val directory=kotlin.io.path.createTempDirectory("riff-phase1-db").toFile()
        try {
            val payload=buildJsonObject{put("schema",1);put("revision",7);put("queue",JsonArray(emptyList()))}
            DesktopSessionRepository(directory).use { db ->
                assertEquals("wal",db.pragma("journal_mode"));assertEquals("2",db.pragma("synchronous"))
                db.save(payload,7,"stable-command");assertEquals(payload,db.load())
                db.save(payload,7,"stable-command")
                assertFailsWith<IllegalStateException>{db.save(buildJsonObject{put("schema",1);put("other",true)},7,"stable-command")}
                assertFailsWith<IllegalStateException>{db.save(payload,6)}
                assertEquals("ok",db.pragma("integrity_check"))
            }
            assertEquals("SQLite format 3\u0000",File(directory,"session.sqlite").inputStream().use{String(it.readNBytes(16))})
            DesktopSessionRepository(directory).use{assertEquals(payload,it.load())}
        } finally { directory.deleteRecursively() }
    }
    @Test fun byteRangesNeverReturnUnfetchedSparseHolesAndRejectShortRanges() {
        val bytes=ByteArray(750000){(it*31).toByte()}
        val requests=mutableListOf<Long>()
        var truncate=false
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/audio") { request ->
            val range=Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.requestHeaders.getFirst("Range"))!!
            val start=range.groupValues[1].toInt();val end=minOf(range.groupValues[2].toInt(),bytes.lastIndex)
            synchronized(requests){requests+=start.toLong()}
            val body=bytes.copyOfRange(start,if(truncate)end else end+1)
            request.responseHeaders.add("Content-Range","bytes $start-$end/${bytes.size}")
            request.responseHeaders.add("ETag","\"actual-v1\"")
            request.sendResponseHeaders(206,body.size.toLong());request.responseBody.use{it.write(body)}
        }
        server.start();val directory=kotlin.io.path.createTempDirectory("riff-phase1-range").toFile()
        try {
            val uri=URI("http://127.0.0.1:${server.address.port}/audio")
            HttpRangeByteSource(uri,emptyMap(),directory).use { source ->
                val target=ByteArray(4096);assertEquals(4096,source.readAt(530000,target,0,target.size))
                assertContentEquals(bytes.copyOfRange(530000,534096),target)
                assertTrue(524288L in requests);val fetched=source.networkBytes
                source.readAt(530000,target,0,target.size);assertEquals(fetched,source.networkBytes);assertTrue(source.cacheHits>0)
                assertEquals(-1,source.readAt(bytes.size.toLong(),target,0,target.size))
            }
            truncate=true
            assertFailsWith<IllegalStateException>{HttpRangeByteSource(uri,emptyMap(),directory)}
            assertTrue(directory.listFiles()!!.isEmpty(),"Failed source leaked its temporary media file")
        } finally {server.stop(0);directory.deleteRecursively()}
    }
    @Test fun descriptorsRejectExpiringUrlsAndTrackRealFileChanges() {
        val file=kotlin.io.path.createTempFile("riff-source-", ".flac").toFile()
        try {
            file.writeBytes(byteArrayOf(1,2,3));val original=describeAudioSource(file.path)
            assertEquals(original,sourceFromJson(original.toJson()))
            file.writeBytes(byteArrayOf(1,2,4));assertNotEquals(original,describeAudioSource(file.path))
            assertFails {describeAudioSource("riff://stream/not-real")}
            assertFails {describeAudioSource("https://media.invalid/expiring")}
        } finally {file.delete()}
    }
    @Test fun legacyPreviewIdsAreBackedUpAndRejectedInsteadOfRestoredAsAudio() {
        val root=kotlin.io.path.createTempDirectory("riff-legacy").toFile()
        try {
            val legacy=File(root,"old").apply{mkdirs()};val destination=File(root,"new")
            val text="SCHEMA|2\nSNAP|default|track-1|4500|1|track-1,track-2\n"
            File(legacy,"library-state.db").writeText(text)
            assertFailsWith<IllegalArgumentException>{LegacySessionImporter.inspect(legacy,destination)}
            val copy=File(destination,"legacy-backup").listFiles()!!.single()
            assertEquals(text,copy.readText());assertEquals(text,File(legacy,"library-state.db").readText())
        }finally{root.deleteRecursively()}
    }
    @Test fun stalledHttpBodyIsCancelledWithinItsBudget() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/stall"){exchange->
            try {
                exchange.responseHeaders.add("Content-Range","bytes 0-99/100")
                exchange.sendResponseHeaders(206,100);exchange.responseBody.write(1);exchange.responseBody.flush()
                Thread.sleep(1500)
            }finally{exchange.close()}
        }
        server.start();val directory=kotlin.io.path.createTempDirectory("riff-stall").toFile()
        try {
            val start=System.nanoTime()
            assertFailsWith<java.io.IOException>{HttpRangeByteSource(URI("http://127.0.0.1:${server.address.port}/stall"),emptyMap(),directory,requestBudget=java.time.Duration.ofMillis(300))}
            assertTrue(System.nanoTime()-start<1_000_000_000L,"A stalled response body outlived its request budget")
            assertTrue(directory.listFiles()!!.isEmpty())
        }finally{server.stop(0);directory.deleteRecursively()}
    }
}
