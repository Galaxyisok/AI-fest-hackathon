# Inputs for the slides (4–5 slides)

Everything here is either implemented in this branch or marked as pending. The numbers are from `eval/results.md` (run 2026-11-16). Present them with the caveat on slide 4: the test calls are synthetic.

## Slide 1 — The problem, the user, the stage

- **Threat:** scam calls that impersonate police, banks, couriers, employers or relatives to take money, OTPs or control of the phone.
- **User:** people who answer unknown numbers; personalised by a scam profile (student, parent, senior, professional).
- **Stage: DURING the attack.** (Phase 1 covers *before*: tap-only questions, Claude Haiku works out which scams you are exposed to, and the app teaches those first, with practice calls. Core for judging: *during*.)
- One line: *"A second pair of ears on the call, which tells you the odds while you can still hang up."*
- Demo opens with: the fake-police parcel call.

## Slide 2 — Threat model

Copy the table from [`threat-model.md`](threat-model.md). The demo follows it: hook → pressure → isolation → extraction, and the meter climbs through those stages.

## Slide 3 — Where the AI is, and why this design

- **Pipeline:** on-device speech-to-text (Vosk) → on-device redaction → Claude Haiku 5.5 on every new stretch of speech → Claude Sonnet 5.5 re-reads the whole call once it looks risky → score maths → percentage + reason + action.
- **Why two models (the "is Sonnet overkill?" answer):** Haiku answers fast and costs about 20 times less per token, so it can run every few seconds. Sonnet is slower and dearer but better at reading a long, subtle conversation, so it is only called when Haiku crosses 35%, and again every 30 seconds while the call stays risky. A normal call never pays for Sonnet. We measured all three options on the same held-out set:

  | Mode | Scams caught | False alarms | Response | Cost of 52 calls |
  |---|---:|---:|---|---:|
  | Haiku only | 26/26 | 2/26 | 2.1 s | $0.06 |
  | **Cascade (shipped)** | 26/26 | 1/26 | 2.1 s, 3.2 s when escalated | $0.35 |
  | Sonnet only | 26/26 | 0/26 | 3.2 s | $1.09 |

  Honest reading: Sonnet alone was the most accurate. The cascade is the middle choice: half of Haiku's false alarms for a third of Sonnet's cost, and it keeps the fast first reaction. So "is Sonnet overkill?" has a measured answer: it is not wasted, and it is not needed on every sentence.
- **Why not rules:** the same words are in genuine calls ("KYC", "blocked", "OTP", "police"). What differs is what the caller steers you to do across several turns, through speech-recognition errors. See "Why rules could not do this" in the threat model.
- **What the model returns:** seven scored signals with quoted evidence, a probability, a confidence, and a plain sentence. Enforced by a JSON schema, so the app never parses free text.
- **Says when it is unsure:** under about 25 meaningful words it reports low confidence and the app shows "Listening…", not a guess.
- **Personalisation:** if the call matches a scam type the user's profile rates high, the score is nudged up. The profile cannot create a warning by itself.

## Slide 4 — Does it work? (Effectiveness)

- Held-out test set: **52 synthetic calls, 26 scam and 26 genuine**, never used for tuning; a separate 20-call set was used for tuning.
- Genuine calls are deliberately hard: a bank fraud team, a KYC reminder that threatens a freeze, loan collections, real customs, real police verification.
- Replayed turn by turn, so we report *when* the alarm came, not just whether.
- **Result (cascade): 26 of 26 scams caught; 1 of 26 genuine calls wrongly alarmed** (a sibling asking to borrow money). Alarm came at a median of turn 3, about 20 seconds into the call.
- 10 of 26 genuine calls were shown amber "be careful": the ones that genuinely sound alarming (blocked card, KYC freeze, real customs, real police).
- Through telephone-quality audio and the phone's own speech model (10 calls): 5 of 5 scams caught, 0 of 5 false alarms; genuine calls scored up to 10 points higher than from clean text.
- Speech recognition alone: about 13% word error on clean synthetic Indian-English voices (measured).
- Risk levels, not yes/no; a reason with every score; a "This was wrong" button.
- **Say this out loud:** the data is synthetic and AI-written, so real-world numbers will be worse.

## Slide 5 — Privacy, misuse, and what it cannot do

- **Audio never leaves the phone.** Speech-to-text runs on-device. Nothing is recorded.
- **Numbers never leave the phone.** OTPs, card, Aadhaar and phone numbers are replaced by `[6-DIGIT NUMBER]` before anything is sent, and again at the server.
- **Kept on the phone:** the scam profile and a count of "this was wrong" taps. That is all. No account, no analytics.
- **Never asks** for a password, OTP or PIN, and warns when a code is said aloud.
- **Misuse:** scores rounded to 5% and smoothed so a script cannot be tuned against them; rate-limited proxy that accepts transcripts only; a warning cannot be talked back down quickly.
- **Cannot do yet:** hear a real call without an accessibility switch (and unproven on hardware); any language but English; tell a cloned voice from a real one; work offline. Full list: [`limits.md`](limits.md).

## Judging criteria → where to point

| Criterion | Evidence |
|---|---|
| Technical 25 | Two-model cascade with a measured comparison; enforced output schema; on-device STT; error states handled (offline, muted mic, declined verdict); 16 unit tests in the app plus 34 on the JavaScript side; Kotlin and JavaScript held to the same vectors |
| Effectiveness 25 | `eval/results.md`: 26/26 caught, 1/26 false alarm on a 52-call held-out set with hard negatives; time-to-alarm; audio subset; stated caveats |
| Innovation 20 | Live, mid-call, percentage that moves as the script unfolds; personal profile from Phase 1 feeding the detector; spoken-digit redaction including misheard digits |
| Privacy 15 | `privacy.md`; data-flow table; what is not requested |
| Before-the-call value | Personal profile by AI from tap-only answers; "Know the scams" training ordered by that profile; practice calls that run through the real detector |
| Usability 15 | One number, one sentence, one action; large text; setup explains each permission before asking; demo an ordinary person can follow |

## Consent and credits line for the last slide

All calls are synthetic: scripted by the team and spoken by Microsoft Edge text-to-speech voices. No real person's voice or data was used. Models: Anthropic Claude Haiku 5.5 and Sonnet 5.5; Vosk small English model (Apache-2.0). Built with Claude Code. Full list in the README.
