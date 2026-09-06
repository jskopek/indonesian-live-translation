package ca.skopek.dengar.store

import ca.skopek.dengar.audio.WavWriter
import ca.skopek.dengar.realtime.RealtimeProtocol
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Conversations on disk: `<root>/<id>/transcript.json` plus `<root>/<id>/audio.wav`.
 * Plain files rather than a database so a conversation can be copied off the phone as-is.
 */
class ConversationStore(private val root: File) {

    init {
        root.mkdirs()
    }

    fun newId(nowMillis: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis))

    fun directory(id: String): File = File(root, id).also { it.mkdirs() }

    fun audioFile(id: String): File = File(directory(id), AUDIO_FILE)

    private fun transcriptFile(id: String): File = File(directory(id), TRANSCRIPT_FILE)

    fun save(conversation: Conversation) {
        val target = transcriptFile(conversation.id)
        val temp = File(target.parentFile, "$TRANSCRIPT_FILE.tmp")
        temp.writeText(ConversationCodec.encode(conversation))
        if (!temp.renameTo(target)) {
            target.writeText(ConversationCodec.encode(conversation))
            temp.delete()
        }
    }

    fun load(id: String): Conversation? {
        val file = transcriptFile(id)
        if (!file.exists()) return null
        return runCatching { ConversationCodec.decode(file.readText()) }.getOrNull()
    }

    /** All saved conversations, newest first. Also repairs recordings cut off by a crash. */
    fun list(): List<Conversation> {
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val audio = File(dir, AUDIO_FILE)
            if (WavWriter.needsRepair(audio)) WavWriter.patchHeader(audio, RealtimeProtocol.SAMPLE_RATE)
            load(dir.name)?.let { conversation ->
                if (conversation.durationMs == 0L && audio.exists()) {
                    conversation.copy(durationMs = wavDurationMs(audio)).also { save(it) }
                } else {
                    conversation
                }
            }
        }.sortedByDescending { it.startedAtMillis }
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    private fun wavDurationMs(audio: File): Long =
        (audio.length() - 44).coerceAtLeast(0) * 1000 / (RealtimeProtocol.SAMPLE_RATE * 2)

    companion object {
        const val AUDIO_FILE = "audio.wav"
        const val TRANSCRIPT_FILE = "transcript.json"
    }
}
