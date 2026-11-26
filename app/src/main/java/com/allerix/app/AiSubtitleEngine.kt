package com.allerix.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Timestamped speech recognition output used by the AI subtitle pipeline. */
data class AiSubtitleSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class AiSubtitleFile(
    val file: File,
    val segments: List<AiSubtitleSegment>
)

/** Whisper.cpp implementation. Audio input must be 16 kHz mono PCM WAV. */
interface AiSubtitleEngine {
    val isAvailable: Boolean
    suspend fun transcribe(
        audioFile: File,
        modelFile: File,
        language: String = "auto"
    ): List<AiSubtitleSegment>
}

class WhisperCppEngine : AiSubtitleEngine {
    override val isAvailable: Boolean
        get() = WhisperNative.available

    override suspend fun transcribe(
        audioFile: File,
        modelFile: File,
        language: String
    ): List<AiSubtitleSegment> = withContext(Dispatchers.IO) {
        check(isAvailable) { "The on-device Whisper engine could not be loaded." }
        require(modelFile.isFile && modelFile.length() > 10L * 1024L * 1024L) {
            "Download a Whisper model before generating subtitles."
        }
        require(audioFile.isFile && audioFile.length() > 44L) {
            "The decoded audio file is empty."
        }
        coroutineContext.ensureActive()
        val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val srt = WhisperNative.transcribeWav(
            modelFile.absolutePath,
            audioFile.absolutePath,
            language.ifBlank { "auto" },
            threadCount
        )
        coroutineContext.ensureActive()
        require(srt.isNotBlank()) {
            "Whisper could not recognize speech in this audio."
        }
        val parsed = SubParser.parse(srt)
            ?: error("Whisper returned no timestamped subtitle cues.")
        parsed.map { AiSubtitleSegment(it.startMs, it.endMs, it.text) }
    }
}

/** Extracts audio, runs Whisper locally, and saves a standard SRT subtitle file. */
object AiSubtitleGenerator {
    private val engine = WhisperCppEngine()

    val isAvailable: Boolean
        get() = engine.isAvailable

    suspend fun generateFromMedia(
        context: Context,
        mediaUri: Uri,
        model: AiSubtitleModel,
        language: String = "auto"
    ): AiSubtitleFile = withContext(Dispatchers.IO) {
        check(engine.isAvailable) { "Whisper native runtime is unavailable on this device." }
        require(AiSubtitleModelStore.isInstalled(context, model)) {
            "The selected Whisper model is not downloaded yet."
        }

        val cacheDir = File(context.cacheDir, "ai_subtitle_work").apply { mkdirs() }
        val wav = File.createTempFile("allervue_audio_", ".wav", cacheDir)
        try {
            MediaAudioDecoder.decodeToWhisperWav(context, mediaUri, wav)
            val cues = engine.transcribe(wav, AiSubtitleModelStore.file(context, model), language)
            val outputDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
                "AI Subtitles"
            ).apply { mkdirs() }
            val output = File(outputDir, "Allervue_AI_Subtitles_${System.currentTimeMillis()}.srt")
            output.writeText(toSrt(cues), Charsets.UTF_8)
            AiSubtitleFile(output, cues)
        } finally {
            wav.delete()
        }
    }

    fun toSrt(segments: List<AiSubtitleSegment>): String = buildString {
        segments.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(timestamp(cue.startMs)).append(" --> ").append(timestamp(cue.endMs)).append('\n')
            append(cue.text.trim()).append("\n\n")
        }
    }

    private fun timestamp(value: Long): String {
        val ms = value.coerceAtLeast(0L)
        val hours = ms / 3_600_000L
        val minutes = (ms / 60_000L) % 60L
        val seconds = (ms / 1_000L) % 60L
        val millis = ms % 1_000L
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}

object AiSubtitleEngineStatus {
    val NATIVE_RUNTIME_READY: Boolean
        get() = WhisperNative.available
}
