package ca.skopek.dengar

import ca.skopek.dengar.realtime.RealtimeEvent
import ca.skopek.dengar.realtime.RealtimeProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeProtocolTest {
    @Test
    fun `session update describes a 24kHz pcm transcription session`() {
        val json = JSONObject(RealtimeProtocol.sessionUpdate("gpt-4o-transcribe", "id"))
        assertEquals("session.update", json.getString("type"))
        val session = json.getJSONObject("session")
        assertEquals("transcription", session.getString("type"))
        val input = session.getJSONObject("audio").getJSONObject("input")
        assertEquals(24000, input.getJSONObject("format").getInt("rate"))
        assertEquals("audio/pcm", input.getJSONObject("format").getString("type"))
        assertEquals("gpt-4o-transcribe", input.getJSONObject("transcription").getString("model"))
        assertEquals("id", input.getJSONObject("transcription").getString("language"))
        assertEquals("server_vad", input.getJSONObject("turn_detection").getString("type"))
    }

    @Test
    fun `audio append base64 encodes only the used part of the buffer`() {
        val json = JSONObject(RealtimeProtocol.audioAppend(byteArrayOf(1, 2, 3, 4), 2))
        assertEquals("input_audio_buffer.append", json.getString("type"))
        assertEquals("AQI=", json.getString("audio"))
    }

    @Test
    fun `server events parse`() {
        assertEquals(
            RealtimeEvent.SpeechStarted("item_1", 1234),
            RealtimeProtocol.parse("""{"type":"input_audio_buffer.speech_started","event_id":"e","item_id":"item_1","audio_start_ms":1234}"""),
        )
        assertEquals(
            RealtimeEvent.SpeechStopped("item_1", 2000),
            RealtimeProtocol.parse("""{"type":"input_audio_buffer.speech_stopped","item_id":"item_1","audio_end_ms":2000}"""),
        )
        assertEquals(
            RealtimeEvent.TranscriptDelta("item_1", "sela"),
            RealtimeProtocol.parse("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"item_1","delta":"sela"}"""),
        )
        assertEquals(
            RealtimeEvent.TranscriptCompleted("item_1", "Selamat pagi."),
            RealtimeProtocol.parse("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1","transcript":"Selamat pagi.","usage":{"type":"duration","seconds":1.5}}"""),
        )
        assertEquals(
            RealtimeEvent.Error("bad key", "invalid_api_key"),
            RealtimeProtocol.parse("""{"type":"error","error":{"type":"invalid_request_error","message":"bad key","code":"invalid_api_key"}}"""),
        )
        assertEquals(RealtimeEvent.SessionReady, RealtimeProtocol.parse("""{"type":"session.updated","session":{}}"""))
        assertTrue(RealtimeProtocol.parse("""{"type":"input_audio_buffer.committed"}""") is RealtimeEvent.Other)
    }
}
