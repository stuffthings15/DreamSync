# DreamSync validation results

Date: 23 July 2026

## Verified locally

### Published 1.3.16 Samsung-approved sensor delivery

- Phone version code 65 and Wear OS version code 66 (`1.3.16-test`) compile.
- Official Samsung Health Sensor SDK v1.4.1 and its required API contract verified.
- Samsung continuous trackers now receive controlled flush requests during the awake
  check and every 30 seconds during a live session so batched readings reach the app.
- Debug export records each tracker flush request, SDK acceptance, completion callback,
  tracker error, and advanced-sensor sample count.
- Shared, phone, and Wear OS debug/release unit suites passed: 123 test executions,
  zero failures, zero errors, zero skipped.
- Phone and Wear OS debug lint, debug APKs, and instrumentation APK packaging passed.
- Signed phone version code 65 and Wear OS version code 66 were published to Google
  Play Internal testing on 2026-07-23. Both releases show Available to internal
  testers. The phone track is Active; Play still labels the Wear OS track Inactive.
- Physical Galaxy Watch proof is still required. Publication does not prove live
  Samsung sensor delivery and must not be described as hardware verification.

### Published 1.3.15 enforced Samsung SDK build

- Phone version code 63 and Wear OS version code 64 (`1.3.15-test`) were built, signed, and published to Google Play internal testing on 2026-07-21. Both releases say Available to internal testers; the phone track is Active and Play still labels the Wear OS track Inactive.
- Wear release builds now fail if the exact official Samsung Health Sensor SDK v1.4.1 AAR is absent, altered, or missing any required tracking contract class.
- The verified SDK exposes the exact APIs used by DreamSync for continuous accelerometer, continuous heart rate and IBI, continuous skin temperature, off-wrist status, and Green/IR/Red continuous PPG.
- 97 unit tests passed with zero failures. Phone and Wear release lint, debug APK builds, signed AAB builds, both AAB signature checks, and the Samsung SDK checksum/API-contract gate passed.
- This proves the official SDK is compiled into the release. It does not replace Samsung partner approval or physical Galaxy Watch sensor validation.

### Published 1.3.14 official Samsung SDK build

- Phone version code 61 and Wear OS version code 62 (`1.3.14-test`) were built, signed, and published to Google Play internal testing on 2026-07-21. Both releases are available to internal testers; the phone track is Active and Play labels the Wear OS track Inactive.
- The official Samsung Health Sensor SDK v1.4.1 AAR was downloaded from the signed-in Samsung Developer site, installed locally under `wear/libs/`, verified by Gradle, and packaged into the Wear APK. The licensed AAR remains ignored by Git.
- Samsung continuous accelerometer, heart-rate/IBI, skin-temperature, off-wrist, and explicit Green/IR/Red PPG adapter paths compile and package. Standard Wear OS motion and heart-rate paths remain available as fallback.
- Samsung motion must deliver five fresh samples before becoming primary. Standard motion remains available and is restored if Samsung data stalls. Samsung heart data is accepted only when Samsung reports successful status.
- 97 unit tests passed with zero failures. Phone and Wear release lint, debug APK builds, signed AAB builds, and both AAB signature checks passed.
- This is package evidence, not physical sensor proof. Health Platform developer mode and a new Galaxy Watch Ultra check are required before the Samsung tracker paths are called working on hardware.

### Published 1.3.13 sensor-recovery build

- Phone version code 59 and Wear OS version code 60 (`1.3.13-test`) were published to their Google Play internal tracks on 2026-07-21. Both releases are available to internal testers; the phone track is Active and Play labels the Wear OS track Inactive.
- Log 8 proved that watch audio and vibration passed, permissions were granted, passive monitoring paused successfully, but Android delivered zero motion callbacks and Health Services remained `ACQUIRING` with zero heart readings.
- The awake watch check now waits for sensor release, probes one motion source at a time, requires five valid samples from one source, runs Health Services heart measurement alone, fully unregisters it, then probes one raw heart sensor at a time.
- Live watch text now shows X/Y/Z, vector magnitude, BPM, the active source, and source-specific state. Registration alone is never reported as a sensor pass.
- Global callback silence is classified explicitly. The phone export includes power, idle, charging, focus, callback latency, flush, source, availability, and every probe transition.
- The overnight foreground service uses the same one-source-at-a-time fallback approach. Fresh passive heart readings are forwarded into the active session instead of discarded; motion and cardiac safety gates still fail closed.
- Clean build, 91 unit tests, phone instrumentation 9/9, Wear workflow instrumentation 10/10, both debug APKs, and both Android lint checks passed.
- Debug APK SHA-256: phone `FABD3905D8E89CC205A0343F0A8EAE44C80A40043E54445CFB53E64FC4EFE577`; Wear OS `28FAE6487F4D988C3AF117FB35948469903FC4B40188635C632B7AA84863A535`.
- Published AAB SHA-256: phone `11A03F75BC7D355EDBCACEC48087D589A49B0DD877C77719F3E83A6FFFED2893`; Wear OS `AD582BD137301FAF35BF3406FB6C0D009189B8C503769236F8B741AD7DA4FB18`.
- Physical Watch Ultra motion and heart-rate delivery is not claimed fixed until the new build returns changing values. If it still reports global silence, disable the watch developer `Sensors Off` toggle and Power saving, wear it snugly, and rerun before exporting the new log.

