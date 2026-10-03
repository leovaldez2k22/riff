package com.musicglass.personal

import android.graphics.*
import android.os.Build
import androidx.compose.ui.geometry.Rect

/** One set of bounds drives both the Android 12 frost mask and the Android 13+ AGSL glass. */
internal fun chromeGlassEffect(width:Int,height:Int,density:Float,showMini:Boolean,compact:Boolean,
    collapsed:Boolean,bottomInset:Int,categories:List<Rect> = emptyList(),frosted:Boolean=false,
    intensity:Float=1f):RenderEffect? {
    if(width<=0 || height<=0) return null
    val shapes=chromeGlassShapes(width,height,density,showMini,compact,collapsed,bottomInset,categories)
    if(Build.VERSION.SDK_INT>=33) return AgslChromeGlassEffect.create(shapes,width,height,density,frosted,intensity)
    if(!frosted || intensity<=0f) return null
    val bitmap=Bitmap.createBitmap((width+3)/4,(height+3)/4,Bitmap.Config.ALPHA_8)
    val canvas=Canvas(bitmap).apply {scale(.25f,.25f)}
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE}
    shapes.forEach {r->canvas.drawRoundRect(r.left,r.top,r.right,r.bottom,r.height/2f,r.height/2f,paint)}
    val mask=RenderEffect.createShaderEffect(BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {setLocalMatrix(Matrix().apply {setScale(4f,4f)})})
    val radius=22f*density*intensity
    val blur=RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP)
    val tint=Color.argb((46*minOf(intensity,1f)).toInt(),179,194,214)
    val tinted=RenderEffect.createColorFilterEffect(BlendModeColorFilter(tint,BlendMode.SRC_OVER),blur)
    val masked=RenderEffect.createBlendModeEffect(tinted,mask,BlendMode.DST_IN)
    return RenderEffect.createBlendModeEffect(RenderEffect.createOffsetEffect(0f,0f),masked,BlendMode.SRC_OVER)
}

internal val LocalGlassBounds = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.snapshots.SnapshotStateMap<String,Rect>?> {null}
