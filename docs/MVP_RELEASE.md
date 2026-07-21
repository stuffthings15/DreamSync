# DreamSync minimum viable product

Date: 2026-07-20

## Ready for private testing today

- Phone: Android 8.0 or later with Google Play services.
- Watch: Samsung or other Wear OS 3+ watch delivered through Google Play and passing DreamSync's capability check.
- One persistent On/Off control on the phone. While On, the watch waits for likely sleep and starts a fresh nightly monitoring session automatically.
- Core sensing uses watch motion and heart rate. Missing, stale, off-wrist, low-battery, or low-confidence data blocks cues.
- Protect only is the default and plays no sleep sound. Experimental watch sound stays locked until seven valid baseline nights, then begins at one pair and can rise to two and four only after low-disturbance nights.
- Experimental audio plays from the built-in watch speaker. Phone fallback is Off unless the user explicitly enables it. The phone shows clear `OFF`, `STARTING`, `ON`, and watch-confirmed states.
- The watch has no settings. Manual launch refers the user to the phone. One short awake-only vibration confirms passive monitoring registration; no haptics are used during sleep.
- Setup check, full-chain debug export, local history/export/deletion, watchdogs, and fail-closed protocol checks are included.
- The awake watch check requests missing activity/heart permissions, plays a watch-speaker cue, sends a test vibration, tries three standard motion paths, uses Wear Health Services for live heart rate, records every result, and opens a detailed report on the phone.
- Durable watch results tolerate reasonable phone/watch clock drift. Time-sensitive cue messages keep the strict five-second future-time limit.

## High-end Samsung path

DreamSync keeps its optional Samsung adapter for IBI/HRV, skin temperature, raw PPG, and off-wrist signals. Those features activate only when the official Samsung Health Sensor SDK AAR is packaged, Samsung approves the package/signing certificate, the watch reports the sensors, and the live self-test passes. Raw PPG opens only for a 30-second pre-cue window and up to five minutes after playback starts. Without approval, Galaxy watches use the standard Wear OS motion and heart-rate path.

## Evidence for this build

- Phone `1.3.1-test`, version code 35.
- Wear OS `1.3.1-test`, version code 36.
- Automated test and build totals are recorded in `VERIFICATION_RESULTS.md` after each release build.
- Seven phone and nine Wear OS workflow instrumentation tests passed on the Android 16 phone emulator. Real watch sensors, speaker, vibration, battery, reboot, and display behavior still require physical Wear OS hardware.
- Phone and watch lint, debug APKs, signed release bundles, signature verification, and Android 16 phone-emulator workflow passed.

## Still required on real hardware

- Paired Android phone and Samsung Wear OS watch setup test.
- Real speaker audibility, watch vibration, permissions, binding, sensors, disconnect, off-wrist, battery, and process-death tests.
- Sensor-only and overnight user testing before any sleep-effect claim.
- Samsung partner approval for advanced Samsung sensors.

DreamSync is experimental, non-medical, and designed to protect sleep continuity. It does not guarantee uninterrupted sleep or accurate sleep staging.
