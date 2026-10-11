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

internal const val EXPORT_WARNING = "The file is not encrypted. It contains your listening history and searches, and anyone who has the file can read it. " +
    "It holds no passwords or sign-in data, and no audio."

/** What a section holds, in the user's words. */
internal fun describeCounts(c: SectionCounts): String = listOfNotNull(
    c.settings.takeIf { it > 0 }?.let { plural(it, "setting") }, c.hidden.takeIf { it > 0 }?.let { plural(it, "hidden item") },
    c.saved.takeIf { it > 0 }?.let { plural(it, "saved track") }, c.recents.takeIf { it > 0 }?.let { "$it recently played" },
    c.searches.takeIf { it > 0 }?.let { plural(it, "search", "searches") }, c.plays.takeIf { it > 0 }?.let { plural(it, "play") },
    c.downloads.takeIf { it > 0 }?.let { plural(it, "download name") }).joinToString(", ").ifEmpty { "Nothing" }

internal fun rejectText(reason: RejectReason): String = when (reason) {
    RejectReason.NotRiff -> "This is not a Riff data file."
    RejectReason.Newer -> "This file was made by a newer version of Riff. Update Riff to import it."
    RejectReason.Truncated -> "This file is incomplete or damaged."
    RejectReason.TooLarge -> "This file is larger than 128 MB and was not read."
    RejectReason.Unreadable -> "Riff could not read this file."
    RejectReason.Storage -> "There is not enough free space on this phone to read the file."
} + " Nothing was changed."

internal fun exportText(outcome: ExportOutcome): String = when (outcome) {
    is ExportOutcome.Done -> "${plural(outcome.records, "item")} exported"
    ExportOutcome.Cancelled -> "Export cancelled. No file was written."
    ExportOutcome.TooLarge -> "The file would be larger than 128 MB. Choose less to export. No file was written."
    is ExportOutcome.Failed -> "Could not export. No usable file was written."
}

