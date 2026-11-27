package com.allerix.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Filipino/Tagalog Vosk model is downloaded on demand (not bundled in the APK).
 * The model is licensed CC-BY-NC-SA 4.0; keep its attribution/license with the
 * model and do not redistribute it inside a commercial app build.
 */
object VoskFilipinoModelStore {
    private const val MODEL_NAME = "vosk-model-tl-ph-generic-0.6"
    private const val PREFS = "vosk_fil_model"
    private const val DOWNLOAD_KEY = "archive_download_id"
    private const val URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private const val MIN_ARCHIVE_BYTES = 250L * 1024L * 1024L

    fun directory(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(root, "allervue_ai_models/$MODEL_NAME")
    }

    private fun archive(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(root, "allervue_ai_models/$MODEL_NAME.zip")
    }

    fun isInstalled(context: Context): Boolean {
        val dir = directory(context)
        return File(dir, "am/final.mdl").isFile &&
            File(dir, "conf/model.conf").isFile &&
            File(dir, "graph").isDirectory
    }

    fun ensureDownloaded(context: Context): Int {
        if (isInstalled(context)) return 0
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getLong(DOWNLOAD_KEY, -1L)
        if (previous >= 0L) {
            val active = runCatching {
                manager.query(DownloadManager.Query().setFilterById(previous))?.use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) in
                        setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)
                } ?: false
            }.getOrDefault(false)
            if (active) return 0
            val successful = runCatching {
                manager.query(DownloadManager.Query().setFilterById(previous))?.use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                        DownloadManager.STATUS_SUCCESSFUL
                } ?: false
            }.getOrDefault(false)
            if (successful && archive(context).length() >= MIN_ARCHIVE_BYTES) return 0
        }

        val target = archive(context)
        target.parentFile?.mkdirs()
        target.delete()
        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle("Allervue · Filipino speech model")
            .setDescription("Downloading the offline Tagalog speech recognition model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "allervue_ai_models/$MODEL_NAME.zip")
        return runCatching {
            val id = manager.enqueue(request)
            prefs.edit().putLong(DOWNLOAD_KEY, id).apply()
            1
        }.getOrDefault(-1)
    }

    fun extractIfDownloaded(context: Context): Boolean {
        if (isInstalled(context)) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(DOWNLOAD_KEY, -1L)
        if (id < 0L) return false
        val successful = runCatching {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
                .query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                        DownloadManager.STATUS_SUCCESSFUL
                } ?: false
        }.getOrDefault(false)
        val zip = archive(context)
        if (!successful || !zip.isFile || zip.length() < MIN_ARCHIVE_BYTES) return false

        val staging = File(zip.parentFile, "$MODEL_NAME.extracting")
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingCanonical = staging.canonicalFile
        return try {
            ZipInputStream(FileInputStream(zip).buffered()).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val entry = input.nextEntry ?: break
                    val output = File(staging, entry.name).canonicalFile
                    require(output.path.startsWith(stagingCanonical.path + File.separator) || output == stagingCanonical) {
                        "Unsafe path in Vosk model archive"
                    }
                    if (entry.isDirectory) output.mkdirs() else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).buffered().use { out ->
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                            }
                        }
                    }
                    input.closeEntry()
                }
            }
            val modelRoot = sequenceOf(staging, *staging.listFiles().orEmpty())
                .flatMap { root -> sequenceOf(root) + root.walkTopDown().filter { it.isDirectory } }
                .firstOrNull { File(it, "am/final.mdl").isFile && File(it, "conf/model.conf").isFile }
                ?: error("The downloaded Vosk archive did not contain a valid model")
            val target = directory(context)
            target.deleteRecursively()
            if (modelRoot == staging) {
                check(staging.renameTo(target)) { "Could not install Vosk model directory" }
            } else {
                check(modelRoot.renameTo(target)) { "Could not move extracted Vosk model into place" }
                staging.deleteRecursively()
            }
            zip.delete()
            isInstalled(context)
        } catch (error: Exception) {
            staging.deleteRecursively()
            Log.e("AllervueVosk", "Model extraction failed", error)
            false
        }
    }
}

