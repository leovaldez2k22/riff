package com.musicglass.desktop

import java.io.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * High-performance framed IPC transport for AudioCommand, AudioResult, and AudioEvent.
 * Uses 4-byte big-endian framing with strict payload limits (1 MiB max) and monotonic sequencing.
 * Conforms to Section 4.4 of Riff Windows desktop architecture.
 */
internal object AudioIpcTransport {
    const val MAX_FRAME_SIZE = 1024 * 1024 // 1 MiB maximum frame size

    // Message type tags
    const val TAG_CMD_HANDSHAKE: Byte = 0x01
    const val TAG_CMD_LOAD_QUEUE: Byte = 0x02
    const val TAG_CMD_SET_PLAYING: Byte = 0x03
    const val TAG_CMD_SEEK: Byte = 0x04
    const val TAG_CMD_SET_VOLUME: Byte = 0x05
    const val TAG_CMD_SET_RATE: Byte = 0x06
    const val TAG_CMD_SET_DSP: Byte = 0x07
    const val TAG_CMD_PREPARE_SOURCE: Byte = 0x08
    const val TAG_CMD_SHUTDOWN: Byte = 0x09
    const val TAG_CMD_PROBE_MEDIA: Byte = 0x0A
    const val TAG_CMD_SLEEP:Byte=0x0B
    const val TAG_CMD_DIAGNOSTICS:Byte=0x0C
    const val TAG_CMD_CANCEL:Byte=0x0D

    const val TAG_CMD_CANONICAL_DSP:Byte=0x0E

    const val TAG_RESULT: Byte = 0x10

    const val TAG_EVT_PLAYBACK_STATE: Byte = 0x20
    const val TAG_EVT_CLOCK_ANCHOR: Byte = 0x21
    const val TAG_EVT_TRANSITION: Byte = 0x22
    const val TAG_EVT_BUFFERING: Byte = 0x23
    const val TAG_EVT_SPECTRUM: Byte = 0x24
    const val TAG_EVT_FAULT: Byte = 0x25

    private fun writeSleep(out:DataOutputStream,plan:SleepPlan){out.writeInt(plan.mode.ordinal);out.writeLong(plan.deadlineMs);out.writeInt(plan.songsLeft);out.writeLong(plan.fadeMs)}
    private fun readSleep(input:DataInputStream)=SleepPlan(SleepMode.entries.getOrNull(input.readInt())?:throw IOException("Unknown sleep mode"),input.readLong(),input.readInt(),input.readLong())

    private fun writeTransitionHints(out:DataOutputStream,hints:com.musicglass.desktop.automix.TransitionHints){
        val value=com.musicglass.desktop.automix.checkedTransitionHints(hints)
        out.writeBoolean(value.isMusic);out.writeBoolean(value.classical);out.writeUTF(value.album);out.writeInt(value.trackNumber?:0)
        out.writeInt(value.vocals.size);value.vocals.forEach{out.writeLong(it.first);out.writeLong(it.last)}
    }
    private fun readTransitionHints(input:DataInputStream):com.musicglass.desktop.automix.TransitionHints{
        val music=input.readBoolean();val classical=input.readBoolean();val album=input.readUTF();val number=input.readInt()
        require(number in 0..1000000);val count=input.readInt();require(count in 0..8192)
        return com.musicglass.desktop.automix.checkedTransitionHints(com.musicglass.desktop.automix.TransitionHints(music,classical,album,List(count){input.readLong()..input.readLong()},number.takeIf{it>0}))
    }

    // --- Command Serialization ---

