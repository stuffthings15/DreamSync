# DreamSync validation results

Date: 19 July 2026

## Verified locally

- Public name: DreamSync.
- Publisher: Remote.
- Package: `com.remote.DreamSync`.
- Phone and Wear OS debug builds pass.
- Phone and Wear OS signed release bundles pass.
- Shared safety checks pass: 9 checks, 0 failures.
- Phone app installs and launches on Android 16.
- Onboarding, disclosure, main screen, export, deletion, and privacy controls are present.
- Phone and watch use a single session-bound protocol with duplicate, expiry, version, device, and session rejection.
- Health data stays local unless the user exports it.
- One valid cue request can produce at most one playback. Invalid requests produce none.
- Protecting sleep takes priority: any arousal starts a 20-minute quiet period and the first cue-linked arousal disables further cues for the night.

## Research alignment

- Deep-sleep cue: two 50 ms pink-noise bursts.
- Pair interval: 1.075 seconds.
- Fade: 5 ms.
- Cue window: up to 120 minutes after estimated sleep onset.
- Cueing stops when motion, heart-rate quality, connection, route, battery, or arousal safety gates fail.
- User gain is capped at 10%; the default is 4%.
- DreamSync estimates cue-ready windows from wrist signals. It does not measure EEG or provide medical care.

## Still required

- Physical S23+ and Watch Ultra sensor/audio validation.
- Play Console app-information forms.
- Public privacy-policy access.
- Google Play closed-release gate: 12 opted-in users for 14 continuous days.
