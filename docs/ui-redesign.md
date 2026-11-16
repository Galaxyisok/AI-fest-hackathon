# UI refresh — 2026-11-16

Approved direction: iOS-inspired polish with less darkness than the concept. Pearl backgrounds, mint and ocean accents, editorial headings, a shield/audio identity, rounded layered surfaces and consistent navigation. Android remains native Jetpack Compose.

Applied across Home, live call, demo picker, setup, privacy, onboarding questions/results, sample profiles and learning. Home/Learn/Profile share a floating dock. Live calls keep their own focused controls. Developer endpoint settings are collapsed under an explicit team-settings control.

Motion: page fade/short slide, once-per-visit card reveal on entering the viewport, subtle scroll-responsive title movement, spring primary-button presses, animated learning expansion, risk arc and colour transitions, and an animated wave while listening. The waveform is a listening indicator, not a measured audio waveform. Device animation scale is respected by Compose animations.

Verification: debug and signed release APK builds passed; all 26 Kotlin tests passed. Installed the debug APK on the Android 15 emulator and inspected Home, Learn, profile/consent, question chips, Setup and live meter. Exercised navigation, scrolling, onboarding consent and stop listening. The live-meter check used the existing mock judge (no AI charges); it does not constitute a new accuracy evaluation. Found and fixed the consent-to-questions scroll position carrying over by resetting the page when the onboarding step changes.

Evidence is saved beside the repository in `../shots/redesign-*.png` and `../shots/redesign-motion-v2.mp4`. Release APK is built to the configured external Gradle build directory. Physical phone operation and acoustic call capture remain unverified.

Build note: Windows briefly locked the release dex file during the incremental rebuild. Stopping the Gradle daemon and building with `--no-daemon --max-workers=1` completed successfully.
