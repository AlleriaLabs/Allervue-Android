package com.allerix.app

/** JNI bridge to the CPU-only whisper.cpp runtime. */
internal object WhisperNative {
    val available: Boolean = runCatching {
        System.loadLibrary("allervue_whisper")
        true
    }.getOrDefault(false)

    external fun transcribeWav(
        modelPath: String,
        wavPath: String,
        language: String,
        threads: Int
    ): String
}