    fun writeCommand(out: DataOutputStream, cmd: AudioCommand) {
        val baos = ByteArrayOutputStream()
        val dataOut = DataOutputStream(baos)

        when (cmd) {
            is AudioCommand.Handshake -> {
                baos.write(TAG_CMD_HANDSHAKE.toInt())
                dataOut.writeLong(cmd.requestId)
                dataOut.writeUTF(cmd.clientBuild)
                dataOut.writeLong(cmd.processNonce)
            }
            is AudioCommand.LoadQueue -> {
                baos.write(TAG_CMD_LOAD_QUEUE.toInt())
                dataOut.writeLong(cmd.requestId)
                dataOut.writeInt(cmd.trackIds.size)
                for (id in cmd.trackIds) dataOut.writeUTF(id)
                dataOut.writeInt(cmd.startIndex)
                dataOut.writeLong(cmd.startPositionMs)
                dataOut.writeLong(cmd.secondaryPositionMs)
                dataOut.writeBoolean(cmd.crossfadeEnabled)
                dataOut.writeUTF(cmd.repeatMode.name)
            }
            is AudioCommand.SetPlaying -> {
                baos.write(TAG_CMD_SET_PLAYING.toInt())
                dataOut.writeLong(cmd.requestId)
                dataOut.writeBoolean(cmd.isPlaying)
            }
            is AudioCommand.Seek -> {
                baos.write(TAG_CMD_SEEK.toInt())
                dataOut.writeLong(cmd.requestId)
                dataOut.writeLong(cmd.positionMs)
                dataOut.writeLong(cmd.timelineEpoch)
            }
            is AudioCommand.SetVolume -> {
                baos.write(TAG_CMD_SET_VOLUME.toInt())
                dataOut.writeLong(cmd.requestId)
                dataOut.writeFloat(cmd.volume)
            }
            is AudioCommand.SetPlaybackRate -> {
                baos.write(TAG_CMD_SET_RATE.toInt())
                dataOut.writeLong(cmd.requestId)
                dataOut.writeFloat(cmd.rate)
            }
            is AudioCommand.SetDSPConfig -> {
                baos.write(TAG_CMD_SET_DSP.toInt())
                dataOut.writeLong(cmd.requestId)
                dataOut.writeBoolean(cmd.equalizerEnabled)
                dataOut.writeInt(cmd.eqBandsDb.size)
                for (b in cmd.eqBandsDb) dataOut.writeFloat(b)
                dataOut.writeFloat(cmd.volumeBoostGain)
                dataOut.writeFloat(cmd.normalizeTrim)
                dataOut.writeFloat(cmd.bassCutDb)
            }
            is AudioCommand.SetCanonicalDSP -> {
                baos.write(TAG_CMD_CANONICAL_DSP.toInt());dataOut.writeLong(cmd.requestId)
                val c=checkedEqualizer(cmd.equalizer)
                dataOut.writeBoolean(c.enabled);dataOut.writeInt(c.mode.ordinal);dataOut.writeInt(c.toneX);dataOut.writeInt(c.toneY);dataOut.writeBoolean(c.focused)
                c.manual.forEach{dataOut.writeFloat(it)};dataOut.writeUTF(c.preset);dataOut.writeFloat(c.balance);dataOut.writeFloat(cmd.boost);dataOut.writeFloat(cmd.trim);dataOut.writeBoolean(cmd.normalize)
            }
            is AudioCommand.PrepareSource -> {
                baos.write(TAG_CMD_PREPARE_SOURCE.toInt())
                dataOut.writeLong(cmd.requestId)
                dataOut.writeUTF(cmd.trackId)
                dataOut.writeUTF(cmd.streamUrl)
                dataOut.writeLong(cmd.expiryEpochMs)
                dataOut.writeFloat(checkedPlaybackRate(cmd.playbackRate))
                writeTransitionHints(dataOut,cmd.transitionHints)
            }
            is AudioCommand.CancelPendingOperation -> {baos.write(TAG_CMD_CANCEL.toInt());dataOut.writeLong(cmd.requestId)}
            is AudioCommand.QueryDiagnostics -> {baos.write(TAG_CMD_DIAGNOSTICS.toInt());dataOut.writeLong(cmd.requestId)}
            is AudioCommand.SetSleepTimer -> {baos.write(TAG_CMD_SLEEP.toInt());dataOut.writeLong(cmd.requestId);writeSleep(dataOut,cmd.plan)}
            is AudioCommand.ProbeMedia -> {baos.write(TAG_CMD_PROBE_MEDIA.toInt());dataOut.writeLong(cmd.requestId);dataOut.writeUTF(cmd.source)}
            is AudioCommand.Shutdown -> {
                baos.write(TAG_CMD_SHUTDOWN.toInt())
                dataOut.writeLong(cmd.requestId)
            }
        }

        dataOut.flush()
        val payload = baos.toByteArray()
        require(payload.size in 1..MAX_FRAME_SIZE){"Audio frame exceeds limit"}
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun readCommand(input: DataInputStream): AudioCommand {
        val length = input.readInt()
        if (length <= 0 || length > MAX_FRAME_SIZE) {
            throw IOException("Invalid IPC command frame length: $length")
        }
        val payload = ByteArray(length)
        input.readFully(payload)

        val bis = ByteArrayInputStream(payload)
        val dataIn = DataInputStream(bis)
        val tag = dataIn.readByte()

        val command=when (tag) {
            TAG_CMD_HANDSHAKE -> AudioCommand.Handshake(
                requestId = dataIn.readLong(),
                clientBuild = dataIn.readUTF(),
                processNonce = dataIn.readLong()
            )
            TAG_CMD_LOAD_QUEUE -> {
                val reqId = dataIn.readLong()
                val size = dataIn.readInt()
                require(size in 0..5000) { "Invalid queue size" }
                val list = ArrayList<String>(size)
                for (i in 0 until size) list.add(dataIn.readUTF())
                val startIdx = dataIn.readInt()
                val startPos = dataIn.readLong()
                AudioCommand.LoadQueue(reqId, list, startIdx, startPos, dataIn.readLong(), dataIn.readBoolean(), RepeatMode.valueOf(dataIn.readUTF()))
            }
            TAG_CMD_SET_PLAYING -> AudioCommand.SetPlaying(
                requestId = dataIn.readLong(),
                isPlaying = dataIn.readBoolean()
            )
            TAG_CMD_SEEK -> AudioCommand.Seek(
                requestId = dataIn.readLong(),
                positionMs = dataIn.readLong(),
                timelineEpoch = dataIn.readLong()
            )
            TAG_CMD_SET_VOLUME -> AudioCommand.SetVolume(
                requestId = dataIn.readLong(),
                volume = dataIn.readFloat()
            )
            TAG_CMD_SET_RATE -> AudioCommand.SetPlaybackRate(
                requestId = dataIn.readLong(),
                rate = dataIn.readFloat()
            )
            TAG_CMD_SET_DSP -> {
                val reqId = dataIn.readLong()
                val eqEnabled = dataIn.readBoolean()
                val count = dataIn.readInt()
                require(count == 10) { "Invalid equalizer band count" }
                val bands = FloatArray(count)
                for (i in 0 until count) bands[i] = dataIn.readFloat()
                val boost = dataIn.readFloat()
                val trim = dataIn.readFloat()
                val bassCut = dataIn.readFloat()
                AudioCommand.SetDSPConfig(reqId, eqEnabled, bands, boost, trim, bassCut)
            }
            TAG_CMD_CANONICAL_DSP -> {
                val request=dataIn.readLong();val enabled=dataIn.readBoolean();val mode=EqMode.entries.getOrNull(dataIn.readInt())?:throw IOException("Invalid equalizer mode")
                val x=dataIn.readInt();val y=dataIn.readInt();val focused=dataIn.readBoolean();val manual=List(7){dataIn.readFloat()};val preset=dataIn.readUTF();val balance=dataIn.readFloat()
                val config=checkedEqualizer(EqualizerConfig(enabled,mode,x,y,focused,manual,preset,balance))
                AudioCommand.SetCanonicalDSP(request,config,dataIn.readFloat(),dataIn.readFloat(),dataIn.readBoolean())
            }
            TAG_CMD_PREPARE_SOURCE -> AudioCommand.PrepareSource(
                requestId = dataIn.readLong(),
                trackId = dataIn.readUTF(),
                streamUrl = dataIn.readUTF(),
                expiryEpochMs = dataIn.readLong(),
                playbackRate = checkedPlaybackRate(dataIn.readFloat()),
                transitionHints = readTransitionHints(dataIn)
            )
            TAG_CMD_CANCEL -> AudioCommand.CancelPendingOperation(dataIn.readLong())
            TAG_CMD_DIAGNOSTICS -> AudioCommand.QueryDiagnostics(dataIn.readLong())
            TAG_CMD_SLEEP -> AudioCommand.SetSleepTimer(dataIn.readLong(),readSleep(dataIn))
            TAG_CMD_PROBE_MEDIA -> AudioCommand.ProbeMedia(dataIn.readLong(),dataIn.readUTF())
            TAG_CMD_SHUTDOWN -> AudioCommand.Shutdown(requestId = dataIn.readLong())
            else -> throw IOException("Unknown command tag: $tag")
        }
        require(dataIn.available()==0){"Trailing bytes in audio command"}
        return command
    }

    // --- Result Serialization ---

    fun writeResult(out: DataOutputStream, res: AudioResult) {
        val baos = ByteArrayOutputStream()
        val dataOut = DataOutputStream(baos)
        baos.write(TAG_RESULT.toInt())
        dataOut.writeLong(res.requestId)
        dataOut.writeBoolean(res.success)
        dataOut.writeLong(res.appliedStateRevision)
        dataOut.writeBoolean(res.error != null)
        if (res.error != null) dataOut.writeUTF(res.error)
        dataOut.writeBoolean(res.details != null)
        if (res.details != null) dataOut.writeUTF(res.details)

        dataOut.flush()
        val payload = baos.toByteArray()
        require(payload.size in 1..MAX_FRAME_SIZE){"Audio frame exceeds limit"}
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun readResult(input: DataInputStream): AudioResult {
        val length = input.readInt()
        if (length <= 0 || length > MAX_FRAME_SIZE) {
            throw IOException("Invalid IPC result frame length: $length")
        }
        val payload = ByteArray(length)
        input.readFully(payload)

        val bis = ByteArrayInputStream(payload)
        val dataIn = DataInputStream(bis)
        val tag = dataIn.readByte()
        if (tag != TAG_RESULT) throw IOException("Expected TAG_RESULT, got $tag")

        val reqId = dataIn.readLong()
        val success = dataIn.readBoolean()
        val rev = dataIn.readLong()
        val hasError = dataIn.readBoolean()
        val err = if (hasError) dataIn.readUTF() else null
        val details = if (dataIn.readBoolean()) dataIn.readUTF() else null
        require(dataIn.available()==0){"Trailing bytes in audio result"}
        return AudioResult(reqId, success, rev, err, details)
    }

    // --- Event Serialization ---

    fun writeEvent(out: DataOutputStream, evt: AudioEvent) {
        val baos = ByteArrayOutputStream()
        val dataOut = DataOutputStream(baos)

        when (evt) {
            is AudioEvent.PlaybackStateChanged -> {
                baos.write(TAG_EVT_PLAYBACK_STATE.toInt())
                dataOut.writeLong(evt.sequenceNumber)
                dataOut.writeLong(evt.timelineEpoch)
                dataOut.writeBoolean(evt.isPlaying)
                dataOut.writeBoolean(evt.currentTrackId != null)
                if (evt.currentTrackId != null) dataOut.writeUTF(evt.currentTrackId)
                dataOut.writeLong(evt.positionMs)
            }
            is AudioEvent.ClockAnchor -> {
                baos.write(TAG_EVT_CLOCK_ANCHOR.toInt())
                dataOut.writeLong(evt.sequenceNumber)
                dataOut.writeLong(evt.timelineEpoch)
                dataOut.writeLong(evt.contentPositionMs)
                dataOut.writeLong(evt.monotonicTimestampNanos)
                dataOut.writeInt(evt.sampleRate)
                dataOut.writeLong(evt.samplesRendered)
                dataOut.writeFloat(evt.latencyEstimateMs)
                dataOut.writeFloat(evt.playbackRate)
                dataOut.writeBoolean(evt.currentEntryId != null)
                evt.currentEntryId?.let { dataOut.writeUTF(it) }
                dataOut.writeBoolean(evt.secondaryEntryId != null)
                evt.secondaryEntryId?.let { dataOut.writeUTF(it) }
                dataOut.writeLong(evt.secondaryPositionMs)
                writeSleep(dataOut,evt.sleep)
            }
            is AudioEvent.TrackTransition -> {
                baos.write(TAG_EVT_TRANSITION.toInt())
                dataOut.writeLong(evt.sequenceNumber)
                dataOut.writeLong(evt.timelineEpoch)
                dataOut.writeUTF(evt.completedTrackId)
                dataOut.writeUTF(evt.nextTrackId)
                dataOut.writeBoolean(evt.isGapless)
            }
            is AudioEvent.BufferingState -> {
                baos.write(TAG_EVT_BUFFERING.toInt())
                dataOut.writeLong(evt.sequenceNumber)
                dataOut.writeLong(evt.timelineEpoch)
                dataOut.writeBoolean(evt.isBuffering)
                dataOut.writeLong(evt.bufferedPositionMs)
            }
            is AudioEvent.FeatureSpectrumFrame -> {
                baos.write(TAG_EVT_SPECTRUM.toInt())
                dataOut.writeLong(evt.sequenceNumber)
                dataOut.writeLong(evt.timelineEpoch)
                dataOut.writeLong(evt.sampleTimestampNanos)
                dataOut.writeFloat(evt.peakRms)
                dataOut.writeInt(evt.bins.size)
                for (b in evt.bins) dataOut.writeShort(b.toInt())
            }
            is AudioEvent.Fault -> {
                baos.write(TAG_EVT_FAULT.toInt())
                dataOut.writeLong(evt.sequenceNumber)
                dataOut.writeLong(evt.timelineEpoch)
                dataOut.writeUTF(evt.errorCode)
                dataOut.writeUTF(evt.message)
                dataOut.writeBoolean(evt.isRecoverable)
            }
        }

        dataOut.flush()
        val payload = baos.toByteArray()
        require(payload.size in 1..MAX_FRAME_SIZE){"Audio frame exceeds limit"}
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    sealed interface IpcMessage {
        data class Result(val result: AudioResult) : IpcMessage
        data class Event(val event: AudioEvent) : IpcMessage
    }

    fun readMessage(input: DataInputStream): IpcMessage {
        val length = input.readInt()
        if (length <= 0 || length > MAX_FRAME_SIZE) {
            throw IOException("Invalid IPC frame length: $length")
        }
        val payload = ByteArray(length)
        input.readFully(payload)

        val bis = ByteArrayInputStream(payload)
        val dataIn = DataInputStream(bis)
        val tag = dataIn.readByte()

        val message=if (tag == TAG_RESULT) {
            val reqId = dataIn.readLong()
            val success = dataIn.readBoolean()
            val rev = dataIn.readLong()
            val hasError = dataIn.readBoolean()
            val err = if (hasError) dataIn.readUTF() else null
            IpcMessage.Result(AudioResult(reqId, success, rev, err, if (dataIn.readBoolean()) dataIn.readUTF() else null))
        } else {
            val evt = decodeEventFromStream(tag, dataIn)
            IpcMessage.Event(evt)
        }
        require(dataIn.available()==0){"Trailing bytes in audio message"}
        return message
    }

    fun readEvent(input: DataInputStream): AudioEvent {
        val length = input.readInt()
        if (length <= 0 || length > MAX_FRAME_SIZE) {
            throw IOException("Invalid IPC event frame length: $length")
        }
        val payload = ByteArray(length)
        input.readFully(payload)

        val bis = ByteArrayInputStream(payload)
        val dataIn = DataInputStream(bis)
        val tag = dataIn.readByte()
        val event=decodeEventFromStream(tag,dataIn)
        require(dataIn.available()==0){"Trailing bytes in audio event"}
        return event
    }

    private fun decodeEventFromStream(tag: Byte, dataIn: DataInputStream): AudioEvent {
        return when (tag) {
            TAG_EVT_PLAYBACK_STATE -> AudioEvent.PlaybackStateChanged(
                sequenceNumber = dataIn.readLong(),
                timelineEpoch = dataIn.readLong(),
                isPlaying = dataIn.readBoolean(),
                currentTrackId = if (dataIn.readBoolean()) dataIn.readUTF() else null,
                positionMs = dataIn.readLong()
            )
            TAG_EVT_CLOCK_ANCHOR -> AudioEvent.ClockAnchor(
                sequenceNumber = dataIn.readLong(),
                timelineEpoch = dataIn.readLong(),
                contentPositionMs = dataIn.readLong(),
                monotonicTimestampNanos = dataIn.readLong(),
                sampleRate = dataIn.readInt(),
                samplesRendered = dataIn.readLong(),
                latencyEstimateMs = dataIn.readFloat(),
                playbackRate = dataIn.readFloat(),
                currentEntryId = if(dataIn.readBoolean()) dataIn.readUTF() else null,
                secondaryEntryId = if(dataIn.readBoolean()) dataIn.readUTF() else null,
                secondaryPositionMs = dataIn.readLong(),
                sleep=readSleep(dataIn)
            )
            TAG_EVT_TRANSITION -> AudioEvent.TrackTransition(
                sequenceNumber = dataIn.readLong(),
                timelineEpoch = dataIn.readLong(),
                completedTrackId = dataIn.readUTF(),
                nextTrackId = dataIn.readUTF(),
                isGapless = dataIn.readBoolean()
            )
            TAG_EVT_BUFFERING -> AudioEvent.BufferingState(
                sequenceNumber = dataIn.readLong(),
                timelineEpoch = dataIn.readLong(),
                isBuffering = dataIn.readBoolean(),
                bufferedPositionMs = dataIn.readLong()
            )
            TAG_EVT_SPECTRUM -> {
                val seq = dataIn.readLong()
                val epoch = dataIn.readLong()
                val stamp = dataIn.readLong()
                val rms = dataIn.readFloat()
                val binCount = dataIn.readInt()
                require(binCount == 256) { "Invalid spectrum size" }
                val bins = ShortArray(binCount)
                for (i in 0 until binCount) bins[i] = dataIn.readShort()
                AudioEvent.FeatureSpectrumFrame(seq, epoch, stamp, bins, rms)
            }
            TAG_EVT_FAULT -> AudioEvent.Fault(
                sequenceNumber = dataIn.readLong(),
                timelineEpoch = dataIn.readLong(),
                errorCode = dataIn.readUTF(),
                message = dataIn.readUTF(),
                isRecoverable = dataIn.readBoolean()
            )
            else -> throw IOException("Unknown event tag: $tag")
        }
    }
}
