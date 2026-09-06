package ca.skopek.dengar.transcript

/**
 * One utterance as the transcription service sees it. [id] is the service's item id, so
 * deltas, timings and the final text for the same utterance all land on the same segment.
 * [startMs]/[endMs] are offsets into the saved recording.
 */
data class Segment(
    val id: String,
    val indonesian: String = "",
    val english: String? = null,
    val isFinal: Boolean = false,
    val startMs: Long? = null,
    val endMs: Long? = null,
)

/** Immutable transcript of one conversation. Pure Kotlin so it can be unit tested without Android. */
data class Transcript(val segments: List<Segment> = emptyList()) {

    val isEmpty: Boolean get() = segments.isEmpty()

    val hasPendingSegments: Boolean get() = segments.any { !it.isFinal }

    fun speechStarted(id: String, startMs: Long): Transcript =
        upsert(id) { it.copy(startMs = it.startMs ?: startMs) }

    fun speechStopped(id: String, endMs: Long): Transcript =
        upsert(id) { it.copy(endMs = endMs) }

    /** Incremental transcript text for an utterance still being recognised. */
    fun delta(id: String, text: String): Transcript =
        upsert(id) { it.copy(indonesian = it.indonesian + text, english = null) }

    /** Final text for an utterance. A blank final means the service heard nothing usable. */
    fun completed(id: String, transcript: String): Transcript {
        val text = transcript.trim()
        if (text.isEmpty()) return copy(segments = segments.filterNot { it.id == id })
        return upsert(id) { segment ->
            segment.copy(
                indonesian = text,
                isFinal = true,
                english = segment.english.takeIf { segment.indonesian.trim() == text },
            )
        }
    }

    /** Apply a translation only if the segment still reads [forIndonesian]; otherwise it is stale. */
    fun translated(id: String, forIndonesian: String, english: String): Transcript {
        val index = segments.indexOfFirst { it.id == id }
        if (index < 0 || segments[index].indonesian != forIndonesian) return this
        return upsert(id) { it.copy(english = english) }
    }

    fun untranslated(): List<Segment> = segments.filter { it.english == null && it.indonesian.isNotBlank() }

    fun cleared(): Transcript = Transcript()

    private fun upsert(id: String, transform: (Segment) -> Segment): Transcript {
        val index = segments.indexOfFirst { it.id == id }
        return if (index < 0) {
            copy(segments = segments + transform(Segment(id)))
        } else {
            copy(segments = segments.toMutableList().also { it[index] = transform(it[index]) })
        }
    }
}
