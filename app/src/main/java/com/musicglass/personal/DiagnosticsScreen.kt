package com.musicglass.personal

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What an exit reason means, in words a user reads (Q15A). */
internal fun exitReasonText(reason: String): String = when (reason) {
    "crash" -> string(R.string.exit_crash)
    "crash_native" -> string(R.string.exit_crash_native)
    "anr" -> string(R.string.exit_anr)
    "low_memory" -> string(R.string.exit_low_memory)
    "excessive_resource_usage" -> string(R.string.exit_excessive_resource_usage)
    "init_failure" -> string(R.string.exit_init_failure)
    "dependency_died" -> string(R.string.exit_dependency_died)
    "user_requested" -> string(R.string.exit_user_requested)
    "user_stopped" -> string(R.string.exit_user_stopped)
    "exit_self" -> string(R.string.exit_self)
    "package_updated" -> string(R.string.exit_package_updated)
    "permission_change" -> string(R.string.exit_permission_change)
    else -> string(R.string.exit_other)
}

internal fun processRoleText(role: ProcessRole): String = when (role) {
    ProcessRole.Main -> string(R.string.app_name)
    ProcessRole.Solver -> string(R.string.stream_helper)
    ProcessRole.Other -> string(R.string.another_part_of_riff)
}

/**
 * Share diagnostics report (Q15A): fixed content, built only from records that are already reduced, and every
 * line reduced again on the way out. Build identity, the exits, the errors, then the event ring.
 */
internal fun diagnosticsReport(exits: List<ExitRecord>, crashes: List<CrashRecord>, ring: List<String>, version: String,
                               sdk: Int, device: String, identity: BuildIdentity = BuildIdentity.current()): String {
    val playback = playbackDiagnosticsReport(ring, version, sdk, device, identity).lines()
    val errors = crashes.flatMap { crash -> listOf(DiagnosticEvent.parse(crash.line).text) + crash.frames.filter(appFrame::matches).map { "  at $it" } }
    return (playback.take(2) +
        "${exits.size} process exits (oldest first)" + exits.map { DiagnosticEvent.parse(it.line).text } +
        "${crashes.size} errors (oldest first)" + errors +
        playback.drop(2)).joinToString("\n")
}

private class DiagnosticsData(val exits: List<ExitRecord>, val crashes: List<CrashRecord>)

private suspend fun loadDiagnostics(context: Context, io: CoroutineContext) = withContext(io) {
    ExitStore.of(context).let { DiagnosticsData(it.exits(), it.crashes()) }
}

private suspend fun buildReport(context: Context, io: CoroutineContext) = withContext(io) {
    val store = ExitStore.of(context)
    diagnosticsReport(store.exits(), store.crashes(), PlaybackEventLog(context).recent(), BuildConfig.VERSION_NAME,
        android.os.Build.VERSION.SDK_INT, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
}

/** [io] is where the records are read and written; tests read them in place (Q18-105). */
@Composable internal fun DiagnosticsScreen(model: ShellModel, bottom: Dp, io: CoroutineContext = Dispatchers.IO, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<DiagnosticsData?>(null) }
    LaunchedEffect(context) { data = loadDiagnostics(context, io) }
    ScreenList(model, bottom) {
        // The list's shape never changes once the records are read (the errors item is always there, empty when
        // there are none), so no row moves under a finger.
        item(key = "back") { BackHeading(string(R.string.settings), model::back) }
        item(key = "title") { ScreenTitle(string(R.string.diagnostics), string(R.string.diagnostics_detail)) }
        item(key = "exits") {
            SettingsSection(string(R.string.last_exits)) {
                val exits = data?.exits.orEmpty().asReversed()
                if (data != null && exits.isEmpty()) Text(string(R.string.no_exits_recorded), color = Muted, fontSize = RiffType.body,
                    modifier = Modifier.padding(vertical = RiffSpace.block))
                exits.forEachIndexed { index, exit ->
                    if (index > 0) SettingsDivider()
                    ExitRow(exit)
                }
            }
        }
        item(key = "errors") {
            val crashes = data?.crashes.orEmpty()
            if (crashes.isNotEmpty()) SettingsSection(string(R.string.errors)) {
                crashes.asReversed().forEachIndexed { index, crash ->
                    if (index > 0) SettingsDivider()
                    Column(Modifier.fillMaxWidth().padding(vertical = RiffSpace.rowGap).testTag("diagnostics_error")) {
                        Text(crash.causes.first(), color = White, fontSize = RiffType.body)
                        Text(listOf(processRoleText(crash.role), quantity(R.plurals.n_times, crash.count), hourText(context, crash.hour))
                            .joinToString(" · "), color = Muted, fontSize = RiffType.detail)
                    }
                }
            }
        }
        item(key = "report") {
            SettingsSection(string(R.string.report)) {
                SettingsLinkRow(string(R.string.share_diagnostics_report), icon = Icons.Rounded.Share, tag = "diagnostics_share") {
                    scope.launch {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, buildReport(context, io))
                        runCatching { context.startActivity(Intent.createChooser(send, string(R.string.share_diagnostics_report))) }
                    }
                }
                SettingsDivider()
                SettingsLinkRow(string(R.string.copy_playback_diagnostics), icon = Icons.Rounded.ContentCopy, tag = "diagnostics_copy") {
                    scope.launch {
                        val report = buildReport(context, io)
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText(string(R.string.riff_playback_diagnostics), report))
                        onNotice(string(R.string.playback_diagnostics_copied))
                    }
                }
                SettingsDivider()
                SettingsLinkRow(string(R.string.clear_exit_records), icon = Icons.Rounded.DeleteOutline, tag = "diagnostics_clear") {
                    scope.launch {
                        withContext(io) { ExitStore.of(context).clear() }
                        data = DiagnosticsData(emptyList(), emptyList())
                        onNotice(string(R.string.exit_records_cleared))
                    }
                }
            }
        }
        item(key = "report_detail") { Text(string(R.string.diagnostics_report_detail), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.item)) }
    }
}

@Composable private fun ExitRow(exit: ExitRecord) {
    val context = LocalContext.current
    val failure = exit.failure
    Row(Modifier.fillMaxWidth().heightIn(min = RiffSpace.listRow).padding(vertical = RiffSpace.item)
        .testTag(if (failure) "diagnostics_exit_failure" else "diagnostics_exit_ordinary"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RiffSpace.rowGap)) {
        Icon(if (failure) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircleOutline, null,
            tint = if (failure) Pink else Muted)
        Column(Modifier.weight(1f)) {
            Text(exitReasonText(exit.reason), color = White, fontSize = RiffType.body)
            Text(listOf(processRoleText(exit.role), hourText(context, exit.hour)).joinToString(" · "), color = Muted, fontSize = RiffType.detail)
        }
    }
}

private fun hourText(context: Context, hour: Long) = if (hour <= 0) "" else
    DateUtils.formatDateTime(context, hour, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
