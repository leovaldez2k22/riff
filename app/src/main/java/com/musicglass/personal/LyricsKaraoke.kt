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
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.graphics.drawscope.translate
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
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.sin

/** How long a held word takes to grow when it starts, and to settle back after it ends (Q16A). */
internal const val HELD_RISE_MS = 220L
internal const val HELD_RELEASE_MS = 380L

/** How long a line takes to turn white when it starts: one or two frames, so it is lit when it is sung. */
internal const val LYRIC_LIGHT_MS = 32

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

/** How long a letter takes to pop up once the sweep reaches it, and how long a held letter's shake takes to start and stop (Q16B). */
internal const val LETTER_POP_MS = 180L
internal const val SHAKE_FADE_MS = 120L

/** How far a letter has popped up, as a share of [RiffLyrics.letterRise]: 0 before the sweep reaches it, a little over 1 on the way, then 1 for good. */
internal fun letterRise(nowMs: Long, litMs: Long): Float {
    if (nowMs <= litMs) return 0f
    val t = ((nowMs - litMs).toFloat() / LETTER_POP_MS).coerceAtMost(1f) - 1
    return 1 + 2.70158f * t * t * t + 1.70158f * t * t
}

/** A letter's size while it pops: 1 before and after, up to [RiffLyrics.letterSwell] in between. */
internal fun letterSwell(nowMs: Long, litMs: Long): Float {
    if (nowMs <= litMs || nowMs >= litMs + LETTER_POP_MS) return 1f
    return 1 + (RiffLyrics.letterSwell - 1) * sin(PI * (nowMs - litMs) / LETTER_POP_MS).toFloat()
}

/**
 * A held word's letter shaking, as a share of [RiffLyrics.heldShake] in each direction: nothing before the letter is
 * lit or once the word has ended, a quick fade in and out, and each letter ([seed]) out of step with the others.
 */
internal fun letterShake(nowMs: Long, litMs: Long, word: LyricWord, seed: Int): Offset {
    if (nowMs < litMs || nowMs >= word.endMs) return Offset.Zero
    val amount = smooth((nowMs - litMs).toFloat() / SHAKE_FADE_MS) * smooth((word.endMs - nowMs).toFloat() / SHAKE_FADE_MS)
    val seconds = nowMs / 1000.0
    return Offset((amount * sin(2 * PI * 11 * seconds + seed * 1.7)).toFloat(), (amount * sin(2 * PI * 13 * seconds + seed * 2.3)).toFloat())
}

private data class LyricHighlight(val part: LyricPart, val path: Path, val bounds: Rect, val rtl: Boolean)

/** One letter (Q16B): its column, the strip it is drawn through, and when the sweep reaches its middle. */
private class LyricLetter(val column: Rect, val litMs: Long)

/** The letters of a word-synced line, all columns together, and when the first and last are lit. */
private class LyricLetters(val letters: List<LyricLetter>, val all: Path) {
    val firstMs = letters.minOfOrNull { it.litMs } ?: Long.MAX_VALUE
    val lastMs = letters.maxOfOrNull { it.litMs } ?: Long.MIN_VALUE
}

/** Letters as the reader sees them (a base and its marks together), with their character ranges. Spaces are left out. */
private fun graphemes(text: String): List<IntRange> = buildList {
    val breaks = BreakIterator.getCharacterInstance().apply { setText(text) }
    var start = breaks.first()
    var end = breaks.next()
    while (end != BreakIterator.DONE) {
        if (text.substring(start, end).isNotBlank()) add(start until end)
        start = end; end = breaks.next()
    }
}

/** Where the sweep is across [bounds] at [x], from 0 to 1, in the reading direction. */
private fun sweepShare(x: Float, bounds: Rect, rtl: Boolean) =
    if (bounds.width <= 0f) 0f else ((if (rtl) bounds.right - x else x - bounds.left) / bounds.width).coerceIn(0f, 1f)