### Prepared 1.3.8 test build

- Phone version code 49 and Wear OS version code 50 (`1.3.8-test`) are being prepared for internal-test upload. Google Play still serves 1.3.4 until a newer build is uploaded.
- 80 local unit tests passed with 0 failures, 0 errors, and 0 skipped.
- Phone instrumentation passed 9/9 on an Android 16 phone emulator, including the Morning Check-in button and the compatibility distinction between detected hardware and verified readings. Wear workflow instrumentation passed 10/10, including the rule that advertised advanced sensors are not called verified until live samples pass; this proves Android behavior only, not physical Wear OS sensors, audio, vibration, battery, reboot, or display behavior.
- Phone and Wear OS lint passed with no blocking errors.
- Phone and Wear OS debug APKs built successfully.
- Signed phone APK SHA-256: `73E63A53C705091A2F40B2015DED929091E7FD214269C84DD1C968EC28A5DC8C`.
- Signed Wear OS APK SHA-256: `E2FFCD1485D0684F402CECC3C602E489F9C5EDEA3BE936B5BEA54D88A770DB7E`.
- Signed phone AAB SHA-256: `B58A89F1C25AA024572EFDE8B635DAFF3D3056B3D0D7BB506F4C079B2D716A3B`.
- Signed Wear OS AAB SHA-256: `F01BA90D0D9F8BC531591CDCFCC65FB11E05E4840F6E04DAAEAA17D258377612`.
- Both AAB signatures passed integrity verification. Google Play showed only the expected non-blocking missing-deobfuscation-file warning; R8/ProGuard obfuscation is not enabled.
- Release builds now fail before execution when the protected upload-signing configuration is missing; Gradle can no longer silently emit an unsigned internal-test bundle.
- Reboot recovery clears persisted elapsed-realtime sample markers before passive monitoring restarts, while preserving the learned sleep baseline.
- Experimental watch audio is valid only at the exact user-confirmed watch media-volume setting. A changed setting blocks audio and requires a new awake check.
- Raw PPG reopens for the full bounded five-minute quality window when watch audio starts, even when the pre-cue window has already closed.
- Protect only is the default. Experimental watch sound can begin on the first eligible night after a successful watch sound check, starts at one pair, can progress to two and four after low-disturbance nights, observes for five minutes after each pair, pauses 20 minutes after arousal signs, and reduces the next gain by 30% after a cue-linked arousal.
- Passive watch monitoring is restored after reboot through WorkManager. A fresh phone-watch handshake is still required before any sound.
- The compatibility screen separates detected hardware from verified live readings. A failed check no longer falsely labels motion or heart-rate hardware unavailable. Advanced Samsung features explicitly show that the official sensor package or live verification is missing.
- Heart-rate collection no longer depends on one default raw sensor. The awake check uses Wear Health Services first, falls back after ten seconds, registers every standard heart-rate candidate, locks to the first valid source, rejects unreliable/out-of-range values, and reports the last BPM. The real sleep session also registers every standard candidate and exports its selected source and registration details.
- Motion collection tries accelerometer, linear acceleration, and gravity candidates. If a vendor accepts registration but produces no event, the awake check retries after eight seconds and the sleep service retries after ten seconds on the main sensor loop. Already-linear acceleration is no longer incorrectly filtered a second time. The phone report and debug export show whether fallback ran and which source emitted data.
- Morning Check-in now always displays a scrollable form. If no completed watch night exists, it creates a clearly labeled manual record. The debug log records tap, display, save, and save failure.
- The complete documented morning report now saves an actual user-entered lucid result instead of always writing `UNSURE`. Required ratings and categorical answers are validated, invalid entries remain on screen, and the dialog closes only after Room confirms the save.

- Public name: DreamSync.
- Publisher: Remote.
- Package: `com.remote.DreamSync`.
- Published phone internal-test code: 41 (`1.3.4-test`).
- Published Wear OS internal-test code: 42 (`1.3.4-test`).
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
- Google Play accepted phone code 29 and Wear OS code 30 for internal testing on 2026-07-20; both releases are available to internal testers.
- Published signed bundle SHA-256 values are phone `AEF1A37AF9050CDF1A2DDA348D3CF26C2E200F92B179CE8F7AFBB6253F9BB38D` and Wear OS `3ADB32AEC5C42D3FB1634B17400E76EC538382CADE8FAB16BA7A2D8BEA2183A1`.
- Room schema 3 is generated on both devices with explicit version 2-to-3 migrations; existing local records are not intentionally destroyed.
- Legacy product, publisher, package, and research-test labels are removed from source and Markdown files.
- The research report has real source links and no broken citation markers or text-encoding artifacts.
- The revised 11-slide capstone presentation preserves the source visual system and passes template fidelity, slide-overflow, empty-placeholder, visual review, and forbidden-branding checks.
- `SOURCE_PROVENANCE.md`, `THIRD_PARTY_NOTICES.md`, and `PROVENANCE_SIGNOFF_TEMPLATE.md` trace the audited commit, release assets, runtime dependency families, inherited-prototype risk, and exact owner evidence needed before production.
- `HARDWARE_VALIDATION_PROTOCOL.md` and `tools/Run-DreamSyncHardwareValidation.ps1` provide one repeatable phone/watch evidence collection path without claiming that unexecuted device tests passed.

