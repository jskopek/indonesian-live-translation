package ca.skopek.dengar

import ca.skopek.dengar.audio.WavHeader
import org.junit.Assert.assertEquals
import org.junit.Test

class WavHeaderTest {
    @Test
    fun `header is 44 bytes of canonical pcm wav`() {
        val header = WavHeader.build(sampleRate = 24000, channels = 1, bitsPerSample = 16, dataBytes = 48000)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4))
        assertEquals("WAVE", String(header, 8, 4))
        assertEquals("fmt ", String(header, 12, 4))
        assertEquals("data", String(header, 36, 4))
        assertEquals(48000, WavHeader.dataBytes(header))
        // byte rate = 24000 * 1 * 2 = 48000 -> 0x0000BB80 little-endian at offset 28
        assertEquals(0x80.toByte(), header[28])
        assertEquals(0xBB.toByte(), header[29])
        assertEquals(1.toByte(), header[22]) // channels
        assertEquals(16.toByte(), header[34]) // bits per sample
    }

    @Test
    fun `dataBytes rejects non-wav input`() {
        assertEquals(-1, WavHeader.dataBytes(ByteArray(10)))
        assertEquals(-1, WavHeader.dataBytes(ByteArray(44)))
    }
}
