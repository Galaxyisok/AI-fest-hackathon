# Results

**Status: run on 2026-11-16 against the live models.** Summary first; the full table for each run is further down, exactly as the script wrote it.

## Summary: held-out test set (52 synthetic calls: 26 scam, 26 genuine; never used for tuning)

"Alarm" means the meter reached red (60% or more) at any point in the call.

| Mode | Scams caught | False alarms | Genuine calls shown "be careful" or worse | Median time to alarm | Median response | Cost of the whole run |
|---|---:|---:|---:|---|---|---:|
| Haiku 5.5 only | 26 / 26 | 2 / 26 (8%) | 11 / 26 | turn 3, about 20 s in | 2.1 s | $0.06 |
| **Cascade (what ships)** | **26 / 26** | **1 / 26 (4%)** | 10 / 26 | turn 3, about 20 s in | 2.1 s fast, 3.2 s deep | $0.35 |
| Sonnet 5.5 only | 26 / 26 | 0 / 26 | 6 / 26 | turn 3, about 20 s in | 3.2 s | $1.09 |

- **The one cascade false alarm** is `t-g10`: a sibling phoning from a room-mate's phone to borrow 2,000 rupees. It peaked at exactly 60%. Haiku alone also alarmed on `t-g25` (a landlord asking for rent to go to his wife's account). Both look like the "relative in trouble" script, and Sonnet alone left both at amber. We did not change the prompt after seeing this: the test set is not to be tuned on.
- **What the three modes say about the design.** Every mode caught every scam, so on this set the choice is about false alarms, speed and cost. Sonnet alone was the most accurate, at about 20 times the cost of Haiku alone and a second slower per check. The cascade sits between: it removes one of Haiku's two false alarms for about a third of Sonnet's cost, and a normal call never pays for Sonnet. If the budget allows, Sonnet-only is the more accurate setting. It is not a configuration switch today: the fast tier's request turns thinking off, which Sonnet does not accept, so it needs a small code change.
- **Amber is common on genuine calls** that really do sound alarming (a blocked card, a KYC freeze warning, real customs, real police verification). Amber only says "do not share a code, do not install an app", which is correct advice on those calls too, but it is a cost in user trust and should be said.
- **No tuning was needed on the development set** (10 of 10 scams caught, 0 false alarms, one genuine call at amber), so the prompt that produced these numbers is the first draft.

## Summary: the same detector on speech-recognised audio (10 of the test calls)

Ten test calls (5 scam, 5 genuine) were spoken by text-to-speech voices, degraded to telephone quality with noise, and transcribed by the app's own on-device speech model before judging (cascade mode).

| | Clean text | Speech-recognised text (13% word error) |
|---|---:|---:|
| Scams caught | 5 / 5 | 5 / 5 |
| False alarms | 0 / 5 | 0 / 5 |
| Highest score on a genuine call | 45% | 55% |

Scam scores stayed at 90 to 95%. Genuine calls drifted up by up to 10 points, so recognition errors push towards false alarms rather than misses.

## Summary: the four demo calls, in the app, on an Android 15 emulator

Recording → on-device speech-to-text → redaction → live judge → meter, sampled every 12 seconds.

| Demo call | Result | Meter |
|---|---|---|
| Bank call about a card payment (genuine) | Stayed green | peak 15% |
| "Bank" call about KYC | Red | 65% at 24 s, 95% by 48 s, ended at 100% |
| Work-from-home job offer | Red | 35% at 24 s, 65% at 36 s, 95% by 60 s |
| "Police" call about a parcel | Red | 80% at 24 s, 95% by 60 s, ended at 100% |

(The meter has since been capped at 95%, so those two now end at 95%.)

## How far to trust these numbers

