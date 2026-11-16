package com.scamguard.phase1

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.scamguard.ui.Eyebrow
import com.scamguard.ui.Ocean
import com.scamguard.ui.Mint
import com.scamguard.ui.ShieldArtwork
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scamguard.BuildConfig
import com.scamguard.profile.Level
import com.scamguard.profile.ProfileRepository
import com.scamguard.profile.Threat
import com.scamguard.profile.ThreatType
import com.scamguard.profile.UserType
import com.scamguard.profile.VulnerabilityProfile
import com.scamguard.ui.Amber
import com.scamguard.ui.BigButton
import com.scamguard.ui.Body
import com.scamguard.ui.Green
import com.scamguard.ui.InfoCard
import com.scamguard.ui.Neutral
import com.scamguard.ui.Page
import com.scamguard.ui.Red
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 1 onboarding: a few tap-only questions -> the Phase 1 backend (Claude Haiku) works out
 * which scams this person is most exposed to -> the derived profile is saved for Phase 2.
 *
 * Follows integration/ANDROID.md from the Phase 1 branch:
 *  - answers live in plain `remember` state: never rememberSaveable, never on disk
 *  - they are cleared the moment they are sent, whatever the outcome
 *  - the profile is decoded with the shared strict parser and saved BEFORE the result is shown
 *  - a failure leaves any previously saved profile untouched
 */
private enum class Step { CONSENT, QUESTIONS, SENDING, RESULT }