/** Every line of the summary shown after an import. Nothing that was skipped or failed is left out. */
internal fun importSummary(report: ImportReport): List<String> = buildList {
    add(if (report.added.total == 0) "Nothing new was added." else "Added: ${describeCounts(report.added)}.")
    if (report.present > 0) add("${plural(report.present, "item")} already on this phone ${if (report.present == 1) "was" else "were"} left as ${if (report.present == 1) "it is" else "they are"}.")
    if (report.malformed > 0) add("${plural(report.malformed, "record")} could not be read and ${if (report.malformed == 1) "was" else "were"} skipped.")
    if (report.overLimit > 0) add("${plural(report.overLimit, "item")} did not fit: a list here was full.")
    if (report.expired > 0) add("${plural(report.expired, "play")} older than your history period ${if (report.expired == 1) "was" else "were"} not added.")
    if (report.settingsKept > 0) add("The history period was kept as it is, so that no plays are removed.")
    if (report.sectionsLeft > 0) add("${plural(report.sectionsLeft, "account section")} ${if (report.sectionsLeft == 1) "was" else "were"} not imported.")
    if (report.added.downloads > 0) add("Downloads are recorded by name only. Nothing was downloaded.")
    if (report.notStored.isNotEmpty()) add("Could not be stored: ${report.notStored.joinToString(", ")}. Import the file again to retry.")
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
        OwnerKind.Device -> "This phone"
        OwnerKind.Guest -> "Signed-out listening"
        OwnerKind.Provider -> if (key == ownerKey && accountName != null) accountName.replaceFirstChar(Char::uppercase) else "Another account used on this phone ($position)"
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
                    is ImportOpen.Rejected -> step = TransferStep.Result("File not imported", listOf(rejectText(opened.reason)))
                    ImportOpen.Cancelled -> { step = null; onNotice("Import cancelled. Nothing was changed.") }
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
                    step = TransferStep.Result(if (outcome.report.notStored.isEmpty()) "Import finished" else "Import incomplete", importSummary(outcome.report))
                }
                ImportOutcome.Cancelled -> { step = null; onNotice("Import cancelled. Nothing was changed.") }
                is ImportOutcome.Rejected -> step = TransferStep.Result("File not imported", listOf(rejectText(outcome.reason)))
                is ImportOutcome.Failed -> step = TransferStep.Result("File not imported", listOf("Riff could not change its storage. Nothing was changed."))
            }
        }
    }

    SettingsSection("Move to another phone") {
        SettingsLinkRow("Export local data", null, Icons.Rounded.FileUpload, "privacy_export") { if (step == null) startExport() }
        SettingsDivider()
        SettingsLinkRow("Import local data", null, Icons.Rounded.FileDownload, "privacy_import") { if (step == null) startImport() }
        Text("Settings, saved tracks, searches and listening history, as one file you choose the place of. Nothing is sent to YouTube Music.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
    }

    when (val now = step) {
        null -> Unit
        is TransferStep.ChooseExport -> FrostedAlertDialog(onDismissRequest = { step = null }, title = { Text("Export local data") },
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
                Text("Choose where to save", color = Pink) } },
            dismissButton = { TextButton({ step = null }, modifier = Modifier.testTag("export_cancel")) { Text("Cancel") } })
        is TransferStep.Exporting -> ProgressDialog("Exporting", "${plural(now.done, "item")} written", null) { stop.set(true) }
        TransferStep.Reading -> ProgressDialog("Reading the file", "Nothing is changed while the file is read.", null) { stop.set(true) }
        is TransferStep.Preview -> {
            val plan = now.plan
            val importable = plan.sections.any { it.counts.total > 0 && (it.destination != Destination.Other || it.index in adopt) }
            FrostedAlertDialog(onDismissRequest = { close(plan) }, title = { Text("Import local data?") },
                text = { Column(Modifier.verticalScroll(rememberScrollState()).testTag("import_preview")) {
                    if (plan.sections.isEmpty()) Text("This file holds nothing that can be imported.", color = White, fontSize = 14.sp)
                    plan.sections.forEach { section ->
                        when (section.destination) {
                            Destination.Direct -> PlainRow(ownerName(section.kind, null, 0), describeCounts(section.counts))
                            Destination.Account -> PlainRow((accountName ?: "This account").replaceFirstChar(Char::uppercase), describeCounts(section.counts) + ". The same account as the one signed in.")
                            Destination.Other -> if (plan.canAdopt) CheckRow("From another account", describeCounts(section.counts) + ". Add to $owner?",
                                section.index in adopt, "import_adopt_${section.index}") { adopt = if (it) adopt + section.index else adopt - section.index }
                            else PlainRow("From an account", describeCounts(section.counts) + ". Not imported: sign in to that account first, then import the file again.")
                        }
                    }
                    if (plan.malformed > 0) Text("${plural(plan.malformed, "record")} in this file cannot be read and will be skipped.", color = Muted, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp).testTag("import_malformed"))
                    if (plan.overLimit > 0) Text("${plural(plan.overLimit, "record")} are beyond what one list may hold and will be skipped.", color = Muted, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp))
                    Text("Importing adds to what is on this phone and removes nothing. Nothing is played or downloaded, and nothing changes in YouTube Music.",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                } },
                confirmButton = { TextButton(enabled = importable, onClick = { uiHaptic(); if (adopt.isEmpty()) import(plan) else step = TransferStep.ConfirmAdopt(plan) },
                    modifier = Modifier.testTag("import_confirm")) { Text("Import", color = Pink) } },
                dismissButton = { TextButton({ close(plan) }, modifier = Modifier.testTag("import_cancel")) { Text("Cancel") } })
        }
        is TransferStep.ConfirmAdopt -> FrostedAlertDialog(onDismissRequest = { step = TransferStep.Preview(now.plan) },
            title = { Text("Add another account's data?") },
            text = { Text("They are added to the data of $owner on this phone. This part of the file comes from another account, so its plays, " +
                "searches and saved tracks are included, and they cannot be separated afterwards.", modifier = Modifier.testTag("import_adopt_prompt")) },
            confirmButton = { TextButton({ uiHaptic(HapticCue.Confirm); import(now.plan) }, modifier = Modifier.testTag("import_adopt_confirm")) { Text("Add and import", color = Pink) } },
            dismissButton = { TextButton({ step = TransferStep.Preview(now.plan) }, modifier = Modifier.testTag("import_adopt_cancel")) { Text("Back") } })
        is TransferStep.Importing -> ProgressDialog("Importing", "${now.done} of ${plural(now.total, "item")}",
            if (now.total > 0) now.done.toFloat() / now.total else null) { stop.set(true) }
        is TransferStep.Result -> FrostedAlertDialog(onDismissRequest = { step = null }, title = { Text(now.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()).testTag("transfer_result")) {
                now.lines.forEach { Text(it, color = White, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp)) } } },
            confirmButton = { TextButton({ step = null }, modifier = Modifier.testTag("transfer_close")) { Text("Close", color = Pink) } })
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
            Text(if (asked) "Stopping…" else detail, color = White, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !asked, onClick = { asked = true; onCancel() }, modifier = Modifier.testTag("transfer_cancel")) { Text("Cancel") } })
}
