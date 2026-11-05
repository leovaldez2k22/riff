package com.musicglass.personal

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.sync.Mutex
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicLong

/** Diagnostic-only: no URL, media id, cookies, exception messages or user metadata. */
internal object StartupDiagnostics {
    @Volatile var enabled = BuildConfig.DEBUG
    private val ids = AtomicLong()
    private val epoch = SystemClock.elapsedRealtimeNanos() / 1000
    private val identity = "pid=${android.os.Process.myPid()} epoch=$epoch"
    @Volatile private var traceQueue: java.util.concurrent.ArrayBlockingQueue<String>? = null
    private val traceDropped = AtomicLong()

    /** Explicit diagnostic builds only. No main-thread disk writes or periodic keepalive. */
    fun installTraceFile(context: android.content.Context, role: String) {
        if (!enabled || !BuildConfig.STARTUP_TRACE_FILE || role !in setOf("main", "solver", "other")) return
        if (traceQueue != null) return
        val queue = java.util.concurrent.ArrayBlockingQueue<String>(512)
        traceQueue = queue
        kotlin.concurrent.thread(name = "riff-startup-trace", isDaemon = true) {
            try {
                val directory = java.io.File(context.cacheDir, "startup-trace")
                check(directory.isDirectory || directory.mkdirs())
                java.io.RandomAccessFile(java.io.File(directory, "$role.log"), "rw").use { file ->
                    file.setLength(0)
                    fun append(line: String) {
                        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                        if (file.filePointer + bytes.size > 1024 * 1024) { file.setLength(0); file.seek(0) }
                        file.write(bytes)
                    }
                    while (true) {
                        val event = queue.take()
                        val lost = traceDropped.getAndSet(0)
                        if (lost > 0) append(DiagnosticEvent.parse("startup_trace_dropped count=$lost").text)
                        append(event)
                    }
                }
            } catch (_: Exception) { traceQueue = null }
        }
    }

