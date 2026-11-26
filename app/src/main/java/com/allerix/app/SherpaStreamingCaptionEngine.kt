package com.allerix.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First bundled model pack: compact, int8 English streaming Zipformer (20M).
 * The four files are downloaded independently so Android DownloadManager can resume them.
 */
object SherpaStreamingModelStore {
    private const val MODEL_ID = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    private const val PREFS = "sherpa_streaming_models"
    private const val BASE_URL =
        "https://huggingface.co/csukuangfj/$MODEL_ID/resolve/main/"

    data class ModelFile(
        val name: String,
        val minimumBytes: Long,
        val url: String
    )

    val files = listOf(
        ModelFile("encoder-epoch-99-avg-1.int8.onnx", 40_000_000L,
            BASE_URL + "encoder-epoch-99-avg-1.int8.onnx?download=true"),
        ModelFile("decoder-epoch-99-avg-1.onnx", 2_000_000L,
            BASE_URL + "decoder-epoch-99-avg-1.onnx?download=true"),
        ModelFile("joiner-epoch-99-avg-1.int8.onnx", 250_000L,
            BASE_URL + "joiner-epoch-99-avg-1.int8.onnx?download=true"),
        ModelFile("tokens.txt", 4_000L, BASE_URL + "tokens.txt?download=true")
    )

    fun directory(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(root, "allervue_ai_models/$MODEL_ID").apply { mkdirs() }
    }

    fun isInstalled(context: Context): Boolean {
        val modelDirectory = directory(context)
        return files.all { modelFile ->
            val downloadedFile = File(modelDirectory, modelFile.name)
            downloadedFile.isFile && downloadedFile.length() >= modelFile.minimumBytes
        }
    }

    fun ensureDownloaded(context: Context): Int {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var queued = 0
        files.forEach { modelFile ->
            val target = File(directory(context), modelFile.name)
            if (target.isFile && target.length() >= modelFile.minimumBytes) return@forEach
            val key = "download_${modelFile.name}"
            val previousId = prefs.getLong(key, -1L)
            if (previousId >= 0L) {
                val cursor = manager.query(DownloadManager.Query().setFilterById(previousId))
                val active = cursor?.use {
                    it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) in
                        setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)
                } ?: false
                if (active) return@forEach
            }
            target.delete()
            val request = DownloadManager.Request(Uri.parse(modelFile.url))
                .setTitle("Allervue · Live captions")
                .setDescription("Downloading English streaming speech model · ${modelFile.name}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(
                    context, Environment.DIRECTORY_DOWNLOADS,
                    "allervue_ai_models/$MODEL_ID/${modelFile.name}"
                )
            val id = manager.enqueue(request)
            prefs.edit().putLong(key, id).apply()
            queued++
        }
        return queued
    }
}

/**
 * True incremental speech recognizer. Audio is decoded from the active playback sink and
 * fed in ~100 ms chunks; sherpa emits revised partial text without waiting for a long window.
 */
