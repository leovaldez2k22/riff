package com.musicglass.personal

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The backup on this phone (owner's request, 20 Oct 2026). One file per owner (this phone, signed-out listening,
 * each account) in Documents, in the local data file format, so it stays when Riff is uninstalled. Android gives a
 * new install no access to files an earlier install made, so restoring takes one choice of the folder in the
 * system picker; Riff offers it on the first start of a new install. Not encrypted (owner's choice).
 *
 * The backup is a mirror: each file is rewritten whole, and a file for an owner whose data was deleted here is
 * removed. Restoring only adds, like an import. An account's file is restored when that account is signed in; until
 * then a private copy waits. Downloads' audio, sign-ins and caches are never in it.
 */
internal class LocalBackup(context: Context, private val transfer: LocalDataTransfer, private val io: CoroutineDispatcher,
    private val wallTime: () -> Long, private val event: (String) -> Unit = {},
    /** The folder Riff can write without asking: its own files in Documents. */
    private val shared: () -> BackupFolder,
    /** The folder the user chose in the picker, or null when that access is gone. */
    private val picked: (String) -> BackupFolder?,
    private val pendingDir: File = File(context.applicationContext.filesDir, "backup-pending")) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("local_backup", Context.MODE_PRIVATE)
    private val lock = Mutex()
    var enabled by mutableStateOf(prefs.getBoolean("enabled", true)); private set
    var lastBackupAt by mutableLongStateOf(prefs.getLong("last_at", 0L)); private set
    /** Sections of restored files whose account has not been signed in here yet. */
    var waiting by mutableStateOf(pendingFiles().size); private set

    private fun folder(): BackupFolder = prefs.getString("tree", null)?.let(picked) ?: shared()
    private fun pendingFiles() = pendingDir.listFiles { f -> f.name.endsWith(BACKUP_SUFFIX) }?.toList().orEmpty()

    /** Writes every owner's file. Without [force], at most once in [BACKUP_GAP_MS]. */
    suspend fun backUp(force: Boolean = false): BackupOutcome = withContext(io) {
        lock.withLock {
            if (!enabled) return@withLock BackupOutcome.Off
            if (!force && wallTime() - lastBackupAt in 0 until BACKUP_GAP_MS) return@withLock BackupOutcome.Skipped
            val folder = folder()
            val written = mutableSetOf<String>(); var failed = 0
            for (owner in transfer.inventory()) {
                // The account's opaque key, the same after a reinstall, so its file is found again.
                val name = backupFileName(owner.kind, if (owner.kind == OwnerKind.Provider) exportScopeDigest(app, owner.key) else null)
                val outcome = transfer.export(setOf(owner.key), { folder.output(name) })
                if (outcome is ExportOutcome.Done) written += name else failed++
            }
            // A mirror: what this install wrote for an owner whose data is gone is removed. A file waiting for its
            // account, or one this install never wrote, is left alone.
            val waitingNames = pendingFiles().mapTo(HashSet()) { it.name }
            val before = prefs.getStringSet("written", emptySet()).orEmpty()
            (before - written - waitingNames).forEach { runCatching { folder.delete(it) } }
            val now = wallTime()
            prefs.edit { putStringSet("written", written); if (failed == 0) putLong("last_at", now) }
            if (failed == 0) lastBackupAt = now
            event("local_backup action=write files=${written.size} failed=$failed")
            if (failed == 0) BackupOutcome.Done(written.size) else BackupOutcome.Failed(failed)
        }
    }

    /**
     * Restores from [folder]: for each owner the newest file. This phone's and signed-out listening's sections are
     * added now, an account's when it is the account signed in; another account's file waits for that account.
     */
    suspend fun restore(folder: BackupFolder, tree: String? = null): RestoreOutcome = withContext(io) {
        lock.withLock {
            if (tree != null) prefs.edit { putString("tree", tree) }
            prefs.edit { putBoolean("restore_offered", true) }
            val files = folder.list().filterKeys { backupBaseName(it) != null }
            if (files.isEmpty()) return@withLock RestoreOutcome(found = 0)
            var restored = 0; var waitingNow = 0; var unreadable = 0; var added = SectionCounts()
            // Two files of one owner (Android numbers a name it cannot reuse): the newest is used. The transfer
            // stages one file at a time, so each is read for its date first and opened again when it is used.
            val dated = files.mapNotNull { (name, open) ->
                when (val opened = transfer.open(open)) {
                    is ImportOpen.Ready -> Triple(backupBaseName(name)!!, opened.plan.exportedAt, name).also { transfer.discard(opened.plan) }
                    else -> { unreadable++; null }
                }
            }
            val newest = dated.groupBy { it.first }.mapValues { (_, all) -> all.maxBy { it.second }.third }
            for ((base, name) in newest) {
                val plan = (transfer.open(files.getValue(name)) as? ImportOpen.Ready)?.plan ?: run { unreadable++; null } ?: continue
                if (plan.sections.any { it.destination == Destination.Other }) {
                    pendingDir.mkdirs(); plan.file.copyTo(File(pendingDir, base), overwrite = true)
                    transfer.discard(plan); waitingNow++; continue
                }
                when (val outcome = transfer.import(plan)) {
                    is ImportOutcome.Finished -> { restored++; added += outcome.report.added }
                    else -> unreadable++
                }
            }
            waiting = pendingFiles().size
            event("local_backup action=restore found=${files.size} restored=$restored waiting=$waitingNow failed=$unreadable")
            RestoreOutcome(files.size, restored, waitingNow, unreadable, added)
        }
    }

    /** Called when the signed-in account changes: a waiting file of that account is restored now. */
    suspend fun restoreWaiting(): Int = withContext(io) {
        lock.withLock {
            var restored = 0
            for (file in pendingFiles()) {
                val opened = transfer.open({ file.inputStream() }) as? ImportOpen.Ready ?: continue
                if (opened.plan.sections.any { it.destination == Destination.Other }) { transfer.discard(opened.plan); continue }
                if (transfer.import(opened.plan) is ImportOutcome.Finished) { file.delete(); restored++ }
            }
            waiting = pendingFiles().size
            if (restored > 0) event("local_backup action=restore_waiting restored=$restored")
            restored
        }
    }

    /** True once, on the first start of an install that holds no data yet: the moment to offer a restore. */
    suspend fun shouldOfferRestore(): Boolean = withContext(io) {
        if (!enabled || prefs.getBoolean("restore_offered", false)) return@withContext false
        val empty = transfer.inventory().sumOf { it.counts.total } == 0
        if (!empty) prefs.edit { putBoolean("restore_offered", true) }
        empty
    }
    fun restoreDeclined() = prefs.edit { putBoolean("restore_offered", true) }

    /** Off removes the files this install wrote, so nothing it no longer keeps up to date is left behind. */
    suspend fun setEnabled(on: Boolean): Int = withContext(io) {
        lock.withLock {
            prefs.edit { putBoolean("enabled", on) }; enabled = on
            if (on) return@withLock 0
            val folder = folder(); val names = prefs.getStringSet("written", emptySet()).orEmpty()
            val removed = names.count { runCatching { folder.delete(it) }.isSuccess }
            prefs.edit { remove("written"); remove("last_at") }; lastBackupAt = 0L
            removed
        }
    }

    /** Where the files are, in the words the phone's Files app uses. */
    val place: String get() = "Documents/${backupFolderName(app.packageName)}"
}

