package com.allerix.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Effects
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Creates a device-local 1080p H.264 copy for playback.
 *
 * This is an offline export, not a real-time decoder pipeline: conversion must finish
 * before the optimized file can be played. Media3 chooses the device's available
 * decoder/encoder implementations. Unsupported source codecs or insufficient device
 * performance are reported to the caller so normal playback can remain available.
 */
@UnstableApi
class LocalVideoOptimizer(context: Context) {
    interface Callback {
        fun onProgress(percent: Int)
        fun onCompleted(output: File)
        fun onError(error: Throwable)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()
    private var transformer: Transformer? = null
    private var callback: Callback? = null

    private val progressTask = object : Runnable {
        override fun run() {
            val active = transformer ?: return
            val cb = callback ?: return
            if (active.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                cb.onProgress(progressHolder.progress.coerceIn(0, 100))
            }
            handler.postDelayed(this, 500)
        }
    }

    fun isRunning(): Boolean = transformer != null

    fun start(sourceUrl: String, output: File, callback: Callback) {
        check(transformer == null) { "A local optimization is already running" }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        this.callback = callback

        val presentation = Presentation.createForHeight(1080)
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(sourceUrl))
            .setEffects(Effects(emptyList(), listOf(presentation)))
            .build()

        val engine = Transformer.Builder(appContext)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(progressTask)
                    transformer = null
                    val cb = this@LocalVideoOptimizer.callback
                    this@LocalVideoOptimizer.callback = null
                    if (output.exists() && output.length() > 0L) cb?.onCompleted(output)
                    else cb?.onError(IllegalStateException("Optimizer produced an empty output file"))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    handler.removeCallbacks(progressTask)
                    transformer = null
                    output.delete()
                    val cb = this@LocalVideoOptimizer.callback
                    this@LocalVideoOptimizer.callback = null
                    cb?.onError(exportException)
                }
            })
            .build()

        transformer = engine
        try {
            engine.start(edited, output.absolutePath)
            handler.post(progressTask)
        } catch (error: Throwable) {
            handler.removeCallbacks(progressTask)
            transformer = null
            this.callback = null
            output.delete()
            throw error
        }
    }

    fun cancel() {
        handler.removeCallbacks(progressTask)
        val active = transformer
        transformer = null
        callback = null
        if (active != null) runCatching { active.cancel() }
    }
}