- **100% of scams caught is a statement about this test set, not about real calls.** All 52 calls are synthetic, written with an AI model from the same family as the judge, in clean English with clear scripts. Real scam calls are messier, longer, mostly not in English, and some are written to evade detection. Expect real recall to be lower.
- 26 scams is a small sample. With 26 of 26 caught, the true miss rate could still plausibly be around one in ten.
- The labels were not independently checked by a second person.
- Audio was synthetic speech, not people on a real phone line on speaker in a room.
- The meter now stops at 95% rather than showing 100%; that display change was made after these runs and does not affect any count above.

## Speech recognition (measured, no AI judge involved)

The app's on-device speech model (Vosk small en-US 0.15), run on a laptop over synthetic text-to-speech audio with Indian-English voices:

| Audio | Clips | Mean word error rate |
|---|---:|---:|
| Demo calls, clean | 4 | 13% |
| Test subset (10 held-out calls), telephone band + noise | 10 | 13% (range 5% to 21%; per clip in `audio/audio_stt.jsonl`) |

Caveat: text-to-speech is clearer than a real person on a real line, and a phone on speaker in a room is worse than either. Real-call error rates will be higher. Whether the judge still works on that text is what the `--stt` run measures.

---

# Full output of each run

## test set, mode: haiku (2026-11-16 04:39 UTC)

26 scam calls, 26 genuine calls. "Alarm" = the meter reached red (>= 60%) at any point in the call.

|                    | Alarm raised | No alarm |
|--------------------|-------------:|---------:|
| **Scam call**      | 26 caught | 0 missed |
| **Genuine call**   | 2 false alarm | 24 left alone |

| Measure | Value |
|---|---|
| Scams caught (recall) | 26/26 = 100% |
| Alarms that were right (precision) | 26/28 = 93% |
| Genuine calls wrongly alarmed (false-alarm rate) | 2/26 = 8% |
| Scams that got at least a "be careful" (amber or red) | 26/26 = 100% |
| Genuine calls that got a "be careful" or worse | 11/26 = 42% |
| Median point of the alarm | turn 3, 53% of the way through the call, about 20 s in |
| Judge calls | claude-haiku-5-5 x272 |
| Median judge response time | fast 2107 ms, deep n/a ms |
| Verdicts unavailable (declined or cut off) | 0 |
| API cost of this run | $0.056 |

Missed scams: none
False alarms: t-g10 (family, peak 60%); t-g25 (landlord, peak 60%)
Genuine calls shown amber: t-g01 (bank_transaction_check, peak 30%); t-g02 (bank_block_notice, peak 50%); t-g03 (kyc_reminder, peak 35%); t-g04 (loan_reminder, peak 30%); t-g07 (customs_real, peak 50%); t-g14 (sales_call, peak 30%); t-g18 (utility_real, peak 45%); t-g19 (police_verification, peak 45%); t-g20 (police_verification, peak 45%)

## test set, mode: cascade (2026-11-16 04:43 UTC)

26 scam calls, 26 genuine calls. "Alarm" = the meter reached red (>= 60%) at any point in the call.

|                    | Alarm raised | No alarm |
|--------------------|-------------:|---------:|
| **Scam call**      | 26 caught | 0 missed |
| **Genuine call**   | 1 false alarm | 25 left alone |

| Measure | Value |
|---|---|
| Scams caught (recall) | 26/26 = 100% |
| Alarms that were right (precision) | 26/27 = 96% |
| Genuine calls wrongly alarmed (false-alarm rate) | 1/26 = 4% |
| Scams that got at least a "be careful" (amber or red) | 26/26 = 100% |
| Genuine calls that got a "be careful" or worse | 10/26 = 38% |
| Median point of the alarm | turn 3, 56% of the way through the call, about 20 s in |
| Judge calls | claude-haiku-5-5 x272, claude-sonnet-5-5 x71 |
| Median judge response time | fast 2125 ms, deep 3182 ms |
| Verdicts unavailable (declined or cut off) | 0 |
| API cost of this run | $0.346 |

