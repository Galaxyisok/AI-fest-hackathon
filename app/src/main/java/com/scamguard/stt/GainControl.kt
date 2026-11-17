package com.scamguard.stt

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Evens out loudness before speech recognition. On a real call the caller's voice reaches the
 * microphone from the loudspeaker, far quieter than the phone owner's own voice, and the speech
 * model's accuracy collapses on quiet audio (measured on real Indian-English speech at -20 dB:
 * see eval/results.md).
 *
 * Works on 100 ms chunks of 16-bit PCM, in place. It follows the recent peak level, turns quiet
 * audio up (never down below the original, never more than [MAX_GAIN] times), eases the gain
 * up slowly and down quickly so a sudden loud voice is not clipped for long, and leaves
 * near-silence alone so background hiss is not amplified into noise.
 *
 * Mirrored by `app_gain` in eval/stt_bench.py, which is how the numbers above were measured.
 */
class GainControl {
    private var envelope = 0.0   // recent peak, 0..1
    private var gain = 1.0

    val currentGain: Double get() = gain

    fun process(pcm: ByteArray, length: Int) {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            val a = abs(s)
            if (a > peak) peak = a
            i += 2
        }
        val level = peak / 32768.0
        envelope = max(level, envelope * DECAY)
        var want = if (envelope > NOISE_FLOOR) min(MAX_GAIN, TARGET / envelope) else gain
        want = max(1.0, want)
        gain += (want - gain) * (if (want < gain) FALL else RISE)
        if (gain <= 1.001) return
        i = 0
        while (i + 1 < length) {
            val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            val v = (s * gain).toInt().coerceIn(-32768, 32767)
            pcm[i] = (v and 0xff).toByte()
            pcm[i + 1] = ((v shr 8) and 0xff).toByte()
            i += 2
        }
    }

    companion object {
        const val TARGET = 0.25        // aim for peaks at a quarter of full scale
        const val MAX_GAIN = 12.0
        const val NOISE_FLOOR = 0.004  // below this it is room noise, not speech
        const val DECAY = 0.97         // per 100 ms chunk
        const val RISE = 0.15
        const val FALL = 0.5
    }
}
