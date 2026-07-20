# DreamSync Publishing Status

## Prepared

- Public product name and publisher naming.
- Official slogan: Dreams Redefined.
- Official mission centered on automatic likely-sleep estimation, sleep continuity, and fail-closed cue safety.
- Broad capability-based Android 8.0+/Wear OS 3+ compatibility copy, with full-sensor features preserved on capable watches, Galaxy Watch Ultra first in physical validation, and Apple Watch clearly marked as planned.
- Version 1.2.4-test with unique phone and Wear OS version codes published to internal testing.
- Consumer interface with sleep-cue settings only.
- Health permission disclosure before the watch permission prompt.
- In-app privacy and wellness disclosures.
- Privacy policy source and deployable website.
- Store listing and Play declaration answers.
- Local export and deletion controls.
- Slogan feature graphic at `assets/dreamsync-feature-graphic-slogan-1024x500.png`.
- Current phone and tablet screenshots captured from version code 9.
- Live Google Play short and full descriptions saved with the official slogan and mission.

## Internal testing

- Phone version code 9 (`1.0.0`) published on 2026-07-19.
- Wear OS version code 7 (`1.0.0`) published on 2026-07-19.
- Phone version code 10 (`1.1.0-test`) published on 2026-07-19.
- Wear OS version code 8 (`1.1.0-test`) published on 2026-07-19.
- Phone version code 11 (`1.1.1-test`) published on 2026-07-19.
- Wear OS version code 12 (`1.1.1-test`) published on 2026-07-19.
- Phone version code 13 (`1.1.2-test`) published on 2026-07-19.
- Wear OS version code 14 (`1.1.2-test`) published on 2026-07-19.
- Phone version code 15 (`1.2.0-test`) published on 2026-07-19.
- Wear OS version code 16 (`1.2.0-test`) published on 2026-07-19.
- Phone version code 17 (`1.2.1-test`) published on 2026-07-19.
- Wear OS version code 18 (`1.2.1-test`) published on 2026-07-19.
- Phone version code 19 (`1.2.2-test`) published on 2026-07-19.
- Wear OS version code 20 (`1.2.2-test`) published on 2026-07-19.
- Phone version code 21 (`1.2.3-test`) published on 2026-07-20.
- Wear OS version code 22 (`1.2.3-test`) published on 2026-07-20.
- Phone version code 23 (`1.2.4-test`) published on 2026-07-20.
- Wear OS version code 24 (`1.2.4-test`) published on 2026-07-20.
- Phone tester link: `https://play.google.com/apps/internaltest/4701743177829341351`
- Wear OS tester link: `https://play.google.com/apps/internaltest/4700051853729851607`
- Both tracks use the `Dream Sync private testers` email list.

## Current internal-test build

- Phone version code 23 (`1.2.4-test`) is available to internal testers.
- Wear OS version code 24 (`1.2.4-test`) is available to internal testers.
- Forty-four shared tests, seven phone unit tests, eight Android instrumentation tests, phone and watch lint, signed-bundle, signature, and release-integrity checks passed before publishing.
- The phone sound check uses a local WAV player with streaming PCM fallback and requires the user to confirm that the cue was audible. A saved calibration without that confirmation is rejected.
- **Run phone + watch check** confirms phone audio, then runs a 45-second watch check for permissions, actual accelerometer and heart-rate samples, sensor registration, off-wrist state, and available Samsung advanced signals. The exact pass, fail, or two-minute timeout result is stored in Export Debug Log.
- The debug export verifies the full chain: configuration, passive monitoring and inputs, automatic session handshake, sensor registration and readings, quality and eligibility decisions, cue transport, phone audio lifecycle, user-run system check, no-haptics policy, and terminal summary.
- Release bundle SHA-256: phone `ADA92814D3665729155486841564F39D8C95D89D7FB41BE97207E962E5158E26`; Wear OS `D47106BF8A80A07DE716EAAF45C3644D646892AC77D0AB35BB0760CDCEC715F2`.
- This build adds persistent automatic nightly operation, broad Android 8.0+/Wear OS 3+ packaging, conservative passive-HR fallback, learned charge reminders, and full-sensor feature selection for capable watches.
- The phone detects the paired watch app. When missing, it opens DreamSync's official Play Store page on the watch and continues setup after the user confirms Install. Google Play does not permit a silent background install.
- The watch has no product settings or power controls. Manual launch sends the user to phone controls; only required first-time watch health-permission prompts remain on the watch.
- Advanced Samsung status remains Basic monitoring only until the official SDK AAR, Samsung partnership approval, package/signature registration, and live Ultra self-test are complete.

## Google Play gate

This developer account must complete Google Play's required closed release with at least 12 continuously opted-in users for 14 days before production access can be requested.

## Before production submission

- Make the hosted privacy policy publicly accessible and enter its URL in Play Console.
- Finish the 11 App information and store listing tasks in Play Console.
- Upload the current icon, slogan feature graphic, and current app screenshots.
- Publish the current phone and Wear OS bundles to a closed track after app setup is complete.
- Add at least 12 real users and keep them opted in for 14 consecutive days.
- Apply for production access after the gate unlocks.
