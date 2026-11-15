package com.scamguard.judge

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

enum class Band { LISTENING, GREEN, AMBER, RED }

/** What we know about the caller before a word is spoken. A nudge, never a verdict. */
data class CallerPrior(
    val international: Boolean = false,
    val unverified: Boolean = false,   // network could not verify the caller ID (STIR/SHAKEN failed)
    val hidden: Boolean = false,       // number withheld
)

/** The slice of a judge verdict that the maths needs. */
data class FusionInput(
    val tier: Tier,
    val p: Double,                 // 0..100 from the model
    val confidence: String,        // low | medium | high
    val profileMatch: Int,         // 0..3: how strongly the call matches a threat this user is exposed to
    val matchedLevel: String,      // none | low | medium | high: the user's level for that threat
)

enum class Tier { FAST, DEEP }

data class Score(val display: Int?, val band: Band, val raw: Double?)

/**
 * Turns a stream of model verdicts into the one number the user sees.
 *  - the model's probability dominates; the deep model outweighs the fast one
 *  - caller prior and profile match shift it in log-odds space (so they cannot push a clearly
 *    normal call into a warning by themselves)
 *  - smoothed so the meter does not flicker, and once a call has been red it cannot fall fast:
 *    a scammer saying something reassuring must not clear the warning
 *  - shown rounded to 5: exact scores would let someone tune a script against the detector
 *
 * Mirrored by eval/lib/fusion.mjs; both run against shared/fusion-vectors.json.
 */
class ScoreFusion(prior: CallerPrior = CallerPrior()) {

    private val priorWeight: Double = min(
        PRIOR_CAP,
        (if (prior.international) 0.35 else 0.0) + (if (prior.unverified) 0.35 else 0.0) + (if (prior.hidden) 0.25 else 0.0),
    )
    private var ema: Double? = null
    private var peak = 0.0
    private var lastFast: FusionInput? = null
    private var lastDeep: FusionInput? = null
    private var fastIsNewer = false

    fun update(v: FusionInput): Score {
        if (v.confidence == "low") return snapshot()   // too little speech: never show a guess

        if (v.tier == Tier.DEEP) { lastDeep = v; fastIsNewer = false } else { lastFast = v; fastIsNewer = true }

        val deep = lastDeep
        val fast = lastFast
        val llm = when {
            deep != null && fast != null && fastIsNewer -> DEEP_WEIGHT * deep.p + (1 - DEEP_WEIGHT) * fast.p
            deep != null -> deep.p
            else -> fast!!.p
        }

        val boost = if (v.profileMatch >= 2) BOOST[v.matchedLevel] ?: 0.0 else 0.0
        val shift = priorWeight + boost
        val adjusted = if (shift == 0.0) llm.coerceIn(0.0, 100.0)
        else 100 * sigmoid(logit(llm.coerceIn(2.0, 98.0) / 100) + shift)

        val prev = ema
        val next = if (prev == null) adjusted else {
            val alpha = if (adjusted >= prev) RISE_ALPHA else FALL_ALPHA
            var n = alpha * adjusted + (1 - alpha) * prev
            if (peak >= RED) n = max(n, prev - MAX_DROP_AFTER_RED)
            n
        }
        ema = next
        peak = max(peak, next)
        return snapshot()
    }

    fun snapshot(): Score {
        val e = ema ?: return Score(null, Band.LISTENING, null)
        val display = Math.round(e / 5.0).toInt() * 5
        return Score(display, bandFor(display), e)
    }

    companion object {
        const val AMBER = 30
        const val RED = 60
        private const val PRIOR_CAP = 0.7
        private val BOOST = mapOf("high" to 0.5, "medium" to 0.25, "low" to 0.0, "none" to 0.0)
        private const val RISE_ALPHA = 0.7
        private const val FALL_ALPHA = 0.4
        private const val DEEP_WEIGHT = 0.65
        private const val MAX_DROP_AFTER_RED = 8.0

        private fun logit(p: Double) = ln(p / (1 - p))
        private fun sigmoid(x: Double) = 1 / (1 + exp(-x))

        fun bandFor(display: Int?): Band = when {
            display == null -> Band.LISTENING
            display >= RED -> Band.RED
            display >= AMBER -> Band.AMBER
            else -> Band.GREEN
        }
    }
}
