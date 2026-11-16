package com.scamguard

import com.scamguard.judge.CallerPrior
import com.scamguard.judge.JudgeClient
import com.scamguard.judge.JudgeOutcome
import com.scamguard.judge.Tier
import com.scamguard.net.ServerAddress
import com.scamguard.phase1.OnboardingClient
import com.scamguard.phase1.onboardingErrorMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real-phone bug of 2026-11-16: a build pointing at 10.0.2.2 said "could not be reached" / "took too long". */
class ServerAddressTest {
    private val emu = "http://10.0.2.2:8000/v1/onboarding/profile"

    @Test fun emulatorAddressOnAPhoneIsNamedNotBlamedOnTheNetwork() {
        // Wi-Fi (connection refused) and mobile data (timeout) were the two screenshots.
        for (e in listOf(java.net.ConnectException("refused"), java.net.SocketTimeoutException())) {
            val msg = onboardingErrorMessage(e, emu)
            assertTrue(msg, msg.contains("emulator"))
            assertTrue(msg, msg.contains("Developer settings"))
        }
    }

    @Test fun realAddressesKeepTheOrdinaryMessages() {
        val real = "https://example.org/v1/onboarding/profile"
        assertTrue(onboardingErrorMessage(java.net.SocketTimeoutException(), real).contains("too long"))
        val dns = onboardingErrorMessage(java.net.UnknownHostException("secret.thing"), real)
        assertTrue(dns, dns.contains("could not find") && dns.contains("example.org"))
        assertFalse(dns.contains("secret"))
        assertTrue(onboardingErrorMessage(javax.net.ssl.SSLHandshakeException("x"), real).contains("secure connection"))
    }

    /** The 18:34 screenshots: a dead tunnel address read as "No internet". It must fall back, or name the host. */
    @Test fun deadSavedJudgeAddressFallsBackToTheBuiltInOne() = runBlocking {
        val server = java.net.ServerSocket(0)
        val port = server.localPort
        val t = Thread {
            server.accept().use { sock ->
                val input = sock.getInputStream().bufferedReader()
                var len = 0
                while (true) {
                    val l = input.readLine() ?: break
                    if (l.isEmpty()) break
                    if (l.lowercase().startsWith("content-length:")) len = l.substring(15).trim().toInt()
                }
                repeat(len) { input.read() }
                val body = "{\"unavailable\":true}"
                sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
            }
        }.apply { start() }
        val out = JudgeClient("https://dead-tunnel.invalid/api/judge", "http://localhost:$port/api/judge")
            .judge(Tier.FAST, "hello", null, CallerPrior())
        t.join(5000); server.close()
        assertEquals(JudgeOutcome.NoVerdict, out)   // reached the fallback server and parsed its answer

        val none = JudgeClient("https://dead-tunnel.invalid/api/judge").judge(Tier.FAST, "hello", null, CallerPrior())
        val msg = (none as JudgeOutcome.Failed).userMessage
        assertTrue(msg, msg.contains("dead-tunnel.invalid") && !msg.contains("No internet"))
    }

    @Test fun savedAddressIsTriedFirstThenBuiltIn() {
        assertEquals(listOf("https://a/x", "https://b/x"), ServerAddress.candidates(" https://a/x ", "https://b/x"))
        assertEquals(listOf("https://b/x"), ServerAddress.candidates(null, "https://b/x"))
        assertEquals(listOf("https://b/x"), ServerAddress.candidates("https://b/x", "https://b/x"))
    }

    @Test fun aBadAddressIsNotBlamedOnTheServer() {
        val e = assertThrows(OnboardingClient.BadAddress::class.java) { OnboardingClient("not a url") }
        assertTrue(onboardingErrorMessage(e, "not a url").contains("not a valid https address"))
        assertThrows(OnboardingClient.BadAddress::class.java) { OnboardingClient("http://192.168.1.5:8000/v1/onboarding/profile") }
    }

    @Test fun judgeNamesTheEmulatorAddressInsteadOfNoInternet() = runBlocking {
        // Nothing listens on port 9 locally, so this fails fast like a phone hitting 10.0.2.2.
        val out = JudgeClient("http://localhost:9/api/judge").judge(Tier.FAST, "hello", null, CallerPrior())
        assertTrue(out is JudgeOutcome.Failed)
        assertTrue((out as JudgeOutcome.Failed).userMessage, out.userMessage.contains("emulator"))
        val bad = JudgeClient("nonsense").judge(Tier.FAST, "hello", null, CallerPrior())
        assertTrue((bad as JudgeOutcome.Failed).userMessage.contains("not valid"))
    }

    @Test fun pastedAddressesAreCompleted() {
        assertEquals("https://abc.trycloudflare.com/api/judge",
            ServerAddress.normalize("  abc.trycloudflare.com/ ", ServerAddress.JUDGE_PATH))
        assertEquals("https://abc.trycloudflare.com/v1/onboarding/profile",
            ServerAddress.normalize("https://abc.trycloudflare.com", ServerAddress.PROFILE_PATH))
        assertEquals("https://h.example/custom/path",
            ServerAddress.normalize("https://h.example/custom/path", ServerAddress.JUDGE_PATH))
        assertEquals("", ServerAddress.normalize("   ", ServerAddress.JUDGE_PATH))
    }

    @Test fun addressProblemsAreRecognised() {
        assertTrue(ServerAddress.isEmulatorOnly(emu))
        assertTrue(ServerAddress.isPlainHttpToRealHost("http://192.168.1.5:8787/api/judge"))
        assertFalse(ServerAddress.isPlainHttpToRealHost(emu))
        assertNull(ServerAddress.addressProblem("https://x.example/api/judge", "checking service"))
    }
}
