package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** The backup on this phone (owner's request, 20 Oct 2026): synthetic owners and songs, a folder on disk. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalBackupTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock()
    private val accountA = "s2_" + "a".repeat(64)
    private val accountB = "s2_" + "b".repeat(64)
    private val store = OfflineMusicStore::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
    private val opened = mutableListOf<LibraryStateDb>()
    private lateinit var documents: File
    private val prefsFiles = listOf("music_collections", "search_history", "not_interested", "accessibility_display", "speed_dial_pins",
        "local_backup", "offline_settings", "account_scopes", AUDIO_BOOST_PREFS, CROSSFADE_PREFS, DATA_SAVER_PREFS)
    @Before fun folder() { documents = File(context.cacheDir, "documents-${UUID.randomUUID()}").apply { mkdirs() } }
    @After fun close() { opened.forEach { it.close() }; documents.deleteRecursively() }

    /** Documents/Riff Backup as a plain folder: it outlives the "install" that wrote it. */
    private class DiskFolder(private val dir: File) : BackupFolder {
        override fun list() = dir.listFiles().orEmpty().associate { f -> f.name to { f.inputStream() as InputStream } }
        override fun output(name: String): OutputStream = File(dir, name).outputStream()
        override fun delete(name: String) { File(dir, name).delete() }
        fun names() = dir.list().orEmpty().sorted()
    }

    /** One install of Riff. A new one after [uninstall] has nothing but what the folder kept. */
    private inner class Install(var current: CurrentOwner = CurrentOwner(accountA, setOf(accountA))) {
        val db = LibraryStateDb(context, "state-${UUID.randomUUID()}.db").also { opened += it }
        val history = HistoryRepository(db, ScopeEpochs(db).also { it.warm() }, clock::wallTimeMillis, HISTORY_ROW_CAP) { historyRetentionDays(context) }
        val hidden = NotInterestedStore(context)
        val pins = SpeedDialPins(context)
        val transfer = LocalDataTransfer(context, db, history, { store }, { SearchHistory(context, Dispatchers.Unconfined) },
            { hidden }, { current }, Dispatchers.Unconfined, clock::wallTimeMillis, Mutex()) { pins }
        val folder = DiskFolder(documents)
        val backup = LocalBackup(context, transfer, Dispatchers.Unconfined, clock::wallTimeMillis, shared = { folder }, picked = { null },
            pendingDir = File(context.filesDir, "backup-pending-test"))
        private var serial = 0
        fun play(owner: String, n: Int) = assertEquals(StoreOutcome.Done, history.record(HistoryEntry(if (owner == DEVICE_LOCAL_SCOPE_KEY) "local:$n" else "yt:fixture%04d".format(n), "Song $n", "Artist",
            null, clock.wall - 1000L * ++serial, eventId = "e-${owner.hashCode().toUInt()}-$n", durationMs = 200_000), history.ticket(owner)))
        fun plays(owner: String) = history.page(owner, null).entries.map { it.title }.toSet()
    }
    private fun uninstall() {
        prefsFiles.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        store.scopes().forEach { store.deleteScope(it) }
        File(context.filesDir, "backup-pending-test").deleteRecursively()
    }
    private fun digest16(key: String) = key.removePrefix("s2_").take(16)

    @Test fun eachOwnerGetsAFileAndANewInstallGetsItAllBackWithOneAccountWaitingForItsSignIn() = runBlocking {
        val first = Install()
        context.getSharedPreferences("accessibility_display", Context.MODE_PRIVATE).edit().putBoolean("reduce_motion", true).commit()
        first.hidden.add(NotInterestedEntry("fixture0031", "Hidden"))
        first.pins.pin(ProviderHomeCard("Pinned", "Show", null, browseId = "MPSPPLtest", kind = CatalogKind.Podcast))
        first.play(DEVICE_LOCAL_SCOPE_KEY, 1); first.play(accountA, 2); first.play(accountB, 3)
        assertEquals(BackupOutcome.Done(3), first.backup.backUp(force = true))
        assertEquals(listOf("riff-backup-account-${digest16(accountA)}.jsonl", "riff-backup-account-${digest16(accountB)}.jsonl", "riff-backup-phone.jsonl"),
            first.folder.names())

        uninstall()
        val second = Install()
        assertTrue("a new install with nothing in it offers the restore", second.backup.shouldOfferRestore())
        val outcome = second.backup.restore(second.folder)
        assertEquals(3, outcome.found); assertEquals(2, outcome.restored); assertEquals(1, outcome.waiting)
        assertEquals(setOf("Song 1"), second.plays(DEVICE_LOCAL_SCOPE_KEY)); assertEquals(setOf("Song 2"), second.plays(accountA))
        assertTrue(second.plays(accountB).isEmpty())
        assertTrue(context.getSharedPreferences("accessibility_display", Context.MODE_PRIVATE).getBoolean("reduce_motion", false))
        assertEquals(listOf("fixture0031"), second.hidden.entries.map { it.key }); assertEquals(listOf("MPSPPLtest"), second.pins.cards.map { it.key() })
        assertFalse("offered once", second.backup.shouldOfferRestore())

        assertEquals("not this account", 0, second.backup.restoreWaiting()); assertEquals(1, second.backup.waiting)
        second.current = CurrentOwner(accountB, setOf(accountB))
        assertEquals(1, second.backup.restoreWaiting()); assertEquals(setOf("Song 3"), second.plays(accountB)); assertEquals(0, second.backup.waiting)
    }

    @Test fun theBackupIsAMirrorButAFileWaitingForItsAccountIsKept() = runBlocking {
        val install = Install()
        install.play(accountA, 1); install.play(accountB, 2)
        install.backup.backUp(force = true)
        install.history.clearCounted(accountB)
        assertEquals(BackupOutcome.Done(2), install.backup.backUp(force = true))
        assertFalse("deleted here, so gone from the backup", "riff-backup-account-${digest16(accountB)}.jsonl" in install.folder.names())

        install.play(accountB, 3); install.backup.backUp(force = true)
        uninstall()
        val again = Install()
        again.backup.restore(again.folder)
        // B has not signed in on this install yet: its data is not here, and its file must survive the next backup.
        again.backup.backUp(force = true)
        assertTrue("riff-backup-account-${digest16(accountB)}.jsonl" in again.folder.names())
    }

    @Test fun ofTwoFilesForOneOwnerTheNewestIsRestored() = runBlocking {
        val old = Install(); old.play(DEVICE_LOCAL_SCOPE_KEY, 1); old.backup.backUp(force = true)
        File(documents, "riff-backup-phone.jsonl").renameTo(File(documents, "riff-backup-phone (1).jsonl"))
        clock.advance(60_000); old.play(DEVICE_LOCAL_SCOPE_KEY, 2); old.backup.backUp(force = true)
        File(documents, "notes.txt").writeText("not a backup")
        uninstall()
        val fresh = Install()
        assertEquals(2, fresh.backup.restore(fresh.folder).found)
        assertEquals(setOf("Song 1", "Song 2"), fresh.plays(DEVICE_LOCAL_SCOPE_KEY))
    }

    @Test fun changesWaitBetweenBackupsAndTurningItOffRemovesItsFiles() = runBlocking {
        val install = Install(); install.play(accountA, 1)
        assertEquals(BackupOutcome.Done(2), install.backup.backUp())
        assertEquals(BackupOutcome.Skipped, install.backup.backUp())
        clock.advance(BACKUP_GAP_MS); assertEquals(BackupOutcome.Done(2), install.backup.backUp())
        File(documents, "someone-elses.txt").writeText("kept")
        assertEquals(2, install.backup.turnBackup(false))
        assertEquals(listOf("someone-elses.txt"), install.folder.names()); assertEquals(BackupOutcome.Off, install.backup.backUp(force = true))
    }

    @Test fun anInstallWithDataIsNotOfferedARestore() = runBlocking {
        val install = Install(); install.play(DEVICE_LOCAL_SCOPE_KEY, 1)
        assertFalse(install.backup.shouldOfferRestore())
        uninstall(); val empty = Install(); empty.backup.restoreDeclined(); assertFalse(empty.backup.shouldOfferRestore())
    }

    @Test fun namesAndFolders() {
        assertEquals("riff-backup-phone.jsonl", backupFileName(OwnerKind.Device, null))
        assertEquals("riff-backup-signed-out.jsonl", backupFileName(OwnerKind.Guest, null))
        assertEquals("riff-backup-account-aaaaaaaaaaaaaaaa.jsonl", backupFileName(OwnerKind.Provider, accountA))
        assertEquals("riff-backup-phone.jsonl", backupBaseName("riff-backup-phone (12).jsonl"))
        assertNull(backupBaseName("riff-local-data-2026-10-20.jsonl")); assertNull(backupBaseName("riff-backup-phone.txt"))
        assertEquals("Riff Backup", backupFolderName("com.musicglass.personal"))
        assertEquals("Riff Backup (preview)", backupFolderName("com.musicglass.personal.preview"))
    }
}
