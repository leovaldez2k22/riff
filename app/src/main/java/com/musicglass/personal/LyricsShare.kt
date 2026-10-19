package com.musicglass.personal

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.PathParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** F2: up to this many lines go on one lyric card. */
internal const val LYRIC_SHARE_MAX_LINES = 5
/** F2: a line has to be held this long (or the phone's own touch-and-hold delay, if longer) to start sharing. */
internal const val LYRIC_HOLD_MS = 650L
/** F2: how small a held line gets before it pops back. */
internal const val LYRIC_HOLD_SCALE = .9f

/**
 * F2: the lines chosen for a card. The first line held stays chosen; tapping another line takes in every line between
 * them, above or below, up to [max] lines counted from the first. Tapping the first line again leaves only it.
 */
internal fun lyricShareSelection(anchor: Int, tapped: Int, lineCount: Int, max: Int = LYRIC_SHARE_MAX_LINES): IntRange {
    val target = tapped.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    return if (target >= anchor) anchor..minOf(target, anchor + max - 1) else maxOf(target, anchor - max + 1)..anchor
}

/** F2: what a line puts on the card: its words, or its backing vocal when that is all it has; blank lines put nothing. */
internal fun lyricShareText(line: LyricLine): String? =
    line.text.trim().ifBlank { line.backing?.text?.trim().orEmpty() }.takeIf { it.isNotBlank() }

/** F2: the card's three looks, picked on the share sheet. */
internal enum class LyricCardLook { Cover, Dark, Pink, Aurora }

/**
 * F2: a lyric line while sharing: dimmed when not chosen, a rounded pink tint behind it when chosen. Q18-037: nothing
 * is clipped, so the sung line's grow and a held word's pop and glow can reach past the line's edges; the tint is drawn
 * a little larger than the line instead, so the words never touch its edge.
 */
internal fun Modifier.lyricLineLook(dim: Boolean, chosen: Boolean): Modifier =
    graphicsLayer { alpha = if (dim) .4f else 1f }
        .drawBehind {
            if (!chosen) return@drawBehind
            val side = RiffLyrics.chosenSide.toPx(); val end = RiffLyrics.chosenEnd.toPx()
            drawRoundRect(Pink.copy(alpha = .16f), Offset(-side, -end), Size(size.width + 2 * side, size.height + 2 * end),
                CornerRadius(RiffLyrics.chosenCorner.toPx()))
        }

/** Riff's two waves (res/drawable/ic_launcher_foreground.xml), drawn in the card's corner instead of the record. */
private const val RIFF_WAVES = "M27,49 C37,57 43,41 51,34 C61,25 71,30 78,41 C66,34 61,43 53,51 C43,61 34,60 27,49 Z " +
    "M29,64 C41,73 47,59 55,52 C66,41 76,46 81,56 C68,49 64,63 56,70 C46,80 36,76 29,64 Z"

/**
 * F2: draws the card: the chosen lines large, the song and artist at the bottom left, Riff's waves and name at the
 * bottom right. [cover] is the artwork's colour, used by [LyricCardLook.Cover]. Square at least, taller for long lines.
 */
