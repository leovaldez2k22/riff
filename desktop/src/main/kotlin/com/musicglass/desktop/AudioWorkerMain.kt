package com.musicglass.desktop

import java.io.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object AudioWorkerMain {
    @JvmStatic fun main(args: Array<String>) {
        if(args.isNotEmpty()) {
            require(args.size==2 && args[0]=="--data-dir")
            System.setProperty("riff.data.dir",File(args[1]).canonicalPath)
        }
        val metadata=java.util.concurrent.ThreadPoolExecutor(2,2,0,java.util.concurrent.TimeUnit.MILLISECONDS,java.util.concurrent.ArrayBlockingQueue(32),{r->Thread(r,"RiffMetadataProbe").apply{isDaemon=true;priority=Thread.MIN_PRIORITY}},java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
        val longOperations=java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),{r->Thread(r,"RiffLongAudioOperation").apply{isDaemon=true}},java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
        var activeOperation:java.util.concurrent.FutureTask<Unit>?=null
        val running = AtomicBoolean(true)
        val reliable = ArrayBlockingQueue<AudioIpcTransport.IpcMessage>(256)
        val latestClock = AtomicReference<AudioEvent.ClockAnchor?>()
        val latestSpectrum = AtomicReference<AudioEvent.FeatureSpectrumFrame?>()
        val output = DataOutputStream(BufferedOutputStream(System.out))
        val writer = Thread({
            try {
                while (running.get() || reliable.isNotEmpty()) {
                    reliable.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { message -> when (message) {
                        is AudioIpcTransport.IpcMessage.Result -> AudioIpcTransport.writeResult(output, message.result)
                        is AudioIpcTransport.IpcMessage.Event -> AudioIpcTransport.writeEvent(output, message.event)
                    } }
                    latestClock.getAndSet(null)?.let { AudioIpcTransport.writeEvent(output, it) }
                    latestSpectrum.getAndSet(null)?.let { AudioIpcTransport.writeEvent(output, it) }
                }
            } catch (_: Exception) { running.set(false) }
        }, "RiffWorkerEvents").apply { isDaemon = true; start() }
        try {
            DesktopProvider(File(DesktopPaths.root, "provider")).use { provider ->
            DesktopAudioRuntime({ event -> when (event) {
                is AudioEvent.ClockAnchor -> latestClock.set(event)
                is AudioEvent.FeatureSpectrumFrame -> latestSpectrum.set(event)
                else -> check(reliable.offer(AudioIpcTransport.IpcMessage.Event(event))) { "Worker event queue full" }
            } }, decoderFactory = PcmDecoderFactory { source ->
                if(source.startsWith("youtube:")) FfmpegPcmDecoder(kotlinx.coroutines.runBlocking { provider.openAudio(source.removePrefix("youtube:")) })
                else FfmpegPcmDecoder(source)
            }).use { runtime ->
                val input = DataInputStream(BufferedInputStream(System.`in`))
                var handshake = false
                while (running.get()) {
                    val command = try { AudioIpcTransport.readCommand(input) } catch (_: EOFException) { break }
                    if(command is AudioCommand.CancelPendingOperation&&handshake){activeOperation?.cancel(true)}
                    if(handshake&&(command is AudioCommand.LoadQueue||command is AudioCommand.Seek)){
                        activeOperation?.cancel(true);longOperations.purge()
                        val delivered=AtomicBoolean()
                        fun deliver(result:AudioResult){if(delivered.compareAndSet(false,true))check(reliable.offer(AudioIpcTransport.IpcMessage.Result(result),2,java.util.concurrent.TimeUnit.SECONDS))}
                        val task=object:java.util.concurrent.FutureTask<Unit>(java.util.concurrent.Callable{deliver(runtime.execute(command));Unit}){
                            override fun done(){if(isCancelled)deliver(AudioResult(command.requestId,false,0,"Audio operation was superseded"))}
                        }
                        activeOperation=task;longOperations.execute(task);continue
                    }
                    if(command is AudioCommand.ProbeMedia && handshake) {
                        try {metadata.execute{val result=runtime.execute(command);check(reliable.offer(AudioIpcTransport.IpcMessage.Result(result),2,java.util.concurrent.TimeUnit.SECONDS))}}
                        catch(_:java.util.concurrent.RejectedExecutionException){reliable.offer(AudioIpcTransport.IpcMessage.Result(AudioResult(command.requestId,false,0,"Metadata probe queue is busy")))}
                        continue
                    }
                    val result = if (!handshake && command !is AudioCommand.Handshake) AudioResult(command.requestId, false, 0, "Handshake required")
                        else runtime.execute(command)
                    if (command is AudioCommand.Handshake) handshake = result.success
                    check(reliable.offer(AudioIpcTransport.IpcMessage.Result(result), 2, java.util.concurrent.TimeUnit.SECONDS))
                    if (command is AudioCommand.Shutdown) break
                }
            }
            }
        } catch (t: Throwable) { System.err.println("RIFF_WORKER_FAILED ${t.javaClass.simpleName}") }
        finally { activeOperation?.cancel(true);longOperations.shutdownNow();longOperations.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);metadata.shutdownNow();metadata.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);running.set(false); writer.join(3000); output.flush() }
    }
}