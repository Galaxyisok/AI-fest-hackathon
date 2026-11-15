package com.scamguard.judge

import kotlin.math.abs

/**
 * When to spend a call to the slower, stronger model.
 *
 * The fast model scores every new stretch of speech so the meter moves within a second or two.
 * The deep model re-reads the whole conversation only when it is worth it:
 *   - the first time the score reaches the "be careful" zone
 *   - after that, every [intervalMs] while the call stays in that zone
 *   - straight away if the fast model has moved a long way since the deep model last looked
 *
 * Mirrored by eval/lib/cascade.mjs.
 */
class CascadePolicy(
    private val amber: Double = 35.0,
    private val intervalMs: Long = 30_000,
    private val moveThreshold: Double = 20.0,
) {
    private var lastDeepAt: Long? = null
    private var fastAtLastDeep = 0.0

    /** [fused] is the smoothed score so far; [fastP] is the fast model's newest probability. */
    fun shouldRunDeep(nowMs: Long, fused: Double?, fastP: Double): Boolean {
        val last = lastDeepAt
        if (last == null) return (fused ?: 0.0) >= amber || fastP >= amber
        if (abs(fastP - fastAtLastDeep) >= moveThreshold) return true
        return (fused ?: 0.0) >= amber && nowMs - last >= intervalMs
    }

    fun onDeepStarted(nowMs: Long, fastP: Double) {
        lastDeepAt = nowMs
        fastAtLastDeep = fastP
    }
}
