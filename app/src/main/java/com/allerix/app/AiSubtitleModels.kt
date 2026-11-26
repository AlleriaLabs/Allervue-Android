package com.allerix.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.security.MessageDigest

/**
 * Local Whisper model catalogue and private model storage.
 *
 * Model files are kept in app-specific external storage. They are not visible
 * to other apps and are removed if Allervue is uninstalled.
 */
data class AiSubtitleModel(
    val id: String,
    val title: String,
    val description: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedBytes: Long,
    val sha1: String
)

object AiSubtitleModels {
    val all = listOf(
        AiSubtitleModel(
            id = "tiny",
            title = "Whisper Tiny",
            description = "Smallest multilingual model · fastest, lower accuracy",
            fileName = "ggml-tiny.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin?download=true",
            expectedBytes = 75L * 1024L * 1024L,
            sha1 = "bd577a113a864445d4c299885e0cb97d4ba92b5f"
        ),
        AiSubtitleModel(
            id = "base",
            title = "Whisper Base",
            description = "Multilingual model · better recognition, slower",
            fileName = "ggml-base.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin?download=true",
            expectedBytes = 142L * 1024L * 1024L,
            sha1 = "465707469ff3a37a2b9b8d8f89f2f99de7299dac"
        )
    )

    fun get(id: String): AiSubtitleModel? = all.firstOrNull { it.id == id }
}

data class AiModelDownloadState(
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val reason: Int? = null
) {
    val isActive: Boolean
        get() = status == DownloadManager.STATUS_PENDING ||
            status == DownloadManager.STATUS_RUNNING ||
            status == DownloadManager.STATUS_PAUSED
}

object AiSubtitleModelStore {
    private const val PREFS = "ai_subtitle_models"
    private const val KEY_PREFIX = "download_"

    fun directory(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        return File(root, "allervue_ai_models").apply { mkdirs() }
    }

    fun file(context: Context, model: AiSubtitleModel): File =
        File(directory(context), model.fileName)

    fun isInstalled(context: Context, model: AiSubtitleModel): Boolean {
        val target = file(context, model)
        return target.isFile && target.length() >= model.expectedBytes * 0.98
    }

    fun isValidModelFile(file: File): Boolean {
        val model = AiSubtitleModels.all.firstOrNull { it.fileName == file.name } ?: return false
        return file.isFile && file.length() >= model.expectedBytes * 0.98 && verifySha1(file, model.sha1)
    }

    fun usedBytes(context: Context): Long =
        directory(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun downloadId(context: Context, modelId: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_PREFIX + modelId, -1L)

    fun saveDownloadId(context: Context, modelId: String, id: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_PREFIX + modelId, id).apply()
    }

    fun clearDownloadId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PREFIX + modelId).apply()
    }

    fun verifySha1(file: File, expected: String): Boolean {
        if (!file.isFile) return false
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected
    }
}

object AiSubtitleModelDownloads {
    /**
     * Queue both multilingual models automatically when the AI subtitle feature is opened.
     * Existing downloads are left alone; completed models are never downloaded again.
     */
    fun ensureDefaultModels(context: Context): Int {
        var queued = 0
        AiSubtitleModels.all.forEach { model ->
            if (AiSubtitleModelStore.isInstalled(context, model)) return@forEach
            val state = state(context, model)
            if (state?.isActive == true) return@forEach
            enqueue(context, model)
            queued++
        }
        return queued
    }

    fun enqueue(context: Context, model: AiSubtitleModel): Long {
        val target = AiSubtitleModelStore.file(context, model)
        if (target.exists()) target.delete()
        val request = DownloadManager.Request(Uri.parse(model.downloadUrl))
            .setTitle("Allervue · ${model.title}")
            .setDescription("Downloading offline AI subtitle model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "allervue_ai_models/${model.fileName}"
            )
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
            .enqueue(request)
        AiSubtitleModelStore.saveDownloadId(context, model.id, id)
        return id
    }

    fun state(context: Context, model: AiSubtitleModel): AiModelDownloadState? {
        val id = AiSubtitleModelStore.downloadId(context, model.id)
        if (id < 0L) return null
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return AiModelDownloadState(
                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloadedBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            )
        }
    }

    fun remove(context: Context, model: AiSubtitleModel) {
        val id = AiSubtitleModelStore.downloadId(context, model.id)
        if (id >= 0L) {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
        }
        AiSubtitleModelStore.clearDownloadId(context, model.id)
        AiSubtitleModelStore.file(context, model).delete()
    }
}