internal sealed interface BackupOutcome {
    data class Done(val files: Int) : BackupOutcome
    data class Failed(val files: Int) : BackupOutcome
    data object Skipped : BackupOutcome
    data object Off : BackupOutcome
}

internal data class RestoreOutcome(val found: Int, val restored: Int = 0, val waiting: Int = 0, val failed: Int = 0,
    val added: SectionCounts = SectionCounts())

/** Changes are backed up when Riff leaves the screen, at most this often; a deletion is backed up at once. */
internal const val BACKUP_GAP_MS = 10 * 60_000L
internal const val BACKUP_SUFFIX = ".jsonl"
private const val BACKUP_PREFIX = "riff-backup-"
private const val BACKUP_MIME = "application/octet-stream"

/** riff-backup-phone, riff-backup-signed-out, riff-backup-account-<16 hex of the account's opaque key>. */
internal fun backupFileName(kind: OwnerKind, accountDigest: String?): String = BACKUP_PREFIX + when (kind) {
    OwnerKind.Device -> "phone"
    OwnerKind.Guest -> "signed-out"
    OwnerKind.Provider -> "account-" + accountDigest.orEmpty().removePrefix("s2_").filter(Char::isLetterOrDigit).take(16)
} + BACKUP_SUFFIX

/** The name without the " (1)" Android adds when it cannot reuse one; null for a file that is not a backup. */
internal fun backupBaseName(name: String): String? {
    val base = name.replace(Regex(""" \(\d+\)(?=\.jsonl$)"""), "")
    return base.takeIf { it.startsWith(BACKUP_PREFIX) && it.endsWith(BACKUP_SUFFIX) && '/' !in it }
}

