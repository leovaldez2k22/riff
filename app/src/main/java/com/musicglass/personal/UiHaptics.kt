package com.musicglass.personal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

internal enum class HapticCue { Selection, Click, Confirm, Reject, ToggleOn, ToggleOff, DragStart, DragEnd, Threshold,
    /** F4: a slider's marks, in three strengths (the platform's heavy, standard and light clicks). */
    TickStrong, TickMedium, TickLight,
    /** Owner, 10 November 2026: moments of their own, each with its own feel (see [hapticRecipe]). */
    Play, Pause, Skip, SkipBack, Favorite, Queue, Download, Remove, Peek, PlayerOpen, PlayerClose, DragStep, Spin,
    Check, SelectStart, Dismiss, Mark }

/** One pulse of a cue: an Android composition primitive at a strength (0 to 1), after [delayMs]. */
internal data class HapticStep(val primitive: Int, val scale: Float, val delayMs: Int = 0)

/**
 * What each cue feels like on a phone that can play composition primitives (owner's table, 10 November 2026). Phones
 * that cannot get [hapticConstant] instead.
 */
internal fun hapticRecipe(cue: HapticCue): List<HapticStep> {
    val click = VibrationEffect.Composition.PRIMITIVE_CLICK; val tick = VibrationEffect.Composition.PRIMITIVE_TICK
    val low = VibrationEffect.Composition.PRIMITIVE_LOW_TICK; val thud = VibrationEffect.Composition.PRIMITIVE_THUD
    val spin = VibrationEffect.Composition.PRIMITIVE_SPIN; val rise = VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
    val slow = VibrationEffect.Composition.PRIMITIVE_SLOW_RISE; val fall = VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
    return when (cue) {
        HapticCue.Click -> listOf(HapticStep(click, .5f))
        HapticCue.Selection -> listOf(HapticStep(tick, .45f))
        HapticCue.Confirm -> listOf(HapticStep(click, .8f))
        HapticCue.Reject -> listOf(HapticStep(low, .9f), HapticStep(low, .9f, 60))
        HapticCue.ToggleOn -> listOf(HapticStep(rise, .35f), HapticStep(click, .7f))
        HapticCue.ToggleOff -> listOf(HapticStep(fall, .5f))
        HapticCue.DragStart -> listOf(HapticStep(thud, .5f))
        HapticCue.DragEnd -> listOf(HapticStep(click, .7f))
        HapticCue.Threshold -> listOf(HapticStep(rise, .6f))
        HapticCue.TickStrong -> listOf(HapticStep(tick, 1f))
        HapticCue.TickMedium -> listOf(HapticStep(tick, .6f))
        HapticCue.TickLight -> listOf(HapticStep(tick, .3f))
        HapticCue.Play -> listOf(HapticStep(click, 1f))
        HapticCue.Pause -> listOf(HapticStep(low, 1f))
        HapticCue.Skip -> listOf(HapticStep(tick, .7f))
        HapticCue.SkipBack -> listOf(HapticStep(tick, .9f))
        HapticCue.Favorite -> listOf(HapticStep(rise, .6f), HapticStep(click, 1f, 20))
        HapticCue.Queue -> listOf(HapticStep(tick, .8f), HapticStep(tick, .8f, 70))
        HapticCue.Download -> listOf(HapticStep(slow, .6f))
        HapticCue.Remove -> listOf(HapticStep(thud, .8f))
        HapticCue.Peek -> listOf(HapticStep(rise, .4f))
        HapticCue.PlayerOpen -> listOf(HapticStep(slow, .25f))
        HapticCue.PlayerClose -> listOf(HapticStep(fall, .3f))
        HapticCue.DragStep -> listOf(HapticStep(tick, .3f))
        HapticCue.Spin -> listOf(HapticStep(spin, .6f))
        HapticCue.Check -> listOf(HapticStep(tick, .6f))
        HapticCue.SelectStart -> listOf(HapticStep(thud, .4f))
        HapticCue.Dismiss -> listOf(HapticStep(fall, .4f))
        HapticCue.Mark -> listOf(HapticStep(tick, .7f))
    }
}

/** The owner's Haptic strength setting: a multiplier on every step, never past the primitive's full strength. */
internal enum class HapticStrength(val key: String, val factor: Float) { Light("light", .6f), Normal("normal", 1f), Strong("strong", 1.45f) }
internal fun hapticStrengthFrom(key: String?) = HapticStrength.entries.firstOrNull { it.key == key } ?: HapticStrength.Normal
internal fun hapticScale(step: HapticStep, strength: HapticStrength) = (step.scale * strength.factor).coerceIn(0f, 1f)

