# DreamSync Publishing Status

## Prepared

- Public product name and publisher naming.
- Official slogan: Dreams Redefined.
- Official mission centered on automatic likely-sleep estimation, sleep continuity, and fail-closed cue safety.
- Broad capability-based Android 8.0+/Wear OS 3+ compatibility copy, with full-sensor features preserved on capable watches, Galaxy Watch Ultra first in physical validation, and Apple Watch clearly marked as planned.
- Version 1.3.8-test with unique phone and Wear OS version codes prepared for internal testing.
- Consumer interface with sleep-cue settings only.
- Health permission disclosure before the watch permission prompt.
- In-app privacy and wellness disclosures.
- Privacy policy source and deployable website.
- Store listing and Play declaration answers.
- Local export and deletion controls.
- Slogan feature graphic at `assets/dreamsync-feature-graphic-slogan-1024x500.png`.
- Current phone and tablet screenshots captured from version code 9.
- Live Google Play short and full descriptions saved with the official slogan and mission.

## Internal testing

- Phone version code 9 (`1.0.0`) published on 2026-07-19.
- Wear OS version code 7 (`1.0.0`) published on 2026-07-19.
- Phone version code 10 (`1.1.0-test`) published on 2026-07-19.
- Wear OS version code 8 (`1.1.0-test`) published on 2026-07-19.
- Phone version code 11 (`1.1.1-test`) published on 2026-07-19.
- Wear OS version code 12 (`1.1.1-test`) published on 2026-07-19.
- Phone version code 13 (`1.1.2-test`) published on 2026-07-19.
- Wear OS version code 14 (`1.1.2-test`) published on 2026-07-19.
- Phone version code 15 (`1.2.0-test`) published on 2026-07-19.
- Wear OS version code 16 (`1.2.0-test`) published on 2026-07-19.
- Phone version code 17 (`1.2.1-test`) published on 2026-07-19.
- Wear OS version code 18 (`1.2.1-test`) published on 2026-07-19.
- Phone version code 19 (`1.2.2-test`) published on 2026-07-19.
- Wear OS version code 20 (`1.2.2-test`) published on 2026-07-19.
- Phone version code 21 (`1.2.3-test`) published on 2026-07-20.
- Wear OS version code 22 (`1.2.3-test`) published on 2026-07-20.
- Phone version code 23 (`1.2.4-test`) published on 2026-07-20.
- Wear OS version code 24 (`1.2.4-test`) published on 2026-07-20.
- Phone version code 25 (`1.2.5-test`) published on 2026-07-20.
- Wear OS version code 26 (`1.2.5-test`) published on 2026-07-20.
- Phone version code 27 (`1.2.6-test`) published on 2026-07-20.
- Wear OS version code 28 (`1.2.6-test`) published on 2026-07-20.
- Phone version code 29 (`1.2.7-test`) published on 2026-07-20.
- Wear OS version code 30 (`1.2.7-test`) published on 2026-07-20.
- Phone version code 33 (`1.3.0-test`) published on 2026-07-20.
- Wear OS version code 34 (`1.3.0-test`) published on 2026-07-20.
- Phone version code 35 (`1.3.1-test`) published on 2026-07-20.
- Wear OS version code 36 (`1.3.1-test`) published on 2026-07-20.
- Phone version code 37 (`1.3.2-test`) published on 2026-07-20.
- Wear OS version code 38 (`1.3.2-test`) published on 2026-07-20.
- Phone version code 39 (`1.3.3-test`) published on 2026-07-20.
- Wear OS version code 40 (`1.3.3-test`) published on 2026-07-20.
- Phone version code 41 (`1.3.4-test`) published on 2026-07-20.
- Wear OS version code 42 (`1.3.4-test`) published on 2026-07-20.
- Phone version code 55 (`1.3.11-test`) published on 2026-07-20.
- Wear OS version code 56 (`1.3.11-test`) published on 2026-07-20.
- Phone version code 57 (`1.3.12-test`) published on 2026-07-20.
- Wear OS version code 58 (`1.3.12-test`) published on 2026-07-20.
- Phone tester link: `https://play.google.com/apps/internaltest/4701743177829341351`
- Wear OS tester link: `https://play.google.com/apps/internaltest/4700051853729851607`
- Both tracks use the `Dream Sync private testers` email list.

## Current internal-test build

