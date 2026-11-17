package com.scamguard

import com.scamguard.calls.CallGuardService
import com.scamguard.calls.GuardSession
import com.scamguard.calls.GuardUi
import com.scamguard.calls.ScamScreeningService
import com.scamguard.judge.Band
import com.scamguard.judge.CallerPrior
import com.scamguard.judge.CascadePolicy
import com.scamguard.judge.JudgeClient
import com.scamguard.judge.JudgeOutcome
import com.scamguard.judge.Signal
import com.scamguard.judge.Tier
import com.scamguard.judge.Verdict
import com.scamguard.profile.VulnerabilityProfile
import com.scamguard.stt.SttEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineTest {

    private fun verdict(tier: Tier, p: Int, confidence: String = "high", reason: String = "r", match: Int = 0) = Verdict(
        tier, p, confidence, reason, if (match > 0) "digital_arrest" else "none",
        listOf(Signal("authority", if (p > 50) 3 else 0, "this is the police"), Signal("profile_match", match, "none")),
    )

    // ---- GuardSession: words in, score out ----

    @Test fun nothingIsSentUntilEnoughHasBeenSaid() = runTest {
        val sent = ArrayList<String>()
        val ui = MutableStateFlow(GuardUi())
        val s = GuardSession(this, { _, t -> sent += t; JudgeOutcome.Ok(verdict(Tier.FAST, 5)) }, null, CallerPrior(), ui, { testScheduler.currentTime })
        s.onEvent(SttEvent.Final("hello who is this"))
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
        assertEquals(Band.LISTENING, ui.value.score.band)
    }

    /** Real phones, 2026-11-16: 79 successful checks and the meter never left "Listening". */
    @Test fun anUnsureJudgeStillShowsANumberOnceEnoughHasBeenSaid() = runTest {
        val ui = MutableStateFlow(GuardUi())
        val s = GuardSession(this, { tier, _ -> JudgeOutcome.Ok(verdict(tier, 20, confidence = "low")) }, null, CallerPrior(), ui, { testScheduler.currentTime })
        s.onEvent(SttEvent.Final("hello am i speaking with the owner of this mobile number yes who is this"))   // 15 words
        advanceUntilIdle()
        assertEquals(Band.LISTENING, ui.value.score.band)            // too early: still no guess
        s.onEvent(SttEvent.Final("i am calling from the help desk how are you doing today sir is this a good time to talk"))
        advanceUntilIdle()
        assertEquals(20, ui.value.score.display)
        assertEquals(Band.GREEN, ui.value.score.band)
        assertEquals(GuardSession.UNSURE_REASON, ui.value.reason)
    }

    @Test fun anUnsureGuessNeverRaisesTheAlarmAndAConfidentVerdictTakesOver() = runTest {
        val ui = MutableStateFlow(GuardUi())
        var confident = false
        val tiers = ArrayList<Tier>()
        val s = GuardSession(this, { tier, _ ->
            tiers += tier
            JudgeOutcome.Ok(if (confident) verdict(tier, 90, reason = "asks for your code") else verdict(tier, 90, confidence = "low"))
        }, null, CallerPrior(), ui, { testScheduler.currentTime })
        s.onEvent(SttEvent.Final("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen".replace(Regex("[a-z]+")) { "word" } + " and many more words were said on this call before anything clear was asked of the person holding it"))
        advanceUntilIdle()
        assertEquals(GuardSession.UNSURE_CAP, ui.value.score.display)   // capped: amber at most
        assertEquals(Band.AMBER, ui.value.score.band)
        assertEquals(listOf(Tier.FAST), tiers)                           // a guess does not wake the deep model
        confident = true
        s.onEvent(SttEvent.Final("now read me the code we sent to your phone"))
        advanceUntilIdle()
        assertEquals(Band.RED, ui.value.score.band)
        assertEquals("asks for your code", ui.value.reason)
    }

    @Test fun digitsNeverReachTheJudgeOrTheScreen() = runTest {
        val sent = ArrayList<String>()
        val ui = MutableStateFlow(GuardUi())
        val s = GuardSession(this, { _, t -> sent += t; JudgeOutcome.Ok(verdict(Tier.FAST, 80)) }, null, CallerPrior(), ui, { testScheduler.currentTime })
        s.onEvent(SttEvent.Final("sir please tell me the code you received on your phone right now"))
        s.onEvent(SttEvent.Final("ok it is four eight two nine one three"))
        advanceUntilIdle()
        assertTrue(sent.isNotEmpty())
        sent.forEach { assertFalse(it, it.contains("four eight two")) }
        assertTrue(sent.last().contains("[6-DIGIT NUMBER]"))
        assertFalse(ui.value.heard.contains("four eight"))
        assertTrue(ui.value.codeSpoken)
    }

    @Test fun amberTriggersTheDeepModelAndItsVerdictDominates() = runTest {
        val tiers = ArrayList<Tier>()
        val ui = MutableStateFlow(GuardUi())
        val s = GuardSession(this, { tier, _ ->
            tiers += tier
            JudgeOutcome.Ok(if (tier == Tier.FAST) verdict(Tier.FAST, 45, "medium", "fast reason") else verdict(Tier.DEEP, 90, "high", "deep reason"))
        }, null, CallerPrior(), ui, { testScheduler.currentTime })
        s.onEvent(SttEvent.Final("this is officer sharma from mumbai police there is a case registered against your aadhaar"))
        advanceUntilIdle()
        assertEquals(listOf(Tier.FAST, Tier.DEEP), tiers)
        assertEquals(Band.RED, ui.value.score.band)
        assertEquals("deep reason", ui.value.reason)
        assertEquals(1, ui.value.deepChecks)
    }

    @Test fun aNormalCallNeverPaysForTheDeepModel() = runTest {
        val tiers = ArrayList<Tier>()
        val ui = MutableStateFlow(GuardUi())
        val s = GuardSession(this, { tier, _ -> tiers += tier; JudgeOutcome.Ok(verdict(tier, 5)) }, null, CallerPrior(), ui, { testScheduler.currentTime })
        s.onEvent(SttEvent.Final("hi this is the clinic calling to confirm your appointment tomorrow at ten"))
        s.onEvent(SttEvent.Final("yes that is fine thank you see you then goodbye"))
        advanceUntilIdle()
        assertTrue(tiers.all { it == Tier.FAST })
        assertEquals(Band.GREEN, ui.value.score.band)
    }

    @Test fun aJudgeFailureIsShownAndTheLastScoreStands() = runTest {
        var fail = false
        val ui = MutableStateFlow(GuardUi())
        val s = GuardSession(this, { tier, _ ->
            if (fail) JudgeOutcome.Failed("No internet, so this call cannot be checked right now.") else JudgeOutcome.Ok(verdict(tier, 20))
        }, null, CallerPrior(), ui, { testScheduler.currentTime })
        s.onEvent(SttEvent.Final("hello i am calling from the courier office about a delivery for you"))
        advanceUntilIdle()
        assertEquals(20, ui.value.score.display)
        fail = true
        s.onEvent(SttEvent.Final("can you confirm you will be at home after five today"))
        advanceUntilIdle()
        assertEquals(20, ui.value.score.display)
        assertTrue(ui.value.problem!!.contains("No internet"))
    }

    @Test fun longSpeechIsCheckedBeforeTheSpeakerPauses() = runTest {
        var calls = 0
        val ui = MutableStateFlow(GuardUi())
        val s = GuardSession(this, { tier, _ -> calls++; JudgeOutcome.Ok(verdict(tier, 10)) }, null, CallerPrior(), ui, { testScheduler.currentTime })
        s.onEvent(SttEvent.Partial("one long sentence that the caller keeps saying without ever stopping to take a breath at all"))
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    // ---- CascadePolicy ----

    @Test fun cascadePolicy() {
        val p = CascadePolicy()
        assertFalse(p.shouldRunDeep(0, 10.0, 10.0))
        assertTrue(p.shouldRunDeep(0, 40.0, 40.0))
        p.onDeepStarted(0, 40.0)
        assertFalse("not again straight away", p.shouldRunDeep(5_000, 60.0, 45.0))
        assertTrue("fast model moved a long way", p.shouldRunDeep(6_000, 60.0, 70.0))
        assertTrue("periodic re-check while risky", p.shouldRunDeep(31_000, 60.0, 45.0))
        assertFalse("no periodic re-check once calm", p.shouldRunDeep(31_000, 10.0, 45.0))
    }

    // ---- wire format ----

    @Test fun requestCarriesOnlyTypeAndLevelOfTheProfile() {
        val body = JudgeClient.requestBody(Tier.DEEP, "hello", VulnerabilityProfile.MOCK, CallerPrior(international = true))
        val o = JSONObject(body)
        assertEquals("deep", o.getString("tier"))
        assertTrue(o.getJSONObject("prior").getBoolean("international"))
        val first = o.getJSONObject("profile").getJSONArray("threats").getJSONObject(0)
        assertEquals(setOf("type", "level"), first.keys().asSequence().toSet())
        assertFalse("the free-text reason stays on the phone", body.contains("Lives away from family"))
    }

    @Test fun verdictParsing() {
        val json = """{"signals":{"authority":{"evidence":"police","score":3},"profile_match":{"evidence":"x","score":2}},
            "matched_threat":"digital_arrest","confidence":"high","scam_probability":88,"reason":"They want money.","tier":"fast"}"""
        val v = Verdict.parse(json, Tier.FAST)!!
        assertEquals(88, v.probability)
        assertEquals(2, v.profileMatch)
        assertEquals("high", v.toFusionInput(VulnerabilityProfile.MOCK).matchedLevel)
        assertEquals("none", v.toFusionInput(null).matchedLevel)
        assertNull(Verdict.parse("""{"unavailable":"refused"}""", Tier.DEEP))
        assertThrows(Exception::class.java) { Verdict.parse("<html>502</html>", Tier.FAST) }
    }

    // ---- caller check ----

    @Test fun callerPrior() {
        assertEquals(CallerPrior(), ScamScreeningService.priorFor("+919848022338", "in", false))
        assertEquals(CallerPrior(), ScamScreeningService.priorFor("9848022338", "in", false))
        assertEquals(CallerPrior(international = true), ScamScreeningService.priorFor("+44 7700 900123", "in", false))
        assertEquals(CallerPrior(hidden = true), ScamScreeningService.priorFor(null, "in", false))
        assertEquals(CallerPrior(unverified = true), ScamScreeningService.priorFor("+15551234567", "us", true))
        assertTrue(ScamScreeningService.describe(CallerPrior(international = true)).contains("another country"))
    }

    // ---- audio helpers ----

    private fun wav(rate: Int, channels: Int, pcm: ByteArray, extraChunk: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(n: Int, bytes: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array(), 0, bytes)
        out.write("RIFF".toByteArray()); le(0, 4); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le(16, 4); le(1, 2); le(channels, 2); le(rate, 4); le(rate * 2 * channels, 4); le(2 * channels, 2); le(16, 2)
        if (extraChunk) { out.write("LIST".toByteArray()); le(3, 4); out.write(byteArrayOf(1, 2, 3, 0)) }   // odd size + pad byte
        out.write("data".toByteArray()); le(pcm.size, 4); out.write(pcm)
        return out.toByteArray()
    }

    @Test fun wavReader() {
        val pcm = ByteArray(64) { it.toByte() }
        assertTrue(pcm.contentEquals(CallGuardService.readWavPcm(ByteArrayInputStream(wav(16000, 1, pcm)))))
        assertTrue(pcm.contentEquals(CallGuardService.readWavPcm(ByteArrayInputStream(wav(16000, 1, pcm, extraChunk = true)))))
        assertThrows(IllegalArgumentException::class.java) { CallGuardService.readWavPcm(ByteArrayInputStream(wav(44100, 1, pcm))) }
        assertThrows(IllegalArgumentException::class.java) { CallGuardService.readWavPcm(ByteArrayInputStream(wav(16000, 2, pcm))) }
        assertThrows(IllegalArgumentException::class.java) { CallGuardService.readWavPcm(ByteArrayInputStream(ByteArray(100))) }
    }

    @Test fun quietSpeechIsTurnedUpAndLoudSpeechAndSilenceAreLeftAlone() {
        fun chunk(amp: Int) = ByteArray(3200).also { b ->
            for (i in 0 until 1600) {
                val v = (amp * kotlin.math.sin(i * 0.3)).toInt()
                b[2 * i] = (v and 0xff).toByte(); b[2 * i + 1] = ((v shr 8) and 0xff).toByte()
            }
        }
        fun peak(b: ByteArray) = (0 until 1600).maxOf { kotlin.math.abs((b[2 * it].toInt() and 0xff) or (b[2 * it + 1].toInt() shl 8)) }

        val quiet = com.scamguard.stt.GainControl()
        var last = ByteArray(0)
        repeat(40) { last = chunk(800); quiet.process(last, last.size) }          // a caller at about -32 dB
        assertTrue("gain ${quiet.currentGain}", quiet.currentGain > 8.0)
        assertTrue(peak(last) in 6500..9000)                                        // near a quarter of full scale

        val loud = com.scamguard.stt.GainControl()
        repeat(40) { last = chunk(20000); loud.process(last, last.size) }
        assertEquals(1.0, loud.currentGain, 0.001)                                  // never turned down, never clipped
        assertTrue(peak(last) in 19900..20000)

        val hiss = com.scamguard.stt.GainControl()
        repeat(40) { last = chunk(60); hiss.process(last, last.size) }              // room noise stays room noise
        assertEquals(1.0, hiss.currentGain, 0.001)

        // a loud voice right after a quiet one is brought back down within a few chunks, not left clipping
        repeat(4) { last = chunk(20000); quiet.process(last, last.size) }
        assertTrue("gain ${quiet.currentGain}", quiet.currentGain < 1.8)
    }

    @Test fun recognisedTextIsMadePlainForTheRedactor() {
        assertEquals("it says four eight two nine", com.scamguard.stt.SpeechEngine.normalise("It says four, eight two nine."))
        assertEquals("your kyc has expired i can't wait", com.scamguard.stt.SpeechEngine.normalise(" Your KYC has expired -- I can't wait! "))
        assertEquals("", com.scamguard.stt.SpeechEngine.normalise(" ... "))
        // and what comes out is redacted like any other transcript
        val r = com.scamguard.judge.PiiRedactor.redact(com.scamguard.stt.SpeechEngine.normalise("Okay, one second. It says four, eight two nine") + "\n" + com.scamguard.stt.SpeechEngine.normalise("one three"))
        assertTrue(r.text, r.text.contains("[6-DIGIT NUMBER]"))
        assertFalse(r.text, r.text.contains("eight"))
        val f = com.scamguard.stt.SpeechEngine.toFloats(byteArrayOf(0, 0x40, 0, 0xc0.toByte(), 0xff.toByte(), 0x7f), 6)
        assertEquals(0.5f, f[0], 1e-4f); assertEquals(-0.5f, f[1], 1e-4f); assertEquals(1f, f[2], 1e-3f)
    }

    @Test fun digitalSilence() {
        assertTrue(CallGuardService.isDigitalSilence(ByteArray(3200), 3200))
        assertTrue(CallGuardService.isDigitalSilence(byteArrayOf(1, 0, -1, -1, 2, 0), 6))      // +1, -1, +2
        assertFalse(CallGuardService.isDigitalSilence(byteArrayOf(0, 0, 100, 0), 4))
        assertFalse(CallGuardService.isDigitalSilence(byteArrayOf(0, 0, 0, -128), 4))           // large negative
    }
}
