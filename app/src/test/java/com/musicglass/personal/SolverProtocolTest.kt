package com.musicglass.personal

import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SolverProtocolTest {
    private val audio = Format(itag = 251, mimeType = "audio/webm; codecs=\"opus\"", bitrate = 160_000,
        contentLength = 3_456_789, audioQuality = "AUDIO_QUALITY_MEDIUM", approxDurationMs = "215000", loudnessDb = -6.5,
        audioTrack = Format.AudioTrack("en.4", "English original", true),
        signatureCipher = "s=AbC%3D%3D&sp=sig&url=https%3A%2F%2Frr1---sn-x.googlevideo.com%2Fvideoplayback%3Fn%3Dabc")
    private val video = Format(itag = 137, url = "https://rr1---sn-x.googlevideo.com/videoplayback?n=xyz&itag=137",
        mimeType = "video/mp4", width = 1920, height = 1080, isDrc = true)

    @Test fun formatsSurviveTheRoundTripExactly() {
        val formats = listOf(audio, video, Format())
        assertEquals(formats, SolverProtocol.decodeFormats(SolverProtocol.encodeFormats(formats)))
        assertTrue(SolverProtocol.decodeFormats(SolverProtocol.encodeFormats(listOf(audio)))[0].isAudio)
        assertFalse(SolverProtocol.decodeFormats(SolverProtocol.encodeFormats(listOf(video)))[0].isAudio)
    }

    @Test fun aLiveManifestsChallengeGoesToTheSolverAsASongsAddressDoes() {
        // Radio's n challenge is solved as a format (the path songs take); 31 Oct the single-address call took 56 s.
        val url = "https://manifest.googlevideo.com/videoplayback?n=AbCdEf123"
        val sent = SolverProtocol.decodeFormats(SolverProtocol.encodeFormats(listOf(hlsChallengeFormat(url)))).single()
        assertEquals(url, sent.url)
        assertNull("nothing to decipher but n", sent.signatureCipher)
    }

    @Test fun realisticRequestIsFarBelowTheBinderLimit() {
        // A player response offers roughly 20 formats; the extractor sends audio plus two video candidates.
        val encoded = SolverProtocol.encodeFormats(List(25) { audio.copy(itag = it) })
        assertTrue("${encoded.length} chars", encoded.length < 32 * 1024)
    }

    @Test fun oversizedPayloadFailsInsteadOfOverflowingTheBinder() {
        val huge = List(200) { audio.copy(itag = it, signatureCipher = "s=" + "x".repeat(2_000)) }
        assertThrows(IOException::class.java) { SolverProtocol.encodeFormats(huge) }
    }

    @Test fun transactionCodesAreDistinctAndInOrder() {
        val codes = listOf(SolverProtocol.INITIALIZE, SolverProtocol.PRELOAD, SolverProtocol.PREWARM,
            SolverProtocol.PROCESS_FORMATS, SolverProtocol.PROCESS_N)
        assertEquals(codes.sorted(), codes); assertEquals(codes.size, codes.toSet().size)
    }
}

class SolverHealthTest {
    private var now = 0L
    private val health = SolverHealth({ now })

    @Test fun twoCrashesKeepTheSolverAvailable() {
        health.recordDeath(); now += 1_000; health.recordDeath()
        assertTrue(health.available())
    }

    @Test fun threeCrashesInTenMinutesPauseItForTenMinutes() {
        repeat(3) { if (it > 0) now += 60_000; health.recordDeath() } // third crash at 2 min
        assertFalse(health.available())
        now = 12 * 60_000L - 1; assertFalse(health.available())   // pause lasts 10 min from the third crash
        now += 1; assertTrue(health.available())
    }

    @Test fun crashesSpreadOverMoreThanTheWindowDoNotAccumulate() {
        repeat(5) { health.recordDeath(); now += 6 * 60_000 }
        assertTrue(health.available())
    }

    @Test fun afterAPauseTheCountStartsAgain() {
        repeat(3) { health.recordDeath() }
        now += 10 * 60_000
        assertTrue(health.available())
        health.recordDeath(); health.recordDeath()
        assertTrue(health.available())
    }
}
