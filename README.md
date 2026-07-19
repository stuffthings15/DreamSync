# Dream Sync

Research prototype pairing a **Wear OS watch app** (sleep staging from motion + heart rate) with a **phone app** (low-gain audio cue playback).

- **Deep cues** — paired 50 ms pink-noise bursts, delivered in blocks during probable early-night NREM.
- **Lucid cues** — a two-tone chime (660/880 Hz) delivered during probable late-night REM.
- The watch estimates *probable* sleep states from accelerometer stillness and heart-rate features. It does **not** measure EEG and does not diagnose sleep stages.

## Safety gating (watch side)

- Cues only fire after a stage has been stable for a configured number of minutes, inside configured time windows.
- Any arousal (movement or heart-rate spike) ends the current cue block and starts a backoff.
- An arousal within 20 s of a cue counts as *cue-linked*: gain drops 30%; three cue-linked arousals disable all cues for the night.
- Gain is clamped to 0.5–20% on both devices.

## Modules

| Module | Role |
|---|---|
| `phone/` | Config UI, cue audio playback (foreground media service), watch remote-launch, session summary display |
| `wear/`  | Sensor collection, stage estimation, cue gating/dispatch, CSV session logs (`files/sessions/`) |

Both share the message paths in `Protocol.java` (`/somnicue/config`, `/somnicue/stop`, `/somnicue/deep_cue`, `/somnicue/lucid_cue`, `/somnicue/summary`).

## Build

Requirements: JDK 17+, Android SDK (platform 36). Copy `local.properties.example` to `local.properties` and set `sdk.dir`.

```
./gradlew :phone:assembleDebug :wear:assembleDebug
```

Or use `build-local.ps1` (Windows) / `build-local.sh`.

## Install

Only the **phone APK** needs to leave this machine — it embeds the watch APK and can deploy it itself.

1. Copy `phone/build/outputs/apk/debug/phone-debug.apk` to the phone (USB, cloud drive, `adb install`, …) and install it (allow "install unknown apps" for the file manager if asked).
2. On the watch: Settings → System → About → tap Build number 7× to unlock Developer options, then enable **ADB debugging** and **Debug over Wi-Fi**. Note the `IP:port` it shows.
3. In the phone app, under **Deploy watch app (Wi-Fi ADB)**, enter that address and tap **Install watch app over Wi-Fi**. Accept the debugging prompt on the watch (tick "always allow"). Both devices must be on the same Wi-Fi network.

Limitations: watches that only offer pairing-code wireless debugging (e.g. Pixel Watch) can't use in-app deploy — install those once from a PC with `adb pair` / `adb connect` / `adb install wear/build/outputs/apk/debug/wear-debug.apk`. Some routers isolate wireless clients from each other; if the connection times out, check AP/client isolation.

## Nightly use

1. Phone: pick protocol, set gain, **Test deep cue** while lying down — calibrate to barely audible, then one step lower.
2. Phone: **Arm phone audio + send settings**.
3. Watch: open Dream Sync (or use **Open Dream Sync on watch**), grant sensor permissions, **Start night session**.
4. Keep the phone 50–100 cm from the pillow, media volume low.
5. Morning: the watch pushes a summary to the phone; the CSV log stays on the watch.

Use the **accelerated test run** checkbox for a quick end-to-end test while awake — never sleep with it enabled.
