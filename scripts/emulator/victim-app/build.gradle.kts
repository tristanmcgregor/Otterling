plugins {
    id("com.android.application")
}

android {
    namespace = "test.blocker.victim"
    compileSdk = 36

    defaultConfig {
        applicationId = "test.blocker.victim"
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
