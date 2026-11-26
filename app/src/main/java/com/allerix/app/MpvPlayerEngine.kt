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
    private var requestedAudioTrack: PlaybackTrackSelection? = null
    private var requestedSubtitleTrack: PlaybackTrackSelection? = null
    private var requestedPlayWhenReady = true
    private var requestedSpeed = 1f
    private var pendingStartPositionMs = 0L
    private var initialized = false
    private var generation = 0

    override fun setListener(listener: PlayerEngine.Listener) {
        this.listener = listener
    }

    override fun play(request: PlaybackRequest) {
        val requestGeneration = ++generation
        requestedAudioTrack = request.audioTrack
        requestedSubtitleTrack = request.subtitleTrack
        requestedPlayWhenReady = request.playWhenReady
        requestedSpeed = request.playbackSpeed.coerceIn(0.25f, 4f)
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
                pendingStartPositionMs = request.startPositionMs.coerceAtLeast(0L)
                videoView.playFile(request.uri)
                videoView.speed = requestedSpeed.toDouble()
                videoView.paused = !requestedPlayWhenReady
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
                        if (pendingStartPositionMs > 0L) {
                            videoView.timePos = pendingStartPositionMs / 1000.0
                            pendingStartPositionMs = 0L
                        }
                        videoView.speed = requestedSpeed.toDouble()
                        if (!requestedPlayWhenReady) videoView.paused = true
                        applyRequestedTracks()
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

    private fun normalizedLanguage(value: String?): String {
        val tag = value.orEmpty().lowercase(java.util.Locale.ROOT).substringBefore('-').substringBefore('_')
        return when (tag) {
            "eng" -> "en"; "spa" -> "es"; "jpn" -> "ja"; "kor" -> "ko"
            "zho", "chi" -> "zh"; "fra", "fre" -> "fr"; "deu", "ger" -> "de"
            "ita" -> "it"; "por" -> "pt"; "rus" -> "ru"; "ara" -> "ar"
            "fil", "tgl" -> "fil"; else -> tag
        }
    }

    private fun applyRequestedTracks() {
        if (!initialized) return
        val tracks = runCatching { videoView.tracks }.getOrDefault(emptyList())
        val audio = requestedAudioTrack
        val audioMatch = audio?.let { selected ->
            tracks.firstOrNull { track ->
                track.type == io.github.yuroyami.libmpvkt.TrackType.Audio &&
                    (track.ffIndex == selected.streamIndex || track.srcId == selected.streamIndex)
            } ?: tracks.firstOrNull { track ->
                track.type == io.github.yuroyami.libmpvkt.TrackType.Audio &&
                    normalizedLanguage(track.lang) == normalizedLanguage(selected.language) &&
                    normalizedLanguage(selected.language).isNotEmpty()
            } ?: tracks.firstOrNull { track ->
                track.type == io.github.yuroyami.libmpvkt.TrackType.Audio &&
                    selected.title != null && track.title.equals(selected.title, true)
            }
        }
        videoView.audioTrack = audioMatch?.let { TrackSelection.Id(it.id) }
            ?: if (audio == null) TrackSelection.Auto else videoView.audioTrack

        val subtitle = requestedSubtitleTrack
        val subtitleMatch = subtitle?.let { selected ->
            tracks.firstOrNull { track ->
                track.type == io.github.yuroyami.libmpvkt.TrackType.Sub &&
                    (track.ffIndex == selected.streamIndex || track.srcId == selected.streamIndex)
            } ?: tracks.firstOrNull { track ->
                track.type == io.github.yuroyami.libmpvkt.TrackType.Sub &&
                    normalizedLanguage(track.lang) == normalizedLanguage(selected.language) &&
                    normalizedLanguage(selected.language).isNotEmpty()
            } ?: tracks.firstOrNull { track ->
                track.type == io.github.yuroyami.libmpvkt.TrackType.Sub &&
                    selected.title != null && track.title.equals(selected.title, true)
            }
        }
        videoView.subtitleTrack = subtitleMatch?.let { TrackSelection.Id(it.id) } ?: TrackSelection.No
    }

    override fun pause() {
        requestedPlayWhenReady = false
        if (initialized) videoView.paused = true
    }

    override fun resume() {
        requestedPlayWhenReady = true
        if (initialized) videoView.paused = false
    }

    override fun seekTo(positionMs: Long) {
        if (initialized) videoView.timePos = positionMs.coerceAtLeast(0L) / 1000.0
    }

    override fun setPlaybackSpeed(speed: Float) {
        requestedSpeed = speed.coerceIn(0.25f, 4f)
        if (initialized) videoView.speed = requestedSpeed.toDouble()
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
            return PlaybackSnapshot(positionMs = 0L, durationMs = 0L, isPlaying = false, isBuffering = false, playWhenReady = requestedPlayWhenReady, playbackSpeed = requestedSpeed)
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
            playWhenReady = requestedPlayWhenReady,
            playbackSpeed = requestedSpeed
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
