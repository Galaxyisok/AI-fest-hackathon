package com.scamguard.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scamguard.BuildConfig
import com.scamguard.calls.CallAudioAccessService
import com.scamguard.calls.CallGuardService
import com.scamguard.calls.DemoCall
import com.scamguard.calls.DemoCalls
import com.scamguard.calls.Guard
import com.scamguard.calls.GuardUi
import com.scamguard.calls.Mode
import com.scamguard.judge.Band
import com.scamguard.profile.Level
import com.scamguard.profile.ProfileRepository
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// What the phone has allowed so far. Re-read every time the user comes back from system settings.
// ---------------------------------------------------------------------------------------------

data class SetupStatus(val mic: Boolean, val notifications: Boolean, val callerCheck: Boolean, val callListening: Boolean) {
    val readyToListen get() = mic && notifications
}

fun readSetupStatus(c: Context) = SetupStatus(
    mic = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    notifications = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    callerCheck = c.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING),
    callListening = CallAudioAccessService.isEnabled(c),
)

@Composable
fun rememberSetupStatus(): SetupStatus {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(readSetupStatus(context)) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) status = readSetupStatus(context) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return status
}

// ---------------------------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(
    onStartLive: () -> Unit, onDemo: () -> Unit, onSetup: () -> Unit,
    onProfile: () -> Unit, onLearn: () -> Unit, onPrivacy: () -> Unit, onOpenLive: () -> Unit,
) {
    val context = LocalContext.current
    val status = rememberSetupStatus()
    val profile by remember { ProfileRepository(context).profileFlow }.collectAsState(initial = null)
    val guard by Guard.ui.collectAsState()

    Page("Scam Guard") {
        Body("Listens to a phone call with you and tells you how likely it is to be a scam, while you are still on the call.")

        if (guard.active) {
            InfoCard(tint = Color(0xFFE8F1F7)) {
                Body("Scam Guard is listening right now.", bold = true)
                BigButton("Show the risk meter", onClick = onOpenLive)
            }
        }

        InfoCard {
            Body("On a call you are not sure about?", bold = true)
            Body("Put the call on speaker, then tap the button.")
            BigButton("Start listening", onClick = onStartLive)
            if (!status.callListening) {
                Body("One more setting is needed before Scam Guard can hear a real phone call.", color = Amber)
                OutlinedButton(onClick = onSetup) { Text("Finish setup", fontSize = 17.sp) }
            }
        }

        InfoCard {
            Body("See how it works", bold = true)
            Body("Play a pretend scam call and watch the meter. Nothing real happens.")
            OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) { Text("Try a demo call", fontSize = 17.sp) }
        }

        InfoCard {
            Body("Scams you should watch for", bold = true)
            val p = profile
            if (p == null) {
                Body("Answer a few questions so Scam Guard knows which scams are most likely to target you.")
            } else {
                p.threats.filter { it.level != Level.LOW }.sortedByDescending { it.level.ordinal }.take(3).forEach { t ->
                    Body("• ${t.type.plainName}" + if (t.level == Level.HIGH) " (most likely)" else "")
                }
            }
            BigButton("Learn how these scams work", onClick = onLearn)
            OutlinedButton(onClick = onProfile) { Text(if (p == null) "Set up my profile" else "Update my profile", fontSize = 17.sp) }
        }

        InfoCard {
            Body("Protection", bold = true)
            StatusRow(status.callerCheck, if (status.callerCheck) "Checks unknown callers before you answer" else "Unknown callers are not checked yet")
            StatusRow(status.mic, if (status.mic) "Can use the microphone" else "Cannot use the microphone yet")
            StatusRow(status.callListening, if (status.callListening) "Can hear calls on speaker" else "Cannot hear real phone calls yet")
            OutlinedButton(onClick = onSetup) { Text("Setup", fontSize = 17.sp) }
        }

        OutlinedButton(onClick = onPrivacy, modifier = Modifier.fillMaxWidth()) { Text("What this app keeps about you", fontSize = 17.sp) }
    }
}

@Composable
fun BigButton(text: String, color: Color = Color(0xFF15577A), onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) { Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
}

// ---------------------------------------------------------------------------------------------
// Live meter
// ---------------------------------------------------------------------------------------------

data class BandCopy(val headline: String, val action: String)

fun copyFor(band: Band) = when (band) {
    Band.LISTENING -> BandCopy("Listening…", "Not enough has been said yet to judge this call.")
    Band.GREEN -> BandCopy("Looks normal", "Nothing worrying so far. Scam Guard keeps listening.")
    Band.AMBER -> BandCopy("Be careful", "Do not share any code (OTP), PIN or card number. Do not install any app they ask for.")
    Band.RED -> BandCopy("Likely a scam", "Hang up now. If it might be real, call back on the official number printed on your card or bill.")
}

/** Plain words for each thing the judge looks for. */
fun signalName(key: String) = when (key) {
    "authority" -> "Says they are an official or the bank"
    "urgency_threat" -> "Rushing or threatening you"
    "secrecy_isolation" -> "Wants you to stay on the line or tell no one"
    "payment_request" -> "Asking you to send money"
    "credential_or_app_request" -> "Asking for a code or to install an app"
    "script_stage" -> "Following a known scam pattern"
    "profile_match" -> "A scam type you are more exposed to"
    else -> key
}

