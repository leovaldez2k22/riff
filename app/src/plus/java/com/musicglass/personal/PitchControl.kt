package com.musicglass.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight

/** Why pitch cannot be changed now, or null when it can: songs only, on the phone (owner, Q23). */
internal fun pitchUnavailable(state: PlaybackUiState): Int? = when {
    state.remote -> R.string.pitch_not_on_cast
    state.live -> R.string.pitch_not_live
    !state.music -> R.string.pitch_songs_only
    else -> null
}

/** Feature 23 (Riff+): −6 to +6 whole semitones for songs, apart from speed, for this session; Reset puts it back. */
@Composable internal fun PitchSteps(playback: PlaybackGateway) {
    val state = playback.state
    var steps by remember { mutableIntStateOf(pitchSteps(state.pitch)) }
    val blocked = pitchUnavailable(state)
    fun choose(value: Int) { steps = value.coerceIn(PITCH_MIN_STEPS, PITCH_MAX_STEPS); uiHaptic(HapticCue.Selection); playback.setPitchSteps(steps) }
    Column(Modifier.fillMaxWidth().padding(top = RiffSpace.item).testTag("pitch_control")) {
        Text(string(R.string.pitch), color = White, fontWeight = FontWeight.SemiBold)
        if (blocked != null) { Text(string(blocked), color = Muted, fontSize = RiffType.detail, modifier = Modifier.testTag("pitch_unavailable")); return@Column }
        val spoken = string(R.string.pitch_value, pitchLabel(steps))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
            IconButton({ choose(steps - 1) }, enabled = steps > PITCH_MIN_STEPS, modifier = Modifier.testTag("pitch_down")) {
                Icon(Icons.Rounded.Remove, string(R.string.pitch_down)) }
            Text(pitchLabel(steps), color = if (steps == 0) White else Pink, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("pitch_value").semantics { contentDescription = spoken; stateDescription = spoken })
            IconButton({ choose(steps + 1) }, enabled = steps < PITCH_MAX_STEPS, modifier = Modifier.testTag("pitch_up")) {
                Icon(Icons.Rounded.Add, string(R.string.pitch_up)) }
            TextButton({ choose(0) }, enabled = steps != 0, modifier = Modifier.testTag("pitch_reset")) { Text(string(R.string.pitch_reset)) }
        }
        Text(string(R.string.pitch_help), color = Muted, fontSize = RiffType.detail)
    }
}
