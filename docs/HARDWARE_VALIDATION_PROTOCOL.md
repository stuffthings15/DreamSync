# DreamSync S23+ and Galaxy Watch Ultra validation protocol

Version under test: phone 13 / Wear OS 14 (`1.1.2-test`)

Primary devices: Samsung Galaxy S23+ and Galaxy Watch Ultra 2024 or 2025

Local tests prove decision logic and build integrity. This protocol gathers the device evidence needed before marketing a watch as physically validated or claiming a sleep effect.

## Before testing

1. Install both internal-test releases from Google Play using the approved tester account.
2. Charge both devices, enable Bluetooth, and confirm the watch is paired through the normal Samsung/Wear OS workflow.
3. Enable developer options and USB or wireless debugging only for the bench tests.
4. Run `tools/Run-DreamSyncHardwareValidation.ps1 -ListOnly` to confirm both serials.
5. Run the same tool with explicit `-PhoneSerial` and `-WatchSerial`. Add `-RunInstrumentation` for the Android workflow/migration tests.
6. Keep DreamSync in Basic monitoring status unless the official Samsung AAR and approvals are present.

The runner saves device serials, build fingerprints, package details, logs, and battery/audio/sensor diagnostics under `docs/hardware-validation/`. That folder is Git-ignored. Treat it as private test evidence, review it for personal data before sharing, and never upload it automatically.

## Acceptance matrix

| Area | Test | Pass condition | Evidence |
|---|---|---|---|
| Installation | Install phone and watch from their Play tester pages | Correct 1.1.2-test build opens on each device | Play screen plus collected package metadata |
| Pair binding | Arm one night from phone | One capability-matched watch is bound; no broadcast to other nodes | Export plus connection events |
| Permissions | Deny, grant, and revoke each required permission | Clear explanation; no cue when a required permission is missing | Screen capture and event export |
| Sensors | Wear watch normally for at least 30 minutes | Fresh motion and HR, usable sample rate/coverage, on-wrist state | Capability/signal export |
| Sleep onset | Three sensor-only nights | Onset is estimated only after sustained low motion and fresh HR | Session exports and morning notes |
| Audio timing | Bench cue at each allowed volume | Two 50 ms bursts, 1.075 seconds apart, no clipping; gain never exceeds 10% | Audio capture and waveform version |
| Audio route | Change media route, mute, headphones, and Bluetooth route | Arming/cueing blocks on unsafe or unknown routes | Event export and screen capture |
| Arousal safety | Move/wake after a cue | 20-minute quiet period; first cue-linked wake disables further cues | Cue/arousal lifecycle export |
| Duplicate safety | Reconnect/reorder around one cue request | At most one playback for one valid request; invalid requests play none | Phone/watch lifecycle logs |
| Phone loss | Disable phone Bluetooth or stop the phone app | Watch disables cues and records `PHONE_LOST` or an explicit interruption | Watch export and recovered phone summary |
| Process death | Force-stop phone, then watch, during controlled runs | No blind cue resume; fresh handshake required | Collected logs and session terminal state |
| Off-wrist | Remove the watch during a session | Cues stop; state explains off-wrist or stale signal | Watch event export |
| Low battery | Controlled low-battery run | Cues disable before unsafe battery threshold | Battery/event export |
| Storage failure | Fill or revoke available storage in a controlled test | Cues fail closed and terminal state explains storage failure | Event export |
| UI | Font scaling and round-screen review | No clipped controls; Stop and Delete remain reachable | Screenshots |
| Recovery | Reconnect after interruption | Summary recovers once; next night starts with clean binding | Phone history and session IDs |

## Required night sequence

1. Three accelerated bench runs with no detected duplicate playback.
2. Three sensor-only nights with cues disabled.
3. One no-cue baseline night.
4. One conservative cue night.
5. One disconnect/process-recovery run.
6. Repeat any failed run after the cause is corrected; keep the failed record.

## Record for each run

- Date/time, tester, phone model/build, watch model/build, app codes.
- Battery start/end and elapsed duration.
- Permissions, route, volume, watch fit, and sensor source.
- Signal freshness, coverage, observed sample rate, off-wrist events.
- Estimated onset, cue lifecycle, arousals, wake-after-sleep-onset estimate, and terminal result.
- Morning disturbance score, adverse effects, notes, and exported bundle checksum.
- Pass/fail plus defect link or corrective action.

## Claim gate

Passing this protocol can support wording such as “tested on Galaxy Watch Ultra” for the tested model/software combination. It cannot prove clinical sleep staging or that DreamSync keeps users asleep. Those claims require PSG comparison and the planned controlled 12-20-night study.
