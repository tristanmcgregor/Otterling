plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // Applied conditionally in app/build.gradle.kts, only once google-services.json exists -- see
    // the guard there. The build works without it; FCM push just stays inert until it's added.
    id("com.google.gms.google-services") version "4.5.0" apply false
}
