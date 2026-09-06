package ca.skopek.dengar

import ca.skopek.dengar.transcript.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    @Test
    fun `partial then commit becomes a segment and clears the partial`() {
        val t = Transcript().withPartial("selamat pagi").commit("selamat pagi", 1000)
        assertEquals(1, t.segments.size)
        assertEquals("selamat pagi", t.segments[0].indonesian)
        assertEquals("", t.partialIndonesian)
        assertEquals(2, t.nextId)
    }

    @Test
    fun `commit reuses the partial translation when the text matches`() {
        val t = Transcript()
            .withPartial("selamat pagi")
            .withPartialTranslation("selamat pagi", "good morning")
            .commit("selamat pagi ", 1000)
        assertEquals("good morning", t.segments[0].english)
        assertTrue(t.untranslated().isEmpty())
    }

    @Test
    fun `commit drops the partial translation when the final differs`() {
        val t = Transcript()
            .withPartial("selamat")
            .withPartialTranslation("selamat", "congratulations")
            .commit("selamat pagi", 1000)
        assertNull(t.segments[0].english)
        assertEquals(listOf(1L), t.untranslated().map { it.id })
    }

    @Test
    fun `stale partial translations are ignored`() {
        val t = Transcript()
            .withPartial("selamat")
            .withPartial("selamat pagi")
            .withPartialTranslation("selamat", "congratulations")
        assertEquals("", t.partialEnglish)
    }

    @Test
    fun `blank final only clears the partial`() {
        val t = Transcript().withPartial("hm").commit("  ", 1000)
        assertTrue(t.segments.isEmpty())
        assertTrue(t.isEmpty)
    }

    @Test
    fun `withTranslation fills the matching segment only`() {
        val t = Transcript().commit("satu", 1).commit("dua", 2).withTranslation(2, "two")
        assertNull(t.segments[0].english)
        assertEquals("two", t.segments[1].english)
    }

    @Test
    fun `cleared keeps the id counter moving forward`() {
        val t = Transcript().commit("satu", 1).cleared().commit("dua", 2)
        assertEquals(2L, t.segments.single().id)
    }
}
