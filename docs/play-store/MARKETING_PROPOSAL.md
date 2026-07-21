# DreamSync science-based Google Play marketing proposal

## Positioning

DreamSync is an experimental sleep-first cue companion for adults using a compatible Wear OS watch and Android phone.

Slogan: **Dreams Redefined.**

Mission: **Designed to protect sleep continuity.** DreamSync automatically estimates likely sleep from wearable signals and stays quiet whenever confidence, signal quality, or recovery is uncertain.

## Primary messages

- One persistent On/Off control; supported watches look for likely sleep automatically every night while On.
- Protecting sleep takes priority over delivering a cue.
- The first cue-linked wake sign stops cueing for the night.
- A rolling seven-night baseline personalizes estimates; earlier nights use conservative defaults.
- Core watches use standard Wear OS motion and heart rate. High-end watches keep available approved IBI/HRV, skin-temperature, off-wrist, and raw-PPG features; raw PPG runs only in short cue-quality windows.
- Users can view estimated awakenings, wake after sleep onset, longest uninterrupted bout, cue-linked arousal rate, and morning disturbance.
- Pre-sleep coaching is separated from in-sleep monitoring.
- After three records, a local reminder asks the user to charge the watch three hours before the learned usual sleep time.
- Data stays local unless the user exports it.

## Device message

Core compatibility targets Android 8.0+ phones with Google Play services and Wear OS 3+ watches that pass the in-app motion, heart-rate, permission, software, and companion-link checks. Eligible Samsung Galaxy Watch and Google Pixel Watch models can enter the core profile. First physical validation still targets Galaxy Watch Ultra 2024 and 2025.

The app shows Enhanced protection, Core protection, Monitoring only, Phone protection only, or Needs attention. Profiles depend on live capabilities, not brand: motion, heart rate, watch speaker, optional IBI and temperature, off-wrist status, required permissions, an approved advanced provider, and live self-tests.

Apple Watch, Fitbit, Garmin, Galaxy Fit, and older Samsung Tizen watches are not supported in the Android release. Never describe an untested model as physically validated.

## Approved claims

- Plays a versioned paired 50 ms pink-noise pattern, 1.075 seconds apart, with 5 ms fades.
- Estimates likely sleep and conservative cue-ready windows from wrist signals.
- Designed to protect sleep continuity by staying quiet when safety checks are uncertain.
- Applies signal-quality, arousal, route, battery, connection, volume, off-wrist, and storage gates.
- Stores data locally with export and deletion controls.
- Reports estimated continuity measures and user-entered morning ratings.

## Laboratory findings that require attribution

- Besedovsky et al. used an EEG phase-locked laboratory protocol in 14 healthy young men.
- Ngo et al. reported slow-oscillation and memory findings under EEG control.
- Weigenand et al. found that open-loop timing did not improve word-pair memory.
- These findings belong to the cited methods, not to DreamSync.

## Prohibited claims

- Keeps the user asleep or guarantees uninterrupted sleep.
- Clinically detects sleep stages or phase-locks to EEG.
- Treats insomnia, sleep apnea, depression, or another condition.
- Improves memory, immunity, or hormones.
- Is physically validated on a watch that has not completed documented testing.

## Store-page structure

1. Lead with the slogan, mission, sleep-first behavior, and privacy.
2. Explain persistent On/Off, one-time permissions, the ongoing phone status notification, and automatic likely-sleep detection.
3. Explain continuity safeguards and user-facing measures.
4. State the research-shaped acoustic pattern.
5. Immediately explain the EEG-versus-wrist limitation.
6. Give compatibility status and validation order.
7. End with adult-wellness and medical boundaries.

## Screenshot messages

- Dreams Redefined.
- Turn On once. Automatic likely-sleep detection every night.
- Charge reminder learned from your own sleep-start records.
- A wake sign stops cueing for the night.
- Live status: Enhanced, Core, Monitoring only, Phone only, or Needs attention.
- Seven-night baseline and private continuity history.
- Science and limitations explained inside the app.

## Peer-reviewed sources

1. Besedovsky L, Ngo HVV, Dimitrov S, et al. *Nature Communications*. 2017;8:1984. DOI: 10.1038/s41467-017-02170-3.
2. Ngo HVV, Martinetz T, Born J, Molle M. *Neuron*. 2013;78:545-553. DOI: 10.1016/j.neuron.2013.03.006.
3. Ngo HVV, et al. *Journal of Neuroscience*. 2015;35:6630-6638. DOI: 10.1523/JNEUROSCI.3133-14.2015.
4. Weigenand A, Molle M, Werner F, Martinetz T, Marshall L. *European Journal of Neuroscience*. 2016;44:2357-2368. DOI: 10.1111/ejn.13334.

Feature-to-source detail is maintained in `docs/RESEARCH_EVIDENCE_MATRIX.md`.
