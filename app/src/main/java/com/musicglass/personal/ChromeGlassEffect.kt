package com.musicglass.personal

import android.graphics.*
import android.os.Build
import androidx.compose.ui.geometry.Rect

/** The clear glass over the whole screen (Android 13+); Android 12 has none. Frosted glass adds [frostCutEffect] over blurred strips. */
internal fun chromeGlassEffect(width:Int,height:Int,density:Float,showMini:Boolean,compact:Boolean,
    collapsed:Boolean,bottomInset:Int,categories:List<Rect> = emptyList(),
    intensity:Float=1f,categoryRadii:List<Float> = emptyList(),bottomChrome:Boolean=true):RenderEffect? {
    if(width<=0 || height<=0 || Build.VERSION.SDK_INT<33) return null
    val shapes=chromeGlassShapes(width,height,density,showMini,compact,collapsed,bottomInset,categories,bottomChrome)
    return AgslChromeGlassEffect.create(shapes,width,height,density,intensity,categoryRadii)
}

/**
 * The frost over [area] (screen pixels), applied to the strips' blurred copies drawn there: the glass shapes cut out,
 * tinted, and on Android 13+ bent at the rim. One set of bounds drives both the Android 12 mask and the AGSL glass.
 */
internal fun frostCutEffect(shapes:List<Rect>,area:Rect,density:Float,intensity:Float,frostIntensity:Float,
    categoryRadii:List<Float> = emptyList()):RenderEffect {
    if(Build.VERSION.SDK_INT>=33) return AgslChromeGlassEffect.frost(shapes,area,density,intensity,frostIntensity,categoryRadii)
    val bitmap=Bitmap.createBitmap(((area.width+3)/4).toInt().coerceAtLeast(1),((area.height+3)/4).toInt().coerceAtLeast(1),Bitmap.Config.ALPHA_8)
    val canvas=Canvas(bitmap).apply {scale(.25f,.25f);translate(-area.left,-area.top)}
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE}
    shapes.forEachIndexed {i,r->val radius=if(i<3) r.height/2f else glassCornerRadius(r,categoryRadii.getOrNull(i-3))
        canvas.drawRoundRect(r.left,r.top,r.right,r.bottom,radius,radius,paint)}
    val mask=RenderEffect.createShaderEffect(BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {setLocalMatrix(Matrix().apply {setScale(4f,4f)})})
    val tint=Color.argb((46*minOf(frostIntensity,1f)).toInt(),179,194,214)
    val tinted=RenderEffect.createColorFilterEffect(BlendModeColorFilter(tint,BlendMode.SRC_OVER))
    return RenderEffect.createBlendModeEffect(tinted,mask,BlendMode.DST_IN)
}

/** The blur of a strip's half-size copy: the full-size frost's sigma, in half-size pixels. */
internal fun frostBlurEffect(density:Float,frostIntensity:Float):RenderEffect {
    val radius=halfSizeBlurRadius(frostBlurRadius(density,frostIntensity))
    return RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP)
}

/** Android turns a blur radius into sigma = 0.57735 r + 0.5; this keeps sigma halved for a copy at half size. */
internal fun halfSizeBlurRadius(radius:Float):Float =
    (((.57735f*radius+.5f)/FROST_SCALE-.5f)/.57735f).coerceAtLeast(.01f)

/** A category shape's corner: its own radius when given (a tile), otherwise a pill. Never more than half its height. */
internal fun glassCornerRadius(rect:Rect,radius:Float?):Float =
    (if(radius!=null && radius>0f) radius else rect.height/2f).coerceAtMost(minOf(rect.width,rect.height)/2f)

internal val LocalGlassBounds = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.snapshots.SnapshotStateMap<String,Rect>?> {null}
