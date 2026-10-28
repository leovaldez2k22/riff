package com.musicglass.personal

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState

/**
 * Feature 6, Stage A: the cover moves a little while the player is open. Drift is a slow zoom and pan, Depth
 * follows the phone's tilt, Mesh lets the colours under the cover drift. Nothing moves under reduced motion or
 * battery saver, or while Riff is in the background.
 */
internal enum class ArtworkMotion(val wire: String, val label: Int) {
    Off("off", R.string.artwork_motion_off), Drift("drift", R.string.artwork_motion_drift),
    Depth("depth", R.string.artwork_motion_depth), Mesh("mesh", R.string.artwork_motion_mesh);
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Off }
}

private const val ARTWORK_MOTION = "artwork_motion"
internal fun artworkMotion(context: Context) = ArtworkMotion.of(context.applicationContext
    .getSharedPreferences("music_collections", Context.MODE_PRIVATE).getString(ARTWORK_MOTION, null))
internal fun setArtworkMotion(context: Context, motion: ArtworkMotion) {
    context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE).edit().putString(ARTWORK_MOTION, motion.wire).apply()
    artworkMotionRevision.floatValue++
}
/** Read by the player so a change in Settings shows at once. */
internal val artworkMotionRevision = mutableFloatStateOf(0f)

/** Drift: 1.00 to 1.08 and back over [DRIFT_MS] each way, with a pan of a third of the growth. */
private const val DRIFT_MS = 24_000
private const val DRIFT_GROWTH = .08f
/** Depth: the most the cover moves, as a share of its width, at a tilt of [DEPTH_TILT] radians. */
private const val DEPTH_SHIFT = .02f
private const val DEPTH_TILT = .35f
private const val MESH_MS = 30_000

/** The style in effect: [chosen] unless the system asks for less motion or less power, or Riff isn't in front. */
@Composable
internal fun activeArtworkMotion(): ArtworkMotion {
    val context = LocalContext.current
    artworkMotionRevision.floatValue
    val chosen = artworkMotion(context)
    val reduced = LocalMotionPolicy.current.reduced
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val saver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    return if (reduced || saver || !resumed) ArtworkMotion.Off else chosen
}

/** A running 0..1..0 phase for Drift, or 0..1 for Mesh; stays 0 when [style] doesn't need it. */
@Composable
internal fun artworkPhase(style: ArtworkMotion): State<Float> {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(style) {
        when (style) {
            ArtworkMotion.Drift -> phase.animateTo(1f, infiniteRepeatable(tween(DRIFT_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse))
            ArtworkMotion.Mesh -> { phase.snapTo(0f); phase.animateTo(1f, infiniteRepeatable(tween(MESH_MS, easing = LinearEasing), RepeatMode.Restart)) }
            else -> phase.snapTo(0f)
        }
    }
    return phase.asState()
}

/** The phone's tilt from the game rotation sensor, as pitch and roll in radians; zero when off or absent. */
@Composable
internal fun deviceTilt(enabled: Boolean): State<androidx.compose.ui.geometry.Offset> {
    val context = LocalContext.current
    val tilt = remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    DisposableEffect(enabled) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (!enabled || sensors == null || sensor == null) { tilt.value = androidx.compose.ui.geometry.Offset.Zero; return@DisposableEffect onDispose { } }
        val rotation = FloatArray(9); val angles = FloatArray(3)
        var rest: FloatArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, angles)
                // The way the phone was held when the player opened is the centre.
                val start = rest ?: angles.copyOf().also { rest = it }
                val target = androidx.compose.ui.geometry.Offset(angles[2] - start[2], angles[1] - start[1])
                // A little smoothing so the hand's tremor doesn't show.
                tilt.value = tilt.value + (target - tilt.value) * .15f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensors.unregisterListener(listener) }
    }
    return tilt
}

/** The cover's transform for [style]; the image is drawn a little larger so its edges never show. */
internal fun Modifier.livingArtwork(style: ArtworkMotion, phase: State<Float>, tilt: State<androidx.compose.ui.geometry.Offset>) = when (style) {
    ArtworkMotion.Drift -> graphicsLayer {
        val grow = 1f + DRIFT_GROWTH * phase.value
        scaleX = grow; scaleY = grow
        translationX = size.width * DRIFT_GROWTH * (phase.value - .5f) / 3f
    }
    ArtworkMotion.Depth -> graphicsLayer {
        val grow = 1f + DEPTH_SHIFT * 2.5f
        scaleX = grow; scaleY = grow
        val t = tilt.value
        translationX = size.width * DEPTH_SHIFT * (t.x / DEPTH_TILT).coerceIn(-1f, 1f)
        translationY = size.width * DEPTH_SHIFT * (t.y / DEPTH_TILT).coerceIn(-1f, 1f)
    }
    else -> this
}
