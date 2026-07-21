# Samsung Health Sensor SDK gate

Status: **official SDK v1.4.1 installed locally; blocked on Samsung partner approval and live hardware verification**.

DreamSync's adapter, Android 16 permissions, Samsung continuous accelerometer, heart-rate/IBI, skin-temperature, off-wrist, explicit Green/IR/Red PPG selection, short raw-PPG windows, runtime capability checks, standard Wear OS fallback, and conditional Gradle dependency are implemented. The official v1.4.1 library is installed locally and remains ignored by Git under Samsung's license. The Samsung paths must still pass compilation, internal-track installation, and live Galaxy Watch Ultra verification.

## Required owner/Samsung steps

1. Run `gradlew.bat :wear:verifySamsungSensorSdk`.
2. Enable Health Platform developer mode for private device testing.
3. Run the live check on a Galaxy Watch. Confirm changing Samsung accelerometer values, heart rate, IBI, skin temperature, off-wrist behavior, and the short raw-PPG window in Export Debug Log.
4. Submit the signed-in [Samsung Health Sensor SDK partner request](https://developer.samsung.com/SHealth/business-partner/m48wzh9rwz606k0h).
5. Register package `com.remote.DreamSync`, its Google Play **app-signing** certificate SHA-256, and every requested tracker type. Do not submit the upload-key fingerprint as the production signature.
6. Keep public builds on standard Wear OS sensors until Samsung shows the package/signature registration as approved.

Samsung states that public SDK use needs prior partner approval and package/signature registration. An unregistered public build can receive `SDK_POLICY_ERROR`. Developer mode is for testing only.

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