/** Plain words for what went wrong. Never shows the server's own message or what was sent. */
fun onboardingErrorMessage(e: Throwable): String = when {
    e is OnboardingClient.Failure && e.status == 422 -> "Those answers were not accepted. Please choose again."
    e is OnboardingClient.Failure && e.status == 429 -> "The service is busy. Please try again in a minute."
    e is OnboardingClient.Failure -> "The profile service is not available right now. Please try again later."
    e is java.net.SocketTimeoutException -> "That took too long. Please try again."
    e is java.net.ConnectException -> "The profile service could not be reached. Please try again later."
    e is java.io.IOException -> "No internet connection, so your profile could not be made."
    e is IllegalArgumentException -> "The profile that came back was not valid, so nothing was saved. Please try again."
    else -> "Something went wrong and nothing was saved. Please try again."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Phase1Screen(onDone: () -> Unit, onLearn: () -> Unit = onDone) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()   // cancelled when the screen closes: a late answer is then discarded
    val repo = remember { ProfileRepository(context) }
    val saved by repo.profileFlow.collectAsState(initial = null)

    var step by remember { mutableStateOf(Step.CONSENT) }
    var answers by remember { mutableStateOf(OnboardingAnswers()) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<VulnerabilityProfile?>(null) }

    fun submit() {
        if (step == Step.SENDING || !answers.canSubmit) return      // no duplicate submits
        val body = answers.toJson()
        answers = OnboardingAnswers()                                // cleared as soon as they are copied out
        error = null
        step = Step.SENDING
        scope.launch {
            try {
                val url = repo.phase1UrlFlow.first() ?: BuildConfig.PHASE1_URL
                val json = withContext(Dispatchers.IO) { OnboardingClient(url).generate(body, consent = true) }
                val profile = VulnerabilityProfile.fromJson(json)    // strict: throws on anything outside the contract
                repo.save(profile)                                   // saved before anything is shown as done
                result = profile
                step = Step.RESULT
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = onboardingErrorMessage(e)
                step = Step.QUESTIONS                                // answers must be re-entered; nothing was kept
            } finally {
                body.keys().asSequence().toList().forEach { body.remove(it) }
            }
        }
    }

    key(step) {
    Page("My scam profile", onBack = onDone) {
        when (step) {
            Step.CONSENT -> {
                InfoCard(tint = Color(0xFFE3F3EE)) {
                    Eyebrow("PERSONAL, BY DESIGN")
                    Text("Know what to\nwatch for.", fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 34.sp, color = Ocean)
                    ShieldArtwork(Modifier.fillMaxWidth().height(104.dp))
                    Body("A few choices help Scam Guard recognise the scams most likely to target you.")
                }
                InfoCard {
                    Eyebrow("BEFORE YOU BEGIN")
                    Body("Only broad choices", bold = true)
                    Body("Tap your answers. No name, number, address or ID is requested.", color = Neutral)
                    HorizontalDivider(color = Ocean.copy(alpha = 0.08f))
                    Body("Analysed by Claude", bold = true)
                    Body("Your choices are sent to Anthropic’s AI service to create your profile. Our app and server do not save those answers.", color = Neutral)
                    HorizontalDivider(color = Ocean.copy(alpha = 0.08f))
                    Body("You stay in control", bold = true)
                    Body("Only the profile stays on your phone. Skip any question except the first, and delete the profile whenever you like.", color = Neutral)
                }
                saved?.let { ProfileCard("Your current profile", it) }
                BigButton("Agree & personalise") { step = Step.QUESTIONS }
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Not now", fontSize = 17.sp) }
                SampleProfiles(repo, onDone)
            }

            Step.QUESTIONS -> {
                Eyebrow("A FEW THINGS ABOUT YOU")
                Body("Only the first answer is required. Everything else is your choice.", color = Neutral)
                error?.let { InfoCard(tint = Amber.copy(alpha = 0.12f)) { Body(it, color = Amber, bold = true) } }
                Question("Which describes you best?", OnboardingQuestions.userType, setOfNotNull(answers.userType)) {
                    answers = answers.copy(userType = it)
                }
                Question("Your age (optional)", OnboardingQuestions.ageBand, setOfNotNull(answers.ageBand)) {
                    answers = answers.copy(ageBand = it.takeIf { k -> k != answers.ageBand })
                }
                Question("Who do you live with? (optional)", OnboardingQuestions.livingSituation, setOfNotNull(answers.livingSituation)) {
                    answers = answers.copy(livingSituation = it.takeIf { k -> k != answers.livingSituation })
                }
                Question("What do you do? (optional)", OnboardingQuestions.employment, setOfNotNull(answers.employment)) {
                    answers = answers.copy(employment = it.takeIf { k -> k != answers.employment })
                }
                Question("How confident are you with apps and online payments? (optional)", OnboardingQuestions.digitalConfidence, setOfNotNull(answers.digitalConfidence)) {
                    answers = answers.copy(digitalConfidence = it.takeIf { k -> k != answers.digitalConfidence })
                }
                Question("How many banking or payment apps do you use? (optional)", OnboardingQuestions.bankAppUsage, setOfNotNull(answers.bankAppUsage)) {
                    answers = answers.copy(bankAppUsage = it.takeIf { k -> k != answers.bankAppUsage })
                }
                Question("Which of these do you do? Tap all that apply (optional)", OnboardingQuestions.activities, answers.activities) {
                    answers = answers.copy(activities = answers.activities.toggle(it))
                }
                Question("Which scams worry you? Tap all that apply (optional)", OnboardingQuestions.concerns, answers.concerns) {
                    answers = answers.copy(concerns = answers.concerns.toggle(it))
                }
                Question("Have you had a scam call before? (optional)", OnboardingQuestions.priorScamExposure, setOfNotNull(answers.priorScamExposure)) {
                    answers = answers.copy(priorScamExposure = it.takeIf { k -> k != answers.priorScamExposure })
                }
                if (answers.canSubmit) BigButton("Work out my profile") { submit() }
                else Body("Answer the first question to continue.", color = Neutral)
            }

            Step.SENDING -> {
                InfoCard {
                    CircularProgressIndicator()
                    Body("Working out your profile… this can take up to half a minute.", bold = true)
                    Body("Your answers have already been cleared from this screen.", color = Neutral)
                }
            }

            Step.RESULT -> {
                result?.let { ProfileCard("Your scam profile", it) }
                InfoCard(tint = Color(0xFFE8F1F7)) {
                    Body("This is to help you stay alert. It is not a promise that you are safe: a scam marked \"less likely\" can still happen to you.")
                }
                BigButton("Show me how these scams work", onClick = onLearn)
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done", fontSize = 17.sp) }
            }
        }
    }
}

}

