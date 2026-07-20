# DreamSync

**Dreams Redefined.**

DreamSync is an Android and Wear OS wellness app that plays low-volume sleep cues only when a paired watch detects conservative cue-ready windows from motion and heart-rate signals. Protecting sleep always takes priority over delivering a cue.

DreamSync does not measure EEG, diagnose sleep, treat a medical condition, guarantee deeper sleep, or guarantee lucid dreams. Session data stays on the user's devices unless the user chooses Export.

## Mission

DreamSync's mission is to protect sleep continuity by automatically estimating likely sleep from wearable signals, staying quiet when confidence is low, and using gentle, research-shaped cues only when strict safety checks indicate a low risk of disturbance.

## Watch compatibility

DreamSync is designed for Samsung Galaxy Watch4 and later models running a compatible Wear OS version, including Watch4/4 Classic, Watch5/5 Pro, Watch6/6 Classic, Watch7, Watch8/8 Classic, Watch FE, and Galaxy Watch Ultra (2024/2025). Cueing requires usable heart-rate and motion signals and the required permissions. Google Play device compatibility and sensor availability can vary by watch software. Galaxy Fit and older Samsung Tizen watches are not supported. Apple Watch support is planned, not available in this release.

## Automatic sleep estimation

Tap **Arm for tonight** once on the phone. DreamSync sends fresh, time-limited settings, opens the watch, and starts approved foreground sensing after any first-use permission prompts. The watch then waits for 10 sustained minutes of valid low-motion and fresh heart-rate signals, estimates likely sleep onset, and anchors all cue windows to that estimate. No cue is allowed before estimated onset and the later stability gates. Motion-only sessions log data but send no cues.

Android does not allow DreamSync to silently start health sensing every night. The one-tap arm action preserves consent, makes sensing visible, and avoids an unsafe background restart.

## Research basis

- Deep cues use two 50 ms pink-noise bursts separated by 1.075 seconds, matching the acoustic pattern in Besedovsky et al. (2017).
- The watch waits for an estimated sleep onset, sustained stillness, usable sensor coverage, and an early-night NREM-eligible window.
- If sleep is not estimated within three hours of arming, the session ends without sending cues. Once sleep is estimated, the normal session clock starts from that point rather than from the arm tap.
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
- Version: 1.0.0
- Privacy policy and store copy are in `docs/play-store/`.

Copyright 2026 Remote. All rights reserved.
