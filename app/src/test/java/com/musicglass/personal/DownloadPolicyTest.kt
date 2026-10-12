package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Q09: the state model, the storage and network rules, and what a download's folder must look like. No Android. */
class DownloadPolicyTest {
    @get:Rule val folder=TemporaryFolder()
    private val gb=1024L*1024*1024
    private val mb=1024L*1024

    // ---- states

    @Test fun everyStateRoundTripsAndTheOldTransferStateIsStillRead() {
        for(state in DownloadState.entries) assertEquals(state,DownloadState.decode(state.db))
        assertEquals(listOf("metadata","queued","resolving","transferring","validating","assets","complete","paused","failed","cancelled"),DownloadState.entries.map {it.db})
        assertEquals("What builds before Q09 wrote",DownloadState.Transferring,DownloadState.decode("downloading"))
    }
    @Test fun aStateNoBuildWritesIsNotGuessedAt() {
        for(text in listOf(null,"","COMPLETE","done","complete ","1","queued;drop")) assertNull("'$text'",DownloadState.decode(text))
    }
    @Test fun onlyATransferInFlightHoldsARow() {
        assertEquals(setOf(DownloadState.Resolving,DownloadState.Transferring,DownloadState.Validating,DownloadState.Assets),DownloadState.entries.filter {it.inFlight}.toSet())
        assertEquals(setOf(DownloadState.Queued,DownloadState.Resolving,DownloadState.Transferring,DownloadState.Validating,DownloadState.Assets),DownloadState.entries.filter {it.pending}.toSet())
    }
    private fun song(state:String,bytes:Long=0,total:Long=0)=OfflineSong("s",ProviderTrack("fixtureD001","Song","Artist"),state,true,bytes,total,"",0,"","pending","pending",0,0,0)
    @Test fun labelsSayWhatIsHappeningAndWhetherATransferStartsAgain() {
        assertEquals("Waiting to download",downloadStatus(song("queued"),false))
        assertEquals("Preparing download",downloadStatus(song("resolving"),false))
        assertEquals("Downloading 25%",downloadStatus(song("transferring",25,100),false))
        assertEquals("Downloading 25%",downloadStatus(song("downloading",25,100),false))
        assertEquals("Downloading…",downloadStatus(song("transferring"),false))
        assertEquals("Checking audio",downloadStatus(song("validating"),false))
        assertEquals("Saving artwork and lyrics",downloadStatus(song("assets"),true))
        assertEquals("Paused · starts again from the beginning",downloadStatus(song("paused"),false))
        assertEquals("Download failed",downloadStatus(song("failed"),false))
        assertEquals("Downloaded",downloadStatus(song("complete"),true))
        // Never "Downloaded" for a row whose audio is not there.
        assertEquals("Download missing",downloadStatus(song("complete"),false))
        assertEquals("Not downloaded",downloadStatus(song("metadata"),false))
        assertEquals("Needs downloading again",downloadStatus(song("something-new"),false))
        // Audio that is on the phone is not described as waiting to download while its artwork is fetched again.
        assertEquals("Waiting to save artwork and lyrics",downloadStatus(song("queued"),true))
        assertEquals("Restart download",downloadRetryLabel(false));assertEquals("Retry missing assets",downloadRetryLabel(true))
        assertFalse("Nothing promises to resume",DownloadState.entries.any {downloadStatus(song(it.db),false).contains("esume")})
    }
    @Test fun everyFailureHasItsOwnWireNameAndTellsTheUserWhatToDo() {
        assertEquals(DownloadFailure.entries.size,DownloadFailure.entries.map {it.wire}.toSet().size)
        for(failure in DownloadFailure.entries) {
            assertTrue(failure.message.length in 20..120)
            assertFalse("Nothing from a response or an address",failure.message.contains("http") || failure.message.contains("="))
        }
        assertEquals(3,DOWNLOAD_AUTOMATIC_RETRIES)
    }

    // ---- storage

