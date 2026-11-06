package com.musicglass.desktop

import kotlinx.serialization.json.*

/** One command implementation shared by the actual worker and explicitly injected test runtimes. */
internal class DesktopAudioRuntime(
    emit: (AudioEvent) -> Unit,
    outputFactory: () -> PcmOutput = { WasapiPcmOutput() },
    decoderFactory: PcmDecoderFactory = PcmDecoderFactory { FfmpegPcmDecoder(it) }
) : AutoCloseable {
    private val decoderFactory = decoderFactory
    private val mixer = DesktopDualDeckMixer(outputFactory, decoderFactory,
        onClockAnchor = emit, onSpectrumFrame = emit, onTrackTransition = emit,
        onBufferingState = emit, onFault = emit, onPlaybackState = emit)
    fun execute(command: AudioCommand): AudioResult = try {
        var details: String? = null
        when (command) {
            is AudioCommand.Handshake -> {
                require(command.clientBuild == AudioProtocol.BUILD_ID) { "Incompatible worker protocol" }
                details = AudioProtocol.BUILD_ID
            }
            is AudioCommand.LoadQueue -> {
                mixer.loadQueue(command.trackIds, command.startIndex, command.startPositionMs, command.secondaryPositionMs, command.crossfadeEnabled, command.repeatMode)
                details = buildJsonObject { put("durationMs", mixer.getDurationMs());put("codec",mixer.getCodecName()) }.toString()
            }
            is AudioCommand.SetPlaying -> {
                mixer.setPlaying(command.isPlaying)
                details=buildJsonObject{
                    put("isPlaying",mixer.isPlaying());put("positionMs",mixer.getCurrentPositionMs())
                    val anchor=mixer.checkpoint()
                    put("entryId",anchor?.currentEntryId);put("secondary",anchor?.secondaryEntryId);put("secondaryPositionMs",anchor?.secondaryPositionMs?:0)
                }.toString()
            }
            is AudioCommand.Seek -> {mixer.seek(command.positionMs, command.timelineEpoch);details=buildJsonObject{put("positionMs",mixer.getCurrentPositionMs())}.toString()}
            is AudioCommand.SetSleepTimer -> mixer.configureSleep(command.plan)
            is AudioCommand.SetVolume -> mixer.volume = command.volume
            is AudioCommand.SetPlaybackRate -> mixer.playbackRate = command.rate
            is AudioCommand.SetCanonicalDSP -> mixer.configureCanonicalDsp(command.equalizer,command.boost,command.trim,command.normalize)
            is AudioCommand.SetDSPConfig -> {
                require(command.eqBandsDb.size==10 && command.volumeBoostGain.isFinite() && command.volumeBoostGain in 1f..3f && command.normalizeTrim.isFinite() && command.normalizeTrim in .1f..1f && command.bassCutDb==0f){"Unsupported DSP parameter or range"}
                command.eqBandsDb.copyInto(mixer.equalizer.bandsDb)
                mixer.equalizer.enabled = command.equalizerEnabled
                mixer.volumeBoost.targetGain = command.volumeBoostGain
                mixer.volumeBoost.trim = command.normalizeTrim
                mixer.configureDsp()
            }
            is AudioCommand.PrepareSource -> {mixer.prepareSource(command.trackId,command.streamUrl,command.playbackRate,command.transitionHints)}
            is AudioCommand.CancelPendingOperation -> mixer.cancelPendingOperation()
            is AudioCommand.QueryDiagnostics -> details=mixer.diagnostics().toString()
            is AudioCommand.ProbeMedia -> {
                require(!command.source.startsWith("youtube:")){"Probe requires a local file"}
                decoderFactory.open(command.source).use{decoder->
                    val first=FloatArray(2048);check(decoder.read(first,1024)>0){"Audio has no frames"}
                    val bytes=decoder.artworkBytes
                    val art=bytes?.let{encoded->
                        require(encoded.size<=16*1024*1024)
                        val key=java.security.MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(""){"%02x".format(it)}
                        val directory=java.io.File(DesktopPaths.root,"artwork-incoming").apply{mkdirs()}
                        val target=java.io.File(directory,"$key.encoded")
                        if(!target.exists()) {
                            val temporary=java.io.File(directory,"$key.${java.util.UUID.randomUUID()}.tmp")
                            try{temporary.writeBytes(encoded);java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)}finally{temporary.delete()}
                        }
                        target.path
                    }
                    details=buildJsonObject{put("durationMs",decoder.durationMs);put("codec",decoder.codecName);put("title",decoder.tags.getOrNull(0));put("artist",decoder.tags.getOrNull(1));put("album",decoder.tags.getOrNull(2));put("artworkFile",art)}.toString()
                }
            }
            is AudioCommand.Shutdown -> mixer.setPlaying(false)
        }
        AudioResult(command.requestId, true, mixer.getTimelineEpoch(), details = details)
    } catch (t: Throwable) {
        if(t is VirtualMachineError || t is ThreadDeath)throw t
        val causes=generateSequence<Throwable>(t){it.cause}.toList()
        val failure=causes.filterIsInstance<AudioOperationException>().firstOrNull()
        val cause=causes.last()
        if(cause is java.util.concurrent.TimeoutException)mixer.signalTimeout()
        val code=failure?.code?:when(cause){is java.util.concurrent.TimeoutException->"ENGINE_TIMEOUT";is LinkageError->"NATIVE_DEPENDENCY_UNAVAILABLE";is java.io.IOException->"SOURCE_IO_FAILED";is InterruptedException->"CANCELLED";else->"AUDIO_COMMAND_REJECTED"}
        val message=failure?.message?:"${cause.javaClass.simpleName}: ${if(cause is IllegalArgumentException || cause is IllegalStateException)cause.message.orEmpty().take(160) else "Audio source or output operation failed"}"
        AudioResult(command.requestId,false,mixer.getTimelineEpoch(),message,
            buildJsonObject{put("errorCode",code);put("retryable",code in setOf("AUDIO_DEVICE_LOST","SOURCE_IO_FAILED"))}.toString())
    }
    override fun close() = mixer.close()
}