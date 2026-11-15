package com.allerix.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import java.io.File

/**
 * Converts a streamed Jellyfin source into a local 1080p H.264/AAC MP4.
 *
 * FFmpeg performs HEVC decoding in its own codec pipeline, so this path does not
 * depend on Android Media3 finding a hardware HEVC decoder. H.264 encoding uses
 * Android MediaCodec through FFmpeg to keep the encode workload off the CPU where
 * the device exposes a compatible encoder. This is an offline conversion: it must
 * finish before the local copy can be played.
 */
class LocalVideoOptimizer(context: Context) {
    interface Callback {
        fun onProgress(framesProcessed: Int, speed: Double)
        fun onCompleted(output: File)
        fun onError(error: Throwable)
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var session: FFmpegSession? = null
    @Volatile private var callback: Callback? = null
    @Volatile private var cancelled = false

    fun isRunning(): Boolean = session != null

    fun start(sourceUrl: String, output: File, callback: Callback) {
        check(session == null) { "A local optimization is already running" }
        require(sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")) {
            "Unsupported Jellyfin source URL"
        }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        this.callback = callback
        cancelled = false

        // FFmpeg's native HEVC decoder handles the source. MediaCodec is used only
        // for AVC encoding; AAC is encoded by FFmpeg. No Media3 Transformer decoder.
        val args = arrayOf(
            "-y",
            "-hide_banner",
            "-loglevel", "info",
            "-stats_period", "0.5",
            "-threads", "0",
            "-i", sourceUrl,
            "-map", "0:v:0",
            "-map", "0:a:0?",
            "-sn",
            "-vf", "scale=-2:1080:flags=fast_bilinear",
            "-c:v", "h264_mediacodec",
            "-b:v", "5M",
            "-c:a", "aac",
            "-b:a", "192k",
            "-movflags", "+faststart",
            output.absolutePath
        )

        try {
            val created = FFmpegKit.executeWithArgumentsAsync(
                args,
                { finished ->
                    session = null
                    val cb = this.callback
                    this.callback = null
                    if (cancelled || ReturnCode.isCancel(finished.returnCode)) {
                        output.delete()
                        return@executeWithArgumentsAsync
                    }
                    if (ReturnCode.isSuccess(finished.returnCode) && output.exists() && output.length() > 0L) {
                        handler.post { cb?.onCompleted(output) }
                    } else {
                        output.delete()
                        val detail = (finished.output ?: finished.allLogsAsString ?: finished.failStackTrace)
                            .orEmpty()
                            .takeLast(1800)
                            .ifBlank { "FFmpeg returned ${finished.returnCode ?: "no return code"}" }
                        handler.post { cb?.onError(IllegalStateException(detail)) }
                    }
                },
                null,
                { statistics: Statistics ->
                    handler.post {
                        if (!cancelled) this.callback?.onProgress(
                            statistics.videoFrameNumber,
                            statistics.speed.toDouble()
                        )
                    }
                }
            )
            session = created
        } catch (error: Throwable) {
            session = null
            this.callback = null
            output.delete()
            throw error
        }
    }

    fun cancel() {
        cancelled = true
        callback = null
        val active = session
        session = null
        if (active != null) runCatching { active.cancel() }
    }
}
