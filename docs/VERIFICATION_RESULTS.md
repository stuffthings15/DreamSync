# DreamSync validation results

Date: 20 July 2026

## Verified locally

- Public name: DreamSync.
- Publisher: Remote.
- Package: `com.remote.DreamSync`.
- Published phone internal-test code: 27 (`1.2.6-test`).
- Published Wear OS internal-test code: 28 (`1.2.6-test`).
- Prepared phone internal-test code: 29 (`1.2.7-test`).
- Prepared Wear OS internal-test code: 30 (`1.2.7-test`).
- Both 1.2.6-test releases were available to internal testers in Google Play Console on 2026-07-20. Google notes that delivery can take up to one hour or occasionally longer.
- Play showed the expected non-blocking missing-deobfuscation-file warning; code obfuscation is not enabled.
- Clean shared tests: 55 checks, 0 failures, 0 errors, and 0 skipped, including durable clock-skew handling while keeping cue messages time-strict.
- Phone instrumentation passes 6/6 on an Android 16 phone emulator, including restoration of the final watch-check result after returning from the watch.
- Nine phone unit tests verify compatible PCM16 cue conversion, local WAV construction, quiet gain, clipping, hostile negative gain handling, built-in-speaker route selection, and the exported pipeline checklist.
- Six Wear OS instrumentation tests compile. They are not treated as physical sensor, display, or vibration evidence for this build.
- The first emulator run exposed missing Room schema assets in both test APKs. Both modules now package their exported schemas through the `androidTest` asset source set; clean rebuild and rerun pass.
- Phone and Wear OS debug builds pass.
- Phone and Wear OS Android lint completes with no blocking errors.
- Phone and Wear OS signed release bundles pass Gradle signing validation and `jarsigner` integrity verification.
- Google Play accepted phone code 27 and Wear OS code 28; the Wear OS release preserved the previously supported wearable catalog.
- Prepared signed bundle SHA-256 values are phone `AEF1A37AF9050CDF1A2DDA348D3CF26C2E200F92B179CE8F7AFBB6253F9BB38D` and Wear OS `3ADB32AEC5C42D3FB1634B17400E76EC538382CADE8FAB16BA7A2D8BEA2183A1`.
- Room schema 3 is generated on both devices with explicit version 2-to-3 migrations; existing local records are not intentionally destroyed.
- Legacy product, publisher, package, and research-test labels are removed from source and Markdown files.
- The research report has real source links and no broken citation markers or text-encoding artifacts.
- The revised 11-slide capstone presentation preserves the source visual system and passes template fidelity, slide-overflow, empty-placeholder, visual review, and forbidden-branding checks.
- `SOURCE_PROVENANCE.md`, `THIRD_PARTY_NOTICES.md`, and `PROVENANCE_SIGNOFF_TEMPLATE.md` trace the audited commit, release assets, runtime dependency families, inherited-prototype risk, and exact owner evidence needed before production.
- `HARDWARE_VALIDATION_PROTOCOL.md` and `tools/Run-DreamSyncHardwareValidation.ps1` provide one repeatable phone/watch evidence collection path without claiming that unexecuted device tests passed.

## Sleep-continuity implementation

- One persistent On/Off choice controls nightly operation. While On, Wear Health Services uses the optional asleep-state signal or a conservative three-sample passive-HR candidate inside the normal sleep window to create a fresh confirmation session every night.
- Turn On now guides the required one-time quiet cue calibration, finds reachable or previously known watches, opens the official watch Play install when the watch app is missing, polls for completion, then binds and synchronizes setup.
- Sound checks wait for confirmed playback instead of assuming the foreground service succeeded. The phone explicitly selects the built-in speaker when available, uses local PCM WAV playback, uses AudioTrack on Android 8, reports the exact backend and route failure, and saves it to a privacy-safe Export Debug Log file.
- Sound calibration now requires an explicit user confirmation that the cue was audible. **Run phone + watch check** then launches a bound, expiring 45-second watch test that records actual motion and heart-rate samples, permissions, registration, off-wrist state, and every available advanced-sensor count. Missing results fail after two minutes and are logged.
- The live check now repairs missing activity/heart permission, sends a 240 ms awake-only test vibration, states that sound remains on the phone, and restores its final pass/fail reason when the phone screen returns.
- The watch contains no user settings. It automatically opens the phone controls on manual launch and automatically retries delayed phone configuration during first-time setup.
- Passive HR alone never enables a cue. Higher-rate sustained stillness, fresh heart rate, signal quality, and all later safety gates remain required.
- The phone keeps a visible low-priority connected-device service while On; reboot and package-update receivers restore both phone readiness and watch passive registration.
- Deterministic flow: On and waiting, passive asleep signal, confirming sleep, stable sleep, possible arousal, recovered, and session stopped.
- Sleep onset still requires sustained low motion plus fresh cardiac data.
- Motion-only sessions log but cannot trigger deep or lucid cues.
- A seven-night rolling median baseline stores resting HR, IBI, RMSSD, skin temperature, and onset timing while early nights use conservative defaults.
- Arousal starts a 20-minute quiet period.
- The first cue-linked wake sign disables cues for the night and lowers gain.
- A morning disturbance rating lowers the next calibration level and requires recalibration.
- Continuity output includes estimated awakening count, WASO, longest uninterrupted bout, cue-linked arousal rate, and morning disturbance.
- Signal output includes registration, freshness, sample counts, observed rates, off-wrist status, source, battery, and capability data.
- Optional user-approved notification protection uses Android priority mode only during a detected sleep session and restores the previous state on Off, timeout, summary, or relaunch recovery.
- Users are told to charge several hours before normal sleep. After three completed records, an inexact local phone alarm reminds them three hours before the median usual sleep-start time.
- Pre-sleep coaching includes schedule, daylight, exercise, warm-bath, and comfortable breathing guidance plus non-diagnostic apnea referral language.

