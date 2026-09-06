package ca.skopek.dengar.realtime

import java.util.Base64
import org.json.JSONObject

/** Server events from an OpenAI Realtime transcription session that Dengar acts on. */
sealed interface RealtimeEvent {
    data object SessionReady : RealtimeEvent
    data class SpeechStarted(val itemId: String, val audioStartMs: Long) : RealtimeEvent
    data class SpeechStopped(val itemId: String, val audioEndMs: Long) : RealtimeEvent
    data class TranscriptDelta(val itemId: String, val delta: String) : RealtimeEvent
    data class TranscriptCompleted(val itemId: String, val transcript: String) : RealtimeEvent
    data class TranscriptFailed(val itemId: String, val message: String) : RealtimeEvent
    data class Error(val message: String, val code: String?) : RealtimeEvent
    data class Other(val type: String) : RealtimeEvent
}

object RealtimeProtocol {
    const val URL = "wss://api.openai.com/v1/realtime?intent=transcription"
    const val SAMPLE_RATE = 24_000

    /** Client event that configures the session. Sent once, right after the socket opens. */
    fun sessionUpdate(model: String, language: String): String {
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
            .put("noise_reduction", JSONObject().put("type", "far_field"))
            .put("transcription", JSONObject().put("model", model).put("language", language))
            .put(
                "turn_detection",
                JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", 0.5)
                    .put("prefix_padding_ms", 300)
                    .put("silence_duration_ms", 600),
            )
        val session = JSONObject()
            .put("type", "transcription")
            .put("audio", JSONObject().put("input", input))
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    fun audioAppend(pcm: ByteArray, length: Int): String {
        val encoded = Base64.getEncoder().encodeToString(pcm.copyOf(length))
        return JSONObject().put("type", "input_audio_buffer.append").put("audio", encoded).toString()
    }

    fun audioCommit(): String = JSONObject().put("type", "input_audio_buffer.commit").toString()

    fun parse(json: String): RealtimeEvent {
        val event = JSONObject(json)
        return when (val type = event.optString("type")) {
            "session.created", "session.updated", "transcription_session.created", "transcription_session.updated" ->
                RealtimeEvent.SessionReady

            "input_audio_buffer.speech_started" ->
                RealtimeEvent.SpeechStarted(event.optString("item_id"), event.optLong("audio_start_ms"))

            "input_audio_buffer.speech_stopped" ->
                RealtimeEvent.SpeechStopped(event.optString("item_id"), event.optLong("audio_end_ms"))

            "conversation.item.input_audio_transcription.delta" ->
                RealtimeEvent.TranscriptDelta(event.optString("item_id"), event.optString("delta"))

            "conversation.item.input_audio_transcription.completed" ->
                RealtimeEvent.TranscriptCompleted(event.optString("item_id"), event.optString("transcript"))

            "conversation.item.input_audio_transcription.failed" -> {
                val error = event.optJSONObject("error")
                RealtimeEvent.TranscriptFailed(
                    event.optString("item_id"),
                    error?.optString("message").orEmpty().ifBlank { "transcription failed" },
                )
            }

            "error" -> {
                val error = event.optJSONObject("error")
                RealtimeEvent.Error(
                    message = error?.optString("message").orEmpty().ifBlank { "unknown error" },
                    code = error?.optString("code")?.takeIf { it.isNotBlank() },
                )
            }

            else -> RealtimeEvent.Other(type)
        }
    }
}
