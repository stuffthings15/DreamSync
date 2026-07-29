# DreamSync — Current Context

## Product

DreamSync is an Android phone and Wear OS sleep-cue research prototype published
privately under `com.remote.DreamSync`. It is not a medical device. Wrist signals
produce probable control states only; they are not EEG/AASM sleep staging.

## Current release

- Version: `1.3.18-test`
- Phone version code: 69
- Wear OS version code: 70
- Google Play: Internal testing only
- Source: Git repository at `Documents\DreamSync`

## Physical test system

- Phone: Galaxy S23+ over USB ADB.
- Watch: Galaxy Watch Ultra over paired wireless ADB.
- Debug lane: `com.remote.DreamSync.debug`, label `DreamSync Dev`.
- Release lane: Play-signed `com.remote.DreamSync`; authoritative for Samsung
  package/signature behavior.
- Commands: `tools\Connect-DreamSyncDevices.ps1`,
  `tools\Start-DreamSyncDeviceTest.ps1`, and
  `tools\Analyze-DreamSyncDeviceTest.ps1`.
- Raw evidence: `AI Brain\Dream Sync\hardware-validation\`.

## Current hardware boundary

Historical physical logs confirmed watch audio, vibration, permissions, passive
monitoring, and sensor registration, but also showed zero motion callbacks and heart
rate remaining `ACQUIRING`. Later code serialized sensor ownership, expanded fallback
sources, and added Samsung SDK diagnostics. Changing Galaxy Watch Ultra motion and
heart values and Samsung policy success still require fresh physical evidence.
