package com.musicglass.desktop.automix

import ai.onnxruntime.*
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.*
import kotlinx.coroutines.CancellationException

/** Owned, real CPU session using exactly Android's approved model/front end and two inference threads. */
internal class DesktopBeatRuntime:BeatLogits,AutoCloseable {
    private val env=OrtEnvironment.getEnvironment().also{it.setTelemetry(false)}
    private val watchdog=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"RiffBeatWatchdog").apply{isDaemon=true}}
    private val guard=java.lang.Object()
    private var active:OrtSession.RunOptions?=null
    private var closed=false
    @Volatile var successfulRuns=0L;private set
    @Volatile var failedRuns=0L;private set
    private val session:OrtSession
    init{
        try{
            val bytes=checkNotNull(javaClass.getResourceAsStream("/automix/beat-this-small0-int8.onnx")){"The approved beat model is not bundled"}.use{it.readNBytes(4655417)}
            check(bytes.size==4655416&&MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}=="0e8b1cf3608dba5459826e940df4f15982a06e420e7a4d2b0a1a04acc587e32c"){"Beat model integrity check failed"}
            session=OrtSession.SessionOptions().use{options->options.setIntraOpNumThreads(2);options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);env.createSession(bytes,options)}
        }catch(error:Throwable){watchdog.shutdownNow();throw error}
    }
    override fun run(spect:FloatArray,frames:Int):Pair<FloatArray,FloatArray>{
        require(frames in 1..1500&&spect.size==frames*MODEL_BANDS&&spect.all{it.isFinite()})
        if(Thread.currentThread().isInterrupted)throw CancellationException("Beat analysis cancelled")
        val thread=Thread.currentThread();val deadline=System.nanoTime()+30_000_000_000L
        OrtSession.RunOptions().use{options->
            synchronized(guard){check(!closed&&active==null){"Beat session closed or already running"};active=options}
            val stop=watchdog.scheduleAtFixedRate({if(thread.isInterrupted||System.nanoTime()>=deadline)runCatching{options.setTerminate(true)}},100,100,TimeUnit.MILLISECONDS)
            try{
                OnnxTensor.createTensor(env,FloatBuffer.wrap(spect),longArrayOf(1,frames.toLong(),MODEL_BANDS.toLong())).use{input->
                    session.run(mapOf("spect" to input),options).use{output->
                        @Suppress("UNCHECKED_CAST") val beat=(output[0].value as Array<FloatArray>)[0].copyOf()
                        @Suppress("UNCHECKED_CAST") val down=(output[1].value as Array<FloatArray>)[0].copyOf()
                        check(beat.size==frames&&down.size==frames&&beat.all{it.isFinite()}&&down.all{it.isFinite()}){"Invalid beat model output"}
                        if(thread.isInterrupted)throw CancellationException("Beat analysis cancelled")
                        successfulRuns++
                        return beat to down
                    }
                }
            }catch(error:Exception){failedRuns++;if(thread.isInterrupted)throw CancellationException("Beat analysis cancelled").apply{initCause(error)};throw error}
            finally{stop.cancel(false);synchronized(guard){active=null;guard.notifyAll()}}
        }
    }
    override fun close(){
        synchronized(guard){
            if(closed)return
            closed=true;active?.let{runCatching{it.setTerminate(true)}}
            val deadline=System.nanoTime()+35_000_000_000L
            while(active!=null){val left=deadline-System.nanoTime();check(left>0){"Beat runtime did not terminate"};TimeUnit.NANOSECONDS.timedWait(guard,left)}
        }
        try{session.close()}finally{watchdog.shutdownNow()}
    }
}
