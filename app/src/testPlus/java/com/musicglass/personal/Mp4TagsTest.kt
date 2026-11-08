package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

/**
 * Feature 15 (Riff+): the tag writer, read back by a reader of the test's own. The device check with Android's reader
 * (m4aVerified) runs on a phone; here every file is built by hand and every title is invented.
 */
class Mp4TagsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun int(v: Long) = ByteBuffer.allocate(4).putInt(v.toInt()).array()
    private fun long(v: Long) = ByteBuffer.allocate(8).putLong(v).array()
    private fun box(type: String, vararg parts: ByteArray) = Mp4Tags.box(type, parts.fold(ByteArray(0)) { a, b -> a + b })
    private val samples = listOf("first-chunk".toByteArray(), "second-chunk".toByteArray())

    /** ftyp, then moov and mdat in either order; one track whose chunk offsets point at [samples] in mdat. */
    private fun movie(moovFirst: Boolean, wide: Boolean = false, oldUdta: Boolean = false): File {
        val ftyp = box("ftyp", "M4A ".toByteArray(), int(0), "isom".toByteArray())
        fun offsets(table: List<Long>) = if (wide) box("co64", int(0), int(table.size.toLong()), *table.map(::long).toTypedArray())
            else box("stco", int(0), int(table.size.toLong()), *table.map(::int).toTypedArray())
        fun moov(table: List<Long>) = box("moov", box("mvhd", ByteArray(100)),
            box("trak", box("tkhd", ByteArray(84)), box("mdia", box("minf", box("stbl", box("stsd", ByteArray(8)), offsets(table))))),
            *(if (oldUdta) arrayOf(box("udta", box("meta", int(0), box("ilst", box("©nam", box("data", int(1), int(0), "Old".toByteArray())))))) else emptyArray()))
        val mdatBody = samples[0] + samples[1]
        val sizeOfMoov = moov(listOf(0, 0)).size
        val mdatStart = ftyp.size + (if (moovFirst) sizeOfMoov else 0)
        val table = listOf(mdatStart + 8L, mdatStart + 8L + samples[0].size)
        val mdat = box("mdat", mdatBody)
        val bytes = if (moovFirst) ftyp + moov(table) + mdat else ftyp + mdat + moov(listOf(ftyp.size + 8L, ftyp.size + 8L + samples[0].size))
        return folder.newFile().apply { writeBytes(bytes) }
    }

    private fun u32(b: ByteArray, at: Int) = ByteBuffer.wrap(b, at, 4).int.toLong() and 0xFFFFFFFFL

    /** A plain walk, apart from the writer's own: the path's box, or null. */
    private fun find(b: ByteArray, path: List<String>, from: Int = 0, to: Int = b.size): Pair<Int, Int>? {
        var at = from
        while (at + 8 <= to) {
            val size = u32(b, at).toInt(); val type = String(b, at + 4, 4, Charsets.ISO_8859_1)
            if (type == path.first()) {
                if (path.size == 1) return at to at + size
                val inner = if (type == "meta") at + 12 else at + 8
                return find(b, path.drop(1), inner, at + size)
            }
            at += size
        }
        return null
    }

    private fun tagText(b: ByteArray, item: String): String? = find(b, listOf("moov", "udta", "meta", "ilst", item, "data"))?.let { (s, e) ->
        String(b, s + 16, e - s - 16, Charsets.UTF_8) }

    private fun chunk(b: ByteArray, index: Int, wide: Boolean): String {
        val (s, _) = find(b, listOf("moov", "trak", "mdia", "minf", "stbl", if (wide) "co64" else "stco"))!!
        val offset = if (wide) ByteBuffer.wrap(b, s + 16 + 8 * index, 8).long.toInt() else u32(b, s + 16 + 4 * index).toInt()
        return String(b, offset, samples[index].size)
    }

    @Test fun tagsAreWrittenAndChunkOffsetsFollowAMovieBoxBeforeTheAudio() {
        for (wide in listOf(false, true)) {
            val source = movie(moovFirst = true, wide = wide); val before = source.readBytes()
            val out = folder.newFile()
            val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(30)
            Mp4Tags.write(source, out, Mp4Tag("Première lumière ✨", "Fixture Band", "Album One", "line one\nline two", cover))
            val b = out.readBytes()
            assertArrayEquals("The source is never changed", before, source.readBytes())
            assertEquals("Première lumière ✨", tagText(b, "©nam"))
            assertEquals("Fixture Band", tagText(b, "©ART")); assertEquals("Album One", tagText(b, "©alb"))
            assertEquals("line one\nline two", tagText(b, "©lyr")); assertEquals("Riff+", tagText(b, "©too"))
            val (cs, ce) = find(b, listOf("moov", "udta", "meta", "ilst", "covr", "data"))!!
            assertEquals(13L, u32(b, cs + 8)); assertEquals(cover.size, ce - cs - 16)
            assertEquals("mdir", String(b, find(b, listOf("moov", "udta", "meta", "hdlr"))!!.first + 16, 4))
            assertEquals("first-chunk", chunk(b, 0, wide)); assertEquals("second-chunk", chunk(b, 1, wide))
        }
    }

    @Test fun aMovieBoxAfterTheAudioKeepsItsOffsetsAndAnOldTagGoes() {
        val out = folder.newFile()
        Mp4Tags.write(movie(moovFirst = false, oldUdta = true), out, Mp4Tag("New", "Fixture"))
        val b = out.readBytes()
        assertEquals("New", tagText(b, "©nam")); assertNull(tagText(b, "©alb")); assertNull(tagText(b, "©lyr"))
        assertEquals(1, Regex("udta").findAll(String(b, Charsets.ISO_8859_1)).count())
        assertEquals("first-chunk", chunk(b, 0, false)); assertEquals("second-chunk", chunk(b, 1, false))
    }

    @Test fun longTextIsCutAndADamagedFileIsRefused() {
        val out = folder.newFile()
        Mp4Tags.write(movie(true), out, Mp4Tag("x".repeat(10_000), "Fixture"))
        assertEquals(Mp4Tags.MAX_TEXT, tagText(out.readBytes(), "©nam")!!.length)
        val damaged = folder.newFile().apply { writeBytes(box("ftyp", ByteArray(8)) + int(9999) + "moov".toByteArray()) }
        assertThrows(java.io.IOException::class.java) { Mp4Tags.write(damaged, folder.newFile(), Mp4Tag("a", "b")) }
        val noMovie = folder.newFile().apply { writeBytes(box("ftyp", ByteArray(8)) + box("mdat", ByteArray(4))) }
        assertThrows(java.io.IOException::class.java) { Mp4Tags.write(noMovie, folder.newFile(), Mp4Tag("a", "b")) }
        assertEquals("A B - C.m4a", m4aFileName(ProviderTrack("Ab-_12345cd", "A / B", "C")))
    }
}
