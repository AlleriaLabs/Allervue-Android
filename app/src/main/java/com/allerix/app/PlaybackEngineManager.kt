package com.allerix.app

import android.content.Context

/**
 * Owns the active decoder and its surface. A previous engine is kept alive until
 * the replacement reports READY, so an asynchronous startup failure can roll back.
 */
class PlaybackEngineManager(
    context: Context,
    initialEngine: ExoPlayerEngine,
    private val onSurfaceChanged: (PlayerEngine) -> Unit,
    private val onFailure: (PlaybackEngineId, Throwable) -> Unit,
    private val onReady: (PlaybackEngineId) -> Unit = {},
    private val onBufferingChanged: (Boolean) -> Unit = {},
    private val onEnded: (PlaybackEngineId) -> Unit = {}
) {
    private val viewContext = context
    private val exoEngine = initialEngine
    var activeEngine: PlayerEngine = initialEngine
        private set
    private var pendingPrevious: PlayerEngine? = null

    init { attachListener(initialEngine) }

    private fun attachListener(engine: PlayerEngine) {
        engine.setListener(object : PlayerEngine.Listener {
            override fun onReady() {
                if (activeEngine !== engine) return
                pendingPrevious?.let { previous ->
                    if (previous !== exoEngine) previous.release()
                    pendingPrevious = null
                }
                onReady(engine.id)
            }
            override fun onBuffering(buffering: Boolean) {
                if (activeEngine === engine) this@PlaybackEngineManager.onBufferingChanged(buffering)
            }
            override fun onEnded() {
                if (activeEngine === engine) onEnded(engine.id)
            }
            override fun onFailure(error: Throwable) {
                if (activeEngine !== engine) return
                val failed = activeEngine
                val previous = pendingPrevious
                if (previous != null) {
                    activeEngine = previous
                    pendingPrevious = null
                    runCatching { onSurfaceChanged(previous) }
                    if (failed !== exoEngine) runCatching { failed.release() }
                }
                onFailure(engine.id, error)
            }
            override fun onTracksChanged() { }
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
            pendingPrevious = previous
            activeEngine = next
            onSurfaceChanged(next)
            next.setPlaybackSpeed(request.playbackSpeed)
            next.play(request)
            true
        } catch (error: Throwable) {
            activeEngine = previous
            pendingPrevious = null
            runCatching { onSurfaceChanged(previous) }
            if (next !== exoEngine) runCatching { next.release() }
            onFailure(engineId, error)
            false
        }
    }

    /** Replays the last request on the current engine after a transient failure. */
    fun retryCurrent(request: PlaybackRequest) {
        activeEngine.play(request)
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
    fun selectAudioTrack(track: PlaybackTrackSelection?) = activeEngine.selectAudioTrack(track)
    fun selectSubtitleTrack(track: PlaybackTrackSelection?) = activeEngine.selectSubtitleTrack(track)

    fun release() {
        pendingPrevious?.let { if (it !== exoEngine) it.release() }
        pendingPrevious = null
        if (activeEngine !== exoEngine) activeEngine.release()
        activeEngine = exoEngine
    }
}
