package com.musicglass.desktop

import com.musicglass.data.*
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Controlled transport serves actual compressed recordings; no synthetic success source or fixture UI. */
internal fun downloadRecording(file:File):CachedByteSource=object:CachedByteSource(0){
    private val input=RandomAccessFile(file,"r")
    override fun length()=input.length()
    @Synchronized override fun readAt(position:Long,target:ByteArray,offset:Int,count:Int):Int{input.seek(position);return input.read(target,offset,count)}
    override fun close()=input.close()
}
class DownloadIntegrationTest {
    private val recording get()=File(checkNotNull(System.getProperty("riff.corpus.dir")),"reference-long.flac")
    private val track get()=TrackRecord(Scopes.GUEST,"youtube:abcdefghijk","Actual compressed recording",sourceKind="youtube")
    private fun await(condition:()->Boolean){val until=System.nanoTime()+20000000000L;while(!condition()){check(System.nanoTime()<until){"Download state did not reach its bounded expected condition"};Thread.sleep(10)}}
    private fun withStore(test:(File,SqliteLibraryRepository)->Unit){val dir=kotlin.io.path.createTempDirectory("riff-download-").toFile();try{SqliteLibraryRepository(File(dir,"library")).use{test(dir,it)}}finally{dir.deleteRecursively()}}
    @Test fun realDownloadsResumeAValidatedPrefixAfterAnActualHttpBodyDrop()=withStore{dir,store->
        val bytes=recording.readBytes();assertTrue(bytes.size>524288)
        val drop=AtomicBoolean(true);val opens=AtomicInteger()
        val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/recording"){exchange->
            val parts=Regex("bytes=(\\d+)-(\\d+)").matchEntire(exchange.requestHeaders.getFirst("Range"))!!.groupValues
            val start=parts[1].toInt();val end=minOf(parts[2].toInt(),bytes.lastIndex)
            exchange.responseHeaders.add("Content-Range","bytes $start-$end/${bytes.size}");exchange.responseHeaders.add("ETag","\"actual-recording\"")
            exchange.sendResponseHeaders(206,(end-start+1).toLong())
            exchange.responseBody.use{it.write(bytes,start,if(drop.get()&&start>=524288)64 else end-start+1)}
        }
        server.start()
        try{DesktopDownloads(store,File(dir,"downloads"),{opens.incrementAndGet();HttpRangeByteSource(java.net.URI("http://127.0.0.1:${server.address.port}/recording"),emptyMap(),File(dir,"ranges"))}).use{manager->
            manager.request(track)
            await{manager.repository.get(track.scope,track.id)?.state==DownloadState.Failed}
            val partial=File(dir,"downloads").listFiles()!!.single{it.extension=="part"}
            assertEquals(524288L,partial.length());assertNull(manager.playbackSource(track.scope,track.id))
            drop.set(false);manager.resume(track.scope,track.id)
            await{manager.repository.get(track.scope,track.id)?.state==DownloadState.Complete}
            val committed=File(checkNotNull(manager.playbackSource(track.scope,track.id)))
            assertContentEquals(bytes,committed.readBytes());assertEquals(2,opens.get())
            assertNotNull(manager.repository.get(track.scope,track.id)?.sha256)
        }}finally{server.stop(0)}
        assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
    }
    @Test fun realDownloadsPauseCancelReadsAndRestartWithoutClaimingPartialFilesComplete()=withStore{dir,store->
        val blocked=java.util.concurrent.CountDownLatch(1);val slow=AtomicBoolean(true)
        DesktopDownloads(store,File(dir,"downloads"),{
            val actual=downloadRecording(recording)
            object:CachedByteSource(0){
                override fun length()=actual.length()
                override fun readAt(position:Long,target:ByteArray,offset:Int,count:Int):Int{if(position>=32768&&slow.get()){blocked.countDown();java.util.concurrent.CountDownLatch(1).await()};return actual.readAt(position,target,offset,count)}
                override fun close()=actual.close()
            }
        }).use{manager->
            manager.request(track)
            // New intent can arrive after the worker enters its wait.
            manager.resume(track.scope,track.id)
            assertTrue(blocked.await(10,java.util.concurrent.TimeUnit.SECONDS))
            manager.pause(track.scope,track.id)
            assertEquals(DownloadState.Paused,manager.repository.get(track.scope,track.id)?.state)
            assertNull(manager.playbackSource(track.scope,track.id))
        }
        slow.set(false)
        DesktopDownloads(store,File(dir,"downloads"),{downloadRecording(recording)}).use{manager->
            assertEquals(DownloadState.Paused,manager.repository.get(track.scope,track.id)?.state)
            manager.resume(track.scope,track.id);await{manager.repository.get(track.scope,track.id)?.state==DownloadState.Complete}
            assertContentEquals(recording.readBytes(),File(checkNotNull(manager.playbackSource(track.scope,track.id))).readBytes())
            manager.remove(track.scope,track.id);await{manager.repository.get(track.scope,track.id)==null}
            assertTrue(File(dir,"downloads").listFiles()!!.none{it.extension in setOf("part","media")})
        }
    }
    @Test fun realDownloadsRejectAnOversizedSourceAndTamperedCompletedAudio()=withStore{dir,store->
        DesktopDownloads(store,File(dir,"small"),{downloadRecording(recording)},quotaBytes=65536,maximumTrackBytes=65536).use{manager->
            manager.request(track);manager.resume(track.scope,track.id)
            await{manager.repository.get(track.scope,track.id)?.state==DownloadState.Failed}
            assertEquals("TRACK_TOO_LARGE",manager.repository.get(track.scope,track.id)?.errorCode)
        }
        DesktopDownloads(store,File(dir,"downloads"),{downloadRecording(recording)}).use{manager->
            manager.resume(track.scope,track.id);await{manager.repository.get(track.scope,track.id)?.state==DownloadState.Complete}
            val file=File(checkNotNull(manager.playbackSource(track.scope,track.id)))
            RandomAccessFile(file,"rw").use{it.seek(100);it.writeByte(0)}
            // Make the corruption deterministic even if that original byte was already zero.
            RandomAccessFile(file,"rw").use{it.seek(101);val value=it.readUnsignedByte();it.seek(101);it.writeByte(value xor 1)}
            assertFailsWith<DownloadFailure>{manager.playbackSource(track.scope,track.id)}
            assertEquals("INTEGRITY_FAILED",manager.repository.get(track.scope,track.id)?.errorCode)
        }
        val reopened=AtomicInteger()
        DesktopDownloads(store,File(dir,"downloads"),{reopened.incrementAndGet();error("No source must be resolved for a failed download")}).use{manager->
            assertNull(manager.playbackSource(track.scope,track.id));assertEquals(0,reopened.get())
        }
    }
    @Test fun realDownloadsRecoverActualProcessDeathAtEveryCommitBoundary(){
        for(checkpoint in DownloadCheckpoint.entries){
            val dir=kotlin.io.path.createTempDirectory("riff-download-kill-").toFile()
            try{
                val javaExecutable=File(System.getProperty("java.home"),"bin/java.exe")
                val child=ProcessBuilder(javaExecutable.path,"-Driff.native.dir="+checkNotNull(System.getProperty("riff.native.dir")),"-cp",checkNotNull(System.getProperty("riff.test.runtime.classpath")),DownloadHaltProbe::class.java.name,dir.path,recording.path,checkpoint.name).redirectErrorStream(true).redirectOutput(File(dir,"child.log")).start()
                try{assertTrue(child.waitFor(40,java.util.concurrent.TimeUnit.SECONDS),"Commit probe stalled: $checkpoint");assertEquals(74,child.exitValue(),File(dir,"child.log").readText())}finally{if(child.isAlive)child.destroyForcibly().waitFor()}
                SqliteLibraryRepository(File(dir,"library")).use{store->
                    val requests=AtomicInteger()
                    DesktopDownloads(store,File(dir,"downloads"),{requests.incrementAndGet();downloadRecording(recording)}).use{manager->
                        await{manager.repository.get(track.scope,track.id)?.state==DownloadState.Complete}
                        assertContentEquals(recording.readBytes(),File(checkNotNull(manager.playbackSource(track.scope,track.id))).readBytes())
                        assertEquals(if(checkpoint==DownloadCheckpoint.BEFORE_VERIFY_RECEIPT)1 else 0,requests.get(),checkpoint.name)
                        assertEquals("ok",store.integrity())
                    }
                }
            }finally{dir.deleteRecursively()}
        }
    }
    @Test fun realDownloadsRejectAChangedResumePrefixBeforeStartingAFreshVerifiedCopy()=withStore{dir,store->
        val attempt=AtomicInteger()
        DesktopDownloads(store,File(dir,"downloads"),{
            val pass=attempt.incrementAndGet();val actual=downloadRecording(recording)
            object:CachedByteSource(0){
                override fun length()=actual.length()
                override fun readAt(position:Long,target:ByteArray,offset:Int,count:Int):Int{
                    if(pass==1&&position>=65536)throw java.io.IOException("Injected transport drop")
                    return actual.readAt(position,target,offset,count).also{if(pass==2&&position==0L&&it>0)target[offset]=(target[offset].toInt() xor 1).toByte()}
                }
                override fun close()=actual.close()
            }
        }).use{manager->
            manager.request(track);await{manager.repository.get(track.scope,track.id)?.state==DownloadState.Failed}
            assertTrue(File(dir,"downloads").listFiles()!!.any{it.extension=="part"&&it.length()==65536L})
            manager.resume(track.scope,track.id);await{manager.repository.get(track.scope,track.id)?.errorCode=="REPRESENTATION_CHANGED"}
            assertTrue(File(dir,"downloads").listFiles()!!.none{it.extension=="part"})
            manager.resume(track.scope,track.id);await{manager.repository.get(track.scope,track.id)?.state==DownloadState.Complete}
            assertContentEquals(recording.readBytes(),File(checkNotNull(manager.playbackSource(track.scope,track.id))).readBytes());assertEquals(3,attempt.get())
        }
    }
    @Test fun realDownloadsRetainRemovingUntilAnActualDecoderReleasesItsWindowsFile()=withStore{dir,store->
        DesktopDownloads(store,File(dir,"downloads"),{downloadRecording(recording)}).use{manager->
            manager.request(track);await{manager.repository.get(track.scope,track.id)?.state==DownloadState.Complete}
            val path=File(checkNotNull(manager.playbackSource(track.scope,track.id)))
            FfmpegPcmDecoder(path.path).use{decoder->
                assertTrue(decoder.read(FloatArray(2048),1024)>0)
                manager.remove(track.scope,track.id);Thread.sleep(150)
                assertEquals(DownloadState.Removing,manager.repository.get(track.scope,track.id)?.state)
                assertTrue(path.isFile,"Windows-locked audio must remain pending, never claim successful deletion")
            }
            manager.remove(track.scope,track.id);await{manager.repository.get(track.scope,track.id)==null};assertFalse(path.exists())
        }
        assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
    }
    @Test fun realDownloadsDirectoryHasExactlyOneWriter()=withStore{dir,store->
        DesktopDownloads(store,File(dir,"downloads"),{downloadRecording(recording)}).use{
            assertFailsWith<java.nio.channels.OverlappingFileLockException>{DesktopDownloads(store,File(dir,"downloads"),{downloadRecording(recording)})}
        }
        DesktopDownloads(store,File(dir,"downloads"),{downloadRecording(recording)}).close()
    }
}
internal object DownloadHaltProbe {
    @JvmStatic fun main(args:Array<String>){
        val dir=File(args[0]);val input=File(args[1]);val halt=DownloadCheckpoint.valueOf(args[2])
        SqliteLibraryRepository(File(dir,"library")).use{store->
            DownloadRepository(store).request(TrackRecord(Scopes.GUEST,"youtube:abcdefghijk","Actual compressed recording",sourceKind="youtube"))
            DesktopDownloads(store,File(dir,"downloads"),{downloadRecording(input)},checkpoint={if(it==halt)Runtime.getRuntime().halt(74)}).use{Thread.sleep(30000);error("Commit checkpoint was never reached")}
        }
    }
}
