package com.musicglass.desktop
import androidx.compose.ui.graphics.*

/** Upload opaque artwork pixels in one operation; independent of preview content. */
internal fun opaqueArtworkBitmap(values: IntArray, width: Int, height: Int): ImageBitmap {
    require(width > 0 && height > 0 && values.size == width * height)
    val rgba = ByteArray(values.size * 4)
    values.forEachIndexed { i, color ->
        require(color ushr 24 == 255)
        rgba[4*i] = (color ushr 16).toByte()
        rgba[4*i+1] = (color ushr 8).toByte()
        rgba[4*i+2] = color.toByte()
        rgba[4*i+3] = 255.toByte()
    }
    val info = org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE)
    val bitmap = org.jetbrains.skia.Bitmap()
    check(bitmap.installPixels(info, rgba, width * 4))
    return bitmap.asComposeImageBitmap()
}