- Phone version code 57 (`1.3.12-test`) is available to internal testers and its track is Active.
- Wear OS version code 58 (`1.3.12-test`) is available to internal testers. Play labels the Wear OS track Inactive, but the release itself is available.
- Version 1.3.12 pauses passive Health Services monitoring during the live sensor check, restores it afterward, keeps live heart acquisition active for the full test, and exports expanded callback diagnostics.
- Version 1.3.11 centralizes the research-based sleep-protection contract across phone and watch: conservative sound limits, fresh-sensor gating, automatic onset stability, post-cue observation, arousal quiet time, and no sleep haptics.
- Shared, phone, and Wear unit tests passed. Both release lints, signed bundle builds, and bundle signature checks passed before publishing.
- Seventy-four unit tests and eight phone instrumentation tests passed. Nine Wear workflow instrumentation tests passed on the Android phone emulator but still require a physical Wear OS watch for sensor, speaker, vibration, battery, reboot, and display proof. Phone and watch lint, signed-bundle, signature, and release-integrity checks also passed before publishing.
- Morning Check-in always opens. It uses a scrollable form, stores a manual morning record when no completed watch night exists, confirms saving, and logs the entire action path for debugging.
- The phone sound check uses a local WAV player with streaming PCM fallback and requires the user to confirm that the cue was audible. A saved calibration without that confirmation is rejected.
- **Run phone + watch check** confirms phone audio, then runs a 75-second watch check with three clear awake-only tones, all standard motion streams, Health Services heart rate, and a sequential standard heart-rate fallback. The result stays visible until acknowledged and the exact pass, fail, or two-minute timeout is stored in Export Debug Log.
- The debug export verifies the full chain: configuration, passive monitoring and inputs, automatic session handshake, sensor registration and readings, quality and eligibility decisions, cue transport, phone audio lifecycle, user-run system check, no-haptics policy, and terminal summary.
- Release bundle SHA-256: phone `97EA746FA6CF44FA4A8FE95C9BE77D0363707B7B456FC50475D1618958EDA402`; Wear OS `DDADD97D8EC9F66EB5529DC3DA6B0EF32D465423A1DEB42EC8744AA0B0D1C9DC`.
- Experimental watch sound no longer waits for seven baseline nights. After a successful watch sound check, the first eligible night allows one cue pair. The rolling seven-night baseline remains for personalization.
- This build resets passive elapsed-time markers after reboot, ties watch calibration to the exact media-volume setup, preserves the bounded five-minute post-playback raw-PPG window, and fails release builds closed when upload signing is unavailable.
- The phone detects the paired watch app. When missing, it opens DreamSync's official Play Store page on the watch and continues setup after the user confirms Install. Google Play does not permit a silent background install.
- The watch has no product settings or power controls. Manual launch sends the user to phone controls; only required first-time watch health-permission prompts remain on the watch.
- Samsung watches remain in Core protection or Monitoring only until the official SDK AAR, partnership approval, package/signature registration, and live advanced self-test are complete. The build provides a fail-closed verification task for the missing AAR.
- The 1.2.7 sound path explicitly prefers the built-in phone speaker, uses AudioTrack on Android 8, and no longer treats an available Bluetooth watch route as active headphones. The phone shows large `OFF`, `STARTING`, `ON`, and watch-confirmed states.

## Prepared 1.3.8 build

- Phone version code 49 and Wear OS version code 50 (`1.3.8-test`) are not yet uploaded.
- Compatibility now reports `DETECTED, NOT VERIFIED` when hardware registration succeeds but samples do not arrive. It no longer calls that hardware unavailable.
- The screen shows why advanced Samsung features are disabled: the official Samsung sensor package and approval remain missing.
- Heart-rate checks now use Wear Health Services first, then try every standard watch heart-rate sensor after ten seconds. The overnight service also uses all standard heart-rate candidates instead of one default sensor.
- Motion checks now try accelerometer, linear acceleration, and gravity, then retry on the watch main sensor loop when registration succeeds but readings remain at zero. The report and export identify the selected source and retry path.
- Morning Check-in now validates the complete report and saves the user's lucid result, adverse effects, and notes. It no longer closes on invalid input or before storage confirms success.
- Nine phone and ten Wear OS instrumentation tests, unit tests, phone/watch lint, debug builds, and signed release builds passed.

## Version 1.2.7 fixes

- Phone version code 29 and Wear OS version code 30 (`1.2.7-test`).
- Fixes watch-result rejection caused by normal phone/watch clock drift while keeping cue timing strict.
- Requests missing watch permissions, sends an awake-only test vibration, logs exact rejection reasons, and restores the completed watch-check status on the phone.
- Signed bundle SHA-256: phone `AEF1A37AF9050CDF1A2DDA348D3CF26C2E200F92B179CE8F7AFBB6253F9BB38D`; Wear OS `3ADB32AEC5C42D3FB1634B17400E76EC538382CADE8FAB16BA7A2D8BEA2183A1`.

## Google Play gate

This developer account must complete Google Play's required closed release with at least 12 continuously opted-in users for 14 days before production access can be requested.

## Before production submission

- Make the hosted privacy policy publicly accessible and enter its URL in Play Console.
- Finish the 11 App information and store listing tasks in Play Console.
- Upload the current icon, slogan feature graphic, and current app screenshots.
- Publish the current phone and Wear OS bundles to a closed track after app setup is complete.
- Add at least 12 real users and keep them opted in for 14 consecutive days.
- Apply for production access after the gate unlocks.
