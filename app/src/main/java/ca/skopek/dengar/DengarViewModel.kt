package ca.skopek.dengar

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.skopek.dengar.audio.AudioCapture
import ca.skopek.dengar.audio.WavWriter
import ca.skopek.dengar.realtime.RealtimeEvent
import ca.skopek.dengar.realtime.RealtimeTranscriber
import ca.skopek.dengar.settings.Settings
import ca.skopek.dengar.store.Conversation
import ca.skopek.dengar.store.ConversationStore
import ca.skopek.dengar.transcript.Transcript
import ca.skopek.dengar.translate.IndonesianTranslator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Live : Screen
    data object History : Screen
    data class Playback(val id: String) : Screen
    data object Settings : Screen
}

enum class Connection { Idle, Connecting, Live, Reconnecting }

data class LiveUiState(
    val transcript: Transcript = Transcript(),
    val recording: Boolean = false,
    val finishing: Boolean = false,
    val connection: Connection = Connection.Idle,
    val elapsedMs: Long = 0,
    val modelReady: Boolean = false,
    val modelError: String? = null,
    val hasApiKey: Boolean = false,
    val notice: String? = null,
)

class DengarViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)
    val store = ConversationStore(File(application.filesDir, "conversations"))

    private val _screen = MutableStateFlow<Screen>(Screen.Live)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _live = MutableStateFlow(LiveUiState(hasApiKey = settings.apiKey.isNotBlank()))
    val live: StateFlow<LiveUiState> = _live.asStateFlow()

    /** Microphone loudness 0..1; separate so it doesn't churn the main state. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val translator = IndonesianTranslator()
    private val capture by lazy { AudioCapture(audioSink) }
    private var transcriber: RealtimeTranscriber? = null

    @Volatile
    private var wav: WavWriter? = null

    /** Recording position when the current socket session began; server offsets are relative to it. */
    @Volatile
    private var sessionOffsetMs = 0L

    private var currentId: String? = null
    private var startedAtMillis = 0L
    private var reconnectDelayMs = 2_000L
    private var tickerJob: Job? = null
    private val partialJobs = HashMap<String, Job>()

    // ---- navigation

    fun navigate(screen: Screen) {
        if (screen == Screen.History) refreshHistory()
        _screen.value = screen
    }

    fun back() {
        _screen.value = when (_screen.value) {
            is Screen.Playback -> Screen.History
            else -> Screen.Live
        }
    }

    // ---- settings

    val apiKey: String get() = settings.apiKey
    val transcribeModel: String get() = settings.transcribeModel

    fun saveSettings(apiKey: String, model: String) {
        settings.apiKey = apiKey
        settings.transcribeModel = model
        _live.update { it.copy(hasApiKey = apiKey.isNotBlank()) }
    }

    // ---- translation model

    private suspend fun prepareModel() {
        _live.update { it.copy(modelError = null) }
        try {
            translator.ensureModel()
            _live.update { it.copy(modelReady = true) }
            _live.value.transcript.untranslated().filter { it.isFinal }.forEach { translateSegment(it.id, it.indonesian) }
        } catch (e: Exception) {
            _live.update { it.copy(modelError = e.message ?: e.javaClass.simpleName) }
        }
    }

    fun retryModel() {
        viewModelScope.launch { prepareModel() }
    }

    // ---- recording

    fun toggleRecording() {
        val state = _live.value
        when {
            state.finishing -> Unit
            state.recording -> stopRecording()
            else -> startRecording()
        }
    }

    private fun startRecording() {
        val key = settings.apiKey
        if (key.isBlank()) {
            notice(getApplication<Application>().getString(R.string.need_api_key))
            return
        }
        val now = System.currentTimeMillis()
        val id = store.newId(now)
        currentId = id
        startedAtMillis = now
        reconnectDelayMs = 2_000L
        partialJobs.values.forEach { it.cancel() }
        partialJobs.clear()
        wav = WavWriter(store.audioFile(id), AudioCapture.SAMPLE_RATE)
        _live.update {
            it.copy(transcript = Transcript(), recording = true, finishing = false, connection = Connection.Connecting, elapsedMs = 0)
        }
        if (!capture.start()) {
            wav?.finish()
            wav = null
            store.delete(id)
            currentId = null
            _live.update { it.copy(recording = false, connection = Connection.Idle) }
            return
        }
        connectTranscriber()
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                _live.update { it.copy(elapsedMs = wav?.durationMs ?: it.elapsedMs) }
                delay(500)
            }
        }
    }

    private fun connectTranscriber() {
        transcriber?.close()
        transcriber = RealtimeTranscriber(
            apiKey = settings.apiKey,
            model = settings.transcribeModel,
            language = "id",
            listener = socketListener,
        ).also { it.connect() }
    }

    private fun stopRecording() {
        _live.update { it.copy(finishing = true) }
        capture.stop()
        _level.value = 0f
        transcriber?.commit()
        viewModelScope.launch {
            // Give the service a moment to finish transcribing what it already has.
            val deadline = System.currentTimeMillis() + 6_000
            while (_live.value.transcript.hasPendingSegments &&
                transcriber?.isOpen == true &&
                System.currentTimeMillis() < deadline
            ) {
                delay(200)
            }
            finishRecording()
        }
    }

    private suspend fun finishRecording() {
        tickerJob?.cancel()
        tickerJob = null
        transcriber?.close()
        transcriber = null
        val writer = wav
        wav = null
        val id = currentId
        currentId = null
        val durationMs = writer?.durationMs ?: 0L
        withContext(Dispatchers.IO) { writer?.finish() }
        if (id != null) {
            val segments = _live.value.transcript.segments.filter { it.indonesian.isNotBlank() }
            if (segments.isEmpty() && durationMs < 3_000) {
                withContext(Dispatchers.IO) { store.delete(id) }
            } else {
                val conversation = Conversation(id, startedAtMillis, durationMs, segments)
                withContext(Dispatchers.IO) { store.save(conversation) }
            }
        }
        _live.update { it.copy(recording = false, finishing = false, connection = Connection.Idle, elapsedMs = durationMs) }
        refreshHistory()
    }

    /** Write the transcript so far, so a crash mid-conversation keeps what was heard. */
    private fun persistProgress() {
        val id = currentId ?: return
        val writer = wav
        val snapshot = Conversation(
            id = id,
            startedAtMillis = startedAtMillis,
            durationMs = writer?.durationMs ?: 0L,
            segments = _live.value.transcript.segments.filter { it.indonesian.isNotBlank() },
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                writer?.flush()
                store.save(snapshot)
            }
        }
    }

    private val audioSink = object : AudioCapture.Sink {
        override fun onAudio(pcm: ByteArray, length: Int) {
            wav?.write(pcm, length)
            transcriber?.sendAudio(pcm, length)
        }

        override fun onLevel(level: Float) {
            _level.value = level
        }

        override fun onCaptureError(message: String) {
            mainHandler.post {
                notice(message)
                if (_live.value.recording && !_live.value.finishing) stopRecording()
            }
        }
    }

    private val socketListener = object : RealtimeTranscriber.Listener {
        override fun onConnected() {
            sessionOffsetMs = wav?.durationMs ?: 0L
            mainHandler.post {
                reconnectDelayMs = 2_000L
                _live.update { it.copy(connection = Connection.Live) }
            }
        }

        override fun onEvent(event: RealtimeEvent) {
            mainHandler.post { handle(event) }
        }

        override fun onDisconnected(reason: String?) {
            if (reason == null) return
            mainHandler.post {
                val state = _live.value
                if (!state.recording || state.finishing) return@post
                notice(getApplication<Application>().getString(R.string.transcription_lost, reason))
                _live.update { it.copy(connection = Connection.Reconnecting) }
                val delayMs = reconnectDelayMs
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15_000L)
                viewModelScope.launch {
                    delay(delayMs)
                    val now = _live.value
                    if (now.recording && !now.finishing && now.connection == Connection.Reconnecting) connectTranscriber()
                }
            }
        }
    }

    private fun handle(event: RealtimeEvent) {
        val offset = sessionOffsetMs
        when (event) {
            is RealtimeEvent.SessionReady -> _live.update { it.copy(connection = Connection.Live) }

            is RealtimeEvent.SpeechStarted ->
                _live.update { it.copy(transcript = it.transcript.speechStarted(event.itemId, event.audioStartMs + offset)) }

            is RealtimeEvent.SpeechStopped ->
                _live.update { it.copy(transcript = it.transcript.speechStopped(event.itemId, event.audioEndMs + offset)) }

            is RealtimeEvent.TranscriptDelta -> {
                _live.update { it.copy(transcript = it.transcript.delta(event.itemId, event.delta)) }
                val text = _live.value.transcript.segments.firstOrNull { it.id == event.itemId }?.indonesian ?: return
                partialJobs[event.itemId]?.cancel()
                partialJobs[event.itemId] = viewModelScope.launch {
                    delay(150) // let a burst of deltas settle before spending a translation on it
                    translateSegment(event.itemId, text)
                }
            }

            is RealtimeEvent.TranscriptCompleted -> {
                partialJobs.remove(event.itemId)?.cancel()
                _live.update { it.copy(transcript = it.transcript.completed(event.itemId, event.transcript)) }
                val segment = _live.value.transcript.segments.firstOrNull { it.id == event.itemId }
                if (segment != null && segment.english == null) translateSegment(segment.id, segment.indonesian)
                persistProgress()
            }

            is RealtimeEvent.TranscriptFailed -> {
                partialJobs.remove(event.itemId)?.cancel()
                notice(getApplication<Application>().getString(R.string.transcription_failed, event.message))
            }

            is RealtimeEvent.Error -> {
                // Committing an empty buffer on stop is harmless; everything else is worth showing.
                if (event.code?.contains("commit_empty") == true || event.message.contains("buffer too small", ignoreCase = true)) return
                notice(event.message)
            }

            is RealtimeEvent.Other -> Unit
        }
    }

    private fun translateSegment(id: String, indonesian: String) {
        if (indonesian.isBlank()) return
        viewModelScope.launch {
            val english = translateOrNull(indonesian) ?: return@launch
            _live.update { it.copy(transcript = it.transcript.translated(id, indonesian, english)) }
        }
    }

    private suspend fun translateOrNull(text: String): String? {
        if (!_live.value.modelReady) return null
        return try {
            translator.translate(text)
        } catch (e: Exception) {
            null
        }
    }

    // ---- history

    fun refreshHistory() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { store.list() }.getOrDefault(emptyList()) }
            _conversations.value = list
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            refreshHistory()
            if (_screen.value == Screen.Playback(id)) _screen.value = Screen.History
        }
    }

    // ---- notices

    fun notice(message: String) {
        _live.update { it.copy(notice = message) }
    }

    fun dismissNotice() {
        _live.update { it.copy(notice = null) }
    }

    fun showPermissionDenied() {
        notice(getApplication<Application>().getString(R.string.permission_denied))
    }

    init {
        viewModelScope.launch { prepareModel() }
        refreshHistory()
    }

    override fun onCleared() {
        tickerJob?.cancel()
        capture.stop()
        transcriber?.close()
        transcriber = null
        // Finish the file synchronously; coroutines are gone by now.
        val writer = wav
        wav = null
        val id = currentId
        if (writer != null && id != null) {
            runCatching { writer.finish() }
            val segments = _live.value.transcript.segments.filter { it.indonesian.isNotBlank() }
            runCatching { store.save(Conversation(id, startedAtMillis, writer.durationMs, segments)) }
        }
        translator.close()
    }
}
