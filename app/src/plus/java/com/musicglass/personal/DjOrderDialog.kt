package com.musicglass.personal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DJ_PREFS = "dj_order"
/** Undo outlives the dialog (and the queue, if it is closed while the offer shows). */
private val djUndoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** One song after the one playing, as the dialog saw the queue. */
internal data class DjEntry(val entry: String, val mediaId: String, val title: String, val artist: String)

/** The queue when the dialog looked: every entry id, the song playing and the songs after it. */
internal data class DjSnapshot(val ids: List<String>, val index: Int, val currentMediaId: String, val upcoming: List<DjEntry>) {
    companion object {
        fun of(state: PlaybackUiState) = DjSnapshot(state.queue.map { it.queueId }, state.index, state.queue.getOrNull(state.index)?.mediaId.orEmpty(),
            state.queue.drop(state.index + 1).map { DjEntry(it.queueId, it.mediaId, it.title, it.artist) })
    }
}

/** "128 BPM · A minor (8A)": what the order knows of a song, or that it stays in its place. */
internal fun djDetail(facts: DjFacts?, stays: Boolean): String {
    if (stays || facts?.bpm == null) return string(R.string.dj_stays)
    val key = facts.key?.let { string(if (it.minor) R.string.dj_key_minor else R.string.dj_key_major, it.noteName, it.camelotLabel) }
    return listOfNotNull(string(R.string.dj_bpm, Math.round(facts.bpm).toInt()), key).joinToString(" · ")
}

/** Feature 18 (Riff+): the queue's DJ order pill, beside Reshuffle. */
@Composable internal fun DjOrderAction(playback: PlaybackGateway, glass: RiffBackdrop?, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val state = playback.state
    QueueActionPill(state.queue.size - state.index - 1 >= 2, glass, string(R.string.dj_order), Icons.Rounded.GraphicEq, "queue_dj_order", modifier) {
        uiHaptic(); open = true
    }
    if (open) DjOrderDialog(playback) { open = false }
}

