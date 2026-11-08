package com.musicglass.personal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the panel says of the song playing, apart from a preparation that is running. */
internal enum class SingSongStatus { Ready, CanPrepare, Loading, NotOnPhone, NoModel }

/** The message for a preparation that stopped short. */
internal fun singFailureText(failure: SingFailure): Int = when (failure) {
    SingFailure.NotOnPhone -> R.string.sing_not_on_phone
    SingFailure.Unsupported -> R.string.sing_unsupported
    SingFailure.Hot -> R.string.sing_hot
    SingFailure.Failed -> R.string.sing_failed
}

/** Feature 20 (Riff+): Sing's switch, and the song playing prepared for it on this phone. */
@Composable internal fun SingDialog(playback: PlaybackGateway, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = playback.state
    val store = remember { SingStore.get(context) }
    val generation by store.generation.collectAsStateWithLifecycle()
    val job by SingWork.state.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(SingSwitch.on) }
    var recheck by remember { mutableStateOf(0) }
    val status by produceState<SingSongStatus?>(null, state.mediaId, generation, recheck) {
        value = withContext(Dispatchers.IO) {
            when {
                !SingModel.present(context) -> SingSongStatus.NoModel
                store.has(state.mediaId) -> SingSongStatus.Ready
                djSource(context, state.mediaId) != null || streamedWhole(context, state.mediaId) -> SingSongStatus.CanPrepare
                // A streamed song: the player is still loading the rest of it (owner, 9 November 2026).
                state.mediaId.startsWith("yt:") && !state.live -> SingSongStatus.Loading
                else -> SingSongStatus.NotOnPhone
            }
        }
    }
    FrostedAlertDialog(onDismissRequest = onDismiss, title = { Text(string(R.string.sing)) }, text = {
        Column(Modifier.fillMaxWidth().testTag("sing_panel"), verticalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
            Text(string(R.string.sing_help), color = Muted, fontSize = RiffType.detail)
            val spoken = string(if (on) R.string.sing_on else R.string.sing_off)
            Row(Modifier.fillMaxWidth().clickable { uiHaptic(HapticCue.Selection); on = !on; SingSwitch.set(context, on) }
                .semantics { role = Role.Switch; stateDescription = spoken }.testTag("sing_switch"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(string(R.string.sing_vocals_off), color = White, style = RiffText.body)
                    Text(string(R.string.sing_vocals_off_detail), color = Muted, fontSize = RiffType.detail)
                }
                Spacer(Modifier.width(RiffSpace.item))
                GlassSwitch(on)
            }
            if (state.remote) Text(string(R.string.sing_not_on_cast), color = Muted, fontSize = RiffType.detail)
            val running = job?.takeIf { it.running }
            val ended = job?.takeIf { !it.running && it.mediaId == state.mediaId }
            when {
                running != null && running.mediaId == state.mediaId -> Column(Modifier.testTag("sing_preparing")) {
                    val p = running.progress
                    Text(if (p == null) string(R.string.sing_preparing) else string(R.string.sing_preparing_percent, (p * 100).toInt()), color = White)
                    if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                    TextButton({ uiHaptic(); SingWork.cancel() }, Modifier.testTag("sing_stop")) { Text(string(R.string.sing_stop)) }
                }
                running != null -> Text(string(R.string.sing_busy), color = Muted, fontSize = RiffType.detail)
                status == SingSongStatus.Ready -> Text(string(R.string.sing_ready), color = White, modifier = Modifier.testTag("sing_ready"))
                status == SingSongStatus.NotOnPhone -> Text(string(R.string.sing_not_on_phone), color = Muted, fontSize = RiffType.detail)
                status == SingSongStatus.Loading -> {
                    Text(string(R.string.sing_loading_song), color = Muted, fontSize = RiffType.detail, modifier = Modifier.testTag("sing_loading_song"))
                    LaunchedEffect(state.mediaId) { kotlinx.coroutines.delay(2_000); recheck++ }
                }
                status == SingSongStatus.NoModel -> Text(string(R.string.sing_failed), color = Muted, fontSize = RiffType.detail)
                status == SingSongStatus.CanPrepare -> Column {
                    ended?.failure?.let { Text(string(singFailureText(it)), color = Muted, fontSize = RiffType.detail, modifier = Modifier.testTag("sing_failure")) }
                    Text(string(R.string.sing_prepare_detail), color = Muted, fontSize = RiffType.detail)
                    TextButton({ uiHaptic(); SingWork.start(context, state.mediaId, state.durationMs) }, Modifier.testTag("sing_prepare")) {
                        Text(string(R.string.sing_prepare))
                    }
                }
            }
        }
    }, confirmButton = { TextButton({ uiHaptic(); onDismiss() }) { Text(string(R.string.done)) } })
}

/** Settings → Downloads: what Sing keeps, and a Clear (owner, 9 November 2026). */
@Composable internal fun SingStorageSettings(onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { SingStore.get(context) }
    val generation by store.generation.collectAsStateWithLifecycle()
    val bytes by produceState(0L, generation) { value = withContext(Dispatchers.IO) { store.size() } }
    var confirm by remember { mutableStateOf(false) }
    SettingsDivider()
    SettingsLinkRow(string(R.string.clear_sing_storage), storageLabel(bytes), Icons.Rounded.MicOff, "settings_clear_sing") { confirm = true }
    if (confirm) FrostedAlertDialog(onDismissRequest = { confirm = false }, title = { Text(string(R.string.clear_sing_storage)) },
        text = { Text(string(R.string.clear_sing_storage_prompt, storageLabel(bytes))) },
        confirmButton = { TextButton({ confirm = false; uiHaptic(HapticCue.Confirm); store.clear(); onNotice(string(R.string.sing_storage_cleared)) },
            Modifier.testTag("confirm_clear_sing")) { Text(string(R.string.clear_cache), color = Pink) } },
        dismissButton = { TextButton({ confirm = false }) { Text(string(R.string.cancel)) } })
}
