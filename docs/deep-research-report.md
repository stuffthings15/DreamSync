# DreamSync sleep-continuity research report

Last verified: 19 July 2026

## Executive finding

DreamSync should not promise to force deep sleep or keep a person asleep. Normal sleep cycles through wake, NREM, and REM. A wrist device does not measure EEG and cannot reproduce laboratory slow-oscillation phase locking.

The defensible product goal is:

> **Dreams Redefined. Designed to protect sleep continuity.**

This is a design goal, not a guaranteed health outcome. DreamSync should reduce avoidable disturbance, estimate likely sleep conservatively, and stop cueing whenever the risk of waking is unclear.

## Evidence-to-product conclusions

### 1. Protect continuity before attempting stimulation

The strongest practical sleep support happens before sleep: regular scheduling, appropriate daylight and exercise, a warm bath or shower before bed, and relaxation. CBT-I remains first-line care for chronic insomnia, but DreamSync is not a CBT-I treatment and should refer users with persistent problems to qualified care.

During likely sleep, the application should keep screens off, avoid haptics, limit network traffic, and apply conservative audio gates. Any possible arousal should start a 20-minute quiet period. The first cue-linked wake signal should stop cues for the night and lower subsequent gain.

Primary continuity measures are estimated wake after sleep onset (WASO), awakening count, longest uninterrupted sleep bout, cue-linked arousal rate, and morning disturbance rating. These are estimates unless validated against polysomnography.

