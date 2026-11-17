package com.scamguard.calls

import com.scamguard.judge.Band
import com.scamguard.judge.CallerPrior
import com.scamguard.judge.CascadePolicy
import com.scamguard.judge.JudgeOutcome
import com.scamguard.judge.PiiRedactor
import com.scamguard.judge.Score
import com.scamguard.judge.ScoreFusion
import com.scamguard.judge.Signal
import com.scamguard.judge.Tier
import com.scamguard.judge.Verdict
import com.scamguard.profile.VulnerabilityProfile
import com.scamguard.stt.SttEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Mode { LIVE, DEMO }

/**
 * Where the live audio comes from. During a call the phone's own processing (echo cancellation,
 * noise suppression) is tuned to REMOVE the caller's voice coming out of the loudspeaker, and how
 * much of it reaches an app differs by phone model. So the path is switchable while listening.
 */
enum class MicMode(val label: String) {
    AUTO("Auto"),            // raw microphone if the phone offers one, otherwise STANDARD
    STANDARD("Standard"),    // VOICE_RECOGNITION
    RAW("Raw"),              // UNPROCESSED: no echo cancellation, no noise suppression
    CAMERA("Camera mic"),    // CAMCORDER: usually a different physical microphone, outside the call processing
    PLAIN("Basic");          // MIC

    companion object { fun from(name: String?) = entries.firstOrNull { it.name == name } ?: AUTO }
}

/** Everything the screen shows. Held in memory only; gone when the call ends. */
data class GuardUi(
    val active: Boolean = false,
    val mode: Mode? = null,
    val title: String = "",
    val preparing: Boolean = false,
    val finished: Boolean = false,
    val score: Score = Score(null, Band.LISTENING, null),
    val reason: String = "",
    val signals: List<Signal> = emptyList(),
    val heard: String = "",                 // what was sent to the judge: numbers already removed
    val codeSpoken: Boolean = false,
    val problem: String? = null,
    val micSilenced: Boolean = false,
    val checks: Int = 0,
    val deepChecks: Int = 0,
)

/** Process-wide handle shared by the service (writer) and the screens (readers). */
object Guard {
    val ui = MutableStateFlow(GuardUi())
    val micMode = MutableStateFlow(MicMode.AUTO)

    /** Set by the pre-answer caller check; used if the user then starts listening. Never written to disk. */
    @Volatile var pendingPrior: CallerPrior? = null
    @Volatile var pendingPriorAt: Long = 0
    fun takePrior(nowMs: Long): CallerPrior =
        pendingPrior?.takeIf { nowMs - pendingPriorAt < 15 * 60_000 } ?: CallerPrior()
}

/**
 * The pipeline from recognised words to the number on screen. Independent of where the audio
 * comes from, so the live microphone and the demo recordings exercise exactly the same code.
 *
 * All methods must be called on [scope], which is confined to one thread.
 */
