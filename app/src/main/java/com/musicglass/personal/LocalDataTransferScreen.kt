package com.musicglass.personal

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** A document the user chose in the system picker. Riff can reach this one document and nothing else. */
internal class PickedDocument(val read: () -> InputStream, val write: () -> OutputStream, val delete: () -> Unit)

/** The system picker. [create] proposes a file name; both answer null when the user backs out. */
internal class DocumentPicker(val create: (String, (PickedDocument?) -> Unit) -> Unit, val open: ((PickedDocument?) -> Unit) -> Unit)

/** No storage permission is requested: access comes from the user's choice in the picker, one document at a time. */
@Composable internal fun rememberSystemDocuments(): DocumentPicker {
    val resolver = LocalContext.current.applicationContext.contentResolver
    var pending by remember { mutableStateOf<((PickedDocument?) -> Unit)?>(null) }
    fun picked(uri: Uri?) {
        val answer = pending; pending = null
        answer?.invoke(uri?.let {
            PickedDocument({ resolver.openInputStream(it) ?: throw java.io.IOException("unavailable") },
                { resolver.openOutputStream(it, "wt") ?: throw java.io.IOException("unavailable") },
                { runCatching { DocumentsContract.deleteDocument(resolver, it) } })
        })
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), ::picked)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::picked)
    return remember(create, open) {
        DocumentPicker({ name, answer -> pending = answer; runCatching { create.launch(name) }.onFailure { pending = null; answer(null) } },
            { answer -> pending = answer; runCatching { open.launch(arrayOf("*/*")) }.onFailure { pending = null; answer(null) } })
    }
}

internal val EXPORT_WARNING: String get() = string(R.string.the_file_is_not_encrypted_it_contains)

/** What a section holds, in the user's words. */
internal fun describeCounts(c: SectionCounts): String = listOfNotNull(
    c.settings.takeIf { it > 0 }?.let { quantity(R.plurals.n_settings, it) }, c.hidden.takeIf { it > 0 }?.let { quantity(R.plurals.n_hidden_items, it) },
    c.saved.takeIf { it > 0 }?.let { quantity(R.plurals.n_saved_tracks, it) }, c.recents.takeIf { it > 0 }?.let { quantity(R.plurals.n_recently_played, it) },
    c.searches.takeIf { it > 0 }?.let { quantity(R.plurals.n_searches, it) }, c.plays.takeIf { it > 0 }?.let { quantity(R.plurals.n_plays, it) },
    c.downloads.takeIf { it > 0 }?.let { quantity(R.plurals.n_download_names, it) }).joinToString(", ").ifEmpty { string(R.string.nothing) }

internal fun rejectText(reason: RejectReason): String = when (reason) {
    RejectReason.NotRiff -> string(R.string.this_is_not_a_riff_data_file)
    RejectReason.Newer -> string(R.string.this_file_was_made_by_a_newer)
    RejectReason.Truncated -> string(R.string.this_file_is_incomplete_or_damaged)
    RejectReason.TooLarge -> string(R.string.this_file_is_larger_than_128_mb)
    RejectReason.Unreadable -> string(R.string.riff_could_not_read_this_file)
    RejectReason.Storage -> string(R.string.there_is_not_enough_free_space_on)
} + string(R.string.nothing_was_changed)

internal fun exportText(outcome: ExportOutcome): String = when (outcome) {
    is ExportOutcome.Done -> quantity(R.plurals.n_items_exported, outcome.records)
    ExportOutcome.Cancelled -> string(R.string.export_cancelled_no_file_was_written)
    ExportOutcome.TooLarge -> string(R.string.the_file_would_be_larger_than_128)
    is ExportOutcome.Failed -> string(R.string.could_not_export_no_usable_file_was)
}

