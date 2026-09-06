package ca.skopek.dengar

import ca.skopek.dengar.store.Conversation
import ca.skopek.dengar.store.ConversationCodec
import ca.skopek.dengar.transcript.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationCodecTest {
    @Test
    fun `round trips through json`() {
        val original = Conversation(
            id = "20260906-101500",
            startedAtMillis = 1_757_153_700_000,
            durationMs = 61_000,
            segments = listOf(
                Segment("a", "Selamat pagi.", "Good morning.", isFinal = true, startMs = 300, endMs = 1800),
                Segment("b", "Apa kabar?", null, isFinal = true, startMs = 2500, endMs = null),
            ),
            title = null,
            summary = "A greeting.",
        )
        val decoded = ConversationCodec.decode(ConversationCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals("Good morning.", decoded.preview)
    }
}
