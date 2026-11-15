package com.scamguard

import com.scamguard.phase1.OnboardingAnswers
import com.scamguard.phase1.OnboardingClient
import com.scamguard.phase1.OnboardingQuestions
import com.scamguard.phase1.onboardingErrorMessage
import com.scamguard.profile.Level
import com.scamguard.profile.ThreatType
import com.scamguard.profile.UserType
import com.scamguard.profile.VulnerabilityProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 1 (Python backend) and the Android app were written separately. These tests read the
 * backend's own contract files, so a change on either side that breaks the other fails here.
 */
class OnboardingContractTest {

    private fun repoFile(path: String) = listOf(File("../$path"), File(path)).first { it.exists() }
    private fun json(path: String) = JSONObject(repoFile(path).readText())
    /** Order-independent form of a flat JSON object whose values are strings or string arrays. */
    private fun canon(o: JSONObject): Map<String, Any> = o.keys().asSequence().sorted().associateWith { k ->
        val v = o.get(k); if (v is JSONArray) v.strings().sorted() else v.toString()
    }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }.toSet()

    /** Pull the enum out of a property that may be {enum}, {anyOf:[{enum},{null}]} or {items:{enum}}. */
    private fun enumOf(prop: JSONObject): Set<String> = when {
        prop.has("enum") -> prop.getJSONArray("enum").strings()
        prop.has("items") -> enumOf(prop.getJSONObject("items"))
        prop.has("anyOf") -> (0 until prop.getJSONArray("anyOf").length())
            .map { prop.getJSONArray("anyOf").getJSONObject(it) }.first { it.has("enum") }.let { enumOf(it) }
        else -> error("no enum in $prop")
    }

    @Test fun everyChoiceOnScreenIsOneTheBackendAccepts_andNoneIsMissing() {
        val props = json("contracts/openapi.json").getJSONObject("components").getJSONObject("schemas")
            .getJSONObject("Answers").getJSONObject("properties")
        assertEquals(props.keys().asSequence().toSet(), OnboardingQuestions.byField.keys)
        for ((field, choices) in OnboardingQuestions.byField) {
            assertEquals(field, enumOf(props.getJSONObject(field)), choices.map { it.key }.toSet())
            assertTrue("$field labels must be unique", choices.map { it.label }.toSet().size == choices.size)
        }
    }

    @Test fun profileSchemaMatchesTheSharedKotlinTypes() {
        val schema = json("contracts/profile.schema.json")
        val threat = schema.getJSONObject("\$defs").getJSONObject("Threat").getJSONObject("properties")
        assertEquals(ThreatType.entries.map { it.key }.toSet(), enumOf(threat.getJSONObject("type")))
        assertEquals(Level.entries.map { it.key }.toSet(), enumOf(threat.getJSONObject("level")))
        assertEquals(UserType.entries.map { it.key }.toSet(), enumOf(schema.getJSONObject("properties").getJSONObject("userType")))
        assertEquals(VulnerabilityProfile.VERSION, schema.getJSONObject("properties").getJSONObject("version").getInt("const"))
        assertTrue(threat.getJSONObject("why").getInt("maxLength") <= VulnerabilityProfile.MAX_WHY_LENGTH)
    }

    @Test fun requestBodyIsExactlyTheSharedVector() {
        val vectors = json("shared/onboarding-request-vectors.json")
        assertEquals(canon(vectors.getJSONObject("minimal")), canon(OnboardingAnswers(userType = "senior").toJson()))
        val full = OnboardingAnswers(
            userType = "student", ageBand = "18_29", livingSituation = "with_others", employment = "seeking_work",
            digitalConfidence = "medium", bankAppUsage = "multiple",
            activities = setOf("social_media", "job_search", "online_shopping"),
            concerns = setOf("job_offer", "digital_arrest"), priorScamExposure = "prefer_not_to_say",
        ).toJson()
        assertEquals(canon(vectors.getJSONObject("full")), canon(full))
        assertFalse(OnboardingAnswers().canSubmit)
        assertThrows(IllegalArgumentException::class.java) { OnboardingAnswers().toJson() }
    }

    @Test fun aBackendResponseDecodesWithTheStrictSharedParser() {
        // Shape taken from the Phase 1 README's success example.
        val p = VulnerabilityProfile.fromJson(
            """{"version":1,"userType":"student","threats":[{"type":"job_offer","level":"medium","why":"Job searching can expose you to fake recruitment offers."}]}"""
        )
        assertEquals(Level.MEDIUM, p.levelOf(ThreatType.JOB_OFFER))
        // The backend's error body must never be mistaken for a profile.
        assertThrows(IllegalArgumentException::class.java) {
            VulnerabilityProfile.fromJson("""{"error":{"code":"analysis_unavailable","message":"x"}}""")
        }
    }

    @Test fun clientRefusesPlainHttpExceptTheEmulatorLoopback() {
        OnboardingClient("https://example.org/v1/onboarding/profile")
        OnboardingClient("http://10.0.2.2:8000/v1/onboarding/profile")
        assertThrows(IllegalArgumentException::class.java) { OnboardingClient("http://example.org/v1/onboarding/profile") }
        assertThrows(IllegalArgumentException::class.java) { OnboardingClient("https://example.org/v1/onboarding/profile?x=1") }
        assertThrows(IllegalArgumentException::class.java) { OnboardingClient("https://user@example.org/p") }
    }

    @Test fun errorsAreExplainedWithoutEchoingAnything() {
        assertTrue(onboardingErrorMessage(OnboardingClient.Failure(429)).contains("busy"))
        assertTrue(onboardingErrorMessage(OnboardingClient.Failure(422)).contains("not accepted"))
        assertTrue(onboardingErrorMessage(OnboardingClient.Failure(502)).contains("not available"))
        assertTrue(onboardingErrorMessage(java.net.UnknownHostException("secret.host")).contains("No internet"))
        assertFalse(onboardingErrorMessage(java.net.UnknownHostException("secret.host")).contains("secret"))
        assertTrue(onboardingErrorMessage(java.net.SocketTimeoutException()).contains("too long"))
        assertTrue(onboardingErrorMessage(IllegalArgumentException("unknown level: x")).contains("nothing was saved"))
    }
}