    @Test fun aSongMustFitTheBudgetWhenDoneAndTheDeviceWhileItIsMade() {
        val v=NetworkStoragePolicy.Verdict.Fits
        assertEquals(v,NetworkStoragePolicy.admit(gb,10*mb,4*gb,10*gb,32*gb))
        // Budget: what is kept is the audio and its assets.
        assertEquals(NetworkStoragePolicy.Verdict.OverBudget,NetworkStoragePolicy.admit(4*gb,10*mb,4*gb,10*gb,32*gb))
        assertEquals(NetworkStoragePolicy.Verdict.OverBudget,NetworkStoragePolicy.admit(4*gb-10*mb-1,10*mb,4*gb,10*gb,32*gb))
        assertEquals(v,NetworkStoragePolicy.admit(4*gb-10*mb-NetworkStoragePolicy.ASSET_ALLOWANCE,10*mb,4*gb,10*gb,32*gb))
        // Device: the source and the rebuilt copy exist together, above the reserve (a tenth of 32 GB here).
        val reserve=NetworkStoragePolicy.deviceReserve(32*gb);assertEquals(32*gb/10,reserve)
        val needed=2*10*mb+NetworkStoragePolicy.ASSET_ALLOWANCE
        assertEquals(v,NetworkStoragePolicy.admit(0,10*mb,4*gb,reserve+needed,32*gb))
        assertEquals(NetworkStoragePolicy.Verdict.DeviceFull,NetworkStoragePolicy.admit(0,10*mb,4*gb,reserve+needed-1,32*gb))
        // One copy would have fitted; before Q09 that was enough.
        assertEquals(NetworkStoragePolicy.Verdict.DeviceFull,NetworkStoragePolicy.admit(0,10*mb,4*gb,reserve+10*mb,32*gb))
        assertEquals("A small device keeps 1 GB",gb,NetworkStoragePolicy.deviceReserve(4*gb))
    }
    @Test fun unlimitedRemovesTheBudgetNotTheDeviceReserve() {
        assertEquals(NetworkStoragePolicy.Verdict.Fits,NetworkStoragePolicy.admit(256*gb,gb/4,Long.MAX_VALUE,100*gb,512*gb))
        assertEquals(NetworkStoragePolicy.Verdict.DeviceFull,NetworkStoragePolicy.admit(256*gb,gb/4,Long.MAX_VALUE,51*gb,512*gb))
        assertEquals(NetworkStoragePolicy.Verdict.DeviceFull,NetworkStoragePolicy.admit(0,10*mb,Long.MAX_VALUE,gb,32*gb))
        assertEquals("No overflow at the top of the range",NetworkStoragePolicy.Verdict.OverBudget,NetworkStoragePolicy.admit(Long.MAX_VALUE-10,100,Long.MAX_VALUE,1000*gb,2000*gb))
    }
    @Test fun sizesThatCannotBeASongAreRefused() {
        for(expected in listOf(0L,-1L,NetworkStoragePolicy.SONG_LIMIT+1,Long.MAX_VALUE)) assertEquals(NetworkStoragePolicy.Verdict.Invalid,NetworkStoragePolicy.admit(0,expected,4*gb,100*gb,128*gb))
        assertEquals(NetworkStoragePolicy.Verdict.Invalid,NetworkStoragePolicy.admit(-1,10*mb,4*gb,100*gb,128*gb))
    }
    @Test fun spaceIsCheckedAgainWhileTheSourceArrivesAndBeforeTheRebuild() {
        val total=32*gb;val reserve=NetworkStoragePolicy.deviceReserve(total);val assets=NetworkStoragePolicy.ASSET_ALLOWANCE
        // Half of 10 MB received: 5 MB still to come, 10 MB for the rebuilt copy, and the assets.
        assertTrue(NetworkStoragePolicy.transferMayContinue(5*mb,10*mb,reserve+15*mb+assets,total))
        assertFalse(NetworkStoragePolicy.transferMayContinue(5*mb,10*mb,reserve+15*mb+assets-1,total))
        // More arrived than was estimated: the rebuilt copy is at least that large.
        assertTrue(NetworkStoragePolicy.transferMayContinue(12*mb,10*mb,reserve+12*mb+assets,total))
        assertFalse(NetworkStoragePolicy.transferMayContinue(12*mb,10*mb,reserve+12*mb+assets-1,total))
        assertTrue(NetworkStoragePolicy.rebuildMayStart(10*mb,reserve+10*mb+assets,total))
        assertFalse(NetworkStoragePolicy.rebuildMayStart(10*mb,reserve+10*mb+assets-1,total))
        assertFalse(NetworkStoragePolicy.rebuildMayStart(10*mb,0,total))
    }
    @Test fun smartDownloadsNeedAnAllowedNetworkAndTwentyPercentBattery() {
        val wifi=NetworkConditions(wifi=true,unmetered=true,validated=true);val cellular=NetworkConditions(false,false,true)
        assertTrue(NetworkStoragePolicy.automaticAllowed(false,wifi,80))
        assertFalse(NetworkStoragePolicy.automaticAllowed(false,cellular,80))
        assertTrue(NetworkStoragePolicy.automaticAllowed(true,cellular,80))
        assertFalse("Metered Wi-Fi counts as mobile data",NetworkStoragePolicy.automaticAllowed(false,NetworkConditions(true,false,true),80))
        assertFalse("Not validated",NetworkStoragePolicy.automaticAllowed(true,NetworkConditions(true,true,false),80))
        assertFalse(NetworkStoragePolicy.automaticAllowed(false,wifi,19));assertTrue(NetworkStoragePolicy.automaticAllowed(false,wifi,20))
        assertFalse(NetworkStoragePolicy.automaticAllowed(false,wifi,0))
        assertTrue("A device that does not report its battery is not held back",NetworkStoragePolicy.automaticAllowed(false,wifi,-1))
    }

