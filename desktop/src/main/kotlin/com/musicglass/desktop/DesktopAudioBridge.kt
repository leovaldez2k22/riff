package com.musicglass.desktop

import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Persistent worker connection. A missing/crashed worker never selects in-process playback. */
internal class DesktopAudioBridge(
    private val workerClassPath:String=System.getProperty("java.class.path"),
    private val runtimeFactory: (((AudioEvent) -> Unit) -> DesktopAudioRuntime)? = null
) : AutoCloseable {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<AudioResult>>()
    private val lifecycle = Any()
    private val writeLock = Any()
    @Volatile private var closed = false
    private var worker: Process? = null
    private var generation = 0L
    val workerPid: Long? get() = worker?.pid()
    private var output: DataOutputStream? = null
    private var local: DesktopAudioRuntime? = null
    private val mutableClock = MutableStateFlow<AudioEvent.ClockAnchor?>(null)
    val clockAnchor: StateFlow<AudioEvent.ClockAnchor?> = mutableClock
    private val mutableSpectrum = MutableStateFlow<AudioEvent.FeatureSpectrumFrame?>(null)
    val spectrumFrame: StateFlow<AudioEvent.FeatureSpectrumFrame?> = mutableSpectrum
    private val mutableTransition = MutableStateFlow<AudioEvent.TrackTransition?>(null)
    val trackTransition: StateFlow<AudioEvent.TrackTransition?> = mutableTransition
    private val mutableFault = MutableStateFlow<AudioEvent.Fault?>(null)
    val faultEvent: StateFlow<AudioEvent.Fault?> = mutableFault
    private val mutableBuffering=MutableStateFlow<AudioEvent.BufferingState?>(null)
    val bufferingState:StateFlow<AudioEvent.BufferingState?> = mutableBuffering
    private val mutablePlayback = MutableStateFlow<AudioEvent.PlaybackStateChanged?>(null)
    val playbackState: StateFlow<AudioEvent.PlaybackStateChanged?> = mutablePlayback
    private fun event(value: AudioEvent) {
        when (value) {
            is AudioEvent.ClockAnchor -> mutableClock.value = value
            is AudioEvent.FeatureSpectrumFrame -> mutableSpectrum.value = value
            is AudioEvent.TrackTransition -> mutableTransition.value = value
            is AudioEvent.Fault -> mutableFault.value = value
            is AudioEvent.PlaybackStateChanged -> mutablePlayback.value = value
            is AudioEvent.BufferingState -> mutableBuffering.value=value
            else -> Unit
        }
    }
    private fun ensureWorker() = synchronized(lifecycle) {
        check(!closed) { "Audio bridge is closed" }
        if (runtimeFactory != null) { if (local == null) local = runtimeFactory.invoke(::event); return@synchronized }
        if (worker?.isAlive == true) return@synchronized
        check(worker == null) { "Audio worker exited; recreate the connection before retrying" }
        val appExecutable = System.getProperty("jpackage.app-path")?.let(::File)
        val configured = System.getProperty("riff.worker.path")?.let(::File)
        val launcher = configured ?: appExecutable?.parentFile?.resolve("RiffAudioWorker.exe")
        val process = if (launcher != null) {
            check(launcher.isFile) { "Packaged audio worker launcher is missing" }
            ProcessBuilder(launcher.absolutePath,"--data-dir",DesktopPaths.root.absolutePath)
        } else {
            val java = File(System.getProperty("java.home"), "bin/java.exe")
            check(java.isFile) { "No packaged worker launcher or development Java launcher" }
            ProcessBuilder(java.absolutePath, "-Driff.native.dir=${checkNotNull(System.getProperty("riff.native.dir"))}",
                "-Driff.data.dir=${DesktopPaths.root.absolutePath}", "-cp", workerClassPath, "com.musicglass.desktop.AudioWorkerMain")
        }.redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val connectionGeneration=++generation
        worker = process
        output = DataOutputStream(BufferedOutputStream(process.outputStream))
        Thread({
            try {
                DataInputStream(BufferedInputStream(process.inputStream)).use { input ->
                    while (!closed) when (val message = AudioIpcTransport.readMessage(input)) {
                        is AudioIpcTransport.IpcMessage.Result -> pending.remove(message.result.requestId)?.complete(message.result)
                        is AudioIpcTransport.IpcMessage.Event -> if(connectionGeneration==generation) event(message.event)
                    }
                }
            } catch (t: Exception) {
                if (!closed && connectionGeneration==generation) event(AudioEvent.Fault(ids.incrementAndGet(), 0, "WORKER_LOST", "Audio worker connection was lost", true))
                if(connectionGeneration==generation) {pending.values.forEach { it.completeExceptionally(IOException("Audio worker disconnected")) }; pending.clear()}
            }
        }, "RiffWorkerReader").apply { isDaemon = true; start() }
    }
    fun sendCommand(command: AudioCommand): AudioResult = try {
        ensureWorker()
        local?.let { return it.execute(command) }
        val done = CompletableFuture<AudioResult>(); pending[command.requestId] = done
        try {
            synchronized(writeLock) { AudioIpcTransport.writeCommand(checkNotNull(output), command) }
            done.get(when(command){is AudioCommand.Shutdown->5;is AudioCommand.LoadQueue->180;else->120}, TimeUnit.SECONDS)
        } finally { pending.remove(command.requestId) }
    } catch (t: Exception) {
        AudioResult(command.requestId, false, 0, "Audio worker unavailable (${t.javaClass.simpleName})")
    }
    /** Fault recovery recreates only this app's owned worker; the controller replays durable intent paused. */
    fun restartWorker() = synchronized(lifecycle) {
        check(!closed)
        generation++
        output?.runCatching { close() };output=null
        worker?.let { it.destroy();if(!it.waitFor(2,TimeUnit.SECONDS))it.destroyForcibly().waitFor(2,TimeUnit.SECONDS) }
        worker=null;local?.close();local=null
        mutableClock.value=null;mutableSpectrum.value=null;mutableBuffering.value=null;mutablePlayback.value=null;mutableFault.value=null
        ensureWorker()
    }
    fun nextId(): Long = ids.incrementAndGet()
    override fun close() = synchronized(lifecycle) {
        if (closed) return@synchronized
        if (worker?.isAlive == true) sendCommand(AudioCommand.Shutdown(nextId()))
        closed = true; local?.close(); output?.close()
        worker?.let { if (!it.waitFor(5, TimeUnit.SECONDS)) { it.destroy(); if (!it.waitFor(2, TimeUnit.SECONDS)) it.destroyForcibly() } }
        pending.values.forEach { it.completeExceptionally(IOException("Audio bridge closed")) }; pending.clear()
    }
}