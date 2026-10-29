package com.musicglass.personal

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Feature 1, the tab bar's pill, made to behave as the owner's reference video does (30 Oct, measured frame by frame
 * at 30 fps; sizes there are in the video's pixels, a bar 192 px tall with tabs 258 px apart):
 * - at rest a frosted pill sits in the selected tab, 0.83 of the bar's height;
 * - a drag (or a held finger) lifts it into a clear lens: in about 0.1 s, with a slight bounce, 1.33 times wider and
 *   1.36 times taller, so it stands 7 % of the bar's height out of the top and the bottom;
 * - lifted, it keeps the offset from where it was taken (it moves a fifth of the way to the finger as it lifts) and
 *   follows the finger one to one, never snapping, between the first and last tabs' centres;
 * - moving, it widens with speed (about 12 % at three tabs a second) and flattens a little, and a fainter copy of its
 *   rim trails behind it, as far as a quick spring lags;
 * - pushed past an end it stays at the end tab's centre and squashes, narrower and taller, and springs back as the
 *   finger comes away;
 * - the selected tab does not change while it moves; let go, the tab under the lens is chosen at once, the lens
 *   glides into it without overshooting (about 0.2 s) and only then shrinks back to the frosted pill (about 0.1 s).
 */

/** What the bar draws for one frame, in px from the bar's left (mirrored by the caller for right to left). */
internal data class TabLensFrame(val centerX: Float, val width: Float, val height: Float,
    /** 0 at rest, 1 lifted: how far the pill has turned into the lens (overshoots a little as it lifts). */
    val lift: Float, val ghostX: Float, val ghostAlpha: Float)

/** A damped spring, stepped in small pieces so a long frame stays stable. */
internal class LensSpring(var value: Float, var velocity: Float = 0f) {
    fun step(target: Float, seconds: Float, stiffness: Float, damping: Float) {
        var left = seconds
        val friction = 2f * damping * sqrt(stiffness)
        while (left > 0f) {
            val dt = minOf(left, .004f)
            velocity += (-stiffness * (value - target) - friction * velocity) * dt
            value += velocity * dt
            left -= dt
        }
    }
    fun snap(to: Float) { value = to; velocity = 0f }
}

internal class TabLensMotion(private val tabs: Int = 4) {
    // The bar, set by the caller each layout.
    private var slot = 1f; private var barHeight = 1f; private var searchCenter = 0f; private var searchSize = 0f
    private var selected = 0
    private val center = LensSpring(0f); private val restWidth = LensSpring(0f)
    private val lift = LensSpring(0f); private val stretch = LensSpring(0f); private val squash = LensSpring(0f)
    private val ghost = LensSpring(0f); private val catchUp = LensSpring(0f)
    private var placed = false; private var reduced = false
    private var pressed = false; private var downX = 0f; private var fingerX = 0f; private var downAt = 0L
    private var grabOffset = 0f; private var lastCenter = 0f; private var speed = 0f
    private var releasedAt = -1L; private var target = 0f
    var lifted = false; private set
    /** True while anything still moves: the caller asks for frames only then. */
    var moving = false; private set

    fun layout(slotPx: Float, barHeightPx: Float, searchCenterPx: Float, searchSizePx: Float, selectedIndex: Int, reducedMotion: Boolean) {
        slot = slotPx; barHeight = barHeightPx; searchCenter = searchCenterPx; searchSize = searchSizePx; reduced = reducedMotion
        if (!placed) { selected = selectedIndex; center.snap(tabCenter(selected)); restWidth.snap(restWidthFor(selected)); target = center.value; ghost.snap(center.value); placed = true }
        else if (selectedIndex != selected) select(selectedIndex)
    }

    /** The selected tab changed (a tap, the back button, a release): the frosted pill slides there. */
    fun select(index: Int) {
        selected = index
        if (!lifted) { target = tabCenter(index); moving = true }
    }

    fun tabCenter(index: Int) = if (index >= tabs) searchCenter else slot * (index + .5f)
    private val firstCenter get() = slot * .5f
    private val lastTabCenter get() = slot * (tabs - .5f)
    private fun restWidthFor(index: Int) = if (index >= tabs) searchSize * REST_HEIGHT else slot - REST_SIDE * barHeight

    fun down(x: Float, nowMs: Long) {
        pressed = true; downX = x; fingerX = x; downAt = nowMs; releasedAt = -1L; moving = true
    }

    fun move(x: Float, slopPx: Float) {
        fingerX = x
        if (pressed && !lifted && abs(x - downX) > slopPx) liftUnderFinger()
    }

    /** A gesture taken by something else, or a tap: nothing lifted, and the pill stays where it is. */
    fun cancel() { pressed = false; if (lifted) release() }

    /** The finger came up after a lift: the tab nearest the lens, which the caller selects. */
    fun up(): Int {
        pressed = false
        val index = ((center.value - firstCenter) / slot).roundToInt().coerceIn(0, tabs - 1)
        selected = index
        release()
        return index
    }

    /** The finger came up over Search after a lift. */
    fun upOverSearch(): Int { pressed = false; selected = tabs; release(); return tabs }

    private fun release() {
        lifted = false; releasedAt = nowMs; target = tabCenter(selected)
        // The lens carries on at the finger's speed into the spring that brings it home.
        center.velocity = speed
        catchUp.snap(0f)
    }

    private fun liftUnderFinger() {
        if (reduced) return
        lifted = true
        val pill = center.value
        // Taken inside the pill, it keeps that offset after a fifth of a step toward the finger; taken elsewhere, the
        // lens comes to the finger.
        grabOffset = if (abs(fingerX - pill) <= restWidth.value / 2f) (fingerX - pill) * (1f - LIFT_TOWARD) else 0f
        val wanted = (fingerX - grabOffset).coerceIn(firstCenter, lastTabCenter)
        catchUp.snap(pill - wanted)
        lastCenter = pill
        moving = true
    }

    private var nowMs = 0L

    fun step(nowMs: Long, seconds: Float) {
        this.nowMs = nowMs
        val dt = seconds.coerceIn(0f, .05f)
        if (pressed && !lifted && nowMs - downAt >= HOLD_MS) liftUnderFinger()
        if (reduced) {
            center.snap(target); restWidth.snap(restWidthFor(selected)); lift.snap(0f); stretch.snap(0f); squash.snap(0f)
            ghost.snap(target); moving = pressed; return
        }
        restWidth.step(restWidthFor(selected), dt, SETTLE_STIFFNESS, 1f)
        if (lifted) {
            val raw = fingerX - grabOffset
            val held = raw.coerceIn(firstCenter, lastTabCenter)
            catchUp.step(0f, dt, CATCH_STIFFNESS, CATCH_DAMPING)
            center.snap(held + catchUp.value)
            val over = abs(raw - held)
            squash.step(1f - exp(-over / (SQUASH_REACH * slot)), dt, SQUASH_STIFFNESS, SQUASH_DAMPING)
            lift.step(1f, dt, LIFT_STIFFNESS, LIFT_DAMPING)
        } else {
            center.step(target, dt, GLIDE_STIFFNESS, GLIDE_DAMPING)
            squash.step(0f, dt, SQUASH_STIFFNESS, SQUASH_DAMPING)
            // Home first, then the lens lets go of its shape.
            val home = abs(center.value - target) < HOME_NEAR * slot && abs(center.velocity) < HOME_SPEED * slot
            val shrink = releasedAt < 0L || home || nowMs - releasedAt >= HOME_WAIT_MS
            lift.step(if (shrink) 0f else 1f, dt, LIFT_STIFFNESS, if (shrink) SHRINK_DAMPING else LIFT_DAMPING)
        }
        val measured = if (dt > 0f) (center.value - lastCenter) / dt else 0f
        speed = if (lifted) measured else center.velocity
        lastCenter = center.value
        stretch.step((STRETCH_PER_TAB_SECOND * abs(speed) / slot).coerceAtMost(STRETCH_MAX), dt, STRETCH_STIFFNESS, STRETCH_DAMPING)
        ghost.step(center.value, dt, GHOST_STIFFNESS, 1f)
        moving = pressed || lifted || abs(center.value - target) > .5f || abs(center.velocity) > 1f || abs(lift.value) > .002f ||
            abs(lift.velocity) > .01f || stretch.value > .002f || squash.value > .002f || abs(ghost.value - center.value) > .5f ||
            abs(restWidth.value - restWidthFor(selected)) > .5f
    }

    fun frame(): TabLensFrame {
        val l = lift.value
        val restHeight = barHeight * REST_HEIGHT
        val sx = (1f + stretch.value) * (1f - SQUASH_NARROWER * squash.value)
        val sy = (1f - STRETCH_FLATTEN * stretch.value) * (1f + SQUASH_TALLER * squash.value)
        val width = restWidth.value * (1f + LIFT_WIDER * l) * sx
        val height = restHeight * (1f + LIFT_TALLER * l) * sy
        val trail = abs(ghost.value - center.value) / (GHOST_FULL * slot.coerceAtLeast(1f))
        return TabLensFrame(center.value, width, height, l, ghost.value, (GHOST_ALPHA * trail.coerceIn(0f, 1f) * l.coerceIn(0f, 1f)))
    }

    /** The tab nearest the lens now, for the tick as it crosses into another. */
    fun hovered(): Int = ((center.value - firstCenter) / slot).roundToInt().coerceIn(0, tabs - 1)

    companion object {
        /** The frosted pill: 0.83 of the bar's height (160 of 192 px), a tab wide less 0.05 of the bar's height. */
        const val REST_HEIGHT = .833f
        const val REST_SIDE = .05f
        /** Lifted: 1.33 times wider and 1.36 times taller (255 to 339 px, 160 to 218 px). */
        const val LIFT_WIDER = .33f
        const val LIFT_TALLER = .36f
        /** At its first touch the lens moves this share of the way to the finger (178 to 194 px of 288). */
        const val LIFT_TOWARD = .18f
        /** A finger held this long lifts the lens without moving (the video's lifts came 0.2 to 0.5 s after the touch). */
        const val HOLD_MS = 350L
        /** The lift: its first peak in about 0.1 s, a bounce of about a sixth. */
        const val LIFT_STIFFNESS = 1_400f
        const val LIFT_DAMPING = .55f
        /** The shrink after the glide: about 0.1 s, no bounce. */
        const val SHRINK_DAMPING = .85f
        /** Bringing a lens taken away from the finger to it. */
        const val CATCH_STIFFNESS = 900f
        const val CATCH_DAMPING = .9f
        /** The glide home after letting go: about 0.2 s, no overshoot (205 to 185 px). */
        const val GLIDE_STIFFNESS = 700f
        const val GLIDE_DAMPING = 1f
        /** The pill moving after a tap: the same calm settle. */
        const val SETTLE_STIFFNESS = 700f
        /** Home: within 6 % of a tab and nearly still; or this long after letting go, whichever is first. */
        const val HOME_NEAR = .06f
        const val HOME_SPEED = .6f
        const val HOME_WAIT_MS = 260L
        /** Speed widens the lens: 4 % per tab a second, at most 25 %, flattening it by a quarter as much. */
        const val STRETCH_PER_TAB_SECOND = .04f
        const val STRETCH_MAX = .25f
        const val STRETCH_FLATTEN = .25f
        const val STRETCH_STIFFNESS = 400f
        const val STRETCH_DAMPING = .7f
        /** Pressing past an end: up to 7 % narrower and 5 % taller, half felt within 0.6 of a tab, back in about 0.3 s. */
        const val SQUASH_NARROWER = .07f
        const val SQUASH_TALLER = .05f
        const val SQUASH_REACH = .6f
        const val SQUASH_STIFFNESS = 300f
        const val SQUASH_DAMPING = .6f
        /** The trailing rim: a spring about 67 ms behind, fully seen a quarter of a tab back, at most 35 % opaque. */
        const val GHOST_STIFFNESS = 900f
        const val GHOST_FULL = .25f
        const val GHOST_ALPHA = .35f
    }
}
