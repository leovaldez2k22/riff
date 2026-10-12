package com.musicglass.personal

import android.app.*
import android.app.job.*
import android.content.*
import android.os.*
import kotlinx.coroutines.*

/**
 * Background work for downloads. Scheduling is idempotent: asking for a job that is already waiting or
 * running with the same conditions changes nothing. JobScheduler replaces, and stops, a job scheduled
 * again under the same id, so before Q09 every new request stopped the transfer in progress.
 */
internal object OfflineDownloadScheduler {
    internal const val SMART=701
    internal const val MANUAL=702
    internal const val SMART_NOW=704
    private fun ensure(scheduler:JobScheduler,job:JobInfo) {
        val pending=scheduler.getPendingJob(job.id)
        val same=pending!=null && pending.isPeriodic==job.isPeriodic &&
            pending.extras.getBoolean("automatic",false)==job.extras.getBoolean("automatic",false) &&
            pending.extras.getBoolean("mobile",false)==job.extras.getBoolean("mobile",false)
        if(!same) scheduler.schedule(job)
    }
    /** Smart Downloads: a periodic job about every six hours, best effort; Android decides when it really runs. */
    fun schedule(context:Context,immediate:Boolean=false) {
        val scheduler=context.getSystemService(JobScheduler::class.java);val store=OfflineMusicStore.get(context)
        if(store.smartEnabled) {
            val component=ComponentName(context,OfflineDownloadJob::class.java)
            val mobile=store.allowMobileData
            val network=if(mobile) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED
            fun extras()=PersistableBundle().apply {putBoolean("automatic",true);putBoolean("mobile",mobile)}
            ensure(scheduler,JobInfo.Builder(SMART,component).setRequiredNetworkType(network).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true)
                .setPeriodic(6*60*60_000L,60*60_000L).setPersisted(true).setExtras(extras()).build())
            if(immediate) ensure(scheduler,JobInfo.Builder(SMART_NOW,component).setRequiredNetworkType(network).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).setExtras(extras()).build())
        } else {scheduler.cancel(SMART);scheduler.cancel(SMART_NOW)}
    }
    /** Downloads asked for by hand continue here when the foreground service is not running: any network, storage not low. */
    fun resumeManualLater(context:Context) {
        ensure(context.getSystemService(JobScheduler::class.java),JobInfo.Builder(MANUAL,ComponentName(context,OfflineDownloadJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresStorageNotLow(true).setMinimumLatency(30_000).setBackoffCriteria(60_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build())
    }
}

/** Starts the work for songs that were just queued by hand. */
internal object DownloadCoordinator {
    /**
     * The foreground service when Android allows one, and always the job behind it. From Android 12 an app
     * in the background may not start a foreground service; the queued songs then wait for the job instead
     * of being lost. Returns whether the service was started.
     */
    fun start(context:Context):Boolean {
        val started=try {context.startForegroundService(Intent(context,OfflineDownloadService::class.java));true}
            catch(e:RuntimeException) {PlaybackEventLog(context).append("download_service_refused kind=${e::class.simpleName}");false}
        OfflineDownloadScheduler.resumeManualLater(context)
        return started
    }
}

class OfflineDownloadService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var work:Job?=null
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="pause") {
            // Every download asked for by hand in this account pauses; a later start begins each from its first byte.
            OfflineMusicStore.get(this).pauseManual()
            work?.cancel();stopSelf();return START_NOT_STICKY
        }
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("song-downloads","Song downloads",NotificationManager.IMPORTANCE_LOW))
        fun notification(message:String):Notification {
            val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val pause=PendingIntent.getService(this,1,Intent(this,OfflineDownloadService::class.java).setAction("pause"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(this,"song-downloads").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Riff downloads").setContentText(message).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Pause",pause).build()).build()
        }
        try {startForeground(703,notification("Preparing highest-quality audio"))}
        catch(e:RuntimeException) {
            // Android refused foreground work (started from the background, or the daily allowance is used up). The job carries on.
            PlaybackEventLog(this).append("download_foreground_refused kind=${e::class.simpleName}")
            OfflineDownloadScheduler.resumeManualLater(this);if(work?.isActive!=true) stopSelf();return START_NOT_STICKY
        }
        // A second start while a run is active changes nothing: the run picks up newly queued songs itself.
        if(work?.isActive!=true) work=scope.launch {
            try {val finished=OfflineDownloadEngine.run(this@OfflineDownloadService,false) {manager.notify(703,notification(it))};if(!finished) OfflineDownloadScheduler.resumeManualLater(this@OfflineDownloadService)}
            finally {stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
        }
        return START_NOT_STICKY
    }
    /** Android 15 limits how long this kind of foreground service may run in a day. What is left waits for the job. */
    override fun onTimeout(startId:Int,fgsType:Int) {
        PlaybackEventLog(this).append("download_foreground_timeout")
        work?.cancel();OfflineDownloadScheduler.resumeManualLater(this);stopSelf()
    }
    override fun onDestroy() {scope.cancel();super.onDestroy()}
}

class OfflineDownloadJob:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val jobs=mutableMapOf<Int,Job>()
    override fun onStartJob(params:JobParameters):Boolean {
        val automatic=params.extras.getBoolean("automatic",false)
        // Started twice for one id: the first run is still the run.
        if(jobs[params.jobId]?.isActive==true) return true
        jobs[params.jobId]=scope.launch {
            var done=false
            // Android stops a job after about ten minutes. Ending at eight leaves time to put the song in hand back in the queue.
            try {done=withTimeout(8*60_000) {OfflineDownloadEngine.run(this@OfflineDownloadJob,automatic) {}}}
            catch(_:TimeoutCancellationException) {} catch(e:CancellationException) {throw e} catch(_:Exception) {}
            finally {jobs.remove(params.jobId);if(isActive) jobFinished(params,!done)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {jobs.remove(params.jobId)?.cancel();return true}
    override fun onDestroy() {scope.cancel();super.onDestroy()}
}
