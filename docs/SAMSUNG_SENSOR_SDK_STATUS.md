# Samsung Health Sensor SDK gate

Status: **Samsung partner approval received; official SDK v1.4.1 integrated; live
Play-signed hardware verification pending**.

DreamSync's adapter, Android 16 permissions, Samsung continuous accelerometer, heart-rate/IBI, skin-temperature, off-wrist, explicit Green/IR/Red PPG selection, short raw-PPG windows, runtime capability checks, and standard Wear OS fallback are implemented. The official v1.4.1 library is installed locally and remains ignored by Git under Samsung's license. Wear release builds now fail unless its exact SHA-256 and required API contract verify. Compilation and packaging pass; internal-track installation and live Galaxy Watch Ultra verification remain.

The `1.3.16-test` candidate also requests controlled Samsung tracker flushes during
the awake check and every 30 seconds during an active sleep session. This makes
batched motion, heart/IBI, temperature, and PPG readings visible without turning
raw PPG into a continuous all-night stream. Debug export records every request,
SDK acceptance, completion callback, tracker error, and sample count.

## Required verification steps

1. Run `gradlew.bat :wear:verifySamsungSensorSdk`. Every Wear release build runs this gate automatically.
2. Enable Health Platform developer mode for private device testing.
3. Run the live check on a Galaxy Watch. Confirm changing Samsung accelerometer values, heart rate, IBI, skin temperature, off-wrist behavior, and the short raw-PPG window in Export Debug Log.
4. Confirm Samsung's approval record includes package `com.remote.DreamSync`, its
   Google Play **app-signing** certificate SHA-256, and every requested tracker type.
5. Install the Wear build from Google Play Internal testing. A locally signed build
   does not use the registered Play app-signing certificate.
6. Keep standard Wear OS fallback enabled for non-Samsung watches and for any tracker
   not reported by Samsung's runtime capability check.

Samsung states that SDK use needs prior partner approval and package/signature
registration. An unregistered or differently signed build can receive
`SDK_POLICY_ERROR`. Developer mode is for local testing only.

## Release evidence

- Official AAR filename, version, source URL, license, size, and SHA-256.
- Compile and test output proving the v1.4.1 PPG overload and tracker APIs resolve.
- Samsung partnership request and approval record.
- Registered package and certificate SHA-256.
- Galaxy Watch live-check export with no policy error.
- Battery result for sensor-only and experimental nights.

Local SDK evidence:

- Archive: Samsung Health Sensor SDK v1.4.1 from Samsung Developer.
- Archive SHA-256: `C2E950774650FF212AE495525FC22714F5C8723A50F91CEB1514A9EC92C42BE9`.
- AAR: `wear/libs/samsung-health-sensor-api.aar`, 61,063 bytes.
- AAR SHA-256: `893CD5D6564DB0F304BF511A555C1D65CA6BCCC8475FC979FF1D71D50680344C`.