Missed scams: none
False alarms: t-g10 (family, peak 60%)
Genuine calls shown amber: t-g01 (bank_transaction_check, peak 30%); t-g02 (bank_block_notice, peak 45%); t-g03 (kyc_reminder, peak 35%); t-g04 (loan_reminder, peak 35%); t-g07 (customs_real, peak 45%); t-g18 (utility_real, peak 45%); t-g19 (police_verification, peak 45%); t-g22 (college, peak 30%); t-g25 (landlord, peak 35%)

## test set, mode: sonnet (2026-11-16 04:46 UTC)

26 scam calls, 26 genuine calls. "Alarm" = the meter reached red (>= 60%) at any point in the call.

|                    | Alarm raised | No alarm |
|--------------------|-------------:|---------:|
| **Scam call**      | 26 caught | 0 missed |
| **Genuine call**   | 0 false alarm | 26 left alone |

| Measure | Value |
|---|---|
| Scams caught (recall) | 26/26 = 100% |
| Alarms that were right (precision) | 26/26 = 100% |
| Genuine calls wrongly alarmed (false-alarm rate) | 0/26 = 0% |
| Scams that got at least a "be careful" (amber or red) | 26/26 = 100% |
| Genuine calls that got a "be careful" or worse | 6/26 = 23% |
| Median point of the alarm | turn 3, 54% of the way through the call, about 20 s in |
| Judge calls | claude-sonnet-5-5 x272 |
| Median judge response time | fast n/a ms, deep 3162 ms |
| Verdicts unavailable (declined or cut off) | 0 |
| API cost of this run | $1.092 |

Missed scams: none
False alarms: none
Genuine calls shown amber: t-g02 (bank_block_notice, peak 30%); t-g03 (kyc_reminder, peak 40%); t-g07 (customs_real, peak 40%); t-g10 (family, peak 55%); t-g18 (utility_real, peak 50%); t-g19 (police_verification, peak 35%)

## audio set, mode: cascade (2026-11-16 04:48 UTC)

5 scam calls, 5 genuine calls. "Alarm" = the meter reached red (>= 60%) at any point in the call.

|                    | Alarm raised | No alarm |
|--------------------|-------------:|---------:|
| **Scam call**      | 5 caught | 0 missed |
| **Genuine call**   | 0 false alarm | 5 left alone |

| Measure | Value |
|---|---|
| Scams caught (recall) | 5/5 = 100% |
| Alarms that were right (precision) | 5/5 = 100% |
| Genuine calls wrongly alarmed (false-alarm rate) | 0/5 = 0% |
| Scams that got at least a "be careful" (amber or red) | 5/5 = 100% |
| Genuine calls that got a "be careful" or worse | 4/5 = 80% |
| Median point of the alarm | turn 5, 53% of the way through the call, about 20 s in |
| Judge calls | claude-haiku-5-5 x100, claude-sonnet-5-5 x21 |
| Median judge response time | fast 2287 ms, deep 3153 ms |
| Verdicts unavailable (declined or cut off) | 0 |
| API cost of this run | $0.107 |

Missed scams: none
False alarms: none
Genuine calls shown amber: t-g03 (kyc_reminder, peak 45%); t-g04 (loan_reminder, peak 30%); t-g07 (customs_real, peak 45%); t-g19 (police_verification, peak 55%)

## test set, mode: cascade (2026-11-16 17:13 UTC)

26 scam calls, 26 genuine calls. "Alarm" = the meter reached red (>= 60%) at any point in the call.

|                    | Alarm raised | No alarm |
|--------------------|-------------:|---------:|
| **Scam call**      | 26 caught | 0 missed |
| **Genuine call**   | 1 false alarm | 25 left alone |

