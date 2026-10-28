package com.musicglass.personal

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Feature 15: the speeds offered, 0.5× to 2× in steps of 0.05×, with the common ones as chips. */
internal const val SPEED_MIN = .5f
internal const val SPEED_MAX = 2f
internal const val SPEED_STEP = .05f
internal val SPEED_CHIPS = listOf(.75f, 1f, 1.25f, 1.5f, 2f)

/** Kept in range and on a step, so a slider never leaves 1.0000001×. */
internal fun playbackSpeedStep(speed: Float): Float =
    (Math.round(speed.coerceIn(SPEED_MIN, SPEED_MAX) / SPEED_STEP) * SPEED_STEP).let { Math.round(it * 100) / 100f }

/** "1.25×", "2×". */
internal fun playbackSpeedLabel(speed: Float): String =
    string(R.string.speed_x, if (speed % 1f == 0f) speed.toInt().toString() else playbackSpeedStep(speed).toString().trimEnd('0'))

/**
 * Feature 15: songs and episodes keep separate speeds. An episode's speed is kept on the phone (podcast listeners
 * set it once); a song's lasts while Riff runs and starts at 1× again after a restart.
 */
internal class PlaybackSpeeds(private val context: Context) {
    private val prefs get() = context.applicationContext.getSharedPreferences("playback_speed", Context.MODE_PRIVATE)
    var songs = 1f; private set
    fun forKind(music: Boolean): Float = if (music) songs else playbackSpeedStep(prefs.getFloat("episodes", 1f))
    fun remember(music: Boolean, speed: Float) {
        val step = playbackSpeedStep(speed)
        if (music) songs = step else prefs.edit().putFloat("episodes", step).apply()
    }
}

@Composable
internal fun PlaybackSpeedDialog(playback: PlaybackGateway, onDismiss: () -> Unit) {
    var speed by remember { mutableFloatStateOf(playback.state.speed) }
    fun choose(value: Float) { speed = playbackSpeedStep(value); playback.setPlaybackSpeed(speed) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(string(R.string.playback_speed)) }, text = {
        Column(Modifier.testTag("playback_speed")) {
            Text(playbackSpeedLabel(speed), modifier = Modifier.testTag("playback_speed_value"))
            CapsuleSlider(speed, { choose(it) }, valueRange = SPEED_MIN..SPEED_MAX, steps = ((SPEED_MAX - SPEED_MIN) / SPEED_STEP).toInt() - 1,
                haptics = SliderHaptics.Intensity, label = string(R.string.playback_speed), spokenValue = playbackSpeedLabel(speed),
                modifier = Modifier.fillMaxWidth().testTag("playback_speed_slider"))
            Row(horizontalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
                SPEED_CHIPS.forEach { chip ->
                    FilterChip(speed == chip, { uiHaptic(HapticCue.Selection); choose(chip) }, { Text(playbackSpeedLabel(chip)) },
                        modifier = Modifier.testTag("playback_speed_${chip}"))
                }
            }
            Text(string(R.string.speed_remembered_for_episodes), color = Muted, fontSize = RiffType.detail)
        }
    }, confirmButton = { TextButton({ uiHaptic(); onDismiss() }) { Text(string(R.string.done)) } })
}
