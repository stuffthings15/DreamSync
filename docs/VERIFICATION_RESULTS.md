# DreamSync validation results

Date: 19 July 2026

## Verified locally

- Public name: DreamSync.
- Publisher: Remote.
- Package: `com.remote.DreamSync`.
- Published phone internal-test code: 13 (`1.1.2-test`).
- Published Wear OS internal-test code: 14 (`1.1.2-test`).
- Both releases were available to internal testers in Google Play Console on 2026-07-19. Google notes that delivery can take up to one hour or occasionally longer.
- Play showed the expected non-blocking missing-deobfuscation-file warning; code obfuscation is not enabled.
- Clean shared tests: 33 checks, 0 failures, 0 errors, and 0 skipped.
- Phone and Wear OS Android instrumentation test APKs compile; device execution remains pending because no test device was attached.
- Phone and Wear OS debug builds pass.
- Phone and Wear OS Android lint completes with no blocking errors.
- Phone and Wear OS signed release bundles pass Gradle signing validation and `jarsigner` integrity verification.
- Room schema 3 is generated on both devices with explicit version 2-to-3 migrations; existing local records are not intentionally destroyed.
- Legacy product, publisher, package, and research-test labels are removed from source and Markdown files.
- The research report has real source links and no broken citation markers or text-encoding artifacts.
- The revised 11-slide capstone presentation preserves the source visual system and passes template fidelity, slide-overflow, empty-placeholder, visual review, and forbidden-branding checks.
- `SOURCE_PROVENANCE.md`, `THIRD_PARTY_NOTICES.md`, and `PROVENANCE_SIGNOFF_TEMPLATE.md` trace the audited commit, release assets, runtime dependency families, inherited-prototype risk, and exact owner evidence needed before production.
- `HARDWARE_VALIDATION_PROTOCOL.md` and `tools/Run-DreamSyncHardwareValidation.ps1` provide one repeatable phone/watch evidence collection path without claiming that unexecuted device tests passed.

## Sleep-continuity implementation

- Visible nightly Arm remains required; after Arm, likely-sleep detection is automatic.
- Deterministic states: Armed, Preparing for sleep, Likely asleep, Stable sleep, Possible arousal, Recovered, and Stopped.
- Sleep onset still requires sustained low motion plus fresh cardiac data.
- Motion-only sessions log but cannot trigger deep or lucid cues.
- A seven-night rolling median baseline stores resting HR, IBI, RMSSD, skin temperature, and onset timing while early nights use conservative defaults.
- Arousal starts a 20-minute quiet period.
- The first cue-linked wake sign disables cues for the night and lowers gain.
- A morning disturbance rating lowers the next calibration level and requires recalibration.
- Continuity output includes estimated awakening count, WASO, longest uninterrupted bout, cue-linked arousal rate, and morning disturbance.
- Signal output includes registration, freshness, sample counts, observed rates, off-wrist status, source, battery, and capability data.
- Optional user-approved notification protection uses Android priority mode only while a night is armed and restores the previous state on Stop, timeout, summary, or relaunch recovery.
- Pre-sleep coaching includes schedule, daylight, exercise, warm-bath, and comfortable breathing guidance plus non-diagnostic apnea referral language.

## Samsung sensor implementation

- Optional Samsung Health Sensor SDK adapter supports continuous HR plus IBI, skin temperature, off-wrist status, and optional raw PPG.
- Raw PPG is disabled by default.
- Standard Wear OS accelerometer and heart-rate sensing remains the fallback.
- Live compatibility states are Fully supported, Basic monitoring only, or Not supported.
- The watch sends durable capability data to the phone and export.
- The build conditionally packages `wear/libs/samsung-health-sensor-api.aar` when supplied.

## Protocol and audio safety

- One valid cue request can produce at most one playback; invalid requests produce none.
- Duplicate, expired, malformed, wrong-version, wrong-session, wrong-device, wrong-sender, and reordered commands fail closed.
- Durable phone-watch state and summary items validate the source node, sender, receiver, session, type, protocol version, path, expiry, and duplicate ID before changing local state.
- The first terminal cause is preserved, exact watch terminal outcomes are stored, and a valid summary clears the session binding so the next night can start cleanly.
- Cue allowance is consumed only after `AUDIO_STARTED`.
- Speaker route, mute, volume, focus, interruptions, initialization, calibration, and waveform version are checked.
- No sleep haptics are used.
- Phone loss, stale sensors, off-wrist state, low battery, storage failure, route problems, and process interruption prevent or disable cueing.
- Logs, summaries, configuration, cue lifecycle, surveys, capabilities, and failures are local and exportable.
- Phone and watch deletion controls remove each device's local database and session files; deletion is blocked during an active session.

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
- The hardware runner parsed successfully and its list-only preflight found zero attached Android devices on 2026-07-19; physical execution therefore remains pending.
