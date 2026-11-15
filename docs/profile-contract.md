# Profile contract: Phase 1 → Phase 2

Phase 1 works out which scams a person is most exposed to. Phase 2 (the live call guard) reads that and uses it while judging a call. This file is the whole agreement between the two.

## The two calls

```kotlin
import com.scamguard.profile.*

// Phase 1, after its AI analysis:
ProfileRepository(context).save(profile)        // suspend

// Phase 2, when a call starts:
val profile = ProfileRepository(context).load() // suspend, null if nothing saved yet
```

Phase 1 code lives in `app/src/main/java/com/scamguard/phase1/`. Replace the body of `Phase1Screen(onDone)` and keep its signature. Nothing else in the app needs to change.

## The format

```json
{
  "version": 1,
  "userType": "student",
  "threats": [
    { "type": "digital_arrest", "level": "high", "why": "Lives away from family and has never dealt with police." }
  ]
}
```

| Field | Allowed values |
|---|---|
| `version` | `1` |
| `userType` | `student`, `parent`, `senior`, `professional` |
| `threats[].type` | `digital_arrest`, `kyc_bank`, `courier_customs`, `job_offer`, `family_emergency`, `loan_app`, `sim_swap`, `utility_disconnect` |
| `threats[].level` | `low`, `medium`, `high` |
| `threats[].why` | One plain-English line, up to 200 characters. Shown to the user. **Never sent off the phone.** |

In Kotlin this is `VulnerabilityProfile(userType, threats)` with `Threat(type, level, why)`; the enums are `UserType`, `ThreatType`, `Level`. `VulnerabilityProfile.fromJson()` throws `IllegalArgumentException` with a readable message if a value is outside the table. A type may appear at most once; leave out types that do not apply.

Phase 2 keys off the `type` strings. **Ask before adding or renaming one.**

## Rules that carry marks

1. **Derived data only.** The profile holds scam types, levels and a short reason. It must not hold a name, phone number, Aadhaar or PAN, birthday, address, employer or college name. Keep `why` generic ("orders online often"), not identifying ("orders from X to flat 204").
2. **Discard the raw answers.** Use what the user tells you for the analysis, then let it go. Do not write it to disk, do not log it.
3. **Never ask** for a password, OTP, PIN, full card number or an ID scan.
4. **The mapping from answers to threats must be done by AI**, not by a rule list such as "age over 60 → digital_arrest". The brief marks down rule lists dressed up as AI.
5. **No API key in the app.** Call the model through the proxy.

## How Phase 2 uses it

- Only `userType` and each threat's `type` and `level` are sent to the judge, alongside the redacted transcript. `why` stays on the phone.
- The judge reports how closely a call follows one of these scam types. If the match is strong and the user's level for that type is `high` or `medium`, the score is nudged up (see `ScoreFusion.kt`). A profile can never turn a normal call into a warning by itself.
- The pre-answer notification names the user's `high` threats ("Watch out for fake police calls").
- With no profile saved, Phase 2 still works; it just has no personal nudge.

## Until Phase 1 lands

`Phase1Screen.kt` saves one of four hand-written sample profiles so Phase 2 can be built and demoed. `VulnerabilityProfile.MOCK` is the student sample and is what the demo calls fall back to on a fresh install.
