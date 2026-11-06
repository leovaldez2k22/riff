package com.musicglass.desktop

import java.io.IOException

internal sealed class AudioOperationException(val code:String,message:String,cause:Throwable):IOException(message,cause)
internal class SourceRepresentationChangedException(cause:Throwable):AudioOperationException("STREAM_REPRESENTATION_CHANGED","The provider encoding changed; reopening at the last heard position",cause)
internal class DecodeException(cause:Throwable):AudioOperationException("DECODE_FAILED","Audio frames could not be decoded",cause)
internal class UnsupportedContainerException(cause:Throwable):AudioOperationException("UNSUPPORTED_OR_CORRUPT_CONTAINER","This file is not a supported readable audio container",cause)
internal class StreamCorruptedException(cause:Throwable):AudioOperationException("CORRUPT_AUDIO_STREAM","The encoded audio stream is corrupt",cause)
internal class AudioDeviceLostException(cause:Throwable):AudioOperationException("AUDIO_DEVICE_LOST","The Windows audio endpoint is unavailable; playback is paused",cause)
internal inline fun <T> decodeFrames(operation:()->T):T=try{operation()}catch(error:IOException){
    val causes=generateSequence<Throwable>(error){it.cause}.take(16).toList()
    if(causes.any{it.message.orEmpty().contains("representation changed",true)||it.message.orEmpty().contains("transport changed",true)})throw SourceRepresentationChangedException(error)
    if(causes.any{it.message.orEmpty().contains("Corrupt",ignoreCase=true)})throw StreamCorruptedException(error)
    throw DecodeException(error)
}
internal inline fun <T> endpointOperation(operation:()->T):T=try{operation()}catch(error:IOException){throw AudioDeviceLostException(error)}

internal class AudioSchedulingUnavailableException(cause:Throwable):AudioOperationException("AUDIO_SCHEDULING_UNAVAILABLE","Windows audio scheduling is unavailable; playback is paused",cause)

internal class TempoDurationLimitException:AudioOperationException("TEMPO_DURATION_LIMIT","Pitch-preserving speed supports recordings up to four hours; choose 1x for this recording",IOException("Tempo duration bound"))
