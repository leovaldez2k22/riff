package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Feature 9 (owner, Q9): the part's rules and the copy's name. The copy into Ringtones itself is checked on a phone. */
class RingtoneTest {
    @Test fun aPartStaysInsideTheSongAndIsFiveToSixtySeconds() {
        assertNull(ringtoneCut(4_000, 0, 4_000))
        assertEquals(RingtoneCut(0, 5_000), ringtoneCut(5_000, 0, 1_000))
        assertEquals(RingtoneCut(10_000, 70_000), ringtoneCut(200_000, 10_000, 190_000))
        assertEquals(RingtoneCut(195_000, 200_000), ringtoneCut(200_000, 199_000, 400_000))
        assertEquals(RingtoneCut(0, 5_000), ringtoneCut(200_000, -50, 10))
        assertEquals(RingtoneCut(30_000, 60_000), ringtoneFirstCut(200_000))
        assertEquals(RingtoneCut(0, 30_000), ringtoneFirstCut(40_000))
        assertEquals(RingtoneCut(0, 20_000), ringtoneFirstCut(20_000))
    }

    @Test fun theCopyIsNamedAfterTheSongAndItsTypeIsTheAudiosOwn() {
        assertEquals("First Light - Fixture Band.m4a", ringtoneFileName("First Light", "Fixture Band", null, "m4a"))
        assertEquals("A B - C (30s).webm", ringtoneFileName("A/B", "C", RingtoneCut(30_000, 60_000), "webm"))
        assertEquals("Riff ringtone.m4a", ringtoneFileName("", " ", null, "m4a"))
        assertEquals("audio/webm", ringtoneMime("webm")); assertEquals("audio/mp4", ringtoneMime("mp4")); assertNull(ringtoneMime(null))
        assertEquals("webm", ringtoneExtension("audio/webm")); assertEquals("m4a", ringtoneExtension("audio/mp4"))
        val unknown = File.createTempFile("ringtone", ".bin").apply { writeBytes(ByteArray(32)); deleteOnExit() }
        assertThrows(java.io.IOException::class.java) { cutAudio(unknown, File(unknown.parentFile, "out.part"), null) }
    }
}
