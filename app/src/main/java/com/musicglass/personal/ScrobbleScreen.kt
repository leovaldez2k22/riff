package com.musicglass.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun serviceName(service: ScrobbleService) = when (service) {
    ScrobbleService.ListenBrainz -> "ListenBrainz"; ScrobbleService.LastFm -> "Last.fm" }

/** Feature 2: connecting ListenBrainz and Last.fm, and what is sent to them. */
@Composable internal fun ScrobblingScreen(model: ShellModel, bottom: Dp, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { ScrobbleSettings.get(context) }
    val outbox = remember(context) { ScrobbleOutbox.get(context) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var askToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var lastFmToken by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf<ScrobbleService?>(null) }
    val historyOn = remember(revision) { historyEnabled(context) }
    fun changed() { revision++ }

    if (askToken) FrostedAlertDialog(onDismissRequest = { if (!busy) { askToken = false; token = "" } },
        title = { Text(string(R.string.scrobble_connect_title, serviceName(ScrobbleService.ListenBrainz))) },
        text = { Column {
            Text(string(R.string.scrobble_listenbrainz_token_help))
            OutlinedTextField(token, { token = it.trim().take(200) }, singleLine = true, label = { Text(string(R.string.scrobble_user_token)) },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = RiffSpace.item).testTag("scrobble_token"))
        } },
        confirmButton = { TextButton(enabled = !busy && token.isNotBlank(), modifier = Modifier.testTag("scrobble_token_connect"), onClick = {
            busy = true
            scope.launch {
                val user = withContext(Dispatchers.IO) { runCatching { ListenBrainzClient().validate(token) } }
                busy = false
                when {
                    user.isFailure -> onNotice(string(R.string.scrobble_not_reached, serviceName(ScrobbleService.ListenBrainz)))
                    user.getOrNull() == null -> { uiHaptic(HapticCue.Reject); onNotice(string(R.string.scrobble_token_refused)) }
                    !settings.connect(ScrobbleService.ListenBrainz, user.getOrThrow()!!, token) -> onNotice(string(R.string.scrobble_not_kept))
                    else -> { uiHaptic(HapticCue.Confirm); askToken = false; token = ""; changed()
                        onNotice(string(R.string.scrobble_connected_notice, serviceName(ScrobbleService.ListenBrainz))) }
                }
            }
        }) { Text(string(R.string.scrobble_connect)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { askToken = false; token = "" }) { Text(string(R.string.cancel)) } })

    lastFmToken?.let { pending ->
        FrostedAlertDialog(onDismissRequest = { if (!busy) lastFmToken = null },
            title = { Text(string(R.string.scrobble_connect_title, serviceName(ScrobbleService.LastFm))) },
            text = { Text(string(R.string.scrobble_lastfm_finish_help)) },
            confirmButton = { TextButton(enabled = !busy, modifier = Modifier.testTag("scrobble_lastfm_done"), onClick = {
                busy = true
                scope.launch {
                    val session = withContext(Dispatchers.IO) { runCatching { LastFmClient().session(pending) } }
                    busy = false
                    val got = session.getOrNull()
                    when {
                        session.isFailure -> onNotice(string(R.string.scrobble_not_reached, serviceName(ScrobbleService.LastFm)))
                        got == null -> onNotice(string(R.string.scrobble_lastfm_not_allowed))
                        !settings.connect(ScrobbleService.LastFm, got.first, got.second) -> onNotice(string(R.string.scrobble_not_kept))
                        else -> { uiHaptic(HapticCue.Confirm); lastFmToken = null; changed()
                            onNotice(string(R.string.scrobble_connected_notice, serviceName(ScrobbleService.LastFm))) }
                    }
                }
            }) { Text(string(R.string.scrobble_lastfm_allowed)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { lastFmToken = null }) { Text(string(R.string.cancel)) } })
    }

    leaving?.let { service ->
        val waiting = remember(service, revision) { outbox.rows(service).size }
        FrostedAlertDialog(onDismissRequest = { leaving = null }, title = { Text(string(R.string.scrobble_disconnect_title, serviceName(service))) },
            text = { Text(if (waiting > 0) quantity(R.plurals.n_scrobble_disconnect_pending, waiting) else string(R.string.scrobble_disconnect_detail)) },
            confirmButton = { TextButton(modifier = Modifier.testTag("scrobble_disconnect_confirm"), onClick = {
                settings.disconnect(service); outbox.clear(service); leaving = null; changed(); uiHaptic(HapticCue.Confirm)
            }) { Text(string(R.string.scrobble_disconnect)) } },
            dismissButton = { TextButton({ leaving = null }) { Text(string(R.string.cancel)) } })
    }

    SettingsPage(model, bottom, string(R.string.scrobbling)) {
        item { Text(string(R.string.scrobbling_detail), color = Muted, fontSize = RiffType.detail) }
        if (!historyOn) item(key = "history_off") { BentoTile(Modifier.fillMaxWidth(), tag = "scrobble_history_off") { BentoHelp(string(R.string.scrobble_history_off), White) } }
        ScrobbleService.entries.filter(::scrobbleOffered).forEach { service ->
            item(key = service.key) {
                val account = remember(service, revision) { settings.account(service) }
                val waiting = remember(service, revision) { outbox.rows(service).size }
                val on = remember(service, revision) { settings.enabled(service) }
                BentoTile(Modifier.fillMaxWidth(), lit = account != null && !account.broken && on, tag = "scrobble_${service.key}") {
                    BentoTitle(serviceName(service))
                    BentoHelp(when {
                        account == null -> string(R.string.scrobble_not_connected)
                        account.broken -> string(R.string.scrobble_refused, serviceName(service))
                        else -> string(R.string.scrobble_connected_as, account.user)
                    })
                    if (waiting > 0) BentoHelp(quantity(R.plurals.n_scrobble_waiting, waiting))
                    if (account != null && !account.broken) SettingsToggleRow(string(R.string.scrobble_send_plays), on,
                        { settings.setEnabled(service, it); changed() }, Icons.Rounded.Podcasts, "scrobble_${service.key}_on")
                    Row(horizontalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
                        if (account == null || account.broken) TextButton(enabled = !busy, modifier = Modifier.testTag("scrobble_${service.key}_connect"), onClick = {
                            uiHaptic()
                            if (service == ScrobbleService.ListenBrainz) askToken = true else {
                                busy = true
                                scope.launch {
                                    val client = LastFmClient()
                                    val request = withContext(Dispatchers.IO) { runCatching { client.requestToken() }.getOrNull() }
                                    busy = false
                                    if (request == null) onNotice(string(R.string.scrobble_not_reached, serviceName(service)))
                                    else if (openWebPage(context, client.authorizeUrl(request), listOf("www.last.fm"))) lastFmToken = request
                                    else onNotice(string(R.string.scrobble_no_browser))
                                }
                            }
                        }) { Text(string(if (account == null) R.string.scrobble_connect else R.string.scrobble_reconnect)) }
                        if (account != null) TextButton({ leaving = service }, modifier = Modifier.testTag("scrobble_${service.key}_disconnect")) {
                            Text(string(R.string.scrobble_disconnect)) }
                    }
                }
            }
        }
        item(key = "options") {
            val nowPlaying = remember(revision) { settings.nowPlaying }; val phoneFiles = remember(revision) { settings.phoneFiles }
            BentoTile(Modifier.fillMaxWidth()) {
                SettingsToggleRow(string(R.string.scrobble_now_playing), nowPlaying, { settings.nowPlaying = it; changed() },
                    Icons.Rounded.Campaign, "scrobble_now_playing", string(R.string.scrobble_now_playing_help))
                SettingsToggleRow(string(R.string.scrobble_phone_files), phoneFiles, { settings.phoneFiles = it; changed() },
                    Icons.Rounded.PhoneAndroid, "scrobble_phone_files", string(R.string.scrobble_phone_files_help))
            }
        }
    }
}
