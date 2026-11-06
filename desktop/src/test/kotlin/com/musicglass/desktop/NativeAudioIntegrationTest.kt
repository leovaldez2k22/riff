package com.musicglass.desktop

import java.io.File
import kotlin.test.*

class NativeAudioIntegrationTest {
    @Test fun realPlaybackCoversABoundedProducerSchedulingStallWithoutInsertedSilence(){
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")))
        val finished=java.util.concurrent.CountDownLatch(1)
        var before=0L;var after=0L;var gapMs=0L;var underruns=-1L;var pauseMs=0L;var bufferFrames=0L
        val wrapper=object:PcmOutput{
            val actual=WasapiPcmOutput()
            override val sampleRate=48000;override val channels=2
            var submitted=0L;var stalled=false
            override fun acquireProducerScheduling()=actual.acquireProducerScheduling()
            override val producerSchedulingActive get()=actual.producerSchedulingActive
            override fun schedulingTelemetry()=actual.schedulingTelemetry()
            override fun write(samples:FloatArray,offsetFrames:Int,frames:Int):Int{
                val presented=actual.presentedFrames()
                if(!stalled && presented>=96000){
                    stalled=true;before=submitted-presented
                    val start=System.nanoTime();Thread.sleep(180);gapMs=(System.nanoTime()-start)/1000000
                    after=actual.presentedFrames();bufferFrames=actual.schedulingTelemetry()[5]
                    check(gapMs<240){"Scheduling injection itself exceeded its 240 ms bound: $gapMs"}
                }
                return actual.write(samples,offsetFrames,frames).also{submitted+=it}
            }
            override fun setPlaying(playing:Boolean){actual.setPlaying(playing);if(!playing&&stalled){underruns=actual.underrunFrames();finished.countDown()}}
            override fun reset(){actual.reset();submitted=0}
            override fun presentedFrames()=actual.presentedFrames()
            override fun underrunFrames()=actual.underrunFrames()
            override fun close()=actual.close()
        }
        val faults=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.Fault>()
        DesktopDualDeckMixer(outputFactory={wrapper},onFault={faults+=it;finished.countDown()}).use{mixer->
            mixer.volume=0f;mixer.prepareSource("actual-recording",File(root,"reference-long.flac").path)
            mixer.loadQueue(listOf("actual-recording"));mixer.setPlaying(true)
            val until=System.nanoTime()+6000000000L
            while((!wrapper.stalled||wrapper.presentedFrames()<192000)&&faults.isEmpty()&&System.nanoTime()<until)Thread.sleep(10)
            val pauseStart=System.nanoTime();mixer.setPlaying(false);pauseMs=(System.nanoTime()-pauseStart)/1000000
            val passed=wrapper.stalled&&faults.isEmpty()&&underruns==0L&&pauseMs<250
            File(root.parentFile,"producer-stall-buffer-evidence.json").writeText("""{"status":"${if(passed)"PASS"else"FAIL"}","realSource":"reference-long.flac","muted":true,"output":"actual WASAPI","injectedProducerStallMs":$gapMs,"queuedFramesBeforeStall":$before,"endpointBufferFrames":$bufferFrames,"hardwareFramesAfterStall":$after,"insertedUnderrunFrames":$underruns,"pauseResponseMs":$pauseMs}""")
            assertTrue(faults.isEmpty(),"Actual pipeline fault: $faults")
            assertTrue(wrapper.stalled,"Scheduling fault injection did not execute")
            assertEquals(0L,underruns,"Bounded producer scheduling gap exhausted real playback headroom")
            assertTrue(pauseMs<250,"Pause must stop the actual endpoint promptly")
        }
        assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
        assertEquals(0,NativeAudio.audioThreadRegistrationCount())
    }

    @Test fun realIncomingDecodeStallPreservesEveryCrossfadeFrame(){
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")))
        val first=File(root,"reference.flac");val second=File(root,"reference.m4a")
        val blendBoundary=FfmpegPcmDecoder(first.path).use{(it.durationMs-2000).coerceAtLeast(0)*48}
        fun run(stall:Boolean):FloatArray{
            val release=java.util.concurrent.CountDownLatch(if(stall)1 else 0)
            val buffering=java.util.concurrent.CountDownLatch(1);val ended=java.util.concurrent.CountDownLatch(1)
            val isBuffering=java.util.concurrent.atomic.AtomicBoolean()
            val faults=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.Fault>()
            val chunks=java.util.Collections.synchronizedList(mutableListOf<FloatArray>())
            val submitted=java.util.concurrent.atomic.AtomicLong()
            val factory=PcmDecoderFactory{path->
                val actual=FfmpegPcmDecoder(path)
                if(path!=second.path||!stall)actual else object:PcmDecoder by actual{
                    var acquired=0
                    override fun read(target:FloatArray,frames:Int):Int{
                        if(acquired>=4096)check(release.await(10,java.util.concurrent.TimeUnit.SECONDS)){"Controlled incoming decode stall was not released"}
                        return actual.read(target,frames).also{acquired+=it}
                    }
                }
            }
            val tap=object:PcmOutput{
                override val sampleRate=48000;override val channels=2
                override fun write(samples:FloatArray,offsetFrames:Int,frames:Int):Int{chunks+=samples.copyOfRange(offsetFrames*2,(offsetFrames+frames)*2);submitted.addAndGet(frames.toLong());if(active)presented.set(submitted.get());return frames}
                @Volatile var active=false
                val presented=java.util.concurrent.atomic.AtomicLong()
                override fun setPlaying(playing:Boolean){active=playing;if(playing)presented.set(submitted.get())}
                override fun reset(){submitted.set(0);chunks.clear();presented.set(0);active=false}
                override fun presentedFrames()=presented.get()
                override fun close(){}
            }
            DesktopDualDeckMixer(outputFactory={tap},decoderFactory=factory,onFault={faults+=it;ended.countDown()},onBufferingState={isBuffering.set(it.isBuffering);if(it.isBuffering&&submitted.get()>=blendBoundary+4096)buffering.countDown()},onPlaybackState={if(!it.isPlaying&&!isBuffering.get()&&it.currentTrackId=="incoming")ended.countDown()}).use{mixer->
                try{
                    mixer.volume=1f;mixer.crossfadeDurationMs=2000
                    mixer.prepareSource("outgoing",first.path);mixer.prepareSource("incoming",second.path)
                    mixer.loadQueue(listOf("outgoing","incoming"),crossfade=true);mixer.setPlaying(true)
                    if(stall){assertTrue(buffering.await(10,java.util.concurrent.TimeUnit.SECONDS),"Incoming PCM exhaustion did not freeze the real pair");assertEquals(blendBoundary+4096,submitted.get(),"Both source timelines must stop at the exact incoming PCM boundary");release.countDown()}
                    assertTrue(ended.await(15,java.util.concurrent.TimeUnit.SECONDS),"Real compressed queue did not finish")
                    assertTrue(faults.isEmpty(),faults.toString())
                }finally{release.countDown()}
            }
            return synchronized(chunks){chunks.flatMap{it.asList()}.toFloatArray()}
        }
        val reference=run(false);val stalled=run(true)
        assertEquals(reference.size,stalled.size,"Incoming starvation lost or inserted submitted PCM")
        var maximum=0f;for(i in reference.indices)maximum=maxOf(maximum,kotlin.math.abs(reference[i]-stalled[i]))
        assertTrue(maximum<=.000001f,"Pausing an incoming decode changed the real crossfade PCM: $maximum")
        File(root.parentFile,"crossfade-incoming-stall-evidence.json").writeText("""{"status":"PASS","realSources":["reference.flac","reference.m4a"],"submittedFrames":${reference.size/2},"maximumPcmDifference":$maximum,"scope":"Actual compressed decoding and both-deck stall/recovery submitted PCM; not endpoint loopback"}""")
        assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
    }