private fun lyricLetters(text: String, layout: TextLayoutResult, highlights: List<LyricHighlight>): LyricLetters {
    val letters = graphemes(text).mapNotNull { range ->
        val highlight = highlights.firstOrNull { range.first >= it.part.from && range.first < it.part.to } ?: return@mapNotNull null
        val row = layout.getLineForOffset(range.first)
        val box = range.map { layout.getBoundingBox(it) }.reduce { a, b -> Rect(minOf(a.left, b.left), a.top, maxOf(a.right, b.right), a.bottom) }
        val part = highlight.part
        LyricLetter(Rect(box.left, layout.getLineTop(row), box.right, layout.getLineBottom(row)),
            part.startMs + ((part.endMs - part.startMs) * sweepShare(box.center.x, highlight.bounds, highlight.rtl)).toLong())
    }
    return LyricLetters(letters, Path().apply { letters.forEach { addRect(it.column) } })
}

/**
 * A held word's letter (Q16B), measured on its own and drawn whole at [at], so its glow is never cut. [column] is
 * where it sits in the line; it is lit when the word's own sweep reaches its middle.
 */
private class HeldLetter(val glyph: TextLayoutResult, val at: Offset, val column: Rect, val litMs: Long)

/** A held word on one display line, measured alone so it can be drawn grown and glowing without its neighbours. */
private class HeldWord(val word: LyricWord, val path: Path, val alone: TextLayoutResult, val topLeft: Offset, val rtl: Boolean,
    val letters: List<HeldLetter>)

private val separateScripts = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK,
    Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED, Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)

