package com.scamguard

import com.scamguard.calls.DemoCalls
import com.scamguard.phase1.ThreatGuide
import com.scamguard.phase1.learningOrder
import com.scamguard.profile.Level
import com.scamguard.profile.Threat
import com.scamguard.profile.ThreatType
import com.scamguard.profile.UserType
import com.scamguard.profile.VulnerabilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrainingTest {

    @Test fun everyScamTypeHasACompleteGuide() {
        for (type in ThreatType.entries) {
            val g = ThreatGuide.of(type)
            assertTrue("$type story", g.howItGoes.length > 60)
            assertEquals("$type signs", 3, g.signs.size)
            assertTrue("$type advice", g.safeMove.length > 30)
            assertEquals("$type practice title goes with its recording", g.practiceAsset == null, g.practiceTitle == null)
        }
    }

    @Test fun everyPracticeCallIsARecordingThatShipsInTheApp() {
        val practice = ThreatType.entries.mapNotNull { ThreatGuide.of(it).practiceAsset }
        assertEquals(3, practice.size)
        val demoAssets = DemoCalls.all.map { it.asset }.toSet()
        for (asset in practice) {
            assertTrue("$asset is a known demo call", asset in demoAssets)
            val f = listOf(File("src/main/assets/$asset"), File("app/src/main/assets/$asset")).firstOrNull { it.exists() }
            assertTrue("$asset exists on disk", f != null && f.length() > 100_000)
        }
    }

    @Test fun theUsersLikeliestScamsComeFirstAndNothingIsLeftOut() {
        val profile = VulnerabilityProfile(
            userType = UserType.SENIOR,
            threats = listOf(
                Threat(ThreatType.LOAN_APP, Level.LOW, "x"),
                Threat(ThreatType.SIM_SWAP, Level.HIGH, "x"),
                Threat(ThreatType.KYC_BANK, Level.MEDIUM, "x"),
            ),
        )
        val order = learningOrder(profile)
        assertEquals(listOf(ThreatType.SIM_SWAP, ThreatType.KYC_BANK, ThreatType.LOAN_APP), order.take(3).map { it.first })
        assertEquals(Level.HIGH, order[0].second)
        assertEquals(ThreatType.entries.toSet(), order.map { it.first }.toSet())
        assertEquals(ThreatType.entries.size, order.size)
        assertNull("types outside the profile carry no level", order[3].second)
    }

    @Test fun withNoProfileEveryScamIsStillTaught() {
        val order = learningOrder(null)
        assertEquals(ThreatType.entries.toList(), order.map { it.first })
        assertTrue(order.all { it.second == null })
    }
}