    @Test fun actualAcquiredAudioAnalysisPlansAndRendersBothSmartModes(){
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")));val source=File(root,"reference-long.flac")
        val totalFrames=FfmpegPcmDecoder(source.path).use{decoder->val pcm=FloatArray(8192);var frames=0L;while(true){val n=decoder.read(pcm,4096);if(n==0)break;frames+=n};frames}
        assertTrue(totalFrames>48000*12)
        val receipts=mutableListOf<String>()
        for(mode in listOf(com.musicglass.desktop.automix.BlendMode.Smart,com.musicglass.desktop.automix.BlendMode.Automix)){
            val directory=kotlin.io.path.createTempDirectory("riff-actual-blend-").toFile()
            val frames=java.util.concurrent.atomic.AtomicLong();var peak=0f
            val isBuffering=java.util.concurrent.atomic.AtomicBoolean()
            val ended=java.util.concurrent.CountDownLatch(1);val faults=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.Fault>()
            val tap=object:PcmOutput{
                override val sampleRate=48000;override val channels=2
                @Volatile var active=false
                val presented=java.util.concurrent.atomic.AtomicLong()
                override fun write(samples:FloatArray,offsetFrames:Int,count:Int):Int{
                    for(i in offsetFrames*2 until (offsetFrames+count)*2){check(samples[i].isFinite()&&kotlin.math.abs(samples[i])<=1.00001f);peak=maxOf(peak,kotlin.math.abs(samples[i]))}
                    frames.addAndGet(count.toLong());if(active)presented.set(frames.get());return count
                }
                override fun setPlaying(playing:Boolean){active=playing;if(playing)presented.set(frames.get())}
                override fun reset(){frames.set(0);presented.set(0);active=false}
                override fun presentedFrames()=presented.get()
                override fun close(){}
            }
            try{
                DesktopDualDeckMixer(outputFactory={tap},onFault={faults+=it;ended.countDown()},onBufferingState={isBuffering.set(it.isBuffering)},onPlaybackState={if(!it.isPlaying&&!isBuffering.get()&&it.currentTrackId=="incoming")ended.countDown()},analysisDirectory=directory).use{mixer->
                    mixer.volume=.4f;mixer.configureCanonicalDsp(EqualizerConfig(),1f,1f);mixer.configureBlend(mode)
                    mixer.prepareSource("outgoing",source.path);mixer.prepareSource("incoming",source.path)
                    mixer.loadQueue(listOf("outgoing","incoming"),crossfade=true)
                    val deadline=System.nanoTime()+60_000_000_000L
                    var state=mixer.diagnostics()
                    val expectedReason=if(mode==com.musicglass.desktop.automix.BlendMode.Automix)"MODEL_PLAN"else"LIGHT_PLAN"
                    while(state["blendReason"]?.toString()!= "\"$expectedReason\""&&System.nanoTime()<deadline){Thread.sleep(50);state=mixer.diagnostics()}
                    assertEquals("\"$expectedReason\"",state["blendReason"]?.toString(),"Actual analysis did not reach the prepared real decoder pair: $state")
                    fun field(key:String)=state.getValue(key).toString().toLong()
                    val start=field("blendStartMs");val end=field("blendEndMs");val entry=field("blendIncomingStartMs")
                    assertTrue(start>0&&end>start)
                    mixer.setPlaying(true);assertTrue(ended.await(20,java.util.concurrent.TimeUnit.SECONDS),"Actual Smart queue did not finish")
                    assertTrue(faults.isEmpty(),faults.toString());assertTrue(peak>.0001f)
                    assertEquals(start*48+totalFrames-entry*48,frames.get(),"Plan boundaries/entry offset lost or inserted actual decoded PCM")
                    receipts+="""{"mode":"${mode.wire}","analysis":"$expectedReason","startMs":$start,"endMs":$end,"entryMs":$entry,"actualSubmittedFrames":${frames.get()},"decodedPeak":$peak}"""
                }
                assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
            }finally{directory.deleteRecursively()}
        }
        File(root.parentFile,"actual-automix-mixer-evidence.json").writeText("""{"scope":"Real complete acquired recording, actual analysis/model, prepared decoder seek and dual-deck DSP submitted PCM; not UI, session restoration or hardware loopback","cases":[${receipts.joinToString(",") }]}""")
    }

    @Test fun actualEndpointBufferCapacityIsRecordedWithoutStartingPlayback(){
        WasapiPcmOutput().use{output->
            val telemetry=output.schedulingTelemetry()
            assertEquals(8,telemetry.size)
            assertTrue(telemetry[5]>0)
            assertEquals(0,telemetry[6]);assertEquals(0,telemetry[7])
            val root=File(checkNotNull(System.getProperty("riff.corpus.dir"))).parentFile
            File(root,"endpoint-buffer-policy.json").writeText("""{"endpointBufferFrames":${telemetry[5]},"endpointBufferMs":${telemetry[5]/48.0},"playing":false}""")
        }
        assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
    }

    @Test fun partialProducerBatchesDoNotInsertSilenceAheadOfAnAlreadyBufferedEndpoint(){
        // Scheduling-only test: intentional zero gain, real WASAPI. No codec/content claim.
        WasapiPcmOutput().use{output->
            val muted=FloatArray(8192)
            assertEquals(4096,output.write(muted,0,4096));assertEquals(1024,output.write(muted,0,1024))
            output.setPlaying(true)
            var submitted=5120L
            repeat(80){
                val required=(6000-(submitted-output.presentedFrames())).coerceIn(0,4096).toInt()
                if(required>0){assertEquals(required,output.write(muted,0,required));submitted+=required}
                Thread.sleep(2)
            }
            val bufferedBeforePause=submitted-output.presentedFrames()
            val began=System.nanoTime();Thread.sleep(60)
            check((System.nanoTime()-began)/1_000_000<bufferedBeforePause/48){"Test producer pause exceeded the actual buffered headroom"}
            output.write(muted,0,4096)
            val inserted=output.underrunFrames()
            output.setPlaying(false)
            assertEquals(0L,inserted,"Renderer inserted $inserted zero frames despite queued endpoint headroom")
        }
    }

