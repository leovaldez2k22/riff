package com.musicglass.personal

import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

internal enum class HapticCue { Selection, Click, Confirm, Reject, ToggleOn, ToggleOff, DragStart, DragEnd, Threshold,
    /** F4: a slider's marks, in three strengths (the platform's heavy, standard and light clicks). */
    TickStrong, TickMedium, TickLight }

/**
 * The platform constant for a cue. Four of them arrived in API 34; before that the view ignores them, so an
 * older phone gets the nearest constant it does know instead of nothing.
 */
@SuppressLint("InlinedApi") // each newer constant is only chosen where sdk says the phone knows it
internal fun hapticConstant(cue: HapticCue, sdk: Int = Build.VERSION.SDK_INT): Int = when(cue) {
    HapticCue.Confirm -> HapticFeedbackConstants.CONFIRM
    HapticCue.Reject -> HapticFeedbackConstants.REJECT
    HapticCue.DragStart -> if(sdk>=34) HapticFeedbackConstants.DRAG_START else HapticFeedbackConstants.LONG_PRESS
    HapticCue.DragEnd -> HapticFeedbackConstants.GESTURE_END
    HapticCue.Threshold -> if(sdk>=34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK
    HapticCue.ToggleOn -> if(sdk>=34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
    HapticCue.ToggleOff -> if(sdk>=34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CONTEXT_CLICK
    HapticCue.Selection -> HapticFeedbackConstants.CLOCK_TICK
    HapticCue.Click -> HapticFeedbackConstants.CONTEXT_CLICK
    HapticCue.TickStrong -> HapticFeedbackConstants.LONG_PRESS
    HapticCue.TickMedium -> HapticFeedbackConstants.VIRTUAL_KEY
    HapticCue.TickLight -> HapticFeedbackConstants.CLOCK_TICK
}

/** Android's semantic touch feedback respects system settings without a vibration permission. */
internal object UiHaptics {
    private var view = WeakReference<View>(null)
    private var lifecycle: Lifecycle? = null
    private var settings: AccessibilitySettings? = null
    private var lastMs=Long.MIN_VALUE
    fun bind(target: View, owner: Lifecycle, preferences: AccessibilitySettings) {
        view=WeakReference(target); lifecycle=owner; settings=preferences
    }
    fun unbind(target: View) { if(view.get()===target) {view.clear(); lifecycle=null; settings=null} }
    fun perform(cue: HapticCue) {
        val target=view.get()?:return
        if(settings?.hapticFeedback!=true || lifecycle?.currentState!=Lifecycle.State.RESUMED || !target.isAttachedToWindow || !target.isHapticFeedbackEnabled) return
        val now=SystemClock.uptimeMillis()
        if(lastMs!=Long.MIN_VALUE && now-lastMs<60) return
        if(target.performHapticFeedback(hapticConstant(cue))) lastMs=now
    }
}
internal fun uiHaptic(cue: HapticCue = HapticCue.Click) = UiHaptics.perform(cue)
internal fun hapticClick(action: () -> Unit): () -> Unit = { uiHaptic(); action() }
internal fun hapticToggle(action: (Boolean) -> Unit): (Boolean) -> Unit = { value -> action(value); uiHaptic(if(value) HapticCue.ToggleOn else HapticCue.ToggleOff) }

/** Only user-driven slider changes pass through this tracker. No pulse for playback position updates. */
/**
 * Seek: pick-up and release only. Volume: each step, the ends stronger. Intensity (glass, 0 to 150 %): a medium tick
 * every 25 %. Boost (100 to 300 %): a mark every 25 %, strongest at 100, 200 and 300, lighter at 150 and 250, lightest
 * between. Seconds (crossfade): a medium tick at each second (F4).
 */
internal enum class SliderHaptics { Seek, Volume, Intensity, Boost, Seconds }
internal class SliderHapticTracker(private val mode: SliderHaptics) {
    private var dragging=false
    private var last: Float?=null
    fun change(value: Float, range: ClosedFloatingPointRange<Float>): HapticCue? {
        val previous=last;last=value
        if(!dragging) {dragging=true;return HapticCue.Selection}
        return when(mode) {
            SliderHaptics.Seek -> null
            SliderHaptics.Volume -> if(previous?.roundToInt()!=value.roundToInt()) if(value==range.start || value==range.endInclusive) HapticCue.Threshold else HapticCue.Selection else null
            SliderHaptics.Intensity -> previous?.let {markCrossed(it,value,range.start,.25f) {HapticCue.TickMedium}}
            SliderHaptics.Boost -> previous?.let {markCrossed(it,value,range.start,25f,::boostMark)}
            SliderHaptics.Seconds -> previous?.let {markCrossed(it,value,range.start,1f) {HapticCue.TickMedium}}
        }
    }
    /** The strongest mark passed or landed on going from [from] to [to]; leaving the mark the slider was on is not one. */
    private fun markCrossed(from: Float, to: Float, start: Float, step: Float, cue: (Int)->HapticCue): HapticCue? {
        if(from==to) return null
        val eps=step/1000f
        val first=kotlin.math.ceil((minOf(from,to)-start)/step-.001f).toInt()
        val last=kotlin.math.floor((maxOf(from,to)-start)/step+.001f).toInt()
        return (first..last).filter {i->val mark=start+i*step
            if(to>from) from<mark-eps && to>=mark-eps else from>mark+eps && to<=mark+eps}
            .map(cue).minByOrNull {it.ordinal}
    }
    fun finish(): HapticCue? { val active=dragging;dragging=false;last=null;return if(active) HapticCue.DragEnd else null }
}
/** Boost marks every 25 % from 100: the hundreds strongest, 150 and 250 lighter, the rest lightest. */
internal fun boostMark(index: Int): HapticCue = when {index%4==0 -> HapticCue.TickStrong; index%2==0 -> HapticCue.TickMedium; else -> HapticCue.TickLight}
