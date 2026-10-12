package com.musicglass.personal

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal data class MotionPolicy(val reduced: Boolean, val scale: Float)

internal val LocalMotionPolicy = compositionLocalOf { MotionPolicy(false, 1f) }

/** The platform animator setting can change while the app is open. */
@Composable
internal fun rememberMotionPolicy(preferenceReduced: Boolean): MotionPolicy {
    val context = LocalContext.current
    fun readScale(): Float = runCatching {
        Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f).coerceIn(0f, 10f)
    var scale by remember(context) { mutableFloatStateOf(readScale()) }
    DisposableEffect(context) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = readScale() }
        }
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return MotionPolicy(preferenceReduced || scale == 0f, scale)
}

/** Smooth alpha ramps with zero slope at both ends, also safe during reversal. */
internal fun motionRamp(progress: Float, start: Float, end: Float): Float {
    val t = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Select a supported rate without changing display resolution or pinning a mode. */
internal fun preferredUiRefreshRate(rates: List<Float>): Float {
    val valid = rates.filter { it.isFinite() && it > 0f }
    return valid.filter { it <= 120.5f }.maxOrNull() ?: valid.minOrNull() ?: 0f
}

internal object GlassMotion {
    const val pressMs = 100
    const val selectionMs = 180
    const val screenMs = 280
    const val playerMs = 380
    const val artworkMs = 360
    const val ambientMs = 900

    fun <T> timed(ms: Int, policy: MotionPolicy): FiniteAnimationSpec<T> =
        if (policy.reduced) snap() else tween((ms * policy.scale).toInt().coerceAtLeast(1),
            easing = FastOutSlowInEasing)

    fun <T> settle(policy: MotionPolicy): FiniteAnimationSpec<T> =
        if (policy.reduced) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium)
}

/** Pure release rule shared by mini and expanded player gestures. */
internal fun playerReleaseOpens(progress: Float, upwardVelocityPxPerSecond: Float): Boolean =
    when {
        upwardVelocityPxPerSecond > 650f -> true
        upwardVelocityPxPerSecond < -650f -> false
        else -> progress >= .5f
    }
