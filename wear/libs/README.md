# Samsung Health Sensor SDK

DreamSync supports Samsung Health Sensor SDK v1.4.1 when the proprietary library is
supplied locally as:

`wear/libs/samsung-health-sensor-api.aar`

The standard Wear OS build remains available without the AAR. The Samsung bridge then
fails closed and reports **Basic monitoring only**.

For watch testing, download the SDK from Samsung Developer and enable Health Platform
developer mode. Public distribution requires Samsung Partner Program approval and
registration of `com.remote.DreamSync` plus the release signing certificate.
