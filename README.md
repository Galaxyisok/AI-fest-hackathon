# Scam Guard — live call scam detection (AI-FEST 2026, CSE: Digital Identity Theft)

An Android app that listens to a phone call with you and shows **how likely it is to be a scam, as a percentage, while you are still on the call**, with one sentence of why and one thing to do.

- **Threat:** impersonation scam calls (fake police / "digital arrest", fake bank KYC, courier and customs, job offers, "your relative is in trouble", loan apps, SIM swap, utility disconnection).
- **Stage:** *during* the attack.
- **Two parts, one APK.** Phase 1 (*before* a call) asks a few tap-only questions, has Claude Haiku build a personal scam profile (which scripts would work on *you*), then teaches those scams, likeliest first, with practice calls. Phase 2 (*during* a call) uses that profile to judge live calls. This branch contains both. The contract between them is [`docs/profile-contract.md`](docs/profile-contract.md).

## How it works

```
Phone rings ─> caller check (CallScreeningService) ─> "Unknown caller, calling from another country. Tap to listen."
Call on speaker ─> microphone ─> Vosk speech-to-text, ON THE PHONE ─> numbers and IDs removed, ON THE PHONE
              ─> redacted text ─> judge proxy ─> Claude Haiku 5.5 on every new stretch of speech (fast)
                                             └> Claude Sonnet 5.5 re-reads the whole call once it looks risky (deep)
              ─> score maths (caller prior, profile match, smoothing) ─> "About 75%. Likely a scam. Hang up."
Demo mode: a bundled recording goes through the same pipeline from speech-to-text onward.
```