/** Plays a cue's recipe; false when this phone cannot, so the caller falls back to the view's feedback. */
internal fun interface HapticPlayer { fun play(cue: HapticCue, strength: HapticStrength): Boolean }

/** The phone's vibrator, used only for the touch moments above; needs VIBRATE (granted at install, no prompt). */
internal class SystemHapticPlayer(context: Context) : HapticPlayer {
    private val app = context.applicationContext
    private val vibrator: Vibrator? = app.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }
    private val supported = HashMap<HapticCue, Boolean>()
    @SuppressLint("MissingPermission") // declared in the manifest
    override fun play(cue: HapticCue, strength: HapticStrength): Boolean {
        val v = vibrator ?: return false
        // Android's own Touch feedback switch: performHapticFeedback reads it by itself, a vibrator does not.
        if (Settings.System.getInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return true
        val steps = hapticRecipe(cue)
        if (!supported.getOrPut(cue) { v.areAllPrimitivesSupported(*steps.map { it.primitive }.distinct().toIntArray()) }) return false
        val effect = VibrationEffect.startComposition().apply { steps.forEach { addPrimitive(it.primitive, hapticScale(it, strength), it.delayMs) } }.compose()
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)) else v.vibrate(effect)
            true
        }.getOrDefault(false)
    }
}

/**
 * The platform constant for a cue. Four of them arrived in API 34; before that the view ignores them, so an
 * older phone gets the nearest constant it does know instead of nothing.
 */
@SuppressLint("InlinedApi") // each newer constant is only chosen where sdk says the phone knows it
internal fun hapticConstant(cue: HapticCue, sdk: Int = Build.VERSION.SDK_INT): Int = when(cue) {
    HapticCue.Confirm, HapticCue.Favorite, HapticCue.Queue, HapticCue.Download -> HapticFeedbackConstants.CONFIRM
    HapticCue.Play, HapticCue.Skip, HapticCue.SkipBack, HapticCue.Check, HapticCue.Mark -> HapticFeedbackConstants.CONTEXT_CLICK
    HapticCue.Pause, HapticCue.DragStep, HapticCue.PlayerOpen, HapticCue.PlayerClose, HapticCue.Dismiss -> HapticFeedbackConstants.CLOCK_TICK
    HapticCue.Remove, HapticCue.Peek, HapticCue.SelectStart -> HapticFeedbackConstants.LONG_PRESS
    HapticCue.Spin -> HapticFeedbackConstants.VIRTUAL_KEY
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
    private var player: HapticPlayer? = null
    private var lastMs=Long.MIN_VALUE
    /** [haptics] plays the richer recipes; without it (tests, or a phone without a vibrator) the view's feedback is used. */
    fun bind(target: View, owner: Lifecycle, preferences: AccessibilitySettings, haptics: HapticPlayer? = null) {
        view=WeakReference(target); lifecycle=owner; settings=preferences; player=haptics; lastMs=Long.MIN_VALUE
    }
    fun unbind(target: View) { if(view.get()===target) {view.clear(); lifecycle=null; settings=null; player=null} }
    fun perform(cue: HapticCue) {
        val target=view.get()?:return
        val preferences=settings
        if(preferences?.hapticFeedback!=true || lifecycle?.currentState!=Lifecycle.State.RESUMED || !target.isAttachedToWindow || !target.isHapticFeedbackEnabled) return
        val now=SystemClock.uptimeMillis()
        if(lastMs!=Long.MIN_VALUE && now-lastMs<60) return
        if(player?.play(cue, preferences.hapticStrength)==true || target.performHapticFeedback(hapticConstant(cue))) lastMs=now
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
    fun change(value: Float, range: ClosedFloatingPointRange<Float>, mark: Float? = null): HapticCue? {
        val previous=last;last=value
        if(!dragging) {dragging=true;return HapticCue.Selection}
        return when(mode) {
            // Owner, 10 November 2026: a tick where the drag passes the Most replayed mark (Riff+), nothing else.
            SliderHaptics.Seek -> if(mark!=null && previous!=null && (previous-mark)*(value-mark)<=0f && previous!=value && previous!=mark) HapticCue.Mark else null
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
