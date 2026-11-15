# Results

**Status: the held-out test has not been run yet.** It needs an Anthropic API key in the local proxy (see `proxy/README.md`). Until the tables below exist, no accuracy claim should be made for this app.

Each run of `node eval/run_eval.mjs --set test --mode <haiku|sonnet|cascade>` appends its table here, with the date. See `README.md` in this folder for what the numbers mean and how the data was made.

## Speech recognition (measured, no AI judge involved)

The app's on-device speech model (Vosk small en-US 0.15), run on a laptop over synthetic text-to-speech audio with Indian-English voices:

| Audio | Clips | Mean word error rate |
|---|---:|---:|
| Demo calls, clean | 4 | 13% |
| Test subset (10 held-out calls), telephone band + noise | 10 | 13% (range 5% to 21%; per clip in `audio/audio_stt.jsonl`) |

Caveat: text-to-speech is clearer than a real person on a real line, and a phone on speaker in a room is worse than either. Real-call error rates will be higher. Whether the judge still works on that text is what the `--stt` run measures.