    // ---- a download's folder

    private val webm=byteArrayOf(0x1A,0x45,0xDF.toByte(),0xA3.toByte())+ByteArray(60) {7}
    private val mp4=byteArrayOf(0,0,0,0x18)+"ftypisom".toByteArray()+ByteArray(60) {7}
    private fun bundle(name:String,audio:ByteArray?=mp4,marker:String?=audio?.size?.toString()):File {
        val dir=folder.newFolder(name)
        if(audio!=null) File(dir,"audio.media").writeBytes(audio)
        if(marker!=null) File(dir,"ready").writeText(marker)
        return dir
    }
    @Test fun theMarkerVouchesForTheAudioByItsLength() {
        assertNotNull(BundleValidator.audio(bundle("sound")));assertNull(BundleValidator.problem(bundle("sound2",webm)))
        assertNotNull("The marker written before rc11",BundleValidator.audio(bundle("legacy",mp4,"1")))
        assertNotNull(BundleValidator.audio(bundle("spaces",mp4," ${mp4.size}\n")))
        assertEquals(BundleProblem.NoMarker,BundleValidator.problem(bundle("nomarker",mp4,null)))
        assertEquals(BundleProblem.NoAudio,BundleValidator.problem(bundle("noaudio",null,"72")))
        assertEquals(BundleProblem.NoAudio,BundleValidator.problem(bundle("empty",ByteArray(0),"0")))
        assertEquals("Truncated after it was committed",BundleProblem.MarkerMismatch,BundleValidator.problem(bundle("short",mp4.copyOf(40),mp4.size.toString())))
        assertEquals(BundleProblem.MarkerMismatch,BundleValidator.problem(bundle("garbage",mp4,"yes")))
        assertEquals(BundleProblem.NoMarker,BundleValidator.problem(bundle("huge",mp4,"9".repeat(40))))
        assertEquals(BundleProblem.NoAudio,BundleValidator.problem(File(folder.root,"missing")))
        for(name in listOf("nomarker","noaudio","empty","short","garbage","huge")) assertNull(name,BundleValidator.audio(File(folder.root,name)))
        val asFolder=folder.newFolder("asfolder");File(asFolder,"audio.media").mkdir();File(asFolder,"ready").writeText("1")
        assertNull(BundleValidator.audio(asFolder))
    }
    @Test fun onlyTheTwoContainersADownloadIsWrittenInAreOpened() {
        assertEquals("webm",BundleValidator.container(File(bundle("w",webm),"audio.media")))
        assertEquals("mp4",BundleValidator.container(File(bundle("m",mp4),"audio.media")))
        val wav="RIFF".toByteArray()+ByteArray(4)+"WAVEfmt ".toByteArray()+ByteArray(40)
        val text="<html>Sign in to confirm</html>".toByteArray()+ByteArray(40)
        for((name,bytes) in listOf("wav" to wav,"text" to text,"zeros" to ByteArray(64),"tiny" to byteArrayOf(0x1A,0x45,0xDF.toByte(),0xA3.toByte()))) {
            val dir=bundle(name,bytes)
            assertNull(name,BundleValidator.container(File(dir,"audio.media")))
            assertNotNull("The marker alone still vouches for the length",BundleValidator.audio(dir))
            assertEquals(name,BundleProblem.UnknownContainer,BundleValidator.problem(dir))
        }
    }
    @Test fun commitLeavesAudioWithAMatchingMarkerAndNothingUnfinished() {
        val dir=folder.newFolder("commit");val assembled=File(dir,"assembled.part").apply {writeBytes(mp4)}
        val audio=BundleValidator.commit(dir,assembled,6.5)
        assertEquals(File(dir,"audio.media"),audio);assertArrayEquals(mp4,audio.readBytes())
        assertEquals(mp4.size.toString(),File(dir,"ready").readText());assertEquals("6.5",File(dir,"loudness").readText())
        assertEquals(setOf("audio.media","ready","loudness"),dir.list()!!.toSet());assertNull(BundleValidator.problem(dir))
        // Committing again over an earlier copy: the new audio, and a marker that is the new audio's.
        File(dir,"assembled.part").writeBytes(webm+ByteArray(100))
        BundleValidator.commit(dir,File(dir,"assembled.part"),null)
        assertEquals((webm.size+100).toString(),File(dir,"ready").readText());assertEquals(webm.size+100L,File(dir,"audio.media").length())
        assertNull(BundleValidator.problem(dir))
    }
    @Test fun commitRefusesWhatIsNotAudioAndLeavesNoMarker() {
        val dir=folder.newFolder("refuse");val assembled=File(dir,"assembled.part").apply {writeBytes(ByteArray(64))}
        assertThrows(java.io.IOException::class.java) {BundleValidator.commit(dir,assembled,null)}
        assertFalse(File(dir,"ready").exists());assertFalse(File(dir,"audio.media").exists());assertNull(BundleValidator.audio(dir))
    }
    @Test fun aCommitThatCannotFinishNeverLeavesAMarkerThatVouches() {
        // The audio cannot take its place.
        val blocked=folder.newFolder("blocked");File(blocked,"audio.media").mkdir();File(blocked,"audio.media/keep").writeText("x")
        File(blocked,"ready").writeText("1")
        assertThrows(java.io.IOException::class.java) {BundleValidator.commit(blocked,File(blocked,"assembled.part").apply {writeBytes(mp4)},null)}
        assertFalse("The earlier marker was withdrawn first",File(blocked,"ready").exists());assertNull(BundleValidator.audio(blocked))
        // The marker cannot be written: the audio is there, unvouched, and is swept as unfinished.
        val unmarked=folder.newFolder("unmarked");File(unmarked,"ready.part").mkdir()
        assertThrows(java.io.IOException::class.java) {BundleValidator.commit(unmarked,File(unmarked,"assembled.part").apply {writeBytes(mp4)},null)}
        assertTrue(File(unmarked,"audio.media").isFile);assertNull(BundleValidator.audio(unmarked))
        BundleValidator.sweep(unmarked);assertFalse(File(unmarked,"audio.media").exists())
    }
    @Test fun sweepRemovesOnlyWhatATransferLeftUnfinished() {
        val dir=bundle("sweep");for(name in listOf("audio.part","assembled.part","artwork.part","lyrics.part","ready.part","loudness.part")) File(dir,name).writeText("x")
        File(dir,"artwork").writeText("art");File(dir,"lyrics.json").writeText("{}");File(dir,"loudness").writeText("3.0")
        assertEquals(6,BundleValidator.sweep(dir))
        assertEquals(setOf("audio.media","ready","artwork","lyrics.json","loudness"),dir.list()!!.toSet());assertNull(BundleValidator.problem(dir))
        assertEquals(0,BundleValidator.sweep(dir));assertEquals(0,BundleValidator.sweep(File(folder.root,"missing")))
        // Audio that was renamed into place but never got its marker is not a download.
        val unmarked=bundle("unmarked2",mp4,null);File(unmarked,"artwork").writeText("art")
        assertEquals(1,BundleValidator.sweep(unmarked));assertEquals(listOf("artwork"),unmarked.list()!!.toList())
    }
    @Test fun revokingTheMarkerMakesADownloadUnplayableWithoutDeletingItsAudio() {
        val dir=bundle("revoke");BundleValidator.revoke(dir)
        assertNull(BundleValidator.audio(dir));assertTrue(File(dir,"audio.media").isFile);assertEquals(BundleProblem.NoMarker,BundleValidator.problem(dir))
    }
}
