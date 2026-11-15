# Native Android integration (Phase 1 only)

> **Integrated.** Since the branches were joined, the client lives at
> `app/src/main/java/com/scamguard/phase1/OnboardingClient.kt` (moved from `integration/phase1/`, package
> changed to `com.scamguard.phase1`, and plain HTTP allowed only for the emulator loopback `10.0.2.2`).
> The onboarding screen that follows the rules below is `Phase1Screen.kt` in the same folder.
> This file is kept as the original hand-off note.

The repository had no Android project or shared profile implementation when this
backend was built. The client below is integration source, not a compiled APK.
Keep Kotlin + Jetpack Compose and ship both phases in the same app. Do not create
a second profile repository or modify Phase 2. No call/microphone/contact permissions
are needed for onboarding; the app needs `android.permission.INTERNET` only.

1. Copy `phase1/OnboardingClient.kt` into the app's own `phase1` package (adjust its
   package prefix). Supply the full HTTPS URL ending in `/v1/onboarding/profile`.
   The endpoint is configuration, not a secret. No Anthropic key belongs in Android.
2. Keep answers in ordinary in-memory Compose/ViewModel state. Do not use
   `rememberSaveable`, `SavedStateHandle`, Room, preferences, analytics, request-body
   interceptors or background jobs for answers. Avoid text fields: use API enums.
3. On consent and submit, copy selections into a JSONObject and clear the editable
   answer state. Call `generate` on `Dispatchers.IO`, never the main thread. Clear
   the temporary JSONObject in `finally` by removing all keys. Network failure
   requires re-entry; do not store pending answers for offline retries.
4. Decode the returned JSON into the existing shared profile type using its strict
   serializer. Call `ProfileRepository.save(profile)` and await successful storage
   before navigating to Phase 2. If storage fails, retain only the derived profile
   in memory for retry and show an error. Never navigate on a failed save.
5. On launch, `ProfileRepository.load()` determines whether a saved profile exists.
   If none exists, show onboarding. These are the only shared repository methods
   Phase 1 calls. Shared storage must exclude backups if the app's privacy policy
   requires device-only data; coordinate that setting with the app owner.

Illustrative wiring (adapt the existing profile serializer and suspend signatures):

```kotlin
// In viewModelScope, after consent; all answer state is transient.
try {
    val json = withContext(Dispatchers.IO) { client.generate(answers, consent = true) }
    val profile = existingProfileSerializer.decode(json)
    ProfileRepository.save(profile)
    // Mark success / navigate only here.
} finally {
    answers.keys().asSequence().toList().forEach { answers.remove(it) }
    // Clear any remaining UI answer state as well.
}
```

The supplied blocking adapter has finite timeouts; cancelling a coroutine does not
immediately interrupt HttpURLConnection. Do not retain its work beyond the screen
lifecycle; discard any result after cancellation and do not save it. The ViewModel
must disable duplicate submits. For immediate socket cancellation, adapt to the
app's existing cancellable HTTP client with body logging disabled.

## Onboarding UI requirements for the other developer

- Welcome/privacy: explain personalized scam awareness, coarse information sent to
  Claude, temporary processing, and only the derived profile saved. Explicit consent.
- Selections: required user type (`student`, `parent`, `senior`, `professional`);
  optional age band, living situation, employment, confidence, banking usage,
  activities, prior exposure and concerns from the API schema. Allow skipping optional
  questions. No identity/credential/ID-upload fields or unrestricted text.
- Submit: loading state, disabled duplicate submit, accessible status text. Error
  states for offline, timeout, invalid selection, busy and unavailable service. Do
  not display provider errors or raw request payloads. Retry is explicit.
- Result: show AI-derived threat names, low/medium/high labels and short reasons.
  Explain this is educational, not a guarantee of safety. Save before continuing.
- Use readable contrast, scalable text, labelled controls, 48dp touch targets, and
  English copy. No Phase 2 calls, call analysis, training engine or bottom navigation
  is part of this backend delivery.

Integration acceptance: submit synthetic answers against live HTTPS proxy, decode
all eight supported types, save, restart app, load an identical version-1 profile,
and verify that only profile fields exist on disk. Test errors leave the old profile
intact and never advance to Phase 2. Schedule this before the requested six-hour
pre-deadline buffer; the actual hackathon deadline was not supplied.
