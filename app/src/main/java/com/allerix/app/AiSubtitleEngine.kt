package com.allerix.app

import java.io.File

/** Timestamped speech recognition output used by the AI subtitle pipeline. */
data class AiSubtitleSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Engine boundary for the Whisper native runtime.
 *
 * Phase 1 establishes the stable contract and model lifecycle. The JNI-backed
 * whisper.cpp implementation is connected in the transcription phase, so the
 * UI must not advertise transcription as ready until that runtime is present.
 */
interface AiSubtitleEngine {
    val isAvailable: Boolean

    suspend fun transcribe(
        audioFile: File,
        modelFile: File,
        language: String? = null
    ): List<AiSubtitleSegment>
}

object AiSubtitleEngineStatus {
    // Deliberately false until the native whisper.cpp runtime is packaged.
    const val NATIVE_RUNTIME_READY = false
}
