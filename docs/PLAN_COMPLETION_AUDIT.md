# DreamSync plan completion audit

Date: 2026-07-20

## Complete locally

- Pure feature engine, eligibility estimator, cue policy, time provider, quality monitor, and session coordinator.
- Versioned, session-bound phone-watch protocol with duplicate, expiry, identity, and hostile-value rejection.
- Room persistence, explicit terminal states, fresh-handshake restart gate, heartbeats, and fail-closed watchdogs.
- Audio-route, focus, volume, calibration, interruption, sensor-freshness, off-wrist, low-battery, and storage gates.
- Estimated sleep onset, conservative NREM-eligible and REM-like eligible labels, seven-night baseline, and sleep-continuity protections.
- Guided user workflow, morning report, local history/export/deletion, deterministic replay, and de-identified sample export.
- Seventy-four deterministic unit tests pass; phone and watch debug, lint, signed bundles, and Android instrumentation packages build.
- Phone instrumentation passes 7/7 on an Android 16 phone emulator; Wear workflow instrumentation passes 9/9 when installed on that same phone emulator. This is Android behavior evidence, not physical Wear OS hardware evidence.
- Google Play internal testing serves phone 41 and Wear 42 (`1.3.4-test`). The phone track is Active; the Wear OS release is available to testers while its track summary remains Inactive pending completion of its Play form-factor setup.
- Morning Check-in always opens a scrollable form. When no completed watch night exists, it clearly creates a manual morning record instead of appearing unresponsive. Display, save, and failure events are included in Export Debug Log.
- The one-time sound check waits for real playback, uses local PCM WAV playback with streaming fallback, requires human confirmation that the cue was audible, shows the exact backend error, and provides a bounded Export Debug Log without raw node identifiers.
- A phone-controlled 75-second live check records actual watch motion and heart-rate samples, permissions, sensor registration, off-wrist state, and available advanced-sensor counts. It tries every registered motion stream, confirms the stream that actually emits data, uses Health Services before a sequential standard heart-rate fallback, and logs a failure after a two-minute timeout.
- Phone and watch diagnostics now trace configuration, passive monitoring and inputs, automatic-session handshake, sensor registration and readings, quality and eligibility decisions, cue delivery and phone audio acknowledgement, the user-run live system check, no-haptics policy, failures, and terminal summaries.
- The phone visibly reports `OFF`, `STARTING`, `ON`, and watch-confirmed states; speaker selection no longer mistakes an available Bluetooth watch route for active headphones.
- Audited source/asset/dependency provenance register, third-party notices, owner sign-off packet, and exact release fingerprints.
- Reproducible S23+/Watch Ultra validation protocol and evidence-collection runner.
- Persistent user On/Off, Wear Health Services asleep-state plus conservative passive-HR fallback, automatic fresh-session handshake, phone connected-device standby service, reboot recovery, and three-record learned charge reminder.
- Broad Android 8.0+/Wear OS 3+ packaging, manufacturer-neutral capability profiles, and automatic preservation of every approved advanced sensor on full-sensor watches.
- Passive candidate detection uses the learned resting-heart-rate baseline after three valid nights, with a conservative 75 BPM fallback before enough history exists.
- Capability, provider, night-state, decision-snapshot, and summary contracts are connected to live session persistence and debug output.
- Compatibility uses all five profiles: Enhanced, Core, Monitoring only, Phone protection only, and Needs attention.
- Watch-speaker calibration stores the exact user-confirmed gain and watch media-volume setting. Experimental sound stays blocked until a real audible check is confirmed and is invalidated if that volume changes.
- Raw PPG is restricted to a 30-second pre-cue quality window and reopens for a bounded five-minute post-playback observation window instead of running all night.
- Reboot recovery clears old elapsed-realtime sample markers before passive registration resumes, preventing timestamps from the prior boot from blocking new samples.
- Phone and Wear release tasks fail closed unless the protected upload key and passwords are present.
- Room schema 4 migrates historical `DEEP` session records to `EXPERIMENTAL_AUDIO`; old settings still fail closed to Protect.
- The hardware runner now records Galaxy, Pixel Watch, and third-party Wear OS groups, battery use, reboot-night evidence, and seven-night progress.

## Compiled but not physically device-executed

- Samsung adapter path when the official SDK AAR is supplied. The `:wear:verifySamsungSensorSdk` task fails closed until that AAR is present.
- Hardware evidence runner is syntax-checked and can enumerate Android devices; no physical phone or watch was attached for execution.

## External gates

- Official Samsung Health Sensor SDK AAR, Samsung partner approval, and package/signing-certificate registration.
- Physical Galaxy, Pixel Watch, and third-party Wear OS sensor, audio, route, disconnect, permission, process-death, off-wrist, reboot-night, seven-night, and battery validation.
- The build 38 code directly addresses the physical log's zero-reading, inaudible-tone, and disappearing-report paths, but those fixes are not claimed proven until build 38 is exercised on the real watch and its exported log is reviewed.
- PSG comparison before sleep-stage accuracy claims.
- Controlled 12-20-night study before any claim that DreamSync reduces fragmentation or keeps users asleep.
- Owner-signed prototype/source/asset declaration and its supporting attachments before public production release.
- Complete Google closed-test and production-access requirements.

DreamSync remains experimental, stage-gated, non-medical, and designed to protect sleep continuity. The slogan `Dreams Redefined.` is branding, not a guarantee.
