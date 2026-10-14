package com.musicglass.personal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.TextUnit

/** How long a held word takes to grow when it starts, and to settle back after it ends (Q16A). */
internal const val HELD_RISE_MS = 220L
internal const val HELD_RELEASE_MS = 380L

private fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

/** How far a held word is grown and raised at [nowMs]: 0 before it, 1 while it is sung, back to 0 after. */
internal fun heldEmphasis(nowMs: Long, word: LyricWord): Float = when {
    nowMs < word.startMs -> 0f
    nowMs < word.endMs -> smooth((nowMs - word.startMs).toFloat() / HELD_RISE_MS)
    else -> smooth(1 - (nowMs - word.endMs).toFloat() / HELD_RELEASE_MS)
}

/** The glow on a held word: it grows with the sweep and fades after the word ends. */
internal fun heldGlow(nowMs: Long, word: LyricWord): Float = when {
    nowMs < word.startMs -> 0f
    nowMs < word.endMs -> (0.25f + 0.75f * (nowMs - word.startMs).toFloat() / word.durationMs) *
        smooth((nowMs - word.startMs).toFloat() / HELD_RISE_MS)
    else -> smooth(1 - (nowMs - word.endMs).toFloat() / HELD_RELEASE_MS)
}

private data class LyricHighlight(val part: LyricPart, val path: Path, val bounds: Rect, val rtl: Boolean)

/** A held word on one display line, measured alone so it can be drawn grown and glowing without its neighbours. */
private class HeldWord(val word: LyricWord, val path: Path, val alone: TextLayoutResult, val topLeft: Offset, val rtl: Boolean)

/**
 * One sung line and, under it, its background vocal (Q16A). [active] lights the line; [backingActive] lights
 * the background vocal on its own timing. Only drawing reads the frame clock.
 */
@Composable
internal fun KaraokeLine(line: LyricLine, active: Boolean, backingActive: Boolean, position: () -> Long,
    motion: MotionPolicy, backingTag: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = RiffLyrics.linePad)
        .semantics { if (active) stateDescription = string(R.string.current_lyric) }) {
        if (line.text.isNotBlank() || line.backing == null)
            KaraokeText(line.text.ifBlank { "♪" }, line.parts, active, position, motion, RiffLyrics.text, RiffLyrics.textLine)
        line.backing?.let { backing ->
            KaraokeText(backing.text, backing.parts, backingActive, position, motion, RiffLyrics.backing, RiffLyrics.backingLine,
                Modifier.padding(top = RiffLyrics.backingGap).testTag(backingTag))
        }
    }
}

