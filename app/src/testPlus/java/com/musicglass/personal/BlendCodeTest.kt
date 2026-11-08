package com.musicglass.personal

import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Feature 24 (Riff+): the blend code, carried whole through a real QR image, and the blend both phones make. */
class BlendCodeTest {
    private val endings = "AEIMQUYcgkosw048"
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private fun ids(n: Int, seed: Int = 1): List<String> {
        val r = Random(seed)
        return List(n) { String(CharArray(10) { alphabet[r.nextInt(64)] }) + endings[r.nextInt(16)] }
    }

    @Test fun everyYouTubeIdPacksIntoEightBytesAndBack() {
        for (id in ids(2_000) + listOf("dQw4w9WgXcQ", "AAAAAAAAAAA", "__________w", "-_-_-_-_-_8")) assertEquals(id, unpackVideoId(packVideoId(id)!!))
        assertNull("its last character never carries the two lowest bits", packVideoId("dQw4w9WgXcR"))
        for (bad in listOf("short", "dQw4w9WgXcQQ", "dQw4w9WgXc!", "")) assertNull(bad, packVideoId(bad))
    }

    @Test fun base45IsRfc9285() {
        assertEquals("BB8", base45Encode("AB".toByteArray()))
        assertEquals("%69 VD92EX0", base45Encode("Hello!!".toByteArray()))
        assertEquals("UJCLQE7W581", base45Encode("base-45".toByteArray()))
        assertArrayEquals("ietf!".toByteArray(), base45Decode("QED8WEX0"))
        for (bad in listOf("GGW", "a", "ZZZZ", "abc")) assertNull(bad, base45Decode(bad))
    }

    @Test fun aCodeOfAHundredSongsReadsBackFromItsPicture() {
        val songs = ids(BLEND_SONGS)
        val text = encodeBlend(songs)
        assertEquals((4 + 8 * 100 + 4) * 3 / 2, text.length)
        // The owner's 100 songs (Q24) make a version 22–24 code at error correction M.
        val version = Encoder.encode(text, ErrorCorrectionLevel.M).version.versionNumber
        assertTrue("version $version", version in 20..25)
        // Drawn three pixels a square, as a camera would see it at its smallest, and read back.
        val matrix = blendMatrix(text)
        val scale = 3
        val w = matrix.width * scale
        val pixels = IntArray(w * w) { i -> if (matrix.get(i % w / scale, i / w / scale)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val read = readQr(RGBLuminanceSource(w, w, pixels))
        assertEquals(text, read)
        assertEquals(BlendRead.Songs(songs), decodeBlend(read!!))
    }

    @Test fun anythingButAWholeCodeIsRefused() {
        val text = encodeBlend(ids(20))
        assertEquals(BlendRead.Refused(BlendCodeProblem.NotABlend), decodeBlend("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(BlendRead.Refused(BlendCodeProblem.NotABlend), decodeBlend(base45Encode("hello there, friend".toByteArray())))
        // One character changed: the checksum notices.
        val altered = text.substring(0, 30) + (if (text[30] == 'A') 'B' else 'A') + text.substring(31)
        assertTrue(decodeBlend(altered) is BlendRead.Refused)
        // A newer version is named as such; cut short or padded is damaged; oversized is not read at all.
        val bytes = base45Decode(text)!!
        assertEquals(BlendRead.Refused(BlendCodeProblem.NewerVersion), decodeBlend(base45Encode(bytes.copyOf().also { it[2] = 2 })))
        assertEquals(BlendRead.Refused(BlendCodeProblem.Damaged), decodeBlend(base45Encode(bytes.copyOf(bytes.size - 8))))
        assertEquals(BlendRead.Refused(BlendCodeProblem.NotABlend), decodeBlend("A".repeat(BLEND_MAX_TEXT + 1)))
        // More songs than allowed is refused even with a right checksum.
        val tooMany = java.nio.ByteBuffer.allocate(4 + 8 * 101 + 4).put('R'.code.toByte()).put('B'.code.toByte()).put(1).put(101.toByte())
        repeat(101) { tooMany.putLong(it.toLong()) }
        val crc = java.util.zip.CRC32().apply { update(tooMany.array(), 0, tooMany.position()) }.value.toInt(); tooMany.putInt(crc)
        assertTrue(decodeBlend(base45Encode(tooMany.array())) is BlendRead.Refused)
    }

    @Test fun repeatsAndUncarriableIdsAreLeftOutOfACode() {
        val songs = ids(5)
        val read = decodeBlend(encodeBlend(listOf(songs[0], songs[0], "dQw4w9WgXcR") + songs.drop(1))) as BlendRead.Songs
        assertEquals(songs, read.ids)
    }

    @Test fun bothPhonesMakeTheSameBlendAndSharedSongsRise() {
        val a = ids(100, seed = 1); val b = ids(100, seed = 2)
        val shared = listOf(a[40], a[90])
        val mine = a; val theirs = b.take(50) + shared + b.drop(50).take(48)
        val one = blendLists(mine, theirs); val other = blendLists(theirs, mine)
        assertEquals("the same list on both phones", one, other)
        assertEquals(100, one.size)
        assertEquals(shared.toSet(), one.filter { it.shared }.map { it.id }.toSet())
        // A song both play rises: 41st and 51st comes before either person's 20th; 91st and 52nd before their 30th.
        val ids = one.map { it.id }
        assertTrue(ids.indexOf(a[40]) < ids.indexOf(a[19]) && ids.indexOf(a[40]) < ids.indexOf(b[19]))
        assertTrue(ids.indexOf(a[90]) < ids.indexOf(a[29]) && ids.indexOf(a[90]) < ids.indexOf(b[29]))
        // Otherwise each person's favourites alternate from the top, and nothing repeats.
        assertEquals(one.size, one.map { it.id }.toSet().size)
        assertTrue(setOf(a[0], b[0]) == one.filterNot { it.shared }.take(2).map { it.id }.toSet())
    }

    @Test fun unrelatedIdenticalAndSparseTastes() {
        val a = ids(100, 3); val b = ids(100, 4)
        assertEquals(0, blendLists(a, b).count { it.shared })
        val same = blendLists(a, a)
        assertEquals(a, same.map { it.id }); assertTrue(same.all { it.shared })
        val sparse = blendLists(ids(5, 5), ids(7, 6))
        assertEquals(12, sparse.size)
    }
}