/** Each build keeps its own folder, so Riff Preview never writes over Riff's backup. */
internal fun backupFolderName(packageName: String): String =
    packageName.removePrefix("com.musicglass.personal").trimStart('.').let { if (it.isEmpty()) "Riff Backup" else "Riff Backup ($it)" }

/** A folder of backup files: their names, and a way to read, write and remove each. */
internal interface BackupFolder {
    fun list(): Map<String, () -> InputStream>
    fun output(name: String): OutputStream
    fun delete(name: String)
}

/** Riff's own files in Documents/<folder>. A new install cannot see an earlier install's files here. */
internal class MediaStoreBackupFolder(private val resolver: ContentResolver, folderName: String) : BackupFolder {
    private val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val path = "Documents/$folderName/"
    private fun uris(): Map<String, Uri> = buildMap {
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(path), null)?.use { c ->
            while (c.moveToNext()) put(c.getString(1), ContentUris.withAppendedId(collection, c.getLong(0)))
        }
    }
    override fun list() = uris().mapValues { (_, uri) -> { resolver.openInputStream(uri) ?: throw IOException("unavailable") } }
    override fun output(name: String): OutputStream {
        val uri = uris()[name] ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, BACKUP_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
        }) ?: throw IOException("not created")
        return resolver.openOutputStream(uri, "wt") ?: throw IOException("unavailable")
    }
    override fun delete(name: String) { uris()[name]?.let { resolver.delete(it, null, null) } }
}

/** A folder the user chose in the system picker; Riff keeps its permission until it is uninstalled. */
internal class PickedBackupFolder(private val resolver: ContentResolver, private val tree: Uri) : BackupFolder {
    private val parent get() = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private fun uris(): Map<String, Uri> = buildMap {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { c -> while (c.moveToNext()) put(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))) }
    }
    override fun list() = uris().mapValues { (_, uri) -> { resolver.openInputStream(uri) ?: throw IOException("unavailable") } }
    override fun output(name: String): OutputStream {
        val uri = uris()[name] ?: DocumentsContract.createDocument(resolver, parent, BACKUP_MIME, name) ?: throw IOException("not created")
        return resolver.openOutputStream(uri, "wt") ?: throw IOException("unavailable")
    }
    override fun delete(name: String) { uris()[name]?.let { DocumentsContract.deleteDocument(resolver, it) } }
    companion object {
        /** The picked folder while Riff still holds write access to it. */
        fun reopen(resolver: ContentResolver, tree: String): PickedBackupFolder? {
            val uri = Uri.parse(tree)
            return if (resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) PickedBackupFolder(resolver, uri) else null
        }
        /** Where the picker opens: Documents/<folder> on the phone's own storage. */
        fun initialUri(folderName: String): Uri =
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/$folderName")
    }
}
