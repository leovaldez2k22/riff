package com.musicglass.personal

import android.app.ActivityManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Q18-026: Android 15 limits how long a dataSync foreground service may run in a day. The real download service is
 * started with two songs queued and a stream that never answers, so its run is still going when the limit, shortened
 * to ten seconds, ends it. Android then calls onTimeout: the run stops, the service stops itself, the songs keep their
 * place in the queue and the job holds them. The limit is shortened with device_config and turned on for this package
 * with a compat change; both are put back afterwards. Emulator only, API 35 and newer: it changes system settings.
 */
@RunWith(AndroidJUnit4::class)
class DownloadTimeLimitDeviceTest {
    private val device=PlaybackDeviceHarness()
    private val target:Context get()=device.target
    private val store get()=OfflineMusicStore.get(target)
    private val scope get()=store.currentScope
    private val ids=listOf("fixtureT001","fixtureT002")
    private val running=CountDownLatch(1)
    private var started=0L
    private var changed=false

    private fun shell(command:String)=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        .let {ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use {r->r.readText().trim()}}
    private fun events(prefix:String)=PlaybackEventLog(target).recent().mapNotNull {line->
        val stamp=line.substringBefore(' ').toLongOrNull()?:return@mapNotNull null
        line.substringAfter(' ').takeIf {stamp>=started && it.startsWith(prefix)}
    }
    @Suppress("DEPRECATION")
    private fun serviceRunning()=target.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        .any {it.service.className==OfflineDownloadService::class.java.name && it.service.packageName==target.packageName}
    private fun manualJob()=target.getSystemService(JobScheduler::class.java).getPendingJob(OfflineDownloadScheduler.MANUAL)
    private fun await(what:String,limitMs:Long,check:()->Boolean) {
        val end=System.currentTimeMillis()+limitMs
        while(!check()) {assertTrue("Timed out waiting for $what",System.currentTimeMillis()<end);Thread.sleep(200)}
    }

    @Before fun setUp() {
        assumeTrue("Android 15's dataSync time limit needs API 35",Build.VERSION.SDK_INT>=35)
        assumeTrue("Emulator only: the test changes system settings",Build.HARDWARE in setOf("ranchu","goldfish"))
        device.requireDisposableInstall()
        runBlocking {device.process.stateReady.await()}
        device.signedOut()
        val http=OkHttpClient.Builder().addInterceptor {throw IOException("Unexpected outbound fixture request")}.build()
        OfflineDownloadService.runtimeFor={device.process.runtime.copy(
            networkConditions={NetworkConditions(wifi=true,unmetered=true,validated=true)},
            storageCapacity={StorageCapacity(100L shl 30,128L shl 30)},batteryPercent={80},httpCalls=http,
            providerTransport={OriginalAudioEngine.create {config {addInterceptor {throw IOException("Unexpected provider HTTP request")}}}},
            // The stream that never answers: the run waits here until Android's limit stops it.
            resolver=StreamResolver {running.countDown();awaitCancellation()},
            startProviderBackgroundWork=false,downloadLyrics=null)}
        changed=true
        val compat=shell("am compat enable --no-kill FGS_INTRODUCE_TIME_LIMITS ${target.packageName}")
        assertTrue("compat change: $compat",compat.startsWith("Enabled change"))
        shell("device_config put activity_manager data_sync_fgs_timeout_duration 10000")
        assertEquals("10000",shell("device_config get activity_manager data_sync_fgs_timeout_duration"))
        Thread.sleep(400);started=System.currentTimeMillis()
    }
    @After fun tearDown() {
        if(!changed) return
        target.stopService(Intent(target,OfflineDownloadService::class.java))
        OfflineDownloadService.runtimeFor={AppContainer.get(it).runtime}
        shell("device_config delete activity_manager data_sync_fgs_timeout_duration")
        shell("am compat reset --no-kill FGS_INTRODUCE_TIME_LIMITS ${target.packageName}")
        target.getSystemService(JobScheduler::class.java).cancel(OfflineDownloadScheduler.MANUAL)
        ids.forEach {store.remove(scope,it,exclude=false);store.directory(scope,it).deleteRecursively()}
    }

    @Test fun androidsDailyLimitEndsTheRealServiceAndTheJobHoldsWhatIsLeft() {
        ids.forEachIndexed {n,id->store.enqueue(ProviderTrack(id,"Fixture ${n+1}","Fixture artist"),scope=scope)}
        assertTrue("Android allowed the foreground service",DownloadCoordinator.start(target))
        assertTrue("The run reached the stream",running.await(20,TimeUnit.SECONDS))
        assertTrue(serviceRunning())
        assertEquals(emptyList<String>(),events("download_foreground_refused"))
        // Starting the service also scheduled the job behind it. Cancelled here, so only onTimeout can bring it back.
        target.getSystemService(JobScheduler::class.java).cancel(OfflineDownloadScheduler.MANUAL)
        assertNull(manualJob())

        await("Android's time limit",60_000) {events("download_foreground_timeout").isNotEmpty()}
        await("the service to stop",15_000) {!serviceRunning()}
        assertEquals(listOf("download_foreground_timeout"),events("download_foreground"))
        assertNotNull("onTimeout handed the rest to the job",manualJob())
        assertEquals("Both songs keep their place in the queue",listOf(DownloadState.Queued,DownloadState.Queued),ids.map {store.song(scope,it)!!.status})
        assertEquals(emptyList<String>(),events("download_failed"))
        // The stopped run let go of the engine, so the job (or the next download asked for) can run.
        val gate=OfflineDownloadEngine::class.java.getDeclaredField("gate").apply {isAccessible=true}.get(OfflineDownloadEngine) as kotlinx.coroutines.sync.Mutex
        await("the run to let go of the engine",10_000) {!gate.isLocked}
        // Android ends an app whose service outlives onTimeout by a few seconds; this process is still here to check.
        Thread.sleep(5_000)
        assertFalse(serviceRunning())
        assertFalse(gate.isLocked)
    }
}
