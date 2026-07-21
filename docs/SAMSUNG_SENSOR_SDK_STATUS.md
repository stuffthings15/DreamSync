# Samsung Health Sensor SDK gate

Status: **blocked on Samsung account access and external approval**.

DreamSync's adapter, Android 16 permissions, Samsung continuous accelerometer, heart-rate/IBI, skin-temperature, off-wrist, explicit Green/IR/Red PPG selection, short raw-PPG windows, runtime capability checks, standard Wear OS fallback, and conditional Gradle dependency are implemented. The official library is not in this repository, so the Samsung paths are not yet compile-checked or hardware-verified against the proprietary API.

## Required owner/Samsung steps

1. Sign in at [Samsung Health Sensor SDK](https://developer.samsung.com/health/sensor).
2. Download official Samsung Health Sensor SDK v1.4.1. Copy only `samsung-health-sensor-api.aar` to `wear/libs/` locally. The AAR is ignored by Git and must not be redistributed without license permission.
3. Run `gradlew.bat :wear:verifySamsungSensorSdk`.
4. Enable Health Platform developer mode for private device testing.
5. Run the live check on a Galaxy Watch. Confirm changing Samsung accelerometer values, heart rate, IBI, skin temperature, off-wrist behavior, and the short raw-PPG window in Export Debug Log.
6. Submit the signed-in [Samsung Health Sensor SDK partner request](https://developer.samsung.com/SHealth/business-partner/m48wzh9rwz606k0h).
7. Register package `com.remote.DreamSync`, its Google Play **app-signing** certificate SHA-256, and every requested tracker type. Do not submit the upload-key fingerprint as the production signature.
8. Keep public builds on standard Wear OS sensors until Samsung shows the package/signature registration as approved.

Samsung states that public SDK use needs prior partner approval and package/signature registration. An unregistered public build can receive `SDK_POLICY_ERROR`. Developer mode is for testing only.

## Release evidence

- Official AAR filename, version, source URL, license, size, and SHA-256.
- Compile and test output proving the v1.4.1 PPG overload and tracker APIs resolve.
- Samsung partnership request and approval record.
- Registered package and certificate SHA-256.
- Galaxy Watch live-check export with no policy error.
- Battery result for sensor-only and experimental nights.
