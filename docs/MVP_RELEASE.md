# DreamSync minimum viable product

Date: 2026-07-20

## Ready for private testing today

- Phone: Android 8.0 or later with Google Play services.
- Watch: Samsung or other Wear OS 3+ watch delivered through Google Play and passing DreamSync's capability check.
- One persistent On/Off control on the phone. While On, the watch waits for likely sleep and starts a fresh nightly monitoring session automatically.
- Core sensing uses watch motion and heart rate. Missing, stale, off-wrist, low-battery, or low-confidence data blocks cues.
- Cue audio plays from the phone speaker. The phone shows clear `OFF`, `STARTING`, `ON`, and watch-confirmed states.
- The watch has no settings. Manual launch refers the user to the phone. One short awake-only vibration confirms passive monitoring registration; no haptics are used during sleep.
- Setup check, full-chain debug export, local history/export/deletion, watchdogs, and fail-closed protocol checks are included.

## High-end Samsung path

DreamSync keeps its optional Samsung adapter for IBI/HRV, skin temperature, raw PPG, and off-wrist signals. Those features activate only when the official Samsung Health Sensor SDK AAR is packaged, Samsung approves the package/signing certificate, the watch reports the sensors, and the live self-test passes. Without that approval, Galaxy watches use the standard Wear OS motion and heart-rate path.

## Evidence for this build

- Phone `1.2.6-test`, version code 27.
- Wear OS `1.2.6-test`, version code 28.
- 73 automated checks: 53 shared, 9 phone unit, 5 phone instrumentation, and 6 watch instrumentation.
- Phone and watch lint, debug APKs, signed release bundles, signature verification, and emulator audio lifecycle passed.

## Still required on real hardware

- Paired Android phone and Samsung Wear OS watch setup test.
- Real speaker audibility, watch vibration, permissions, binding, sensors, disconnect, off-wrist, battery, and process-death tests.
- Sensor-only and overnight user testing before any sleep-effect claim.
- Samsung partner approval for advanced Samsung sensors.

DreamSync is experimental, non-medical, and designed to protect sleep continuity. It does not guarantee uninterrupted sleep or accurate sleep staging.
