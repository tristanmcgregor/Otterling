# Family Device Guard

Samsung Knox parental-control app for Android 9+ (`minSdk 28`).

## Phase 1 setup

1. Install Android Studio with Android SDK 37.
2. Generate a Knox development key for
   `au.com.tbmcgregor.bwparker.familyguard`.
3. Download the current `knoxsdk.jar` from the Knox Developer Portal and place
   it at `app/libs/knoxsdk.jar`.
4. Copy `local.properties.example` to `local.properties`, set `sdk.dir`, and
   add `KNOX_LICENSE_KEY`. Alternatively, export `KNOX_LICENSE_KEY`.
5. Run `./gradlew assembleDebug`, then install on a Knox-capable Samsung test
   device.
6. Open the app and verify these Logcat tags:
   - `KnoxLicenseManager` confirms the activation request.
   - `KnoxLicenseReceiver` reports error code `0` for success.

The SDK classes are discovered reflectively during this bootstrap phase so the
project remains buildable before Samsung's proprietary JAR is downloaded. The
JAR is still declared as a compile-only dependency for direct API integration
in later phases.

## Secret handling

`local.properties` and `app/libs/*.jar` are ignored. Never commit Knox license
keys.