@Composable
fun LiveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by Guard.ui.collectAsState()
    var reported by remember { mutableStateOf(false) }
    val band = ui.score.band
    val copy = copyFor(band)

    Page(if (ui.mode == Mode.DEMO) "Demo: ${ui.title}" else "This call", onBack = onBack) {
        if (ui.preparing) {
            InfoCard { Body("Getting ready… this takes a few seconds the first time.") }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RiskMeter(ui) }

        InfoCard(tint = band.color().copy(alpha = 0.10f)) {
            Text(copy.headline, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = band.color())
            Body(copy.action, bold = band == Band.RED)
        }

        if (band == Band.RED) {
            BigButton("Call 1930 (cyber fraud helpline)", color = Red) {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")))
            }
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sancharsaathi.gov.in"))) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Report this caller on Sanchar Saathi", fontSize = 17.sp) }
        }

        if (ui.codeSpoken) {
            InfoCard(tint = Red.copy(alpha = 0.10f)) {
                Body("A code was said out loud on this call.", bold = true, color = Red)
                Body("Never read out an OTP or PIN to anyone who phones you. A real bank will not ask.")
            }
        }

        if (ui.reason.isNotBlank() && band != Band.LISTENING) {
            InfoCard {
                Body("Why", bold = true)
                Body(ui.reason)
                ui.signals.filter { it.key != "script_stage" }.take(4).forEach { s ->
                    Body("• ${signalName(s.key)}")
                    if (s.evidence.isNotBlank() && s.evidence != "none") Body("   heard: \"${s.evidence}\"", color = Neutral)
                }
            }
        }

        if (ui.micSilenced && ui.mode == Mode.LIVE) {
            InfoCard(tint = Amber.copy(alpha = 0.12f)) {
                Body("Scam Guard cannot hear anything.", bold = true, color = Amber)
                Body("Check the call is on speaker. If it is, your phone is blocking apps from hearing calls: open Setup and turn on call listening.")
            }
        }

        ui.problem?.let { InfoCard(tint = Amber.copy(alpha = 0.12f)) { Body(it, color = Amber) } }

        if (ui.heard.isNotBlank()) {
            InfoCard {
                Body("What was heard", bold = true)
                Body("Numbers are removed on your phone before anything is checked.", color = Neutral)
                Text(ui.heard, fontSize = 15.sp, lineHeight = 21.sp, color = Neutral)
            }
        }

        if (ui.active) {
            OutlinedButton(onClick = { CallGuardService.stop(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Stop listening", fontSize = 18.sp)
            }
        } else if (ui.finished) {
            Body(if (ui.mode == Mode.DEMO) "The demo call has ended." else "Listening has stopped. Nothing from this call was saved.", color = Neutral)
        } else if (!ui.preparing) {
            Body("Scam Guard is not listening. Go back and tap Start listening.", color = Neutral)
        }

        if (ui.checks > 0) {
            if (reported) {
                Body("Thank you. Only a count is kept on this phone, nothing about the call.", color = Neutral)
            } else {
                OutlinedButton(onClick = {
                    reported = true
                    scope.launch { ProfileRepository(context).countWrong() }
                }, modifier = Modifier.fillMaxWidth()) { Text("This was wrong", fontSize = 17.sp) }
            }
        }
    }
}

