# DreamSync third-party notices

Date: 2026-07-19

Audited release: DreamSync `1.1.2-test`, phone code 13, Wear OS code 14

DreamSync's original source is governed by the repository `LICENSE`. The libraries below retain their own terms. This inventory was derived from the resolved `releaseRuntimeClasspath` configurations, not only from the direct Gradle declarations.

## Shipped runtime families

| Component family | Resolved versions observed | License or terms | Use |
|---|---|---|---|
| AndroidX Core and transitive AndroidX Activity, Annotation, Arch, Collection, Concurrent, Fragment, Interpolator, Lifecycle, Loader, SavedState, ViewPager, and VersionedParcelable | Versions resolved by Gradle; direct Core `1.12.0` | Apache License 2.0 | Android compatibility and lifecycle infrastructure. |
| AndroidX Wear Remote Interactions and Wear Core | `1.2.0`, `1.0.0` | Apache License 2.0 | Opens/coordinates the paired Wear OS app. |
| AndroidX Room and SQLite | Room `2.8.4`; SQLite `2.6.2` | Apache License 2.0 | Local phone/watch persistence. |
| Google Play services Wearable, Base, Basement, and Tasks | Wearable `20.0.1`; transitive versions resolved by Gradle | Android Software Development Kit License and applicable Google API/Play terms | Wear Data Layer transport and device discovery. |
| Kotlin standard library | `2.1.20` | Apache License 2.0 | Transitive runtime support. |
| Kotlin coroutines | Phone resolves `1.9.0`; watch resolves `1.8.1` | Apache License 2.0 | Transitive asynchronous support. |
| JetBrains Annotations | `23.0.0` | Apache License 2.0 | Transitive annotations. |
| JSpecify | `1.0.0` | Apache License 2.0 | Transitive nullness annotations. |
| Guava `listenablefuture` compatibility artifact | `1.0` | Apache License 2.0 | Transitive future API compatibility. |

Apache License 2.0 text: <https://www.apache.org/licenses/LICENSE-2.0>

Google Android SDK terms: <https://developer.android.com/studio/terms>

## Build/test-only components

These are used to build or test DreamSync and are not intended to be embedded as application runtime code:

| Component | Version | License |
|---|---|---|
| JUnit | `4.13.2` | Eclipse Public License 1.0 |
| Hamcrest Core | `1.3` | BSD 3-Clause |
| Android Gradle Plugin, Gradle wrapper, Android SDK build tools, and Android test libraries | Build-resolved versions | Their respective Android, Apache, Gradle, and component terms |

## Optional Samsung component

`wear/libs/samsung-health-sensor-api.aar` is not present and is not part of the current release. If it is later supplied, its Samsung license, partner approval, package-name registration, and signing-certificate registration must be added to this notice before any build is described as advanced-Samsung-enabled.

## Reproduce the inventory

From the repository root with Java 21 configured:

```powershell
.\gradlew.bat --offline :phone:dependencies --configuration releaseRuntimeClasspath :wear:dependencies --configuration releaseRuntimeClasspath
```

Re-run this inventory and review license changes whenever a dependency version changes. This notice summarizes compliance evidence; the original license and service terms control.