The judge does not match keywords. It scores seven things about the *shape* of the conversation (claimed authority, pressure, secrecy, a request for money, a request for codes or device access, how far along the script is, and the match to this user's profile), each with the words it heard as evidence, and returns a probability through an enforced JSON schema.

| Score | Shown as | Advice |
|---|---|---|
| no score yet | Listening… | Not enough has been said to judge |
| 0–25 | green, "Looks normal" | Nothing worrying so far |
| 30–55 | amber, "Be careful" | Never share an OTP or PIN, never install an app they ask for |
| 60+ | red, "Likely a scam" | Hang up, call back on the official number, 1930 / Sanchar Saathi one tap away |

## What is proven and what is not

| Claim | Status |
|---|---|
| APK builds, installs, cold-launches | Verified on an Android 15 emulator |
| Recording → on-device speech-to-text → redaction → proxy → meter | Verified on the emulator with the live models |
| Unknown-caller notification before answering | Verified on the emulator with a simulated incoming call |
| Redaction and score maths are identical in the app and in the eval | Verified: Kotlin and JavaScript pass the same vectors in `shared/` |
| Onboarding: questions → Phase 1 server → profile saved → shown on Home after restart; a server failure leaves the old profile untouched | Verified on the emulator against the real Phase 1 server with a canned analyzer; the server's live Haiku call verified separately (a sensible profile in 3.5 s) |
| First launch opens the profile questions once; "Know the scams" lists the user's likeliest scams first and starts a practice call | Verified on the emulator |
| Scams caught / false alarms on the held-out test set (52 synthetic calls) | **26 of 26 scams caught, 1 of 26 genuine calls wrongly alarmed** in the shipped cascade mode. Synthetic data: read the caveats in [`eval/results.md`](eval/results.md). |
| The four demo calls land in the right colour with the live model | Verified on the emulator: genuine call peaked at 15%, the three scams reached 95% or more |
| Hearing a real phone call on speaker | **Built, not proven on hardware.** Needs the accessibility switch in Setup; see `docs/limits.md`. |

## Build and run

**Deployed (Vercel, both stateless, key held as a project environment variable):** call judge `https://scamguard-judge.vercel.app/api/judge` (project in `proxy/`), profile service `https://scamguard-profile.vercel.app/v1/onboarding/profile` (repo root: `api/index.py` serves `backend/` unchanged; `.vercelignore` uploads only that). Put the key on both with `bash scripts/vercel-set-key.sh`, then `npx vercel deploy --prod` in each.

Needs JDK 17+, the Android SDK (API 35) and Node 20+. The first build downloads the speech model (about 40 MB).

```bash
# 1. both servers, locally, with one key (Python 3.12 and Node 20+; installs what is missing)
echo ANTHROPIC_API_KEY=sk-ant-... > .env        # repo root, git-ignored
bash scripts/dev-servers.sh                      # profile service :8000 and call judge :8787; leave running

# 2. app, on an emulator (defaults: judge http://10.0.2.2:8787/api/judge, profile http://10.0.2.2:8000/v1/onboarding/profile)
./gradlew installDebug
#    or against the deployed servers (what a real phone needs; 10.0.2.2 exists only in the emulator):
./gradlew assembleRelease -PjudgeUrl=https://scamguard-judge.vercel.app/api/judge -Pphase1Url=https://scamguard-profile.vercel.app/v1/onboarding/profile

# 3. tests
./gradlew testDebugUnitTest
node --test eval/lib/lib.test.mjs && (cd proxy && npm test)
.venv/Scripts/python -m pytest -q

# 4. start a demo call without touching the screen (handy for rehearsal)
adb shell am start -n com.scamguard/.MainActivity --es open demo --es asset demo/scam_kyc_bank.wav
```

Evaluation: [`eval/README.md`](eval/README.md). Deploying the proxy: [`proxy/README.md`](proxy/README.md).

## Layout

```
app/src/main/java/com/scamguard/
  calls/    ScamScreeningService (caller check), CallGuardService (mic + demo), GuardSession (the pipeline),
            CallAudioAccessService (lets Android un-mute the mic during calls), DemoCalls
  stt/      VoskEngine (on-device speech-to-text)
  judge/    PiiRedactor, JudgeClient, CascadePolicy, ScoreFusion
  profile/  VulnerabilityProfile, ProfileRepository      <- the Phase 1 / Phase 2 contract
  phase1/   Phase1Screen, OnboardingAnswers, OnboardingClient   <- onboarding: questions -> AI profile
            LearnScreen, ThreatGuide                            <- training: each scam explained, practice calls
  ui/       Home, live meter, demo picker, setup, privacy
backend/    Phase 1: FastAPI service that turns onboarding answers into a profile with Claude Haiku (tests/, contracts/)
proxy/      Phase 2: the judge endpoint (Vercel function + local server), prompt and output schema
eval/       dev set, held-out test set, audio subset, run_eval.mjs, results.md
shared/     test vectors that both the Kotlin and the JavaScript must pass
demo/       scripts and generator for the demo recordings (the WAVs live in app/src/main/assets/demo)
docs/       profile-contract.md, threat-model.md, privacy.md, limits.md, slides-inputs.md
```

## Credits

| What | Used for | Licence / terms |
|---|---|---|
| Anthropic Claude Haiku 5.5 and Sonnet 5.5 (API) | The call judge | Commercial API |
| Anthropic Claude Haiku 5.5 (API) | Phase 1 profile building | Commercial API |
| FastAPI, Pydantic, httpx, uvicorn | Phase 1 backend | MIT / BSD |
| [Vosk](https://alphacephei.com/vosk/) and `vosk-model-small-en-us-0.15` | On-device speech-to-text | Apache-2.0 |
| JNA | Required by Vosk | Apache-2.0 / LGPL |
| Jetpack Compose, AndroidX, Kotlin coroutines | App | Apache-2.0 |
| `@anthropic-ai/sdk` | Proxy | MIT |
| Microsoft Edge neural voices via `edge-tts` | Synthetic voices for demo and test audio | Synthetic speech; no real person's voice |
| ffmpeg | Audio conversion | LGPL/GPL (build tool only) |
| Claude (via Claude Code) | Wrote code, the synthetic call scripts and these documents with the team | |

All test and demo data is synthetic. No real calls, real people, real phone numbers or real identity documents were used.
