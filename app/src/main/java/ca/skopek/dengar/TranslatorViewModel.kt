package ca.skopek.dengar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.skopek.dengar.speech.SpeechListener
import ca.skopek.dengar.transcript.Transcript
import ca.skopek.dengar.translate.IndonesianTranslator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val transcript: Transcript = Transcript(),
    val listening: Boolean = false,
    val modelReady: Boolean = false,
    val modelError: String? = null,
    val speechAvailable: Boolean = true,
    val notice: String? = null,
)

class TranslatorViewModel(application: Application) : AndroidViewModel(application), SpeechListener.Callbacks {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Microphone level in dB from the recogniser; separate so it doesn't churn the main state. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val translator = IndonesianTranslator()
    private val speech = SpeechListener(application, languageTag = "id-ID", callbacks = this)
    private var partialJob: Job? = null

    init {
        _uiState.update { it.copy(speechAvailable = speech.isAvailable) }
        viewModelScope.launch { prepareModel() }
    }

    private suspend fun prepareModel() {
        _uiState.update { it.copy(modelError = null) }
        try {
            translator.ensureModel()
            _uiState.update { it.copy(modelReady = true) }
            // Anything heard before the model arrived can be translated now.
            _uiState.value.transcript.untranslated().forEach { segment ->
                translateSegment(segment.id, segment.indonesian)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(modelError = e.message ?: e.javaClass.simpleName) }
        }
    }

    fun retryModel() {
        viewModelScope.launch { prepareModel() }
    }

    fun toggleListening() {
        if (_uiState.value.listening) speech.stop() else speech.start()
    }

    fun clearTranscript() {
        partialJob?.cancel()
        _uiState.update { it.copy(transcript = it.transcript.cleared()) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun showPermissionDenied() {
        val message = getApplication<Application>().getString(R.string.permission_denied)
        _uiState.update { it.copy(notice = message) }
    }

    override fun onPartial(text: String) {
        _uiState.update { it.copy(transcript = it.transcript.withPartial(text)) }
        partialJob?.cancel()
        partialJob = viewModelScope.launch {
            delay(120) // let a burst of partials settle before spending a translation on it
            val english = translateOrNull(text) ?: return@launch
            _uiState.update { it.copy(transcript = it.transcript.withPartialTranslation(text, english)) }
        }
    }

    override fun onFinal(text: String) {
        partialJob?.cancel()
        val before = _uiState.value.transcript
        val after = before.commit(text, System.currentTimeMillis())
        _uiState.update { it.copy(transcript = after) }
        val segment = after.segments.lastOrNull() ?: return
        if (after.segments.size == before.segments.size || segment.english != null) return
        translateSegment(segment.id, segment.indonesian)
    }

    private fun translateSegment(id: Long, indonesian: String) {
        viewModelScope.launch {
            val english = translateOrNull(indonesian) ?: return@launch
            _uiState.update { it.copy(transcript = it.transcript.withTranslation(id, english)) }
        }
    }

    private suspend fun translateOrNull(text: String): String? {
        if (!_uiState.value.modelReady) return null
        return try {
            translator.translate(text)
        } catch (e: Exception) {
            null
        }
    }

    override fun onListeningChanged(listening: Boolean) {
        _uiState.update { it.copy(listening = listening) }
        if (!listening) {
            _level.value = 0f
            // Whatever was half-recognised when the user stopped is still worth keeping.
            val partial = _uiState.value.transcript.partialIndonesian
            if (partial.isNotBlank()) onFinal(partial)
        }
    }

    override fun onLevel(rmsDb: Float) {
        _level.value = rmsDb
    }

    override fun onError(message: String, fatal: Boolean) {
        _uiState.update { it.copy(notice = message) }
    }

    override fun onCleared() {
        speech.destroy()
        translator.close()
    }
}