class SherpaStreamingCaptionEngine(
    private val modelDirectory: File,
    private val threadCount: Int = 2
) : LiveCaptionEngine {
    override val engineId: String = "sherpa-onnx-streaming-zipformer-en20m"
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Allervue-Sherpa-Streaming").apply { priority = Thread.NORM_PRIORITY }
    }
    private val closed = AtomicBoolean(false)
    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var stream: OnlineStream? = null
    @Volatile private var eventCallback: ((LiveCaptionEvent) -> Unit)? = null
    @Volatile private var errorCallback: ((Throwable) -> Unit)? = null
    @Volatile private var ready = false
    private var utteranceStartMediaMs = 0L
    private var lastAudioEndMediaMs = 0L
    private var lastPartialText = ""

    override val isReady: Boolean
        get() = ready && !closed.get()

    override fun start(
        sampleRate: Int,
        language: String,
        onEvent: (LiveCaptionEvent) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        check(!closed.get()) { "Streaming caption engine is closed." }
        require(sampleRate == 16_000) { "sherpa streaming model requires 16 kHz mono audio." }
        require(language.isBlank() || language == "auto" || language == "en" || language == "eng") {
            "The installed sherpa streaming model currently supports English. Select English or Auto, or use Whisper for other languages."
        }
        eventCallback = onEvent
        errorCallback = onError

        val encoder = File(modelDirectory, "encoder-epoch-99-avg-1.int8.onnx")
        val decoder = File(modelDirectory, "decoder-epoch-99-avg-1.onnx")
        val joiner = File(modelDirectory, "joiner-epoch-99-avg-1.int8.onnx")
        val tokens = File(modelDirectory, "tokens.txt")
        listOf(encoder, decoder, joiner, tokens).forEach {
            require(it.isFile && it.length() > 0L) { "Missing sherpa streaming model file: ${it.name}" }
        }

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = encoder.absolutePath,
                decoder = decoder.absolutePath,
                joiner = joiner.absolutePath
            ),
            tokens = tokens.absolutePath,
            numThreads = threadCount.coerceIn(1, 4),
            provider = "cpu",
        )
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0.0f),
            modelConfig = modelConfig,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 0.8f, 0.0f),
                rule2 = EndpointRule(true, 0.5f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f)
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4
        )
        recognizer = OnlineRecognizer(config = config)
        stream = recognizer!!.createStream()
        ready = true
    }

    override fun acceptAudio(
        samples: FloatArray,
        mediaStartMs: Long,
        capturedAtElapsedRealtimeMs: Long
    ) {
        if (!isReady || samples.isEmpty()) return
        val immutableSamples = samples.copyOf()
        worker.execute {
            if (!isReady) return@execute
            try {
                val activeRecognizer = recognizer ?: return@execute
                val activeStream = stream ?: return@execute
                if (lastAudioEndMediaMs == 0L) utteranceStartMediaMs = mediaStartMs
                activeStream.acceptWaveform(immutableSamples, 16_000)
                lastAudioEndMediaMs = mediaStartMs + immutableSamples.size * 1_000L / 16_000L
                while (activeRecognizer.isReady(activeStream)) {
                    activeRecognizer.decode(activeStream)
                }
                val result = activeRecognizer.getResult(activeStream)
                val text = result.text.trim()
                if (text.isNotEmpty() && text != lastPartialText) {
                    lastPartialText = text
                    val timestamps = result.timestamps
                    val startMs = if (timestamps.isNotEmpty()) {
                        utteranceStartMediaMs + (timestamps.first() * 1_000.0f).toLong()
                    } else utteranceStartMediaMs
                    val endMs = if (timestamps.isNotEmpty()) {
                        utteranceStartMediaMs + (timestamps.last() * 1_000.0f).toLong() + 500L
                    } else lastAudioEndMediaMs
                    eventCallback?.invoke(
                        LiveCaptionEvent.Partial(
                            text = text,
                            mediaStartMs = startMs.coerceAtLeast(0L),
                            mediaEndMs = maxOf(endMs, lastAudioEndMediaMs).coerceAtLeast(startMs + 250L)
                        )
                    )
                }
                if (activeRecognizer.isEndpoint(activeStream)) {
                    if (text.isNotEmpty()) {
                        val timestamps = result.timestamps
                        val startMs = if (timestamps.isNotEmpty()) {
                            utteranceStartMediaMs + (timestamps.first() * 1_000.0f).toLong()
                        } else utteranceStartMediaMs
                        val endMs = if (timestamps.isNotEmpty()) {
                            utteranceStartMediaMs + (timestamps.last() * 1_000.0f).toLong() + 650L
                        } else lastAudioEndMediaMs
                        eventCallback?.invoke(
                            LiveCaptionEvent.Final(
                                text = text,
                                mediaStartMs = startMs.coerceAtLeast(0L),
                                mediaEndMs = maxOf(endMs, lastAudioEndMediaMs).coerceAtLeast(startMs + 250L)
                            )
                        )
                    }
                    activeRecognizer.reset(activeStream)
                    utteranceStartMediaMs = lastAudioEndMediaMs
                    lastPartialText = ""
                }
            } catch (error: Throwable) {
                errorCallback?.invoke(error)
            }
        }
    }

    override fun flush() {
        // Endpoint detection handles natural pauses. We intentionally do not force an
        // endpoint on pause/temporary buffering, which would truncate a spoken phrase.
    }

    override fun reset(mediaPositionMs: Long) {
        if (closed.get()) return
        worker.execute {
            runCatching {
                val activeRecognizer = recognizer ?: return@runCatching
                stream?.release()
                stream = activeRecognizer.createStream()
                utteranceStartMediaMs = mediaPositionMs.coerceAtLeast(0L)
                lastAudioEndMediaMs = utteranceStartMediaMs
                lastPartialText = ""
            }.onFailure { errorCallback?.invoke(it) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        worker.execute {
            runCatching { stream?.release() }
            stream = null
            runCatching { recognizer?.release() }
            recognizer = null
            worker.shutdown()
        }
    }
}
