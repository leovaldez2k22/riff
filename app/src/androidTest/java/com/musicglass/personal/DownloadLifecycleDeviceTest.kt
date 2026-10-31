package com.musicglass.personal

import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Q09: downloads on a device. The download engine runs with Android's own extractor and muxer on audio
 * encoded here from a generated tone; the resolver, the audio and artwork responses and the lyrics are
 * scripted, so nothing reaches a provider. What it leaves is then played by the real playback service.
 * Emulator or the side-by-side debug build only (disposableInstall), signed out, synthetic songs.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class DownloadLifecycleDeviceTest {
    /**
     * Q18-071: run by hand with no limit, a network-off test here waited six hours. withTimeout around the engine
     * cannot stop blocking work, so the whole test, set-up included, ends after five minutes.
     */
    @get:Rule val deadline: Timeout = Timeout.seconds(300)
    private val device=PlaybackDeviceHarness()
    private var gateway:MusicPlaybackGateway?=null
    private var started=0L
    private val used=mutableSetOf<String>()
    private val store get()=OfflineMusicStore.get(device.target)
    private val scope get()=store.currentScope
    private fun id(n:Int)="fixtureD%03d".format(n).also(used::add)
    private fun row(n:Int)=store.song(scope,id(n))!!
    private fun dir(n:Int)=store.directory(scope,id(n))
    private fun files(n:Int)=dir(n).list()?.toSet().orEmpty()
    private val gb=1024L*1024*1024

    // ---- what the engine is given instead of a provider
    private val tone by lazy {DownloadFixtures.aac(device.instrumentation)}
    private var audioBody:()->ByteArray={tone}
    private var artworkBody:()->ByteArray={png}
    private var declaredMs=30_000L
    private var resolves=0
    private var audioRequests=0
    private val png by lazy {ByteArrayOutputStream().also {
        android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray()}
    private fun world():RuntimeDependencies {
        fun reply(chain:okhttp3.Interceptor.Chain,body:ByteArray):Response=Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("fixture").body(body.toResponseBody("application/octet-stream".toMediaType())).build()
        val http=OkHttpClient.Builder().addInterceptor {chain->
            val host=chain.request().url.host
            if(host=="audio.fixture") {audioRequests++;reply(chain,audioBody())}
            else if(host.endsWith("ytimg.com")) reply(chain,artworkBody())
            else throw IOException("Unexpected outbound fixture request")
        }.build()
        return device.process.runtime.copy(
            networkConditions={NetworkConditions(wifi=true,unmetered=true,validated=true)},
            storageCapacity={StorageCapacity(100*gb,128*gb)},batteryPercent={80},httpCalls=http,
            providerTransport={OriginalAudioEngine.create {config {addInterceptor {throw IOException("Unexpected provider HTTP request")}}}},
            resolver=StreamResolver {request->resolves++
                Result.success(ProviderStream("https://audio.fixture/${request.videoId}",emptyMap(),"audio/mp4",profileId="profile-$resolves",
                    sourceVideoId=request.videoId,bitrate=48_000,contentLength=audioBody().size.toLong(),durationMs=declaredMs,loudnessDb=4.0))},
            startProviderBackgroundWork=false,wait={},
            downloadLyrics={flowOf(LyricsDocument("fixture","",listOf(LyricLine(0,1000,"la"))))})
    }
    private fun download(vararg numbers:Int):Boolean {
        numbers.forEach {store.enqueue(ProviderTrack(id(it),"Fixture $it","Fixture artist"),scope=scope)}
        return runBlocking {withTimeout(120_000) {OfflineDownloadEngine.run(device.target,false,world()) {}}}
    }

    private fun events(prefix:String)=PlaybackEventLog(device.target).recent().mapNotNull {line->
        val stamp=line.substringBefore(' ').toLongOrNull()?:return@mapNotNull null
        line.substringAfter(' ').takeIf {stamp>=started && it.startsWith(prefix)}
    }
    private fun state()=device.onMain {gateway!!.state}
    private fun current()=device.onMain {device.controller.currentMediaItem?.mediaId}
    private fun playing()=device.onMain {device.controller.isPlaying}
    private fun position()=device.onMain {device.controller.currentPosition}
    private fun play(vararg numbers:Int) {
        device.onMain {device.controller.volume=0f}
        assertEquals(SessionResult.RESULT_SUCCESS,device.send(PLAY_CATALOG_QUEUE,device.queueArgs(numbers.map {id(it) to "Fixture $it"},autoplay=true)))
    }
    private fun advances(what:String) {
        val before=position()
        device.await(what,15_000) {playing() && position()>before+300}
    }

    @Before fun connect() {
        device.requireDisposableInstall()
        device.connect();device.signedOut();device.emptyPlayer()
        Thread.sleep(400);started=System.currentTimeMillis()
        gateway=device.onMain {MusicPlaybackGateway(device.target)}
        device.await("the app's own controller") {state().ready}
    }
    @After fun disconnect() {
        if(!device.connected) return
        device.onMain {device.controller.stop();device.controller.clearMediaItems();device.controller.volume=1f;device.controller.release();gateway?.close()}
        used.forEach {store.remove(scope,it,exclude=false);store.directory(scope,it).deleteRecursively()}
        listOf(GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY).forEach(device.process.snapshots::clear)
    }

    // ---- DLD03: what the engine leaves plays, seeks and plays again with nothing asked of the provider

    @Test fun aDownloadRebuiltOnTheDevicePlaysSeeksAndPlaysAgainWithoutTheProvider() {
        // Offline only, like RecoveryDeviceTest: on a phone with its network on, it is skipped, not failed.
        assumeTrue("The network must be off",!device.process.runtime.networkConditions().validated)
        assertTrue(download(1,2))
        for(n in 1..2) {
            val song=row(n)
            assertEquals(DownloadState.Complete,song.status);assertEquals("",song.error)
            assertEquals("available",song.artworkState);assertEquals("available",song.lyricsState)
            assertEquals(setOf("audio.media","ready","loudness","artwork","lyrics.json"),files(n))
            assertNull(BundleValidator.problem(dir(n)));assertEquals("mp4",BundleValidator.container(File(dir(n),"audio.media")))
            assertEquals(File(dir(n),"audio.media").length().toString(),File(dir(n),"ready").readText())
            assertEquals(4.0,store.loudness(scope,id(n))!!,0.0)
        }
        assertEquals(2,resolves);assertEquals(2,audioRequests)

        play(1,2)
        device.await("the download to play",20_000) {current()=="yt:${id(1)}" && playing()}
        device.await("its length") {device.onMain {device.controller.duration} in 29_000..31_000}
        advances("playback from the start")
        // A seek far from what has been read: the rebuilt file's index has to answer it.
        device.onMain {device.controller.seekTo(21_000)}
        device.await("the seek to land",15_000) {playing() && position() in 21_000..25_000}
        advances("playback after the seek")
        device.onMain {device.controller.seekTo(2_000)}
        device.await("the seek back",15_000) {playing() && position() in 2_000..6_000}
        device.onMain {device.controller.seekToNextMediaItem()}
        device.await("the next download",20_000) {current()=="yt:${id(2)}" && playing()}
        advances("playback of the next download")

        // Played again from nothing, as after the app is opened afresh.
        device.emptyPlayer()
        play(2)
        device.await("the download to play again",20_000) {current()=="yt:${id(2)}" && playing()}
        advances("playback the second time")

        assertEquals("The provider's resolver was not asked",emptyList<String>(),events("selected_stream_ready"))
        assertEquals(emptyList<String>(),events("stream_resolved"))
        assertEquals(emptyList<String>(),events("recovery"));assertEquals(emptyList<String>(),events("download_"))
        assertNull(state().error);assertNull(device.onMain {device.controller.playerError})
        assertEquals("Nothing more was fetched for a finished download",2,resolves);assertEquals(2,audioRequests)
    }

    // ---- DLD02: audio that is cut short, or not as long as it was said to be, never becomes a download

    @Test fun audioCutShortIsRefusedByTheRebuildAndNothingPlayableIsKept() {
        audioBody={tone.copyOf(tone.size*3/5)}
        assertTrue(download(3))
        val song=row(3)
        assertEquals(DownloadState.Failed,song.status);assertEquals(DownloadFailure.Damaged.message,song.error);assertEquals(1,song.failures)
        assertNull(store.audio(scope,id(3)));assertEquals(emptySet<String>(),files(3))
        assertEquals("A fresh address each time, and no more than three",3,resolves)
        assertEquals(listOf("download_retry phase=validate attempt=1 failure=damaged","download_retry phase=validate attempt=2 failure=damaged",
            "download_failed phase=validate kind=Unusable failure=damaged"),events("download_"))
    }
    @Test fun audioShorterThanTheProviderSaidIsRefused() {
        declaredMs=90_000
        assertTrue(download(4))
        assertEquals(DownloadState.Failed,row(4).status);assertEquals(DownloadFailure.Damaged.message,row(4).error)
        assertNull(store.audio(scope,id(4)));assertEquals(emptySet<String>(),files(4))
        // The same song with the right length declared: the failure did not stay with it.
        declaredMs=30_000
        assertTrue(download(4))
        assertEquals(DownloadState.Complete,row(4).status);assertEquals(0,row(4).failures);assertNull(BundleValidator.problem(dir(4)))
    }
    @Test fun artworkThatIsNotAnImageLeavesASongThatPlays() {
        artworkBody={"<html>not an image</html>".toByteArray()}
        assertTrue(download(5))
        val song=row(5)
        assertEquals(DownloadState.Complete,song.status);assertEquals("failed",song.artworkState);assertEquals("available",song.lyricsState)
        assertEquals(setOf("audio.media","ready","loudness","lyrics.json"),files(5))
        play(5)
        device.await("the download to play",20_000) {current()=="yt:${id(5)}" && playing()}
        advances("playback without artwork")
        assertEquals(emptyList<String>(),events("download_"))
    }

    // ---- a finished download that has gone bad on the device

    @Test fun aFinishedDownloadThatIsNotAudioIsNeverGivenToTheDecoder() {
        // Online, the refused song is fetched instead of waiting for a connection, so this is offline only.
        assumeTrue("The network must be off",!device.process.runtime.networkConditions().validated)
        assertTrue(download(6))
        // Replaced behind the app's back by something of the same kind of length that is not a download.
        val audio=File(dir(6),"audio.media")
        device.instrumentation.context.assets.open("quality/audio/short.wav").use {input->audio.outputStream().use {input.copyTo(it)}}
        File(dir(6),"ready").writeText(audio.length().toString())
        assertNotNull("The marker alone still vouches for it",store.audio(scope,id(6)))

        play(6)
        device.await("the download to be refused",20_000) {events("download_invalid").isNotEmpty()}
        device.await("the song to wait for a connection instead",40_000) {state().playback.recovery==RecoveryReason.Network}
        assertEquals(listOf("download_invalid reason=container"),events("download_invalid"))
        val song=row(6)
        assertEquals(DownloadState.Failed,song.status);assertEquals(DownloadFailure.Integrity.message,song.error)
        assertNull(store.audio(scope,id(6)));assertFalse(File(dir(6),"ready").exists())
        assertEquals("Download failed",downloadStatus(song,ready=false))
        Thread.sleep(4_000)
        assertEquals("It is refused once, not again and again",1,events("download_invalid").size)
        assertNull("Waiting for a connection is not a failure",state().error);assertEquals(RecoveryReason.Network,state().playback.recovery)

        // Downloading it again puts a sound copy in its place, and that one plays.
        device.emptyPlayer()
        assertTrue(download(6))
        assertEquals(DownloadState.Complete,row(6).status);assertNull(BundleValidator.problem(dir(6)))
        play(6)
        device.await("the new copy to play",20_000) {current()=="yt:${id(6)}" && playing()}
        advances("playback of the new copy")
    }
    @Test fun aDownloadThatBeginsRightButCannotBePlayedFailsAsADownloadAndIsNotRetried() {
        assertTrue(download(7))
        // The first part of real audio, with a marker that matches: nothing short of reading it shows the damage.
        val audio=File(dir(7),"audio.media")
        audio.writeBytes(audio.readBytes().let {it.copyOf(it.size/2)})
        File(dir(7),"ready").writeText(audio.length().toString())
        assertNull("It passes every check made before playing",BundleValidator.problem(dir(7)))

        play(7)
        // The file's index is at its front, so the first half plays and the damage is found only when the missing half
        // is read. Waiting for playback to get there needed seconds of audio clock, which a slow emulator late in the
        // lane did not always give (31 Oct, API 31, once in a lane and once in two reruns). So once the good half
        // plays, it seeks into the missing half, which has to be read at once.
        device.await("the good half to play",20_000) {current()=="yt:${id(7)}" && playing()}
        device.onMain {device.controller.seekTo(device.controller.duration*3/4)}
        try {device.await("the download failure",30_000) {state().playback.failure?.kind==PlaybackFailureKind.DownloadInvalid}}
        catch(e:AssertionError) {throw AssertionError("${e.message}; at ${position()} ms, playing ${playing()}",e)}
        val now=state()
        assertEquals(PlaybackStatus.Failed,now.playback.status)
        assertEquals("Download incomplete or unavailable",now.error)
        assertEquals(listOf(FailureAction.Redownload,FailureAction.RemoveDownload),now.playback.failure!!.actions())
        device.await("the download to be marked",10_000) {row(7).status==DownloadState.Failed}
        assertEquals(DownloadFailure.Integrity.message,row(7).error);assertNull(store.audio(scope,id(7)))
        assertEquals(listOf("download_invalid reason=unreadable"),events("download_invalid"))
        Thread.sleep(4_000)
        assertTrue("Not tried again by itself: ${events("recovery")}",events("recovery class=").isEmpty())
        assertEquals(1,events("recovery_stop").size);assertEquals(1,events("download_invalid").size)
        assertEquals("The provider was not asked while the download was failing",emptyList<String>(),events("selected_stream_ready"))
        assertFalse(playing())
    }
}
