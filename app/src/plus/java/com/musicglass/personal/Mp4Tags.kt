package com.musicglass.personal

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Feature 15 (Riff+): iTunes-style tags (moov/udta/meta/ilst) written into an MPEG-4 file, as players and music
 * libraries read them. Small on purpose: the old udta is replaced, every other box is copied byte for byte, and when
 * the movie box comes before the audio, each chunk offset in it is moved by the bytes the tags added.
 */
internal data class Mp4Tag(val title: String, val artist: String, val album: String = "", val lyrics: String = "",
    val cover: ByteArray? = null, val tool: String = "Riff+")

internal object Mp4Tags {
    /** Containers walked through to find the chunk offsets. */
    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf")
    /** Longest text kept in one tag, and the largest cover. */
    const val MAX_TEXT = 4_000
    const val MAX_LYRICS = 100_000
    const val MAX_COVER = 4 * 1024 * 1024

    internal data class Box(val type: String, val start: Long, val size: Long)

    private fun u32(b: ByteArray, at: Int) = ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
        ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
    private fun u64(b: ByteArray, at: Int) = (u32(b, at) shl 32) or u32(b, at + 4)
    private fun putU32(b: ByteArray, at: Int, v: Long) { for (i in 0..3) b[at + i] = (v shr (24 - 8 * i)).toByte() }
    private fun putU64(b: ByteArray, at: Int, v: Long) { putU32(b, at, v ushr 32); putU32(b, at + 4, v and 0xFFFFFFFFL) }
    private fun int(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun typeBytes(type: String) = type.toByteArray(Charsets.ISO_8859_1).also { require(it.size == 4) }

    fun box(type: String, payload: ByteArray): ByteArray = int(8 + payload.size) + typeBytes(type) + payload

    /** The top-level boxes of a file, from their headers alone. */
    fun topLevel(file: RandomAccessFile): List<Box> {
        val boxes = ArrayList<Box>(); var at = 0L; val length = file.length(); val head = ByteArray(16)
        while (at + 8 <= length) {
            file.seek(at); file.readFully(head, 0, 8)
            var size = u32(head, 0); val type = String(head, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) { file.readFully(head, 8, 8); size = u64(head, 8) } else if (size == 0L) size = length - at
            if (size < 8 || at + size > length) throw IOException("Damaged MPEG-4 box $type")
            boxes += Box(type, at, size); at += size
            if (boxes.size > 10_000) throw IOException("Too many boxes")
        }
        return boxes
    }

    /** The children of a box held in [b] between [from] and [to]: type, start, end. */
    internal fun children(b: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>(); var at = from
        while (at + 8 <= to) {
            var size = u32(b, at); var header = 8
            if (size == 1L) { size = u64(b, at + 8); header = 16 } else if (size == 0L) size = (to - at).toLong()
            if (size < header || at + size > to) throw IOException("Damaged MPEG-4 box")
            out += Triple(String(b, at + 4, 4, Charsets.ISO_8859_1), at, (at + size).toInt()); at += size.toInt()
        }
        return out
    }

    /** Moves every chunk offset under [from]..[to] of [b] by [delta], in place. */
    private fun shiftOffsets(b: ByteArray, from: Int, to: Int, delta: Long) {
        for ((type, start, end) in children(b, from, to)) when (type) {
            in containers -> shiftOffsets(b, start + 8, end, delta)
            "stco" -> { val n = u32(b, start + 12).toInt(); if (start + 16 + 4L * n > end) throw IOException("Damaged stco")
                for (i in 0 until n) { val at = start + 16 + 4 * i; val v = u32(b, at) + delta
                    if (v > 0xFFFFFFFFL) throw IOException("Too large for 32-bit offsets"); putU32(b, at, v) } }
            "co64" -> { val n = u32(b, start + 12).toInt(); if (start + 16 + 8L * n > end) throw IOException("Damaged co64")
                for (i in 0 until n) { val at = start + 16 + 8 * i; putU64(b, at, u64(b, at) + delta) } }
        }
    }

    private fun text(type: String, value: String, limit: Int = MAX_TEXT) =
        box(type, box("data", int(1) + int(0) + value.take(limit).toByteArray(Charsets.UTF_8)))

    /** The udta box for [tag]: a meta box with its mdir handler and the item list. */
    fun udta(tag: Mp4Tag): ByteArray {
        var items = text("©nam", tag.title) + text("©ART", tag.artist)
        if (tag.album.isNotBlank()) items += text("©alb", tag.album)
        if (tag.lyrics.isNotBlank()) items += text("©lyr", tag.lyrics, MAX_LYRICS)
        items += text("©too", tag.tool)
        tag.cover?.takeIf { it.size in 8..MAX_COVER }?.let { cover ->
            val png = cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte()
            items += box("covr", box("data", int(if (png) 14 else 13) + int(0) + cover))
        }
        val hdlr = box("hdlr", int(0) + int(0) + "mdir".toByteArray() + "appl".toByteArray() + ByteArray(8) + byteArrayOf(0))
        return box("udta", box("meta", int(0) + hdlr + box("ilst", items)))
    }

    /** The movie box with its udta replaced by [tag]'s, offsets moved by [delta] once the new size is known. */
    fun retag(moov: ByteArray, tag: Mp4Tag, shift: Boolean): ByteArray {
        val parts = children(moov, 8, moov.size).filter { it.first != "udta" }.map { moov.copyOfRange(it.second, it.third) }
        val udta = udta(tag)
        val body = parts.fold(ByteArray(0)) { sum, part -> sum + part } + udta
        val out = box("moov", body)
        if (shift) shiftOffsets(out, 8, out.size, (out.size - moov.size).toLong())
        return out
    }

    /** Writes [source] to [output] with [tag]; [source] is not changed. */
    fun write(source: File, output: File, tag: Mp4Tag) {
        RandomAccessFile(source, "r").use { input ->
            val boxes = topLevel(input)
            val moov = boxes.singleOrNull { it.type == "moov" } ?: throw IOException("No movie box")
            if (moov.size > 64L * 1024 * 1024) throw IOException("Movie box too large")
            val mdat = boxes.firstOrNull { it.type == "mdat" } ?: throw IOException("No audio")
            val old = ByteArray(moov.size.toInt()).also { input.seek(moov.start); input.readFully(it) }
            if (u32(old, 0) == 1L) throw IOException("Unexpected 64-bit movie box")
            val tagged = retag(old, tag, shift = moov.start < mdat.start)
            output.outputStream().buffered().use { out ->
                val buffer = ByteArray(256 * 1024)
                for (box in boxes) {
                    if (box === moov) { out.write(tagged); continue }
                    input.seek(box.start); var left = box.size
                    while (left > 0) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); if (n < 0) throw IOException("Short read"); out.write(buffer, 0, n); left -= n }
                }
            }
        }
    }
}
