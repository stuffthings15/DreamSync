# DreamSync — Current Context

## Product

DreamSync is an Android phone and Wear OS sleep-cue research prototype published
privately under `com.remote.DreamSync`. It is not a medical device. Wrist signals
produce probable control states only; they are not EEG/AASM sleep staging.

## Current release

- Version: `1.3.19-test`
- Phone version code: 71
- Wear OS version code: 72
- Google Play: Internal testing only
- Source: Git worktree at `.codex\worktrees\76c2\DreamSync`

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

Historical physical logs confirmed watch audio, vibration, passive monitoring, and
sensor registration, but also showed zero motion callbacks and heart rate remaining
`ACQUIRING`. Version `1.3.19-test` moves Samsung tracker listener registration onto a
dedicated handler thread, matching Samsung's sample pattern. Phone code 71 and Wear
code 72 are available to Internal testers and were clean-installed from Google Play
on the S23+ and Watch Ultra with matching Play signer
`2e94c57bd8cbe3f5dcba40cc2bf4883a01804970ca6dd45cf07ca76f8e76f37a`.

The release is not hardware-validated yet. Watch health/activity permissions still
need the normal first-run grant flow. Two consecutive awake checks with changing
motion and heart values, the named fault checks, an overnight sensor-only run, and a
later controlled-cue test remain required. A first capture was stopped before
screenshots because the phone was in unrelated private use; no private screenshot was
saved. The runner now skips screenshots unless DreamSync is foreground.