@Composable
fun RiskMeter(ui: GuardUi) {
    val display = ui.score.display
    val band = ui.score.band
    val sweep by animateFloatAsState(targetValue = (display ?: 0) / 100f, animationSpec = tween(700), label = "meter")
    val color = band.color()
    val spoken = if (display == null) "Still listening, no score yet" else "About $display percent chance this is a scam"
    Box(Modifier.size(240.dp).semantics { contentDescription = spoken }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp)) {
            val stroke = Stroke(width = 26.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(Color(0xFFDCE3E9), 135f, 270f, false, Offset(inset, inset), arcSize, style = stroke)
            if (display != null) drawArc(color, 135f, 270f * sweep.coerceAtLeast(0.02f), false, Offset(inset, inset), arcSize, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (display == null) {
                Text("…", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = color)
                Text("listening", fontSize = 16.sp, color = Neutral)
            } else {
                Text("$display%", fontSize = 60.sp, fontWeight = FontWeight.Bold, color = color)
                Text("chance this\nis a scam", fontSize = 16.sp, color = Neutral, textAlign = TextAlign.Center, lineHeight = 20.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Demo picker
// ---------------------------------------------------------------------------------------------

@Composable
fun DemoScreen(onBack: () -> Unit, onPlay: (DemoCall) -> Unit) {
    Page("Demo calls", onBack = onBack) {
        Body("These are pretend calls made with computer voices. Android does not let any app hear a phone call directly, so a recording stands in for the call here. Scam Guard is not told which ones are scams: it listens and decides, exactly as it would on a real call.")
        DemoCalls.all.forEach { call ->
            InfoCard {
                Body(call.title, bold = true)
                Body(call.blurb)
                BigButton("Play this call") { onPlay(call) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Setup: one plain explanation per permission, asked only when the user taps
// ---------------------------------------------------------------------------------------------

@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(readSetupStatus(context)) }
    val refresh = { status = readSetupStatus(context) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val askRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    Page("Setup", onBack = onBack) {
        Body("Scam Guard asks for four things. Each one is explained here, and you can say no to any of them.")

        SetupStep(
            done = status.notifications, title = "1. Show warnings",
            why = "So a warning can appear on top of your call screen when a call starts to look like a scam.",
            button = "Allow warnings",
        ) { if (Build.VERSION.SDK_INT >= 33) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

        SetupStep(
            done = status.mic, title = "2. Use the microphone",
            why = "So Scam Guard can hear a call you have put on speaker. The sound is turned into text on your phone and then thrown away. It is never saved or sent anywhere.",
            button = "Allow microphone",
        ) { askPermission.launch(Manifest.permission.RECORD_AUDIO) }

        SetupStep(
            done = status.callerCheck, title = "3. Check unknown callers",
            why = "So you get a note when a number that is not in your contacts is ringing. Scam Guard never blocks a call and cannot see your contacts.",
            button = "Turn on caller check",
        ) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) askRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
        }

        SetupStep(
            done = status.callListening, title = "4. Hear real phone calls",
            why = "Android mutes the microphone for apps during a phone call unless you switch them on under Accessibility. On the next screen, find \"Scam Guard call listening\" and turn it on. Scam Guard does not read your screen or what you type.",
            button = "Open Accessibility settings",
        ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        // For the team, not for ordinary users: where the checking service lives.
        var url by remember { mutableStateOf("") }
        val saved by remember { ProfileRepository(context).judgeUrlFlow }.collectAsState(initial = null)
        var p1 by remember { mutableStateOf("") }
        val savedP1 by remember { ProfileRepository(context).phase1UrlFlow }.collectAsState(initial = null)
        InfoCard {
            Body("For the team: profile service address", bold = true)
            Body("Now using: ${savedP1 ?: BuildConfig.PHASE1_URL}", color = Neutral)
            OutlinedTextField(value = p1, onValueChange = { p1 = it }, singleLine = true, label = { Text("https://…/v1/onboarding/profile") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { scope.launch { ProfileRepository(context).setPhase1Url(p1); p1 = "" } }) {
                Text(if (p1.isBlank()) "Reset to built-in address" else "Save address", fontSize = 16.sp)
            }
        }
        InfoCard {
            Body("For the team: checking service address", bold = true)
            Body("Now using: ${saved ?: BuildConfig.JUDGE_URL}", color = Neutral)
            OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, label = { Text("https://…/api/judge") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { scope.launch { ProfileRepository(context).setJudgeUrl(url); url = "" } }) {
                Text(if (url.isBlank()) "Reset to built-in address" else "Save address", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun SetupStep(done: Boolean, title: String, why: String, button: String, onClick: () -> Unit) {
    InfoCard(tint = if (done) Green.copy(alpha = 0.08f) else Color.White) {
        Body(title, bold = true)
        Body(why)
        if (done) Body("✓ Done", color = Green, bold = true)
        else BigButton(button, onClick = onClick)
    }
}

// ---------------------------------------------------------------------------------------------
// Privacy, in plain words
// ---------------------------------------------------------------------------------------------

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wrong by remember { ProfileRepository(context).wrongCountFlow }.collectAsState(initial = 0)
    val profile by remember { ProfileRepository(context).profileFlow }.collectAsState(initial = null)
    Page("What this app keeps", onBack = onBack) {
        InfoCard {
            Body("Kept on this phone", bold = true)
            Body("• Your scam profile: a short list of scam types and how exposed you are. " + if (profile == null) "(none yet)" else "(${profile!!.threats.size} items)")
            Body("• How many times you tapped \"This was wrong\": $wrong")
            Body("That is everything.")
        }
        InfoCard {
            Body("Never kept", bold = true)
            Body("• The sound of your calls. It is turned into text on your phone and discarded straight away.")
            Body("• The text of your calls. It lives in memory during the call and is gone when the call ends.")
            Body("• Your answers to the profile questions. Only the result is kept.")
            Body("• Phone numbers, your contacts, your call history.")
            Body("• Any OTP, PIN, card or Aadhaar number. This app never asks for them.")
        }
        InfoCard {
            Body("What leaves the phone", bold = true)
            Body("During a call: only the text of the call, with every number and ID already removed, is sent to an AI service that judges it. Your voice never leaves the phone.")
            Body("When you make your scam profile: the choices you tap are sent once to an AI service. They are not saved on this phone or on our servers.")
            Body("The AI service is Claude, made by Anthropic. Our own servers keep no copy of anything.")
        }
        OutlinedButton(onClick = { scope.launch { ProfileRepository(context).clear() } }, modifier = Modifier.fillMaxWidth()) {
            Text("Delete my scam profile", fontSize = 17.sp)
        }
    }
}
