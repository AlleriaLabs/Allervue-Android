package com.allerix.app

import java.io.Closeable

/** Immutable short PCM chunk copied from the active playback audio sink. */
data class LiveSubtitleAudioChunk(
    val samples: ShortArray,
    val capturedAtElapsedRealtimeMs: Long
)

/**
 * Immutable six-second PCM window copied from the active playback audio sink.
 *
 * capturedAtElapsedRealtimeMs is recorded on the audio-processing thread when the
 * final sample in the window is accepted. It is deliberately independent of the
 * player clock, which is sampled on the main thread and mapped back to this instant.
 */
data class LiveSubtitleAudioWindow(
    val samples: ShortArray,
    val capturedAtElapsedRealtimeMs: Long
)

/** Events emitted by a streaming recognizer while an utterance is still being spoken. */
sealed interface LiveCaptionEvent {
    val text: String
    val mediaStartMs: Long
    val mediaEndMs: Long

    data class Partial(
        override val text: String,
        override val mediaStartMs: Long,
        override val mediaEndMs: Long
    ) : LiveCaptionEvent

    data class Final(
        override val text: String,
        override val mediaStartMs: Long,
        override val mediaEndMs: Long
    ) : LiveCaptionEvent
}

/**
 * Streaming engine contract for Allervue.
 *
 * Implementations must accept decoded playback PCM (not microphone input), must not
 * block the caller, and must emit partial hypotheses before endpoint/final events.
 * A sherpa-onnx OnlineRecognizer adapter will implement this contract; Whisper is
 * intentionally kept outside this interface as a background correction engine.
 */
interface LiveCaptionEngine : Closeable {
    val engineId: String
    val isReady: Boolean

    fun start(
        sampleRate: Int,
        language: String,
        onEvent: (LiveCaptionEvent) -> Unit,
        onError: (Throwable) -> Unit
    )

    fun acceptAudio(
        samples: FloatArray,
        mediaStartMs: Long,
        capturedAtElapsedRealtimeMs: Long
    )

    fun flush()
    fun reset(mediaPositionMs: Long)
}

/** Optional second-pass correction; never allowed to delay streaming caption events. */
interface CaptionCorrectionEngine {
    suspend fun correct(
        audioWindow: LiveSubtitleAudioWindow,
        modelFile: java.io.File,
        workDirectory: java.io.File,
        language: String
    ): List<AiSubtitleSegment>
}