/** Every line of the summary shown after an import. Nothing that was skipped or failed is left out. */
internal fun importSummary(report: ImportReport): List<String> = buildList {
    add(if (report.added.total == 0) string(R.string.nothing_new_was_added) else string(R.string.added_x, describeCounts(report.added)))
    if (report.present > 0) add(quantity(R.plurals.n_items_already_on_this_phone_were, report.present))
    if (report.malformed > 0) add(quantity(R.plurals.n_records_could_not_be_read_and, report.malformed))
    if (report.overLimit > 0) add(quantity(R.plurals.n_items_did_not_fit_a_list, report.overLimit))
    if (report.expired > 0) add(quantity(R.plurals.n_plays_older_than_your_history_period, report.expired))
    if (report.settingsKept > 0) add(string(R.string.the_history_period_was_kept_as_it))
    if (report.sectionsLeft > 0) add(quantity(R.plurals.n_account_sections_were_not_imported, report.sectionsLeft))
    if (report.added.downloads > 0) add(string(R.string.downloads_are_recorded_by_name_only_nothing))
    if (report.notStored.isNotEmpty()) add(string(R.string.could_not_be_stored_x_import_the, report.notStored.joinToString(", ") { keyLabel(it) }))
}

private sealed interface TransferStep {
    data class ChooseExport(val owners: List<ExportOwner>) : TransferStep
    data class Exporting(val done: Int) : TransferStep
    data object Reading : TransferStep
    data class Preview(val plan: ImportPlan) : TransferStep
    data class ConfirmAdopt(val plan: ImportPlan) : TransferStep
    data class Importing(val done: Int, val total: Int) : TransferStep
    data class Result(val title: String, val lines: List<String>) : TransferStep
}

/**
 * Export and import of local data, in Settings → Privacy and data. Each step states what it will do
 * before it does it; backing out of any step changes nothing.
 */
