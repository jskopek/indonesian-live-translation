package ca.skopek.dengar

import ca.skopek.dengar.transcript.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    @Test
    fun `speech start, deltas and completion land on one segment in order`() {
        val t = Transcript()
            .speechStarted("a", 1000)
            .delta("a", "selamat")
            .delta("a", " pagi")
            .speechStopped("a", 2400)
            .completed("a", "Selamat pagi.")
        assertEquals(1, t.segments.size)
        val s = t.segments.single()
        assertEquals("Selamat pagi.", s.indonesian)
        assertTrue(s.isFinal)
        assertEquals(1000L, s.startMs)
        assertEquals(2400L, s.endMs)
        assertFalse(t.hasPendingSegments)
    }

    @Test
    fun `segments keep the order speech started even if completions arrive out of order`() {
        val t = Transcript()
            .speechStarted("a", 0)
            .speechStarted("b", 3000)
            .completed("b", "dua")
            .completed("a", "satu")
        assertEquals(listOf("satu", "dua"), t.segments.map { it.indonesian })
    }

    @Test
    fun `a second speech start does not move an existing start`() {
        val t = Transcript().speechStarted("a", 100).speechStarted("a", 900)
        assertEquals(100L, t.segments.single().startMs)
    }

    @Test
    fun `completion keeps a translation only when the text is unchanged`() {
        val kept = Transcript().delta("a", "selamat pagi").translated("a", "selamat pagi", "good morning").completed("a", "selamat pagi")
        assertEquals("good morning", kept.segments.single().english)
        val dropped = Transcript().delta("a", "selamat").translated("a", "selamat", "congrats").completed("a", "selamat pagi")
        assertNull(dropped.segments.single().english)
        assertEquals(listOf("a"), dropped.untranslated().map { it.id })
    }

    @Test
    fun `stale translations are ignored`() {
        val t = Transcript().delta("a", "sel").delta("a", "amat").translated("a", "sel", "x")
        assertNull(t.segments.single().english)
        assertEquals(Transcript().delta("z", "q"), Transcript().delta("z", "q").translated("missing", "q", "x"))
    }

    @Test
    fun `blank completion removes the segment`() {
        val t = Transcript().speechStarted("a", 0).completed("a", "  ")
        assertTrue(t.isEmpty)
    }

    @Test
    fun `delta on a completed segment invalidates its translation`() {
        val t = Transcript().completed("a", "satu").translated("a", "satu", "one").delta("a", " dua")
        assertNull(t.segments.single().english)
    }
}
