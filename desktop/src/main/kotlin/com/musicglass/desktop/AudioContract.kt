package com.musicglass.desktop

/**
 * Milestone 3: Typed IPC Command and Event Protocol between persistent Desktop Host and Audio Engine.
 * Conforms to Section 4.4 of Riff Windows desktop architecture.
 */

internal object AudioProtocol {
    const val MAJOR_VERSION = 2
    const val MINOR_VERSION = 7
    const val BUILD_ID = "riff-audio/2.7"
}

/** Envelope for commands sent from Desktop Host to the Audio Engine. */
internal sealed interface AudioCommand {
    val requestId: Long

    data class Handshake(
        override val requestId: Long,
        val clientBuild: String,
        val processNonce: Long
    ) : AudioCommand

    data class LoadQueue(
        override val requestId: Long,
        val trackIds: List<String>,
        val startIndex: Int = 0,
        val startPositionMs: Long = 0L,
        val secondaryPositionMs: Long = 0L,
        val crossfadeEnabled: Boolean = false,
        val repeatMode: RepeatMode = RepeatMode.Off
    ) : AudioCommand

    data class SetPlaying(
        override val requestId: Long,
        val isPlaying: Boolean
    ) : AudioCommand

    data class Seek(
        override val requestId: Long,
        val positionMs: Long,
        val timelineEpoch: Long
    ) : AudioCommand

    data class SetVolume(
        override val requestId: Long,
        val volume: Float // 0.0f .. 1.0f
    ) : AudioCommand

    data class SetPlaybackRate(
        override val requestId: Long,
        val rate: Float // 0.5f .. 2.0f
    ) : AudioCommand

    data class SetDSPConfig(
        override val requestId: Long,
        val equalizerEnabled: Boolean,
        val eqBandsDb: FloatArray,
        val volumeBoostGain: Float, // 1.0f .. 3.0f
        val normalizeTrim: Float,   // <= 1.0f
        val bassCutDb: Float = 0f
    ) : AudioCommand {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SetDSPConfig) return false
            return requestId == other.requestId &&
                   equalizerEnabled == other.equalizerEnabled &&
                   eqBandsDb.contentEquals(other.eqBandsDb) &&
                   volumeBoostGain == other.volumeBoostGain &&
                   normalizeTrim == other.normalizeTrim &&
                   bassCutDb == other.bassCutDb
        }
        override fun hashCode(): Int = requestId.hashCode()
    }

    data class PrepareSource(
        override val requestId: Long,
        val trackId: String,
        val streamUrl: String,
        val expiryEpochMs: Long,
        val playbackRate:Float=1f,
        val transitionHints:com.musicglass.desktop.automix.TransitionHints=com.musicglass.desktop.automix.TransitionHints()
    ) : AudioCommand

    data class CancelPendingOperation(override val requestId:Long):AudioCommand
    data class QueryDiagnostics(override val requestId:Long):AudioCommand
    data class SetSleepTimer(override val requestId:Long,val plan:SleepPlan):AudioCommand
    data class SetCanonicalDSP(override val requestId:Long,val equalizer:EqualizerConfig,val boost:Float=1f,val trim:Float=1f,val normalize:Boolean=false):AudioCommand
    data class ProbeMedia(override val requestId:Long,val source:String):AudioCommand
    data class Shutdown(override val requestId: Long) : AudioCommand
}

/** Acknowledgement result for commands. */
internal data class AudioResult(
    val requestId: Long,
    val success: Boolean,
    val appliedStateRevision: Long,
    val error: String? = null,
    val details: String? = null
)

/** Events emitted by the Audio Engine to the Desktop Host. */
internal sealed interface AudioEvent {
    val sequenceNumber: Long
    val timelineEpoch: Long

    data class PlaybackStateChanged(
        override val sequenceNumber: Long,
        override val timelineEpoch: Long,
        val isPlaying: Boolean,
        val currentTrackId: String?,
        val positionMs: Long
    ) : AudioEvent

    /** Authoritative Clock Anchor emitted at 5Ã¢â‚¬â€œ10 Hz and on discontinuities (seek, pause, device shift). */
    data class ClockAnchor(
        override val sequenceNumber: Long,
        override val timelineEpoch: Long,
        val contentPositionMs: Long,
        val monotonicTimestampNanos: Long,
        val sampleRate: Int,
        val samplesRendered: Long,
        val latencyEstimateMs: Float,
        val playbackRate: Float = 1.0f,
        val currentEntryId: String? = null,
        val secondaryEntryId: String? = null,
        val secondaryPositionMs: Long = 0L,
        val sleep:SleepPlan=SleepPlan()
    ) : AudioEvent

    data class TrackTransition(
        override val sequenceNumber: Long,
        override val timelineEpoch: Long,
        val completedTrackId: String,
        val nextTrackId: String,
        val isGapless: Boolean
    ) : AudioEvent

    data class BufferingState(
        override val sequenceNumber: Long,
        override val timelineEpoch: Long,
        val isBuffering: Boolean,
        val bufferedPositionMs: Long
    ) : AudioEvent

    data class FeatureSpectrumFrame(
        override val sequenceNumber: Long,
        override val timelineEpoch: Long,
        val sampleTimestampNanos: Long,
        val bins: ShortArray, // 256 uint16 FFT magnitude bins
        val peakRms: Float
    ) : AudioEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FeatureSpectrumFrame) return false
            return sequenceNumber == other.sequenceNumber &&
                   sampleTimestampNanos == other.sampleTimestampNanos &&
                   bins.contentEquals(other.bins) &&
                   peakRms == other.peakRms
        }
        override fun hashCode(): Int = sequenceNumber.hashCode()
    }

    data class Fault(
        override val sequenceNumber: Long,
        override val timelineEpoch: Long,
        val errorCode: String,
        val message: String,
        val isRecoverable: Boolean
    ) : AudioEvent
}