## Samsung sensor implementation

- Optional Samsung Health Sensor SDK adapter supports continuous HR plus IBI, skin temperature, off-wrist status, and raw PPG.
- The manufacturer-neutral feature classifier keeps all approved reported sensors enabled on full-sensor watches; unsupported trackers are ignored at runtime.
- Standard Wear OS accelerometer, passive HR, and active heart-rate sensing remain the broad core fallback.
- Phone minimum is Android 8.0/API 26; watch minimum is Wear OS 3/API 30. Play and live capability checks may narrow delivery further.
- Live compatibility states are Fully supported, Basic monitoring only, or Not supported.
- The watch sends durable capability data to the phone and export.
- The build conditionally packages `wear/libs/samsung-health-sensor-api.aar` when supplied.

## Protocol and audio safety

- One valid cue request can produce at most one playback; invalid requests produce none.
- Duplicate, expired, malformed, wrong-version, wrong-session, wrong-device, wrong-sender, and reordered commands fail closed. Durable results allow up to five minutes of cross-device clock drift; cue lifecycle messages retain a strict five-second future-time limit.
- Durable phone-watch state and summary items validate the source node, sender, receiver, session, type, protocol version, path, expiry, and duplicate ID before changing local state.
- The first terminal cause is preserved, exact watch terminal outcomes are stored, and a valid summary clears only the current session while preserving the recurring On enrollment for the next night.
- Cue allowance is consumed only after `AUDIO_STARTED`.
- Speaker route, mute, volume, focus, interruptions, initialization, calibration, and waveform version are checked.
- No sleep haptics are used. The debug export explicitly reports `NONE_DURING_SLEEP`; cue audio is phone-speaker only.
- The phone shows large `OFF`, `STARTING`, `ON`, and watch-confirmed states. The watch gives one short confirmation vibration only while the user is awake after passive monitoring registers.
- Phone loss, stale sensors, off-wrist state, low battery, storage failure, route problems, and process interruption prevent or disable cueing.
- Logs, summaries, configuration, cue lifecycle, surveys, capabilities, and failures are local and exportable.
- The separate phone debug export contains app version, Android/device summary, current audio volume/route state, audible-confirmation state, watch binding flags, bounded phone and watch events, live sensor values and counts, quality and eligibility decisions, cue acknowledgements, the latest user-run system-check result or timeout, and a numbered pipeline checklist. It excludes raw node IDs.
- Watch diagnostics record passive-registration status, permissions, activity-state and passive-HR inputs, automatic-session start blocks, active sensor registration and freshness, heartbeat readings, cue decisions, and terminal results. A no-cue result is valid when a safety gate is blocked, and the exact gate is logged.
- The confirmed phone deletion control removes phone data and queues a bound, expiring, duplicate-guarded watch deletion command; deletion remains blocked while DreamSync is On or a session is active.

## Research and marketing boundary

- Slogan: Dreams Redefined.
- Mission: Designed to protect sleep continuity.
- Public copy uses estimated, likely, experimental, stage-gated, and non-medical language.
- Compatibility is capability-based and does not use "or better."
- The claim-to-evidence matrix maps each product statement to a paper, official source, local implementation, and claim limit.
- No claim of guaranteed sleep continuity, EEG staging, treatment, deeper sleep, memory benefit, biological effect, or guaranteed lucid dreaming is made.

## External gates still required

- Add the official Samsung Health Sensor SDK AAR after Samsung sign-in.
- Obtain Samsung Partner Program approval and register `com.remote.DreamSync` plus the upload signing certificate.
- Run the live Samsung self-test on Galaxy Watch Ultra 2024 and 2025.
- Physical S23+ and Watch Ultra sensor, audio, route, battery, permission, disconnect, off-wrist, and process-death validation.
- Three sensor-only nights, baseline/no-cue comparison, active-cue night, and recovery tests.
- Concurrent polysomnography before any sleep-stage accuracy claim.
- Controlled 12-20-night study before any claim that DreamSync reduces fragmentation or keeps users asleep.
- Apple Watch remains a separate future Swift/HealthKit/Core Motion/Watch Connectivity feasibility project.
- Google Play closed-release gate and all production-policy tasks remain external to local code.
- The hardware runner parsed successfully and its list-only preflight found zero attached physical Android devices on 2026-07-19; physical execution therefore remains pending.
