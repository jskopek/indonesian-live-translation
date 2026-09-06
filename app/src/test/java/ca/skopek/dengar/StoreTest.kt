package ca.skopek.dengar

import ca.skopek.dengar.audio.WavHeader
import ca.skopek.dengar.audio.WavWriter
import ca.skopek.dengar.store.Conversation
import ca.skopek.dengar.store.ConversationStore
import ca.skopek.dengar.transcript.Segment
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `wav writer patches the header on finish`() {
        val file = File(folder.root, "a.wav")
        val writer = WavWriter(file, 24000)
        writer.write(ByteArray(4800), 4800)
        writer.write(ByteArray(100), 40)
        assertEquals(4840L, writer.bytesWritten)
        assertEquals(100L, writer.durationMs)
        writer.finish()
        assertEquals(44L + 4840, file.length())
        val header = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        assertEquals(4840, WavHeader.dataBytes(header))
        assertFalse(WavWriter.needsRepair(file))
    }

    @Test
    fun `an unfinished wav is detected and repaired by list`() {
        val store = ConversationStore(File(folder.root, "conversations"))
        val id = store.newId(1_757_153_700_000)
        assertEquals(15, id.length)
        val writer = WavWriter(store.audioFile(id), 24000)
        writer.write(ByteArray(48000), 48000) // one second, never finished
        writer.flush()
        store.save(Conversation(id, 1_757_153_700_000, 0, listOf(Segment("a", "halo", null, true, 0, 900))))
        assertTrue(WavWriter.needsRepair(store.audioFile(id)))

        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals(1000L, listed.single().durationMs)
        assertFalse(WavWriter.needsRepair(store.audioFile(id)))
        assertEquals(1000L, store.load(id)!!.durationMs)
    }

    @Test
    fun `list is newest first and delete removes everything`() {
        val store = ConversationStore(File(folder.root, "conversations"))
        store.save(Conversation("old", 1000, 10, emptyList()))
        store.save(Conversation("new", 2000, 10, emptyList()))
        assertEquals(listOf("new", "old"), store.list().map { it.id })
        store.delete("new")
        assertEquals(listOf("old"), store.list().map { it.id })
        assertNull(store.load("new"))
    }
}
