# Dream Sync verification results

Date: 18 July 2026

## Verified locally

- Local Git baseline: `fdd1244 chore: baseline Dream Sync prototype`.
- Java/Android compilation: phone and watch debug builds pass.
- Shared unit/property tests: 9 tests, 0 failures. Protocol round trip, wrong session/device/version, expiry, duplicates, reordering, malformed payloads, signal gates, sleep onset, arousal policy, waveform timing, and deterministic replay.
- Sample replay fingerprint: `25ba38d66d023dea6bf4589aaf4986688be4f8ede85a054c34f498af2d2c0e9b`.
- Room schema generation: phone and watch schema version 1 generated.
- Watch installer recovery: authorization is probed before upload, the APK is pushed then installed through `pm install -r`, every stage is visible, and network reads fail after 120 seconds instead of hanging forever.
- Presentation: all 11 slides rendered and inspected; template-fidelity audit passed with zero issues.
- Word artifacts: package structure, styles, tables, headings, metadata, and content inspected. Automated page rendering was attempted but LibreOffice was unavailable and headless Word export did not complete.

## Acceptance status

| Area | Status | Evidence |
|---|---|---|
| Shared deterministic core | PASS | Gradle tests |
| Phone debug APK | PASS | `phone/build/outputs/apk/debug/phone-debug.apk` |
| Watch debug APK | PASS | `wear/build/outputs/apk/debug/wear-debug.apk` |
| Durable protocol/persistence | IMPLEMENTED | Build, schemas, code trace |
| Audio/sensor readiness | IMPLEMENTED | Build and unit tests; physical routes/sensors pending |
| S23+ / Watch Ultra accelerated runs | PENDING | Physical devices required |
| Sensor-only / ACTIVE / SHAM nights | PENDING | Overnight study required |
| Disconnect, removal, battery, process-death fault runs | PENDING | Physical instrumentation required |
| Sleep or lucid-dream effect | NOT CLAIMED | Requires planned 12–20-night experiment |

## Core invariant

One valid cue request produces at most one playback. Every invalid request produces none.

Hardware results must be appended here after each real run. Record device/OS versions, APK hashes, condition, signal coverage, audio timing, battery use, terminal state, failures, and summary recovery.

## APK checksums

- Phone debug APK: `154D943A33B8CDC9A01D7D463C98E5F89E7F95EBAC9D54DBDC1A0722F0E0532A`
- Watch debug APK: `B766B16EC63BB155A13EBCC5840C4F08FC375E10D79CE2E9CC9DC8028F02702D`
