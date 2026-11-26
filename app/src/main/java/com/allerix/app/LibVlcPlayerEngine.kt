package com.allerix.app

import android.content.Context
import android.net.Uri
import android.view.View
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * LibVLC fallback engine. It owns only the decoder and rendering surface;
 * Allervue's PlayerActivity continues to own the shared controls and gestures.
 */
class LibVlcPlayerEngine(context: Context) : PlayerEngine {
    private val appContext = context.applicationContext
    private val libVlc = LibVLC(appContext, arrayListOf(
        "--no-video-title-show",
        "--audio-time-stretch",
        "--avcodec-hw=any"
    ))
    private val mediaPlayer = MediaPlayer(libVlc)
    private val videoLayout = VLCVideoLayout(context)

    override val id: PlaybackEngineId = PlaybackEngineId.LIBVLC
    override val view: View get() = videoLayout

    private var listener: PlayerEngine.Listener? = null
    private var requestedAudioTrackId: String? = null
    private var requestedSubtitleTrackId: String? = null
    private var lastRequest: PlaybackRequest? = null

    private val eventListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Opening,
            MediaPlayer.Event.Buffering -> listener?.onBuffering(true)
            MediaPlayer.Event.Playing -> {
                listener?.onBuffering(false)
                listener?.onReady()
                applyRequestedTracks()
            }
            MediaPlayer.Event.Paused,
            MediaPlayer.Event.Stopped -> listener?.onBuffering(false)
            MediaPlayer.Event.EndReached -> listener?.onEnded()
            MediaPlayer.Event.EncounteredError -> listener?.onFailure(
                IllegalStateException("LibVLC encountered a playback error")
            )
            MediaPlayer.Event.Vout -> listener?.onTracksChanged()
        }
    }

    init {
        mediaPlayer.attachViews(videoLayout, null, false, false)
        mediaPlayer.setEventListener(eventListener)
    }

    override fun setListener(listener: PlayerEngine.Listener) {
        this.listener = listener
    }

    override fun play(request: PlaybackRequest) {
        lastRequest = request
        val media = Media(libVlc, Uri.parse(request.uri))
        request.headers.forEach { (name, value) ->
            if (name.equals("User-Agent", true)) media.addOption(":http-user-agent=$value")
            else if (name.equals("Referer", true)) media.addOption(":http-referrer=$value")
            else media.addOption(":http-header=$name: $value")
        }
        request.mimeType?.let { media.addOption(":demux=$it") }
        mediaPlayer.media = media
        media.release()
        if (request.startPositionMs > 0L) {
            mediaPlayer.setTime(request.startPositionMs)
        }
        mediaPlayer.play()
        if (!request.playWhenReady) mediaPlayer.pause()
    }

    override fun pause() {
        mediaPlayer.pause()
    }

    override fun resume() {
        mediaPlayer.play()
    }

    override fun seekTo(positionMs: Long) {
        mediaPlayer.setTime(positionMs.coerceAtLeast(0L))
    }

    override fun setPlaybackSpeed(speed: Float) {
        mediaPlayer.setRate(speed.coerceIn(0.25f, 4f))
    }

    override fun selectAudioTrack(trackId: String?) {
        requestedAudioTrackId = trackId
        applyRequestedTracks()
    }

    override fun selectSubtitleTrack(trackId: String?) {
        requestedSubtitleTrackId = trackId
        applyRequestedTracks()
    }

    private fun applyRequestedTracks() {
        requestedAudioTrackId?.toIntOrNull()?.let { mediaPlayer.setAudioTrack(it) }
        requestedSubtitleTrackId?.toIntOrNull()?.let { mediaPlayer.setSpuTrack(it) }
    }

    override fun snapshot(): PlaybackSnapshot = PlaybackSnapshot(
        positionMs = mediaPlayer.time.coerceAtLeast(0L),
        durationMs = mediaPlayer.length.coerceAtLeast(0L),
        isPlaying = mediaPlayer.isPlaying,
        isBuffering = mediaPlayer.isPlaying.not() && mediaPlayer.time >= 0L,
        audioTrackId = requestedAudioTrackId,
        subtitleTrackId = requestedSubtitleTrackId
    )

    override fun release() {
        listener = null
        mediaPlayer.setEventListener(null)
        mediaPlayer.detachViews()
        mediaPlayer.release()
        libVlc.release()
    }
}