    private fun writeLine(raw: String) {
        val line = DiagnosticEvent.parse(raw).text
        Log.w("RiffStartup", line)
        traceQueue?.let { if (!it.offer(line)) traceDropped.incrementAndGet() }
    }
    @Volatile private var request = 0L
    enum class Kind { Ui, Source, Preload, Provider, Session, Catalog, Player, Config, Token, Solver, Media, Audio }
    enum class Stage {
        Tap, MiniTap, Dispatch, EntryState, ServiceState, Background, Foreground, ServiceCreate, ServiceDestroy,
        SolverCreate, SolverDestroy, ServiceForeground, QueueRestore, QueueEncode, QueueDecode, QueueApply, Resolve, FlightWait, Warmup,
        TokenWatch, TokenInterpreter, TokenBrowser, TokenIntegrity, TokenMinter, TokenReady, TokenLifetime, UrlCache, SolverIdle, RequestHeaders, ResponseHeadersStart,
        TokenLock, TokenWork, Javascript, WebviewLoad, SolverBind, SolverRpc,
        SolverReceived, CipherCall, SourceGate, HttpCall, DnsStart, DnsEnd,
        ConnectStart, ConnectEnd, TlsStart, TlsEnd, Acquired, Headers,
        ConnectionReleased, HttpFailed, FirstByte, Decoder, AudioAdvancing,
        Playing, Suppressed, AudioSinkError, ExtractorConfig, ExtractorPlayers,
        ExtractorCipher, ExtractorWarm, EjsEvaluate, CipherStart, CipherDone
    }
    // index is process-local context, NOT a propagated request ID. Capture one deliberate tap per run.
    fun request(stage: Stage = Stage.Tap) {
        if (!enabled) return
        request = ids.incrementAndGet(); point(stage, Kind.Ui)
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun state(stage: Stage, player: Player) {
        if (!enabled) return
        val scheme = player.currentMediaItem?.localConfiguration?.uri?.scheme
        val source = when (scheme) {
            "catalog" -> "catalog"
            "file", "content" -> "local"
            "https", "http" -> "direct"
            "sabr" -> "sabr"
            null -> "unknown" // Controllers may omit localConfiguration; do not infer a direct URL.
            else -> "other"
        }
        writeLine("startup index=$request lookup=${ids.incrementAndGet()} $identity stage=${stage.name.lowercase()} kind=source phase=mark at=${SystemClock.elapsedRealtimeNanos() / 1000} " +
            "state=${player.playbackState} buffered_ms=${player.bufferedPosition} ahead_ms=${player.totalBufferedDuration} position_ms=${player.currentPosition} count=${player.mediaItemCount} queue_index=${player.currentMediaItemIndex} suppression=${player.playbackSuppressionReason} play_when_ready=${player.playWhenReady} source=$source")
    }

    /** Own services only. This is an observed foreground flag, not a process-death diagnosis. */
    @Suppress("DEPRECATION")
    fun services(context: android.content.Context) {
        if (!enabled) return
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        manager.getRunningServices(Int.MAX_VALUE).filter { it.uid == android.os.Process.myUid() }.forEach { service ->
            val kind = when (service.service.className) {
                MusicPlaybackService::class.java.name -> Kind.Source
                SolverService::class.java.name -> Kind.Solver
                else -> return@forEach
            }
            point(Stage.ServiceForeground, kind, if (service.foreground) 1 else 0)
        }
    }
    class Span internal constructor(val stage: Stage, val kind: Kind) {
        private val parent = request
        private val id = ids.incrementAndGet()
        private val began = SystemClock.elapsedRealtimeNanos()
        init { emit("begin", began, 0, true) }
        private fun emit(phase: String, at: Long, count: Long, ok: Boolean) {
            if (!enabled) return
            val ms = java.lang.String.format(java.util.Locale.ROOT, "%.3f", (at - began) / 1_000_000.0)
            val line = "startup index=$parent lookup=$id $identity stage=${stage.name.lowercase()} kind=${kind.name.lowercase()} phase=$phase at=${at / 1000} ms=$ms count=$count result=${if (ok) "ok" else "failed"}"
            writeLine(line)
        }
        fun mark(stage: Stage, count: Long = 0) {
            if (!enabled) return
            val at = SystemClock.elapsedRealtimeNanos()
            writeLine("startup index=$parent lookup=$id $identity stage=${stage.name.lowercase()} kind=${kind.name.lowercase()} phase=mark at=${at / 1000} count=$count")
        }
        fun finish(ok: Boolean = true) = emit("end", SystemClock.elapsedRealtimeNanos(), 0, ok)
    }
    fun begin(stage: Stage, kind: Kind) = Span(stage, kind)
    fun point(stage: Stage, kind: Kind, count: Long = 0) {
        if (!enabled) return
        begin(stage, kind).let { it.mark(stage, count); it.finish() }
    }
    fun <T> measure(stage: Stage, kind: Kind, block: () -> T): T {
        if (!enabled) return block()
        val span = begin(stage, kind)
        var ok = false
        try { return block().also { ok = true } } finally { span.finish(ok) }
    }
    suspend fun <T> await(stage: Stage, kind: Kind, block: suspend () -> T): T {
        if (!enabled) return block()
        val span = begin(stage, kind)
        var ok = false
        try { return block().also { ok = true } } finally { span.finish(ok) }
    }
    suspend fun <T> locked(mutex: Mutex, stage: Stage, kind: Kind, block: suspend () -> T): T {
        await(stage, kind) { mutex.lock() }
        try { return block() } finally { mutex.unlock() }
    }
    private fun httpKind(call: Call, fallback: Kind): Kind {
        if (fallback == Kind.Media) return fallback
        val path = call.request().url.encodedPath
        return when {
            path.endsWith("/account_menu") -> Kind.Session
            path.endsWith("/player") -> Kind.Player
            path.endsWith("/browse") || path.endsWith("/search") -> Kind.Catalog
            path == "/watch" || path.startsWith("/s/player/") -> Kind.Config
            path == "/api/jnn/v1/GenerateIT" -> Kind.Token
            else -> fallback
        }
    }
    fun httpEvents(kind: Kind) = EventListener.Factory { call ->
        if (!enabled) EventListener.NONE else object : EventListener() {
            private val span = begin(Stage.HttpCall, httpKind(call, kind))
            private var connected = false
            override fun dnsStart(call: Call, domainName: String) = span.mark(Stage.DnsStart)
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = span.mark(Stage.DnsEnd)
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = span.mark(Stage.ConnectStart)
            override fun secureConnectStart(call: Call) = span.mark(Stage.TlsStart)
            override fun secureConnectEnd(call: Call, handshake: Handshake?) = span.mark(Stage.TlsEnd)
            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { connected = true; span.mark(Stage.ConnectEnd) }
            // count=0: pooled/shared connection; count=1: a connect completed during this call.
            override fun connectionAcquired(call: Call, connection: Connection) = span.mark(Stage.Acquired, if (connected) 1 else 0)
            override fun requestHeadersEnd(call: Call, request: Request) = span.mark(Stage.RequestHeaders)
            override fun responseHeadersStart(call: Call) = span.mark(Stage.ResponseHeadersStart)
            override fun responseHeadersEnd(call: Call, response: Response) = span.mark(Stage.Headers, response.code.toLong())
            override fun connectionReleased(call: Call, connection: Connection) = span.mark(Stage.ConnectionReleased)
            override fun callEnd(call: Call) = span.finish()
            override fun callFailed(call: Call, ioe: IOException) { span.mark(Stage.HttpFailed); span.finish(false) }
        }
    }
    fun extractorStep(event: com.metrolist.innertubex.InnerTubeLogEvent) {
        val stage = when (event.message) {
            "embedded config ready", "watch page config ready" -> Stage.ExtractorConfig
            "player responses received" -> Stage.ExtractorPlayers
            "cipher processing completed", "cipher fallback completed" -> Stage.ExtractorCipher
            "prewarm completed" -> Stage.ExtractorWarm
            else -> return
        }
        point(stage, Kind.Provider, event.details["elapsedMs"]?.toLongOrNull() ?: 0)
    }
    fun cipherStep(message: String) {
        val stage = when {
            message.startsWith("processFormats start ") -> Stage.CipherStart
            message.startsWith("processFormats done ") -> Stage.CipherDone
            message.startsWith("EJS evaluate done ") -> Stage.EjsEvaluate
            else -> return
        }
        point(stage, Kind.Solver)
    }
}

@androidx.media3.common.util.UnstableApi
internal class StartupAudioListener : AnalyticsListener {
    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
        StartupDiagnostics.point(StartupDiagnostics.Stage.Decoder, StartupDiagnostics.Kind.Audio, initializationDurationMs)
    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) =
        StartupDiagnostics.point(StartupDiagnostics.Stage.AudioAdvancing, StartupDiagnostics.Kind.Audio, playoutStartSystemTimeMs)
    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) =
        StartupDiagnostics.point(StartupDiagnostics.Stage.Playing, StartupDiagnostics.Kind.Audio, if (isPlaying) 1 else 0)
    override fun onPlaybackSuppressionReasonChanged(eventTime: AnalyticsListener.EventTime, playbackSuppressionReason: Int) =
        StartupDiagnostics.point(StartupDiagnostics.Stage.Suppressed, StartupDiagnostics.Kind.Audio, playbackSuppressionReason.toLong())
    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) =
        StartupDiagnostics.point(StartupDiagnostics.Stage.AudioSinkError, StartupDiagnostics.Kind.Audio)
}
