package ca.skopek.dengar.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Continuous speech recognition on top of the platform [SpeechRecognizer], which only ever
 * handles one utterance per session. This restarts a session whenever one ends so the app keeps
 * listening until [stop] is called. All calls must happen on the main thread.
 */
class SpeechListener(
    private val context: Context,
    private val languageTag: String,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onListeningChanged(listening: Boolean)
        fun onLevel(rmsDb: Float)
        fun onError(message: String, fatal: Boolean)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (wanted) return
        wanted = true
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
            }
        }
        callbacks.onListeningChanged(true)
        listen()
    }

    fun stop() {
        if (!wanted) return
        wanted = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        callbacks.onListeningChanged(false)
    }

    fun destroy() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        if (!wanted) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer?.startListening(intent)
    }

    private fun restart(delayMillis: Long) {
        if (!wanted) return
        handler.postDelayed({ listen() }, delayMillis)
    }

    private fun fail(message: String) {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        callbacks.onError(message, fatal = true)
        callbacks.onListeningChanged(false)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            callbacks.onLevel(rmsdB)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.bestResult()
            if (text.isNotBlank()) callbacks.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            callbacks.onFinal(results.bestResult())
            restart(delayMillis = 50)
        }

        override fun onError(error: Int) {
            when (error) {
                // Silence: nothing was said in this session. Just open the next one.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(delayMillis = 50)

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> restart(delayMillis = 500)

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    fail("Microphone permission was denied.")

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    callbacks.onError("Speech service can't reach the network; retrying.", fatal = false)
                    restart(delayMillis = 1500)
                }

                // 12 and 13 are language-not-supported / language-unavailable on Android 12+.
                12, 13 -> fail("This phone's speech service doesn't support Indonesian.")

                else -> {
                    callbacks.onError("Speech recogniser error $error; retrying.", fatal = false)
                    restart(delayMillis = 1000)
                }
            }
        }
    }

    private fun Bundle?.bestResult(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}
