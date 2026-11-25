package com.allerix.app

import android.view.View
import android.widget.FrameLayout

/**
 * Engine-neutral playback contract. Engine implementations own decoding/rendering;
 * PlayerActivity owns the controls, session reporting and track intent.
 */
enum class PlaybackEngineId { EXOPLAYER, MPV, LIBVLC }

data class PlaybackRequest(
    val uri: String,
    val mimeType: String? = null,
    val startPositionMs: Long = 0L,
    val playWhenReady: Boolean = true,
    val audioTrackId: String? = null,
    val subtitleTrackId: String? = null,
    val headers: Map<String, String> = emptyMap()
)

data class PlaybackSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val audioTrackId: String? = null,
    val subtitleTrackId: String? = null
)

interface PlayerEngine {
    val id: PlaybackEngineId
    val view: View
    fun setListener(listener: Listener)
    fun play(request: PlaybackRequest)
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun selectAudioTrack(trackId: String?)
    fun selectSubtitleTrack(trackId: String?)
    fun snapshot(): PlaybackSnapshot
    fun release()

    interface Listener {
        fun onReady()
        fun onBuffering(buffering: Boolean)
        fun onEnded()
        fun onFailure(error: Throwable)
        fun onTracksChanged()
    }
}

enum class PlaybackFailureKind { CODEC_OR_CONTAINER, NETWORK, AMBIGUOUS }

enum class PlaybackAction {
    RETRY_SAME_ENGINE,
    TRY_FORMAT_VARIATION,
    SWITCH_TO_NEXT_ENGINE,
    STOP
}

/**
 * Runtime policy for ExoPlayer -> libmpv -> LibVLC.
 * Network/ambiguous errors never cause an engine swap on their first occurrence.
 */
class PlaybackOrchestrator(
    initialEngine: PlaybackEngineId = PlaybackEngineId.EXOPLAYER
) {
    private val hierarchy = listOf(
        PlaybackEngineId.EXOPLAYER,
        PlaybackEngineId.MPV,
        PlaybackEngineId.LIBVLC
    )

    var currentEngine: PlaybackEngineId = initialEngine
        private set
    private var sameEngineRetries = 0
    private var formatVariationTried = false

    fun resetForNewItem() {
        sameEngineRetries = 0
        formatVariationTried = false
        currentEngine = PlaybackEngineId.EXOPLAYER
    }

    fun selectEngine(engine: PlaybackEngineId) {
        currentEngine = engine
        sameEngineRetries = 0
        formatVariationTried = false
    }

    fun decide(kind: PlaybackFailureKind): PlaybackAction = when (kind) {
        PlaybackFailureKind.NETWORK, PlaybackFailureKind.AMBIGUOUS -> {
            if (sameEngineRetries < 1) {
                sameEngineRetries++
                PlaybackAction.RETRY_SAME_ENGINE
            } else {
                PlaybackAction.STOP
            }
        }
        PlaybackFailureKind.CODEC_OR_CONTAINER -> when {
            !formatVariationTried && currentEngine == PlaybackEngineId.EXOPLAYER -> {
                formatVariationTried = true
                PlaybackAction.TRY_FORMAT_VARIATION
            }
            else -> {
                val index = hierarchy.indexOf(currentEngine)
                if (index >= 0 && index < hierarchy.lastIndex) {
                    currentEngine = hierarchy[index + 1]
                    sameEngineRetries = 0
                    formatVariationTried = false
                    PlaybackAction.SWITCH_TO_NEXT_ENGINE
                } else PlaybackAction.STOP
            }
        }
    }

    fun nextEngine(): PlaybackEngineId? {
        val index = hierarchy.indexOf(currentEngine)
        return hierarchy.getOrNull(index + 1)
    }
}
