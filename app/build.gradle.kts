import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
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

    androidResources {
        // Keep the .tflite embedder model uncompressed so MediaPipe can memory-map it from assets.
        noCompress += "tflite"
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.security:security-crypto:1.1.0")

    // On-device image embeddings for habit photo-proof verification (MobileNet-V3 TFLite model
    // bundled in assets/mobilenet_embedder.tflite).
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
