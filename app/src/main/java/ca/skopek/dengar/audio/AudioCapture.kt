package ca.skopek.dengar.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Reads the microphone as 16-bit mono PCM at [SAMPLE_RATE] and hands out 100 ms chunks on a
 * dedicated thread. Falls back to capturing at 48 kHz and averaging pairs of samples on phones
 * whose audio HAL refuses 24 kHz.
 */
class AudioCapture(private val sink: Sink) {

    interface Sink {
        /** 16-bit little-endian mono PCM at [SAMPLE_RATE]. Called on the capture thread. */
        fun onAudio(pcm: ByteArray, length: Int)

        /** Loudness from 0 (silence) to 1. Called on the capture thread. */
        fun onLevel(level: Float)

        /** Capture stopped on its own. Called on the capture thread. */
        fun onCaptureError(message: String)
    }

    private var thread: Thread? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** The caller checks RECORD_AUDIO first; lint cannot see that from here. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val (record, nativeRate) = openRecord() ?: run {
            sink.onCaptureError("Could not open the microphone.")
            return false
        }
        running = true
        thread = Thread({ loop(record, nativeRate) }, "dengar-audio").also { it.start() }
        return true
    }

    /** Stops capture and waits for the last chunk to be delivered. */
    fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): Pair<AudioRecord, Int>? {
        for (rate in intArrayOf(SAMPLE_RATE, SAMPLE_RATE * 2)) {
            val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, rate * 2), // a full second of headroom
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) return record to rate
            record.release()
        }
        return null
    }

    private fun loop(record: AudioRecord, nativeRate: Int) {
        val factor = nativeRate / SAMPLE_RATE
        val native = ShortArray(CHUNK_FRAMES * factor)
        val out = ByteArray(CHUNK_FRAMES * 2)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                sink.onCaptureError("The microphone is being used by another app.")
                return
            }
            while (running) {
                val read = record.read(native, 0, native.size)
                if (read < 0) {
                    sink.onCaptureError("Microphone read failed ($read).")
                    return
                }
                val frames = read / factor
                if (frames == 0) continue
                var sumSquares = 0.0
                for (i in 0 until frames) {
                    var sample = 0
                    for (k in 0 until factor) sample += native[i * factor + k].toInt()
                    sample /= factor
                    out[2 * i] = (sample and 0xFF).toByte()
                    out[2 * i + 1] = ((sample shr 8) and 0xFF).toByte()
                    sumSquares += sample.toDouble() * sample
                }
                sink.onLevel(levelFromRms(sqrt(sumSquares / frames)))
                sink.onAudio(out, frames * 2)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        const val CHUNK_FRAMES = SAMPLE_RATE / 10 // 100 ms

        /** Maps RMS amplitude to 0..1 on a decibel scale: -50 dBFS is silence, 0 dBFS is full. */
        fun levelFromRms(rms: Double): Float {
            if (rms < 1.0) return 0f
            val db = 20.0 * log10(rms / 32768.0)
            return ((db + 50.0) / 50.0).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
