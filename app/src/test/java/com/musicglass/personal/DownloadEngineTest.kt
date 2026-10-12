package com.musicglass.personal

import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Q09: the download engine from request to a playable bundle, with every outside part scripted: the
 * resolver, the audio and artwork responses, free space, network, battery, the rebuild and the lyrics.
 * Synthetic account and songs; no network. A stop of the app is an Error thrown from one of those parts:
 * no handler in the engine runs, as none would if the process died.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class DownloadEngineTest {
    private class Killed:Error("The app stopped here")
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val processField=AppContainer::class.java.getDeclaredField("process").apply {isAccessible=true}
    private val storeField=OfflineMusicStore::class.java.getDeclaredField("instance").apply {isAccessible=true}
    private var previous:Any?=null
    private lateinit var container:AppContainer
    private lateinit var runtime:RuntimeDependencies
    private val gb=1024L*1024*1024

    // ---- the scripted world
    private var available=100*gb
    private val total=128*gb
    private var network=NetworkConditions(wifi=true,unmetered=true,validated=true)
    private var battery=80
    private val resolves=mutableListOf<StreamRequest>()
    private var payload=audio(300_000)
    private var streamFor:(StreamRequest)->ProviderStream={stream(it.videoId)}
    /** (song, first byte asked for, how many times the song was resolved) to status and body. */
    private var audioResponse:(String,Long,Int)->Pair<Int,ByteArray> ={_,_,_->200 to payload}
    private var artworkResponse:()->Pair<Int,ByteArray> ={200 to png}
    private var lyricsFor:(LyricsTrack)->Flow<LyricsDocument> ={flowOf(LyricsDocument("fixture","",listOf(LyricLine(0,1000,"la"))))}
    private var onSpace:()->Unit={}
    private var onAssemble:(File,File)->Unit={_,_->}
    private var onWallClock:()->Unit={}
    private val trace=mutableListOf<String>()
    private val audioRequests=mutableListOf<String>()
    private var artworkRequests=0
    private val png by lazy {ByteArrayOutputStream().also {Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()}

    private fun audio(size:Int)=byteArrayOf(0,0,0,0x18)+"ftypisom".toByteArray()+ByteArray(size-12) {(it%251).toByte()}
    private fun id(n:Int)="engine%05d".format(n)
    private fun track(n:Int)=ProviderTrack(id(n),"Song $n","Artist")
    private fun stream(id:String,length:Long?=payload.size.toLong(),bounded:Boolean=false,profile:String="profile-${resolves.count {it.videoId==id}}")=
        ProviderStream("https://audio.fixture/$id",emptyMap(),"audio/mp4",profileId=profile,sourceVideoId=id,bitrate=128_000,
            contentLength=length,durationMs=30_000,boundedRange=bounded,loudnessDb=4.0)
    private val store get()=OfflineMusicStore.get(context)
    private val scope get()=store.currentScope
    private fun row(n:Int)=store.song(scope,id(n))!!
    private fun files(n:Int)=store.directory(scope,id(n)).list()?.toSet().orEmpty()
    private fun state(id:String)=store.song(store.currentScope,id)?.state.orEmpty()
    private fun events()=PlaybackEventLog(context).recent().map {it.substringAfter(' ')}.filter {it.startsWith("download_")}

    private fun session(sync:String?) {
        val edit=context.getSharedPreferences("provider_session",Context.MODE_PRIVATE).edit().clear()
        CookieManager.getInstance().removeAllCookies(null)
        if(sync==null) edit.putBoolean("signed_out",true).commit()
        else {edit.putString("sync",sync).putString("account","0").commit();CookieManager.getInstance().setCookie("https://music.youtube.com","SAPISID=fixture")}
    }
    private fun reply(chain:Interceptor.Chain,code:Int,body:ByteArray):Response=Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(code).message("fixture").body(body.toResponseBody("application/octet-stream".toMediaType())).build()

    @Before fun world() {
        previous=processField.get(null);storeField.set(null,null)
        session("fixture-sync-A")
        val clock=object:AppClocks {
            var monotonic=0L
            override fun wallTimeMillis():Long {onWallClock();return 1_800_000_000_000L}
            // Each reading is half a second later, so once-a-second checks come round.
            override fun monotonicMillis():Long {monotonic+=500;return monotonic}
        }
        val http=OkHttpClient.Builder().addInterceptor {chain->
            val url=chain.request().url
            if(url.host=="audio.fixture") {
                val song=url.pathSegments.last()
                val from=url.queryParameter("range")?.substringBefore('-')?.toLong()?:chain.request().header("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLong()?:0L
                audioRequests+="$song@$from";trace+="audio:${state(song)}"
                val (code,body)=audioResponse(song,from,resolves.count {it.videoId==song})
                reply(chain,code,body)
            } else if(url.host.endsWith("ytimg.com")) {
                artworkRequests++;trace+="artwork:${state(url.pathSegments.getOrElse(1) {""})}"
                val (code,body)=artworkResponse();reply(chain,code,body)
            } else throw IOException("Unexpected outbound fixture request")
        }.build()
        runtime=RuntimeDependencies(
            networkConditions={network},
            storageCapacity={onSpace();StorageCapacity(available,total)},
            batteryPercent={battery},httpCalls=http,clocks=clock,
            dispatchers=AppDispatchers(Dispatchers.Unconfined,Dispatchers.Unconfined),
            providerTransport={OriginalAudioEngine.create {config {addInterceptor {throw IOException("Unexpected provider HTTP request")}}}},
            resolver=StreamResolver {request->resolves+=request;trace+="resolve:${state(request.videoId)}";runCatching {streamFor(request)}},
            storedScopeKeys={emptySet()},startProviderBackgroundWork=false,wait={},
            assembler=AudioAssembler {source,output,_,check->
                trace+="assemble:${state(source.parentFile!!.name)}"
                check();onAssemble(source,output)
                if(BundleValidator.container(source)==null) throw IOException("No valid audio")
                output.writeBytes(source.readBytes())
            },
            downloadLyrics={track->trace+="lyrics:${state(track.mediaId.removePrefix("yt:"))}";lyricsFor(track)})
        container=AppContainer(context,runtime);processField.set(null,container)
    }
    @After fun restore() {
        runCatching {container.provider.close()}
        processField.set(null,previous);storeField.set(null,null);session(null)
    }
    private fun run(automatic:Boolean=false):Boolean=runBlocking {withTimeout(30_000) {OfflineDownloadEngine.run(context,automatic,runtime) {}}}
    /** The app starts again: the store is opened afresh and checks what it finds. */
    private fun restart():ReconcileReport {storeField.set(null,null);return store.startupReport}
    private fun queue(vararg numbers:Int) {numbers.forEach {store.enqueue(track(it))}}
    private fun assertPlayable(n:Int) {
        val dir=store.directory(scope,id(n))
        assertNull("song $n is sound",BundleValidator.problem(dir));assertNotNull(store.audio(scope,id(n)))
        assertFalse("Nothing unfinished in ${files(n)}",files(n).any {it.endsWith(".part")})
    }
    private fun assertNothingLeft(n:Int) {assertNull(store.audio(scope,id(n)));assertEquals("song $n",emptySet<String>(),files(n))}

    // ---- the path

    @Test fun aSongGoesThroughEveryStateAndEndsAsABundleThatNeedsNoProvider() {
        queue(1)
        assertTrue(run())
        assertEquals(listOf("resolve:resolving","audio:transferring","assemble:validating","artwork:assets","lyrics:assets"),trace)
        val row=row(1)
        assertEquals(DownloadState.Complete,row.status);assertEquals("",row.error);assertEquals(0,row.failures)
        assertEquals(payload.size.toLong(),row.bytes);assertEquals(row.bytes,row.total);assertEquals(30_000L,row.durationMs)
        assertEquals("available",row.artworkState);assertEquals("available",row.lyricsState);assertEquals(1_800_000_000_000L,row.downloadedAt)
        assertTrue(row.quality.startsWith("128 kbps"))
        assertEquals(setOf("audio.media","ready","loudness","artwork","lyrics.json"),files(1))
        assertPlayable(1)
        assertEquals(payload.size.toString(),File(store.directory(scope,id(1)),"ready").readText())
        assertEquals(4.0,store.loudness(scope,id(1))!!,0.0);assertNotNull(store.lyrics(scope,id(1)))
        assertEquals(listOf(StreamRequest(id(1),download=true,quality=com.metrolist.innertubex.extraction.AudioQuality.HIGH)),resolves)
        // I11: nothing more is asked of the provider for a finished download, by another run or after a restart.
        assertTrue(run());restart();assertTrue(run())
        assertEquals(1,resolves.size);assertEquals(1,audioRequests.size);assertEquals(1,artworkRequests)
        assertPlayable(1);assertEquals(DownloadState.Complete,row(1).status)
        assertEquals(emptyList<String>(),events())
    }
    @Test fun aLargerSongArrivesInBoundedRangesAndProgressIsRecorded() {
        payload=audio(2_500_000);streamFor={stream(it.videoId,bounded=true)}
        val seen=mutableListOf<Long>()
        audioResponse={_,from,_->seen+=row(1).bytes;200 to payload.copyOfRange(from.toInt(),minOf(from.toInt()+1_048_576,payload.size))}
        queue(1);assertTrue(run())
        assertEquals(listOf("${id(1)}@0","${id(1)}@1048576","${id(1)}@2097152"),audioRequests)
        assertTrue("Progress is written as it arrives: $seen",seen[0]==0L && seen[1] in 700_000..1_048_576 && seen[2]>seen[1])
        assertArrayEquals(payload,store.audio(scope,id(1))!!.readBytes());assertPlayable(1)
    }

    // ---- DLD01: the app stops in each phase

    private fun stopsAndRecovers(expectResolveAgain:Boolean,playableWhileStopped:Boolean,arrange:()->Unit,disarm:()->Unit) {
        queue(1);arrange()
        assertThrows(Killed::class.java) {run()}
        assertNotEquals("Never complete at the moment of the stop",DownloadState.Complete,row(1).status)
        assertEquals(playableWhileStopped,store.audio(scope,id(1))!=null)
        val resolvedBefore=resolves.size
        val report=restart();disarm()
        assertEquals(1,report.requeued)
        assertEquals(DownloadState.Queued,row(1).status);assertEquals(playableWhileStopped,store.audio(scope,id(1))!=null)
        assertFalse(files(1).any {it.endsWith(".part")})
        if(!playableWhileStopped) assertEquals(emptySet<String>(),files(1))
        assertTrue(run())
        assertEquals(DownloadState.Complete,row(1).status);assertPlayable(1)
        assertEquals(if(expectResolveAgain) resolvedBefore+1 else resolvedBefore,resolves.size)
        assertEquals("available",row(1).artworkState);assertEquals("available",row(1).lyricsState)
    }
    @Test fun aStopWhileResolvingIsRecovered()=stopsAndRecovers(true,false,
        {streamFor={throw Killed()}},{streamFor={stream(it.videoId)}})
    @Test fun aStopInTheMiddleOfATransferIsRecovered() {
        payload=audio(2_500_000)
        stopsAndRecovers(true,false,{onSpace={if(row(1).status==DownloadState.Transferring && row(1).bytes>0) throw Killed()}},{onSpace={}})
    }
    @Test fun aStopDuringTheRebuildIsRecovered()=stopsAndRecovers(true,false,
        {onAssemble={source,output->output.writeBytes(source.readBytes().copyOf(1000));throw Killed()}},{onAssemble={_,_->}})
    @Test fun aStopAfterTheMarkerButBeforeTheTableIsRecoveredWithoutAskingTheProviderAgain()=stopsAndRecovers(false,true,
        {onWallClock={throw Killed()}},{onWallClock={}})
    @Test fun aStopWhileSavingArtworkIsRecoveredWithoutAskingTheProviderAgain()=stopsAndRecovers(false,true,
        {artworkResponse={throw Killed()}},{artworkResponse={200 to png}})
    @Test fun aStopWhileSavingLyricsIsRecoveredWithoutAskingTheProviderAgain()=stopsAndRecovers(false,true,
        {lyricsFor={throw Killed()}},{lyricsFor={flowOf(LyricsDocument("fixture","",listOf(LyricLine(0,1000,"la"))))}})

    // ---- DLD02: the provider or the transfer goes wrong

    @Test fun aRefusalInTheMiddleOfATransferStartsAgainFromTheFirstByteWithAFreshStream() {
        payload=audio(2_500_000);streamFor={stream(it.videoId,bounded=true)}
        audioResponse={_,from,resolved->if(resolved==1 && from>0) 403 to ByteArray(0) else 200 to payload.copyOfRange(from.toInt(),minOf(from.toInt()+1_048_576,payload.size))}
        queue(1);assertTrue(run())
        assertEquals(listOf("${id(1)}@0","${id(1)}@1048576","${id(1)}@0","${id(1)}@1048576","${id(1)}@2097152"),audioRequests)
        assertEquals("The stream that was refused is not offered again",setOf("profile-1"),resolves[1].excludedProfiles)
        assertEquals(2,resolves.size);assertEquals(DownloadState.Complete,row(1).status);assertEquals(0,row(1).failures)
        assertArrayEquals("Nothing from the first attempt was kept or appended",payload,store.audio(scope,id(1))!!.readBytes())
        assertEquals(listOf("download_retry phase=transfer attempt=1 failure=rejected"),events())
    }
    @Test fun aSongThatIsAlwaysRefusedFailsAfterThreeAttemptsAndIsNotTriedForEver() {
        audioResponse={_,_,_->403 to ByteArray(0)}
        queue(1);assertTrue(run())
        assertEquals(3,resolves.size);assertEquals(setOf("profile-1","profile-2"),resolves[2].excludedProfiles)
        var row=row(1)
        assertEquals(DownloadState.Failed,row.status);assertEquals(DownloadFailure.Rejected.message,row.error);assertEquals(1,row.failures)
        assertNothingLeft(1)
        assertTrue(events().last().startsWith("download_failed phase=transfer kind=Rejected failure=rejected"))
        // Two more runs try it by themselves; after that it waits for the user.
        assertTrue(run());assertTrue(run())
        assertEquals(9,resolves.size);assertEquals(3,row(1).failures)
        assertTrue(run());restart();assertTrue(run())
        assertEquals("Not tried a fourth time",9,resolves.size);assertEquals(DownloadState.Failed,row(1).status)
        // The user restarts it, and this time it works.
        audioResponse={_,_,_->200 to payload}
        store.enqueue(track(1));row=row(1);assertEquals(0,row.failures);assertEquals("",row.error)
        assertTrue(run());assertEquals(DownloadState.Complete,row(1).status);assertPlayable(1)
    }
    @Test fun moreOrLessThanTheRangeAskedForIsNotAccepted() {
        for((n,body) in listOf(1 to payload+ByteArray(10),2 to payload.copyOf(payload.size-10),3 to ByteArray(0))) {
            audioResponse={_,_,_->200 to body}
            queue(n);assertTrue(run())
            assertEquals("song $n",DownloadState.Failed,row(n).status);assertEquals(DownloadFailure.Incomplete.message,row(n).error)
            assertNothingLeft(n)
            assertEquals("Three attempts in its first run",3,resolves.count {it.videoId==id(n)})
        }
    }
    @Test fun aSongWithoutADeclaredLengthIsRefusedAtOnce() {
        streamFor={stream(it.videoId,length=null)}
        queue(1);assertTrue(run())
        // Without a length the estimate is the duration times the bitrate; a plain stream must still declare its length.
        assertEquals(DownloadState.Failed,row(1).status);assertEquals(DownloadFailure.Unavailable.message,row(1).error)
        assertEquals("No second attempt can cure it",1,resolves.size);assertEquals(0,audioRequests.size);assertNothingLeft(1)
    }
    @Test fun aDeclaredLengthThatTheAudioDoesNotHaveIsNotAccepted() {
        streamFor={stream(it.videoId,length=payload.size+500L)}
        queue(1);assertTrue(run())
        assertEquals(DownloadFailure.Incomplete.message,row(1).error);assertNothingLeft(1)
    }
    @Test fun aPlaylistManifestIsNotADownload() {
        streamFor={stream(it.videoId).copy(mimeType="application/x-mpegURL")}
        queue(1);assertTrue(run())
        assertEquals(DownloadFailure.Unavailable.message,row(1).error);assertEquals(1,resolves.size);assertEquals(0,audioRequests.size)
    }
    @Test fun audioThatCannotBeRebuiltIsDamagedNotADownload() {
        payload="<html>Sign in to confirm you are not a bot</html>".toByteArray()+ByteArray(5000)
        queue(1);assertTrue(run())
        assertEquals(DownloadState.Failed,row(1).status);assertEquals(DownloadFailure.Damaged.message,row(1).error)
        assertEquals(3,resolves.size);assertNothingLeft(1)
        assertTrue(events().last().contains("phase=validate") && events().last().contains("failure=damaged"))
    }
    @Test fun aResolverThatFailsLeavesAFailedRowAndNoFiles() {
        streamFor={throw FixtureException(FixtureFailure.OFFLINE)}
        queue(1);assertTrue(run())
        assertEquals(DownloadState.Failed,row(1).status);assertEquals(DownloadFailure.Incomplete.message,row(1).error);assertNothingLeft(1)
        assertEquals("The first resolve is not repeated inside a run",1,resolves.size)
    }

    // ---- DLD02: full disk

    @Test fun aSongThatWouldNotFitIsNotStarted() {
        available=NetworkStoragePolicy.deviceReserve(total)+payload.size
        queue(1);assertTrue(run())
        assertEquals(DownloadFailure.Storage.message,row(1).error);assertEquals(DownloadState.Failed,row(1).status)
        assertEquals(1,resolves.size);assertEquals("Nothing was transferred",0,audioRequests.size);assertNothingLeft(1)
    }
    @Test fun theBudgetIsItsOwnReasonAndUnlimitedStillKeepsTheDeviceReserve() {
        context.getSharedPreferences("offline_settings",Context.MODE_PRIVATE).edit().putLong("budget_bytes",1000L).commit()
        queue(1);assertTrue(run())
        assertEquals(DownloadFailure.Budget.message,row(1).error);assertEquals(0,audioRequests.size)
        context.getSharedPreferences("offline_settings",Context.MODE_PRIVATE).edit().putLong("budget_bytes",Long.MAX_VALUE).commit()
        available=NetworkStoragePolicy.deviceReserve(total)
        store.enqueue(track(1));assertTrue(run())
        assertEquals(DownloadFailure.Storage.message,row(1).error);assertEquals(0,audioRequests.size)
        available=100*gb
        store.enqueue(track(1));assertTrue(run());assertEquals(DownloadState.Complete,row(1).status)
    }
    @Test fun theDiskFillingUpDuringTheTransferStopsItWithoutRetrying() {
        payload=audio(2_500_000)
        onSpace={if(row(1).status==DownloadState.Transferring && row(1).bytes>0) available=NetworkStoragePolicy.deviceReserve(total)+1000}
        queue(1);assertTrue(run())
        assertEquals(DownloadFailure.Storage.message,row(1).error);assertEquals(1,resolves.size);assertNothingLeft(1)
        assertEquals(listOf("download_failed phase=transfer kind=Stop failure=storage"),events())
    }
    @Test fun theDiskFillingUpBeforeTheRebuildStopsIt() {
        onSpace={if(row(1).status==DownloadState.Validating) available=NetworkStoragePolicy.deviceReserve(total)+1000}
        queue(1);assertTrue(run())
        assertEquals(DownloadFailure.Storage.message,row(1).error);assertEquals(listOf("resolve:resolving","audio:transferring"),trace);assertNothingLeft(1)
    }
    @Test fun theDiskFillingUpDuringTheRebuildIsAStorageFailureNotBadAudio() {
        onAssemble={_,output->output.writeBytes(ByteArray(100));available=10;throw IOException("write failed: ENOSPC")}
        queue(1);assertTrue(run())
        assertEquals(DownloadFailure.Storage.message,row(1).error);assertEquals(1,resolves.size);assertNothingLeft(1)
    }
    @Test fun aCommitThatCannotBeMadeLeavesNoDownload() {
        // Something sits where the audio must go and cannot be replaced.
        onAssemble={source,_->File(source.parentFile,"audio.media").apply {mkdir()}.resolve("blocker").writeText("x")}
        queue(1);assertTrue(run())
        assertEquals(DownloadState.Failed,row(1).status);assertEquals(DownloadFailure.Commit.message,row(1).error)
        assertNull(store.audio(scope,id(1)));assertFalse("ready" in files(1));assertEquals(1,resolves.size)
        assertTrue(events().last().contains("phase=commit"))
    }

    // ---- assets are not the download

    @Test fun missingArtworkAndLyricsDoNotUndoSoundAudio() {
        artworkResponse={404 to ByteArray(0)};lyricsFor={throw IOException("lyrics source down")}
        queue(1);assertTrue(run())
        val row=row(1)
        assertEquals(DownloadState.Complete,row.status);assertEquals("failed",row.artworkState);assertEquals("failed",row.lyricsState)
        assertPlayable(1);assertEquals(setOf("audio.media","ready","loudness"),files(1))
        // "Retry missing assets": only what is missing is fetched; the audio is not touched and the provider is not asked for it.
        val stamp=store.audio(scope,id(1))!!.lastModified()
        artworkResponse={200 to png};lyricsFor={emptyFlow()}
        store.enqueue(track(1));assertEquals("Waiting to save artwork and lyrics",downloadStatus(row(1),true))
        assertTrue(run())
        assertEquals(1,resolves.size);assertEquals(1,audioRequests.size);assertEquals(stamp,store.audio(scope,id(1))!!.lastModified())
        assertEquals("available",row(1).artworkState);assertEquals("Looked for and not found",unavailable,row(1).lyricsState)
        assertEquals(DownloadState.Complete,row(1).status)
    }
    private val unavailable="unavailable"
    /** That a response which is not an image is refused is checked on the emulator: this JVM's image decoder accepts any bytes. */
    @Test fun artworkThatCannotBeWrittenIsNeverRecordedAsSaved() {
        artworkResponse={500 to ByteArray(0)}
        queue(1);assertTrue(run())
        assertEquals("failed",row(1).artworkState);assertFalse("artwork" in files(1));assertNull(store.artwork(scope,id(1)))
        // The image is fine but its place is taken: the write fails and the state says so.
        artworkResponse={200 to png}
        File(store.directory(scope,id(1)),"artwork").apply {mkdir()}.resolve("blocker").writeText("x")
        store.enqueue(track(1));assertTrue(run())
        assertEquals("failed",row(1).artworkState);assertNull(store.artwork(scope,id(1)));assertFalse("artwork.part" in files(1))
        assertEquals(DownloadState.Complete,row(1).status);assertPlayable(1)
    }

    // ---- only the work that is concerned stops

    @Test fun deleteAllDuringArtworkRemovesEverythingAndNothingComesBack() {
        queue(1,2)
        artworkResponse={store.removeAll(scope);200 to png}
        assertTrue(run())
        for(n in 1..2) {
            val row=row(n)
            assertEquals("song $n",DownloadState.Metadata,row.status);assertTrue(row.excluded);assertFalse(row.pinned)
            assertFalse("song $n has no folder",store.directory(scope,id(n)).exists())
        }
        assertEquals("The song that was waiting is not started",1,resolves.size)
        assertEquals(emptyList<String>(),events())
        assertTrue(run());restart();assertTrue(run());assertEquals(1,resolves.size)
    }
    @Test fun pausingOneSongLetsTheNextOneFinish() {
        payload=audio(2_500_000);streamFor={stream(it.videoId,bounded=true)}
        audioResponse={song,from,_->if(song==id(1) && from>0) store.pause(scope,id(1));200 to payload.copyOfRange(from.toInt(),minOf(from.toInt()+1_048_576,payload.size))}
        queue(1,2)
        store.update(scope,id(1),"priority" to 5)
        assertTrue(run())
        assertEquals(DownloadState.Paused,row(1).status);assertNothingLeft(1);assertTrue(row(1).pinned)
        assertEquals(DownloadState.Complete,row(2).status);assertPlayable(2)
        // It stays paused until asked for, and then begins at the first byte.
        audioRequests.clear();assertTrue(run());assertEquals(emptyList<String>(),audioRequests)
        audioResponse={_,from,_->200 to payload.copyOfRange(from.toInt(),minOf(from.toInt()+1_048_576,payload.size))}
        store.enqueue(track(1));assertTrue(run())
        assertEquals("${id(1)}@0",audioRequests.first());assertEquals(DownloadState.Complete,row(1).status)
    }
    @Test fun aSongRemovedAndAskedForAgainDuringItsTransferIsDownloadedOnceMoreByTheSameRun() {
        // While the first transfer is asking where the audio is.
        var done=false
        streamFor={if(!done) {done=true;store.remove(scope,id(1),exclude=false);store.enqueue(track(1))};stream(it.videoId)}
        queue(1);assertTrue(run())
        assertEquals("Only the second transfer fetched audio",1,audioRequests.size)
        assertEquals("The first transfer lost the row; the second finished it",2,resolves.size)
        assertEquals(DownloadState.Complete,row(1).status);assertPlayable(1)
    }
    @Test fun songsQueuedWhileARunIsGoingAreTakenUpByThatRun() {
        artworkResponse={if(store.song(scope,id(2))==null) store.enqueue(track(2));200 to png}
        queue(1);assertTrue(run())
        assertEquals(listOf(id(1),id(2)),resolves.map {it.videoId})
        assertEquals(DownloadState.Complete,row(2).status);assertPlayable(2)
    }

    // ---- DLD06: bulk requests and pins

    @Test fun aBulkRequestFetchesOnlyWhatIsNotThereAndLeavesFinishedFilesAlone() {
        queue(1);assertTrue(run())
        val stamp=store.audio(scope,id(1))!!.lastModified();val bytes=store.audio(scope,id(1))!!.readBytes()
        assertEquals(2,store.enqueueSongs(listOf(track(1),track(2),track(2),track(1))))
        assertTrue(run())
        assertEquals(listOf(id(1),id(2)),resolves.map {it.videoId})
        assertEquals(stamp,store.audio(scope,id(1))!!.lastModified());assertArrayEquals(bytes,store.audio(scope,id(1))!!.readBytes())
        assertEquals(2,store.rows(scope).count {it.status==DownloadState.Complete && it.pinned})
    }

    // ---- DLD05: Smart Downloads and its conditions; manual downloads are their own thing

    private fun smart(on:Boolean) {context.getSharedPreferences("offline_settings",Context.MODE_PRIVATE).edit().putBoolean("smart_enabled",on).commit()}
    /** A song the user has listened to enough for Smart Downloads to choose it. */
    private fun listenedTo(n:Int) {
        store.remember(track(n));store.update(scope,id(n),"listened" to 60_000L,"duration" to 30_000L,"lastPlayed" to 1_799_999_000_000L,"plays" to 3)
    }
    @Test fun smartDownloadsChoosesListenedSongsAndRespectsRemovalsAndPins() {
        smart(true)
        listenedTo(1)
        // Removed by the user: excluded, whatever its listening says.
        listenedTo(2);store.enqueue(track(2),pinned=false);store.remove(scope,id(2))
        // Kept by hand and never listened to: not Smart Downloads' to remove.
        queue(3);assertTrue(run());assertEquals(DownloadState.Complete,row(3).status)
        // Downloaded by Smart Downloads earlier and no longer in its selection.
        queue(4);assertTrue(run());store.update(scope,id(4),"pinned" to 0)
        resolves.clear()

        assertTrue(run(automatic=true))
        assertEquals(listOf(id(1)),resolves.map {it.videoId})
        assertEquals(DownloadState.Complete,row(1).status);assertFalse(row(1).pinned);assertPlayable(1)
        assertEquals("Not chosen again after the user removed it",DownloadState.Metadata,row(2).status);assertTrue(row(2).excluded)
        assertEquals(DownloadState.Complete,row(3).status);assertTrue(row(3).pinned);assertPlayable(3)
        assertEquals("The selection rolled on",DownloadState.Metadata,row(4).status);assertFalse(row(4).excluded);assertNull(store.audio(scope,id(4)))
        // Downloading it by hand ends the exclusion.
        store.enqueue(track(2));assertFalse(row(2).excluded);assertTrue(row(2).pinned)
    }
    @Test fun aChangeOfNetworkDuringAnAutomaticTransferStopsItAndItWaits() {
        smart(true);listenedTo(1)
        payload=audio(2_500_000);streamFor={stream(it.videoId,bounded=true)}
        audioResponse={_,from,_->if(from>0) network=NetworkConditions(wifi=false,unmetered=false,validated=true)
            200 to payload.copyOfRange(from.toInt(),minOf(from.toInt()+1_048_576,payload.size))}
        assertFalse(run(automatic=true))
        assertEquals(DownloadState.Queued,row(1).status);assertNothingLeft(1)
        assertEquals(listOf("download_stopped reason=conditions phase=transfer"),events())
        assertFalse("Not while the network is not allowed",run(automatic=true));assertEquals(1,resolves.size)
        // Allowing mobile data, or Wi-Fi coming back, lets it run.
        context.getSharedPreferences("offline_settings",Context.MODE_PRIVATE).edit().putBoolean("allow_mobile_data",true).commit()
        audioResponse={_,from,_->200 to payload.copyOfRange(from.toInt(),minOf(from.toInt()+1_048_576,payload.size))}
        assertTrue(run(automatic=true));assertEquals(DownloadState.Complete,row(1).status);assertPlayable(1)
    }
    @Test fun aLowBatteryOrPlaybackHoldsAutomaticWorkBack() {
        smart(true);listenedTo(1)
        battery=15;assertFalse(run(automatic=true));assertEquals(0,resolves.size)
        battery=80;store.playbackBusy=true
        run(automatic=true);assertEquals("Nothing is chosen or fetched while something is playing",0,resolves.size)
        assertEquals(DownloadState.Metadata,row(1).status)
        store.playbackBusy=false
        audioResponse={_,_,_->battery=10;200 to payload}
        assertFalse("The battery fell during the transfer",run(automatic=true))
        assertEquals(DownloadState.Queued,row(1).status);assertNothingLeft(1)
    }
    @Test fun switchingSmartDownloadsOffDuringItsTransferPausesItAndKeepsWhatTheUserPinned() {
        smart(true);listenedTo(1)
        queue(2);assertTrue(run());queue(3)
        audioResponse={song,_,_->if(song==id(1)) store.setSmart(false);200 to payload}
        run(automatic=true)
        assertEquals(DownloadState.Paused,row(1).status);assertNothingLeft(1);assertEquals(1,resolves.count {it.videoId==id(1)})
        assertEquals("A finished download kept by hand",DownloadState.Complete,row(2).status);assertPlayable(2);assertTrue(row(2).pinned)
        assertEquals("A waiting download asked for by hand",DownloadState.Queued,row(3).status);assertTrue(row(3).pinned)
        run(automatic=true);assertEquals("Off means off",1,resolves.count {it.videoId==id(1)})
        assertTrue(run());assertEquals(DownloadState.Complete,row(3).status)
        assertEquals("Smart Downloads' song was not fetched by the manual run",DownloadState.Paused,row(1).status)
    }
    @Test fun aDownloadAskedForByHandIgnoresTheSmartDownloadsConditions() {
        network=NetworkConditions(wifi=false,unmetered=false,validated=false);battery=5;store.playbackBusy=true
        queue(1);assertTrue(run())
        assertEquals(DownloadState.Complete,row(1).status);assertPlayable(1)
        store.playbackBusy=false
    }
    @Test fun anAutomaticRunLeavesManualSongsAndAManualRunLeavesAutomaticOnes() {
        smart(true);listenedTo(1);queue(2)
        assertTrue(run(automatic=true))
        assertEquals(listOf(id(1)),resolves.map {it.videoId});assertEquals(DownloadState.Queued,row(2).status)
        assertTrue(run());assertEquals(DownloadState.Complete,row(2).status)
    }

    // ---- integrity after the fact

    @Test fun aFinishedDownloadWhoseFilesWentAwayIsFoundAndFetchedAgain() {
        queue(1);assertTrue(run())
        File(store.directory(scope,id(1)),"audio.media").writeBytes(payload.copyOf(1000))
        assertNull("The marker no longer matches",store.audio(scope,id(1)))
        assertTrue(run())
        assertEquals("Found, said, and fetched again in the same run",2,resolves.size)
        assertEquals("download_integrity damaged=1",events().first())
        assertEquals(DownloadState.Complete,row(1).status);assertPlayable(1);assertArrayEquals(payload,store.audio(scope,id(1))!!.readBytes())
    }
}