@Composable internal fun LocalDataTransferSection(provider: YouTubeMusicProvider, ownerKey: String, owner: String, onNotice: (String) -> Unit,
    transfer: LocalDataTransfer, documents: DocumentPicker = rememberSystemDocuments(), onImported: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<TransferStep?>(null) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var adopt by remember { mutableStateOf(emptySet<Int>()) }
    val stop = remember { AtomicBoolean(false) }
    val accountName = if (provider.accountScope() is AccountScope.Provider && ownerKey != UNVERIFIED_SCOPE_KEY) owner else null

    fun ownerName(kind: OwnerKind, key: String?, position: Int) = when (kind) {
        OwnerKind.Device -> string(R.string.this_phone)
        OwnerKind.Guest -> string(R.string.signed_out_listening)
        OwnerKind.Provider -> if (key == ownerKey && accountName != null) accountName.replaceFirstChar(Char::uppercase) else string(R.string.another_account_used_on_this_phone_x, position)
    }
    fun close(plan: ImportPlan? = null) { step = null; if (plan != null) scope.launch { transfer.discard(plan) } }

    fun startExport() {
        scope.launch {
            val owners = transfer.inventory()
            // Other accounts' data is offered but not preselected: it may belong to someone else.
            chosen = owners.filter { it.kind != OwnerKind.Provider || it.key == ownerKey }.mapTo(mutableSetOf()) { it.key }
            step = TransferStep.ChooseExport(owners)
        }
    }
    fun export() {
        val owners = chosen
        val day = java.time.LocalDate.now().toString()
        documents.create("riff-local-data-$day.jsonl") { document ->
            if (document == null) return@create
            stop.set(false); step = TransferStep.Exporting(0)
            scope.launch {
                val outcome = transfer.export(owners, document.write, stop::get) { step = TransferStep.Exporting(it) }
                // A document that holds no complete export is removed again, so it cannot be mistaken for one.
                if (outcome !is ExportOutcome.Done) document.delete()
                step = null
                if (outcome is ExportOutcome.Done) uiHaptic(HapticCue.Confirm)
                onNotice(exportText(outcome))
            }
        }
    }
    fun startImport() {
        documents.open { document ->
            if (document == null) return@open
            stop.set(false); adopt = emptySet(); step = TransferStep.Reading
            scope.launch {
                when (val opened = transfer.open(document.read, stop::get)) {
                    is ImportOpen.Ready -> step = TransferStep.Preview(opened.plan)
                    is ImportOpen.Rejected -> step = TransferStep.Result(string(R.string.file_not_imported), listOf(rejectText(opened.reason)))
                    ImportOpen.Cancelled -> { step = null; onNotice(string(R.string.import_cancelled_nothing_was_changed)) }
                }
            }
        }
    }
    fun import(plan: ImportPlan) {
        stop.set(false); step = TransferStep.Importing(0, plan.total)
        scope.launch {
            when (val outcome = transfer.import(plan, adopt, stop::get) { done, total -> step = TransferStep.Importing(done, total) }) {
                is ImportOutcome.Finished -> {
                    onImported(); uiHaptic(HapticCue.Confirm)
                    step = TransferStep.Result(if (outcome.report.notStored.isEmpty()) string(R.string.import_finished) else string(R.string.import_incomplete), importSummary(outcome.report))
                }
                ImportOutcome.Cancelled -> { step = null; onNotice(string(R.string.import_cancelled_nothing_was_changed)) }
                is ImportOutcome.Rejected -> step = TransferStep.Result(string(R.string.file_not_imported), listOf(rejectText(outcome.reason)))
                is ImportOutcome.Failed -> step = TransferStep.Result(string(R.string.file_not_imported), listOf(string(R.string.riff_could_not_change_its_storage_nothing)))
            }
        }
    }

    SettingsSection(string(R.string.move_to_another_phone)) {
        SettingsLinkRow(string(R.string.export_local_data), null, Icons.Rounded.FileUpload, "privacy_export") { if (step == null) startExport() }
        SettingsDivider()
        SettingsLinkRow(string(R.string.import_local_data), null, Icons.Rounded.FileDownload, "privacy_import") { if (step == null) startImport() }
        Text(string(R.string.settings_saved_tracks_searches_and_listening),
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
    }

    when (val now = step) {
        null -> Unit
        is TransferStep.ChooseExport -> FrostedAlertDialog(onDismissRequest = { step = null }, title = { Text(string(R.string.export_local_data)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                var other = 0
                now.owners.forEach { item ->
                    val name = ownerName(item.kind, item.key, if (item.kind == OwnerKind.Provider && item.key != ownerKey) ++other else 0)
                    CheckRow(name, describeCounts(item.counts), item.key in chosen, "export_owner_${item.kind.word}_${if (item.key == ownerKey || item.kind != OwnerKind.Provider) "active" else other}") {
                        chosen = if (it) chosen + item.key else chosen - item.key
                    }
                }
                Text(EXPORT_WARNING, color = White, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp).testTag("export_warning"))
            } },
            confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { uiHaptic(); export() }, modifier = Modifier.testTag("export_confirm")) {
                Text(string(R.string.choose_where_to_save), color = Pink) } },
            dismissButton = { TextButton({ step = null }, modifier = Modifier.testTag("export_cancel")) { Text(string(R.string.cancel)) } })
        is TransferStep.Exporting -> ProgressDialog(string(R.string.exporting), quantity(R.plurals.n_items_written, now.done), null) { stop.set(true) }
        TransferStep.Reading -> ProgressDialog(string(R.string.reading_the_file), string(R.string.nothing_is_changed_while_the_file_is), null) { stop.set(true) }
        is TransferStep.Preview -> {
            val plan = now.plan
            val importable = plan.sections.any { it.counts.total > 0 && (it.destination != Destination.Other || it.index in adopt) }
            FrostedAlertDialog(onDismissRequest = { close(plan) }, title = { Text(string(R.string.import_local_data_2)) },
                text = { Column(Modifier.verticalScroll(rememberScrollState()).testTag("import_preview")) {
                    if (plan.sections.isEmpty()) Text(string(R.string.this_file_holds_nothing_that_can_be), color = White, fontSize = 14.sp)
                    plan.sections.forEach { section ->
                        when (section.destination) {
                            Destination.Direct -> PlainRow(ownerName(section.kind, null, 0), describeCounts(section.counts))
                            Destination.Account -> PlainRow((accountName ?: string(R.string.this_account_2)).replaceFirstChar(Char::uppercase), describeCounts(section.counts) + ". The same account as the one signed in.")
                            Destination.Other -> if (plan.canAdopt) CheckRow(string(R.string.from_another_account), describeCounts(section.counts) + ". Add to $owner?",
                                section.index in adopt, "import_adopt_${section.index}") { adopt = if (it) adopt + section.index else adopt - section.index }
                            else PlainRow(string(R.string.from_an_account), describeCounts(section.counts) + ". Not imported: sign in to that account first, then import the file again.")
                        }
                    }
                    if (plan.malformed > 0) Text(quantity(R.plurals.n_records_in_this_file_cannot_be, plan.malformed), color = Muted, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp).testTag("import_malformed"))
                    if (plan.overLimit > 0) Text(quantity(R.plurals.n_records_are_beyond_what_one_list, plan.overLimit), color = Muted, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp))
                    Text(string(R.string.importing_adds_to_what_is_on_this),
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                } },
                confirmButton = { TextButton(enabled = importable, onClick = { uiHaptic(); if (adopt.isEmpty()) import(plan) else step = TransferStep.ConfirmAdopt(plan) },
                    modifier = Modifier.testTag("import_confirm")) { Text(string(R.string.import_label), color = Pink) } },
                dismissButton = { TextButton({ close(plan) }, modifier = Modifier.testTag("import_cancel")) { Text(string(R.string.cancel)) } })
        }
        is TransferStep.ConfirmAdopt -> FrostedAlertDialog(onDismissRequest = { step = TransferStep.Preview(now.plan) },
            title = { Text(string(R.string.add_another_account_s_data)) },
            text = { Text(string(R.string.they_are_added_to_the_data_of, owner), modifier = Modifier.testTag("import_adopt_prompt")) },
            confirmButton = { TextButton({ uiHaptic(HapticCue.Confirm); import(now.plan) }, modifier = Modifier.testTag("import_adopt_confirm")) { Text(string(R.string.add_and_import), color = Pink) } },
            dismissButton = { TextButton({ step = TransferStep.Preview(now.plan) }, modifier = Modifier.testTag("import_adopt_cancel")) { Text(string(R.string.back)) } })
        is TransferStep.Importing -> ProgressDialog(string(R.string.importing), quantity(R.plurals.n_of_n_items, now.total, now.done),
            if (now.total > 0) now.done.toFloat() / now.total else null) { stop.set(true) }
        is TransferStep.Result -> FrostedAlertDialog(onDismissRequest = { step = null }, title = { Text(now.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()).testTag("transfer_result")) {
                now.lines.forEach { Text(it, color = White, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp)) } } },
            confirmButton = { TextButton({ step = null }, modifier = Modifier.testTag("transfer_close")) { Text(string(R.string.close), color = Pink) } })
    }
}

@Composable private fun CheckRow(title: String, detail: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag).toggleable(checked, role = Role.Checkbox, onValueChange = hapticToggle(onChange)),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null)
        Column(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Text(title, color = White, fontSize = 15.sp)
            Text(detail, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable private fun PlainRow(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, color = White, fontSize = 15.sp)
        Text(detail, color = Muted, fontSize = 13.sp)
    }
}

/** Cancel asks the work to stop; the dialog closes when the work answers, so the outcome shown is the real one. */
@Composable private fun ProgressDialog(title: String, detail: String, fraction: Float?, onCancel: () -> Unit) {
    var asked by remember { mutableStateOf(false) }
    FrostedAlertDialog(onDismissRequest = {}, title = { Text(title) },
        text = { Column(Modifier.testTag("transfer_progress")) {
            Text(if (asked) string(R.string.stopping) else detail, color = White, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !asked, onClick = { asked = true; onCancel() }, modifier = Modifier.testTag("transfer_cancel")) { Text(string(R.string.cancel)) } })
}
