package com.scamguard.phase1

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.scamguard.profile.Level
import com.scamguard.profile.ProfileRepository
import com.scamguard.profile.ThreatType
import com.scamguard.profile.VulnerabilityProfile
import com.scamguard.ui.Amber
import com.scamguard.ui.BigButton
import com.scamguard.ui.Body
import com.scamguard.ui.InfoCard
import com.scamguard.ui.Neutral
import com.scamguard.ui.Page
import com.scamguard.ui.Red

/** Scam types in the order this person should read them: their likeliest first, the rest after. */
fun learningOrder(profile: VulnerabilityProfile?): List<Pair<ThreatType, Level?>> {
    val mine = profile?.threats.orEmpty().sortedByDescending { it.level.ordinal }.map { it.type to it.level }
    val rest = ThreatType.entries.filter { t -> mine.none { it.first == t } }.map { it to null }
    return mine + rest
}

/**
 * Training: how each scam call goes and what to do, ordered by the user's own profile.
 * "Hear a practice call" plays a synthetic scam through the real detector, so the user watches
 * the meter react to the same lines they have just read about.
 */
@Composable
fun LearnScreen(onBack: () -> Unit, onPractice: (asset: String, title: String) -> Unit) {
    val context = LocalContext.current
    val profile by remember { ProfileRepository(context).profileFlow }.collectAsState(initial = null)
    val order = learningOrder(profile)
    var open by remember(order.firstOrNull()?.first) { mutableStateOf(order.firstOrNull()?.first) }

    Page("Know the scams", onBack = onBack) {
        Body(
            if (profile == null) "How the most common scam calls work, and the one safe thing to do in each."
            else "The scams most likely to target you are first. Tap one to see how the call goes and what to do."
        )
        order.forEach { (type, level) ->
            val guide = ThreatGuide.of(type)
            InfoCard {
                Body(type.plainName, bold = true)
                when (level) {
                    Level.HIGH -> Body("Most likely for you", color = Red, bold = true)
                    Level.MEDIUM -> Body("Possible for you", color = Amber, bold = true)
                    else -> Unit
                }
                if (open == type) {
                    Body("How the call goes", bold = true)
                    Body(guide.howItGoes)
                    Body("What gives it away", bold = true)
                    guide.signs.forEach { Body("• $it") }
                    Body("What to do", bold = true)
                    Body(guide.safeMove)
                    if (guide.practiceAsset != null && guide.practiceTitle != null) {
                        BigButton("Hear a practice call") { onPractice(guide.practiceAsset, guide.practiceTitle) }
                        Body("A pretend call with computer voices. Watch how the risk meter reacts.", color = Neutral)
                    }
                } else {
                    OutlinedButton(onClick = { open = type }, modifier = Modifier.fillMaxWidth()) {
                        Text("Show me", fontSize = 17.sp)
                    }
                }
            }
        }
        InfoCard {
            Body("For every call", bold = true)
            Body("• Nobody genuine needs your OTP, PIN or CVV.")
            Body("• Nobody genuine needs you to install an app during a call.")
            Body("• If a caller rushes you, that is the reason to slow down. Hang up and call back on a number you already trust.")
            Body("• Lost money to a scam? Call 1930 at once. The sooner you call, the better the chance of stopping the transfer.")
        }
    }
}
