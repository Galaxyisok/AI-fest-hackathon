package com.scamguard.calls

import com.scamguard.judge.CallerPrior

/**
 * Simulated calls for the demo. Android does not let an ordinary app hear a phone call directly,
 * so on stage these recordings stand in for the live audio. They go through the SAME pipeline:
 * the app does not know the label below, it only hears the audio.
 *
 * All four are synthetic: scripted by the team and spoken by text-to-speech voices.
 */
data class DemoCall(
    val asset: String,
    val title: String,
    val blurb: String,
    val isScam: Boolean,
    val prior: CallerPrior = CallerPrior(),
)

object DemoCalls {
    val all = listOf(
        DemoCall(
            "demo/scam_digital_arrest.wav", "\"Police\" call about a parcel",
            "A caller says a parcel in your name was seized and connects you to the police.", true,
            CallerPrior(international = true),
        ),
        DemoCall(
            "demo/scam_kyc_bank.wav", "\"Bank\" call about KYC",
            "A caller says your account will be blocked today unless you verify now.", true,
        ),
        DemoCall(
            "demo/scam_job_offer.wav", "Work-from-home job offer",
            "A recruiter says you are selected and must confirm your slot right now.", true,
        ),
        DemoCall(
            "demo/genuine_bank_card.wav", "Bank call about a card payment",
            "The card company checks whether a payment was really made by you.", false,
        ),
    )

    fun priorFor(asset: String): CallerPrior = all.firstOrNull { it.asset == asset }?.prior ?: CallerPrior()
}
