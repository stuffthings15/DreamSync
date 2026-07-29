# DreamSync Android and Wear OS hardware validation protocol

Version under test: phone 69 / Wear OS 70 (`1.3.18-test`)

Required device groups:

1. Galaxy: Galaxy S23+ with Galaxy Watch Ultra 2024 or 2025.
2. Pixel: a supported Android phone with a Pixel Watch that passes the live check.
3. Third-party Wear OS: a non-Samsung, non-Google Wear OS 3+ watch that passes the live check.

Local tests prove decision logic and build integrity. This protocol gathers the device evidence needed before marketing a watch as physically validated or claiming a sleep effect.

## Before testing

1. Install both internal-test releases from Google Play using the approved tester account.
2. Charge the watch several hours before the normal sleep time and record starting battery; target at least 80%. Charge the phone, enable Bluetooth, and confirm normal Samsung/Wear OS pairing.
3. Connect the phone through USB ADB and the watch through paired wireless ADB.
4. Run `tools/Connect-DreamSyncDevices.ps1 -ListOnly`, then connect the watch with
   `-WatchAddress` if it is absent.
5. Use `tools/Start-DreamSyncDeviceTest.ps1` with an explicit lane and scenario.
   See `USB_DEVICE_LAB.md`.
6. Destructive scenarios require `-RequireDestructiveApproval`; controlled cueing
   requires `-RequireControlledCueApproval`.
7. Keep Samsung advanced features disabled unless the official Samsung AAR and approvals are present.

The device lab stores raw evidence under
`AI Brain\Dream Sync\hardware-validation\`. Manifests use hashed device identities.
Treat it as private evidence, review it before sharing, and never upload it automatically.

## Acceptance matrix

| Area | Test | Pass condition | Evidence |
|---|---|---|---|
| Installation | Install phone and watch from their Play tester pages | Correct 1.3.18-test build opens on each device | Play screen plus collected package metadata |
| Pair binding | Turn DreamSync On from the phone | One capability-matched watch is bound; no broadcast to other nodes | Export plus connection events |
| Permissions | Deny, grant, and revoke each required permission | Clear explanation; no cue when a required permission is missing | Screen capture and event export |
| Sensors | Wear watch normally for at least 30 minutes | Fresh motion and HR, usable sample rate/coverage, on-wrist state | Capability/signal export |
| Sleep onset | Three sensor-only nights | Onset is estimated only after sustained low motion and fresh HR | Session exports and morning notes |
| Audio check | Run the watch sound check at the selected gain | Watch cue is audible, exact gain is stored as the threshold, overnight gain is half-threshold and at most 4%, and no stale timeout remains | Screen recording plus Export Debug Log |
| Audio timing | Bench cue at each allowed volume | Two 50 ms bursts, 1.075 seconds apart, no clipping; gain never exceeds 10% | Audio capture and waveform version |
| Audio route | Change media route, mute, headphones, and Bluetooth route | Automatic session/cueing blocks on unsafe or unknown routes | Event export and screen capture |
| Arousal safety | Move/wake after a cue | 20-minute quiet period; first cue-linked wake disables further cues | Cue/arousal lifecycle export |
| Duplicate safety | Reconnect/reorder around one cue request | At most one playback for one valid request; invalid requests play none | Phone/watch lifecycle logs |
| Phone loss | Disable phone Bluetooth or stop the phone app | Watch disables cues and records `PHONE_LOST` or an explicit interruption | Watch export and recovered phone summary |
| Process death | Force-stop phone, then watch, during controlled runs | No blind cue resume; fresh handshake required | Collected logs and session terminal state |
| Off-wrist | Remove the watch during a session | Cues stop; state explains off-wrist or stale signal | Watch event export |
| Low battery | Controlled low-battery run | Cues disable before unsafe battery threshold | Battery/event export |
| Battery | Full-night sensor-only and experimental runs | Start/end/elapsed and app-attributed drain are recorded for each device group; no release threshold is claimed until measured | Runner batterystats plus session export |
| Reboot night | Reboot phone and watch while DreamSync is On | Passive monitoring restores; cueing does not blindly resume; next session requires a fresh handshake | Runner reboot record and diagnostic export |
| Storage failure | Fill or revoke available storage in a controlled test | Cues fail closed and terminal state explains storage failure | Event export |
| UI | Font scaling and round-screen review | Phone On/Off, Export Debug Log, and Delete remain reachable; watch shows no settings and returns the user to phone controls | Screenshots |
| Recovery | Reconnect after interruption | Summary recovers once; next night starts with clean binding | Phone history and session IDs |

## Required sequence for each device group

1. Three accelerated bench runs with no detected duplicate playback.
2. Three sensor-only nights with cues disabled.
3. Seven consecutive baseline nights. Confirm the passive candidate threshold changes from the fallback to the learned resting baseline plus 5 BPM, capped at 75 BPM.
4. One no-cue comparison night.
5. One conservative cue night after audible watch calibration.
6. One disconnect/process-recovery run.
7. One reboot-night run.
8. Repeat any failed run after correction; keep both failed and repeated records.

## Record for each run

- Date/time, tester, phone model/build, watch model/build, app codes.
- Battery start/end and elapsed duration.
- Permissions, route, volume, watch fit, and sensor source.
- Signal freshness, coverage, observed sample rate, off-wrist events.
- Estimated onset, cue lifecycle, arousals, wake-after-sleep-onset estimate, and terminal result.
- Morning disturbance score, adverse effects, notes, and exported bundle checksum.
- Pass/fail plus defect link or corrective action.

## Claim gate

Passing one group supports only that exact model/software combination. Pixel, Galaxy, and third-party Wear OS groups must each pass before broad tested-device wording is used. This cannot prove clinical sleep staging or that DreamSync keeps users asleep. Those claims require PSG comparison and the planned controlled 12-20-night study.