/** Vosk streaming adapter. It consumes the same decoded 16 kHz playback PCM as Sherpa. */
class VoskStreamingCaptionEngine(
    private val modelDirectory: File
) : LiveCaptionEngine {
    override val engineId = "vosk-filipino-streaming"
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Allervue-Vosk-Streaming").apply { priority = Thread.NORM_PRIORITY }
    }
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    @Volatile private var ready = false
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var model: Model? = null
    @Volatile private var eventCallback: ((LiveCaptionEvent) -> Unit)? = null
    @Volatile private var errorCallback: ((Throwable) -> Unit)? = null
    private var utteranceStartMs = 0L
    private var lastAudioEndMs = 0L
    private var lastPartial = ""

    override val isReady: Boolean get() = ready && !closed.get()

    override fun start(
        sampleRate: Int,
        language: String,
        onEvent: (LiveCaptionEvent) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        check(!closed.get()) { "Vosk engine is closed" }
        require(sampleRate == 16_000) { "Vosk Filipino model requires 16 kHz mono PCM" }
        require(language == "auto" || language == "tl" || language == "fil") {
            "Vosk Filipino model only supports Filipino/Tagalog selection"
        }
        require(File(modelDirectory, "am/final.mdl").isFile) { "Filipino Vosk model is not installed" }
        eventCallback = onEvent
        errorCallback = onError
        val loadedModel = Model(modelDirectory.absolutePath)
        val loadedRecognizer = try {
            Recognizer(loadedModel, 16_000.0f).also { runCatching { it.setWords(true) } }
        } catch (error: Throwable) {
            runCatching { loadedModel.close() }
            throw error
        }
        synchronized(lifecycleLock) {
            if (closed.get()) {
                runCatching { loadedRecognizer.close() }
                runCatching { loadedModel.close() }
                return
            }
            model = loadedModel
            recognizer = loadedRecognizer
            ready = true
        }
    }

    override fun acceptAudio(
        samples: FloatArray,
        mediaStartMs: Long,
        capturedAtElapsedRealtimeMs: Long
    ) {
        if (!isReady || samples.isEmpty()) return
        val audio = ShortArray(samples.size) { index ->
            (samples[index].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        runCatching {
            worker.execute {
                if (!isReady) return@execute
                try {
                    val active = recognizer ?: return@execute
                    if (lastAudioEndMs == 0L) utteranceStartMs = mediaStartMs
                    val isEndpoint = active.acceptWaveForm(audio, audio.size)
                    lastAudioEndMs = mediaStartMs + audio.size * 1_000L / 16_000L
                    if (isEndpoint) {
                        emitFinal(JSONObject(active.result))
                        lastPartial = ""
                        utteranceStartMs = lastAudioEndMs
                    } else {
                        val partial = runCatching { JSONObject(active.partialResult).optString("partial").trim() }
                            .getOrDefault("")
                        if (partial.isNotEmpty() && partial != lastPartial) {
                            lastPartial = partial
                            eventCallback?.invoke(
                                LiveCaptionEvent.Partial(
                                    partial,
                                    utteranceStartMs.coerceAtLeast(0L),
                                    maxOf(lastAudioEndMs, utteranceStartMs + 350L)
                                )
                            )
                        }
                    }
                } catch (error: Throwable) {
                    errorCallback?.invoke(error)
                }
            }
        }.onFailure { errorCallback?.invoke(it) }
    }

    override fun flush() {
        if (!isReady) return
        runCatching {
            worker.execute {
                runCatching {
                    val active = recognizer ?: return@execute
                    val result = JSONObject(active.finalResult)
                    emitFinal(result)
                    lastPartial = ""
                    utteranceStartMs = lastAudioEndMs
                }.onFailure { errorCallback?.invoke(it) }
            }
        }
    }

    private fun emitFinal(result: JSONObject) {
        val text = result.optString("text").trim()
        if (text.isBlank()) return
        val words = result.optJSONArray("result")
        val start = if (words != null && words.length() > 0) {
            (words.optJSONObject(0)?.optDouble("start", 0.0)?.times(1000.0))?.toLong() ?: 0L
        } else 0L
        val end = if (words != null && words.length() > 0) {
            (words.optJSONObject(words.length() - 1)?.optDouble("end", 0.0)?.times(1000.0))?.toLong() ?: 0L
        } else lastAudioEndMs - utteranceStartMs
        eventCallback?.invoke(
            LiveCaptionEvent.Final(
                text,
                (utteranceStartMs + start).coerceAtLeast(0L),
                maxOf(utteranceStartMs + end, lastAudioEndMs, utteranceStartMs + 350L)
            )
        )
    }

    override fun reset(mediaPositionMs: Long) {
        if (!isReady) return
        runCatching {
            worker.execute {
                runCatching {
                    recognizer?.reset()
                    utteranceStartMs = mediaPositionMs.coerceAtLeast(0L)
                    lastAudioEndMs = utteranceStartMs
                    lastPartial = ""
                }.onFailure { errorCallback?.invoke(it) }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        synchronized(lifecycleLock) {
            worker.execute {
                runCatching { recognizer?.close() }
                recognizer = null
                runCatching { model?.close() }
                model = null
                worker.shutdown()
            }
        }
    }
}