class GuardSession(
    private val scope: CoroutineScope,
    private val judge: suspend (Tier, String) -> JudgeOutcome,   // receives REDACTED text only
    private val profile: VulnerabilityProfile?,
    private val prior: CallerPrior,
    private val ui: MutableStateFlow<GuardUi>,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val fusion = ScoreFusion(prior)
    private val policy = CascadePolicy()
    private val finals = ArrayList<String>()
    private var partial = ""
    private var judgedWords = 0
    private var pending = false
    private var fastRunning = false
    private var deepRunning = false
    private var codeSpoken = false
    private var confidentSeen = false

    fun onEvent(e: SttEvent) {
        when (e) {
            is SttEvent.Partial -> {
                partial = e.text
                publishHeard()
                // Do not wait for the speaker to pause: re-check after every dozen new words.
                if (wordCount(transcript()) - judgedWords >= WORDS_BETWEEN_CHECKS) kick()
            }
            is SttEvent.Final -> {
                finals.add(e.text)
                partial = ""
                publishHeard()
                kick()
            }
        }
    }

    /** Wait for checks that are still in flight (used when a recording ends). */
    suspend fun awaitIdle(maxMs: Long = 45_000) {
        val start = now()
        while ((pending || fastRunning || deepRunning) && now() - start < maxMs) delay(100)
    }

    private fun transcript(): String =
        (finals + listOfNotNull(partial.takeIf { it.isNotEmpty() })).joinToString("\n")

    private fun redacted(): PiiRedactor.Result {
        // The whole call at once, so a code read out across a pause is still caught.
        val r = PiiRedactor.redact(transcript())
        if (r.codeSpoken) codeSpoken = true
        return PiiRedactor.Result(r.text, codeSpoken)
    }

    private fun publishHeard() {
        val r = redacted()
        ui.update { it.copy(heard = r.text.takeLast(HEARD_CHARS), codeSpoken = r.codeSpoken) }
    }

    private fun kick() {
        pending = true
        if (fastRunning) return
        fastRunning = true
        scope.launch {
            try {
                while (pending) { pending = false; runFast() }
            } finally {
                fastRunning = false
            }
        }
    }

    private suspend fun runFast() {
        val r = redacted()
        val words = wordCount(r.text)
        if (words < MIN_WORDS) return
        judgedWords = words
        when (val out = judge(Tier.FAST, r.text)) {
            is JudgeOutcome.Ok -> {
                val unsure = isUnsureButShowable(out.verdict, words)
                val score = apply(if (unsure) asUnsureEstimate(out.verdict) else out.verdict, unsure)
                // "Still listening" is not a reason to wake the deep model.
                if (out.verdict.confidence != "low") maybeDeep(score, out.verdict.probability.toDouble())
            }
            JudgeOutcome.NoVerdict -> Unit
            is JudgeOutcome.Failed -> ui.update { it.copy(problem = out.userMessage) }
        }
    }

    private fun maybeDeep(score: Score, fastP: Double) {
        if (deepRunning || !policy.shouldRunDeep(now(), score.raw, fastP)) return
        deepRunning = true
        policy.onDeepStarted(now(), fastP)
        scope.launch {
            try {
                val out = judge(Tier.DEEP, redacted().text)
                if (out is JudgeOutcome.Ok) {
                    if (out.verdict.confidence != "low") policy.onDeepVerdict(out.verdict.probability.toDouble())
                    apply(out.verdict)
                    ui.update { it.copy(deepChecks = it.deepChecks + 1) }
                }
                // A failed or declined deep check is not an error for the user: the fast score stands.
            } finally {
                deepRunning = false
            }
        }
    }

    /**
     * The judge says "low confidence" while a call has no clear purpose yet. That is right for the
     * first seconds, but a long ordinary chat (or a badly heard one) stayed on "Listening" for the
     * whole call on real phones. Once enough has been said, show the estimate anyway, capped below
     * red: an unsure guess may say "looks normal" or "be careful", never "likely a scam".
     * Only until the first confident verdict; after that, unsure verdicts are ignored as before.
     */
    private fun isUnsureButShowable(v: Verdict, words: Int) =
        v.confidence == "low" && !confidentSeen && words >= SHOW_UNSURE_WORDS

    private fun asUnsureEstimate(v: Verdict) =
        v.copy(confidence = "medium", probability = minOf(v.probability, UNSURE_CAP))

    private fun apply(v: Verdict, unsure: Boolean = false): Score {
        // The "why" bullets and their quotes always come from the fast model, which looks at every stretch
        // of speech. The deep model may be asked for its verdict only (no evidence list), so it never replaces them.
        val keepBullets = v.tier == Tier.DEEP
        val score = fusion.update(v.toFusionInput(profile))
        if (v.confidence != "low" && !unsure) confidentSeen = true
        ui.update {
            val informative = v.confidence != "low" && !unsure
            it.copy(
                score = score,
                reason = when {
                    informative -> v.reason
                    unsure -> UNSURE_REASON
                    else -> it.reason
                },
                signals = if (informative && !keepBullets) v.signals.filter { s -> s.score >= 2 }.sortedByDescending { s -> s.score } else it.signals,
                problem = null,
                checks = it.checks + 1,
            )
        }
        return score
    }

    companion object {
        const val MIN_WORDS = 8
        const val WORDS_BETWEEN_CHECKS = 12
        const val SHOW_UNSURE_WORDS = 30      // mirrored in eval/run_eval.mjs
        const val UNSURE_CAP = 45             // below ScoreFusion.RED: a guess never raises the alarm
        const val UNSURE_REASON = "The caller has not asked for anything risky so far. Scam Guard is still listening."
        const val HEARD_CHARS = 600
        fun wordCount(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }
    }
}
