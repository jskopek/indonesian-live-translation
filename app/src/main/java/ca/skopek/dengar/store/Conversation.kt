package ca.skopek.dengar.store

import ca.skopek.dengar.transcript.Segment
import org.json.JSONArray
import org.json.JSONObject

/** A saved conversation: the recording lives next to this as audio.wav. */
data class Conversation(
    val id: String,
    val startedAtMillis: Long,
    val durationMs: Long,
    val segments: List<Segment>,
    val title: String? = null,
    val summary: String? = null,
) {
    /** Something to show in a list when there is no title: the first words heard. */
    val preview: String
        get() = title ?: segments.firstOrNull { it.indonesian.isNotBlank() }?.let { it.english ?: it.indonesian }.orEmpty()
}

object ConversationCodec {
    fun encode(conversation: Conversation): String {
        val segments = JSONArray()
        conversation.segments.forEach { segment ->
            segments.put(
                JSONObject()
                    .put("id", segment.id)
                    .put("indonesian", segment.indonesian)
                    .put("english", segment.english ?: JSONObject.NULL)
                    .put("isFinal", segment.isFinal)
                    .put("startMs", segment.startMs ?: JSONObject.NULL)
                    .put("endMs", segment.endMs ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("id", conversation.id)
            .put("startedAtMillis", conversation.startedAtMillis)
            .put("durationMs", conversation.durationMs)
            .put("title", conversation.title ?: JSONObject.NULL)
            .put("summary", conversation.summary ?: JSONObject.NULL)
            .put("segments", segments)
            .toString(2)
    }

    fun decode(json: String): Conversation {
        val root = JSONObject(json)
        val array = root.optJSONArray("segments") ?: JSONArray()
        val segments = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Segment(
                id = item.getString("id"),
                indonesian = item.optString("indonesian"),
                english = item.optNullableString("english"),
                isFinal = item.optBoolean("isFinal", true),
                startMs = item.optNullableLong("startMs"),
                endMs = item.optNullableLong("endMs"),
            )
        }
        return Conversation(
            id = root.getString("id"),
            startedAtMillis = root.getLong("startedAtMillis"),
            durationMs = root.optLong("durationMs"),
            segments = segments,
            title = root.optNullableString("title"),
            summary = root.optNullableString("summary"),
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key)) null else optLong(key)
}