| Measure | Value |
|---|---|
| Scams caught (recall) | 26/26 = 100% |
| Alarms that were right (precision) | 26/27 = 96% |
| Genuine calls wrongly alarmed (false-alarm rate) | 1/26 = 4% |
| Scams that got at least a "be careful" (amber or red) | 26/26 = 100% |
| Genuine calls that got a "be careful" or worse | 10/26 = 38% |
| Median point of the alarm | turn 3, 56% of the way through the call, about 20 s in |
| Judge calls | claude-haiku-5-5 x272, claude-sonnet-5-5 x69 |
| Median judge response time | fast 2594 ms, deep 2908 ms |
| Verdicts unavailable (declined or cut off) | 0 |
| API cost of this run | $0.342 |

Missed scams: none
False alarms: t-g10 (family, peak 70%)
Genuine calls shown amber: t-g01 (bank_transaction_check, peak 30%); t-g02 (bank_block_notice, peak 45%); t-g03 (kyc_reminder, peak 35%); t-g04 (loan_reminder, peak 30%); t-g07 (customs_real, peak 45%); t-g18 (utility_real, peak 45%); t-g19 (police_verification, peak 45%); t-g20 (police_verification, peak 30%); t-g25 (landlord, peak 30%)

## audio set, mode: cascade (2026-11-16 17:34 UTC)

5 scam calls, 5 genuine calls. "Alarm" = the meter reached red (>= 60%) at any point in the call.

|                    | Alarm raised | No alarm |
|--------------------|-------------:|---------:|
| **Scam call**      | 5 caught | 0 missed |
| **Genuine call**   | 1 false alarm | 4 left alone |

| Measure | Value |
|---|---|
| Scams caught (recall) | 5/5 = 100% |
| Alarms that were right (precision) | 5/6 = 83% |
| Genuine calls wrongly alarmed (false-alarm rate) | 1/5 = 20% |
| Scams that got at least a "be careful" (amber or red) | 5/5 = 100% |
| Genuine calls that got a "be careful" or worse | 5/5 = 100% |
| Median point of the alarm | turn 3, 44% of the way through the call, about 20 s in |
| Judge calls | claude-haiku-5-5 x88, claude-sonnet-5-5 x26 |
| Median judge response time | fast 2635 ms, deep 3027 ms |
| Verdicts unavailable (declined or cut off) | 0 |
| API cost of this run | $0.143 |

Missed scams: none
False alarms: t-g19 (police_verification, peak 65%)
Genuine calls shown amber: t-g01 (bank_transaction_check, peak 35%); t-g03 (kyc_reminder, peak 45%); t-g04 (loan_reminder, peak 45%); t-g07 (customs_real, peak 40%)

## audio set, mode: cascade (2026-11-16 17:36 UTC)

5 scam calls, 5 genuine calls. "Alarm" = the meter reached red (>= 60%) at any point in the call.

|                    | Alarm raised | No alarm |
|--------------------|-------------:|---------:|
| **Scam call**      | 5 caught | 0 missed |
| **Genuine call**   | 1 false alarm | 4 left alone |

| Measure | Value |
|---|---|
| Scams caught (recall) | 5/5 = 100% |
| Alarms that were right (precision) | 5/6 = 83% |
| Genuine calls wrongly alarmed (false-alarm rate) | 1/5 = 20% |
| Scams that got at least a "be careful" (amber or red) | 5/5 = 100% |
| Genuine calls that got a "be careful" or worse | 5/5 = 100% |
| Median point of the alarm | turn 5, 43% of the way through the call, about 20 s in |
| Judge calls | claude-haiku-5-5 x100, claude-sonnet-5-5 x23 |
| Median judge response time | fast 2473 ms, deep 2840 ms |
| Verdicts unavailable (declined or cut off) | 0 |
| API cost of this run | $0.116 |

Missed scams: none
False alarms: t-g03 (kyc_reminder, peak 60%)
Genuine calls shown amber: t-g01 (bank_transaction_check, peak 30%); t-g04 (loan_reminder, peak 30%); t-g07 (customs_real, peak 45%); t-g19 (police_verification, peak 55%)