/** Whether a word's letters keep their shapes when drawn one by one. Joined scripts (Arabic, Devanagari and the like) do not. */
private fun separable(word: String) = word.codePoints().allMatch { Character.UnicodeScript.of(it) in separateScripts }

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
    // Feature 3: sung lines are display-sized, so they take Inter Display, tracked in a little.
    val style = LocalTextStyle.current.merge(TextStyle(fontFamily = InterDisplayFamily, fontSize = size, lineHeight = lineHeight,
        fontWeight = FontWeight.Bold, letterSpacing = RiffLyrics.tracking))
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
            val topLeft = Offset(left, measured.getLineBaseline(row) - alone.firstBaseline)
            val whole = Rect(topLeft, Size(alone.size.width.toFloat(), alone.size.height.toFloat()))
            val spelled = text.substring(word.from, word.to)
            val pieces = if (separable(spelled)) graphemes(spelled) else listOf(spelled.indices)
            val letters = pieces.map { range ->
                val box = range.map { alone.getBoundingBox(it) }.reduce { a, b -> Rect(minOf(a.left, b.left), a.top, maxOf(a.right, b.right), a.bottom) }
                val column = Rect(topLeft.x + box.left, whole.top, topLeft.x + box.right, whole.bottom)
                val single = pieces.size == 1
                HeldLetter(if (single) alone else measurer.measure(spelled.substring(range.first, range.last + 1), style, softWrap = false, maxLines = 1),
                    if (single) topLeft else Offset(column.left, topLeft.y), column,
                    word.startMs + (word.durationMs * sweepShare(column.center.x, whole, rtl)).toLong())
            }
            HeldWord(word, path, alone, topLeft, rtl, letters)
        }
    }
    val letters = remember(layout, highlights) { layout?.let { if (highlights.isEmpty()) null else lyricLetters(text, it, highlights) } }
    // Q18-056: a line lights at once when it starts (a slow fade read as late); it dims at the usual pace.
    val baseColor by animateColorAsState(if (active) White.copy(alpha = if (parts.isEmpty()) 1f else .38f)
        else White.copy(alpha = .40f), GlassMotion.timed(if (active) LYRIC_LIGHT_MS else GlassMotion.selectionMs, motion), label = "lyric focus")
    val scale = animateFloatAsState(if (active && !motion.reduced) 1.025f else 1f,
        GlassMotion.timed(GlassMotion.selectionMs, motion), label = "lyric emphasis")
    Text(text, color = baseColor, style = style, onTextLayout = { layout = it },
        modifier = modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .drawWithContent {
                val measured = layout
                val lifted = letters
                val raise = if (motion.reduced) 0f else RiffLyrics.letterRise.toPx()
                if (measured == null || (!active && (lifted == null || raise == 0f))) { drawContent(); return@drawWithContent }
                val now = position()
                val popping = if (!active) emptyList() else heldWords.filter { now >= it.word.startMs && now < it.word.endMs + HELD_RELEASE_MS }
                // The held word is drawn again on its own, grown; the copy in the line must not show under it.
                val cut = if (popping.isEmpty()) null else Path().apply { popping.forEach { addPath(it.path) } }
                // The line as it stands now: dim letters, and while it is current, white where it has been sung.
                fun DrawScope.paint() {
                    if (cut == null) this@drawWithContent.drawContent() else clipPath(cut, ClipOp.Difference) { this@drawWithContent.drawContent() }
                    if (!active) return
                    for (highlight in highlights) {
                        val part = highlight.part
                        if (popping.any { part.from < it.word.to && part.to > it.word.from }) continue
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
                }
                when {
                    // Q16B: each letter pops up as the sweep reaches it and stays up. Nothing lit yet, or no word timing: as it is.
                    lifted == null || raise == 0f || now <= lifted.firstMs -> paint()
                    // Every letter is up: one pass for all of them, and one for anything between (spaces, untimed text).
                    now >= lifted.lastMs + LETTER_POP_MS -> {
                        clipPath(lifted.all, ClipOp.Difference) { paint() }
                        translate(top = -raise) { clipPath(lifted.all) { paint() } }
                    }
                    else -> {
                        val up = Path()
                        val moving = ArrayList<LyricLetter>()
                        for (letter in lifted.letters) when {
                            now <= letter.litMs -> Unit
                            now >= letter.litMs + LETTER_POP_MS -> up.addRect(letter.column)
                            else -> moving += letter
                        }
                        val down = Path().apply { addPath(up); moving.forEach { addRect(it.column) } }
                        clipPath(down, ClipOp.Difference) { paint() }
                        translate(top = -raise) { clipPath(up) { paint() } }
                        for (letter in moving) {
                            val swell = letterSwell(now, letter.litMs)
                            withTransform({ translate(top = -raise * letterRise(now, letter.litMs)); scale(swell, swell, letter.column.center) }) {
                                clipRect(letter.column.left, letter.column.top, letter.column.right, letter.column.bottom) { paint() }
                            }
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
    val pad = glowRadius + soft
    // Q16B: letter by letter, each popped up like the rest of the line and shaking while the word is sung.
    val lift = if (reduced) 0f else RiffLyrics.letterRise.toPx()
    val shake = if (reduced) 0f else RiffLyrics.heldShake.toPx()
    // Each letter is drawn whole (never through a strip), so its glow is never cut.
    fun DrawScope.eachLetter(draw: DrawScope.(HeldLetter) -> Unit) = held.letters.forEachIndexed { index, letter ->
        val wobble = letterShake(now, letter.litMs, word, index) * shake
        val swell = if (reduced) 1f else letterSwell(now, letter.litMs)
        withTransform({ translate(wobble.x, wobble.y - lift * letterRise(now, letter.litMs)); scale(swell, swell, letter.column.center) }) {
            draw(letter)
        }
    }
    withTransform({ translate(top = -rise); scale(grow, grow, centre) }) {
        eachLetter { drawText(it.glyph, color = dim, topLeft = it.at) }
        val reach = pad + lift * 1.2f + shake
        val area = Rect(held.topLeft.x - reach, held.topLeft.y - reach, held.topLeft.x + width + reach, held.topLeft.y + height + reach)
        drawContext.canvas.saveLayer(area, Paint())
        eachLetter {
            drawText(it.glyph, color = White, topLeft = it.at,
                shadow = if (glow > 0f) Shadow(RiffLyrics.glow.copy(alpha = .9f * glow), Offset.Zero, glowRadius * glow) else null)
        }
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
