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
        message = "Microphone permission is needed for voice search. You can still type a search."
    }

    fun start(onResult: (String) -> Unit) {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            message = "Voice recognition is unavailable on this device."
            return
        }
        val run = ++generation
        try {
            val service = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = service
            service.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (run == generation) {message = "Listening\u2026";uiHaptic(HapticCue.Confirm)}
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (run == generation) message = "Finding words\u2026"
                }
                override fun onError(error: Int) {
                    if (run != generation) return
                    message = when (error) {
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                            "This language is not supported by the device's speech service."
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            "This language is not available yet. Try typing the search."
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "No words recognized. Try again or type a search."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission is needed for voice search."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Voice recognition needs a network connection on this device."
                        else -> "Voice recognition failed. Try again or type a search."
                    }
                    finish()
                }
                override fun onResults(results: Bundle?) {
                    if (run != generation) return
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (words.isBlank()) message = "No words recognized. Try again or type a search."
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
            message = "Starting voice search\u2026"
            service.startListening(intent)
        } catch (_: Exception) {
            message = "Voice recognition is unavailable on this device."
            finish()
        }
    }

    fun cancel() {
        if (!listening) return
        generation++
        runCatching { recognizer?.cancel() }
        message = "Voice search canceled."
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