## Sleep-continuity implementation

- One persistent On/Off choice controls nightly operation. While On, Wear Health Services uses the optional asleep-state signal or a conservative three-sample passive-HR candidate inside the normal sleep window to create a fresh confirmation session every night.
- Turn On finds reachable or previously known watches, opens the official watch Play install when the watch app is missing, polls for completion, then binds and synchronizes setup. Phone-speaker calibration is optional because the phone is now fallback output.
- The watch built-in speaker is the primary cue path. A streaming AudioTrack selects and verifies that route. Phone playback runs only when watch audio fails before start; no fallback runs after watch playback begins.
- **Run watch system check** launches a bound, expiring 75-second watch test. It plays three 300 ms awake-only tones at 10% PCM gain, records every standard motion registration attempt, confirms the stream that emits data, uses Health Services MeasureClient before a sequential standard heart-rate fallback, and records watch audio, vibration, permissions, off-wrist state, and advanced sensors.
- The phone opens a detailed pass/fail report after every returned result and asks whether watch audio was actually heard. The watch no longer remote-opens a replacement phone activity after delivery, and the report remains pending until the user acknowledges it. The debug export includes 5/15/30/60-second progress and the full final payload. Missing results fail after two minutes.
- The watch contains no user settings. It automatically opens the phone controls on manual launch and automatically retries delayed phone configuration during first-time setup.
- Passive HR alone never enables a cue. Higher-rate sustained stillness, fresh heart rate, signal quality, and all later safety gates remain required.
- The phone keeps a visible low-priority connected-device service while On; reboot and package-update receivers restore both phone readiness and watch passive registration.
- Deterministic flow: On and waiting, passive asleep signal, confirming sleep, stable sleep, possible arousal, recovered, and session stopped.
- Sleep onset still requires sustained low motion plus fresh cardiac data.
- Motion-only sessions log but cannot trigger experimental sound.
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
- Live compatibility states are Enhanced, Core, Monitoring only, Phone protection only, or Needs attention.
- The watch sends durable capability data to the phone and export.
- The build conditionally packages `wear/libs/samsung-health-sensor-api.aar` when supplied.

## Protocol and audio safety

- One valid cue request can produce at most one playback; invalid requests produce none.
- Duplicate, expired, malformed, wrong-version, wrong-session, wrong-device, wrong-sender, and reordered commands fail closed. Durable results allow up to five minutes of cross-device clock drift; cue lifecycle messages retain a strict five-second future-time limit.
- Durable phone-watch state and summary items validate the source node, sender, receiver, session, type, protocol version, path, expiry, and duplicate ID before changing local state.
- The first terminal cause is preserved, exact watch terminal outcomes are stored, and a valid summary clears only the current session while preserving the recurring On enrollment for the next night.
- Cue allowance is consumed only after `AUDIO_STARTED`.
- Speaker route, mute, volume, focus, interruptions, initialization, calibration, and waveform version are checked.
- No sleep haptics are used. The debug export explicitly reports `NONE_DURING_SLEEP`; cue audio is watch-speaker primary with phone-speaker pre-start fallback.
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

- Obtain Samsung Partner Program approval and register `com.remote.DreamSync` plus the Google Play app-signing certificate.
- Run the live Samsung self-test on Galaxy Watch Ultra 2024 and 2025.
- Physical Galaxy, Pixel Watch, and third-party Wear OS sensor, audio, route, battery, permission, disconnect, off-wrist, reboot-night, and process-death validation.
- Seven-night hardware baseline plus sensor-only, no-cue comparison, active-cue, and recovery tests.
- Concurrent polysomnography before any sleep-stage accuracy claim.
- Controlled 12-20-night study before any claim that DreamSync reduces fragmentation or keeps users asleep.
- Apple Watch remains a separate future Swift/HealthKit/Core Motion/Watch Connectivity feasibility project.
- Google Play 1.3.13 internal releases are complete. Both releases are available to internal testers. The Wear OS track still shows Inactive until the Wear form-factor setup is finished. The closed-release gate and all production-policy tasks remain external to local code.
- The hardware runner parsed successfully and its list-only preflight found zero attached physical Android devices on 2026-07-19; physical execution therefore remains pending.
