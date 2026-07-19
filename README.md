# Dream Sync

Private Android/Wear OS research prototype for an S23+ and Galaxy Watch Ultra.
It logs accelerometer and heart-rate features, estimates conservative cue eligibility,
and asks the phone to play calibrated audio. It is experimental, non-medical, and
does not measure EEG or diagnose sleep stages.

## Research mapping

- The local source paper used two 50 ms pink-noise tones separated by 1.075 seconds,
  phase-locked to EEG slow oscillations during consolidated NonREM sleep.
- Dream Sync preserves the waveform timing but cannot reproduce EEG phase locking.
  Its watch states are therefore `NREM_ELIGIBLE` and `REM_LIKE_ELIGIBLE`, not sleep-stage claims.
- Lucid cues require fresh motion and cardiac data. Motion-only sessions log data but
  do not trigger lucid cues.
- `ACTIVE` and `SHAM` sessions record the same eligibility opportunities. SHAM omits sound.

Source: Besedovsky et al., *Nature Communications* 8, 1984 (2017),
DOI `10.1038/s41467-017-02170-3`, preserved in `docs/sleep study data.docx`.

## Safety and reliability

- One session binds one capability-matched phone and watch.
- Cue envelopes carry protocol, session, message, cue, sender, target, time, and expiry fields.
- Invalid, duplicate, expired, wrong-session, wrong-device, and unknown cue commands fail closed.
- A cue counts against the night only after the phone reports `AUDIO_STARTED`.
- Audio arming blocks mute, headphones, Bluetooth audio, active calls, failed calibration,
  focus denial, incomplete playback, stale sensors, low battery, storage failure, or phone loss.
- Process restart marks the old session interrupted. Cueing requires a fresh handshake.

## Modules

| Module | Role |
|---|---|
| `shared/` | Protocol, deterministic waveform/features/eligibility, replay harness, unit tests |
| `phone/` | Onboarding, readiness, audio, trial assignment, survey, history/export/delete, Room |
| `wear/` | Sensors, quality gates, cue policy, watchdogs, durable summary/log, Room |

## Build and test

Requires JDK 17 and Android SDK platform 36.

```powershell
$env:JAVA_HOME='C:\Users\stuff\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
.\gradlew.bat :shared:test testDebugUnitTest :wear:assembleDebug :phone:assembleDebug
```

The phone debug APK embeds the watch debug APK for the existing private Wi-Fi ADB installer.
Public Play distribution, production signing, Samsung Health Sensor SDK, learned models,
Health Connect, and medical claims are deliberately out of scope.

## License and publication status

The private capstone uses the Dream Sync package ID `com.remote.DreamSync`.
No reliable license/provenance file was present in the supplied prototype. Ownership and
third-party provenance must be resolved before any publication. Do not publish this tree
or its APKs to a public store.
