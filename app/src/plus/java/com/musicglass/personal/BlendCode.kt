package com.musicglass.personal

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.time.LocalDate
import java.util.zip.CRC32

/*
 * Feature 24 (Riff+): Blend with a friend by QR. Each phone shows a code of its top songs and scans the other's; both
 * then make the same playlist from the two lists, with no server and no account between them. A code is readable by
 * anyone who sees it, so it carries only song ids in order: no name, account, play counts, times or files.
 *
 * The code (version 1): "R" "B", version, how many songs (1 to 100), each song's YouTube id in 8 bytes, a CRC-32 of
 * all that: 812 bytes for 100 songs. It is written in Base45 (RFC 9285), the QR code's own alphanumeric alphabet, so
 * it reads back byte for byte on any scanner; it is not a link, and nothing in it is ever opened.
 */

/** The owner's choice (Q24, 8 November): up to 100 songs from each person. */
internal const val BLEND_SONGS = 100
/** Fewer than this and there is too little to share. */
internal const val BLEND_MIN_SONGS = 5
/** What the songs are taken from: listening time in the last year, so the code is today's taste, not a decade's. */
internal const val BLEND_DAYS = 365L
internal const val BLEND_VERSION = 1
private const val BLEND_HEADER = 4
private const val BLEND_CHECK = 4
/** A longer text than the largest version-1 code is refused before it is decoded. */
internal const val BLEND_MAX_TEXT = (BLEND_HEADER + 8 * BLEND_SONGS + BLEND_CHECK) * 3 / 2 + 2
/** Reciprocal-rank fusion's constant: rank 1 counts about twice rank 11, so both people's favourites lead. */
private const val BLEND_RANK_K = 10.0

private const val ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

/**
 * A YouTube id in 8 bytes: its eleven base-64 characters carry 66 bits, and the last always ends in two zero bits
 * (only "AEIMQUYcgkosw048" end an id), so 64 are left. Null for anything that is not such an id.
 */
internal fun packVideoId(id: String): Long? {
    if (!VIDEO_ID.matches(id)) return null
    var bits = 0L
    for (i in 0 until 10) bits = (bits shl 6) or ID_ALPHABET.indexOf(id[i]).toLong()
    val last = ID_ALPHABET.indexOf(id[10])
    if (last and 3 != 0) return null
    return (bits shl 4) or (last shr 2).toLong()
}

internal fun unpackVideoId(bits: Long): String {
    val chars = CharArray(11)
    chars[10] = ID_ALPHABET[((bits and 15) shl 2).toInt()]
    var rest = bits ushr 4
    for (i in 9 downTo 0) { chars[i] = ID_ALPHABET[(rest and 63).toInt()]; rest = rest ushr 6 }
    return String(chars)
}

private const val BASE45 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

internal fun base45Encode(bytes: ByteArray): String = buildString {
    var i = 0
    while (i + 1 < bytes.size) {
        val n = (bytes[i].toInt() and 255) * 256 + (bytes[i + 1].toInt() and 255)
        append(BASE45[n % 45]); append(BASE45[n / 45 % 45]); append(BASE45[n / 2025]); i += 2
    }
    if (i < bytes.size) { val n = bytes[i].toInt() and 255; append(BASE45[n % 45]); append(BASE45[n / 45]) }
}

/** Null for text that is not Base45. */
internal fun base45Decode(text: String): ByteArray? {
    if (text.length % 3 == 1) return null
    val out = ByteArray(text.length / 3 * 2 + if (text.length % 3 == 2) 1 else 0)
    var o = 0; var i = 0
    while (i < text.length) {
        val c = BASE45.indexOf(text[i]); val d = BASE45.indexOf(text[i + 1])
        if (c < 0 || d < 0) return null
        if (i + 2 < text.length) {
            val e = BASE45.indexOf(text[i + 2]); if (e < 0) return null
            val n = c + d * 45 + e * 2025; if (n > 65535) return null
            out[o++] = (n shr 8).toByte(); out[o++] = n.toByte(); i += 3
        } else {
            val n = c + d * 45; if (n > 255) return null
            out[o++] = n.toByte(); i += 2
        }
    }
    return out
}

/** Why a scanned code was refused. */
internal enum class BlendCodeProblem { NotABlend, NewerVersion, Damaged }

/** A scanned code: the songs, or why it was refused. */
internal sealed interface BlendRead {
    data class Songs(val ids: List<String>) : BlendRead
    data class Refused(val problem: BlendCodeProblem) : BlendRead
}

/** The code for [ids] (best first): ids that cannot be packed are skipped, repeats dropped, at most [BLEND_SONGS]. */
internal fun encodeBlend(ids: List<String>): String {
    val packed = ids.distinct().mapNotNull(::packVideoId).take(BLEND_SONGS)
    require(packed.isNotEmpty()) { "no songs" }
    val bytes = java.nio.ByteBuffer.allocate(BLEND_HEADER + 8 * packed.size + BLEND_CHECK)
    bytes.put('R'.code.toByte()).put('B'.code.toByte()).put(BLEND_VERSION.toByte()).put(packed.size.toByte())
    packed.forEach { bytes.putLong(it) }
    val crc = CRC32().apply { update(bytes.array(), 0, bytes.position()) }.value
    bytes.putInt(crc.toInt())
    return base45Encode(bytes.array())
}