@Composable private fun DjOrderDialog(playback: PlaybackGateway, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(DJ_PREFS, Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(DjMode.entries.firstOrNull { it.key == prefs.getString("mode", null) } ?: DjMode.Steady) }
    var snapshot by remember { mutableStateOf(DjSnapshot.of(playback.state)) }
    var changed by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    val pinned by produceState<Set<String>?>(null, snapshot) { value = playback.queuePinned() ?: emptySet() }
    val facts by produceState<Map<String, DjFacts?>?>(null, snapshot, round) {
        value = withContext(Dispatchers.IO) { (snapshot.upcoming.map { it.mediaId } + snapshot.currentMediaId).distinct().associateWith { djFacts(context, it) } }
    }
    val analysable by produceState(0, snapshot, round) { value = withContext(Dispatchers.IO) { djAnalysable(context, snapshot.upcoming.map { it.mediaId }) } }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var analysis by remember { mutableStateOf<Job?>(null) }
    var stopped by remember { mutableStateOf<DjStop?>(null) }
    var applying by remember { mutableStateOf(false) }

    val known = facts; val pins = pinned
    val slots = if (known != null && pins != null) snapshot.upcoming.map { DjSlot(it.entry, known[it.mediaId], it.entry in pins) } else null
    val order = slots?.let { djOrder(it, known?.get(snapshot.currentMediaId), mode) }
    val coverage = slots?.let(::djCoverage)
    val byEntry = snapshot.upcoming.associateBy { it.entry }

    fun analyse() {
        stopped = null
        analysis = scope.launch {
            try { stopped = analyseQueueForDj(context, snapshot.upcoming.map { it.mediaId }) { done, total -> progress = done to total } }
            finally { progress = null; analysis = null; round++ }
        }
    }

    fun apply(target: List<String>) {
        applying = true
        scope.launch {
            val head = snapshot.ids.take(snapshot.index + 1)
            val previous = snapshot.ids
            if (playback.reorderQueue(snapshot.ids, head + target)) {
                uiHaptic(HapticCue.Confirm)
                onDismiss()
                UndoNotices.show(string(R.string.dj_applied)) {
                    djUndoScope.launch {
                        val now = playback.state
                        val ids = now.queue.map { it.queueId }
                        playback.reorderQueue(ids, djUndoOrder(ids, now.index, previous, playback.queuePinned().orEmpty()))
                    }
                }
            } else {
                // The queue moved on while the dialog was open: show the order for the queue as it is now.
                changed = true; snapshot = DjSnapshot.of(playback.state)
            }
            applying = false
        }
    }

    LaunchedEffect(mode) { prefs.edit().putString("mode", mode.key).apply() }

    FrostedAlertDialog(onDismissRequest = { analysis?.cancel(); onDismiss() }, title = { Text(string(R.string.dj_order)) },
        text = {
            Column(Modifier.testTag("dj_order"), verticalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
                Text(string(R.string.dj_order_help), color = Muted, fontSize = RiffType.body)
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
                    DjMode.entries.forEach { m ->
                        val selected = m == mode
                        Text(string(m.label), color = if (selected) Ink else White, fontSize = RiffType.body, maxLines = 1,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(RiffRadius.pill))
                                .then(if (selected) Modifier.background(Pink) else Modifier)
                                .selectable(selected, role = Role.RadioButton) { uiHaptic(HapticCue.Selection); mode = m }
                                .padding(RiffSpace.item).testTag("dj_mode_${m.key}"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                if (changed) Text(string(R.string.dj_changed), color = White, fontSize = RiffType.detail)
                coverage?.let { c ->
                    Text(if (c.withTempo == 0) string(R.string.dj_nothing) else string(R.string.dj_coverage, c.withTempo, c.upcoming, c.withKey),
                        color = Muted, fontSize = RiffType.detail, modifier = Modifier.testTag("dj_coverage"))
                }
                val running = progress
                when {
                    running != null -> Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(string(R.string.dj_analysing, minOf(running.first + 1, running.second), running.second), color = White, fontSize = RiffType.detail, modifier = Modifier.weight(1f))
                        TextButton({ analysis?.cancel() }) { Text(string(R.string.dj_stop)) }
                    }
                    analysable > 0 -> TextButton(::analyse, Modifier.testTag("dj_analyse")) { Text(quantity(R.plurals.dj_analyse, analysable)) }
                }
                stopped?.let { Text(string(if (it == DjStop.BatterySaver) R.string.dj_stopped_battery else R.string.dj_stopped_warm), color = White, fontSize = RiffType.detail) }
                if (order != null && known != null) LazyColumn(Modifier.fillMaxWidth().heightIn(max = RiffDjOrder.previewMax).testTag("dj_preview")) {
                    itemsIndexed(order, key = { _, id -> id }) { _, id ->
                        val entry = byEntry[id] ?: return@itemsIndexed
                        val slot = slots.firstOrNull { it.entry == id }
                        Column(Modifier.fillMaxWidth().padding(vertical = RiffSpace.tight)) {
                            Text(entry.title, color = White, fontSize = RiffType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(entry.artist, djDetail(known[entry.mediaId], slot?.movable != true)).filter { it.isNotBlank() }.joinToString(" · "),
                                color = Muted, fontSize = RiffType.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val target = order
            val upcomingIds = snapshot.upcoming.map { it.entry }
            TextButton({ if (target != null) apply(target) }, enabled = target != null && target != upcomingIds && !applying && progress == null,
                modifier = Modifier.testTag("dj_apply")) { Text(string(R.string.dj_apply)) }
        },
        dismissButton = { TextButton({ analysis?.cancel(); onDismiss() }) { Text(string(R.string.cancel)) } })
}

private val DjMode.label: Int get() = when (this) {
    DjMode.Steady -> R.string.dj_mode_steady
    DjMode.BuildUp -> R.string.dj_mode_build_up
    DjMode.WindDown -> R.string.dj_mode_wind_down
}
