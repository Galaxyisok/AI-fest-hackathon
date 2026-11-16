# Phase 1: profile service

The Python API behind the app's onboarding. It takes tap-only answers, asks Claude Haiku which
scam calls this person is most exposed to, and returns the profile the call guard uses.
It has no database and stores nothing. The screens that call it are in
`app/src/main/java/com/scamguard/phase1/`; the shared profile format is `docs/profile-contract.md`.

Quickest way to run it together with the call judge: `bash scripts/dev-servers.sh` from the
repository root (one `.env` with the key serves both). The rest of this file is the detail.

## Run

Python 3.12 or later, from the repository root:

```powershell
python -m venv .venv
.venv/Scripts/python -m pip install -r backend/requirements.lock
# Set ANTHROPIC_API_KEY in the server environment using your secret manager.
.venv/Scripts/python -m uvicorn backend.app:app --host 127.0.0.1 --port 8000 --no-access-log --workers 1 --no-proxy-headers
```

`.env.example` lists configuration; the service does not automatically load `.env`.
Docker: `docker build -t phase1 .`, then
`docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges -p 127.0.0.1:8000:8000 --env-file .env phase1`.
Put the service behind HTTPS for Android. Do not expose the development HTTP port publicly.
Use one worker: rate and concurrency limits are global per process. This MVP has no
user accounts; a public deployment needs gateway quotas/abuse controls. Do not embed
a shared proxy secret or an Anthropic key in an APK. Disable request/body logging,
tracing, crash payload capture and caching at the gateway too.

## API

`POST /v1/onboarding/profile`, `Content-Type: application/json`:

```json
{
  "consent": true,
  "answers": {
    "userType": "student",
    "ageBand": "18_29",
    "employment": "seeking_work",
    "digitalConfidence": "medium",
    "activities": ["job_search", "online_shopping"],
    "concerns": ["job_offer"]
  }
}
```

Only `consent: true` and `answers.userType` are required. Optional fields and exact
choices are in `/docs` and `contracts/openapi.json`. Unknown fields, free text,
duplicate selections and missing consent are rejected. Do not collect passwords,
OTPs, card numbers, ID scans, names, phone numbers, exact birthdays or addresses.
Use age bands and coarse selections; optional answers can be omitted.

Success is the profile itself, with no wrapper or metadata:

```json
{
  "version": 1,
  "userType": "student",
  "threats": [
    {"type": "job_offer", "level": "medium", "why": "Job searching can expose you to fake recruitment offers."}
  ]
}
```

This example is illustrative, never a fallback response. Haiku performs every real
assessment. All eight threat codes are fixed; levels are `low`, `medium`, `high`.
There are one to eight unique threats. `why` is one short English line, max 160
characters. The server validates output, user type consistency, and obvious
identifier patterns before returning it. Invalid output, refusal, truncation and
provider errors fail closed; no profile is saved or manufactured.

Errors: `{"error":{"code":"analysis_unavailable","message":"..."}}`.
HTTP 408 = slow upload; 413 = over 8 KiB; 415 = media/encoding unsupported;
422 = invalid selections/consent; 429 = capacity/quota (Retry-After: 60);
502 = AI failure; 503 = missing server configuration. No input is echoed.
`GET /healthz` checks the process; `/readyz` checks configuration, not live AI credentials.

## Privacy and AI

Only bounded categorical answers enter the service. They exist in request memory
while Haiku analyzes them; there is no database, queue, disk cache, conversation
history or body logger. The server also does not persist the derived profile.
Only Android's shared `ProfileRepository.save(profile)` should persist that result.
Python/Android cannot guarantee immediate cryptographic erasure of immutable memory
or OS-managed swap; this implementation releases request references and never
intentionally writes answers to storage. No telemetry SDK is configured.

Answers are sent to Anthropic. Deployment must verify the Anthropic account's
data-retention settings/zero-data-retention eligibility before claiming that no
provider retains submitted data. Consent must disclose this external AI processing.
No application can promise provider-side deletion merely by avoiding local storage.
Avoid debug HTTP logging and infrastructure snapshots of requests. Free-text reasons
are AI-generated; identifier-pattern rejection is defense in depth, not a perfect
PII detector. Since identity fields never enter the model, reasons should describe
only coarse circumstances, and the system prompt explicitly forbids invented identities.

Uses Haiku `claude-haiku-5-5` (set `CLAUDE_MODEL` to change it; thinking is switched off and
effort set low for a quick answer, and a Haiku 4.5 model ID is sent neither), server-only key, a 35-second total provider
deadline, bounded output, no automatic retry, and structured JSON via Anthropic's
[structured output API](https://platform.claude.com/docs/en/build-with-claude/structured-outputs).
There are no age-to-threat rules, scores or static threat fallbacks.

## Android handoff and verification

[integration/ANDROID.md](../integration/ANDROID.md) is the original hand-off note: the rules the
onboarding screen follows (answers never saved, cleared on send, save before showing the result).
The Kotlin client now lives in the app at `app/src/main/java/com/scamguard/phase1/OnboardingClient.kt`.

Run `.venv/Scripts/python -m pytest -q`. Tests use a mocked provider and exercise the
real HTTP adapter, validation, failure handling, concurrency and privacy-safe errors.
They do not establish live model quality. Live testing requires a server-side key
and the eventual HTTPS endpoint. `python -m backend.smoke` runs an opt-in, paid
provider check with synthetic inputs after setting the server key, and prints the profile it got
so its relevance can be read. Use synthetic selections first, check profile
schema and relevance, then verify the actual app's save/load round trip before
integration rehearsal. No real-user information belongs in fixtures.