## Real-phone iteration (2026-11-16, app 0.4)

Two problems seen on real phones: the meter stayed on "Listening" for whole calls, and the transcript was poor.

**Meter.** Server logs showed 79 successful judge calls from two phones with no number ever shown. The app hid every "low confidence" verdict, and the judge says "low" for as long as a call has no clear purpose. Now, once 30 words have been heard and no confident verdict has arrived, the estimate is shown anyway, capped at 45 (below red), so an unsure guess can say "looks normal" or "be careful" but never "likely a scam". Re-run with this rule (same synthetic sets): dev 10/10 caught, 0/10 false alarms; held-out test 26/26 caught, 1/26 false alarms (t-g10, as before).

**Speech recognition.** Measured on 150 real Indian-English recordings (Svarah, AI4Bharat, human transcripts; about 20 minutes; `eval/stt_bench.py`). Word error rate:

| Audio | en-US small 0.15 (was shipped) | en-IN small 0.4 | en-IN + level control (now shipped) |
|---|---|---|---|
| Clean | 45.5% | 35.7% | n/a |
| Phone band + noise | 75.2% | 63.3% | 59.1% |
| Phone band, 20 dB quieter | 83.4% | 91.4% | 65.7% |

On the project's own synthetic audio: demo clips 13.6% -> 8.3%, held-out audio subset 13.3% -> 11.1%.
Svarah is hard (place names, spontaneous speech) and the phone conditions are simulated, not recorded from a handset; the absolute numbers are poor for both models. The judge is told to read through recognition errors, and a garbled real-phone transcript extended with scam content still scored 92.

**Detector on speech-recognised audio (10 held-out calls), run twice on the same day:** en-IN transcripts 5/5 caught, 1/5 false alarms (t-g19, peak 65); en-US transcripts 5/5 caught, 1/5 false alarms (t-g03, peak 60). The earlier single run reported 0/5. So the false-alarm count on this small audio set moves between 0 and 1 from run to run, on genuine official-sounding calls near the 60 line; it did not change with the speech model. Not tuned on.

Not measured: the level control on a real handset during a real call (unit-tested and benchmarked through a Python mirror only).

## Speech engine change (2026-11-17, app 0.5)

Report from two real phones: the owner's voice transcribes well, the caller's (through the loudspeaker) barely at all. Compared on-device engines on 60 real Indian-English recordings (Svarah; `eval/stt_bench_engines.py`), including a simulation of a caller heard through a loudspeaker (phone band, 26 dB quieter, room echo, noise, then the app's level control). Word error rate:

| Engine (all on-device, streaming) | Clean | Phone band + noise | Caller on speaker (simulated) |
|---|---|---|---|
| Vosk small en-IN 0.4 (was shipped) | 39.7% | 66.6% | 98.0% |
| **sherpa-onnx Zipformer "Kroko" 2025-08 (now shipped)** | **23.1%** | **28.0%** | **72.3%** |
| sherpa-onnx Zipformer 20M int8 | 61.9% | 82.4% | 99.0% |
| sherpa-onnx Zipformer 2023-06-26 int8 | 42.0% | 68.9% | 98.4% |
| NeMo fast-conformer CTC 480 ms int8 | 29.7% | 46.8% | 87.8% |

On the project's own synthetic audio with Kroko: demo clips 6% (was 8%), held-out audio subset 3% (was 11%).
The "caller on speaker" condition is a simulation built by us, not a recording from a handset, and is deliberately harsh; it ranks engines, it does not predict a real phone. 72% is still poor: a better recogniser cannot recover speech the phone's echo canceller has removed, which is why 0.5 also changes how audio is captured (raw microphone when the phone offers one, echo cancellation and noise suppression switched off for the recording, call volume raised while listening, and a microphone switch on the call screen). None of the capture changes has been measured on a handset.

The detector was NOT re-measured with the new engine: the API account ran out of credit during this work.
