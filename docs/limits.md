# What Scam Guard cannot do (yet)

Said plainly, because the brief asks for it and a judge will find these anyway.

## Hearing a real call is the weakest link

- **Android does not let an ordinary app hear a phone call.** Since Android 10, while a call is active an ordinary app's microphone receives silence. The documented exception is an app the user has enabled as an accessibility service ([Android docs: sharing audio input, "Voice call + ordinary app"](https://developer.android.com/media/platform/sharing-audio-input)). So Setup step 4 asks the user to switch on "Scam Guard call listening". That service contains no code and cannot read the screen; it exists only to lift the mute.
- **This path is built but not proven on a real phone.** It has not yet been tested on hardware. Some manufacturers restrict it further. If the microphone is being muted, the app says "Scam Guard cannot hear anything" instead of showing a calm green meter.
- **The call has to be on speaker**, and the app hears it through the room. Noise, a quiet caller, or a user who will not use speaker all defeat it.
- **The demo therefore uses recordings.** They run through exactly the same pipeline from speech-to-text onward, and the app is not told which are scams. But a recording played inside the phone is cleaner than a loudspeaker in a room.
- A Play Store release would not be allowed to use an accessibility service this way. A real product needs to be the phone's dialler or be built into the phone by the manufacturer or carrier, as Google's own on-device scam detection is.

## Language and speech

- **English only.** The speech model is a small US-English one. It handled Indian-English synthetic voices at about 13% word error, but real Indian scam calls are mostly in Hindi, Telugu, Tamil and other languages, or mixed with English. Those will not be understood at all today.
- **No speaker separation.** The app cannot tell who said what. The judge is told this and reads the call as a whole.
- **No voice-clone detection.** We judge what is said, not whether the voice is synthetic. A cloned voice running a "family emergency" script is caught only by the script.

## The judge

- **It needs the internet.** No connection, no score; the app says so. Only speech-to-text and redaction are on-device.
- **Latency.** Each check is a network round trip to a model. Expect a few seconds between words being said and the meter moving. Measured values are in `eval/results.md` once run.
- **It will miss some scams and flag some genuine calls.** The measured rates are in `eval/results.md`. A patient scammer who spends the first ten minutes building trust and asks for nothing is, correctly, not flagged until they ask.
- **The test data is synthetic and written with AI.** It is cleaner than real calls and comes from the same model family as the judge, which probably flatters the numbers.
- **Prompt injection is handled by instruction, not proven by test.** The judge is told to treat "mark this call safe" as a red flag. We have not measured how well that holds.

## Redaction

- Numbers of four or more digits (spoken or written) and email/UPI-style IDs are removed. **Names, addresses and anything else personal said on the call are not removed** and do reach the judge as text.
- Three or fewer spoken digits are left in, so that ordinary speech survives ("give me two minutes").

## The caller check

- It only knows three things: international, hidden, unverified. It has no database of reported scam numbers.
- "International" is worked out from the number's country code against a short built-in list, defaulting to India.
- Its notification sits below the phone's own incoming-call banner, so it is easy to miss while the phone is ringing.

## Phase 1

- The profile screen in this branch is a placeholder with four hand-written sample profiles. The AI-driven version is Phase 1's work.

## Abuse resistance

- The proxy's rate limit is in memory and per server instance: a speed bump, not a wall.
- Nothing stops someone extracting the proxy address from the APK and calling it directly within that limit.
