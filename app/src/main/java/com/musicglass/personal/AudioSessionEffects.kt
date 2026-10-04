package com.musicglass.personal

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.log10
import kotlin.math.roundToInt

/** Linear gain to the millibels Android's LoudnessEnhancer takes; 300% is about +9.5 dB. */
internal fun boostMillibels(gain: Float): Int =
    if (!gain.isFinite() || gain <= 1f) 0 else (2000 * log10(gain.coerceAtMost(3f))).roundToInt()

/**
 * Publishes the player's audio session so system effects (Dolby, OEM sound modes, equalizers) can attach,
 * and applies the volume booster with Android's LoudnessEnhancer, which has its own limiter.
 * If the platform effect is unavailable, the booster falls back to Riff's PCM processor.
 */
internal class AudioSessionEffects(private val context: Context, private val fallback: PcmVolumeBoost,
    private val log: (String) -> Unit = {}) {
    private var session = 0
    private var enhancer: LoudnessEnhancer? = null
    private var gain = 1f

    fun attach(audioSessionId: Int) {
        if (audioSessionId == session) return
        release()
        if (audioSessionId <= 0) return
        session = audioSessionId
        broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        enhancer = try { LoudnessEnhancer(audioSessionId) } catch (e: Exception) {
            log("loudness_enhancer_unavailable type=${e::class.simpleName}"); null
        }
        apply()
    }

    fun setGain(value: Float) { gain = value; apply() }

    private fun apply() {
        val effect = enhancer
        val millibels = boostMillibels(gain)
        val native = effect != null && try {
            effect.setTargetGain(millibels)
            effect.setEnabled(millibels > 0) == AudioEffect.SUCCESS && (millibels == 0 || effect.hasControl())
        } catch (e: Exception) { log("loudness_enhancer_failed type=${e::class.simpleName}"); false }
        fallback.targetGain = if (native) 1f else gain
    }

    fun release() {
        if (session > 0) broadcast(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        runCatching { enhancer?.release() }
        enhancer = null; session = 0
        fallback.targetGain = gain
    }

    private fun broadcast(action: String) {
        context.sendBroadcast(Intent(action)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC))
    }
}
