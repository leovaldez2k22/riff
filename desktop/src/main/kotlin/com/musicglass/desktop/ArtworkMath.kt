package com.musicglass.desktop

internal const val BACKDROP_GRID = 6
internal const val BACKDROP_SAMPLE_PX = 128
internal const val BACKDROP_VIBRANCE = 1.12f
internal const val BACKDROP_FLOOR = .045f
/** Where the sharp art ends, as a share of the panel's height. */
internal const val BACKDROP_SEAM = .5f
private const val BACKDROP_BLUR_RADIUS = 6
private const val BACKDROP_BLUR_PASSES = 3
/** The blurred copy's opacity over the mesh. */
private const val BACKDROP_BLUR_ALPHA = .6f
/** How far down the panel below the seam the blurred copy takes to come in fully. */
private const val BACKDROP_BLUR_FADE = .35f

/** The average colour of each cell of a [grid] × [grid] split of [pixels] ([width] × [height]), row by row. */
internal fun meshColors(pixels: IntArray, width: Int, height: Int, grid: Int = BACKDROP_GRID): IntArray {
    val out = IntArray(grid * grid)
    for (row in 0 until grid) for (column in 0 until grid) {
        val x0 = column * width / grid; val x1 = ((column + 1) * width / grid).coerceAtLeast(x0 + 1)
        val y0 = row * height / grid; val y1 = ((row + 1) * height / grid).coerceAtLeast(y0 + 1)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in y0 until y1.coerceAtMost(height)) for (x in x0 until x1.coerceAtMost(width)) {
            val p = pixels[y * width + x]; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; n++
        }
        n = n.coerceAtLeast(1)
        out[row * grid + column] = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }
    return out
}

/**
 * The deck's layout of the mesh: its first row is the cover's last (against the seam); the rows above that, flipped
 * so the ones nearest the seam come first, are then turned sideways (rows become columns). The cover's own colours,
 * in its own proportions, without a mirrored face or horizon.
 */
internal fun deckMesh(mesh: IntArray, grid: Int = BACKDROP_GRID): IntArray {
    val out = IntArray(grid * grid)
    for (column in 0 until grid) out[column] = mesh[(grid - 1) * grid + column]
    // The cover's rows grid-2 downTo 0, flipped; then turned: deck cell (row, column) takes flipped cell (column, row).
    val rest = grid - 1
    for (row in 1 until grid) for (column in 0 until grid) {
        val flippedRow = column * rest / grid
        val sourceRow = grid - 2 - flippedRow
        val sourceColumn = (row - 1) * grid / rest
        out[row * grid + column] = mesh[sourceRow.coerceIn(0, grid - 1) * grid + sourceColumn.coerceIn(0, grid - 1)]
    }
    return out
}

/** Slightly more saturated, and never quite black, so a near-black cover still has depth. */
internal fun deckTone(argb: Int): Int {
    val r = (argb shr 16 and 0xFF) / 255f; val g = (argb shr 8 and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
    val mean = (r + g + b) / 3f
    fun channel(c: Float) = (mean + (c - mean) * BACKDROP_VIBRANCE).coerceIn(BACKDROP_FLOOR, 1f)
    return (0xFF shl 24) or ((channel(r) * 255).toInt() shl 16) or ((channel(g) * 255).toInt() shl 8) or (channel(b) * 255).toInt()
}

/** Three box-blur passes over a small copy, horizontally then vertically each time. */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int = BACKDROP_BLUR_RADIUS, passes: Int = BACKDROP_BLUR_PASSES): IntArray {
    var source = pixels.copyOf(); var target = IntArray(pixels.size)
    fun pass(horizontal: Boolean) {
        val lines = if (horizontal) height else width; val length = if (horizontal) width else height
        for (line in 0 until lines) for (i in 0 until length) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (k in (i - radius).coerceAtLeast(0)..(i + radius).coerceAtMost(length - 1)) {
                val p = source[if (horizontal) line * width + k else k * width + line]
                r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; n++
            }
            target[if (horizontal) line * width + i else i * width + line] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        val swap = source; source = target; target = swap
    }
    repeat(passes) { pass(true); pass(false) }
    return source
}


