package com.allerix.app

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import `is`.xyz.mpv.MPVLib
import java.io.File

/**
 * Minimal libmpv surface for Allervue's decoder fallback.
 * The view owns one native mpv instance and exposes transport methods to PlayerActivity.
 */
class AllervueMpvView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private var initialized = false
    private var attached = false
    private var pendingUrl: String? = null
    private var destroyed = false

    init { holder.addCallback(this) }

    fun initialize() {
        if (initialized || destroyed) return
        val config = File(context.filesDir, "mpv").apply { mkdirs() }
        val cache = File(context.cacheDir, "mpv").apply { mkdirs() }
        MPVLib.create(context.applicationContext)
        MPVLib.setOptionString("config", "no")
        MPVLib.setOptionString("profile", "fast")
        MPVLib.setOptionString("alang", "eng")
        MPVLib.setOptionString("slang", "eng")
        MPVLib.setOptionString("idle", "once")
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("hwdec", "auto-safe")
        MPVLib.setOptionString("hwdec-codecs", "hevc,h264,vp9,av1")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("config-dir", config.absolutePath)
        MPVLib.setOptionString("gpu-shader-cache-dir", cache.absolutePath)
        MPVLib.setOptionString("icc-cache-dir", cache.absolutePath)
        MPVLib.setOptionString("demuxer-max-bytes", "67108864")
        MPVLib.setOptionString("demuxer-max-back-bytes", "16777216")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("idle", "once")
        MPVLib.init()
        initialized = true
        if (holder.surface.isValid) attachAndLoad()
    }

    fun play(url: String, positionMs: Long = 0L) {
        if (!initialized) initialize()
        pendingUrl = url
        pendingPositionMs = positionMs
        if (attached) loadPending(positionMs)
    }

    private var pendingPositionMs = 0L

    private fun loadPending(positionMs: Long = pendingPositionMs) {
        val url = pendingUrl ?: return
        pendingUrl = null
        pendingPositionMs = 0L
        MPVLib.command(arrayOf("loadfile", url, "replace"))
        if (positionMs > 0) {
            MPVLib.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute+exact"))
        }
    }

    private fun attachAndLoad() {
        if (!initialized || attached || destroyed || !holder.surface.isValid) return
        MPVLib.attachSurface(holder.surface)
        attached = true
        MPVLib.setOptionString("force-window", "yes")
        loadPending()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { attachAndLoad() }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (initialized && attached) MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (initialized && attached) {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.detachSurface()
            attached = false
        }
    }

    fun pausePlayback() { if (initialized) MPVLib.setPropertyBoolean("pause", true) }
    fun resumePlayback() { if (initialized) MPVLib.setPropertyBoolean("pause", false) }
    fun togglePlayback() {
        if (!initialized) return
        MPVLib.setPropertyBoolean("pause", !(MPVLib.getPropertyBoolean("pause") ?: false))
    }
    fun seekTo(positionMs: Long) {
        if (initialized) MPVLib.command(arrayOf("seek", (positionMs.coerceAtLeast(0) / 1000.0).toString(), "absolute+exact"))
    }
    fun seekBy(deltaMs: Long) {
        if (initialized) MPVLib.command(arrayOf("seek", (deltaMs / 1000.0).toString(), "relative"))
    }
    fun setSpeed(value: Float) { if (initialized) MPVLib.setPropertyDouble("speed", value.toDouble()) }
    fun positionMs(): Long = ((MPVLib.getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong()
    fun durationMs(): Long = ((MPVLib.getPropertyDouble("duration") ?: 0.0) * 1000).toLong()
    fun isPlaying(): Boolean = initialized && !(MPVLib.getPropertyBoolean("pause") ?: true)

    fun release() {
        if (destroyed) return
        destroyed = true
        holder.removeCallback(this)
        if (initialized) {
            if (attached) runCatching { MPVLib.detachSurface() }
            runCatching { MPVLib.destroy() }
        }
        attached = false
        initialized = false
    }
}
