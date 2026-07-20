# DreamSync

**Dreams Redefined.**

DreamSync is an Android and Wear OS wellness app that plays low-volume sleep cues only when a paired watch detects conservative cue-ready windows from motion and heart-rate signals. Protecting sleep always takes priority over delivering a cue.

DreamSync does not measure EEG, diagnose sleep, treat a medical condition, guarantee deeper sleep, or guarantee lucid dreams. Session data stays on the user's devices unless the user chooses Export.

## Mission

DreamSync's mission is to protect sleep continuity by automatically estimating likely sleep from wearable signals, staying quiet when confidence is low, and using gentle, research-shaped cues only when strict safety checks indicate a low risk of disturbance.

## Watch compatibility

DreamSync targets Android 8.0+ phones with Google Play services and Wear OS 3+ watches. The watch must pass live motion, heart-rate, software, permission, and companion-link checks. Core watches use standard Wear OS signals. High-end watches are not downgraded: every approved advanced signal reported by the device is enabled, including IBI/HRV, skin temperature, off-wrist status, and raw PPG when the sensor provider supports them. Galaxy Watch4 and later models can use the optional Samsung sensor layer after its package/signing approval. Apple Watch, Fitbit, Garmin, Galaxy Fit, and older Samsung Tizen watches are not supported in this Android release.

## Automatic sleep estimation

Turn **DreamSync On** once on the phone. Approve the watch health permissions once. While On, Wear Health Services watches for a supported asleep-state signal every night. Watches without that optional state can use a conservative passive-heart-rate candidate inside the normal sleep window. That candidate only starts higher-rate motion and heart-rate confirmation; it never authorizes a cue by itself. Motion-only sessions log data but send no cues.

Install the phone app first. **Set up watch app** finds a connected Wear OS watch. If DreamSync is missing there, the phone opens DreamSync's official Play Store page on the watch. Tap **Install** once; the phone then detects, binds, and configures the watch automatically. Google Play requires that install confirmation, so Android apps cannot silently install the watch app in the background. The watch has no settings; opening it returns to the phone controls.

The first Turn On runs a quiet phone-speaker sound check. DreamSync continues only after playback is confirmed. If it fails, the phone shows the exact reason. **Export debug log** saves a bounded technical report with app version, audio state, watch binding flags, and app-owned events; it excludes sleep-session content and raw device-node identifiers.

The phone shows a low-priority ongoing notification while DreamSync is On. The watch registration persists independently of the app screen. Reboot receivers restore the On state. Turning DreamSync Off unregisters passive monitoring, stops the current session, and disables all cues. If required passive data, permissions, or the phone-watch link are unavailable, DreamSync fails closed and sends no cue.

Charge the watch several hours before normal sleep time. After three completed records, DreamSync learns the median usual sleep start and schedules a phone reminder three hours earlier.

## Research basis

- Deep cues use two 50 ms pink-noise bursts separated by 1.075 seconds, matching the acoustic pattern in Besedovsky et al. (2017).
- The watch waits for an estimated sleep onset, sustained stillness, usable sensor coverage, and an early-night NREM-eligible window.
- After the passive asleep signal, DreamSync allows 30 minutes for sustained signal confirmation. If confirmation fails, the session ends without cues and persistent On mode remains ready for the next sleep period.
- Cueing pauses for 20 minutes after any arousal sign and stops for the night after the first cue-linked arousal.
- DreamSync cannot reproduce the paper's EEG phase locking. It therefore uses conservative eligibility labels and makes no sleep-stage or treatment claim.
- Lucid cues are a separate optional feature and require both reliable motion and cardiac data.

Source: Besedovsky et al., *Nature Communications* 8, 1984 (2017), DOI `10.1038/s41467-017-02170-3`. The supplied article is preserved at `docs/sleep study data.docx`.

Related peer-reviewed evidence and limits:

- Ngo et al., *Neuron* 78, 545-553 (2013), DOI `10.1016/j.neuron.2013.03.006`.
- Ngo et al., *Journal of Neuroscience* 35, 6630-6638 (2015), DOI `10.1523/JNEUROSCI.3133-14.2015`.
- Weigenand et al., *European Journal of Neuroscience* 44, 2357-2368 (2016), DOI `10.1111/ejn.13334`. Its open-loop null result is why DreamSync makes no memory claim.

## Safety and reliability

- One session binds one reachable phone and watch.
- Invalid, duplicate, expired, wrong-session, and wrong-device commands fail closed.
- A cue counts only after phone audio starts.
- Cues stop for stale sensors, low battery, storage failure, phone loss, unsupported audio routes, or the first cue-linked arousal.
- A fresh phone-watch handshake is required after interruption.

## Build

Requires JDK 17 and Android SDK platform 36.

```powershell
$env:JAVA_HOME='C:\Users\stuff\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
.\gradlew.bat clean :shared:test :phone:assembleDebug :wear:assembleDebug :phone:bundleRelease :wear:bundleRelease
```

## Publishing

- Package: `com.remote.DreamSync`
- Publisher: Remote
- Product name: DreamSync
- Test version: phone 19 / Wear OS 20 (`1.2.2-test`)
- Privacy policy and store copy are in `docs/play-store/`.

Copyright 2026 Remote. All rights reserved.
