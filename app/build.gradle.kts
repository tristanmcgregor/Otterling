import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use(::load)
    }
}

val knoxLicenseKey = System.getenv("KNOX_LICENSE_KEY")
    ?: localProperties.getProperty("KNOX_LICENSE_KEY")
    ?: ""
val escapedKnoxLicenseKey = knoxLicenseKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "au.com.tbmcgregor.bwparker.familyguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "au.com.tbmcgregor.bwparker.familyguard"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "KNOX_LICENSE_KEY", "\"$escapedKnoxLicenseKey\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    // Samsung distributes this JAR through the Knox Developer Portal, not Maven.
    // Knox classes are accessed reflectively, so the project builds before it's present.
    val knoxSdkJar = file("libs/knoxsdk.jar")
    if (knoxSdkJar.exists()) {
        compileOnly(files(knoxSdkJar))
    }

    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