/** Text measurement stays cached through the whole line; a held word is measured once, when the line is laid out. */
@Composable
private fun KaraokeText(text: String, parts: List<LyricPart>, active: Boolean, position: () -> Long, motion: MotionPolicy,
    size: TextUnit, lineHeight: TextUnit, modifier: Modifier = Modifier) {
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = size, lineHeight = lineHeight, fontWeight = FontWeight.Bold))
    val measurer = rememberTextMeasurer(cacheSize = 4)
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val highlights = remember(layout, parts) {
        val measured = layout
        if (measured == null) emptyList() else parts.map { part ->
            val path = measured.getPathForRange(part.from, part.to)
            LyricHighlight(part, path, path.getBounds(), measured.getBidiRunDirection(part.from) == ResolvedTextDirection.Rtl)
        }
    }
    val held = remember(text, parts) { heldLyricWords(text, parts) }
    val heldWords = remember(layout, held, style) {
        val measured = layout
        if (measured == null) emptyList() else held.mapNotNull { word ->
            val row = measured.getLineForOffset(word.from)
            // A word broken across two display lines is not grown: scaling a two-line box looks wrong.
            if (measured.getLineForOffset(word.to - 1) != row) return@mapNotNull null
            val path = measured.getPathForRange(word.from, word.to)
            val bounds = path.getBounds()
            val alone = measurer.measure(text.substring(word.from, word.to), style, softWrap = false, maxLines = 1)
            val rtl = measured.getBidiRunDirection(word.from) == ResolvedTextDirection.Rtl
            val left = if (rtl) bounds.right - alone.size.width else bounds.left
            HeldWord(word, path, alone, Offset(left, measured.getLineBaseline(row) - alone.firstBaseline), rtl)
        }
    }
    val baseColor by animateColorAsState(if (active) White.copy(alpha = if (parts.isEmpty()) 1f else .38f)
        else White.copy(alpha = .40f), GlassMotion.timed(GlassMotion.selectionMs, motion), label = "lyric focus")
    val scale = animateFloatAsState(if (active && !motion.reduced) 1.025f else 1f,
        GlassMotion.timed(GlassMotion.selectionMs, motion), label = "lyric emphasis")
    Text(text, color = baseColor, style = style, onTextLayout = { layout = it },
        modifier = modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .drawWithContent {
                val measured = layout
                if (!active || measured == null) { drawContent(); return@drawWithContent }
                val now = position()
                val popping = heldWords.filter { now >= it.word.startMs && now < it.word.endMs + HELD_RELEASE_MS }
                if (popping.isEmpty()) drawContent()
                else {
                    // The held word is drawn again on its own, grown; the copy in the line must not show under it.
                    val cut = Path().apply { popping.forEach { addPath(it.path) } }
                    clipPath(cut, ClipOp.Difference) { this@drawWithContent.drawContent() }
                }
                for (highlight in highlights) {
                    val part = highlight.part
                    if (popping.any { part.from >= it.word.from && part.to <= it.word.to }) continue
                    val fraction = ((now - part.startMs).toFloat() / (part.endMs - part.startMs)).coerceIn(0f, 1f)
                    if (fraction <= 0) continue
                    val bounds = highlight.bounds
                    clipPath(highlight.path) {
                        clipRect(left = if (highlight.rtl) bounds.right - bounds.width * fraction else bounds.left,
                            top = bounds.top, right = if (highlight.rtl) bounds.right else bounds.left + bounds.width * fraction,
                            bottom = bounds.bottom) {
                            drawText(measured, color = White)
                        }
                    }
                }
                for (word in popping) drawHeldWord(word, now, baseColor, motion.reduced)
            })
}

/** Grown and raised around its own centre (not under reduced motion), white where sung, with a pink glow. */
private fun DrawScope.drawHeldWord(held: HeldWord, now: Long, dim: Color, reduced: Boolean) {
    val word = held.word
    val emphasis = if (reduced) 0f else heldEmphasis(now, word)
    val glow = heldGlow(now, word)
    val sweep = ((now - word.startMs).toFloat() / word.durationMs).coerceIn(0f, 1f)
    val width = held.alone.size.width.toFloat()
    val height = held.alone.size.height.toFloat()
    val grow = 1f + (RiffLyrics.heldScale - 1f) * emphasis
    val centre = Offset(held.topLeft.x + width / 2, held.topLeft.y + height / 2)
    val glowRadius = RiffLyrics.heldGlow.toPx()
    val soft = RiffLyrics.sweepEdge.toPx()
    val rise = RiffLyrics.heldRise.toPx() * emphasis
    withTransform({ translate(top = -rise); scale(grow, grow, centre) }) {
        drawText(held.alone, color = dim, topLeft = held.topLeft)
        val pad = glowRadius + soft
        val area = Rect(held.topLeft.x - pad, held.topLeft.y - pad, held.topLeft.x + width + pad, held.topLeft.y + height + pad)
        drawContext.canvas.saveLayer(area, Paint())
        drawText(held.alone, color = White, topLeft = held.topLeft,
            shadow = if (glow > 0f) Shadow(RiffLyrics.glow.copy(alpha = .9f * glow), Offset.Zero, glowRadius * glow) else null)
        // Keep what has been sung; past the sweep the letters and their glow fade out over a short soft edge.
        val keep = if (sweep >= 1f) {
            Brush.horizontalGradient(listOf(Color.Black, Color.Black), startX = area.left, endX = area.right)
        } else if (held.rtl) {
            val edge = held.topLeft.x + width * (1 - sweep)
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = edge - soft / 2, endX = edge + soft / 2)
        } else {
            val edge = held.topLeft.x + width * sweep
            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = edge - soft / 2, endX = edge + soft / 2)
        }
        drawRect(keep, topLeft = area.topLeft, size = area.size, blendMode = BlendMode.DstIn)
        drawContext.canvas.restore()
    }
}
