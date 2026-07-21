# Samsung Health Sensor SDK gate

Status: **blocked on Samsung account access and external approval**.

DreamSync's adapter, permissions, runtime capability checks, short raw-PPG windows, standard Wear OS fallback, and conditional Gradle dependency are implemented. The official library is not in this repository.

## Required owner/Samsung steps

1. Sign in at [Samsung Health Sensor SDK](https://developer.samsung.com/health/sensor).
2. Download the official SDK package. Copy only `samsung-health-sensor-api.aar` to `wear/libs/` locally. Do not commit it unless its license permits redistribution.
3. Run `gradlew.bat :wear:verifySamsungSensorSdk`.
4. Enable Health Platform developer mode for private device testing.
5. Run the live check on a Galaxy Watch. Confirm IBI, skin-temperature, off-wrist, and the short raw-PPG window in Export Debug Log.
6. Request the Samsung Partner Program for public distribution.
7. Register package `com.remote.DreamSync` and the SHA-256 of the actual Google Play upload/app-signing certificate requested by Samsung.
8. Keep public builds on standard Wear OS sensors until Samsung shows the package/signature registration as approved.

Samsung states that public SDK use needs prior partner approval and package/signature registration. An unregistered public build can receive `SDK_POLICY_ERROR`. Developer mode is for testing only.

## Release evidence

- Official AAR filename, version, source URL, license, size, and SHA-256.
- Samsung partnership request and approval record.
- Registered package and certificate SHA-256.
- Galaxy Watch live-check export with no policy error.
- Battery result for sensor-only and experimental nights.
