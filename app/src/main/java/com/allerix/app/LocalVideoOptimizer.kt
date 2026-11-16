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
 * FFmpeg decodes HEVC and the bundled Android MediaCodec encoder produces H.264.
 * This is an offline conversion: it must finish before the local copy can be played.
 */
class LocalVideoOptimizer(context: Context) {
    interface Callback {
        fun onProgress(framesProcessed: Int, speed: Double)
        fun onFallbackToSoftware() {}
        fun onCompleted(output: File)
        fun onError(error: Throwable)
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var session: FFmpegSession? = null
    @Volatile private var callback: Callback? = null
    @Volatile private var cancelled = false

    fun isRunning(): Boolean = session != null

    fun start(
        sourceUrl: String,
        output: File,
        sourceVideoCodec: String?,
        sourceAudioCodec: String?,
        callback: Callback
    ) {
        check(session == null) { "A local optimization is already running" }
        require(sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")) {
            "Unsupported Jellyfin source URL"
        }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        this.callback = callback
        cancelled = false

        val videoCodec = sourceVideoCodec?.lowercase().orEmpty()
        // Try Android's hardware HEVC decoder first for HEVC/H.265 sources.
        // Some devices cannot expose 10-bit HEVC through MediaCodec, so retry
        // the exact same conversion with FFmpeg's software decoder on failure.
        val tryHardwareHevc = videoCodec in setOf("hevc", "h265", "hev1", "hvc1")
        val audioCopyCodecs = setOf("aac", "mp3")
        val audioMode = if (sourceAudioCodec?.lowercase()?.let { it in audioCopyCodecs } == true) "copy" else "aac"

        fun finishWithError(finished: FFmpegSession) {
            output.delete()
            val detail = (finished.output ?: finished.allLogsAsString ?: finished.failStackTrace)
                .orEmpty()
                .replace(Regex("(?i)(api_key=)[^&\\s]+"), "$1[redacted]")
                .takeLast(1800)
                .ifBlank { "FFmpeg returned ${finished.returnCode ?: "no return code"}" }
            val cb = this.callback
            this.callback = null
            handler.post { cb?.onError(IllegalStateException(detail)) }
        }

        lateinit var runAttempt: (Boolean) -> Unit
        runAttempt = { useHardwareDecoder ->
            if (cancelled) {
                output.delete()
            } else {
                if (output.exists()) output.delete()
                val args = mutableListOf(
                    "-y",
                    "-hide_banner",
                    "-loglevel", "info",
                    "-stats_period", "0.5",
                    "-threads", "0"
                )
                if (useHardwareDecoder) {
                    // Select the Android MediaCodec HEVC decoder for this input.
                    args.addAll(listOf("-c:v", "hevc_mediacodec"))
                }
                args.addAll(listOf(
                    "-i", sourceUrl,
                    "-map", "0:v:0",
                    "-map", "0:a:0?",
                    "-sn",
                    "-vf", "scale=-2:1080:flags=fast_bilinear",
                    "-c:v", "h264_mediacodec",
                    "-b:v", "5M",
                    "-c:a", audioMode,
                    "-b:a", "192k",
                    "-movflags", "+faststart",
                    "-f", "mov",
                    "-brand", "mp42",
                    output.absolutePath
                ))

                try {
                    val created = FFmpegKit.executeWithArgumentsAsync(
                        args.toTypedArray(),
                        { finished ->
                            session = null
                            if (cancelled || ReturnCode.isCancel(finished.returnCode)) {
                                output.delete()
                                this.callback = null
                                return@executeWithArgumentsAsync
                            }
                            if (ReturnCode.isSuccess(finished.returnCode) && output.exists() && output.length() > 0L) {
                                val cb = this.callback
                                this.callback = null
                                handler.post { cb?.onCompleted(output) }
                            } else if (useHardwareDecoder) {
                                // Hardware decode can fail for unsupported profiles/bit depths.
                                // Retry once with software decode; never loop between paths.
                                output.delete()
                                handler.post { this.callback?.onFallbackToSoftware() }
                                runAttempt(false)
                            } else {
                                finishWithError(finished)
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
                    if (useHardwareDecoder && !cancelled) {
                        output.delete()
                        handler.post { this.callback?.onFallbackToSoftware() }
                        runAttempt(false)
                    } else {
                        output.delete()
                        val cb = this.callback
                        this.callback = null
                        handler.post { cb?.onError(error) }
                    }
                }
            }
        }

        runAttempt(tryHardwareHevc)
    }

    fun cancel() {
        cancelled = true
        callback = null
        val active = session
        session = null
        if (active != null) runCatching { active.cancel() }
    }
}
