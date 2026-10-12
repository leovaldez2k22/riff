package com.musicglass.personal

import android.app.ForegroundServiceStartNotAllowedException
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Q09: starting and scheduling download work any number of times, and what happens when Android refuses a foreground service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class DownloadSchedulingTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val storeField=OfflineMusicStore::class.java.getDeclaredField("instance").apply {isAccessible=true}
    private val scheduler get()=context.getSystemService(JobScheduler::class.java)
    private val settings get()=context.getSharedPreferences("offline_settings",Context.MODE_PRIVATE)
    private val store get()=OfflineMusicStore.get(context)
    private fun job(id:Int):JobInfo?=scheduler.getPendingJob(id)
    private fun events()=PlaybackEventLog(context).recent().map {it.substringAfter(' ')}.filter {it.startsWith("download_")}

    @Before fun fresh() {storeField.set(null,null);scheduler.cancelAll()}
    @After fun restore() {runCatching {YouTubeMusicProvider.shared(context).close()};storeField.set(null,null)}

    @Test fun schedulingTheSameWorkAgainLeavesTheJobThatIsThere() {
        settings.edit().putBoolean("smart_enabled",true).commit()
        OfflineDownloadScheduler.schedule(context,immediate=true)
        val periodic=job(OfflineDownloadScheduler.SMART)!!;val now=job(OfflineDownloadScheduler.SMART_NOW)!!
        assertTrue(periodic.isPeriodic);assertTrue(periodic.isPersisted);assertEquals(6*60*60_000L,periodic.intervalMillis)
        assertTrue(periodic.extras.getBoolean("automatic"));assertTrue(periodic.isRequireBatteryNotLow);assertTrue(periodic.isRequireStorageNotLow)
        // JobScheduler replaces, and stops, a job scheduled again under its id. Asking again must not do that.
        repeat(5) {OfflineDownloadScheduler.schedule(context,immediate=true)}
        assertSame(periodic,job(OfflineDownloadScheduler.SMART));assertSame(now,job(OfflineDownloadScheduler.SMART_NOW))

        OfflineDownloadScheduler.resumeManualLater(context)
        val manual=job(OfflineDownloadScheduler.MANUAL)!!
        assertFalse(manual.extras.getBoolean("automatic",false));assertTrue(manual.isPersisted);assertFalse(manual.isRequireBatteryNotLow)
        repeat(5) {OfflineDownloadScheduler.resumeManualLater(context)}
        assertSame(manual,job(OfflineDownloadScheduler.MANUAL))
        assertEquals(3,scheduler.allPendingJobs.size)
    }
    @Test fun aChangedConditionReplacesTheJobAndSwitchingOffCancelsOnlySmartDownloads() {
        settings.edit().putBoolean("smart_enabled",true).commit()
        OfflineDownloadScheduler.schedule(context);OfflineDownloadScheduler.resumeManualLater(context)
        val wifiOnly=job(OfflineDownloadScheduler.SMART)!!;val manual=job(OfflineDownloadScheduler.MANUAL)!!
        assertFalse(wifiOnly.extras.getBoolean("mobile"));assertNull("Only when asked to run now",job(OfflineDownloadScheduler.SMART_NOW))
        settings.edit().putBoolean("allow_mobile_data",true).commit()
        OfflineDownloadScheduler.schedule(context)
        val anyNetwork=job(OfflineDownloadScheduler.SMART)!!
        assertNotSame(wifiOnly,anyNetwork);assertTrue(anyNetwork.extras.getBoolean("mobile"))
        settings.edit().putBoolean("smart_enabled",false).commit()
        OfflineDownloadScheduler.schedule(context)
        assertNull(job(OfflineDownloadScheduler.SMART));assertNull(job(OfflineDownloadScheduler.SMART_NOW))
        assertSame("Downloads asked for by hand keep their job",manual,job(OfflineDownloadScheduler.MANUAL))
    }
    @Test fun whenAndroidRefusesAForegroundServiceTheQueuedSongsWaitForTheJob() {
        val refusing=object:ContextWrapper(context) {
            override fun startForegroundService(service:Intent):ComponentName=throw ForegroundServiceStartNotAllowedException("fixture: started from the background")
        }
        store.enqueue(ProviderTrack("schedule001","Song","Artist"),scope=store.currentScope)
        assertFalse(DownloadCoordinator.start(refusing))
        assertNotNull(job(OfflineDownloadScheduler.MANUAL))
        assertEquals(DownloadState.Queued,store.song(store.currentScope,"schedule001")!!.status)
        assertEquals(listOf("download_service_refused kind=ForegroundServiceStartNotAllowedException"),events())
    }
    @Test fun startingTwiceStartsOneServiceRequestEachAndKeepsOneJob() {
        assertTrue(DownloadCoordinator.start(context));val manual=job(OfflineDownloadScheduler.MANUAL)
        assertTrue(DownloadCoordinator.start(context))
        assertSame(manual,job(OfflineDownloadScheduler.MANUAL))
        assertEquals(OfflineDownloadService::class.java.name,shadowOf(context as android.app.Application).nextStartedService.component!!.className)
        assertEquals(emptyList<String>(),events())
    }
    @Test fun pauseFromTheNotificationPausesWhatTheUserAskedForAndNothingElse() {
        val scope=store.currentScope
        store.enqueue(ProviderTrack("schedule001","Song","Artist"),scope=scope)
        store.enqueue(ProviderTrack("schedule002","Song","Artist"),scope=scope);val ticket=store.beginDownload(scope,"schedule002")!!
        store.enqueue(ProviderTrack("schedule003","Song","Artist"),pinned=false,scope=scope)
        store.enqueue(ProviderTrack("schedule004","Song","Artist"),scope="another-account")
        val service=Robolectric.buildService(OfflineDownloadService::class.java,Intent(context,OfflineDownloadService::class.java).setAction("pause")).create()
        service.startCommand(0,1)
        assertEquals(listOf("paused","paused","queued"),listOf("schedule001","schedule002","schedule003").map {store.song(scope,it)!!.state})
        assertFalse("The transfer in flight lost its row",store.holds(scope,"schedule002",ticket))
        assertEquals(DownloadState.Queued,store.song("another-account","schedule004")!!.status)
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
    }
}
