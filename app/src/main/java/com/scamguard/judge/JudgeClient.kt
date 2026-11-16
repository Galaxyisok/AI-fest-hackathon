package com.scamguard.judge

import com.scamguard.net.ServerAddress
import com.scamguard.profile.VulnerabilityProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One signal the judge scored, with the words it heard as evidence. */
data class Signal(val key: String, val score: Int, val evidence: String)

data class Verdict(
    val tier: Tier,
    val probability: Int,          // 0..100
    val confidence: String,        // low | medium | high
    val reason: String,
    val matchedThreat: String,     // a ThreatType key, "other_scam" or "none"
    val signals: List<Signal>,
) {
    val profileMatch: Int get() = signals.firstOrNull { it.key == "profile_match" }?.score ?: 0

    fun toFusionInput(profile: VulnerabilityProfile?): FusionInput {
        val level = profile?.threats?.firstOrNull { it.type.key == matchedThreat }?.level?.key ?: "none"
        return FusionInput(tier, probability.toDouble(), confidence, profileMatch, level)
    }

    companion object {
        /** Returns null if the body is the proxy saying "no verdict this time". Throws on garbage. */
        fun parse(body: String, tier: Tier): Verdict? {
            val o = JSONObject(body)
            if (o.has("unavailable")) return null
            val sig = o.getJSONObject("signals")
            val signals = sig.keys().asSequence().map { k ->
                val s = sig.getJSONObject(k)
                Signal(k, s.optInt("score", 0).coerceIn(0, 3), s.optString("evidence", "none"))
            }.toList()
            return Verdict(
                tier = tier,
                probability = o.getInt("scam_probability").coerceIn(0, 100),
                confidence = o.optString("confidence", "low"),
                reason = o.optString("reason"),
                matchedThreat = o.optString("matched_threat", "none"),
                signals = signals,
            )
        }
    }
}

sealed interface JudgeOutcome {
    data class Ok(val verdict: Verdict) : JudgeOutcome
    /** The judge answered but had no verdict (declined or cut off). Keep the last score. */
    data object NoVerdict : JudgeOutcome
    /** Could not reach the judge. The user is told, in plain words. */
    data class Failed(val userMessage: String) : JudgeOutcome
}

/**
 * Sends REDACTED TEXT to the judge proxy. This is the only network call in the app.
 * No audio, no phone number, no profile free-text ("why") is sent: only threat type + level.
 */
class JudgeClient(private val url: String, private val fallbackUrl: String? = null) {

    suspend fun judge(
        tier: Tier,
        redactedTranscript: String,
        profile: VulnerabilityProfile?,
        prior: CallerPrior,
    ): JudgeOutcome = withContext(Dispatchers.IO) {
        val body = requestBody(tier, redactedTranscript, profile, prior)
        // A saved address that is dead or malformed must not strand the app: fall back to the built-in one.
        val urls = listOfNotNull(url, fallbackUrl).distinct()
        val badAddress = JudgeOutcome.Failed("The checking service address is not valid. Fix it in Setup, then Developer settings.")
        var failed: JudgeOutcome = badAddress
        for (u in urls) {
            try {
                return@withContext post(u, body, tier)
            } catch (e: java.net.MalformedURLException) {
                failed = badAddress
            } catch (e: java.io.IOException) {
                // Names the server and what failed; a wrong or blocked address used to read as "no internet".
                failed = JudgeOutcome.Failed(ServerAddress.describeFailure(e, u, "checking service"))
            } catch (e: Exception) {
                return@withContext JudgeOutcome.Failed("The checking service sent something unexpected. Still listening.")
            }
        }
        failed
    }

    private fun post(u: String, body: String, tier: Tier): JudgeOutcome {
        val conn = (URL(u).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout = if (tier == Tier.DEEP) 30_000 else 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            return when (val code = conn.responseCode) {
                200 -> {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    Verdict.parse(text, tier)?.let { JudgeOutcome.Ok(it) } ?: JudgeOutcome.NoVerdict
                }
                429 -> JudgeOutcome.Failed("The checking service is busy. Still listening.")
                else -> JudgeOutcome.Failed("The checking service had a problem ($code). Still listening.")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MAX_CHARS = 6000

        fun requestBody(tier: Tier, redactedTranscript: String, profile: VulnerabilityProfile?, prior: CallerPrior): String =
            JSONObject().apply {
                put("tier", if (tier == Tier.DEEP) "deep" else "fast")
                put("transcript", redactedTranscript.takeLast(MAX_CHARS))
                put("profile", JSONObject().apply {
                    put("userType", profile?.userType?.key ?: "unknown")
                    put("threats", JSONArray().apply {
                        profile?.threats?.forEach { t ->
                            put(JSONObject().apply { put("type", t.type.key); put("level", t.level.key) })
                        }
                    })
                })
                put("prior", JSONObject().apply {
                    put("international", prior.international)
                    put("unverified", prior.unverified)
                    put("hidden", prior.hidden)
                })
            }.toString()
    }
}
