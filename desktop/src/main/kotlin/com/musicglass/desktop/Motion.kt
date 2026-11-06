package com.musicglass.desktop
import androidx.compose.animation.core.*
internal data class MotionPolicy(val reduced: Boolean, val scale: Float)
internal object GlassMotion {
    const val pressMs = 130
    const val selectionMs = 260
    const val screenMs = 420
    const val sheetMs = 450
    const val playerMs = 520
    /**
     * The full-screen player opening and closing, slower at the owner's request (phone checks 29 Oct, item 18). Closing
     * is not a quarter quicker here, and [playerEasing] starts gently and lands softly, so nothing jumps.
     */
    const val playerOpenMs = 700
    const val playerCloseMs = 600
    val playerEasing = CubicBezierEasing(.32f, 0f, .12f, 1f)
    fun <T> player(open: Boolean, policy: MotionPolicy): FiniteAnimationSpec<T> =
        if (policy.reduced) snap() else tween(((if (open) playerOpenMs else playerCloseMs) * policy.scale).toInt().coerceAtLeast(1),
            easing = playerEasing)
    /** After a drag of the player is let go: a softer spring than [settle], to match the player's pace. */
    fun <T> playerSettle(policy: MotionPolicy): FiniteAnimationSpec<T> =
        if (policy.reduced) snap() else spring(dampingRatio = 1f,
            stiffness = Spring.StiffnessLow / (policy.scale * policy.scale).coerceAtLeast(.01f))
    const val artworkMs = 520
    const val ambientMs = 1200

    /** Material 3 "standard": starts promptly and spends longer settling, which is what reads as calm. */
    val calmEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)

    fun <T> timed(ms: Int, policy: MotionPolicy): FiniteAnimationSpec<T> =
        if (policy.reduced) snap() else tween((ms * policy.scale).toInt().coerceAtLeast(1),
            easing = calmEasing)

    /** What leaves goes a quarter quicker than what arrives, so the eye follows the arrival. */
    fun <T> exit(ms: Int, policy: MotionPolicy): FiniteAnimationSpec<T> = timed(exitMs(ms), policy)

    fun exitMs(ms: Int): Int = ms * 3 / 4

    fun <T> settle(policy: MotionPolicy): FiniteAnimationSpec<T> =
        if (policy.reduced) snap() else spring(
            dampingRatio = .9f,
            stiffness = Spring.StiffnessMediumLow / (policy.scale * policy.scale).coerceAtLeast(.01f))

    /** A settings icon as its switch turns on or off: a quick springy pop, a little past and back (owner, 30 Oct). */
    fun <T> pop(policy: MotionPolicy): FiniteAnimationSpec<T> =
        if (policy.reduced) snap() else spring(
            dampingRatio = .42f,
            stiffness = Spring.StiffnessMedium / (policy.scale * policy.scale).coerceAtLeast(.01f))
    /** Settings tiles come in one after another, this far apart. */
    const val staggerMs = 35
    /** And no tile waits longer than this, however far down the page. */
    const val staggerMaxMs = 280
}

/** Settings → Appearance → Motion. Quick restores the pace Riff had before Feature 35. */
internal enum class MotionPace(val key: String, val factor: Float) { Calm("calm", 1f), Quick("quick", .7f) }

internal fun motionPaceFrom(key: String?): MotionPace = MotionPace.entries.firstOrNull { it.key == key } ?: MotionPace.Calm

/** Pure release rule shared by mini and expanded player gestures. */
internal fun playerReleaseOpens(progress: Float, upwardVelocityPxPerSecond: Float): Boolean =
    when {
        upwardVelocityPxPerSecond > 650f -> true
        upwardVelocityPxPerSecond < -650f -> false
        else -> progress >= .5f
    }

internal val LocalMotionPolicy = androidx.compose.runtime.staticCompositionLocalOf { MotionPolicy(false,1f) }
