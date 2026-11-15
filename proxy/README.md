# Judge proxy

A single stateless endpoint, `POST /api/judge`. It exists so the Anthropic API key never ships inside the APK. It takes a redacted transcript and returns a verdict. It stores nothing and logs nothing.

## Run locally (development, eval, emulator)

```bash
cd proxy
npm install
echo ANTHROPIC_API_KEY=sk-ant-... > .env      # .env is git-ignored
node local.mjs                                 # http://localhost:8787/api/judge
```

The Android emulator reaches this as `http://10.0.2.2:8787/api/judge`, which is the app's built-in default.

`JUDGE_BACKEND=mock node local.mjs` starts it with no key and a fake judge. That is for testing the plumbing only; no reported number comes from it.

## Deploy (Vercel)

```bash
cd proxy
npx vercel link                                         # once, creates the project
npx vercel env add ANTHROPIC_API_KEY production         # paste the key when asked; it is never written to the repo
npx vercel deploy --prod
curl -s -X POST https://<your-app>.vercel.app/api/judge -H "Content-Type: application/json" \
  -d '{"tier":"fast","transcript":"hello this is the courier office calling about a parcel for you are you at home today"}'
```

Then build the app against it: `./gradlew assembleRelease -PjudgeUrl=https://<your-app>.vercel.app/api/judge` (or paste the address into Setup inside the app).

## Request and response

```jsonc
// request: anything not listed here is ignored
{ "tier": "fast" | "deep",
  "transcript": "redacted text, newest 8000 characters are used",
  "profile": { "userType": "student", "threats": [ { "type": "kyc_bank", "level": "high" } ] },
  "prior": { "international": false, "unverified": false, "hidden": false } }

// response
{ "signals": { "authority": { "evidence": "...", "score": 0-3 }, "urgency_threat": {...}, "secrecy_isolation": {...},
               "payment_request": {...}, "credential_or_app_request": {...}, "script_stage": {...}, "profile_match": {...} },
  "matched_threat": "kyc_bank", "confidence": "low|medium|high", "scam_probability": 0-100,
  "reason": "one plain sentence", "tier": "fast", "model": "claude-haiku-5-5" }

// or, when the model gave no verdict (declined or cut off); the app keeps its last score
{ "unavailable": "refused" | "truncated" | "empty", "tier": "deep", "model": "..." }
```

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `ANTHROPIC_API_KEY` | none | Required |
| `JUDGE_FAST_MODEL` | `claude-haiku-5-5` | Model for `tier: fast` (every new stretch of speech) |
| `JUDGE_DEEP_MODEL` | `claude-sonnet-5-5` | Model for `tier: deep` (whole-call re-check once a call looks risky) |
| `JUDGE_FAST_EFFORT` / `JUDGE_DEEP_EFFORT` | `low` | Reasoning effort |
| `JUDGE_FAST_THINKING` | `off` | `off` disables thinking on the fast tier for speed |
| `JUDGE_RATE_PER_MIN` | `40` | Requests per client per minute |

## Design notes

- **Structured outputs.** The response shape is enforced with `output_config.format` (JSON schema), so the app never parses free text. Sonnet 5.5 no longer accepts a forced tool call, which is the older way to do this.
- **Prompt caching.** The system prompt is marked cacheable; repeated checks during one call re-read it at a fraction of the price.
- **Refusals.** If a safety classifier declines a transcript (`stop_reason: "refusal"`), the proxy answers `unavailable` and the app keeps the last score. We did not enable Anthropic's server-side model fallback: the cascade already means no single call is load-bearing.
- **Rate limiting is per warm instance and in memory.** It slows down someone hammering the endpoint; it is not a hard wall. A real deployment should add a platform firewall rule.
- **Second redaction pass.** The phone removes numbers before sending. The proxy removes them again, in case a modified client did not.

Tests: `npm test`.
