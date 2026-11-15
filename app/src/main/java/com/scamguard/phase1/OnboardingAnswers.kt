package com.scamguard.phase1

import com.scamguard.profile.ThreatType
import org.json.JSONArray
import org.json.JSONObject

/** One choice: the code the Phase 1 backend expects, and the words the user sees. */
data class Choice(val key: String, val label: String)

/**
 * Every question and choice the onboarding screen offers. The keys are the Phase 1 backend's
 * API enums (contracts/openapi.json); OnboardingContractTest fails if the two ever differ.
 * There are no free-text fields on purpose: nothing identifying can be typed in.
 */
object OnboardingQuestions {
    val userType = listOf(
        Choice("student", "Student"), Choice("parent", "Parent"),
        Choice("senior", "Senior citizen"), Choice("professional", "Working professional"),
    )
    val ageBand = listOf(
        Choice("under_18", "Under 18"), Choice("18_29", "18 to 29"), Choice("30_44", "30 to 44"),
        Choice("45_59", "45 to 59"), Choice("60_plus", "60 or older"),
    )
    val livingSituation = listOf(
        Choice("alone", "On my own"), Choice("with_family", "With family"), Choice("with_others", "With other people"),
    )
    val employment = listOf(
        Choice("studying", "Studying"), Choice("employed", "Employed"), Choice("self_employed", "Self-employed"),
        Choice("seeking_work", "Looking for work"), Choice("retired", "Retired"), Choice("other", "Other"),
    )
    val digitalConfidence = listOf(
        Choice("low", "Not very"), Choice("medium", "Fairly"), Choice("high", "Very"),
    )
    val bankAppUsage = listOf(
        Choice("none", "None"), Choice("one", "One"), Choice("multiple", "More than one"),
    )
    val activities = listOf(
        Choice("online_banking", "Online banking"), Choice("online_shopping", "Online shopping"),
        Choice("job_search", "Looking for jobs"), Choice("loan_apps", "Loan apps"),
        Choice("international_parcels", "Parcels from abroad"), Choice("online_bill_payments", "Paying bills online"),
        Choice("social_media", "Social media"),
    )
    val concerns = ThreatType.entries.map { Choice(it.key, it.plainName) }
    val priorScamExposure = listOf(
        Choice("yes", "Yes"), Choice("no", "No"), Choice("prefer_not_to_say", "Rather not say"),
    )

    /** API field name -> its choices. Used by the contract test. */
    val byField = mapOf(
        "userType" to userType, "ageBand" to ageBand, "livingSituation" to livingSituation,
        "employment" to employment, "digitalConfidence" to digitalConfidence, "bankAppUsage" to bankAppUsage,
        "activities" to activities, "concerns" to concerns, "priorScamExposure" to priorScamExposure,
    )
}

/** The user's selections. Lives in memory only; never saved, never restored after the screen closes. */
data class OnboardingAnswers(
    val userType: String? = null,
    val ageBand: String? = null,
    val livingSituation: String? = null,
    val employment: String? = null,
    val digitalConfidence: String? = null,
    val bankAppUsage: String? = null,
    val activities: Set<String> = emptySet(),
    val concerns: Set<String> = emptySet(),
    val priorScamExposure: String? = null,
) {
    val canSubmit get() = userType != null

    /** The `answers` object of POST /v1/onboarding/profile. Skipped questions are left out. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("userType", requireNotNull(userType) { "userType is required" })
        ageBand?.let { put("ageBand", it) }
        livingSituation?.let { put("livingSituation", it) }
        employment?.let { put("employment", it) }
        digitalConfidence?.let { put("digitalConfidence", it) }
        bankAppUsage?.let { put("bankAppUsage", it) }
        if (activities.isNotEmpty()) put("activities", JSONArray(activities.sorted()))
        if (concerns.isNotEmpty()) put("concerns", JSONArray(concerns.sorted()))
        priorScamExposure?.let { put("priorScamExposure", it) }
    }
}
