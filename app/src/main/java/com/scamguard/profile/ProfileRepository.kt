package com.scamguard.profile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore(name = "scamguard")

/**
 * The ONLY API Phase 1 touches:
 *
 *     ProfileRepository(context).save(profile)   // Phase 1, after its analysis
 *     ProfileRepository(context).load()          // Phase 2, when a call starts
 *
 * Everything this app keeps on disk is in this one small store: the derived profile, the two service
 * addresses, and a count of "this was wrong" taps. No transcripts, no audio, no phone numbers.
 */
class ProfileRepository(context: Context) {
    private val store = context.applicationContext.store

    suspend fun save(profile: VulnerabilityProfile) {
        store.edit { it[PROFILE] = profile.toJson() }
    }

    /** null until Phase 1 has saved one (or if what is stored breaks the contract). */
    suspend fun load(): VulnerabilityProfile? = profileFlow.first()

    val profileFlow: Flow<VulnerabilityProfile?> = store.data.map { prefs ->
        prefs[PROFILE]?.let { runCatching { VulnerabilityProfile.fromJson(it) }.getOrNull() }
    }

    suspend fun clear() { store.edit { it.remove(PROFILE) } }

    // ---- Phase 2 settings (not part of the Phase 1 contract) ----
    val judgeUrlFlow: Flow<String?> = store.data.map { it[JUDGE_URL] }
    suspend fun setJudgeUrl(url: String?) {
        store.edit { if (url.isNullOrBlank()) it.remove(JUDGE_URL) else it[JUDGE_URL] = url.trim() }
    }

    val phase1UrlFlow: Flow<String?> = store.data.map { it[PHASE1_URL] }
    suspend fun setPhase1Url(url: String?) {
        store.edit { if (url.isNullOrBlank()) it.remove(PHASE1_URL) else it[PHASE1_URL] = url.trim() }
    }

    /** True once the first-run profile screen has been shown, so skipping it is respected. */
    val onboardingOfferedFlow: Flow<Boolean> = store.data.map { it[ONBOARDING_OFFERED] ?: false }
    suspend fun setOnboardingOffered() { store.edit { it[ONBOARDING_OFFERED] = true } }

    val wrongCountFlow: Flow<Int> = store.data.map { it[WRONG_COUNT] ?: 0 }
    suspend fun countWrong() { store.edit { it[WRONG_COUNT] = (it[WRONG_COUNT] ?: 0) + 1 } }

    private companion object {
        val PROFILE = stringPreferencesKey("profile_json")
        val JUDGE_URL = stringPreferencesKey("judge_url")
        val PHASE1_URL = stringPreferencesKey("phase1_url")
        val WRONG_COUNT = intPreferencesKey("wrong_count")
        val ONBOARDING_OFFERED = booleanPreferencesKey("onboarding_offered")
    }
}
