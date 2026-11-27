import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}


val sherpaAarFile = layout.projectDirectory.file("libs/sherpa-onnx-2.0.3.aar").asFile
val downloadSherpaOnnxAar = tasks.register("downloadSherpaOnnxAar") {
    outputs.file(sherpaAarFile)
    doLast {
        val expectedSha256 = "4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50"
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!sherpaAarFile.isFile || sha256(sherpaAarFile) != expectedSha256) {
            sherpaAarFile.parentFile.mkdirs()
            val temporary = File(sherpaAarFile.parentFile, sherpaAarFile.name + ".part")
            URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-2.0.3.aar")
                .openStream().buffered().use { input ->
                    temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            check(sha256(temporary) == expectedSha256) {
                "sherpa-onnx AAR SHA-256 verification failed"
            }
            if (sherpaAarFile.exists()) sherpaAarFile.delete()
            check(temporary.renameTo(sherpaAarFile)) { "Could not install sherpa-onnx AAR" }
        }
    }
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
    // Official sherpa-onnx Android runtime; fetched and SHA-256 verified before Android preBuild.
    implementation(files(sherpaAarFile))
    // Vosk offline streaming recognizer; language model is downloaded on demand.
    implementation("com.alphacephei:vosk-android:0.3.75")
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

// Keep the 50 MB native AAR out of Git while making clean local/CI builds reproducible.
tasks.named("preBuild").configure { dependsOn(downloadSherpaOnnxAar) }
