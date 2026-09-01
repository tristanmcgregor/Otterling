plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // Applied conditionally in app/build.gradle.kts, only once google-services.json exists -- see
    // the guard there. The build works without it; FCM push just stays inert until it's added.
    id("com.google.gms.google-services") version "4.5.0" apply false
    // Static analysis for Kotlin, applied per-module (see app/build.gradle.kts and
    // proxytest/build.gradle.kts) with a baseline of this codebase's existing findings, so `detekt`
    // only fails CI on newly introduced issues -- see config/detekt/README.md.
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}
