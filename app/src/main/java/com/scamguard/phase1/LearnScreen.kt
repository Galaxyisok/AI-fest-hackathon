package com.scamguard.phase1

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.scamguard.ui.Ocean
import com.scamguard.ui.Eyebrow
import com.scamguard.ui.WaveTrace

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
    var open by remember { mutableStateOf<ThreatType?>(null) }

    Page("Stay a step ahead.", onBack = onBack) {
        Eyebrow("THE FIELD GUIDE")
        Body(
            if (profile == null) "How the most common scam calls work, and the one safe thing to do in each."
            else "The scams most likely to target you are first. Tap one to see how the call goes and what to do."
        )
        InfoCard(tint = Color(0xFFDCEFEA)) {
            Eyebrow("FAMILY EMERGENCY SCAMS")
            Text("A familiar voice.\nA different story.", fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 33.sp, color = Ocean)
            WaveTrace(Modifier.fillMaxWidth().height(46.dp))
            Body("Urgency can make an unfamiliar request feel believable.", color = Neutral)
            TextButton(onClick = { open = ThreatType.FAMILY_EMERGENCY }) { Text("Explore the signs  →", color = Ocean, fontWeight = FontWeight.SemiBold) }
        }
        Eyebrow("RECOGNISE THE PATTERN")
        order.forEachIndexed { index, (type, level) ->
            val guide = ThreatGuide.of(type)
            InfoCard(modifier = Modifier.animateContentSize()) {
                TextButton(onClick = { open = if (open == type) null else type }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("%02d".format(index + 1), color = Ocean.copy(alpha = 0.55f), fontSize = 13.sp)
                        Text(type.plainName, modifier = Modifier.weight(1f), fontSize = 17.sp, lineHeight = 22.sp, color = Ocean, fontWeight = FontWeight.SemiBold)
                        Text(if (open == type) "−" else "+", fontSize = 23.sp, color = Ocean)
                    }
                }
                when (level) {
                    Level.HIGH -> Body("Most likely for you", color = Red, bold = true)
                    Level.MEDIUM -> Body("Possible for you", color = Amber, bold = true)
                    else -> Unit
                }
                AnimatedVisibility(visible = open == type, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = Ocean.copy(alpha = 0.12f))
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
                  }
                }
            }
        }
        InfoCard(tint = Color(0xFFEDF4F4)) {
            Eyebrow("KEEP THESE CLOSE")
            Text("For every call.", fontFamily = FontFamily.Serif, fontSize = 25.sp, color = Ocean)
            Body("• Nobody genuine needs your OTP, PIN or CVV.")
            Body("• Nobody genuine needs you to install an app during a call.")
            Body("• If a caller rushes you, that is the reason to slow down. Hang up and call back on a number you already trust.")
            Body("• Lost money to a scam? Call 1930 at once. The sooner you call, the better the chance of stopping the transfer.")
        }
    }
}
