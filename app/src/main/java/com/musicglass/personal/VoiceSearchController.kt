package com.musicglass.personal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Speech-to-text search only. Humming needs a separate music-recognition service. */
internal class VoiceSearchController(private val context: Context) : AutoCloseable {
    var listening by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0

    fun permissionDenied() {
        message = string(R.string.microphone_permission_is_needed_for_voice_se)
    }

    fun start(onResult: (String) -> Unit) {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            message = string(R.string.voice_recognition_is_unavailable_on_this_dev)
            return
        }
        val run = ++generation
        try {
            val service = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = service
            service.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (run == generation) {message = string(R.string.listening);uiHaptic(HapticCue.Confirm)}
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (run == generation) message = string(R.string.finding_words)
                }
                override fun onError(error: Int) {
                    if (run != generation) return
                    message = when (error) {
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                            string(R.string.this_language_is_not_supported_by_the)
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            string(R.string.this_language_is_not_available_yet_try)
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            string(R.string.no_words_recognized_try_again_or_type)
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            string(R.string.microphone_permission_is_needed_for_voice_se_2)
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            string(R.string.voice_recognition_needs_a_network_connection)
                        else -> string(R.string.voice_recognition_failed_try_again_or_type)
                    }
                    finish()
                }
                override fun onResults(results: Bundle?) {
                    if (run != generation) return
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (words.isBlank()) message = string(R.string.no_words_recognized_try_again_or_type)
                    else {
                        message = null
                        onResult(words.take(100))
                    }
                    finish()
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            listening = true
            message = string(R.string.starting_voice_search)
            service.startListening(intent)
        } catch (_: Exception) {
            message = string(R.string.voice_recognition_is_unavailable_on_this_dev)
            finish()
        }
    }

    fun cancel() {
        if (!listening) return
        generation++
        runCatching { recognizer?.cancel() }
        message = string(R.string.voice_search_canceled)
        finish()
    }

    private fun finish() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    override fun close() {
        generation++
        runCatching { recognizer?.cancel() }
        finish()
    }
}
