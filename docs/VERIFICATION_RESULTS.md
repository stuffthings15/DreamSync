# DreamSync validation results

Date: 19 July 2026

## Verified locally

- Public name: DreamSync.
- Publisher: Remote.
- Package: `com.remote.DreamSync`.
- Phone release code: 9 (`1.0.0`).
- Official slogan and mission are consistent across the app and Play marketing copy.
- Public compatibility copy names Samsung Galaxy Watch4 and later Wear OS models, requires usable heart-rate and motion signals, and marks Apple Watch as planned.
- Live Google Play short and full descriptions are saved with the official slogan and mission.
- Phone and Wear OS debug builds pass.
- Phone and Wear OS signed release bundles pass.
- Shared safety and protocol checks pass: 12 checks, 0 failures.
- Phone and Wear OS Android lint checks pass with no blocking errors.
- Phone app installs and launches on Android 16.
- Onboarding, disclosure, main screen, research explanation, export, deletion, and privacy controls are present.
- Phone and watch use a single session-bound protocol with duplicate, expiry, version, device, and session rejection.
- Health data stays local unless the user exports it.
- One valid cue request can produce at most one playback. Invalid requests produce none.
- Protecting sleep takes priority: any arousal starts a 20-minute quiet period and the first cue-linked arousal disables further cues for the night.
- The 10% gain ceiling is enforced in the interface, watch configuration, and phone playback service.
- Conservative sleep-first fallbacks are enforced even when a saved configuration is missing.
- One phone tap creates fresh, six-hour settings and targets the bound watch only.
- The watch waits until its Activity is resumed before starting the visible health foreground service.
- Likely sleep onset is estimated only after 10 sustained minutes of valid low motion plus fresh heart-rate data.
- Motion-only sessions log data but cannot trigger deep or lucid cues.
- Cue timing starts from estimated onset, not the arm tap; no estimate within three hours ends the session without cues.
- Stale, reordered, stopped-session, and wrong-session arm flows fail closed.
- A 10-minute phone watchdog shuts the audio service down unless a live watch heartbeat arrives.
- Pending Arm, double-Arm, delayed ACK, and Stop races are generation-guarded.

## Research alignment

- Deep-sleep cue: two 50 ms pink-noise bursts.
- Pair interval: 1.075 seconds.
- Fade: 5 ms.
- Cue window: 25 to 145 minutes after estimated sleep onset.
- Cueing stops when motion, heart-rate quality, connection, route, battery, or arousal safety gates fail.
- User gain is capped at 10%; the default is 4%.
- DreamSync estimates cue-ready windows from wrist signals. It does not measure EEG or provide medical care.
- The in-app research screen maps each design choice to four peer-reviewed studies and states the evidence limits.
- Public marketing avoids claims of deeper sleep, memory improvement, biological effects, or guaranteed lucid dreams.

## Still required

- Physical S23+ and Watch Ultra sensor/audio validation.
- Physical verification of the phone-to-bound-watch automatic launch and first-use permission flow.
- Play Console graphics upload and release-asset submission.
- Google Play closed-release gate: 12 opted-in users for 14 continuous days.
