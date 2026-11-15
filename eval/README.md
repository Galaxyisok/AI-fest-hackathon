# Evaluation

How we know what the detector catches and what it wrongly flags.

## The data

| Set | File | Size | Used for |
|---|---|---|---|
| Development | `dev/dev.jsonl` | 20 calls (10 scam, 10 genuine) | Tuning the prompt and thresholds. Never reported. |
| **Held-out test** | `testset/test.jsonl` | **52 calls (26 scam, 26 genuine)** | Reported in `results.md`. **Never tuned on.** |
| Audio subset | `audio/audio_subset.json` → `audio/audio_stt.jsonl` | 10 of the test calls (5 + 5) | Spoken by TTS, degraded to telephone quality, run through the app's own speech model. Measures what speech recognition costs. |

Scam calls cover all eight profile types plus four that fit none of them (lottery, tech support, "wrong transfer" refund, investment tips). Genuine calls are chosen to be hard: a bank's fraud team, a blocked-card notice, a KYC reminder that threatens a freeze, overdue-loan collections, real customs, real police verification, a traffic fine, a relative asking for money, a hospital emergency desk.

**Where the data came from, honestly.** Every call is synthetic and was written for this project with an AI model (Claude) from public descriptions of how these scams run. **A human teammate should read all 52 labels before the numbers go on a slide; that review has not happened yet.** No real call, real person or real number is in it. The test set was written after the prompt was drafted, in a separate pass from the development set, using different scenarios. Two limits follow and should be said on the slide: the text is cleaner and more "textbook" than real calls, and it was written by a model from the same family as the judge, which probably flatters the scores. The audio subset is the partial answer to the first; real recorded calls (with consent) would be the answer to both.

## How a call is replayed

`run_eval.mjs` feeds each call to the judge one turn at a time, the way the app hears it: lower case, no punctuation, no speaker labels, numbers redacted. After each turn the verdict goes through the same score maths as the app (`lib/fusion.mjs` and the redactor are held to the same test vectors as the Kotlin; `lib/cascade.mjs` is a line-for-line mirror of `CascadePolicy.kt`). So the result is what the meter would have shown at each moment, not a single after-the-fact label.

- **Alarm** = the meter reached red (60% or more) at any point. A genuine call that touches red even once counts as a false alarm.
- **Time to alarm** = how far into the call the first red was, in turns, as a share of the call, and in seconds at 2.5 words per second.
- One fixed profile (the student sample) is used for every call, so differences between runs are differences between models.

Three modes: `haiku` (fast model only), `sonnet` (deep model only), `cascade` (what ships: fast on every turn, deep when a call looks risky).

## Run it

```bash
node proxy/local.mjs                                      # terminal 1, needs ANTHROPIC_API_KEY (see proxy/README.md)

node eval/run_eval.mjs --set test --mode cascade --dry-run   # cost estimate, no API calls
node eval/run_eval.mjs --set dev  --mode cascade             # tuning runs: printed, not written to results.md
node eval/run_eval.mjs --set test --mode haiku               # appended to results.md
node eval/run_eval.mjs --set test --mode sonnet
node eval/run_eval.mjs --set test --mode cascade
node eval/run_eval.mjs --stt eval/audio/audio_stt.jsonl --mode cascade
```

Cost preflight for the full test set (272 checkpoints), assuming no caching and every checkpoint escalating: Haiku-only about $0.11, Sonnet-only about $4.20, cascade at most about $4.30. Expect less.

Rebuild the audio subset: `python demo/make_audio.py eval/audio/audio_subset.json eval/audio --phone` then `python eval/stt_transcribe.py eval/audio/audio_subset.json eval/audio eval/audio/audio_stt.jsonl`.

Unit tests for the shared maths: `node --test eval/lib/lib.test.mjs`.
