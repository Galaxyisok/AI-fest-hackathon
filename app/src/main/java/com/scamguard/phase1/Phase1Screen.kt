package com.scamguard.phase1

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.scamguard.profile.Level
import com.scamguard.profile.ProfileRepository
import com.scamguard.profile.Threat
import com.scamguard.profile.ThreatType
import com.scamguard.profile.UserType
import com.scamguard.profile.VulnerabilityProfile
import com.scamguard.ui.BigButton
import com.scamguard.ui.Body
import com.scamguard.ui.InfoCard
import com.scamguard.ui.Page
import kotlinx.coroutines.launch

/**
 * ============================  PHASE 1 GOES HERE  ============================
 *
 * This file is a PLACEHOLDER so Phase 2 can be built, tested and demoed before Phase 1 lands.
 * It does no AI and asks nothing personal: it saves one of four hand-written sample profiles.
 *
 * Phase 1 owner: replace the body of [Phase1Screen] with your screens (keep the signature).
 * Your only obligations to Phase 2 are in docs/profile-contract.md:
 *   1. build a VulnerabilityProfile from your AI analysis
 *   2. call ProfileRepository(context).save(profile)
 *   3. discard the raw answers; never write them to disk or send them anywhere but your AI call
 *   4. call onDone() when finished
 */
@Composable
fun Phase1Screen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { ProfileRepository(context) }
    val current by repo.profileFlow.collectAsState(initial = null)

    Page("My scam profile", onBack = onDone) {
        InfoCard(tint = Color(0xFFFFF4DB)) {
            Body("Sample profiles for now", bold = true)
            Body("The full version asks a few questions and uses AI to work out which scams are most likely to target you. Until it is ready, pick the description closest to you.")
        }
        current?.let { p ->
            InfoCard {
                Body("Saved profile: ${p.userType.key}", bold = true)
                p.threats.forEach { t -> Body("• ${t.type.plainName}: ${t.level.key}. ${t.why}") }
            }
        }
        SAMPLES.forEach { (label, profile) ->
            BigButton(label) {
                scope.launch { repo.save(profile); onDone() }
            }
        }
        Text("")
    }
}

private fun t(type: ThreatType, level: Level, why: String) = Threat(type, level, why)

private val SAMPLES = listOf(
    "I am a student" to VulnerabilityProfile.MOCK,
    "I am a parent" to VulnerabilityProfile(
        userType = UserType.PARENT,
        threats = listOf(
            t(ThreatType.FAMILY_EMERGENCY, Level.HIGH, "A child studying in another city makes an emergency call believable."),
            t(ThreatType.KYC_BANK, Level.HIGH, "Manages the family accounts and gets many bank messages."),
            t(ThreatType.DIGITAL_ARREST, Level.MEDIUM, "Would take a call from the police seriously."),
            t(ThreatType.UTILITY_DISCONNECT, Level.MEDIUM, "Pays the household bills."),
        ),
    ),
    "I am a senior citizen" to VulnerabilityProfile(
        userType = UserType.SENIOR,
        threats = listOf(
            t(ThreatType.DIGITAL_ARREST, Level.HIGH, "Respects authority and has savings worth targeting."),
            t(ThreatType.KYC_BANK, Level.HIGH, "Less sure which requests a bank really makes by phone."),
            t(ThreatType.SIM_SWAP, Level.MEDIUM, "Unlikely to notice a SIM going dead quickly."),
            t(ThreatType.UTILITY_DISCONNECT, Level.MEDIUM, "Pays bills by phone."),
            t(ThreatType.FAMILY_EMERGENCY, Level.MEDIUM, "Children and grandchildren live elsewhere."),
        ),
    ),
    "I am a working professional" to VulnerabilityProfile(
        userType = UserType.PROFESSIONAL,
        threats = listOf(
            t(ThreatType.COURIER_CUSTOMS, Level.HIGH, "Sends and receives parcels for work."),
            t(ThreatType.KYC_BANK, Level.MEDIUM, "Several accounts and cards."),
            t(ThreatType.LOAN_APP, Level.MEDIUM, "Gets frequent loan and credit offers."),
            t(ThreatType.JOB_OFFER, Level.LOW, "Already employed."),
        ),
    ),
)
