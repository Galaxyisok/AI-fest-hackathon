package com.scamguard.stt

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SttEvent {
    /** Words so far in the sentence being spoken. Replaced by the next Partial or Final. */
    data class Partial(val text: String) : SttEvent
    /** A finished stretch of speech. */
    data class Final(val text: String) : SttEvent
}

/**
 * Speech-to-text that runs entirely on the phone, which is why audio never has to leave the device.
 *
 * Engine: sherpa-onnx (Apache-2.0) with the streaming Zipformer "Kroko" English model (CC-BY-SA).
 * Chosen by measurement on real Indian-English speech (eval/results.md): on phone-quality audio it
 * gets 28% of words wrong where the earlier Vosk model got 67%, and it is the only engine tested
 * that recovers anything from a faint caller heard through the loudspeaker.
 */
object SpeechEngine {
    const val SAMPLE_RATE = 16_000
    private const val DIR = "model-kroko"

    private var recognizer: OnlineRecognizer? = null
    private val lock = Mutex()

    /** First call loads the model from the APK (a second or two); later calls return at once. */
    suspend fun model(context: Context): OnlineRecognizer = lock.withLock {
        recognizer ?: withContext(Dispatchers.IO) {
            val config = OnlineRecognizerConfig().apply {
                modelConfig = OnlineModelConfig().apply {
                    transducer = OnlineTransducerModelConfig().apply {
                        encoder = "$DIR/encoder.onnx"
                        decoder = "$DIR/decoder.onnx"
                        joiner = "$DIR/joiner.onnx"
                    }
                    tokens = "$DIR/tokens.txt"
                    numThreads = 2
                }
                // A stretch of speech ends after 0.8 s of quiet, or 2.4 s of quiet with nothing said, or 20 s.
                endpointConfig = EndpointConfig(
                    EndpointRule(false, 2.4f, 0f),
                    EndpointRule(true, 0.8f, 0f),
                    EndpointRule(false, 0f, 20f),
                )
                enableEndpoint = true
                decodingMethod = "greedy_search"
            }
            OnlineRecognizer(context.applicationContext.assets, config)
        }.also { recognizer = it }
    }

    class Stream(private val recognizer: OnlineRecognizer) : AutoCloseable {
        private val stream = recognizer.createStream("")
        private var lastPartial = ""

        /** Feed 16 kHz mono 16-bit PCM. Returns what changed, or null if nothing new was heard. */
        fun accept(pcm: ByteArray, length: Int): SttEvent? {
            stream.acceptWaveform(toFloats(pcm, length), SAMPLE_RATE)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val text = normalise(recognizer.getResult(stream).text)
            if (recognizer.isEndpoint(stream)) {
                recognizer.reset(stream)
                lastPartial = ""
                return if (text.isNotEmpty()) SttEvent.Final(text) else null
            }
            if (text.isEmpty() || text == lastPartial) return null
            lastPartial = text
            return SttEvent.Partial(text)
        }

        /** Call once when the audio ends, to collect the last words. */
        fun finish(): SttEvent? {
            stream.acceptWaveform(FloatArray(SAMPLE_RATE * 8 / 10), SAMPLE_RATE)   // the model needs a little trailing quiet
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return normalise(recognizer.getResult(stream).text).takeIf { it.isNotEmpty() }?.let { SttEvent.Final(it) }
        }

        override fun close() = stream.release()
    }

    fun toFloats(pcm: ByteArray, length: Int): FloatArray {
        val out = FloatArray(length / 2)
        for (i in out.indices) {
            val s = (pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)
            out[i] = s / 32768f
        }
        return out
    }

    private val NOT_WORD = Regex("[^a-z0-9' ]+")
    private val SPACES = Regex("\\s+")

    /**
     * The model writes "It says four, eight two nine." The rest of the app (number removal above all)
     * expects plain lower-case words, so capitals and punctuation are dropped here.
     */
    fun normalise(text: String): String =
        text.lowercase().replace(NOT_WORD, " ").replace(SPACES, " ").trim()
}