Sources: [ACP insomnia guideline](https://doi.org/10.7326/M15-2175), [AASM behavioral-treatment guideline](https://doi.org/10.5664/jcsm.8986), [passive body-heating systematic review](https://doi.org/10.1016/j.smrv.2018.11.008).

### 2. Automatic detection must remain conservative

One visible nightly Arm action is required. After arming, DreamSync can automatically move through:

`Armed -> Preparing for sleep -> Likely asleep -> Stable sleep -> Possible arousal -> Recovered or stopped`

The estimator should combine sustained stillness, heart-rate change, signal quality, time since arming, and time since estimated onset. When Samsung signals are available, it can additionally use IBI/HRV stability, skin-temperature trend, and off-wrist status. It must fail closed when motion or heart data is stale, the watch is off wrist, storage fails, the phone is lost, battery is low, or confidence is insufficient.

Night one uses conservative defaults. A rolling seven-night median baseline can personalize resting heart rate, IBI, RMSSD, skin temperature, and sleep-onset timing. Personalization does not convert wrist estimates into clinical sleep staging.

Android restricts foreground-service launches from the background, so silent unattended nightly starts are not a reliable or compliant design. [Android foreground-service launch rules](https://developer.android.com/develop/background-work/services/fgs/launch)

### 3. Samsung signals add useful context, not EEG

The Samsung Health Sensor SDK supports Galaxy Watch4 and later watches running Wear OS Powered by Samsung, subject to model, software, sensor, and permission availability. Continuous tracker documentation includes accelerometer, heart rate with IBI, skin temperature, and raw PPG. Skin temperature begins with Watch5-class hardware. Raw PPG is optional in DreamSync and remains off by default because it increases battery and permission cost.

Public distribution of an app using the SDK requires Samsung partnership approval plus registration of the package name and signing certificate. Until the official AAR and approval are supplied, DreamSync uses standard Wear OS accelerometer and heart-rate sensors and classifies the watch as basic monitoring only.

Official sources: [Samsung Health Sensor SDK overview](https://developer.samsung.com/health/sensor/overview.html), [introduction and compatibility](https://developer.samsung.com/health/sensor/guide/introduction.html), [getting started](https://developer.samsung.com/health/sensor/guide/getting-started.html), [data specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html), [partner process](https://developer.samsung.com/health/sensor/process.html), [app verification](https://developer.samsung.com/health/sensor/guide/app-verification.html).

### 4. Auditory cue research supports an experiment, not a promise

Laboratory studies used EEG to time brief auditory stimulation to slow oscillations. DreamSync cannot phase-lock because it has no EEG. Its paired 50 ms pink-noise pattern and 1.075-second interval are research-shaped acoustic parameters only. The app uses much smaller cue budgets, user-calibrated speaker gain, and arousal-triggered shutdown to prioritize continuity.

Open-loop or wrist-estimated cue timing must be described as experimental. No claim of deeper sleep, better memory, immune effects, or guaranteed lucid dreaming is supported for DreamSync.

Peer-reviewed sources: [Ngo et al., Neuron 2013](https://doi.org/10.1016/j.neuron.2013.03.006), [Ngo et al., Journal of Neuroscience 2015](https://doi.org/10.1523/JNEUROSCI.3133-14.2015), [Weigenand et al., European Journal of Neuroscience 2016](https://doi.org/10.1111/ejn.13334), [Besedovsky et al., Nature Communications 2017](https://doi.org/10.1038/s41467-017-02170-3).

## Implemented product policy

- One visible Arm action creates a fresh, expiring phone-watch session.
- Motion-only sessions may log but cannot cue.
- Lucid cues require fresh cardiac data.
- Wrong-device, wrong-session, expired, duplicate, reordered, and incompatible commands produce no playback.
- Cue allowance is consumed only after confirmed audio start.
- No haptics are used during likely or confirmed sleep.
- Arousal creates at least 20 minutes of silence.
- The first cue-linked arousal disables cues for the rest of the session.
- Speaker routing, mute, volume, focus, calibration, battery, storage, phone connection, and sensor freshness are checked.
- Configuration, events, cue lifecycle, signal quality, continuity metrics, summaries, surveys, and logs remain local unless the user exports them.
- Replay logic remains deterministic for the same inputs, versions, thresholds, and configuration.

## Compatibility policy

Compatibility is based on live capability checks, not the phrase “or better.”

| Status | Meaning |
|---|---|
| Fully supported | Samsung SDK is packaged and connected; motion, HR, IBI, skin temperature, off-wrist checks, permissions, and live self-test pass. |
| Basic monitoring only | Standard Wear OS motion and heart rate work, but advanced Samsung signals or validation are incomplete. |
| Not supported | Required Wear OS motion or heart-rate capability is absent. |

Validation order:

1. Galaxy Watch Ultra 2024 and 2025.
2. Galaxy Watch8, Watch8 Classic, and Watch7.
3. Watch4, Watch5, and Watch6 only after the in-app capability check and device testing pass.

Apple Watch, Pixel Watch, Fitbit, Garmin, Galaxy Fit, and older Samsung Tizen watches are not supported in the Android release. Hardware groups must not be marketed as tested until their validation record is complete.

## Validation gates

Local automated tests can prove decision rules, persistence behavior, protocol invariants, and build integrity. They cannot prove sleep benefit or hardware accuracy.

Before a public effectiveness claim:

1. Bench-test sensor coverage, audio timing, routing, and battery use.
2. Complete three sensor-only nights on the target phone and watch.
3. Compare baseline, active-cue, and no-cue nights.
4. Test disconnect, off-wrist, low battery, permission loss, route change, process death, and summary recovery.
5. Validate estimated onset, awakenings, and coarse stability against concurrent polysomnography.
6. Complete a controlled 12-20-night experiment before claiming reduced fragmentation.

Required reporting: failures included, source mapping, limitations, device/software versions, configuration and waveform versions, signal coverage, continuity metrics, adverse effects, and the recommended next experiment.

## Apple Watch roadmap

Keep the decision protocol, feature definitions, export schema, and replay cases platform-neutral. A future Apple implementation should be separate Swift iPhone and watchOS apps using HealthKit, Core Motion, and Watch Connectivity.

Begin with pre-sleep coaching and post-night analysis on Apple Watch Series 8 or later and Ultra models. Whole-night intervention needs a separate feasibility study because watchOS background execution and sensor access do not mirror the Android foreground health-service model. Apple support must remain roadmap language until a real watchOS prototype passes battery, sensor, background, and App Review tests.

## Public claim boundary

Allowed:

- “Dreams Redefined.”
- “Designed to protect sleep continuity.”
- “Automatically estimates likely sleep after you arm the night.”
- “Uses conservative wrist-signal checks and stops cues when disturbance risk rises.”
- “Experimental wellness software; not a medical device.”

Not allowed without future controlled evidence or regulatory work:

- Keeps you asleep.
- Guarantees uninterrupted or deeper sleep.
- Detects clinical sleep stages.
- Treats insomnia or sleep apnea.
- Improves memory, hormones, immunity, or lucid-dream frequency.
