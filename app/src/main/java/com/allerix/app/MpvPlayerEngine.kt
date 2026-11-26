package com.allerix.app

import android.content.Context
import android.view.View
import io.github.yuroyami.libmpvkt.EndFileReason
import io.github.yuroyami.libmpvkt.HwdecMode
import io.github.yuroyami.libmpvkt.MpvEvent
import io.github.yuroyami.libmpvkt.MpvPlaybackState
import io.github.yuroyami.libmpvkt.TrackSelection
import io.github.yuroyami.libmpvkt.view.MpvOptions
import io.github.yuroyami.libmpvkt.view.MpvView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * libmpv fallback renderer. The activity continues to own the shared controls,
 * gesture layer and overlays; this adapter only owns decoding and its video surface.
 */
class MpvPlayerEngine(context: Context) : PlayerEngine {
    private val videoView = MpvView(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val id: PlaybackEngineId = PlaybackEngineId.MPV
    override val view: View get() = videoView

    private var listener: PlayerEngine.Listener? = null
    private var requestedAudioTrackId: String? = null
    private var requestedSubtitleTrackId: String? = null
    private var initialized = false
    private var generation = 0

    override fun setListener(listener: PlayerEngine.Listener) {
        this.listener = listener
    }

    override fun play(request: PlaybackRequest) {
        val requestGeneration = ++generation
        requestedAudioTrackId = request.audioTrackId
        requestedSubtitleTrackId = request.subtitleTrackId
        scope.launch {
            try {
                if (!initialized) {
                    val headers = request.headers.map { (key, value) -> "$key: $value" }
                    val options = MpvOptions(
                        hwdec = HwdecMode.Auto,
                        inputDefaultBindings = false,
                        extra = buildMap {
                            if (headers.isNotEmpty()) put("http-header-fields", headers.joinToString(","))
                            put("osc", "no")
                            put("sub-ass-override", "no")
                        }
                    )
                    val prepared = withContext(Dispatchers.IO) { videoView.prepare(options) }
                    if (requestGeneration != generation) {
                        prepared.close()
                        return@launch
                    }
                    videoView.attach(prepared)
                    initialized = true
                    observePlayback()
                }
                videoView.playFile(request.uri)
                if (request.startPositionMs > 0L) {
                    videoView.timePos = request.startPositionMs / 1000.0
                }
                videoView.paused = !request.playWhenReady
                applyRequestedTracks()
            } catch (error: Throwable) {
                listener?.onFailure(error)
            }
        }
    }

    private fun observePlayback() {
        val core = videoView.mpv ?: return
        scope.launch {
            core.playback.collect { state ->
                when (state.status) {
                    MpvPlaybackState.Status.Loading,
                    MpvPlaybackState.Status.Buffering -> listener?.onBuffering(true)
                    MpvPlaybackState.Status.Playing -> {
                        listener?.onBuffering(false)
                        listener?.onReady()
                    }
                    MpvPlaybackState.Status.Paused,
                    MpvPlaybackState.Status.Idle -> listener?.onBuffering(false)
                    MpvPlaybackState.Status.Ended -> {
                        listener?.onBuffering(false)
                        listener?.onEnded()
                    }
                }
            }
        }
        scope.launch {
            core.events.collect { event ->
                if (event is MpvEvent.EndFile && event.reason == EndFileReason.Error) {
                    listener?.onFailure(IllegalStateException(event.fileError ?: "libmpv playback failed"))
                }
            }
        }
    }

    private fun applyRequestedTracks() {
        requestedAudioTrackId?.toIntOrNull()?.let { videoView.audioTrack = TrackSelection.Id(it) }
        requestedSubtitleTrackId?.let { id ->
            videoView.subtitleTrack = id.toIntOrNull()?.let(TrackSelection::Id) ?: TrackSelection.Auto
        }
    }

    override fun pause() {
        if (initialized) videoView.paused = true
    }

    override fun resume() {
        if (initialized) videoView.paused = false
    }

    override fun seekTo(positionMs: Long) {
        if (initialized) videoView.timePos = positionMs.coerceAtLeast(0L) / 1000.0
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (initialized) videoView.speed = speed.coerceIn(0.25f, 4f).toDouble()
    }

    override fun selectAudioTrack(trackId: String?) {
        requestedAudioTrackId = trackId
        if (initialized) applyRequestedTracks()
    }

    override fun selectSubtitleTrack(trackId: String?) {
        requestedSubtitleTrackId = trackId
        if (initialized) applyRequestedTracks()
    }

    override fun snapshot(): PlaybackSnapshot {
        if (!initialized) {
            return PlaybackSnapshot(positionMs = 0L, durationMs = 0L, isPlaying = false, isBuffering = false, audioTrackId = requestedAudioTrackId, subtitleTrackId = requestedSubtitleTrackId)
        }
        val position = ((videoView.timePos ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
        val duration = ((videoView.duration ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
        val buffering = videoView.playback?.value?.status in setOf(
            MpvPlaybackState.Status.Loading,
            MpvPlaybackState.Status.Buffering
        )
        return PlaybackSnapshot(
            positionMs = position,
            durationMs = duration,
            isPlaying = !videoView.paused,
            isBuffering = buffering,
            audioTrackId = requestedAudioTrackId,
            subtitleTrackId = requestedSubtitleTrackId
        )
    }

    override fun release() {
        generation++
        listener = null
        scope.cancel()
        videoView.destroy()
        initialized = false
    }
}
