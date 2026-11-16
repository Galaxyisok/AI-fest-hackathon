package com.scamguard.net

import java.net.URI

/**
 * The two server addresses the app talks to, and the mistakes that used to read as "no internet".
 *
 * 10.0.2.2 exists only inside the Android emulator (it is the emulator's name for the laptop).
 * On a real phone it leads nowhere: Wi-Fi refuses it, mobile data hangs until the timeout.
 * Builds made without -PjudgeUrl / -Pphase1Url default to it, so a phone must be told where
 * the servers are, either at build time or in Setup -> Developer settings.
 */
object ServerAddress {
    const val PROFILE_PATH = "/v1/onboarding/profile"
    const val JUDGE_PATH = "/api/judge"

    private val LOOPBACK = setOf("10.0.2.2", "localhost", "127.0.0.1")

    /** True when the address only works from the emulator on the developer's laptop. */
    fun isEmulatorOnly(url: String): Boolean = host(url) in LOOPBACK

    /** True when the address is plain http to a real host, which Android refuses to send. */
    fun isPlainHttpToRealHost(url: String): Boolean = runCatching {
        val u = URI(url.trim())
        u.scheme.equals("http", ignoreCase = true) && u.host !in LOOPBACK
    }.getOrDefault(false)

    /**
     * Tidies an address typed or pasted into Setup: trims it, adds https:// when no scheme was
     * given, and adds the service path when only the host was pasted. Blank stays blank
     * (blank means "reset to the built-in address").
     */
    fun normalize(input: String, defaultPath: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (!s.contains("://")) s = "https://$s"
        val path = runCatching { URI(s).path }.getOrNull()
        if (path.isNullOrEmpty() || path == "/") s += defaultPath
        return s
    }

    /** Plain words for a connection that failed, or null if the address is not the cause. */
    fun addressProblem(url: String, service: String): String? = when {
        isEmulatorOnly(url) ->
            "This app is still pointing at the emulator's address, which does not exist on a phone. " +
                "Open Setup, then Developer settings, and enter the $service address."
        isPlainHttpToRealHost(url) ->
            "The $service address must start with https://. Fix it in Setup, then Developer settings."
        else -> null
    }

    private fun host(url: String): String? = runCatching { URI(url.trim()).host }.getOrNull()
}