    @Test fun actualMixerProducerRegistersAndReleasesWindowsAudioScheduling(){
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")))
        assertEquals(0,NativeAudio.audioThreadRegistrationCount())
        DesktopDualDeckMixer().use{mixer->
            mixer.volume=0f;mixer.prepareSource("real",File(root,"reference-long.flac").path)
            mixer.loadQueue(listOf("real"));mixer.setPlaying(true)
            assertEquals(1,NativeAudio.audioThreadRegistrationCount())
            assertTrue(mixer.diagnostics().getValue("producerMmcss").toString()=="true")
            mixer.setPlaying(false)
        }
        assertEquals(0,NativeAudio.audioThreadRegistrationCount())
        assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
    }
    @Test fun compressedFilesDecodeTheirActualPcmAndSeekToReferenceFrames() {
        assertTrue(NativeAudio.version().startsWith("n8.1") || NativeAudio.version().startsWith("8.1"))
        val root = File(checkNotNull(System.getProperty("riff.corpus.dir")))
        for (name in listOf("reference.mp3", "reference.flac", "reference.m4a")) {
            val path = File(root, name)
            assertTrue(path.isFile, "Missing genuine compressed corpus file: $name")
            FfmpegPcmDecoder(path.path).use { decoder ->
                assertEquals(mapOf("reference.mp3" to "mp3","reference.flac" to "flac","reference.m4a" to "aac")[name],decoder.codecName)
                assertTrue(decoder.durationMs > 1000)
                val reference = FloatArray(4096 * 2)
                assertEquals(4096, decoder.read(reference, 4096))
                assertTrue(reference.any { kotlin.math.abs(it) > .0001f }, "Decoded silence: $name")
                decoder.seek(0)
                val again = FloatArray(4096 * 2)
                assertEquals(4096, decoder.read(again, 4096))
                reference.indices.forEach { assertEquals(reference[it], again[it], 0.000001f, "$name seek at $it") }
                val target = 4800L
                decoder.seek(target)
                val seeked = FloatArray(1024 * 2); assertEquals(1024, decoder.read(seeked, 1024))
                decoder.seek(0)
                val skipped = FloatArray(4096 * 2); var remaining = target
                while (remaining > 0) remaining -= decoder.read(skipped, minOf(remaining, 4096).toInt())
                val expected = FloatArray(1024 * 2); assertEquals(1024, decoder.read(expected, 1024))
                expected.indices.forEach { assertEquals(expected[it], seeked[it], 0.000001f) }
            }
        }
    }
    @Test fun realEmbeddedCoverAndTagsComeFromCompressedContainer(){
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")))
        FfmpegPcmDecoder(File(root,"embedded-cover.flac").path).use{decoder->
            assertEquals("Actual compressed recording",decoder.tags[0])
            assertEquals("Windows acceptance corpus",decoder.tags[1])
            assertEquals("Phase Two probe",decoder.tags[2])
            val encoded=assertNotNull(decoder.artworkBytes)
            val image=javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(encoded))
            assertEquals(32,image.width);assertEquals(32,image.height)
            val frames=FloatArray(8192);assertTrue(decoder.read(frames,4096)>0);assertTrue(frames.any{kotlin.math.abs(it)>.00001f})
        }
    }
    @Test fun actualCompressedAudioAndFileIdentitySupportLongUnicodePaths(){
        val directory=kotlin.io.path.createTempDirectory("riff-long-").toFile()
        try{
            var nested=directory
            repeat(6){index->nested=File(nested,"音楽-${index}-"+"a".repeat(45)).apply{mkdirs()}}
            val file=File(nested,"recorded-🎵.flac")
            File(checkNotNull(System.getProperty("riff.corpus.dir")),"reference.flac").copyTo(file)
            assertTrue(file.canonicalPath.length>300)
            val id=WindowsFileIdentity.of(file)
            FfmpegPcmDecoder(file.path).use{decoder->val pcm=FloatArray(8192);assertTrue(decoder.read(pcm,4096)>0);assertTrue(pcm.any{kotlin.math.abs(it)>.00001f})}
            val renamed=File(nested,"renamed-🎵.flac");assertTrue(file.renameTo(renamed));assertEquals(id,WindowsFileIdentity.of(renamed))
        }finally{directory.deleteRecursively()}
    }
    @Test fun corruptInputFailsWithoutSynthesizedFallback() {
        val file = kotlin.io.path.createTempFile("riff-corrupt", ".mp3").toFile()
        try { file.writeBytes(ByteArray(100)); assertFailsWith<java.io.IOException> { FfmpegPcmDecoder(file.path) } }
        finally { file.delete() }
    }
    @Test fun actualDualDeckPipelineReachesWindowsLoopback() {
        val capture = java.nio.ByteBuffer.allocateDirect(192000 * 8 * 5).order(java.nio.ByteOrder.nativeOrder())
        val recording = java.util.concurrent.CompletableFuture.supplyAsync { NativeAudio.captureLoopback(capture, 5000) }
        Thread.sleep(300)
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")))
        val faults=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.Fault>()
        val clocks=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.ClockAnchor>()
        val spectra=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.FeatureSpectrumFrame>()
        DesktopDualDeckMixer(onFault={faults.add(it)},onClockAnchor={clocks.add(it)},onSpectrumFrame={spectra.add(it)}).use { mixer ->
            mixer.volume=.12f
            mixer.prepareSource("actual", File(root,"reference.flac").absolutePath)
            mixer.loadQueue(listOf("actual"));mixer.setPlaying(true)
            Thread.sleep(1800)
            mixer.seek(1000);Thread.sleep(1200);mixer.setPlaying(false)
            assertTrue(faults.isEmpty(),faults.toString())
            assertTrue(clocks.any { it.samplesRendered>0 && it.contentPositionMs>200 })
            assertTrue(spectra.any { frame -> frame.bins.any { it>0 } },"No genuine post-DSP spectrum")
        }
        val result=recording.get(10,java.util.concurrent.TimeUnit.SECONDS)
        val rate=(result ushr 32).toInt();val frames=result.toInt()
        assertTrue(rate>=8000 && frames>rate,"No physical endpoint capture")
        val data=FloatArray(frames*2);capture.asFloatBuffer().get(data)
        assertTrue(data.all { it.isFinite() && kotlin.math.abs(it)<=1.001f },"Nonfinite/clipped endpoint output")
        val rms=kotlin.math.sqrt(data.sumOf { it.toDouble()*it } / data.size)
        assertTrue(rms>.00001,"Endpoint captured silence: $rms")
        val reference=FloatArray(2048*2)
        FfmpegPcmDecoder(File(root,"reference.flac").path).use{it.seek(24000);assertEquals(2048,it.read(reference,2048))}
        // Match real recording content, independent of endpoint volume/APO gain and other app sounds.
        val points=256
        val pattern=DoubleArray(points){reference[it*8*2].toDouble()}
        val energy=pattern.sumOf{it*it};var correlation=0.0
        val step=8.0*rate/48000
        val span=(points*step).toInt()
        for(at in 0 until frames-span step 4) {
            var dot=0.0;var capturedEnergy=0.0
            for(i in 0 until points){val value=data[(at+(i*step).toInt())*2].toDouble();dot+=pattern[i]*value;capturedEnergy+=value*value}
            if(capturedEnergy>1e-12)correlation=maxOf(correlation,kotlin.math.abs(dot)/kotlin.math.sqrt(energy*capturedEnergy))
        }
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(File(root,"reference.flac").readBytes()).joinToString(""){"%02x".format(it)}
        File(root.parentFile,"loopback-evidence.json").writeText("""{"rate":$rate,"frames":$frames,"rms":$rms,"sourceSha256":"$digest","contentCorrelation":$correlation}""")
        assertTrue(correlation>.65,"Endpoint signal did not match the decoded real recording: $correlation")
    }
    @Test fun actualCompressedFramesDecodeAndSeekThroughValidatedHttpsAvio() {
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")))
        val bytes=File(root,"reference-long.flac").readBytes()
        val keyStore=java.security.KeyStore.getInstance("PKCS12").apply{File(root,"localhost-test.p12").inputStream().use{load(it,"riff-test-only".toCharArray())}}
        val keys=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply{init(keyStore,"riff-test-only".toCharArray())}
        val trusts=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply{init(keyStore)}
        val tls=javax.net.ssl.SSLContext.getInstance("TLS").apply{init(keys.keyManagers,trusts.trustManagers,null)}
        val server=com.sun.net.httpserver.HttpsServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.httpsConfigurator=com.sun.net.httpserver.HttpsConfigurator(tls)
        server.createContext("/track") {exchange->
            val range=Regex("bytes=(\\d+)-(\\d+)").matchEntire(exchange.requestHeaders.getFirst("Range"))!!
            val from=range.groupValues[1].toInt();val end=minOf(range.groupValues[2].toInt(),bytes.lastIndex)
            exchange.responseHeaders.add("Content-Range","bytes $from-$end/${bytes.size}")
            exchange.responseHeaders.add("ETag","\"corpus-sha\"")
            exchange.sendResponseHeaders(206,(end-from+1).toLong());exchange.responseBody.use{it.write(bytes,from,end-from+1)}
        }
        server.start();val directory=kotlin.io.path.createTempDirectory("riff-tls-cache").toFile()
        try {
            val http=java.net.http.HttpClient.newBuilder().sslContext(tls).build()
            val source=HttpRangeByteSource(java.net.URI("https://127.0.0.1:${server.address.port}/track"),emptyMap(),directory,client=http)
            FfmpegPcmDecoder(source).use{decoder->
                val actual=FloatArray(2048);decoder.seek(48000*12L);assertEquals(1024,decoder.read(actual,1024))
                FfmpegPcmDecoder(File(root,"reference-long.flac").path).use{local->val expected=FloatArray(2048);local.seek(48000*12L);assertEquals(1024,local.read(expected,1024));assertContentEquals(expected,actual)}
                assertTrue(source.networkBytes>262144);assertTrue(source.cacheHits>0)
            }
        }finally{server.stop(0);directory.deleteRecursively()}
    }
    @Test fun crossfadeRestoresBothRealDeckPositionsAndTransitions() {
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")))
        var saved:AudioEvent.ClockAnchor?=null
        fun waitFor(seconds:Int,condition:()->Boolean){val end=System.nanoTime()+seconds*1_000_000_000L;while(!condition()){check(System.nanoTime()<end){"Crossfade clock did not advance"};Thread.sleep(10)}}
        val anchors=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.ClockAnchor>()
        DesktopDualDeckMixer(onClockAnchor={anchors.add(it)}).use{mixer->
            mixer.volume=0f;mixer.prepareSource("first",File(root,"reference.flac").path);mixer.prepareSource("second",File(root,"reference.m4a").path)
            mixer.loadQueue(listOf("first","second"),startPositionMs=4000,crossfade=true);mixer.setPlaying(true)
            waitFor(10){anchors.any{it.secondaryEntryId=="second"&&it.secondaryPositionMs>100}}
            mixer.setPlaying(false);saved=anchors.last{it.secondaryEntryId=="second"}
        }
        anchors.clear();val checkpoint=assertNotNull(saved)
        DesktopDualDeckMixer(onClockAnchor={anchors.add(it)}).use{mixer->
            mixer.volume=0f;mixer.prepareSource("first",File(root,"reference.flac").path);mixer.prepareSource("second",File(root,"reference.m4a").path)
            mixer.loadQueue(listOf("first","second"),startPositionMs=checkpoint.contentPositionMs,secondaryPositionMs=checkpoint.secondaryPositionMs,crossfade=true);mixer.setPlaying(true)
            waitFor(10){anchors.any{it.currentEntryId=="second"}}
            assertTrue(anchors.any{it.secondaryEntryId=="second"&&it.secondaryPositionMs>=checkpoint.secondaryPositionMs})
        }
    }
    @Test fun nonFiniteEncodedPcmStopsWithItsStructuredCorruptionCodeBeforeOutput(){
        // Negative input: actual decoded recording PCM in a valid float WAV, with one deliberately corrupt NaN.
        // This is not a synthesized track or a substitute for positive programme/loopback acceptance.
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")))
        val directory=kotlin.io.path.createTempDirectory("riff-nonfinite-source-").toFile()
        try{
            val pcm=FloatArray(2048)
            FfmpegPcmDecoder(File(root,"reference.flac").path).use{assertEquals(1024,it.read(pcm,1024))}
            pcm[4]=Float.NaN
            val wave=java.nio.ByteBuffer.allocate(44+pcm.size*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            wave.put("RIFF".toByteArray(Charsets.US_ASCII));wave.putInt(36+pcm.size*4);wave.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            wave.putInt(16);wave.putShort(3);wave.putShort(2);wave.putInt(48000);wave.putInt(48000*8);wave.putShort(8);wave.putShort(32)
            wave.put("data".toByteArray(Charsets.US_ASCII));wave.putInt(pcm.size*4);pcm.forEach{wave.putFloat(it)}
            val source=File(directory,"corrupt-real-recording.wav").apply{writeBytes(wave.array())}
            val faults=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.Fault>()
            DesktopDualDeckMixer(onFault={faults+=it}).use{mixer->
                mixer.volume=0f;mixer.prepareSource("corrupt",source.path);mixer.loadQueue(listOf("corrupt"))
                assertFails{mixer.setPlaying(true)}
                val deadline=System.nanoTime()+3_000_000_000
                while(faults.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(10)
                assertEquals("CORRUPT_AUDIO_STREAM",faults.single().errorCode)
            }
            val previousData=System.getProperty("riff.data.dir")
            val profile=File(directory,"real-host");System.setProperty("riff.data.dir",profile.path)
            try{kotlinx.coroutines.runBlocking{
                val bridge=DesktopAudioBridge(workerClassPath=checkNotNull(System.getProperty("riff.test.production.classpath")))
                DesktopHost(audioBridge=bridge,directory=profile).use{host->
                    suspend fun await(test:()->Boolean){kotlinx.coroutines.withTimeout(30000){while(!test()){check(host.snapshot.running);kotlinx.coroutines.delay(25)}}}
                    suspend fun command(value:DesktopCommand){host.dispatch(value);host.awaitIdle().get()}
                    await{host.snapshot.initialized};command(DesktopCommand.SetVolume(0f));command(DesktopCommand.AddFileToQueue(source))
                    await{!host.snapshot.busy};val originalWorker=bridge.workerPid
                    command(DesktopCommand.SetPlaying(true))
                    await{bridge.workerPid!=originalWorker&&!host.snapshot.busy&&!host.snapshot.isPlaying}
                    command(DesktopCommand.AddFileToQueue(File(root,"reference.flac")));await{!host.snapshot.busy}
                    val valid=host.snapshot.queueEntries.last().song;command(DesktopCommand.PlayTrack(valid))
                    await{host.snapshot.isPlaying&&host.snapshot.currentTrack.id==valid.id&&host.snapshot.playbackPositionMs>=200}
                    val diagnostic=bridge.sendCommand(AudioCommand.QueryDiagnostics(bridge.nextId()))
                    assertTrue(diagnostic.success)
                    val values=kotlinx.serialization.json.Json.parseToJsonElement(checkNotNull(diagnostic.details)).let{it as kotlinx.serialization.json.JsonObject}
                    assertTrue(values.getValue("decodedPeak").toString().toFloat()>.00001f)
                    assertEquals("true",values.getValue("producerMmcss").toString())
                    command(DesktopCommand.SetPlaying(false))
                    File(root.parentFile,"corrupt-source-host-recovery.json").writeText("""{"invalidSampleRejected":true,"corruptionCodePreserved":true,"workerRestartedPaused":true,"validRealTrackThenPlayed":true,"muted":true}""")
                }
            }}finally{if(previousData==null)System.clearProperty("riff.data.dir")else System.setProperty("riff.data.dir",previousData)}
            assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
            assertEquals(0,NativeAudio.audioThreadRegistrationCount())
        }finally{directory.deleteRecursively()}
    }

    @Test fun tempoEngineRetainsPitchAndDurationForMathematicalDspSignals(){
        // Analytical DSP-only signal. This is not a production source, codec success fixture or playback acceptance.
        assertEquals("2.4.1",NativeAudio.tempoVersion())
        val reports=mutableListOf<String>()
        for(rate in listOf(.5f,.75f,1.25f,1.5f,2f)){
            val handle=NativeAudio.tempoOpen(rate);val input=java.nio.ByteBuffer.allocateDirect(32768).order(java.nio.ByteOrder.nativeOrder());val output=java.nio.ByteBuffer.allocateDirect(32768).order(java.nio.ByteOrder.nativeOrder())
            val chunks=mutableListOf<FloatArray>();var total=0L
            fun drain(){while(true){val count=NativeAudio.tempoRead(handle,output,4096);if(count==0)break;val block=FloatArray(count*2);output.asFloatBuffer().get(block);chunks+=block;total+=count}}
            try{
                var at=0;while(at<240000){val count=minOf(4096,240000-at);val samples=FloatArray(count*2){index->(.125*kotlin.math.sin(2*kotlin.math.PI*1000*(at+index/2)/48000)).toFloat()};input.asFloatBuffer().put(samples);NativeAudio.tempoPut(handle,input,count);drain();at+=count}
                NativeAudio.tempoFlush(handle);drain();val expected=kotlin.math.round(240000.0/rate).toLong();assertTrue(kotlin.math.abs(total-expected)<=1,"Tempo duration mismatch $rate: $total vs $expected")
                val pcm=chunks.flatMap{it.asList()}.toFloatArray();assertTrue(pcm.all{it.isFinite()&&kotlin.math.abs(it)<.15f});assertTrue(pcm.any{kotlin.math.abs(it)>.1f})
                val from=(total/4).toInt();val length=minOf(48000,(total-from-4800).toInt());var rises=0;for(frame in from+1 until from+length)if(pcm[(frame-1)*2]<=0f&&pcm[frame*2]>0f)rises++
                val frequency=rises*48000.0/length;assertTrue(kotlin.math.abs(frequency-1000)<3,"Pitch shifted at $rate: $frequency Hz")
                reports+="""{"rate":$rate,"outputFrames":$total,"expectedFrames":$expected,"measuredPitchHz":$frequency}"""
            }finally{NativeAudio.tempoClose(handle)}
        }
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir"))).parentFile
        File(root,"tempo-mathematical-dsp-evidence.json").writeText("""{"scope":"Mathematical DSP verification only, not real-media playback","cases":[${reports.joinToString(",") }]}""")
        assertEquals(0,NativeAudio.tempoHandleCount())
    }
    @Test fun actualCompressedTracksChangeTempoAndSeekWithoutSynthesizedAudio(){
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")));val reports=mutableListOf<String>()
        for(name in listOf("reference.mp3","reference.flac","reference.m4a")){
            val file=File(root,name);var sourceFrames=0L
            FfmpegPcmDecoder(file.path).use{decoder->val pcm=FloatArray(8192);while(true){val count=decoder.read(pcm,4096);if(count==0)break;sourceFrames+=count}}
            for(rate in listOf(.5f,.75f,1.25f,1.5f,2f)){
                var frames=0L;var peak=0f
                TempoDecoder(FfmpegPcmDecoder(file.path),rate).use{decoder->val pcm=FloatArray(8192);while(true){val count=decoder.read(pcm,4096);if(count==0)break;frames+=count;for(i in 0 until count*2){assertTrue(pcm[i].isFinite());peak=maxOf(peak,kotlin.math.abs(pcm[i]))}}
                    val target=48000L;decoder.seek(target);val sought=FloatArray(2048);assertEquals(1024,decoder.read(sought,1024))
                    TempoDecoder(FfmpegPcmDecoder(file.path),rate).use{fresh->fresh.seek(target);val expected=FloatArray(2048);assertEquals(1024,fresh.read(expected,1024));for(i in sought.indices)assertEquals(expected[i],sought[i],.00001f,"Stale tempo samples survived seek: $name/$rate")}
                }
                val expected=kotlin.math.round(sourceFrames.toDouble()/rate).toLong();assertTrue(kotlin.math.abs(frames-expected)<=1,"Real encoded duration mismatch $name/$rate: $frames vs $expected");assertTrue(peak>.00001f)
                reports+="""{"codecFile":"$name","rate":$rate,"sourceFrames":$sourceFrames,"outputFrames":$frames,"expectedFrames":$expected,"peak":$peak}"""
            }
        }
        File(root.parentFile,"tempo-real-codec-evidence.json").writeText("""{"scope":"Actual compressed decode/tempo/seek, not a hardware loopback","cases":[${reports.joinToString(",")}]}""")
        assertEquals(0,NativeAudio.tempoHandleCount());assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
    }

    @Test fun canonicalEqualizerMatchesAndroidPcm16OracleOnActualDecodedAudio(){
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")));val music=FloatArray(48000*2)
        FfmpegPcmDecoder(File(root,"reference-long.flac").path).use{decoder->var at=0;val block=FloatArray(8192);while(at<48000){val count=decoder.read(block,minOf(4096,48000-at));check(count>0);System.arraycopy(block,0,music,at*2,count*2);at+=count}}
        val input=ShortArray(music.size){kotlin.math.round(music[it]*.25f*32768).toInt().coerceIn(-32768,32767).toShort()}
        assertTrue(input.any{kotlin.math.abs(it.toInt())>5})
        val configs=EQ_PRESETS.map{(name,gains)->EqualizerConfig(true,EqMode.Manual,manual=gains,preset=name)}+
            listOf(EqualizerConfig(),EqualizerConfig(true,EqMode.Dynamic,-5,5),EqualizerConfig(true,EqMode.Dynamic,5,-5,true),EqualizerConfig(true,balance=-1f),EqualizerConfig(true,balance=1f))
        var maximum=0f
        for(config in configs){
            val original=com.musicglass.desktop.androidreference.EqualizerConfig(config.enabled,com.musicglass.desktop.androidreference.EqMode.valueOf(config.mode.name),config.toneX,config.toneY,config.focused,config.manual,config.preset,config.balance)
            val processor=com.musicglass.desktop.androidreference.PcmEqualizer().apply{this.config=original;configure(48000,2)}
            val source=java.nio.ByteBuffer.allocate(input.size*2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply{input.forEach{putShort(it)};flip()}
            val expected=java.nio.ByteBuffer.allocate(input.size*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);processor.process(source,expected);expected.flip()
            val actual=FloatArray(input.size){input[it]/32768f};CanonicalFloatEqualizer().apply{configure(prepareEqualizer(config));reset()}.process(actual,actual.size/2)
            for(i in actual.indices){val error=kotlin.math.abs(actual[i]-expected.short/32768f);maximum=maxOf(maximum,error);if(error>.000016f)fail("Canonical EQ differs beyond PCM16 rounding: ${config.preset}/${config.mode} sample $i error $error")}
        }
        File(root.parentFile,"canonical-equalizer-android-pcm-evidence.json").writeText("""{"source":"actual decoded reference-long.flac","framesPerConfiguration":48000,"configurations":${configs.size},"maximumFloatToAndroidPcm16Error":$maximum,"toleranceReason":"Android output quantizes to PCM16; desktop retains Float32","scope":"EQ PCM parity, not hardware listening or UI acceptance"}""")
        assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
    }

    @Test fun unavailablePrefetchedSourceCannotCutOffTheCurrentActualRecording()=assertNextFailureKeepsPrimary(0)
    @Test fun failedPrefetchDuringCrossfadeRestoresTheActualPrimaryTail()=assertNextFailureKeepsPrimary(86016)
    private fun assertNextFailureKeepsPrimary(failAfterFrames:Long){
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")));val source=File(root,"reference.flac")
        val expected=mutableListOf<Float>()
        FfmpegPcmDecoder(source.path).use{decoder->val pcm=FloatArray(8192);while(true){val count=decoder.read(pcm,4096);if(count==0)break;repeat(count*2){expected+=pcm[it]}}}
        val reference=expected.toFloatArray();FloatDesktopDsp().apply{volume=1f}.process(reference,reference.size/2)
        val chunks=java.util.Collections.synchronizedList(mutableListOf<FloatArray>())
        val presented=java.util.concurrent.atomic.AtomicLong();val done=java.util.concurrent.CountDownLatch(1)
        val faults=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.Fault>()
        val factory=PcmDecoderFactory{path->
            if(path=="negative-next"&&failAfterFrames==0L)throw DecodeException(java.io.IOException("Injected unavailable next source"))
            val decoder=FfmpegPcmDecoder(if(path=="negative-next")File(root,"reference.m4a").path else path)
            object:PcmDecoder{
                override val durationMs=decoder.durationMs;override val codecName=decoder.codecName
                var decoded=0L
                override fun read(target:FloatArray,frames:Int):Int{
                    if(path=="negative-next"&&decoded>=failAfterFrames)throw DecodeException(java.io.IOException("Injected failure after real compressed frames"))
                    return decoder.read(target,frames).also{decoded+=it}
                }
                override fun seek(frame:Long)=decoder.seek(frame).also{decoded=it}
                override fun close()=decoder.close()
            }
        }
        val tap=object:PcmOutput{
            override val sampleRate=48000;override val channels=2
            override fun write(samples:FloatArray,offsetFrames:Int,frames:Int):Int{chunks+=samples.copyOfRange(offsetFrames*2,(offsetFrames+frames)*2);presented.addAndGet(frames.toLong());Thread.sleep(1);return frames}
            override fun presentedFrames()=presented.get()
            override fun setPlaying(playing:Boolean){}
            override fun reset(){presented.set(0);chunks.clear()}
            override fun close(){}
        }
        DesktopDualDeckMixer(outputFactory={tap},decoderFactory=factory,onFault={faults+=it},onPlaybackState={if(!it.isPlaying&&it.currentTrackId=="primary")done.countDown()}).use{mixer->
            mixer.volume=1f;mixer.prepareSource("primary",source.path);mixer.prepareSource("next","negative-next")
            mixer.loadQueue(listOf("primary","next"),crossfade=true);mixer.setPlaying(true)
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS),"Valid current recording was cut off")
            assertEquals(listOf("NEXT_TRACK_UNAVAILABLE"),faults.map{it.errorCode})
            val actual=synchronized(chunks){chunks.flatMap{it.asList()}.toFloatArray()}
            assertEquals(reference.size,actual.size,"Valid primary frames were lost when speculative next decode failed")
            val from=if(failAfterFrames==0L)0 else (actual.size-9600).coerceAtLeast(0)
            var tailError=0f;for(i in from until actual.size)tailError=maxOf(tailError,kotlin.math.abs(reference[i]-actual[i]))
            assertTrue(tailError<=.00001f,"Primary did not recover to its genuine decoded tail: $tailError")
            assertEquals("primary",mixer.getCurrentTrackId())
            mixer.seek(0);mixer.setPlaying(true);mixer.setPlaying(false)
            File(root.parentFile,"next-source-failure-$failAfterFrames.json").writeText("""{"source":"reference.flac","faultAfterNextDecodedFrames":$failAfterFrames,"primaryFrames":${actual.size/2},"tailMaximumError":$tailError,"engineRemainsUsable":true,"scope":"Real decoded PCM submitted-output tap; not hardware loopback"}""")
        }
        val until=System.nanoTime()+2000000000L;while(NativeAudio.handleCounts().any{it!=0L}&&System.nanoTime()<until)Thread.sleep(5)
        assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
    }

    @Test fun actualFloatEchoMatchesAndroidAndSeekReset(){
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")));val music=FloatArray(144000*2)
        FfmpegPcmDecoder(File(root,"reference-long.flac").path).use{decoder->var at=0;val block=FloatArray(8192);while(at<144000){val n=decoder.read(block,minOf(4096,144000-at));check(n>0);System.arraycopy(block,0,music,at*2,n*2);at+=n}}
        val input=ShortArray(music.size){kotlin.math.round(music[it]*.25f*32768).toInt().coerceIn(-32768,32767).toShort()}
        val cue=com.musicglass.desktop.automix.EchoOut(1500,500,500)
        val oracle=com.musicglass.desktop.automix.androidreference.PcmEcho().apply{this.cue=cue;configure(48000,2,0)}
        val bytes=java.nio.ByteBuffer.allocate(input.size*2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply{input.forEach{putShort(it)};flip()}
        val expected=java.nio.ByteBuffer.allocate(input.size*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);oracle.process(bytes,expected);expected.flip()
        val echo=com.musicglass.desktop.automix.FloatEcho();val actual=FloatArray(input.size);var frame=0
        while(frame<144000){val n=minOf(257,144000-frame);val block=FloatArray(n*2){input[frame*2+it]/32768f};echo.process(block,n,frame.toLong(),cue);System.arraycopy(block,0,actual,frame*2,n*2);frame+=n}
        var maximum=0f;for(i in actual.indices){val error=kotlin.math.abs(actual[i]-expected.short/32768f);maximum=maxOf(maximum,error);assertTrue(error<=.000016f,"Actual Android echo mismatch at $i: $error")}
        assertTrue(actual.drop(96000*2).any{kotlin.math.abs(it)>.00001f},"Real delayed input must ring after the cut")
        val sought=FloatArray(2048){input[24000*2+it]/32768f};val fresh=sought.copyOf()
        echo.reset(24000);echo.process(sought,1024,24000,cue)
        com.musicglass.desktop.automix.FloatEcho().apply{reset(24000)}.process(fresh,1024,24000,cue);assertContentEquals(fresh,sought)
        File(root.parentFile,"echo-android-pcm-evidence.json").writeText("""{"actualSource":"reference-long.flac","frames":144000,"maximumPcm16QuantizationError":$maximum,"sourceSeekResetCompared":true,"scope":"Real decoded audio and independent Android echo oracle; not complete Automix playback"}""")
        assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
    }

    @Test fun actualBlendShelvesMatchAndroidPcmAndOffsetProcessing(){
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")));val music=FloatArray(48000*2)
        FfmpegPcmDecoder(File(root,"reference-long.flac").path).use{decoder->var at=0;val block=FloatArray(8192);while(at<48000){val n=decoder.read(block,minOf(4096,48000-at));check(n>0);System.arraycopy(block,0,music,at*2,n*2);at+=n}}
        val input=ShortArray(music.size){kotlin.math.round(music[it]*.25f*32768).toInt().coerceIn(-32768,32767).toShort()}
        var maximum=0f;var cases=0
        for(low in listOf(0f,-13f,-26f))for(above in listOf(0f,-13f,-26f)){
            val oracle=com.musicglass.desktop.androidreference.PcmEqualizer().apply{bassCutDb=low;aboveBassCutDb=above;configure(48000,2)}
            val bytes=java.nio.ByteBuffer.allocate(input.size*2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply{input.forEach{putShort(it)};flip()}
            val expected=java.nio.ByteBuffer.allocate(input.size*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);oracle.process(bytes,expected);expected.flip()
            val actual=FloatArray(input.size){input[it]/32768f}
            val desktop=CanonicalFloatEqualizer().apply{blendShelves(low,above);reset()}
            var offset=0;while(offset<48000){val n=minOf(257,48000-offset);desktop.process(actual,n,frameOffset=offset);offset+=n}
            for(i in actual.indices){val error=kotlin.math.abs(actual[i]-expected.short/32768f);maximum=maxOf(maximum,error);assertTrue(error<=.000016f,"Actual Android blend shelf mismatch $low/$above sample $i: $error")}
            cases++
        }
        File(root.parentFile,"blend-shelves-android-pcm-evidence.json").writeText("""{"actualSource":"reference-long.flac","cases":$cases,"framesPerCase":48000,"maximumPcm16QuantizationError":$maximum,"scope":"Real decoded audio and independent Android filter oracle; not complete Automix playback"}""")
        assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
    }

    @Test fun canonicalEqualizerTravelsWithBothActualGaplessDecoderDecks(){
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")));val files=listOf("reference.mp3","reference.flac","reference.m4a").map{File(root,it)}
        val config=EqualizerConfig(true,EqMode.Manual,manual=EQ_PRESETS.getValue("Rock"),preset="Rock")
        val expected=mutableListOf<Float>()
        for(file in files){
            val eq=CanonicalFloatEqualizer().apply{configure(prepareEqualizer(config));reset()}
            FfmpegPcmDecoder(file.path).use{decoder->val pcm=FloatArray(8192);while(true){val count=decoder.read(pcm,4096);if(count==0)break;eq.process(pcm,count,decoder.sourceChannels==2);repeat(count*2){expected+=pcm[it]}}}
        }
        val reference=expected.toFloatArray();FloatDesktopDsp().apply{volume=1f}.process(reference,reference.size/2)
        val actual=java.util.Collections.synchronizedList(mutableListOf<FloatArray>());val submittedFrames=java.util.concurrent.atomic.AtomicLong();val ended=java.util.concurrent.CountDownLatch(1);val faults=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.Fault>()
        val tap=object:PcmOutput{
            override val sampleRate=48000;override val channels=2
            override fun write(samples:FloatArray,offsetFrames:Int,frames:Int):Int{actual+=samples.copyOfRange(offsetFrames*2,(offsetFrames+frames)*2);submittedFrames.addAndGet(frames.toLong());return frames}
            override fun setPlaying(playing:Boolean){}
            override fun reset(){submittedFrames.set(0);actual.clear()}
            override fun presentedFrames()=submittedFrames.get()
            override fun close(){}
        }
        DesktopDualDeckMixer(outputFactory={tap},onFault={faults+=it;ended.countDown()},onPlaybackState={if(!it.isPlaying&&it.currentTrackId=="last")ended.countDown()}).use{mixer->
            mixer.volume=1f;mixer.configureCanonicalDsp(config,1f,1f);val ids=listOf("first","middle","last");ids.zip(files).forEach{(id,file)->mixer.prepareSource(id,file.path)};mixer.loadQueue(ids);mixer.setPlaying(true)
            assertTrue(ended.await(20,java.util.concurrent.TimeUnit.SECONDS));assertTrue(faults.isEmpty(),"Actual mixer faults: $faults")
            val pcm=synchronized(actual){actual.flatMap{it.asList()}.toFloatArray()};assertEquals(reference.size,pcm.size)
            var error=0f;for(i in pcm.indices)error=maxOf(error,kotlin.math.abs(pcm[i]-reference[i]));assertTrue(error<=.00001f,"Per-deck canonical EQ wiring mismatch: $error")
            File(root.parentFile,"canonical-equalizer-gapless-evidence.json").writeText("""{"realCodecs":["MP3","FLAC","AAC"],"frames":${pcm.size/2},"maximumWiringError":$error,"scope":"Real decoded PCM submitted tap; not hardware loopback"}""")
        }
    }

    @Test fun normalizationTravelsWithEachActualGaplessDecoderDeck(){
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")));val files=listOf("reference.mp3","reference.flac","reference.m4a").map{File(root,it)}
        // Scripted loudness values isolate deck wiring; the encoded recordings and native decoding are real.
        // Genuine provider metadata/download/restart is a distinct DownloadPlaybackAcceptance journey.
        val config=EqualizerConfig();val loudness=listOf(6.0,12.0,0.0)
        val factory=PcmDecoderFactory{path->val decoder=FfmpegPcmDecoder(path);object:PcmDecoder by decoder{override val loudnessDb=loudness[files.indexOfFirst{it.path==path}]}}
        val expected=mutableListOf<Float>()
        for(file in files){
            val gain=Math.pow(10.0,-minOf(loudness[files.indexOf(file)],12.0)/20).toFloat()
            FfmpegPcmDecoder(file.path).use{decoder->val pcm=FloatArray(8192);while(true){val count=decoder.read(pcm,4096);if(count==0)break;repeat(count*2){expected+=pcm[it]*gain}}}
        }
        val reference=expected.toFloatArray();FloatDesktopDsp().apply{volume=1f}.process(reference,reference.size/2)
        val actual=java.util.Collections.synchronizedList(mutableListOf<FloatArray>());val submittedFrames=java.util.concurrent.atomic.AtomicLong();val ended=java.util.concurrent.CountDownLatch(1);val faults=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.Fault>()
        val tap=object:PcmOutput{
            override val sampleRate=48000;override val channels=2
            override fun write(samples:FloatArray,offsetFrames:Int,frames:Int):Int{actual+=samples.copyOfRange(offsetFrames*2,(offsetFrames+frames)*2);submittedFrames.addAndGet(frames.toLong());return frames}
            override fun setPlaying(playing:Boolean){}
            override fun reset(){submittedFrames.set(0);actual.clear()}
            override fun presentedFrames()=submittedFrames.get()
            override fun close(){}
        }
        DesktopDualDeckMixer(outputFactory={tap},decoderFactory=factory,onFault={faults+=it;ended.countDown()},onPlaybackState={if(!it.isPlaying&&it.currentTrackId=="last")ended.countDown()}).use{mixer->
            mixer.volume=1f;mixer.configureCanonicalDsp(config,1f,1f,true);val ids=listOf("first","middle","last");ids.zip(files).forEach{(id,file)->mixer.prepareSource(id,file.path)};mixer.loadQueue(ids);mixer.setPlaying(true)
            assertTrue(ended.await(20,java.util.concurrent.TimeUnit.SECONDS));assertTrue(faults.isEmpty(),"Actual mixer faults: $faults")
            val pcm=synchronized(actual){actual.flatMap{it.asList()}.toFloatArray()};assertEquals(reference.size,pcm.size)
            var error=0f;for(i in pcm.indices)error=maxOf(error,kotlin.math.abs(pcm[i]-reference[i]));assertTrue(error<=.00001f,"Per-deck normalization wiring mismatch: $error")
            File(root.parentFile,"normalization-gapless-evidence.json").writeText("""{"realCodecs":["MP3","FLAC","AAC"],"frames":${pcm.size/2},"maximumWiringError":$error,"scope":"Real decoded PCM submitted tap with scripted per-recording loudness; not provider or hardware loopback"}""")
        }
    }

    @Test fun realCompressedGaplessQueueHasExactlyTheDecodedFramesAndNoInsertedPcm(){
        // Deterministic submitted-output tap: proves frame/envelope accounting, not endpoint audibility.
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")))
        val files=listOf("reference.mp3","reference.flac","reference.m4a").map{File(root,it)}
        val reference=java.io.ByteArrayOutputStream()
        for(file in files)FfmpegPcmDecoder(file.path).use{decoder->
            val pcm=FloatArray(8192);val bytes=java.nio.ByteBuffer.allocate(32768).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            while(true){val count=decoder.read(pcm,4096);if(count==0)break;bytes.clear();repeat(count*2){bytes.putFloat(pcm[it])};reference.write(bytes.array(),0,count*8)}
        }
        val expectedBytes=reference.toByteArray();val expected=FloatArray(expectedBytes.size/4)
        java.nio.ByteBuffer.wrap(expectedBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(expected)
        // Same accepted user gain and initial ramp, independently applied to the concatenated real reference.
        FloatDesktopDsp().apply{volume=1f}.process(expected,expected.size/2)
        val chunks=java.util.Collections.synchronizedList(mutableListOf<FloatArray>())
        val submitted=java.util.concurrent.atomic.AtomicLong()
        val finished=java.util.concurrent.CountDownLatch(1)
        val faults=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.Fault>()
        val output=object:PcmOutput{
            override val sampleRate=48000;override val channels=2
            override fun write(samples:FloatArray,offsetFrames:Int,frames:Int):Int{chunks+=samples.copyOfRange(offsetFrames*2,(offsetFrames+frames)*2);submitted.addAndGet(frames.toLong());return frames}
            override fun setPlaying(playing:Boolean){}
            override fun reset(){submitted.set(0);chunks.clear()}
            override fun presentedFrames()=submitted.get()
            override fun close(){}
        }
        DesktopDualDeckMixer(outputFactory={output},onPlaybackState={if(!it.isPlaying&&it.currentTrackId=="last")finished.countDown()},onFault={faults+=it;finished.countDown()}).use{mixer->
            mixer.volume=1f;val ids=listOf("first","middle","last");ids.zip(files).forEach{(id,file)->mixer.prepareSource(id,file.path)}
            mixer.loadQueue(ids,crossfade=false);mixer.setPlaying(true)
            assertTrue(finished.await(20,java.util.concurrent.TimeUnit.SECONDS),"Real gapless queue did not finish")
            assertTrue(faults.isEmpty(),"Actual decode/mixer fault: $faults")
            val actual=synchronized(chunks){chunks.flatMap{it.asList()}.toFloatArray()}
            assertEquals(expected.size,actual.size,"Inserted or lost decoded frames at a queue boundary")
            var error=0f;actual.indices.forEach{error=maxOf(error,kotlin.math.abs(expected[it]-actual[it]))}
            assertTrue(error<=.00001f,"Real gapless queue PCM deviation: $error")
            File(root.parentFile,"gapless-real-pcm-evidence.json").writeText("""{"frames":${actual.size/2},"maxError":$error,"insertedFrames":0,"output":"deterministic submitted tap, not hardware loopback","codecs":["MP3","FLAC","AAC"]}""")
        }
        assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
    }

    @Test fun realDecodeStallFreezesEffectivePlaybackAndResumesWithoutFakeFrames() {
        val root=File(checkNotNull(System.getProperty("riff.corpus.dir")))
        val release=java.util.concurrent.CountDownLatch(1)
        val events=java.util.concurrent.CopyOnWriteArrayList<AudioEvent.BufferingState>()
        val factory=PcmDecoderFactory {path->
            val decoder=FfmpegPcmDecoder(path)
            object:PcmDecoder {
                override val durationMs=decoder.durationMs
                override val codecName=decoder.codecName
                var readFrames=0L
                override fun read(target:FloatArray,frames:Int):Int {
                    if(readFrames>=48000)check(release.await(15,java.util.concurrent.TimeUnit.SECONDS)){"Fault-injection release timed out"}
                    return decoder.read(target,frames).also{readFrames+=it}
                }
                override fun seek(frame:Long)=decoder.seek(frame)
                override fun close()=decoder.close()
            }
        }
        fun waitFor(condition:()->Boolean){val end=System.nanoTime()+10_000_000_000L;while(!condition()){check(System.nanoTime()<end){"Expected buffering transition missing"};Thread.sleep(10)}}
        try {
            DesktopDualDeckMixer(decoderFactory=factory,onBufferingState={events.add(it)}).use{mixer->
                mixer.volume=0f;mixer.prepareSource("actual",File(root,"reference.flac").path);mixer.loadQueue(listOf("actual"));mixer.setPlaying(true)
                waitFor{events.any{it.isBuffering}};assertFalse(mixer.isPlaying())
                val paused=mixer.getCurrentPositionMs();Thread.sleep(200);assertEquals(paused,mixer.getCurrentPositionMs())
                release.countDown();waitFor{events.lastOrNull()?.isBuffering==false&&mixer.isPlaying()}
                waitFor{mixer.getCurrentPositionMs()>paused+100}
            }
        }finally{release.countDown()}
    }
}
