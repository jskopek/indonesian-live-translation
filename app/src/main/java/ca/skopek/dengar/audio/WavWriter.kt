package ca.skopek.dengar.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Streams 16-bit mono PCM into a WAV file. The header is written with a zero data size and
 * patched by [finish]; [repair] fixes a file whose recording was cut off before that.
 */
class WavWriter(private val file: File, private val sampleRate: Int) {
    private val output = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
    @Volatile
    var bytesWritten: Long = 0
        private set

    init {
        output.write(WavHeader.build(sampleRate, channels = 1, bitsPerSample = 16, dataBytes = 0))
    }

    val durationMs: Long get() = bytesWritten * 1000 / (sampleRate * 2)

    @Synchronized
    fun write(pcm: ByteArray, length: Int) {
        output.write(pcm, 0, length)
        bytesWritten += length
    }

    /** Push buffered audio to disk so a crash loses at most what came in since. */
    @Synchronized
    fun flush() = output.flush()

    @Synchronized
    fun finish() {
        output.flush()
        output.close()
        patchHeader(file, sampleRate)
    }

    companion object {
        /** Rewrites the header sizes from the file length. Returns the data length in bytes. */
        fun patchHeader(file: File, sampleRate: Int): Long {
            val dataBytes = (file.length() - WavHeader.SIZE).coerceAtLeast(0)
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(WavHeader.build(sampleRate, channels = 1, bitsPerSample = 16, dataBytes = dataBytes.toInt()))
            }
            return dataBytes
        }

        /** True if the header says the file is empty but the file has audio in it. */
        fun needsRepair(file: File): Boolean {
            if (!file.exists() || file.length() <= WavHeader.SIZE) return false
            val header = ByteArray(WavHeader.SIZE)
            RandomAccessFile(file, "r").use { raf -> raf.readFully(header) }
            return WavHeader.dataBytes(header) == 0
        }
    }
}
