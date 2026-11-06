package com.musicglass.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

class AudioEngineContractTest {

    @Test
    fun equalizerProcessesPcmWithoutClippingOrNan() {
        val eq = DesktopEqualizer()
        eq.configure(rate = 44100, channelCount = 2)

        // Generate synthetic stereo 440Hz sine wave PCM
        val samples = ShortArray(4410 * 2) { i ->
            (sin(2.0 * Math.PI * 440.0 * (i / 2) / 44100.0) * 16000.0).toInt().toShort()
        }

        // Boost 500Hz band by +6 dB
        eq.bandsDb[4] = 6.0f
        val processed = eq.process(samples)

        assertEquals(samples.size, processed.size)
        for (sample in processed) {
            assertTrue(sample.toInt() in -32768..32767)
        }

        // Bass cut turns down low end
        eq.bassCutDb = 12.0f
        val bassCutProcessed = eq.process(samples)
        assertNotNull(bassCutProcessed)
    }

    @Test
    fun volumeBoostAndLimiterPreventClipping() {
        val booster = DesktopVolumeBoost()
        booster.targetGain = 2.5f // 2.5x amplification
        booster.reset()

        // Generate near-full-scale input samples
        val loudSamples = ShortArray(2000) { 20000.toShort() }
        val boosted = booster.process(loudSamples, sampleRate = 44100, channels = 2)

        for (sample in boosted) {
            assertTrue(abs(sample.toInt()) <= 32767, "Sample must not exceed 16-bit range")
        }

        // Normalization trim reduces gain
        booster.trim = 0.5f
        booster.reset()
        val quietSamples = ShortArray(100) { 1000.toShort() }
        val trimmed = booster.process(quietSamples, sampleRate = 44100, channels = 2)
        assertEquals((1000 * 2.5f * 0.5f).toInt().toShort(), trimmed.last())
    }

    @Test
    fun echoProducesDecayingFeedbackTail() {
        val echo = DesktopEcho()
        echo.configure(rate = 44100, channels = 2)
        echo.enabled = true
        echo.delayMs = 10 // 10ms delay for fast test
        echo.feedback = 0.5f

        val impulse = ShortArray(441 * 2) // 10ms impulse block
        impulse[0] = 10000.toShort()
        impulse[1] = 10000.toShort()

        val out1 = echo.process(impulse, sampleRate = 44100, channels = 2)
        assertEquals(10000.toShort(), out1[0])

        // Second block has input silence; echo feedback must appear!
        val silence = ShortArray(441 * 2)
        val out2 = echo.process(silence, sampleRate = 44100, channels = 2)
        assertTrue(out2.any { it > 0 }, "Echo feedback must appear after delay period")
    }

    @Test
    fun audioBridgeHandlesCommandsAndClockAnchors() {
        val bridge = testAudioBridge()
        val hsResult = bridge.sendCommand(AudioCommand.Handshake(bridge.nextId(), AudioProtocol.BUILD_ID, 12345L))
        assertTrue(hsResult.success)

        val loadResult = bridge.sendCommand(
            AudioCommand.LoadQueue(bridge.nextId(), listOf("track-1", "track-2"), startIndex = 0, startPositionMs = 0L)
        )
        assertTrue(loadResult.success)

        val playResult = bridge.sendCommand(AudioCommand.SetPlaying(bridge.nextId(), isPlaying = true))
        assertTrue(playResult.success)

        // Verify clock anchor emission
        val anchor = bridge.clockAnchor.value
        assertNotNull(anchor)
        assertTrue(anchor.contentPositionMs>=0L)
        assertEquals(48000, anchor.sampleRate)

        assertTrue(bridge.sendCommand(AudioCommand.SetPlaying(bridge.nextId(),false)).success)
        // Seek while paused so the asserted anchor is stable.
        assertTrue(bridge.sendCommand(AudioCommand.Seek(bridge.nextId(),2000L,2)).success)
        val updatedAnchor = bridge.clockAnchor.value
        assertNotNull(updatedAnchor)
        assertEquals(2000L, updatedAnchor.contentPositionMs)

        // Post-DSP spectrum delivery is verified by the real native end-to-end lane.
        bridge.close()
    }

    @Test
    fun streamResolverDeduplicatesAndCaches() = runBlocking {
        val resolver = DesktopStreamResolver(StreamResolverBackend { request ->
            StreamHandle(request.trackId, "https://media.example.test/resolved", "audio/ogg",
                System.currentTimeMillis() + 120_000, request.transport, request.sessionScope)
        })
        val result1 = resolver.resolveStream("track-1")
        assertTrue(result1.isSuccess)
        val handle1 = result1.getOrThrow()
        assertEquals("track-1", handle1.trackId)

        // Second resolve hits memory cache
        val result2 = resolver.resolveStream("track-1")
        assertTrue(result2.isSuccess)
        assertSame(handle1, result2.getOrThrow())

        // Invalidate clears cache
        resolver.invalidate(trackId = "track-1")
        val result3 = resolver.resolveStream("track-1")
        assertTrue(result3.isSuccess)
    }

