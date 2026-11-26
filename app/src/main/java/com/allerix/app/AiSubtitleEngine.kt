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

class WhisperCppEngine : AiSubtitleEngine, CaptionCorrectionEngine {
    override val isAvailable: Boolean
        get() = WhisperNative.available

    // A live-caption session reuses this engine for every short window. Verify the
    // large model once per file version rather than hashing 75–142 MB every few seconds.
    private var verifiedModelKey: String? = null

    override suspend fun correct(
        audioWindow: LiveSubtitleAudioWindow,
        modelFile: File,
        workDirectory: File,
        language: String
    ): List<AiSubtitleSegment> = withContext(Dispatchers.IO) {
        workDirectory.mkdirs()
        val wav = File.createTempFile("allervue_live_correction_", ".wav", workDirectory)
        try {
            LiveSubtitleWav.write(audioWindow.samples, wav)
            transcribe(wav, modelFile, language)
        } finally {
            wav.delete()
        }
    }

    override suspend fun transcribe(
        audioFile: File,
        modelFile: File,
        language: String
    ): List<AiSubtitleSegment> = withContext(Dispatchers.IO) {
        check(isAvailable) { "The on-device Whisper engine could not be loaded." }
        val modelKey = "${modelFile.absolutePath}:${modelFile.length()}:${modelFile.lastModified()}"
        if (verifiedModelKey != modelKey) {
            require(AiSubtitleModelStore.isValidModelFile(modelFile)) {
                "Download a complete Whisper model before generating subtitles."
            }
            verifiedModelKey = modelKey
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
        if (srt.startsWith("!ALLERVUE_ERROR:")) {
            val nativeError = srt.substringAfter(':')
            val errorCode = nativeError.substringBefore(':')
            val nativeDetail = nativeError.substringAfter(':', "")
            val detail = when (errorCode) {
                "WAV_INVALID" -> "Audio was decoded, but its WAV format is invalid. The audio conversion stage needs fixing."
                "AUDIO_TOO_SHORT" -> "The selected recording contains less than half a second of usable audio."
                "AUDIO_SILENT" -> "The extracted audio is silent or too quiet. Try another media file."
                "MODEL_LOAD" -> "Whisper could not load the downloaded model. Remove and download the Tiny model again."
                "INFERENCE" -> "Whisper inference failed while processing the audio. This may be a native engine or memory issue."
                "NO_SPEECH" -> "Audio extraction succeeded, but Whisper detected no speech. Check the recording language and try a longer, clear voice sample."
                else -> "Whisper failed with an unknown native error."
            }
            error(if (nativeDetail.isNotBlank()) "$detail Native diagnostics: $nativeDetail" else detail)
        }
        require(srt.isNotBlank()) {
            "Whisper returned an empty result without a diagnostic code."
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

    suspend fun generateFromAudioFile(
        context: Context,
        audioFile: File,
        model: AiSubtitleModel,
        language: String = "auto",
        outputName: String = "Allervue_AI_Subtitles"
    ): AiSubtitleFile = withContext(Dispatchers.IO) {
        check(engine.isAvailable) { "Whisper native runtime is unavailable on this device." }
        require(AiSubtitleModelStore.isInstalled(context, model)) {
            "The selected Whisper model is not downloaded yet."
        }
        require(audioFile.isFile && audioFile.length() > 0L) { "The Jellyfin audio stream is empty." }
        val cacheDir = File(context.cacheDir, "ai_subtitle_work").apply { mkdirs() }
        val wav = File.createTempFile("allervue_audio_", ".wav", cacheDir)
        try {
            MediaAudioDecoder.decodeFileToWhisperWav(audioFile, wav)
            val cues = engine.transcribe(wav, AiSubtitleModelStore.file(context, model), language)
            val outputDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
                "AI Subtitles"
            ).apply { mkdirs() }
            val safeName = outputName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val output = File(outputDir, "${safeName}_AI_Subtitles.srt")
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
