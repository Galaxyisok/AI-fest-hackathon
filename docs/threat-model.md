# Threat model

**Threat:** scam phone calls (vishing) that impersonate an authority to take over a person's identity or money. **Who:** people in India who answer calls from unknown numbers; the profile from Phase 1 narrows this to the scripts that work on this particular person. **Stage:** *during* the attack, while the victim is still on the call.

| Field | Answer |
|---|---|
| **Attacker** | Organised call-centre fraud teams working from a script, often with a "courier agent" who hands over to a "police officer". Sometimes a lone caller posing as a relative, recruiter or bank employee. |
| **Target** | Anyone who picks up. Most exposed: students living away from home, parents with a child in another city, seniors with savings, first-time bank and UPI users. They are vulnerable because the caller sounds official, knows a detail or two about them, and leaves no time to think or check. |
| **Goal** | Money moved to the attacker's account; OTPs, PINs and card or Aadhaar numbers that unlock accounts; remote control of the phone; a SIM swap that redirects the victim's OTPs. Each of these is the victim's identity being used without them. |
| **Method** | 1. **Hook**: a call from an unknown number with a believable reason (parcel seized, KYC expired, job offer, relative in trouble, power to be cut). 2. **Pressure**: arrest, a blocked account, a deadline tonight. 3. **Isolation**: stay on the line, tell no one, move to a video call. 4. **Extraction**: transfer to a "safe account", read out the OTP, install a screen-sharing app, approve a UPI request. |
| **Signals** | What the AI looks for is the *shape* of the conversation, not words: an authority the person cannot verify; pressure or threats; demands for secrecy; a request for money, codes or device access; how far along the script the call is; and whether it matches a script this person is exposed to. Plus two weak signals known before answering: an international or hidden number, and a caller ID the network could not verify. |
| **Our defence** | We step in at stages 2 to 4, live. The phone turns the call into text on-device, strips numbers, and an AI judge scores it every few seconds. The user sees a percentage, one sentence of why, and one action: carry on, be careful, or hang up and call back on the official number, with 1930 and Sanchar Saathi one tap away. A warning also arrives as a notification, because the user is looking at the call screen, not at our app. |

## Why rules could not do this

The same words appear on both sides. A real bank says "KYC", "blocked" and "OTP" ("we will never ask for your OTP"). A real police constable phones about passport verification. A real collections agent threatens a credit-score hit. As a sanity check, the crude eight-keyword stand-in we use for wiring tests (not a serious rule system) raised a red alarm on 0 of 10 scams in the development set and showed "be careful" on a genuine KYC reminder. What separates the calls is intent across several turns: is the caller steering the person to act *now, on this call*, in a way that hands over money, codes or control? That is a language-understanding question, and it has to survive speech-recognition errors ("your gay y c has expired"). Remove the AI and the app has nothing left to decide with.

## What an attacker could try against us

See `privacy.md` → "How an attacker could not abuse this tool".
