package ca.skopek.dengar.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds the 44-byte canonical RIFF/WAVE header for little-endian PCM. */
object WavHeader {
    const val SIZE = 44

    fun build(sampleRate: Int, channels: Int, bitsPerSample: Int, dataBytes: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        return ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk size
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
    }

    /** The data chunk size recorded in an existing header, or -1 if it does not look like a WAV. */
    fun dataBytes(header: ByteArray): Int {
        if (header.size < SIZE) return -1
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") return -1
        return buffer.getInt(40)
    }
}
