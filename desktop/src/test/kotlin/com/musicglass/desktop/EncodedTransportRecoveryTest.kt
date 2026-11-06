package com.musicglass.desktop

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.flow
import kotlin.test.*

/** Tests the encoded-byte transport, not codec capability or audible playback. */
class EncodedTransportRecoveryTest {
    private fun temporary()=kotlin.io.path.createTempDirectory("riff-transport-").toFile()
    @Test fun nestedEncodedSourceFailuresRetainTheirRecoveryClassification(){
        val changed=java.io.IOException("Provider audio acquisition failed",java.io.IOException("Media representation changed during reconnect; reopen at content time"))
        assertFailsWith<SourceRepresentationChangedException>{decodeFrames{throw changed}}
        val corrupt=java.io.IOException("Audio source read failed",java.io.IOException("Corrupt audio packet"))
        assertFailsWith<StreamCorruptedException>{decodeFrames{throw corrupt}}
        assertFailsWith<DecodeException>{decodeFrames{throw java.io.IOException("Socket closed")}}
    }
    @Test fun persistentBlocksValidatePayloadAndRepresentationBeforeReuse(){
        val root=temporary()
        try{
            val payload=ByteArray(262144){(it*17).toByte()}
            PersistentAudioBlocks(root,"recording-v1").use{it.write(0,300000,payload)}
            PersistentAudioBlocks(root,"recording-v1").use{assertContentEquals(payload,it.read(0)!!.bytes)}
            val source=root.listFiles()!!.single{it.isDirectory}
            PersistentAudioBlocks(root,"different-recording").use{other->
                val otherDirectory=root.listFiles()!!.single{it.isDirectory&&it!=source}
                File(source,"0.block").copyTo(File(otherDirectory,"0.block"))
                assertNull(other.read(0),"A copied block must not authenticate another representation")
            }
            val block=File(source,"0.block")
            val corrupt=block.readBytes();corrupt[corrupt.lastIndex]=(corrupt.last().toInt() xor 1).toByte();block.writeBytes(corrupt)
            PersistentAudioBlocks(root,"recording-v1").use{assertNull(it.read(0));assertFalse(block.exists())}
        }finally{root.deleteRecursively()}
    }
    @Test fun evictionPinsActiveOsLeasesAndReclaimsReleasedRepresentations(){
        val root=temporary();val payload=ByteArray(262144){it.toByte()}
        try{
            PersistentAudioBlocks(root,"playing",400000).use{playing->
                playing.write(0,262144,payload)
                PersistentAudioBlocks(root,"next",400000).use{next->
                    next.write(0,262144,payload)
                    assertContentEquals(payload,playing.read(0)!!.bytes)
                    assertNull(next.read(0),"An optional cache write cannot evict an active source")
                }
            }
            PersistentAudioBlocks(root,"next",400000).use{next->next.write(0,262144,payload);assertContentEquals(payload,next.read(0)!!.bytes)}
            assertEquals(1,root.walkTopDown().count{it.name.endsWith(".block")})
        }finally{root.deleteRecursively()}
    }
    private fun server(bytes:ByteArray,expired:Boolean=false,changed:Boolean=false):Pair<HttpServer,AtomicInteger>{
        val calls=AtomicInteger();val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/audio"){exchange->
            calls.incrementAndGet()
            val range=Regex("bytes=(\\d+)-(\\d+)").matchEntire(exchange.requestHeaders.getFirst("Range"))!!
            val start=range.groupValues[1].toInt();val end=minOf(range.groupValues[2].toInt(),bytes.lastIndex)
            if(expired&&start>0&&exchange.requestHeaders.getFirst("X-Lease")!="renewed"){
                exchange.sendResponseHeaders(403,-1);exchange.close()
            }else{
                exchange.responseHeaders.add("ETag",if(changed&&start>0)"\"new\""else"\"original\"")
                exchange.responseHeaders.add("Content-Range","bytes $start-$end/${bytes.size}")
                exchange.sendResponseHeaders(206,(end-start+1).toLong());exchange.responseBody.use{it.write(bytes,start,end-start+1)}
            }
        };server.start();return server to calls
    }
    @Test fun reopeningVerifiedCacheReadsOfflineWithoutAnotherHttpRequest(){
        val root=temporary();val bytes=ByteArray(600000){(it*31).toByte()};val(server,calls)=server(bytes)
        val uri=URI("http://127.0.0.1:${server.address.port}/audio")
        try{
            HttpRangeByteSource(uri,emptyMap(),root,representationKey="protected-v1").use{source->
                val target=ByteArray(1000);assertEquals(1000,source.readAt(530000,target,0,1000));assertContentEquals(bytes.copyOfRange(530000,531000),target)
            }
            server.stop(0);val before=calls.get()
            HttpRangeByteSource(uri,emptyMap(),root,representationKey="protected-v1").use{source->
                val target=ByteArray(1000);assertEquals(1000,source.readAt(530000,target,0,1000));assertContentEquals(bytes.copyOfRange(530000,531000),target);assertEquals(0,source.networkBytes)
            };assertEquals(before,calls.get())
        }finally{server.stop(0);root.deleteRecursively()}
    }
    @Test fun expiredLeaseRenewsOnceAndRetainsExactByteIdentity(){
        val root=temporary();val bytes=ByteArray(600000){(it*7).toByte()};val(server,calls)=server(bytes,expired=true);val renewals=AtomicInteger()
        val uri=URI("http://127.0.0.1:${server.address.port}/audio")
        try{
            HttpRangeByteSource(uri,emptyMap(),root,refreshLease={renewals.incrementAndGet();MediaLease(uri,mapOf("X-Lease" to "renewed"),null)}).use{source->
                val target=ByteArray(1000);source.readAt(530000,target,0,1000);assertContentEquals(bytes.copyOfRange(530000,531000),target)
            };assertEquals(1,renewals.get());assertEquals(3,calls.get())
        }finally{server.stop(0);root.deleteRecursively()}
    }
    @Test fun changedEncodingOnRenewalIsRejectedBeforePublishingItsBytes(){
        val root=temporary();val bytes=ByteArray(600000){it.toByte()};val(server,_)=server(bytes,expired=true,changed=true)
        val uri=URI("http://127.0.0.1:${server.address.port}/audio")
        try{
            HttpRangeByteSource(uri,emptyMap(),root,refreshLease={MediaLease(uri,mapOf("X-Lease" to "renewed"),null)}).use{source->
                val target=ByteArray(1000){42};val error=assertFailsWith<IOException>{source.readAt(530000,target,0,1000)}
                assertTrue(error.message!!.contains("representation changed"));assertTrue(target.all{it==42.toByte()})
            }
        }finally{server.stop(0);root.deleteRecursively()}
    }
    @Test fun growingStreamReconnectComparesExistingPrefixBeforeAppending(){
        val root=temporary();val bytes=ByteArray(90000){(it*13).toByte()};val retries=AtomicInteger()
        try{
            GrowingMediaByteSource(root,0,flow{emit(bytes.copyOfRange(0,40000));throw IOException("connection lost")},reconnect={retries.incrementAndGet();flow{emit(bytes.copyOfRange(0,20000));emit(bytes.copyOfRange(20000,90000))}}).use{source->
                val restored=ByteArray(bytes.size);var offset=0
                while(offset<restored.size){val n=source.readAt(offset.toLong(),restored,offset,restored.size-offset);assertTrue(n>0);offset+=n}
                assertContentEquals(bytes,restored);assertEquals(-1,source.readAt(bytes.size.toLong(),ByteArray(1),0,1));assertEquals(1,retries.get())
            }
        }finally{root.deleteRecursively()}
    }
    @Test fun growingStreamRejectsChangedPrefixButKeepsPreviouslyValidatedBytes(){
        val root=temporary();val original=byteArrayOf(1,2,3,4)
        try{
            GrowingMediaByteSource(root,0,flow{emit(original);throw IOException("connection lost")},reconnect={flow{emit(byteArrayOf(1,2,9,4,5))}}).use{source->
                assertFailsWith<IOException>{source.readAt(4,ByteArray(1),0,1)}
                val prefix=ByteArray(4);assertEquals(4,source.readAt(0,prefix,0,4));assertContentEquals(original,prefix)
            }
        }finally{root.deleteRecursively()}
    }
}
