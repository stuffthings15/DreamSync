# DreamSync

**Dreams Redefined.**

DreamSync is an Android and Wear OS wellness app built to protect sleep continuity. It automatically watches for likely sleep, stays quiet by default, and permits low-volume experimental watch sound only after a personal baseline and strict safety checks. Protecting sleep always takes priority over delivering sound.

DreamSync does not measure EEG, diagnose sleep, treat a medical condition, guarantee deeper sleep, or guarantee uninterrupted sleep. Session data stays on the user's devices unless the user chooses Export.

## Mission

DreamSync's mission is to protect sleep continuity by automatically estimating likely sleep from wearable signals and staying quiet whenever confidence, signal quality, or recovery is uncertain.

## Watch compatibility

DreamSync targets Android 8.0+ phones with Google Play services and Wear OS 3+ watches. The watch must pass live motion, heart-rate, speaker, software, permission, and companion-link checks. The app reports Enhanced protection, Core protection, Monitoring only, Phone protection only, or Needs attention. High-end watches are not downgraded: approved IBI/HRV, skin temperature, off-wrist, and raw-PPG features remain available when verified; raw PPG runs only in short cue-quality windows. Galaxy Watch4 and later models can use the optional Samsung sensor layer after its package/signing approval. Apple Watch, Fitbit, Garmin, Galaxy Fit, and older Samsung Tizen watches are not supported in this Android release.

## Automatic sleep estimation

Turn **DreamSync On** once on the phone. Approve the watch health permissions once. While On, Wear Health Services watches for a supported asleep-state signal every night. Watches without that optional state can use a conservative passive-heart-rate candidate inside the normal sleep window. That candidate only starts higher-rate motion and heart-rate confirmation; it never authorizes a cue by itself. Motion-only sessions log data but send no cues.

Install the phone app first. **Set up watch app** finds a connected Wear OS watch. If DreamSync is missing there, the phone opens DreamSync's official Play Store page on the watch. Tap **Install** once; the phone then detects, binds, and configures the watch automatically. Google Play requires that install confirmation, so Android apps cannot silently install the watch app in the background. The watch has no settings; opening it returns to the phone controls.

Turn On does not depend on phone-speaker calibration. The built-in watch speaker is the primary experimental output. Phone fallback is Off unless the user explicitly enables it. **Run watch system check** plays an awake watch cue, sends a short test vibration, tries accelerometer/linear-acceleration/gravity motion paths, uses Wear Health Services MeasureClient for live heart rate, and checks every available advanced sensor. The phone always opens a result report. A missing result fails after two minutes. **Export debug log** saves sensor inventory, every registration attempt, heart availability and readings, 5/15/30-second progress, audio route and lifecycle, the final payload, and report display. Raw device-node identifiers are excluded.

The pipeline check records configuration delivery, passive sleep monitoring, passive heart-rate or asleep-state inputs, fresh-session handshake, active sensor registration and readings, quality and safety decisions, cue delivery, watch-primary and phone-fallback audio lifecycles, the user-run live system check, phone report display, and terminal summaries. Every blocked action includes its reason. Sleep haptics remain disabled.

The phone shows a low-priority ongoing notification while DreamSync is On. The watch registration persists independently of the app screen. After reboot, a durable background worker restores passive watch registration; cueing never resumes without a fresh handshake. Turning DreamSync Off unregisters passive monitoring, stops the current session, and disables all sound. If required passive data, permissions, or the phone-watch link are unavailable, DreamSync fails closed and stays quiet.

Charge the watch several hours before normal sleep time. After three completed records, DreamSync learns the median usual sleep start and schedules a phone reminder three hours earlier.

## Research basis

- Protect only is the default and plays no sleep sound.
- Experimental sound remains locked until seven valid baseline nights are recorded.
- The experimental waveform uses two 50 ms pink-noise bursts separated by 1.075 seconds, matching the acoustic pattern in Besedovsky et al. (2017).
- The watch waits for estimated sleep onset, sustained stillness, usable sensor coverage, and an early-night NREM-eligible window.
- After the passive asleep signal, DreamSync allows 30 minutes for sustained signal confirmation. If confirmation fails, the session ends without cues and persistent On mode remains ready for the next sleep period.
- The first eligible experimental night allows one pair. The allowance can rise to two, then four only after consecutive low-disturbance nights. Each pair is followed by five observation minutes.
- Sound pauses for 20 minutes after any arousal sign and stops for the night after the first cue-linked arousal. The next gain is lowered by 30%.
- DreamSync cannot reproduce the paper's EEG phase locking. It therefore uses conservative eligibility labels and makes no sleep-stage or treatment claim.

Source: Besedovsky et al., *Nature Communications* 8, 1984 (2017), DOI `10.1038/s41467-017-02170-3`. The supplied article is preserved at `docs/sleep study data.docx`.

Related peer-reviewed evidence and limits:

- Ngo et al., *Neuron* 78, 545-553 (2013), DOI `10.1016/j.neuron.2013.03.006`.
- Ngo et al., *Journal of Neuroscience* 35, 6630-6638 (2015), DOI `10.1523/JNEUROSCI.3133-14.2015`.
- Weigenand et al., *European Journal of Neuroscience* 44, 2357-2368 (2016), DOI `10.1111/ejn.13334`. Its open-loop null result is why DreamSync makes no memory claim.

## Safety and reliability

- One session binds one reachable phone and watch.
- Invalid, duplicate, expired, wrong-session, and wrong-device commands fail closed.
- A cue counts only after watch audio starts.
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
- Test version: phone 33 / Wear OS 34 (`1.3.0-test`)
- Privacy policy and store copy are in `docs/play-store/`.

Copyright 2026 Remote. All rights reserved.
