package ca.skopek.dengar.transcript

/** One finished utterance from the speaker. `english` is null until the translation arrives. */
data class Segment(
    val id: Long,
    val indonesian: String,
    val english: String?,
    val startedAtMillis: Long,
)

/**
 * Immutable transcript of a listening session: finished segments plus the utterance currently
 * being recognised. Pure Kotlin so it can be unit tested without Android.
 */
data class Transcript(
    val segments: List<Segment> = emptyList(),
    val partialIndonesian: String = "",
    val partialEnglish: String = "",
    val nextId: Long = 1,
) {
    val isEmpty: Boolean get() = segments.isEmpty() && partialIndonesian.isBlank()

    /** The recogniser produced a new best guess for the utterance in progress. */
    fun withPartial(indonesian: String): Transcript =
        if (indonesian == partialIndonesian) this else copy(partialIndonesian = indonesian, partialEnglish = "")

    /** A translation for a partial arrived; ignored if the partial has moved on since. */
    fun withPartialTranslation(indonesian: String, english: String): Transcript =
        if (indonesian == partialIndonesian) copy(partialEnglish = english) else this

    /** The recogniser finalised the utterance. Blank finals just clear the partial. */
    fun commit(indonesian: String, nowMillis: Long): Transcript {
        val text = indonesian.trim()
        if (text.isEmpty()) return clearPartial()
        val english = partialEnglish.takeIf { text == partialIndonesian.trim() && it.isNotBlank() }
        return copy(
            segments = segments + Segment(nextId, text, english, nowMillis),
            partialIndonesian = "",
            partialEnglish = "",
            nextId = nextId + 1,
        )
    }

    fun clearPartial(): Transcript = copy(partialIndonesian = "", partialEnglish = "")

    fun withTranslation(id: Long, english: String): Transcript =
        copy(segments = segments.map { if (it.id == id) it.copy(english = english) else it })

    fun untranslated(): List<Segment> = segments.filter { it.english == null }

    fun cleared(): Transcript = copy(segments = emptyList(), partialIndonesian = "", partialEnglish = "")
}
