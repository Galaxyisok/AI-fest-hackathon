package com.scamguard

import com.scamguard.judge.CallerPrior
import com.scamguard.judge.FusionInput
import com.scamguard.judge.PiiRedactor
import com.scamguard.judge.ScoreFusion
import com.scamguard.judge.Tier
import com.scamguard.profile.Level
import com.scamguard.profile.ThreatType
import com.scamguard.profile.VulnerabilityProfile
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin that ships and the JavaScript the eval runs must agree, or the reported numbers
 * describe a different app. Both are held to the same vectors in /shared.
 */
class SharedVectorsTest {

    private fun vectors(name: String): JSONArray {
        val f = listOf(File("../shared/$name"), File("shared/$name")).first { it.exists() }
        return JSONArray(f.readText())
    }

    @Test fun redactorMatchesSharedVectors() {
        val vs = vectors("redactor-vectors.json")
        assertTrue(vs.length() >= 10)
        for (i in 0 until vs.length()) {
            val v = vs.getJSONObject(i)
            val r = PiiRedactor.redact(v.getString("in"))
            assertEquals(v.getString("name"), v.getString("out"), r.text)
            assertEquals(v.getString("name"), v.getBoolean("codeSpoken"), r.codeSpoken)
        }
    }

    @Test fun fusionMatchesSharedVectors() {
        val vs = vectors("fusion-vectors.json")
        assertTrue(vs.length() >= 8)
        for (i in 0 until vs.length()) {
            val v = vs.getJSONObject(i)
            val p = v.getJSONObject("prior")
            val fusion = ScoreFusion(CallerPrior(p.optBoolean("international"), p.optBoolean("unverified"), p.optBoolean("hidden")))
            val steps = v.getJSONArray("steps")
            for (s in 0 until steps.length()) {
                val st = steps.getJSONObject(s)
                val score = fusion.update(
                    FusionInput(
                        tier = if (st.getString("tier") == "deep") Tier.DEEP else Tier.FAST,
                        p = st.getDouble("p"),
                        confidence = st.getString("confidence"),
                        profileMatch = st.getInt("profileMatch"),
                        matchedLevel = st.getString("matchedLevel"),
                    )
                )
                val where = "${v.getString("name")} step $s"
                assertEquals(where, if (st.isNull("display")) null else st.getInt("display"), score.display)
                assertEquals(where, st.getString("band"), score.band.name.lowercase())
            }
        }
    }

    @Test fun profileRoundTrips() {
        val back = VulnerabilityProfile.fromJson(VulnerabilityProfile.MOCK.toJson())
        assertEquals(VulnerabilityProfile.MOCK, back)
        assertEquals(Level.HIGH, back.levelOf(ThreatType.DIGITAL_ARREST))
        assertEquals(null, back.levelOf(ThreatType.SIM_SWAP))
    }

    @Test fun profileRejectsWhatBreaksTheContract() {
        assertThrows(IllegalArgumentException::class.java) { VulnerabilityProfile.fromJson("not json") }
        assertThrows(IllegalArgumentException::class.java) {
            VulnerabilityProfile.fromJson("""{"version":2,"userType":"student","threats":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VulnerabilityProfile.fromJson("""{"version":1,"userType":"alien","threats":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VulnerabilityProfile.fromJson("""{"version":1,"userType":"student","threats":[{"type":"romance","level":"high","why":"x"}]}""")
        }
    }
}
