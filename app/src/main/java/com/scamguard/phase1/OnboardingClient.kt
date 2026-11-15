package com.scamguard.phase1

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Blocking native Android adapter. Invoke on Dispatchers.IO, never the UI thread.
 * No API key, logging, persistence, retries, or shared profile types live here.
 */
class OnboardingClient(endpoint: String) {
    private val url = URL(endpoint).also {
        // HTTPS only. The one exception is the Android emulator's alias for the developer's own
        // machine, so the backend can be run locally during development (integration change).
        val devLoopback = it.protocol == "http" && it.host in setOf("10.0.2.2", "localhost", "127.0.0.1")
        require((it.protocol == "https" || devLoopback) && it.userInfo == null && it.query == null && it.ref == null) {
            "Onboarding endpoint must be an https URL"
        }
    }

    class Failure(val status: Int) : Exception("Onboarding could not be completed")

    fun generate(answers: JSONObject, consent: Boolean): String {
        require(consent) { "Consent is required" }
        val body = JSONObject().put("consent", true).put("answers", answers)
            .toString().toByteArray(Charsets.UTF_8)
        require(body.size <= 8192) { "Request is too large" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 45_000
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status != 200) throw Failure(status)
            val bytes = connection.inputStream.use { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (result.size() + count > 16384) throw Failure(502)
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
            // Decode with the app's shared strict profile serializer before save().
            return String(bytes, Charsets.UTF_8)
        } finally {
            body.fill(0)
            connection.disconnect()
        }
    }
}
