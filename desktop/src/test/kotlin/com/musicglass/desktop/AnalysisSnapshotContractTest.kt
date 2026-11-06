package com.musicglass.desktop
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import com.sun.net.httpserver.HttpServer

/** Transport-only byte coverage tests. These bytes are not offered as audio/player acceptance. */
class AnalysisSnapshotContractTest {
    @Test fun rangeSnapshotNeverFetchesMissingBlocksAndPreservesExistingDestination(){
        val root=Files.createTempDirectory("riff-analysis-range-").toFile();val bytes=ByteArray(300000){(it%251).toByte()};val requests=AtomicInteger()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/media"){exchange->
            requests.incrementAndGet();val values=Regex("bytes=(\\d+)-(\\d+)").matchEntire(exchange.requestHeaders.getFirst("Range"))!!
            val start=values.groupValues[1].toInt();val end=minOf(values.groupValues[2].toInt(),bytes.lastIndex)
            exchange.responseHeaders.add("Content-Range","bytes $start-$end/${bytes.size}");exchange.responseHeaders.add("ETag","\"stable\"")
            exchange.sendResponseHeaders(206,(end-start+1).toLong());exchange.responseBody.use{it.write(bytes,start,end-start+1)}
        };server.start()
        try{
            HttpRangeByteSource(java.net.URI("http://127.0.0.1:${server.address.port}/media"),emptyMap(),root).use{source->
                val target=File(root,"analysis.media");assertEquals(1,requests.get());assertFalse(source.cachedEncodedSnapshot(target));assertFalse(target.exists());assertEquals(1,requests.get())
                assertTrue(source.readAt(262144,ByteArray(1024),0,1024)>0);assertEquals(2,requests.get())
                assertTrue(source.cachedEncodedSnapshot(target));assertContentEquals(bytes,target.readBytes());assertEquals(2,requests.get())
                val original=target.readBytes();assertFails{source.cachedEncodedSnapshot(target)};assertContentEquals(original,target.readBytes())
            }
        }finally{server.stop(0);root.deleteRecursively()}
    }
    @Test fun growingSnapshotRequiresActualCompletedAcquisition()=runBlocking{
        val root=Files.createTempDirectory("riff-analysis-growing-").toFile();val release=CompletableDeferred<Unit>();val bytes=ByteArray(5000){(it%173).toByte()}
        try{
            GrowingMediaByteSource(root,0,flow{emit(bytes.copyOfRange(0,2000));release.await();emit(bytes.copyOfRange(2000,bytes.size))}).use{source->
                val prefix=ByteArray(100);assertEquals(100,withContext(Dispatchers.IO){source.readAt(0,prefix,0,100)})
                val target=File(root,"analysis.media");assertFalse(source.cachedEncodedSnapshot(target));assertFalse(target.exists())
                release.complete(Unit);withTimeout(3000){while(source.length()<0)delay(10)}
                assertTrue(source.cachedEncodedSnapshot(target));assertContentEquals(bytes,target.readBytes())
                val cancelled=File(root,"cancelled.media")
                try{Thread.currentThread().interrupt();assertFails{source.cachedEncodedSnapshot(cancelled)}}finally{Thread.interrupted()}
                assertFalse(cancelled.exists());assertEquals(100,source.readAt(0,ByteArray(100),0,100),"Analysis cancellation must not close the playback channel")
            }
        }finally{release.complete(Unit);root.deleteRecursively()}
    }
}
