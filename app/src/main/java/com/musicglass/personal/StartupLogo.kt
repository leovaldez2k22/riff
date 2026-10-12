package com.musicglass.personal
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithCache
import kotlinx.coroutines.flow.first
import kotlin.math.*

private const val StartupDurationMs = 1150
private const val WaveArrivalMs = 740

private class StartupRibbon(
    start: Offset,
    curves: List<FloatArray>,
    val direction: Float,
    val phase: Float,
) {
    // Sample once so the traveling ripple can deform the actual launcher silhouettes.
    val path=Path()
    val points: List<Offset> = buildList {
        var last = start
        add(last)
        for (curve in curves) {
            for (step in 1..40) {
                val u = step / 40f
                val v = 1f - u
                add(Offset(
                    v*v*v*last.x + 3f*v*v*u*curve[0] + 3f*v*u*u*curve[2] + u*u*u*curve[4],
                    v*v*v*last.y + 3f*v*v*u*curve[1] + 3f*v*u*u*curve[3] + u*u*u*curve[5],
                ))
            }
            last = Offset(curve[4], curve[5])
        }
    }
}

private val startupRibbons = listOf(
    StartupRibbon(Offset(27f, 49f), listOf(
        floatArrayOf(37f,57f,43f,41f,51f,34f),
        floatArrayOf(61f,25f,71f,30f,78f,41f),
        floatArrayOf(66f,34f,61f,43f,53f,51f),
        floatArrayOf(43f,61f,34f,60f,27f,49f),
    ), direction = -1f, phase = 0f),
    StartupRibbon(Offset(29f, 64f), listOf(
        floatArrayOf(41f,73f,47f,59f,55f,52f),
        floatArrayOf(66f,41f,76f,46f,81f,56f),
        floatArrayOf(68f,49f,64f,63f,56f,70f),
        floatArrayOf(46f,80f,36f,76f,29f,64f),
    ), direction = 1f, phase = PI.toFloat()),
)

private fun startupSmoothStep(from: Float, to: Float, value: Float): Float {
    val u = ((value - from) / (to - from)).coerceIn(0f, 1f)
    return u*u*(3f - 2f*u)
}

@Composable internal fun StartupLogo(show:Boolean,content:@Composable ()->Unit) {
    val context=LocalContext.current
    // Only a cold start shows the logo; on recreation (rotation, dark mode, font) skip the disk read.
    var visible by remember {mutableStateOf(show && !(context.getSharedPreferences("accessibility_display",0).getBoolean("reduce_motion",false) ||
        android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f))}
    val reveal=remember {Animatable(0f)}
    var appReady by remember {mutableStateOf(!visible)}
    var contentLaidOut by remember {mutableStateOf(false)}
    LaunchedEffect(Unit) {if(visible) {
        withFrameNanos { }
        reveal.animateTo(WaveArrivalMs.toFloat()/StartupDurationMs,tween(WaveArrivalMs,easing=LinearEasing))
        // Build the app between phases: its first composition cannot stall the traveling logo.
        appReady=true
        snapshotFlow {contentLaidOut}.first {it}
        withFrameNanos { }
        reveal.animateTo(1f,tween(StartupDurationMs-WaveArrivalMs,easing=LinearEasing))
        visible=false
    }}
    Box(Modifier.fillMaxSize().preferredFrameRate(LocalUiRefreshRate.current)) {
        if(appReady) Box(Modifier.fillMaxSize().onGloballyPositioned {contentLaidOut=true}) {content()}
        if(visible) Box(Modifier.fillMaxSize().graphicsLayer {alpha=((1f-reveal.value)/.22f).coerceIn(0f,1f);compositingStrategy=CompositingStrategy.ModulateAlpha}.background(Color(0xFF080A12)),contentAlignment=Alignment.Center) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val t=reveal.value
                alpha=sin(t*PI).toFloat().coerceAtLeast(0f)
                scaleX=.36f+1.6f*t;scaleY=scaleX
                compositingStrategy=CompositingStrategy.ModulateAlpha
            }.drawWithCache {
                val radius=size.minDimension*.5f
                val origin=Offset(size.width/2f,size.height/2f)
                val glow=Brush.radialGradient(listOf(Color(0xFFAD3E9A).copy(alpha=.35f),Color.Transparent),origin,radius)
                onDrawBehind {drawCircle(glow,radius,origin)}
            })
            Box(Modifier.fillMaxSize().graphicsLayer {
                val t=reveal.value;val radius=size.minDimension*(.18f+.8f*t)
                alpha=sin(t*PI).toFloat().coerceAtLeast(0f)
                scaleX=.36f+1.6f*t;scaleY=scaleX
                translationX=cos(t*PI*1.6).toFloat()*radius*.4f
                translationY=sin(t*PI*1.6).toFloat()*radius*.25f
                compositingStrategy=CompositingStrategy.ModulateAlpha
            }.drawWithCache {
                val radius=size.minDimension*.325f
                val origin=Offset(size.width/2f,size.height/2f)
                val glow=Brush.radialGradient(listOf(Color(0xFF4296EF).copy(alpha=.3f),Color.Transparent),origin,radius)
                onDrawBehind {drawCircle(glow,radius,origin)}
            })
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = string(R.string.app_name) }) {
                val progress = (reveal.value * StartupDurationMs / WaveArrivalMs).coerceIn(0f, 1f)
                val remaining = 1f - progress
                val arrival = if (progress < .5f) 4f*progress*progress*progress
                    else 1f - 4f*remaining*remaining*remaining
                val distance = (size.width / 2f + 48.dp.toPx()) * (1f - arrival)
                val iconScale = 156.dp.toPx() / 108f
                // Fade deformation to exactly zero at arrival; no looping or idle ripple.
                val amplitude = 5.2f * startupSmoothStep(0f, .12f, progress) *
                    (1f - startupSmoothStep(.72f, 1f, progress))
                for (ribbon in startupRibbons) {
                    val path = ribbon.path.apply {rewind()}
                    ribbon.points.forEachIndexed { index, point ->
                        val bend = amplitude * sin(
                            (point.x - 27f) / 54f * PI.toFloat() * 2.4f -
                                progress * PI.toFloat() * 4f + ribbon.phase,
                        )
                        val x = center.x + (point.x - 54f) * iconScale + ribbon.direction * distance
                        val y = center.y + (point.y - 54f + bend) * iconScale
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    drawPath(path, Color(0xFFF45B9A))
                }
            }
        }
    }
}
