plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseStoreFile = System.getenv("ALLERVUE_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("ALLERVUE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ALLERVUE_KEY_ALIAS")
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias
).all { !it.isNullOrBlank() }

android {
    namespace = "com.allerix.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.allerix.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                // PKCS12 uses the store password for the private key password.
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
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
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
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // VideoLAN LibVLC fallback engine for formats/codecs Media3 cannot decode.
    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.coil-kt:coil-compose:2.6.0")
}
