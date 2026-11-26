package com.allerix.app

import android.content.Context

/**
 * Owns the active decoder and its surface. PlayerActivity keeps ownership of the
 * shared controls/overlays and supplies the media request when a decoder changes.
 */
class PlaybackEngineManager(
    context: Context,
    initialEngine: ExoPlayerEngine,
    private val onSurfaceChanged: (PlayerEngine) -> Unit,
    private val onFailure: (PlaybackEngineId, Throwable) -> Unit,
    private val onReady: (PlaybackEngineId) -> Unit = {},
    private val onBuffering: (Boolean) -> Unit = {}
) {
    private val viewContext = context
    private val exoEngine = initialEngine
    var activeEngine: PlayerEngine = initialEngine
        private set

    init {
        attachListener(initialEngine)
    }

    private fun attachListener(engine: PlayerEngine) {
        engine.setListener(object : PlayerEngine.Listener {
            override fun onReady() = this@PlaybackEngineManager.onReady(engine.id)
            override fun onBuffering(buffering: Boolean) = this@PlaybackEngineManager.onBuffering(buffering)
            override fun onEnded() {}
            override fun onFailure(error: Throwable) = this@PlaybackEngineManager.onFailure(engine.id, error)
            override fun onTracksChanged() {}
        })
    }

    fun switchTo(engineId: PlaybackEngineId, request: PlaybackRequest): Boolean {
        if (engineId == activeEngine.id) return true
        val next = when (engineId) {
            PlaybackEngineId.EXOPLAYER -> exoEngine
            PlaybackEngineId.MPV -> MpvPlayerEngine(viewContext)
            PlaybackEngineId.LIBVLC -> LibVlcPlayerEngine(viewContext)
        }

        val previous = activeEngine
        return try {
            attachListener(next)
            activeEngine = next
            onSurfaceChanged(next)
            next.setPlaybackSpeed(request.playbackSpeed)
            next.play(request)
            if (previous !== exoEngine) previous.release()
            true
        } catch (error: Throwable) {
            activeEngine = previous
            runCatching { onSurfaceChanged(previous) }
            if (next !== exoEngine) runCatching { next.release() }
            onFailure(engineId, error)
            false
        }
    }

    fun snapshot(): PlaybackSnapshot = activeEngine.snapshot()

    fun pause() = activeEngine.pause()

    fun resume() = activeEngine.resume()

    fun isPlaying(): Boolean = activeEngine.snapshot().isPlaying

    fun positionMs(): Long = activeEngine.snapshot().positionMs

    fun durationMs(): Long = activeEngine.snapshot().durationMs

    fun bufferedPositionMs(): Long = activeEngine.snapshot().bufferedPositionMs

    fun seekTo(positionMs: Long) = activeEngine.seekTo(positionMs)

    fun setPlaybackSpeed(speed: Float) = activeEngine.setPlaybackSpeed(speed)

    fun selectAudioTrack(trackId: String?) = activeEngine.selectAudioTrack(trackId)

    fun selectSubtitleTrack(trackId: String?) = activeEngine.selectSubtitleTrack(trackId)

    fun release() {
        if (activeEngine !== exoEngine) activeEngine.release()
        activeEngine = exoEngine
    }
}
