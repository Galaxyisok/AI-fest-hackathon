package com.scamguard.stt

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface SttEvent {
    /** Words so far in the sentence being spoken. Replaced by the next Partial or Final. */
    data class Partial(val text: String) : SttEvent
    /** A finished stretch of speech. */
    data class Final(val text: String) : SttEvent
}

/**
 * Speech-to-text that runs entirely on the phone (Vosk, small English model, Apache-2.0).
 * This is why audio never has to leave the device.
 */
object VoskEngine {
    const val SAMPLE_RATE = 16_000

    private var model: Model? = null
    private val lock = Mutex()

    /** First call unpacks the model from the APK (a few seconds); later calls return at once. */
    suspend fun model(context: Context): Model = lock.withLock {
        model ?: suspendCancellableCoroutine<Model> { cont ->
            StorageService.unpack(
                context.applicationContext, "model-en-us", "model",
                { m -> cont.resume(m) },
                { e -> cont.resumeWithException(e) },
            )
        }.also { model = it }
    }

    class Stream(model: Model) : AutoCloseable {
        private val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

        /** Feed 16 kHz mono 16-bit PCM. Returns what changed, or null if nothing new was heard. */
        fun accept(pcm: ByteArray, length: Int): SttEvent? =
            if (recognizer.acceptWaveForm(pcm, length)) {
                text(recognizer.result, "text")?.let { SttEvent.Final(it) }
            } else {
                text(recognizer.partialResult, "partial")?.let { SttEvent.Partial(it) }
            }

        /** Call once when the audio ends, to collect the last words. */
        fun finish(): SttEvent? = text(recognizer.finalResult, "text")?.let { SttEvent.Final(it) }

        override fun close() = recognizer.close()

        private fun text(json: String, key: String): String? =
            JSONObject(json).optString(key).trim().takeIf { it.isNotEmpty() }
    }
}
