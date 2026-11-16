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

    /**
     * Plain words for a network failure, naming the server and what failed, so a screenshot is
     * enough to tell a blocked or dead address from a phone that is really offline.
     * Only the configured host is shown, never anything from the exception.
     */
    fun describeFailure(e: java.io.IOException, url: String, service: String): String {
        addressProblem(url, service)?.let { return it }
        val h = host(url) ?: "the server"
        return when (e) {
            is java.net.UnknownHostException ->
                "This phone could not find the $service ($h). Check your internet, or switch between Wi-Fi and mobile data: some networks block it."
            is javax.net.ssl.SSLException ->
                "This network refused a secure connection to the $service ($h). Switch between Wi-Fi and mobile data and try again."
            is java.net.SocketTimeoutException ->
                "The $service ($h) took too long to answer. Please try again."
            is java.net.ConnectException ->
                "The $service ($h) could not be reached. Please try again."
            else -> "The connection to the $service ($h) dropped. Check your internet and try again."
        }
    }

    /** Addresses to try in order: the one saved in Setup (if any), then the one built into the app. */
    fun candidates(saved: String?, builtIn: String): List<String> =
        listOfNotNull(saved?.trim()?.takeIf { it.isNotEmpty() }, builtIn).distinct()

    private fun host(url: String): String? = runCatching { URI(url.trim()).host }.getOrNull()
}
