# DreamSync USB device lab

DreamSync uses two physical-test lanes. Neither lane proves EEG/AASM sleep staging,
clinical benefit, or support for a watch/software combination that has not passed the
hardware protocol.

## Connection model

- Phone: USB ADB.
- Galaxy Watch Ultra: paired wireless ADB. Its charging puck is not assumed to carry
  USB data.
- Keep the phone and watch normally paired through Galaxy Wearable/Bluetooth while
  ADB is connected separately to the development computer.

Enable phone USB debugging. On the watch, enable Developer options, ADB debugging,
Wireless debugging, and **Turn off automatic Wi-Fi**. Pair once with the pairing port
and reconnect later with the separate connection port:

```powershell
.\tools\Connect-DreamSyncDevices.ps1 `
  -PairAddress 'WATCH_IP:PAIRING_PORT' `
  -PairingCode 'ONE_TIME_CODE' `
  -WatchAddress 'WATCH_IP:CONNECTION_PORT'
```

The pairing code is never stored. Later:

```powershell
.\tools\Connect-DreamSyncDevices.ps1 -WatchAddress 'WATCH_IP:CONNECTION_PORT'
```

Use `-ListOnly` for a read-only device inventory.

## Lanes

### Debug lane

`com.remote.DreamSync.debug` is labeled **DreamSync Dev** and coexists with the
Play app. Build both APKs, connect both devices, then run:

```powershell
.\gradlew.bat :phone:assembleDebug :wear:verifySamsungSensorSdk :wear:assembleDebug
.\tools\Start-DreamSyncDeviceTest.ps1 `
  -Lane Debug `
  -Scenario watch-system-check `
  -Install `
  -DurationMinutes 5
```

Both debug apps must be built on the same machine/signing identity. Debug Samsung
sensor results are diagnostic only; package/signature policy must be validated with
the Play lane.

### Play lane

Install phone `69` and Wear OS `70` (`1.3.18-test`) from Internal testing. The runner
will not sideload or replace them:

```powershell
.\tools\Start-DreamSyncDeviceTest.ps1 `
  -Lane Play `
  -Scenario samsung-policy `
  -DurationMinutes 5 `
  -PullPlayExport
```

Use **Export Debug Log** on the phone during the capture and save it to Downloads.
Export requests a fresh, bounded, authenticated watch snapshot before opening the
document picker.

## Safety gates

The default scenarios are awake and non-destructive. These require
`-RequireDestructiveApproval`:

- `process-death`
- `reboot`
- `power-saving`
- `sensors-off`

`controlled-cue` separately requires `-RequireControlledCueApproval`.
`overnight-sensor-only` captures sensing and battery evidence without authorizing
experimental cues.

## Evidence

Default output:

`Documents\AI Brain\Dream Sync\hardware-validation\<UTC-date>\<run-id>\`

Each run contains raw phone/watch captures, exports, screenshots, a merged timeline,
classified findings, a sanitized manifest, and SHA-256 checksums. Serial numbers are
represented only by 16-character SHA-256 prefixes in manifests and summaries.

States:

- `PASS`: required positive evidence exists.
- `EXPECTED_BLOCK`: a safety condition correctly prevented cueing.
- `FAIL`: contrary evidence exists.
- `NO_EVIDENCE`: the capture cannot establish the result.

Sensor registration alone is never a pass. Motion requires at least five valid
callbacks from one source; heart rate requires at least one accepted reading.

## Common recovery

- Watch absent: confirm the connection port, not the pairing port, then run
  `adb connect WATCH_IP:PORT`.
- Network isolation: put the computer and watch on the same private hotspot.
- Global sensor silence: disable developer **Sensors Off**, disable Power saving,
  wear the watch snugly, and repeat the failed run without deleting the first record.
- Mixed app lanes: install matching Debug apps on both devices or matching Play apps
  on both devices.
