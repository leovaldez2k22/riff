package com.musicglass.personal

import android.app.*
import android.app.job.*
import android.content.*
import android.os.*
import android.net.NetworkCapabilities
import kotlinx.coroutines.*

internal object OfflineDownloadScheduler {
    private const val SMART=701
    private const val MANUAL=702
    fun schedule(context:Context,immediate:Boolean=false) {
        val scheduler=context.getSystemService(JobScheduler::class.java);val store=OfflineMusicStore.get(context)
        if(store.smartEnabled) {
            val component=ComponentName(context,OfflineDownloadJob::class.java)
            val network=if(store.allowMobileData) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED
            scheduler.schedule(JobInfo.Builder(SMART,component).setRequiredNetworkType(network).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true)
                .setPeriodic(6*60*60_000L,60*60_000L).setPersisted(true).setExtras(PersistableBundle().apply {putBoolean("automatic",true)}).build())
        if(immediate) scheduler.schedule(JobInfo.Builder(704,component).setRequiredNetworkType(network).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).setExtras(PersistableBundle().apply {putBoolean("automatic",true)}).build())
        } else {scheduler.cancel(SMART);scheduler.cancel(704)}
    }
    fun resumeManualLater(context:Context) {
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(MANUAL,ComponentName(context,OfflineDownloadJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresStorageNotLow(true).setMinimumLatency(30_000).setBackoffCriteria(60_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build())
    }
    fun startManual(context:Context,track:ProviderTrack) {
        OfflineMusicStore.get(context).enqueue(track)
        context.startForegroundService(Intent(context,OfflineDownloadService::class.java))
        resumeManualLater(context)
    }
}

class OfflineDownloadService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var work:Job?=null
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="pause") {
            val store=OfflineMusicStore.get(this);store.rows().filter {it.pinned && it.state in setOf("queued","downloading","assets")}.forEach {store.update(it.scope,it.track.videoId,"state" to "paused")}
            work?.cancel();stopSelf();return START_NOT_STICKY
        }
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("song-downloads","Song downloads",NotificationManager.IMPORTANCE_LOW))
        fun notification(message:String):Notification {
            val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val pause=PendingIntent.getService(this,1,Intent(this,OfflineDownloadService::class.java).setAction("pause"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(this,"song-downloads").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Riff downloads").setContentText(message).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Pause",pause).build()).build()
        }
        startForeground(703,notification("Preparing highest-quality audio"))
        if(work?.isActive!=true) work=scope.launch {
            try {val finished=OfflineDownloadEngine.run(this@OfflineDownloadService,false) {manager.notify(703,notification(it))};if(!finished) OfflineDownloadScheduler.resumeManualLater(this@OfflineDownloadService)}
            finally {stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int) {work?.cancel();stopSelf()}
    override fun onDestroy() {scope.cancel();super.onDestroy()}
}

class OfflineDownloadJob:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val jobs=mutableMapOf<Int,Job>()
    override fun onStartJob(params:JobParameters):Boolean {
        val automatic=params.extras.getBoolean("automatic",false)
        jobs[params.jobId]=scope.launch {
            var done=false
            try {done=withTimeout(8*60_000) {OfflineDownloadEngine.run(this@OfflineDownloadJob,automatic) {}}}
            catch(_:TimeoutCancellationException) {} catch(e:CancellationException) {throw e} catch(_:Exception) {}
            finally {jobs.remove(params.jobId);if(isActive) jobFinished(params,!done)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {jobs.remove(params.jobId)?.cancel();return true}
    override fun onDestroy() {scope.cancel();super.onDestroy()}
}