private fun Set<String>.toggle(key: String) = if (key in this) this - key else this + key

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Question(title: String, choices: List<Choice>, selected: Set<String>, onTap: (String) -> Unit) {
    InfoCard {
        Body(title, bold = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { c ->
                FilterChip(
                    selected = c.key in selected,
                    onClick = { onTap(c.key) },
                    label = { Text(c.label, fontSize = 16.sp) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Ocean, labelColor = Neutral),
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(title: String, profile: VulnerabilityProfile) {
    InfoCard {
        Body(title, bold = true)
        profile.threats.sortedByDescending { it.level.ordinal }.forEachIndexed { index, t ->
            if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Ocean.copy(alpha = 0.10f))
            val (word, color) = when (t.level) {
                Level.HIGH -> "Most likely" to Red
                Level.MEDIUM -> "Possible" to Amber
                Level.LOW -> "Less likely" to Green
            }
            Body("${t.type.plainName}", bold = true)
            Body(word, color = color, bold = true)
            Body(t.why, color = Neutral)
        }
    }
}

/**
 * Hand-written sample profiles: NO AI is involved. Kept so the call guard can be demonstrated
 * when the profile service cannot be reached. Clearly labelled as samples in the UI.
 */
@Composable
private fun SampleProfiles(repo: ProfileRepository, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text("For demos: use a ready-made sample profile instead", fontSize = 15.sp) }
    if (open) {
        Body("These are fixed examples, not worked out by AI and not about you.", color = Neutral)
        SAMPLES.forEach { (label, profile) ->
            OutlinedButton(onClick = { scope.launch { repo.save(profile); onDone() } }, modifier = Modifier.fillMaxWidth()) {
                Text(label, fontSize = 16.sp)
            }
        }
    }
}

private fun t(type: ThreatType, level: Level, why: String) = Threat(type, level, why)

private val SAMPLES = listOf(
    "Sample: student" to VulnerabilityProfile.MOCK,
    "Sample: parent" to VulnerabilityProfile(
        userType = UserType.PARENT,
        threats = listOf(
            t(ThreatType.FAMILY_EMERGENCY, Level.HIGH, "A child studying in another city makes an emergency call believable."),
            t(ThreatType.KYC_BANK, Level.HIGH, "Manages the family accounts and gets many bank messages."),
            t(ThreatType.DIGITAL_ARREST, Level.MEDIUM, "Would take a call from the police seriously."),
            t(ThreatType.UTILITY_DISCONNECT, Level.MEDIUM, "Pays the household bills."),
        ),
    ),
    "Sample: senior citizen" to VulnerabilityProfile(
        userType = UserType.SENIOR,
        threats = listOf(
            t(ThreatType.DIGITAL_ARREST, Level.HIGH, "Respects authority and has savings worth targeting."),
            t(ThreatType.KYC_BANK, Level.HIGH, "Less sure which requests a bank really makes by phone."),
            t(ThreatType.SIM_SWAP, Level.MEDIUM, "Unlikely to notice a SIM going dead quickly."),
            t(ThreatType.UTILITY_DISCONNECT, Level.MEDIUM, "Pays bills by phone."),
            t(ThreatType.FAMILY_EMERGENCY, Level.MEDIUM, "Children and grandchildren live elsewhere."),
        ),
    ),
    "Sample: working professional" to VulnerabilityProfile(
        userType = UserType.PROFESSIONAL,
        threats = listOf(
            t(ThreatType.COURIER_CUSTOMS, Level.HIGH, "Sends and receives parcels for work."),
            t(ThreatType.KYC_BANK, Level.MEDIUM, "Several accounts and cards."),
            t(ThreatType.LOAN_APP, Level.MEDIUM, "Gets frequent loan and credit offers."),
            t(ThreatType.JOB_OFFER, Level.LOW, "Already employed."),
        ),
    ),
)
