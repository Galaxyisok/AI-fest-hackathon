package com.scamguard.phase1

import com.scamguard.profile.ThreatType

/**
 * The training half of Phase 1: for each scam type, how the call goes, what gives it away, and
 * the one safe thing to do. Written by the team from widely published guidance on these scams;
 * it is fixed text, not AI output, so it reads the same for everyone and should be checked
 * against cybercrime.gov.in before release. What is personal is the ORDER: the user's profile puts their likeliest scams first.
 *
 * Three types also have a practice call: a synthetic recording played through the real detector.
 */
data class Guide(
    val howItGoes: String,
    val signs: List<String>,
    val safeMove: String,
    val practiceAsset: String? = null,     // a demo call in assets/demo, if we have one for this type
    val practiceTitle: String? = null,
)

object ThreatGuide {
    fun of(type: ThreatType): Guide = when (type) {
        ThreatType.DIGITAL_ARREST -> Guide(
            "Someone says a parcel, SIM card or bank account in your name is linked to a crime. They pass you to a \"police officer\" who says you are under \"digital arrest\" and must stay on a video call until you move your money to a \"safe\" account.",
            listOf(
                "They say you must not hang up or tell your family.",
                "They want you on a video call, sometimes for hours.",
                "They ask you to transfer money to \"verify\" it or keep it safe.",
            ),
            "Hang up. There is no such thing as a digital arrest. Real police do not arrest, question or collect money over a phone or video call.",
            "demo/scam_digital_arrest.wav", "\"Police\" call about a parcel",
        )
        ThreatType.KYC_BANK -> Guide(
            "A caller from \"your bank\" says your KYC has expired or your card is blocked, and offers to fix it on the call. They ask for the code sent to your phone, your card details, or for you to install an app.",
            listOf(
                "They ask you to read out an OTP, PIN, CVV or card number.",
                "They ask you to install an app such as AnyDesk or TeamViewer.",
                "They say there is no time to visit a branch.",
            ),
            "Hang up and call the number printed on the back of your card. A bank never asks for an OTP, PIN or CVV on a call.",
            "demo/scam_kyc_bank.wav", "\"Bank\" call about KYC",
        )
        ThreatType.COURIER_CUSTOMS -> Guide(
            "A caller says a parcel for you is stuck: wrong address, unpaid customs duty, or something illegal inside. They ask for a small fee through a link, or pass you to \"customs\" or \"police\".",
            listOf(
                "You are not expecting that parcel.",
                "They send a link and ask for card details to pay a few rupees.",
                "A missed delivery suddenly turns into a criminal matter.",
            ),
            "Do not pay through any link they send. Check the order in the shopping or courier app you actually used.",
        )
        ThreatType.JOB_OFFER -> Guide(
            "A \"recruiter\" says you are selected, often with no interview and a high salary for little work. Before you can start you must pay a registration, training, uniform or visa fee, or deposit money for \"tasks\".",
            listOf(
                "You are asked to pay anything at all to get the job.",
                "The offer expires in minutes.",
                "They ask you to install an app or enter your UPI PIN to \"receive\" money.",
            ),
            "A real employer never charges you to hire you. Do not pay, and check the company through its official website.",
            "demo/scam_job_offer.wav", "Work-from-home job offer",
        )
        ThreatType.FAMILY_EMERGENCY -> Guide(
            "Someone says a relative has had an accident, been arrested or lost their phone and needs money right now. It may even sound like them: voices can be copied.",
            listOf(
                "The relative \"cannot talk right now\".",
                "They tell you not to call anyone else.",
                "The money must go to a stranger's number.",
            ),
            "Hang up and call your relative on the number you already have. If you cannot reach them, call someone who is with them.",
        )
        ThreatType.LOAN_APP -> Guide(
            "Either a loan is \"pre-approved\" and you only need to pay a processing fee first, or a loan app you used starts threatening to send your photos and contacts to everyone unless you pay more.",
            listOf(
                "A fee has to be paid before the loan is released.",
                "They threaten to shame you in front of your contacts.",
                "The amount they say you owe keeps growing.",
            ),
            "Do not pay a fee to get a loan, and do not pay under threat. Report the app on cybercrime.gov.in or call 1930.",
        )
        ThreatType.SIM_SWAP -> Guide(
            "A caller from \"your mobile company\" offers a free upgrade to 5G or eSIM and asks you to confirm a message or share a code. Your SIM then goes dead, and theirs starts receiving your bank OTPs.",
            listOf(
                "They ask you to reply to an SMS or share a code or QR code.",
                "They say your phone will show \"no service\" for a while and that this is normal.",
                "They tell you not to visit a store or call customer care.",
            ),
            "Never share a code to \"upgrade\" a SIM. If your phone loses signal for no reason, tell your bank and your mobile company from another phone straight away.",
        )
        ThreatType.UTILITY_DISCONNECT -> Guide(
            "A caller or message says your electricity or gas will be cut tonight because a bill was not updated. To stop it you must call an \"officer\", install an app, or pay a small amount while they watch.",
            listOf(
                "Disconnection is threatened within hours.",
                "They ask you to install a \"support\" app.",
                "They ask you to pay to a personal number, not through the official site.",
            ),
            "Check your bill in the official app or website, or call the number printed on a paper bill. Never install an app because a caller told you to.",
        )
    }
}
