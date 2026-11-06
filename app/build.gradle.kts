plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "my.spectrum.notify"
    compileSdk = 34

    defaultConfig {
        applicationId = "my.spectrum.notify"
        minSdk = 26
        targetSdk = 34
        versionCode = 97
        versionName = "0.33.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            // same fixed key as debug builds, so release APKs install over existing installs
            signingConfig = signingConfigs.getByName("debug")
            // phones only: the assistant's TensorFlow Lite engine for emulators (x86) would add ~2 MB nobody uses
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.firebase:firebase-messaging:24.0.0")
    // OpenStreetMap map on the Shuttle screen (no API key; tiles from openstreetmap.org)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // the assistant's on-device model (assets/assistant.tflite, trained in tools/assistant)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
