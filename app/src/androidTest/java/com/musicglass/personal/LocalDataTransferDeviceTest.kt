package com.musicglass.personal

import android.content.Context
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Export and import of local data against the app's real stores (Q05A: EXP01, EXP02). Emulator
 * only, because it deletes an owner's data to stand for a clean install: identities and songs are
 * synthetic and nothing here reaches a provider. The system picker is not driven; the file is
 * written to and read from the app's own cache folder.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class LocalDataTransferDeviceTest {
    private val device=PlaybackDeviceHarness()
    private val target get()=device.target
    private val process get()=device.process
    private val ownerA by lazy {providerScope(verifiedAccountIdentity("fixture-sync-A")!!).opaqueId}
    private val ownerB by lazy {providerScope(verifiedAccountIdentity("fixture-sync-B")!!).opaqueId}
    private val owners by lazy {listOf(ownerA,ownerB,GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY)}
    private val collections get()=target.getSharedPreferences("music_collections",Context.MODE_PRIVATE)
    private val file by lazy {File(target.cacheDir,"q05a-export.jsonl")}

    @Before fun connect() {
        device.requireDisposableInstall()
        device.connect();device.signedOut();device.emptyPlayer()
        clean()
    }
    @After fun disconnect() {
        if(!device.connected) return
        device.signedOut()
        device.onMain {device.controller.release()}
        clean()
    }
    private fun clean() {
        file.delete()
        owners.forEach {process.history.clear(it);process.snapshots.clear(it)}
        listOf(ownerA,ownerB).forEach {owner->
            runBlocking {process.localData.clearSearches(owner)}
            clearCollectionScope(target,owner);OfflineMusicStore.get(target).deleteScope(owner)
        }
        runBlocking {process.localData.clearSearches(GUEST_SCOPE_KEY)}
    }
    private fun become(sync:String) = runBlocking {withContext(Dispatchers.Main) {
        process.sessions.transition("fixture_switch",providerScope(verifiedAccountIdentity(sync)!!)) {
            target.getSharedPreferences("provider_session",Context.MODE_PRIVATE).edit().clear().putString("sync",sync).putString("account","0").commit()
            CookieManager.getInstance().setCookie("https://music.youtube.com","SAPISID=fixture")
            device.provider.refreshAccount()
        }
    }}
    private var serial=0
    private fun play(owner:String) {
        val id=if(owner==DEVICE_LOCAL_SCOPE_KEY) "local:7" else "yt:fixtureT001"
        assertEquals(StoreOutcome.Done,process.history.record(HistoryEntry(id,"Fixture song","Fixture artist",null,System.currentTimeMillis()-serial*1000L,
            eventId="q05a-${serial++}",durationMs=180_000),process.history.ticket(owner)))
    }
    private fun seed(owner:String) {
        repeat(3) {play(owner)}
        collections.edit().putString("account_${owner}_online_favorites",JSONArray(listOf(JSONObject().put("id","fixtureT002").put("title","Saved song")
            .put("artist","Fixture artist").put("artwork",providerArtworkUrl("fixtureT002")))).toString()).commit()
        runBlocking {SearchHistory.get(target).importEntries(mapOf(owner to listOf("fixture search one","fixture search two")))}
        OfflineMusicStore.get(target).enqueue(ProviderTrack("fixtureT003","Downloaded song","Fixture artist"),scope=owner)
    }
    private data class Kept(val plays:List<String>,val favorites:List<String>,val searches:List<String>,val downloads:Set<String>)
    private fun kept(owner:String)=Kept(process.history.page(owner).entries.map {it.eventId},storedCollection(target,owner,"online_favorites").map {it.videoId},
        runBlocking {SearchHistory.get(target).stored(owner)},OfflineMusicStore.get(target).rows(owner).mapTo(mutableSetOf()) {it.track.videoId})
    private fun export(vararg from:String) {
        val outcome=runBlocking {process.localTransfer.export(from.toSet(),{file.outputStream()})}
        assertTrue(outcome.toString(),outcome is ExportOutcome.Done)
    }
    private fun open(bytes:ByteArray=file.readBytes())=runBlocking {process.localTransfer.open({bytes.inputStream()})}

    @Test fun exportThenImportAfterTheDataIsGoneRestoresItAndStartsNothing() {
        assertTrue(become("fixture-sync-A") is SessionTransition.Committed)
        assertEquals(ownerA,device.provider.storageScopeKey())
        seed(ownerA);repeat(2) {play(GUEST_SCOPE_KEY)};play(DEVICE_LOCAL_SCOPE_KEY)
        val before=listOf(ownerA,GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY).associateWith(::kept)
        export(DEVICE_LOCAL_SCOPE_KEY,GUEST_SCOPE_KEY,ownerA)

        // Nothing that identifies or authorises the session, and no address but an image host's.
        val text=file.readText()
        listOf("fixture-sync","SAPISID","cookie","token","googlevideo","videoplayback","content:","expire=").forEach {
            assertFalse("the file must not contain $it",text.contains(it,ignoreCase=true))
        }
        assertTrue(text.lineSequence().first().startsWith("{\"riff\":\"local-data\",\"schema\":1,"))

        // Stands for a clean install: the owner's data is deleted, then the file is imported.
        assertTrue(runBlocking {process.localData.deleteAccountData(ownerA) {}} is DataActionResult.Done)
        process.history.clear(GUEST_SCOPE_KEY);process.history.clear(DEVICE_LOCAL_SCOPE_KEY)
        assertEquals(Kept(emptyList(),emptyList(),emptyList(),emptySet()),kept(ownerA))

        val plan=(open() as ImportOpen.Ready).plan
        assertEquals(listOf(Destination.Direct,Destination.Direct,Destination.Account),plan.sections.map {it.destination})
        val report=(runBlocking {process.localTransfer.import(plan)} as ImportOutcome.Finished).report
        assertTrue(report.toString(),report.notStored.isEmpty() && report.malformed==0 && report.sectionsLeft==0)
        assertEquals(SectionCounts(saved=1,searches=2,plays=6,downloads=1),report.added.copy(settings=0,hidden=0))
        assertEquals(before,listOf(ownerA,GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY).associateWith(::kept))

        // The download came back as a name only, and the import started nothing.
        val row=OfflineMusicStore.get(target).song(ownerA,"fixtureT003")!!
        assertEquals("metadata",row.state);assertEquals(0,OfflineMusicStore.get(target).downloadCount(ownerA))
        Thread.sleep(2_000)
        assertTrue(device.ids().isEmpty());assertFalse(device.onMain {device.controller.isPlaying})
        assertNull(process.snapshots.load(ownerA))
        assertFalse(File(target.cacheDir,"local-data-transfer").listFiles().orEmpty().isNotEmpty())

        // The same file again adds nothing.
        val again=(runBlocking {process.localTransfer.import((open() as ImportOpen.Ready).plan)} as ImportOutcome.Finished).report
        assertEquals(0,again.added.copy(settings=0).total)
        assertEquals(before,listOf(ownerA,GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY).associateWith(::kept))
    }

    @Test fun anotherAccountsFileIsNotAdoptedAndDamagedFilesChangeNothing() {
        assertTrue(become("fixture-sync-A") is SessionTransition.Committed)
        seed(ownerA)
        export(ownerA)
        val good=file.readBytes()
        assertTrue(become("fixture-sync-B") is SessionTransition.Committed)
        assertEquals(ownerB,device.provider.storageScopeKey())
        play(ownerB)
        val beforeA=kept(ownerA);val beforeB=kept(ownerB)

        // The other account's section is offered, never taken.
        val plan=(open(good) as ImportOpen.Ready).plan
        assertEquals(Destination.Other,plan.sections.single().destination);assertTrue(plan.canAdopt)
        val report=(runBlocking {process.localTransfer.import(plan)} as ImportOutcome.Finished).report
        assertEquals(ImportReport(sectionsLeft=1),report)
        assertEquals(beforeB,kept(ownerB));assertEquals(beforeA,kept(ownerA))

        // Refused whole: cut short, a newer schema, not a Riff file.
        fun refused(bytes:ByteArray)=(open(bytes) as ImportOpen.Rejected).reason
        assertEquals(RejectReason.Truncated,refused(good.copyOf(good.size*2/3)))
        assertEquals(RejectReason.Newer,refused(String(good).replaceFirst("\"schema\":1","\"schema\":2").toByteArray()))
        assertEquals(RejectReason.NotRiff,refused(ByteArray(4_096) {(it*7).toByte()}))
        // A damaged record is passed over; the rest of the file is still readable.
        val damaged=String(good).lines().toMutableList().also {it[2]=it[2].dropLast(9)}.joinToString("\n").toByteArray()
        val careful=(open(damaged) as ImportOpen.Ready).plan
        assertEquals(1,careful.malformed)
        runBlocking {process.localTransfer.discard(careful)}
        assertEquals(beforeB,kept(ownerB));assertEquals(beforeA,kept(ownerA))

        // On the user's word it is added to the account signed in now, and the account's own play stays.
        val adopted=(runBlocking {process.localTransfer.import((open(good) as ImportOpen.Ready).plan,adopt=setOf(0))} as ImportOutcome.Finished).report
        assertEquals(SectionCounts(saved=1,searches=2,plays=3,downloads=1),adopted.added)
        assertEquals((beforeA.plays+beforeB.plays).toSet(),kept(ownerB).plays.toSet())
        assertEquals(beforeA,kept(ownerA))
        assertTrue(device.ids().isEmpty())
    }
}
