package ca.skopek.dengar.settings

import android.content.Context

/** Tiny persisted settings. The API key lives in app-private storage on the phone only. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("dengar", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var transcribeModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    companion object {
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_MODEL = "transcribe_model"
        const val DEFAULT_MODEL = "gpt-4o-transcribe"
        val MODELS = listOf(
            "gpt-4o-transcribe" to "Best accuracy (about \$0.006 per minute)",
            "gpt-4o-mini-transcribe" to "Cheaper and faster (about \$0.003 per minute)",
        )
    }
}
