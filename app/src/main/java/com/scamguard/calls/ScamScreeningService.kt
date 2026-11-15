package com.scamguard.calls

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.scamguard.R
import com.scamguard.judge.CallerPrior
import com.scamguard.profile.Level
import com.scamguard.profile.ProfileRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pre-answer caller check. Android calls this while the phone is ringing, and only for numbers
 * that are NOT in the user's contacts (we hold no contacts permission, so that filter is done by
 * the system and we never see the address book).
 *
 * It never blocks or rejects a call. It tells the user what is known and offers to listen.
 * The number is used here, in memory, and then dropped: it is not stored, logged or sent anywhere.
 */
class ScamScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        // Always let the call through, first, so nothing below can delay the ring.
        respondToCall(details, CallResponse.Builder().build())
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return

        val prior = priorFor(
            number = details.handle?.schemeSpecificPart,
            networkCountry = getSystemService(TelephonyManager::class.java)?.networkCountryIso,
            verificationFailed = Build.VERSION.SDK_INT >= 30 &&
                details.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED,
        )
        Guard.pendingPrior = prior
        Guard.pendingPriorAt = SystemClock.elapsedRealtime()
        notify(this, prior)
    }

    companion object {
        private const val NOTIFICATION_ID = 3
        private val DIAL_CODES = mapOf("in" to "91", "us" to "1", "ca" to "1", "gb" to "44", "ae" to "971", "sg" to "65", "au" to "61")

        /** Pure, so it can be unit-tested. */
        fun priorFor(number: String?, networkCountry: String?, verificationFailed: Boolean): CallerPrior {
            val n = number?.filter { it.isDigit() || it == '+' }.orEmpty()
            val hidden = n.isEmpty()
            val home = DIAL_CODES[networkCountry?.lowercase()] ?: "91"
            val international = n.startsWith("+") && !n.startsWith("+$home")
            return CallerPrior(international = international, unverified = verificationFailed, hidden = hidden)
        }

        fun describe(prior: CallerPrior): String {
            val facts = buildList {
                add("This number is not in your contacts.")
                if (prior.international) add("It is calling from another country.")
                if (prior.hidden) add("The caller has hidden their number.")
                if (prior.unverified) add("Your network could not confirm the caller's number is real.")
            }
            return facts.joinToString(" ")
        }

        fun notify(context: Context, prior: CallerPrior) {
            CallGuardService.createChannels(context)
            val elevated = prior.international || prior.hidden || prior.unverified
            val exposed = runBlocking {
                withTimeoutOrNull(400) { ProfileRepository(context).load() }
            }?.threats?.filter { it.level == Level.HIGH }?.take(2)?.joinToString(" and ") { it.type.plainName.lowercase() }
            val text = buildString {
                append(describe(prior))
                if (exposed != null && exposed.isNotEmpty()) append(" Watch out for $exposed.")
                append(" If you answer, put the call on speaker and tap here so Scam Guard can listen.")
            }
            val n = NotificationCompat.Builder(context, CallGuardService.CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(if (elevated) "Unknown caller: take extra care" else "Unknown caller")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(CallGuardService.openApp(context, startLive = true))
                .setTimeoutAfter(10 * 60_000)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        }
    }
}
