package com.musicglass.personal

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

internal enum class HapticCue { Selection, Click, Confirm, Reject, ToggleOn, ToggleOff, DragStart, DragEnd, Threshold }

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
        val effect=when(cue) {
            HapticCue.Confirm -> HapticFeedbackConstants.CONFIRM
            HapticCue.Reject -> HapticFeedbackConstants.REJECT
            HapticCue.DragStart -> if(Build.VERSION.SDK_INT>=34) HapticFeedbackConstants.DRAG_START else HapticFeedbackConstants.LONG_PRESS
            HapticCue.DragEnd -> HapticFeedbackConstants.GESTURE_END
            HapticCue.Threshold -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            HapticCue.ToggleOn -> if(Build.VERSION.SDK_INT>=34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
            HapticCue.ToggleOff -> if(Build.VERSION.SDK_INT>=34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CONTEXT_CLICK
            HapticCue.Selection -> HapticFeedbackConstants.CLOCK_TICK
            HapticCue.Click -> HapticFeedbackConstants.CONTEXT_CLICK
        }
        if(target.performHapticFeedback(effect)) lastMs=now
    }
}
internal fun uiHaptic(cue: HapticCue = HapticCue.Click) = UiHaptics.perform(cue)
internal fun hapticClick(action: () -> Unit): () -> Unit = { uiHaptic(); action() }
internal fun hapticToggle(action: (Boolean) -> Unit): (Boolean) -> Unit = { value -> action(value); uiHaptic(if(value) HapticCue.ToggleOn else HapticCue.ToggleOff) }

/** Only user-driven slider changes pass through this tracker. No pulse for playback position updates. */
internal enum class SliderHaptics { Seek, Volume, Intensity }
internal class SliderHapticTracker(private val mode: SliderHaptics) {
    private var dragging=false
    private var last: Float?=null
    fun change(value: Float, range: ClosedFloatingPointRange<Float>): HapticCue? {
        val previous=last;last=value
        if(!dragging) {dragging=true;return HapticCue.Selection}
        return when(mode) {
            SliderHaptics.Seek -> null
            SliderHaptics.Volume -> if(previous?.roundToInt()!=value.roundToInt()) if(value==range.start || value==range.endInclusive) HapticCue.Threshold else HapticCue.Selection else null
            SliderHaptics.Intensity -> if(previous!=null && listOf(0f,1f,1.5f).any { point -> (previous<point && value>=point) || (previous>point && value<=point) }) HapticCue.Threshold else null
        }
    }
    fun finish(): HapticCue? { val active=dragging;dragging=false;last=null;return if(active) HapticCue.DragEnd else null }
}
