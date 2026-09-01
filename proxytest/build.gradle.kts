plugins {
    id("com.android.application")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "app.otterling.proxytest"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.otterling.proxytest"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Not pulled in transitively here the way it is for :app (via Room/WorkManager) -- this
    // module has none of those, just the coroutine-based relay code itself.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline-proxytest.xml")
}
