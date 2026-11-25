package com.allerix.app

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Media3 implementation of the engine-neutral contract.
 *
 * PlayerActivity continues to own its existing ExoPlayer instance during the migration;
 * this adapter gives the orchestrator a stable surface and transport API without changing
 * the established Allervue player UI.
 */
class ExoPlayerEngine(
    private val context: Context,
    private val player: ExoPlayer,
    private val playerView: PlayerView
) : PlayerEngine {
    override val id: PlaybackEngineId = PlaybackEngineId.EXOPLAYER
    override val view: View get() = playerView

    private var listener: PlayerEngine.Listener? = null
    private var lastAudioTrackId: String? = null
    private var lastSubtitleTrackId: String? = null

    private val mediaListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> listener?.onReady()
                Player.STATE_ENDED -> listener?.onEnded()
            }
            listener?.onBuffering(playbackState == Player.STATE_BUFFERING)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) listener?.onBuffering(false)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            listener?.onFailure(error)
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            listener?.onTracksChanged()
        }
    }

    init {
        playerView.player = player
        player.addListener(mediaListener)
    }

    override fun setListener(listener: PlayerEngine.Listener) {
        this.listener = listener
    }

    override fun play(request: PlaybackRequest) {
        val item = MediaItem.Builder()
            .setUri(Uri.parse(request.uri))
            .apply { request.mimeType?.let(::setMimeType) }
            .build()
        player.setMediaItem(item, request.startPositionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = request.playWhenReady
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 4f))
    }

    /**
     * Track IDs are retained as engine-neutral intent until the shared track mapper is
     * connected. Media3's current UI continues to perform its existing native selection.
     */
    override fun selectAudioTrack(trackId: String?) {
        lastAudioTrackId = trackId
    }

    override fun selectSubtitleTrack(trackId: String?) {
        lastSubtitleTrackId = trackId
    }

    override fun snapshot(): PlaybackSnapshot = PlaybackSnapshot(
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = player.duration.takeIf { it >= 0L } ?: 0L,
        isPlaying = player.isPlaying,
        isBuffering = player.playbackState == Player.STATE_BUFFERING,
        audioTrackId = lastAudioTrackId,
        subtitleTrackId = lastSubtitleTrackId
    )

    override fun release() {
        player.removeListener(mediaListener)
        listener = null
        // PlayerActivity owns and releases the underlying ExoPlayer instance.
    }
}
