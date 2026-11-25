import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = System.getenv("ALLERVUE_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("ALLERVUE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ALLERVUE_KEY_ALIAS")
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias
).all { !it.isNullOrBlank() }

android {
    namespace = "com.allerix.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.allerix.app"
        minSdk = 24
        targetSdk = 34
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 37
        versionName = "1.4.0-beta01"
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseStorePassword
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures { compose = true }
    // LibVLC and libmpvKt both bundle the same C++ shared runtime.
    // Package one copy to avoid duplicate native-library merge failures.
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // Last-resort playback backend for unusual containers and network streams.
    implementation("org.videolan.android:libvlc-all:3.6.0")
    // Published 0.3.0 Android View artifact; 0.4.0 renamed the artifact family.
    implementation("io.github.yuroyami:libmpvkt-view:0.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.coil-kt:coil-compose:2.6.0")
}
