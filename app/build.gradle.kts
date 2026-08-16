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

// Release signing + the pinned fingerprint ApprovedUpdateManager checks a downloaded update APK
// against -- see .github/workflows/update-review.yml and scripts/update_review_checklist.md for
// where a real value comes from (the Guardian-approved CI release environment, not this machine).
val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    ?: localProperties.getProperty("RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    ?: localProperties.getProperty("RELEASE_KEYSTORE_PASSWORD")
    ?: ""
val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
    ?: localProperties.getProperty("RELEASE_KEY_ALIAS")
    ?: ""
val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    ?: localProperties.getProperty("RELEASE_KEY_PASSWORD")
    ?: ""
val releaseCertSha256 = System.getenv("RELEASE_CERT_SHA256")
    ?: localProperties.getProperty("RELEASE_CERT_SHA256")
    ?: ""

// Google's Firebase plugin reads this file at build time and fails the build if it's missing, so
// the plugin is applied conditionally at the bottom of this file -- the project builds fine without
// it, and FCM push (instant tamper alerts) just stays inert until google-services.json is dropped
// in for the app.otterling / otterling-98c2e Firebase project.
val googleServicesJson = file("google-services.json")

android {
    namespace = "app.otterling"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.otterling"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.5"

        buildConfigField("String", "KNOX_LICENSE_KEY", "\"$escapedKnoxLicenseKey\"")
        // Empty in any build that isn't the Guardian-approved CI release build -- ApprovedUpdateManager
        // treats an empty pin as "not configured" and refuses to install anything rather than
        // treating a missing pin as "skip the check".
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$releaseCertSha256\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        // Keep the .tflite embedder model uncompressed so MediaPipe can memory-map it from assets.
        noCompress += "tflite"
    }

    lint {
        // False positive: MainActivity is a Compose ComponentActivity, not a FragmentActivity, so
        // its registerForActivityResult calls are correct regardless of the androidx.fragment
        // version. This check only started firing (failing lintVitalRelease) once firebase-messaging
        // pulled androidx.fragment onto the classpath transitively -- the app uses no Fragments.
        disable += "InvalidFragmentVersionForActivityResult"
    }

    signingConfigs {
        // Only defined when the release keystore secrets are actually present -- CI's protected
        // `release` environment (after AI review + Guardian approval), or deliberately set up
        // locally by the Guardian. A checkout without them can still run `assembleRelease` (it
        // just produces an unsigned APK) rather than failing outright, since an unsigned local
        // release build was never a valid install candidate anyway -- the phone only trusts an
        // APK whose signing cert matches RELEASE_CERT_SHA256, and only CI's protected environment
        // ever signs with the key that produces that fingerprint.
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
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

    // FCM: lets the filter-server push a "poll now" wake to the phone so a Mac tamper alert reaches
    // the accountability partner in seconds instead of on MacTamperPollWorker's 15-minute floor.
    // Only firebase-messaging -- no firestore/crashlytics -- to keep the dependency surface minimal.
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-messaging")

    // On-device image embeddings for habit photo-proof verification (MobileNet-V3 TFLite model
    // bundled in assets/mobilenet_embedder.tflite).
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    testImplementation("junit:junit:4.13.2")
    // Runs raw Room migration SQL against a real SQLite engine (not Room/Robolectric) so a typo
    // in a migration string is caught here instead of on a real device's first post-update launch.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// See the googleServicesJson doc comment above: applied only when the file is present, so a
// checkout without it still builds (FCM inert) rather than failing outright.
if (googleServicesJson.exists()) {
    apply(plugin = "com.google.gms.google-services")
}
