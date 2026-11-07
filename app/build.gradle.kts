plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "au.gordo.portalcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "au.gordo.portalcam"
        // Meta advises minSdk 28 and targetSdk 29 for Portal. The Portal runs Android 10 (SDK 29).
        minSdk = 28
        targetSdk = 29
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed so it installs with plain `adb install` and no keystore setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    lint { checkReleaseBuilds = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

// No library dependencies. The app uses only the Android framework and the Kotlin standard library.