internal fun renderLyricCard(lines: List<String>, title: String, artist: String, look: LyricCardLook, cover: Int,
    width: Int = 1080): Bitmap {
    val pad = width * .09f
    val textWidth = (width - 2 * pad).toInt()
    val ink = if (look == LyricCardLook.Pink) 0xFF14070D.toInt() else 0xFFFFFFFF.toInt()
    val soft = if (look == LyricCardLook.Pink) 0xB314070D.toInt() else 0xB3FFFFFF.toInt()
    // Riff pink would vanish on the Aurora look's pink corner, so its waves are white.
    val accent = when (look) { LyricCardLook.Pink -> 0xFF14070D.toInt(); LyricCardLook.Aurora -> 0xFFFFFFFF.toInt(); else -> Pink.toArgb() }
    val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; textSize = width * .062f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val words = lines.joinToString("\n")
    val lyrics = StaticLayout.Builder.obtain(words, 0, words.length, lyricPaint, textWidth)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(width * .018f, 1f).build()
    val footer = width * .1f
    val height = maxOf(width, (pad * 1.4f + lyrics.height + pad + footer + pad).toInt())
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (look == LyricCardLook.Aurora) drawAurora(canvas, width, height) else {
        val (top, bottom) = when (look) {
            LyricCardLook.Cover -> {
                val tone = Color(cover).readableArtworkTone()
                tone.toArgb() to Color(tone.red * .35f, tone.green * .35f, tone.blue * .35f).toArgb()
            }
            LyricCardLook.Dark -> 0xFF231524.toInt() to 0xFF0B0C12.toInt()
            else -> 0xFFFF7DBD.toInt() to 0xFFF45B9A.toInt()
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
    }
    // The glass sheen Riff's panels have: light from the top left, gone by the middle.
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
        shader = LinearGradient(0f, 0f, width * .6f, height * .6f, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    })
    // A short rule above the words, then the words, centred in the space above the footer.
    val room = height - pad - footer - pad
    val lyricsTop = pad + ((room - pad - lyrics.height) / 2f).coerceAtLeast(0f) + pad * .4f
    canvas.drawRoundRect(pad, lyricsTop - pad * .55f, pad + width * .08f, lyricsTop - pad * .55f + width * .009f,
        width * .005f, width * .005f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
    canvas.save(); canvas.translate(pad, lyricsTop); lyrics.draw(canvas); canvas.restore()
    // Riff's waves and name, bottom right.
    val baseline = height - pad
    val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; textSize = width * .042f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val brandWidth = brandPaint.measureText("Riff")
    val logo = width * .075f
    val logoLeft = width - pad - brandWidth - width * .015f - logo
    val waves = PathParser.createPathFromPathData(RIFF_WAVES)
    canvas.save()
    canvas.translate(logoLeft, baseline - logo * .82f)
    canvas.scale(logo / 54f, logo / 54f)
    canvas.translate(-27f, -26f)
    canvas.drawPath(waves, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
    canvas.restore()
    canvas.drawText("Riff", width - pad - brandWidth, baseline - logo * .2f, brandPaint)
    // The song and artist, bottom left, cut short before the logo.
    val labelWidth = logoLeft - pad - width * .04f
    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; textSize = width * .034f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = width * .03f }
    canvas.drawText(TextUtils.ellipsize(title, titlePaint, labelWidth, TextUtils.TruncateAt.END).toString(),
        pad, baseline - width * .045f, titlePaint)
    canvas.drawText(TextUtils.ellipsize(artist, artistPaint, labelWidth, TextUtils.TruncateAt.END).toString(),
        pad, baseline, artistPaint)
    return bitmap
}

/** Q18-036: the owner's mockup card: deep navy, blue light from the top left, pink from the bottom right, violet between. */
private fun drawAurora(canvas: Canvas, width: Int, height: Int) {
    val w = width.toFloat(); val h = height.toFloat(); val reach = maxOf(w, h)
    canvas.drawColor(0xFF10143A.toInt())
    canvas.drawRect(0f, 0f, w, h, Paint().apply {
        shader = RadialGradient(w * .04f, h * .04f, reach * .85f, intArrayOf(0xFF295AD9.toInt(), 0x8C2A4FC4.toInt(), 0x002A4FC4),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
    })
    canvas.drawRect(0f, 0f, w, h, Paint().apply {
        shader = RadialGradient(w * .98f, h * .98f, reach * .95f,
            intArrayOf(0xFFC44B8E.toInt(), 0xD9A4468F.toInt(), 0x805E3F8B.toInt(), 0x005E3F8B), floatArrayOf(0f, .3f, .65f, 1f), Shader.TileMode.CLAMP)
    })
}

/**
 * Q18-048: a new name for every card, and the earlier cards removed. Android's share sheet keeps the preview of an
 * address it has shown, so one fixed name made it show an older card in place of this one.
 */
private fun lyricCardFile(context: Context): File {
    val folder = File(context.cacheDir, "shared_lyrics").apply { mkdirs() }
    folder.listFiles()?.forEach { it.delete() }
    return folder.resolve("riff-lyrics-${System.currentTimeMillis()}.png")
}

/** F2: hands the card to Android's share sheet through Riff's file provider; the file lives in the cache only. */
internal fun shareLyricCard(context: Context, card: Bitmap): Boolean = runCatching {
    context.startActivity(Intent.createChooser(lyricCardShareIntent(context, card), string(R.string.share_lyrics))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}.isSuccess

/** The card written to the cache and the intent that offers it: a content:// address the receiver may only read. */
internal fun lyricCardShareIntent(context: Context, card: Bitmap): Intent {
    val file = lyricCardFile(context)
    file.outputStream().use { check(card.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    return Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).also { it.clipData = ClipData.newRawUri(null, uri) }
}

/** F2: saves the card to Pictures/Riff. Needs no permission: Riff only adds its own picture (Android 10 and later). */
internal fun saveLyricCard(context: Context, card: Bitmap, title: String): Boolean = runCatching {
    val resolver = context.contentResolver
    val name = "Riff lyrics ${title.replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().take(40)} ${System.currentTimeMillis()}.png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Riff")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
    try {
        resolver.openOutputStream(uri)!!.use { check(card.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (error: Throwable) {
        resolver.delete(uri, null, null); throw error
    }
}.isSuccess

/** F2: a held lyric line gives way at least [LYRIC_HOLD_MS] before it counts as a long press. */
@Composable internal fun rememberLyricHoldConfiguration(): ViewConfiguration {
    val base = LocalViewConfiguration.current
    return remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long get() = maxOf(base.longPressTimeoutMillis, LYRIC_HOLD_MS)
        }
    }
}

/**
 * F2: a lyric line's touch. A tap does [onTap] (seek, or choose while sharing). Held, the line slowly shrinks; when
 * the hold counts it pops back to its size and [onLongPress] runs. TalkBack gets both as actions.
 */
@Composable internal fun Modifier.lyricLineTouch(onTap: (() -> Unit)?, tapLabel: String, onLongPress: (() -> Unit)?,
    longPressLabel: String): Modifier {
    val motion = LocalMotionPolicy.current
    val holdMs = LocalViewConfiguration.current.longPressTimeoutMillis.toInt()
    var holding by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (holding) LYRIC_HOLD_SCALE else 1f,
        when {
            motion.reduced -> snap()
            holding -> tween(holdMs - 90, delayMillis = 90, easing = LinearOutSlowInEasing)
            else -> spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
        }, label = "lyric hold")
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val canTap = onTap != null; val canHold = onLongPress != null
    return graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, .5f) }
        .pointerInput(canTap, canHold) {
            if (!canTap && !canHold) return@pointerInput
            detectTapGestures(
                onPress = { if (canHold) { holding = true; try { tryAwaitRelease() } finally { holding = false } } },
                onLongPress = if (canHold) { _ -> holding = false; longPress?.invoke() } else null,
                onTap = if (canTap) { _ -> tap?.invoke() } else null)
        }
        .semantics {
            if (canTap) onClick(tapLabel) { tap?.invoke(); true }
            if (canHold) onLongClick(longPressLabel) { longPress?.invoke(); true }
        }
}

/** F2: the share sheet: the card as it will look, its four looks, Save and Share. */
@Composable internal fun LyricShareSheet(lines: List<String>, title: String, artist: String, cover: Color, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var look by remember { mutableStateOf(LyricCardLook.Cover) }
    var status by remember { mutableStateOf<String?>(null) }
    val coverArgb = cover.toArgb()
    val card by produceState<Bitmap?>(null, lines, title, artist, look, coverArgb) {
        value = withContext(Dispatchers.Default) { renderLyricCard(lines, title, artist, look, coverArgb) }
    }
    FrostedAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("lyrics_share_sheet"),
        title = { Text(string(R.string.share_lyrics)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RiffLyrics.sheetGap)) {
                Box(Modifier.fillMaxWidth().aspectRatio(card?.let { it.width / it.height.toFloat() } ?: 1f)
                    .clip(RoundedCornerShape(RiffLyrics.cardCorner)).testTag("lyrics_share_preview")) {
                    card?.let { Image(it.asImageBitmap(), string(R.string.lyric_card_preview), contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
                    LyricCardLook.entries.forEach { option ->
                        FilterChip(look == option, { uiHaptic(HapticCue.Selection); look = option; status = null },
                            label = { Text(string(when (option) {
                                LyricCardLook.Cover -> R.string.lyric_card_cover
                                LyricCardLook.Dark -> R.string.lyric_card_dark
                                LyricCardLook.Pink -> R.string.lyric_card_pink
                                LyricCardLook.Aurora -> R.string.lyric_card_aurora
                            })) }, modifier = Modifier.testTag("lyrics_share_look_${option.name.lowercase()}"))
                    }
                }
                status?.let { Text(it, color = Muted, modifier = Modifier.testTag("lyrics_share_status")) }
            }
        },
        confirmButton = {
            Button({ val image = card ?: return@Button; uiHaptic(HapticCue.Confirm)
                if (shareLyricCard(context, image)) onDismiss() else status = string(R.string.couldn_t_share_lyrics) },
                enabled = card != null, colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Color.Black),
                modifier = Modifier.testTag("lyrics_share_send")) { Text(string(R.string.share)) }
        },
        dismissButton = {
            TextButton({ val image = card ?: return@TextButton; uiHaptic()
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { saveLyricCard(context, image, title) }
                    uiHaptic(if (saved) HapticCue.Confirm else HapticCue.Reject)
                    status = string(if (saved) R.string.lyric_card_saved else R.string.couldn_t_save_lyric_card)
                }
            }, enabled = card != null, modifier = Modifier.testTag("lyrics_share_save")) { Text(string(R.string.save_to_photos), color = White) }
        })
}
