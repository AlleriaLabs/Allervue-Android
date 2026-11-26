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
    private var requestedAudioTrack: PlaybackTrackSelection? = null
    private var requestedSubtitleTrack: PlaybackTrackSelection? = null

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
            applyRequestedTracks()
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
        requestedAudioTrack = request.audioTrack
        requestedSubtitleTrack = request.subtitleTrack
        player.setMediaItem(item, request.startPositionMs.coerceAtLeast(0L))
        player.prepare()
        player.playbackParameters = PlaybackParameters(request.playbackSpeed.coerceIn(0.25f, 4f))
        player.playWhenReady = request.playWhenReady
    }

    override fun pause() {
        player.pause()
    }

    override fun resume() {
        player.play()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 4f))
    }

    override fun selectAudioTrack(track: PlaybackTrackSelection?) {
        requestedAudioTrack = track
        applyRequestedTracks()
    }

    override fun selectSubtitleTrack(track: PlaybackTrackSelection?) {
        requestedSubtitleTrack = track
        applyRequestedTracks()
    }

    private fun applyRequestedTracks() {
        if (player.currentTracks.groups.isEmpty()) return
        val builder = player.trackSelectionParameters.buildUpon()
        val audioGroups = player.currentTracks.groups.filter {
            it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSupported
        }
        requestedAudioTrack?.let { selected ->
            val group = audioGroups.firstOrNull { candidate ->
                (0 until candidate.length).any { index ->
                    val format = candidate.getTrackFormat(index)
                    format.id?.toIntOrNull() == selected.streamIndex ||
                        (selected.language != null && format.language.equals(selected.language, true))
                }
            }
            if (group != null) builder.setOverrideForType(
                androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0)
            )
        }
        val textGroups = player.currentTracks.groups.filter {
            it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSupported
        }
        val subtitle = requestedSubtitleTrack
        if (subtitle == null) {
            builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
        } else {
            val group = textGroups.firstOrNull { candidate ->
                (0 until candidate.length).any { index ->
                    val format = candidate.getTrackFormat(index)
                    format.id?.toIntOrNull() == subtitle.streamIndex ||
                        (subtitle.language != null && format.language.equals(subtitle.language, true))
                }
            }
            if (group != null) {
                builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(
                        androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0)
                    )
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    override fun snapshot(): PlaybackSnapshot = PlaybackSnapshot(
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = player.duration.takeIf { it >= 0L } ?: 0L,
        isPlaying = player.isPlaying,
        isBuffering = player.playbackState == Player.STATE_BUFFERING,
        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
        playWhenReady = player.playWhenReady,
        playbackSpeed = player.playbackParameters.speed
    )

    override fun release() {
        player.removeListener(mediaListener)
        listener = null
        // PlayerActivity owns and releases the underlying ExoPlayer instance.
    }
}
