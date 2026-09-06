package ca.skopek.dengar.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** On-device Indonesian → English translation via ML Kit. Needs a one-time model download. */
class IndonesianTranslator {
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.INDONESIAN)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build(),
    )

    suspend fun ensureModel() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    suspend fun translate(text: String): String = translator.translate(text).await()

    fun close() = translator.close()
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