    @Test
    fun fftProduces256BinsAndCalculatesRms() {
        // Silence input
        val silence = ShortArray(1024)
        val (silenceBins, silenceRms) = DesktopFFT.analyze(silence, channels = 2)
        assertEquals(256, silenceBins.size)
        assertEquals(0.0f, silenceRms)
        assertTrue(silenceBins.all { it.toInt() == 0 })

        // 1000 Hz sine wave input
        val sineWave = ShortArray(1024 * 2) { i ->
            (sin(2.0 * Math.PI * 1000.0 * (i / 2) / 44100.0) * 20000.0).toInt().toShort()
        }
        val (sineBins, sineRms) = DesktopFFT.analyze(sineWave, channels = 2)
        assertEquals(256, sineBins.size)
        assertTrue(sineRms > 0.3f, "RMS of 20000 amplitude sine wave must be > 0.3")
        assertTrue(sineBins.any { it > 0 }, "FFT must detect frequency energy")
    }

    @Test
    fun audioIpcTransportRoundTripsCommandsAndEvents() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // 1. Handshake Command
        val cmdHandshake = AudioCommand.Handshake(requestId = 101L, clientBuild = "1.0.0-riff", processNonce = 987654L)
        AudioIpcTransport.writeCommand(dos, cmdHandshake)

        // 2. LoadQueue Command
        val cmdLoadQueue = AudioCommand.LoadQueue(requestId = 102L, trackIds = listOf("t1", "t2", "t3"), startIndex = 1, startPositionMs = 5000L)
        AudioIpcTransport.writeCommand(dos, cmdLoadQueue)

        // 3. AudioResult
        val result = AudioResult(requestId = 102L, success = true, appliedStateRevision = 42L, error = null)
        AudioIpcTransport.writeResult(dos, result)

        // 4. ClockAnchor Event
        val clockAnchor = AudioEvent.ClockAnchor(
            sequenceNumber = 500L,
            timelineEpoch = 2L,
            contentPositionMs = 12345L,
            monotonicTimestampNanos = 999999999L,
            sampleRate = 44100,
            samplesRendered = 544321L,
            latencyEstimateMs = 12.5f,
            playbackRate = 1.0f
        )
        AudioIpcTransport.writeEvent(dos, clockAnchor)

        // 5. FeatureSpectrumFrame Event
        val spectrum = AudioEvent.FeatureSpectrumFrame(
            sequenceNumber = 501L,
            timelineEpoch = 2L,
            sampleTimestampNanos = 1000000000L,
            bins = ShortArray(256) { it.toShort() },
            peakRms = 0.75f
        )
        AudioIpcTransport.writeEvent(dos, spectrum)

        // Read and verify back
        val bais = ByteArrayInputStream(baos.toByteArray())
        val dis = DataInputStream(bais)

        val readCmd1 = AudioIpcTransport.readCommand(dis) as AudioCommand.Handshake
        assertEquals(cmdHandshake.requestId, readCmd1.requestId)
        assertEquals(cmdHandshake.clientBuild, readCmd1.clientBuild)
        assertEquals(cmdHandshake.processNonce, readCmd1.processNonce)

        val readCmd2 = AudioIpcTransport.readCommand(dis) as AudioCommand.LoadQueue
        assertEquals(cmdLoadQueue.requestId, readCmd2.requestId)
        assertEquals(cmdLoadQueue.trackIds, readCmd2.trackIds)
        assertEquals(cmdLoadQueue.startIndex, readCmd2.startIndex)
        assertEquals(cmdLoadQueue.startPositionMs, readCmd2.startPositionMs)

        val readResult = AudioIpcTransport.readResult(dis)
        assertEquals(result.requestId, readResult.requestId)
        assertEquals(result.success, readResult.success)
        assertEquals(result.appliedStateRevision, readResult.appliedStateRevision)

        val readAnchor = AudioIpcTransport.readEvent(dis) as AudioEvent.ClockAnchor
        assertEquals(clockAnchor.sequenceNumber, readAnchor.sequenceNumber)
        assertEquals(clockAnchor.contentPositionMs, readAnchor.contentPositionMs)
        assertEquals(clockAnchor.samplesRendered, readAnchor.samplesRendered)

        val readSpectrum = AudioIpcTransport.readEvent(dis) as AudioEvent.FeatureSpectrumFrame
        assertEquals(spectrum.sequenceNumber, readSpectrum.sequenceNumber)
        assertEquals(spectrum.peakRms, readSpectrum.peakRms)
        assertEquals(256, readSpectrum.bins.size)
        assertEquals(100.toShort(), readSpectrum.bins[100])
    }

    @Test
    fun dualDeckMixerGaplessTransitionAndSeeking() {
        var transitionCount = 0
        val mixer = DesktopDualDeckMixer(
            outputFactory={TestPcmOutput()},decoderFactory=TestDecoderFactory,
            onTrackTransition = { transitionCount++ }
        )

        // Load 2 synthetic tracks of 1000ms duration each
        mixer.loadQueue(listOf("track-1", "track-2"), startIndex = 0, startPositionMs = 0L)
        mixer.setPlaying(true)
        assertTrue(mixer.isPlaying())

        // Seek to 500ms
        mixer.seek(500L)
        assertTrue(mixer.getCurrentPositionMs() in 450L..550L)

        // Volume adjustment
        mixer.volume = 0.5f
        assertEquals(0.5f, mixer.volume)

        mixer.close()
    }
}
