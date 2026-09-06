package ca.skopek.dengar.playback

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import java.io.File

/** MediaPlayer wrapper for a saved recording with slow-speed playback. Main thread only. */
class ConversationPlayer(file: File, onCompletion: () -> Unit) {
    private val player = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build(),
        )
        setDataSource(file.absolutePath)
        setOnCompletionListener { onCompletion() }
        prepare()
    }

    val durationMs: Long get() = player.duration.toLong().coerceAtLeast(0)
    val positionMs: Long get() = player.currentPosition.toLong().coerceAtLeast(0)
    val isPlaying: Boolean get() = player.isPlaying

    /** 1.0 is normal. Takes effect immediately while playing, otherwise on the next [play]. */
    var speed: Float = 1f
        set(value) {
            field = value
            if (player.isPlaying) applySpeed()
        }

    fun play() {
        // Setting playback params on a paused player starts it; start() is then a no-op.
        applySpeed()
        if (!player.isPlaying) player.start()
    }

    fun pause() {
        if (player.isPlaying) player.pause()
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceIn(0, durationMs), MediaPlayer.SEEK_CLOSEST)
    }

    fun release() {
        runCatching { player.stop() }
        player.release()
    }

    private fun applySpeed() {
        player.playbackParams = PlaybackParams().setSpeed(speed)
    }
}