/** Reads a scanned text. Anything but a whole, unaltered version-1 code is refused; nothing in it is ever opened. */
internal fun decodeBlend(text: String): BlendRead {
    val refuse = { p: BlendCodeProblem -> BlendRead.Refused(p) }
    if (text.length > BLEND_MAX_TEXT || text.length < 8) return refuse(BlendCodeProblem.NotABlend)
    val bytes = base45Decode(text) ?: return refuse(BlendCodeProblem.NotABlend)
    if (bytes.size < BLEND_HEADER + 8 + BLEND_CHECK || bytes[0] != 'R'.code.toByte() || bytes[1] != 'B'.code.toByte()) return refuse(BlendCodeProblem.NotABlend)
    if (bytes[2].toInt() != BLEND_VERSION) return refuse(if (bytes[2] > BLEND_VERSION) BlendCodeProblem.NewerVersion else BlendCodeProblem.NotABlend)
    val count = bytes[3].toInt() and 255
    if (count !in 1..BLEND_SONGS || bytes.size != BLEND_HEADER + 8 * count + BLEND_CHECK) return refuse(BlendCodeProblem.Damaged)
    val buffer = java.nio.ByteBuffer.wrap(bytes)
    val crc = CRC32().apply { update(bytes, 0, bytes.size - BLEND_CHECK) }.value.toInt()
    if (buffer.getInt(bytes.size - BLEND_CHECK) != crc) return refuse(BlendCodeProblem.Damaged)
    buffer.position(BLEND_HEADER)
    return BlendRead.Songs(List(count) { unpackVideoId(buffer.long) }.distinct())
}

/** A song of the blend: in which of the two lists, for the "you both play" count. */
internal data class BlendSong(val id: String, val shared: Boolean)

/**
 * The blend of two lists, the same whichever phone makes it: each song scores 1 / (10 + rank) in each list it is in,
 * so the two people's favourites alternate from the top and a song both play rises well above either person's songs of
 * the same rank (at ranks 41 and 51 it sits among the top 17s). Ties go to the better single rank, then the id. At most
 * [limit] songs.
 */
internal fun blendLists(a: List<String>, b: List<String>, limit: Int = BLEND_SONGS): List<BlendSong> {
    val score = HashMap<String, Double>(); val best = HashMap<String, Int>(); val lists = HashMap<String, Int>()
    for (list in listOf(a.distinct(), b.distinct())) list.forEachIndexed { rank, id ->
        score[id] = (score[id] ?: 0.0) + 1.0 / (BLEND_RANK_K + rank + 1)
        best[id] = minOf(best[id] ?: Int.MAX_VALUE, rank)
        lists[id] = (lists[id] ?: 0) + 1
    }
    return score.keys.sortedWith(compareByDescending<String> { score.getValue(it) }.thenBy { best.getValue(it) }.thenBy { it })
        .take(limit).map { BlendSong(it, lists.getValue(it) > 1) }
}

/** The code as squares: error correction M, a quiet zone of four, as ZXing writes it. */
internal fun blendMatrix(text: String): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))

/** A QR code's text in [source], or null when there is none (or it cannot be read). */
internal fun readQr(source: LuminanceSource): String? = try {
    QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)),
        mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text
} catch (_: NotFoundException) { null } catch (_: ChecksumException) { null } catch (_: FormatException) { null }

/** One of the songs a code would carry, with what the preview shows of it. */
internal data class BlendEntry(val id: String, val title: String, val artist: String)

/**
 * The songs this phone would share: YouTube Music songs by listening time in the last [BLEND_DAYS] days, best first,
 * without Never play and without ids a code cannot carry. Read only; call off the main thread.
 */
internal fun blendSnapshot(db: SQLiteDatabase, scopes: List<String>, today: LocalDate, blocked: (String) -> Boolean): List<BlendEntry> {
    if (scopes.isEmpty()) return emptyList()
    val scopeIn = scopes.joinToString(",", "scope IN (", ")") { "?" }
    return try {
        db.rawQuery("SELECT key,MAX(title),MAX(subtitle),SUM(listened_ms) AS ms,SUM(plays) AS plays FROM stats_daily " +
            "WHERE $scopeIn AND kind=? AND day>=? AND key LIKE 'yt:%' GROUP BY key HAVING ms>0 OR plays>0 ORDER BY ms DESC,plays DESC,key LIMIT ${BLEND_SONGS * 2}",
            (scopes + EncoreKind.SONG + today.minusDays(BLEND_DAYS).toString()).toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(BlendEntry(c.getString(0).removePrefix("yt:"), c.getString(1).orEmpty(), c.getString(2).orEmpty())) }
        }.filter { packVideoId(it.id) != null && !blocked(it.id) }.distinctBy { it.id }.take(BLEND_SONGS)
    } catch (_: SQLiteException) { emptyList() }
}
