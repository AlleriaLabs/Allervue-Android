package com.allerix.app

import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Rational
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.log10

/**
 * Allervue player: floating glass control card, selectable decoder (HW / HW+ / SW), 4K/8K direct play,
 * custom subtitle engine (srt, ass/ssa, vtt, text sub) with style, sync and swipe gestures,
 * and software volume boost up to 200%.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val accent = 0xFFD8B77E.toInt()

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var hud: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var controls: FrameLayout
    private lateinit var lockOverlay: ImageView
    private lateinit var titleView: TextView
    private lateinit var subTitle: TextView
    private lateinit var badgeRow: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var curTime: TextView
    private lateinit var durTime: TextView
    private lateinit var playBtn: ImageView
    private lateinit var prevEpisodeBtn: TextView
    private lateinit var nextEpisodeBtn: TextView
    private lateinit var skipIntroBtn: TextView
    private lateinit var subText: TextView
    private lateinit var speedLabel: TextView
    private lateinit var decoderLabel: TextView
    private lateinit var audio: AudioManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var itemId = ""
    private var offlinePath: String? = null
    private var sourceId = ""
    private var sessionId = UUID.randomUUID().toString().replace("-", "")
    private var quality = Quality.ORIGINAL
    private var audioMode = AudioMode.ORIGINAL
    private var decoder = DecoderMode.HW
    private var playbackEngine = PlaybackEngine.AUTO
    private var vlcFallbackAttempted = false
    private var siblings: List<Item> = emptyList()
    private var siblingIndex = -1
    private var introSegments: List<Pair<Long, Long>> = emptyList()
    private var plan: PlaybackPlan? = null
    private var autoRetried = false

    // subtitles (own engine)
    private var subStreams: List<MediaStream> = emptyList()
    private var audioStreams: List<MediaStream> = emptyList()
    private var defaultAudioApplied = false
    private var defaultSubtitleApplied = false
    private var cues: List<SubCue> = emptyList()
    private var subOn = false
    private var activeSub = -1          // index in subStreams, -2 = loaded from file, -1 = none
    private var subDelayMs = 0L
    private var subPos = 0.12f          // bottom offset as a fraction of screen height
    private var nativeOn = false        // an image-based (PGS/DVB) track is shown by ExoPlayer
    private var shownText = ""
    private var previewUntil = 0L
    private var subDrag = false
    private var subMode = 0             // 0 undecided, 1 reposition, 2 jump
    private var subDownX = 0f
    private var subDownY = 0f
    private var subStartPos = 0f

    // volume boost
    private var enhancer: LoudnessEnhancer? = null
    private var boostPct = 100
    private var lastVolume = 0

    private var locked = false
    private var longPressing = false
    private var controlsVisible = false
    private var scrubbing = false
    private var audioOnly = false
    private var loopOne = false
    private var autoPlayNext = true
    private var speed = 1f
    private var nowTag = ""
    private var startupStartedAt = 0L

    private enum class Mode { NONE, SEEK, BRIGHT, VOLUME }
    private var mode = Mode.NONE
    private var startPos = 0L
    private var seekTarget = 0L
    private var brightness = 0.5f
    private var volF = 0f

    private val isTranscoding get() = offlinePath == null && plan?.transcoding == true
    private val hasNext get() = siblingIndex >= 0 && siblingIndex < siblings.size - 1
    private val hasPrev get() = siblingIndex > 0

    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val autoHide = Runnable { hideControls() }
    private val hideLockOverlay = Runnable { lockOverlay.visibility = View.GONE }
    private val progressTick = object : Runnable {
        override fun run() { report("progress"); handler.postDelayed(this, 10_000) }
    }
    private val uiTick = object : Runnable {
        override fun run() {
            if (controlsVisible && !scrubbing) updateProgress()
            updateIntroSkip()
            handler.postDelayed(this, 500)
        }
    }
    private val subTick = object : Runnable {
        override fun run() { updateSubtitle(); handler.postDelayed(this, 100) }
    }

    // ================================================================= lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.record(this, "PlayerActivity onCreate: entered")
        Api.load(this)
        itemId = intent.getStringExtra("id") ?: return finish()
        DiagnosticLog.record(this, "PlayerActivity onCreate: item id received")
        offlinePath = intent.getStringExtra("file")
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences("player", MODE_PRIVATE)
        quality = runCatching { Quality.valueOf(prefs.getString("quality", "AUTO") ?: "AUTO") }
            .getOrDefault(Quality.AUTO)
        audioMode = runCatching { AudioMode.valueOf(prefs.getString("audio", "ORIGINAL") ?: "ORIGINAL") }
            .getOrDefault(AudioMode.ORIGINAL)
        decoder = runCatching { DecoderMode.valueOf(prefs.getString("decoder", "HW") ?: "HW") }
            .getOrDefault(DecoderMode.HW)
        playbackEngine = runCatching { PlaybackEngine.valueOf(prefs.getString("playback_engine", "AUTO") ?: "AUTO") }
            .getOrDefault(PlaybackEngine.AUTO)
        vlcFallbackAttempted = intent.getBooleanExtra("skip_vlc_fallback", false)
        speed = prefs.getFloat("speed", 1f)
        autoPlayNext = prefs.getBoolean("autoplay", true)
        subPos = prefs.getFloat("sub_pos", 0.12f)
        if (playbackEngine == PlaybackEngine.VLC) {
            launchVlc(intent.getLongExtra("start_position_ms", 0L), null)
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val wlp = window.attributes
            wlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = wlp
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        DiagnosticLog.record(this, "PlayerActivity: creating ExoPlayer; decoder=$decoder engine=$playbackEngine")
        player = createPlayer()
        DiagnosticLog.record(this, "PlayerActivity: ExoPlayer created")
        playerView = PlayerView(this).apply {
            player = this@PlayerActivity.player
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        buildUi()
        DiagnosticLog.record(this, "PlayerActivity: UI built")
        setupGestures()
        attachPlayerListener()
        handler.post(uiTick)
        handler.post(subTick)
        showControls()
        DiagnosticLog.record(this, "PlayerActivity: starting loadAndPlay")
        loadAndPlay()
    }

    private fun launchVlc(positionMs: Long, url: String?) {
        if (::player.isInitialized && sourceId.isNotEmpty()) {
            report("stop")
            sourceId = "" // The VLC activity takes over this Jellyfin playback session.
        }
        val i = Intent(this, VlcPlayerActivity::class.java)
            .putExtra("id", itemId)
            .putExtra("file", offlinePath)
            .putExtra("stream_url", url ?: plan?.url)
            .putExtra("source_id", sourceId)
            .putExtra("session_id", sessionId)
            .putExtra("position_ms", positionMs)
            .putExtra("title", if (::titleView.isInitialized) titleView.text.toString() else intent.getStringExtra("title"))
            .putExtra("autoplay_next", autoPlayNext)
            .putExtra("transcoding", isTranscoding)
        startActivity(i)
        finish()
    }

    /** Builds an ExoPlayer using the chosen decoder mode. 4K/8K play whenever the device decoder supports them. */
    private fun createPlayer(): ExoPlayer {
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            when (decoder) {
                DecoderMode.SW -> all.filter { it.softwareOnly }.ifEmpty { all }
                DecoderMode.HW -> all.filter { it.hardwareAccelerated }.ifEmpty { all }
                DecoderMode.HW_PLUS -> all
            }
        }
        val factory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(true)
        if (decoder == DecoderMode.HW_PLUS) factory.forceEnableMediaCodecAsynchronousQueueing()
        val p = ExoPlayer.Builder(this, factory)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        p.setPlaybackSpeed(speed)
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
        )
        p.setHandleAudioBecomingNoisy(true)
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        // subtitles are drawn by our own engine; ExoPlayer's text renderer is only used for image subtitles
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        return p
    }

    private fun changeDecoder(mode: DecoderMode) {
        if (mode == decoder) return
        decoder = mode
        prefs.edit().putString("decoder", mode.name).apply()
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady
        player.release()
        player = createPlayer()
        playerView.player = player
        attachPlayerListener()
        if (loopOne) player.repeatMode = Player.REPEAT_MODE_ONE
        if (audioOnly) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        nativeOn = false
        val file = offlinePath
        if (file != null) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(file))), pos)
            player.prepare(); player.playWhenReady = wasPlaying
        } else if (sourceId.isNotEmpty()) {
            lifecycleScope.launch {
                player.setMediaItem(buildMediaItem(), pos)
                player.prepare(); player.playWhenReady = wasPlaying
            }
        }
        showHud("Decoder: ${mode.label}", 1500)
    }

    // ================================================================= UI building
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun circleBg(color: Int = 0x99181E30.toInt(), stroke: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    private fun icon(res: Int, size: Int = 44, pad: Int = 10) = ImageView(this).apply {
        setImageResource(res); setColorFilter(Color.WHITE)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun glassIcon(res: Int, size: Int = 44, pad: Int = 11) =
        icon(res, size, pad).apply { background = null }

    /** 10-second skip button: a circular arrow with "10" in the middle, on a glass disc. */
    private fun skipButton(forward: Boolean): FrameLayout = FrameLayout(this).apply {
        background = circleBg(0xAA17171B.toInt())
        setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_replay); setColorFilter(Color.WHITE)
            scaleX = if (forward) -1f else 1f
        }, FrameLayout.LayoutParams(-1, -1))
        addView(TextView(context).apply {
            text = "10"; setTextColor(Color.WHITE); textSize = 12f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    }

    /** Icon with a small label underneath; one entry in the bottom glass card. */
    /** Individual floating action: no shared toolbar slab or glass panel. */
    private fun action(res: Int, label: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(0, dp(3), 0, dp(2))
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        addView(FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22222228)
                setStroke(dp(1), 0x22FFFFFF)
            }
            elevation = dp(2).toFloat()
            addView(ImageView(context).apply {
                setImageResource(res); setColorFilter(0xFFD8B77E.toInt())
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(context).apply {
            text = label; setTextColor(0xFFE4E4E8.toInt()); textSize = 10f
            gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(5), 0, 0)
        })
    }

    private fun badge(text: String, filled: Boolean): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 11f; setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dp(3).toFloat()
            if (filled) setColor(accent) else { setColor(0x00000000); setStroke(dp(1), 0xCCFFFFFF.toInt()) }
        }
    }

    private fun nowPlayingText(playing: Boolean): CharSequence {
        val label = if (playing) "Now Playing" else "Paused"
        val s = SpannableString("● " + label + (if (nowTag.isNotEmpty()) "  ·  " + nowTag else ""))
        s.setSpan(ForegroundColorSpan(accent), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun buildUi() {
        // Cinematic Allervue layout: minimal title bar, centered transport controls,
        // warm gold timeline, and a compact lower utility dock.
        val backBtn = glassIcon(R.drawable.ic_back, 30, 7)
        titleView = TextView(this).apply {
            text = intent.getStringExtra("title") ?: ""
            setTextColor(0xFFF8F6F0.toInt()); textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.NORMAL)
            maxLines = 1; maxWidth = dp(220); ellipsize = TextUtils.TruncateAt.END
        }
        subTitle = TextView(this).apply {
            textSize = 10f; setTextColor(0xFFD8B77E.toInt())
            text = nowPlayingText(true); letterSpacing = 0.05f; maxLines = 1
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(4), 0)
            addView(titleView)
            addView(subTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE; setPadding(0, 0, dp(3), 0)
        }
        val castBtn = glassIcon(R.drawable.ic_cast, 28, 6)
        val pipBtn = glassIcon(R.drawable.ic_pip, 28, 6).apply {
            if (!pipSupported()) visibility = View.GONE
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            addView(backBtn, LinearLayout.LayoutParams(dp(34), dp(38)))
            addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
            addView(badgeRow, LinearLayout.LayoutParams(-2, -2))
            addView(castBtn, LinearLayout.LayoutParams(dp(34), dp(38)))
            addView(pipBtn, LinearLayout.LayoutParams(dp(34), dp(38)))
        }
        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xD9101013.toInt(), 0x66101013, 0x00101013))
            addView(topBar, LinearLayout.LayoutParams(-1, -2))
        }

        fun featureAction(res: Int, label: String): LinearLayout =
            LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(4), dp(3), dp(4), dp(3))
                addView(ImageView(this@PlayerActivity).apply {
                    setImageResource(res); setColorFilter(0xFFE4C58B.toInt())
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(21), dp(21)))
                addView(TextView(this@PlayerActivity).apply {
                    text = label; textSize = 9f; gravity = Gravity.CENTER
                    setTextColor(0xFFE1DED7.toInt()); maxLines = 1
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }

        val subtitlesAction = featureAction(R.drawable.ic_subtitles, "Subtitles")
        val audioAction = featureAction(R.drawable.ic_music, "Audio")
        val speedAction = featureAction(R.drawable.ic_schedule, speedText(speed))
        val moreAction = featureAction(R.drawable.ic_more_horiz, "More")
        speedLabel = speedAction.getChildAt(1) as TextView

        prevEpisodeBtn = episodeNavButton("‹|").apply {
            textSize = 18f; background = null; setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        nextEpisodeBtn = episodeNavButton("|›").apply {
            textSize = 18f; background = null; setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val rewBtn = skipButton(false).apply {
            background = circleBg(0x55202024); setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        playBtn = icon(R.drawable.ic_pause, 58, 14).apply {
            background = circleBg(0xFFE4C58B.toInt())
            setColorFilter(0xFF17130D.toInt())
            elevation = dp(8).toFloat()
        }
        val fwdBtn = skipButton(true).apply {
            background = circleBg(0x55202024); setPadding(dp(9), dp(9), dp(9), dp(9))
        }

        // Keep transport controls as one compact cluster. Weighted children here
        // used to expand across the full screen width and made the buttons look scattered.
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            fun fixed(v: View, width: Int, height: Int, start: Int = 0, end: Int = 0) {
                addView(v, LinearLayout.LayoutParams(dp(width), dp(height)).apply {
                    leftMargin = dp(start)
                    rightMargin = dp(end)
                })
            }
            fixed(prevEpisodeBtn, 34, 46, end = 10)
            fixed(rewBtn, 48, 48, end = 14)
            fixed(playBtn, 68, 68, end = 14)
            fixed(fwdBtn, 48, 48, end = 10)
            fixed(nextEpisodeBtn, 34, 46)
        }
        val centerControls = FrameLayout(this).apply {
            addView(transport, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            isClickable = false
        }

        curTime = TextView(this).apply {
            text = "00:00"; setTextColor(0xFFF5F1E8.toInt()); textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }
        durTime = TextView(this).apply {
            text = "--:--"; setTextColor(0xFFAAA69E.toInt()); textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(0xFFE4C58B.toInt())
            thumbTintList = ColorStateList.valueOf(0xFFFFF8E9.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
            secondaryProgressTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
            minimumHeight = dp(18); setPadding(dp(2), 0, dp(2), 0)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) curTime.text = fmt(p.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar) {
                scrubbing = true; handler.removeCallbacks(autoHide)
            }
            override fun onStopTrackingTouch(s: SeekBar) {
                scrubbing = false; player.seekTo(s.progress.toLong()); scheduleHide()
            }
        })
        val timeline = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(7), dp(18), dp(1))
            addView(curTime, LinearLayout.LayoutParams(-2, dp(26)))
            addView(seek, LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                leftMargin = dp(10); rightMargin = dp(10)
            })
            addView(durTime, LinearLayout.LayoutParams(-2, dp(26)))
        }

        val utilityDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(14), dp(2), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0x331E1E22)
                setStroke(dp(1), 0x22FFFFFF)
            }
            listOf(subtitlesAction, audioAction, speedAction, moreAction).forEach { v ->
                addView(v, LinearLayout.LayoutParams(0, dp(54), 1f))
            }
        }
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00101013, 0xC9101013.toInt(), 0xF5101013.toInt()))
            addView(timeline, LinearLayout.LayoutParams(-1, -2))
            addView(utilityDock, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(3)
            })
        }

        controls = FrameLayout(this).apply {
            addView(topPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(centerControls, FrameLayout.LayoutParams(-1, dp(100), Gravity.CENTER))
            addView(bottomPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        subText = TextView(this).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        hud = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(0xE617171B.toInt()); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0x44E4C58B)
            }
            visibility = View.GONE
        }
        spinner = ProgressBar(this).apply {
            isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(0xFFE4C58B.toInt())
            visibility = View.GONE
        }
        lockOverlay = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock); setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = circleBg()
            visibility = View.GONE
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(subText, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
            skipIntroBtn = TextView(this@PlayerActivity).apply {
                text = "SKIP INTRO  ›"; setTextColor(0xFF17130D.toInt())
                textSize = 11f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f
                setPadding(dp(18), dp(10), dp(18), dp(10))
                background = GradientDrawable().apply { setColor(0xFFE4C58B.toInt()); cornerRadius = dp(22).toFloat() }
                visibility = View.GONE; elevation = dp(8).toFloat()
                setOnClickListener {
                    val active = introSegments.firstOrNull {
                        player.currentPosition >= it.first && player.currentPosition < it.second
                    }
                    if (active != null) {
                        player.seekTo(active.second); showHud("Intro skipped"); visibility = View.GONE
                    }
                }
            }
            addView(skipIntroBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(126) })
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(lockOverlay, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.END)
                .apply { rightMargin = dp(18) })
        }
        setContentView(root)
        applySubStyle()
        layoutSubtitle()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(dp(12) + i.left, dp(10) + i.top, dp(12) + i.right, dp(8))
            (bottomPanel.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = i.left; rightMargin = i.right; bottomMargin = i.bottom
            }
            bottomPanel.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(root)

        act(backBtn) { finish() }
        act(castBtn) { openCast() }
        act(pipBtn) { enterPip() }
        act(rewBtn) { seekBy(-10_000); showHud("-10s") }
        act(fwdBtn) { seekBy(10_000); showHud("+10s") }
        act(playBtn) { if (player.isPlaying) player.pause() else player.play() }
        act(prevEpisodeBtn) { if (hasPrev) switchTo(siblings[siblingIndex - 1].id) }
        act(nextEpisodeBtn) { if (hasNext) switchTo(siblings[siblingIndex + 1].id) }
        act(subtitlesAction) { showSubtitleSheet() }
        act(audioAction) { showAudioSheet() }
        act(speedAction) { showSpeedSheet() }
        act(moreAction) { showMoreSheet() }
        lockOverlay.setOnClickListener { toggleLock() }
    }

    private fun episodeNavButton(label: String) = TextView(this).apply {
        text = label; textSize = 11f; setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(7), dp(9), dp(7))
        background = GradientDrawable().apply {
            setColor(0xAA202026.toInt()); cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0x55FFFFFF)
        }
        visibility = View.GONE
    }

    private fun updateEpisodeButtons() {
        prevEpisodeBtn.visibility = if (hasPrev) View.VISIBLE else View.GONE
        nextEpisodeBtn.visibility = if (hasNext) View.VISIBLE else View.GONE
    }

    private fun updateIntroSkip() {
        if (!::skipIntroBtn.isInitialized || locked || offlinePath != null || introSegments.isEmpty()) {
            if (::skipIntroBtn.isInitialized) skipIntroBtn.visibility = View.GONE
            return
        }
        val active = introSegments.firstOrNull {
            player.currentPosition >= it.first && player.currentPosition < it.second
        }
        skipIntroBtn.visibility = if (active != null) View.VISIBLE else View.GONE
    }

    private fun act(v: View, f: () -> Unit) = v.setOnClickListener { scheduleHide(); f() }

    // ================================================================= bottom sheets
    private fun sheet(title: String, content: (LinearLayout, Dialog) -> Unit) {
        val d = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        val r = dp(22).toFloat()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(22))
            background = GradientDrawable().apply {
                setColor(0xF2141418.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title; setTextColor(Color.WHITE); textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply {
                text = "✕"; setTextColor(Color.WHITE); textSize = 20f
                setPadding(dp(12), dp(6), dp(4), dp(6)); setOnClickListener { d.dismiss() }
            })
        }
        box.addView(head)
        content(box, d)
        d.setContentView(ScrollView(this).apply { addView(box) })
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 15f
        setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, dp(4))
    }

    private fun noteText(text: String) = TextView(this).apply {
        this.text = text; setTextColor(0xFF77777D.toInt()); textSize = 11f; setPadding(0, dp(12), 0, 0)
    }

    private fun radioGroup(base: Int, labels: List<String>, checked: Int, onPick: (Int) -> Unit): RadioGroup =
        RadioGroup(this).apply {
            labels.forEachIndexed { i, l ->
                addView(RadioButton(context).apply {
                    id = base + i; text = l; setTextColor(Color.WHITE); textSize = 15f
                    buttonTintList = ColorStateList.valueOf(accent); setPadding(dp(8), dp(10), 0, dp(10))
                })
            }
            if (checked >= 0) check(base + checked)
            setOnCheckedChangeListener { _, id -> onPick(id - base) }
        }

    private fun sheetRow(label: String, value: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, dp(14))
        addView(TextView(context).apply { text = label; setTextColor(Color.WHITE); textSize = 16f },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply { text = value; setTextColor(0xFF9A9AA0.toInt()); textSize = 14f })
        setOnClickListener { onClick() }
    }

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply { setColor(0xFF2A2A2E.toInt()); cornerRadius = dp(20).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun languageName(raw: String?): String {
        val tag = raw?.trim().orEmpty()
        if (tag.isEmpty() || tag.equals("und", true) || tag.equals("unknown", true)) return "Unknown"
        val normalized = when (tag.lowercase(Locale.ROOT).substringBefore("-").substringBefore("_")) {
            "eng" -> "en"; "spa" -> "es"; "jpn" -> "ja"; "kor" -> "ko"
            "zho", "chi" -> "zh"; "fra", "fre" -> "fr"; "deu", "ger" -> "de"
            "ita" -> "it"; "por" -> "pt"; "rus" -> "ru"; "ara" -> "ar"
            "fil", "tgl" -> "fil"; "tha" -> "th"; "vie" -> "vi"
            else -> tag
        }
        val name = Locale.forLanguageTag(normalized).getDisplayLanguage(Locale.getDefault())
        return name.takeIf { it.isNotBlank() && !it.equals("und", true) } ?: "Unknown"
    }

    private fun trackLabel(g: Tracks.Group, i: Int): String {
        val f = g.getTrackFormat(0)
        val streams = if (g.type == C.TRACK_TYPE_AUDIO) audioStreams else subStreams
        val serverStream = f.id?.toIntOrNull()?.let { id -> streams.firstOrNull { it.index == id } }
            ?: streams.getOrNull(i)
        return languageName(serverStream?.language ?: f.language)
    }

    private fun showAudioSheet() = sheet("Audio") { box, _ ->
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        if (groups.isEmpty()) {
            box.addView(noteText("No audio tracks found."))
        } else {
            val labels = groups.mapIndexed { i, g -> trackLabel(g, i) }
            val sel = groups.indexOfFirst { it.isSelected }.coerceAtLeast(0)
            box.addView(radioGroup(100, labels, sel) { i ->
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(groups[i].mediaTrackGroup, 0)).build()
            })
        }
        if (isTranscoding) box.addView(noteText(
            "While transcoding only one audio track is available. Choose Original quality and audio to switch tracks."))
    }

    private fun showSpeedSheet() = sheet("Playback Speed") { box, dlg ->
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        box.addView(radioGroup(100, speeds.map { speedText(it) }, speeds.indexOfFirst { it == speed }.coerceAtLeast(0)) { i ->
            speed = speeds[i]; player.setPlaybackSpeed(speed); speedLabel.text = speedText(speed); dlg.dismiss()
        })
    }

    private fun showDecoderSheet() = sheet("Decoder") { box, dlg ->
        val all = DecoderMode.values()
        box.addView(radioGroup(100, all.map { it.label + "  ·  " + it.desc }, decoder.ordinal) { i ->
            dlg.dismiss(); changeDecoder(all[i])
        })
        box.addView(noteText(
            "HW uses the phone's hardware decoder (best for battery). HW+ also enables asynchronous, multi-threaded " +
                "decoding for smoother high-bitrate video. SW uses the system software decoder, which Android runs on " +
                "several CPU cores. 4K and 8K play when your phone's chip supports them."))
    }

    private fun showVolumeSheet() = sheet("Volume") { box, _ ->
        val maxV = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val label = TextView(this).apply {
            text = "${volumePercent()}%"; setTextColor(Color.WHITE); textSize = 30f; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        }
        val bar = SeekBar(this).apply {
            max = 200; progress = volumePercent()
            progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) { setVolumeLevel(p / 100f * maxV); label.text = "$p%" }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        box.addView(label); box.addView(bar)
        box.addView(noteText("Above 100% the volume is boosted by software, up to 200%. Very loud audio may distort."))
    }

    private fun showQualitySheet() = sheet("Video quality") { box, dlg ->
        val all = Quality.values()
        box.addView(radioGroup(100, all.map { it.label }, quality.ordinal) { i ->
            applyChoice(all[i], audioMode); dlg.dismiss()
        })
    }

    private fun showAudioOutputSheet() = sheet("Audio output") { box, dlg ->
        val all = AudioMode.values()
        box.addView(radioGroup(100, all.map { it.label }, audioMode.ordinal) { i ->
            applyChoice(quality, all[i]); dlg.dismiss()
        })
        box.addView(noteText("Audio sets the maximum channels; sources with fewer channels are not upmixed."))
    }

    private fun showMoreSheet() = sheet("More") { box, dlg ->
        fun row(label: String, value: String = "", onClick: () -> Unit) {
            box.addView(sheetRow(label, value) { dlg.dismiss(); onClick() })
        }
        row("Lock controls", if (locked) "On" else "Off") { toggleLock() }
        row("Playback engine", decoder.label) { showDecoderSheet() }
        row("Volume", "${volumePercent()}%") { showVolumeSheet() }
        row("This phone & playback info") { showDeviceSheet() }
        row("Video quality", qualityText()) { showQualitySheet() }
        row("Audio output", audioMode.label) { showAudioOutputSheet() }
        row("Subtitle sync", delayText()) { showSubtitleSyncSheet() }
        row("Aspect ratio", aspectName()) { cycleAspect() }
        row("Audio only", if (audioOnly) "On" else "Off") { toggleAudioOnly() }
        row("Repeat", if (loopOne) "On" else "Off") { toggleLoop() }
        row("Screenshot") { takeScreenshot() }
        row("Picture-in-picture") { enterPip() }
        row("Cast screen") { openCast() }
        row("Rotate screen") { rotateScreen() }
        row("Skip +90s") { seekBy(90_000); showHud("+90s") }
        if (hasPrev) row("Previous episode") { switchTo(siblings[siblingIndex - 1].id) }
        if (hasNext) row("Next episode") { switchTo(siblings[siblingIndex + 1].id) }
    }

    private fun qualityText() = when (quality) {
        Quality.AUTO -> "Auto"
        Quality.ORIGINAL -> "Original"
        else -> quality.label.substringBefore(' ')
    }
    private fun speedText(f: Float) = (if (f % 1f == 0f) f.toInt().toString() else f.toString()) + "x"

    // ================================================================= subtitles
    private fun applySubStyle() {
        // Allervue subtitle standard: clean white text with a restrained black shadow.
        subText.textSize = 20f
        subText.setTextColor(Color.WHITE)
        subText.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        subText.setPadding(0, 0, 0, 0)
        subText.background = null
        subText.setShadowLayer(2.5f, 0f, 1.5f, Color.BLACK)
    }

    private fun layoutSubtitle() {
        val lp = subText.layoutParams as FrameLayout.LayoutParams
        val h = if (root.height > 0) root.height else resources.displayMetrics.heightPixels
        lp.bottomMargin = (subPos * h).toInt() + (if (controlsVisible) dp(110) else 0)
        subText.maxWidth = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        subText.layoutParams = lp
    }

    private fun updateSubtitle() {
        if (System.currentTimeMillis() < previewUntil) return
        if (!subOn || cues.isEmpty()) {
            if (subText.visibility != View.GONE) subText.visibility = View.GONE
            shownText = ""
            return
        }
        val txt = SubParser.activeText(cues, player.currentPosition - subDelayMs)
        if (txt != shownText) {
            shownText = txt
            subText.text = txt
            subText.visibility = if (txt.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun previewSubtitle() {
        previewUntil = System.currentTimeMillis() + 2500
        subText.text = "Sample subtitle text"
        subText.visibility = View.VISIBLE
        handler.postDelayed({ shownText = "~"; updateSubtitle() }, 2600)
    }

    private fun delayText() = "%+.1f s".format(subDelayMs / 1000f)

    private fun subExt(codec: String?): String = when (codec?.lowercase()) {
        "ass", "ssa" -> "ass"
        "webvtt", "vtt" -> "vtt"
        else -> "srt"
    }

    private fun streamLabel(s: MediaStream): String = languageName(s.language)

    private fun nativeImageGroups(): List<Tracks.Group> = player.currentTracks.groups.filter {
        it.type == C.TRACK_TYPE_TEXT && it.isSupported &&
            (it.getTrackFormat(0).sampleMimeType == MimeTypes.APPLICATION_PGS ||
                it.getTrackFormat(0).sampleMimeType == MimeTypes.APPLICATION_DVBSUBS)
    }

    private fun setNativeText(group: Tracks.Group?) {
        val b = player.trackSelectionParameters.buildUpon()
        if (group == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true); nativeOn = false
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            nativeOn = true
        }
        player.trackSelectionParameters = b.build()
    }

    private fun subtitlesOff() {
        subOn = false; activeSub = -1; cues = emptyList()
        setNativeText(null)
        updateSubtitle()
        showHud("Subtitles off")
    }

    private fun selectNativeSubtitle(g: Tracks.Group) {
        cues = emptyList(); subOn = false; activeSub = -1
        setNativeText(g)
        updateSubtitle()
        showHud("Image subtitles on")
    }

    private fun selectSubStream(i: Int) {
        val st = subStreams.getOrNull(i) ?: return
        if (!st.isText) { showHud("Image subtitle (${st.codec ?: "?"}). Use the embedded image list if shown"); return }
        lifecycleScope.launch {
            try {
                val bytes = Api.fetchBytes(Api.subtitleUrl(itemId, sourceId, st.index, subExt(st.codec)))
                val parsed = SubParser.parse(Api.decodeText(bytes))
                if (parsed == null) { showHud("Couldn't read this subtitle"); return@launch }
                setNativeText(null)
                cues = parsed; subOn = true; activeSub = i
                shownText = "~"; updateSubtitle()
                showHud("Subtitles: " + streamLabel(st), 1500)
            } catch (e: Exception) { showHud("Couldn't load subtitle (${e.message})", 3000) }
        }
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            } else null
        }

    private fun loadSubtitleFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { displayName(uri) } ?: "subtitle"
                if (name.lowercase().endsWith(".idx")) {
                    showHud("VobSub (.idx/.sub) is image-based and isn't supported", 3500); return@launch
                }
                val bytes = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    ?: ByteArray(0)
                val parsed = SubParser.parse(Api.decodeText(bytes))
                if (parsed == null) {
                    showHud("Unsupported subtitle. Use .srt, .ass/.ssa, .vtt or text .sub", 3500); return@launch
                }
                setNativeText(null)
                cues = parsed; subOn = true; activeSub = -2
                shownText = "~"; updateSubtitle()
                showHud("Subtitles: $name", 1500)
            } catch (e: Exception) { showHud("Couldn't open file (${e.message})", 3000) }
        }
    }

    private fun toggleSubtitles() {
        if (cues.isNotEmpty()) {
            subOn = !subOn; shownText = "~"; updateSubtitle()
            showHud(if (subOn) "Subtitles on" else "Subtitles off")
        } else {
            val def = subStreams.indexOfFirst { it.isDefault && it.isText }
            val first = if (def >= 0) def else subStreams.indexOfFirst { it.isText }
            if (first >= 0) selectSubStream(first) else showSubtitleSheet()
        }
    }

    private fun showSubtitleSheet() = sheet("Subtitles") { box, dlg ->
        val labels = ArrayList<String>()
        labels.add("Off")
        subStreams.forEach { labels.add(streamLabel(it) + (if (it.isText) "" else "  (image)")) }
        val checked = when {
            !subOn -> 0
            activeSub >= 0 -> activeSub + 1
            else -> 0
        }
        box.addView(radioGroup(100, labels, checked) { i ->
            dlg.dismiss()
            when {
                i == 0 -> subtitlesOff()
                else -> selectSubStream(i - 1)
            }
        })
        val nativeGroups = nativeImageGroups()
        if (nativeGroups.isNotEmpty()) {
            box.addView(sectionLabel("Embedded image subtitles"))
            val sel = if (nativeOn) nativeGroups.indexOfFirst { it.isSelected } else -1
            box.addView(radioGroup(200, nativeGroups.mapIndexed { i, g -> trackLabel(g, i) }, sel) { i ->
                dlg.dismiss(); selectNativeSubtitle(nativeGroups[i])
            })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        row.addView(pill("Sync  (" + delayText() + ")") { dlg.dismiss(); showSubtitleSyncSheet() })
        box.addView(row)
        box.addView(noteText(
            "Choose an embedded subtitle track. Tip: swipe left or right on the subtitle text to jump " +
                "between lines, and drag it up or down to move it."))
    }

    private fun showSubtitleSyncSheet() = sheet("Subtitle sync") { box, _ ->
        val value = TextView(this).apply {
            text = delayText(); setTextColor(Color.WHITE); textSize = 30f; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        box.addView(value)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun step(label: String, delta: Long) {
            row.addView(pill(label) {
                subDelayMs = if (delta == 0L) 0L else subDelayMs + delta
                value.text = delayText(); shownText = "~"; updateSubtitle()
            }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        step("-1s", -1000L); step("-0.1s", -100L); step("Reset", 0L); step("+0.1s", 100L); step("+1s", 1000L)
        box.addView(row)
        box.addView(noteText("A positive delay shows subtitles later; a negative delay shows them earlier."))
    }

    /** Swipe on the subtitle text: left/right jumps between lines, up/down moves the text. */
    private fun handleSubtitleTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (locked || !subText.isShown) return false
                val pad = dp(28)
                val inside = ev.x >= subText.left - pad && ev.x <= subText.right + pad &&
                    ev.y >= subText.top - pad && ev.y <= subText.bottom + pad
                if (!inside) return false
                subDrag = true; subMode = 0
                subDownX = ev.x; subDownY = ev.y; subStartPos = subPos
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!subDrag) return false
                val dx = ev.x - subDownX
                val dy = ev.y - subDownY
                if (subMode == 0) {
                    if (abs(dy) > dp(10) && abs(dy) > abs(dx)) subMode = 1
                    else if (abs(dx) > dp(10)) subMode = 2
                }
                if (subMode == 1) {
                    subPos = (subStartPos - dy / root.height).coerceIn(0.02f, 0.6f)
                    layoutSubtitle()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!subDrag) return false
                subDrag = false
                val dx = ev.x - subDownX
                if (subMode == 2 && abs(dx) > dp(60)) jumpSubtitle(if (dx > 0) 1 else -1)
                else if (subMode == 1) prefs.edit().putFloat("sub_pos", subPos).apply()
                else if (subMode == 0) { if (controlsVisible) hideControls() else showControls() }
                return true
            }
        }
        return false
    }

    private fun jumpSubtitle(dir: Int) {
        if (cues.isEmpty()) return
        val t = player.currentPosition - subDelayMs
        val idx = SubParser.indexAt(cues, t)
        val target: SubCue? = if (dir > 0) cues.getOrNull(idx + 1) else {
            val active = idx >= 0 && t <= cues[idx].endMs
            cues.getOrNull(if (active) idx - 1 else idx)
        }
        if (target != null) {
            player.seekTo((target.startMs + subDelayMs).coerceAtLeast(0L))
            showHud(if (dir > 0) "Next subtitle" else "Previous subtitle")
        }
    }

    // ================================================================= volume + boost
    private fun maxVol() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private fun currentVolumeLevel(): Float {
        val m = maxVol().toFloat()
        val sys = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        return if (boostPct > 100) m + (boostPct - 100) / 100f * m else sys
    }

    private fun volumePercent(): Int = (currentVolumeLevel() / maxVol() * 100).toInt()

    /** Level runs from 0 to 2x the system maximum; anything above the maximum is software boost. */
    private fun setVolumeLevel(level: Float) {
        val m = maxVol().toFloat()
        val l = level.coerceIn(0f, m * 2f)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(l, m).toInt(), 0)
        boostPct = if (l > m) 100 + ((l - m) / m * 100).toInt() else 100
        applyBoost()

    }

    private fun applyBoost() {
        val e = enhancer ?: return
        try {
            if (boostPct <= 100) {
                e.setEnabled(false)
            } else {
                e.setTargetGain((2000.0 * log10(boostPct / 100.0)).toInt())
                e.setEnabled(true)
            }
        } catch (ex: Exception) { /* effect not available on this device */ }
    }

    private fun attachEnhancer(sessionId: Int) {
        try { enhancer?.release() } catch (ex: Exception) { }
        enhancer = null
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            enhancer = LoudnessEnhancer(sessionId)
            applyBoost()
        } catch (ex: Exception) { enhancer = null }
    }

    // ================================================================= controls visibility
    private fun showControls() {
        if (locked) return
        controlsVisible = true; controls.visibility = View.VISIBLE
        layoutSubtitle(); updateProgress(); scheduleHide()
    }

    private fun hideControls() {
        controlsVisible = false; controls.visibility = View.GONE
        handler.removeCallbacks(autoHide)
        layoutSubtitle()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(autoHide)
        if (player.isPlaying) handler.postDelayed(autoHide, 4000)
    }

    private fun updateProgress() {
        val dur = player.duration
        if (dur > 0) seek.max = dur.toInt()
        seek.progress = player.currentPosition.toInt().coerceAtLeast(0)
        seek.secondaryProgress = player.bufferedPosition.toInt().coerceAtLeast(0)
        curTime.text = fmt(player.currentPosition)
        durTime.text = if (dur > 0) fmt(dur) else "--:--"

    }

    // ================================================================= playback
    private suspend fun buildMediaItem(): MediaItem {
        val q = quality
        val chosen: PlaybackPlan = when {
            q == Quality.AUTO && audioMode == AudioMode.ORIGINAL ->
                PlaybackPlan(Api.streamUrl(itemId, sourceId), false, false, sessionId,
                    "Direct play · automatic fallback enabled")
            q == Quality.ORIGINAL && audioMode == AudioMode.ORIGINAL ->
                PlaybackPlan(Api.streamUrl(itemId, sourceId), false, false, sessionId, "Direct play (original file)")
            else -> {
                val useQ = if (q == Quality.AUTO) Quality.ORIGINAL else q
                val t = Api.transcodeUrl(itemId, sourceId, useQ, audioMode, sessionId)
                PlaybackPlan(t.url, true, true, t.sessionId, "Transcoding · ${useQ.label} · audio ${audioMode.label}")
            }
        }
        plan = chosen
        sessionId = chosen.sessionId
        val b = MediaItem.Builder().setUri(chosen.url)
        if (chosen.hls) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }

    private fun applyDefaultAudioTrack() {
        if (defaultAudioApplied || audioStreams.isEmpty()) return
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        if (groups.isEmpty()) return
        val preferred = audioStreams.firstOrNull { it.isDefault } ?: audioStreams.firstOrNull()
        val target = preferred?.let { stream ->
            groups.firstOrNull { group ->
                group.getTrackFormat(0).id?.toIntOrNull() == stream.index
            }
        } ?: groups.firstOrNull { it.isSelected } ?: groups.first()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0)).build()
        defaultAudioApplied = true
    }

    private fun applyDefaultSubtitle() {
        if (defaultSubtitleApplied || subStreams.isEmpty()) return
        val english = subStreams.indexOfFirst {
            it.isText && (it.language?.startsWith("en", true) == true ||
                languageName(it.language).equals("English", true))
        }
        val preferred = if (english >= 0) english else
            subStreams.indexOfFirst { it.isDefault && it.isText }.takeIf { it >= 0 }
                ?: subStreams.indexOfFirst { it.isText }
        if (preferred != null && preferred >= 0) {
            defaultSubtitleApplied = true
            selectSubStream(preferred)
            return
        }
        val imageGroups = nativeImageGroups()
        if (imageGroups.isEmpty()) return
        val englishImage = imageGroups.firstOrNull { group ->
            val fmt = group.getTrackFormat(0)
            val id = fmt.id?.toIntOrNull()
            val stream = id?.let { n -> subStreams.firstOrNull { it.index == n } }
            (stream?.language ?: fmt.language)?.startsWith("en", true) == true
        }
        val defaultImage = imageGroups.firstOrNull { group ->
            val id = group.getTrackFormat(0).id?.toIntOrNull()
            id?.let { n -> subStreams.firstOrNull { it.index == n }?.isDefault } == true
        }
        val target = englishImage ?: defaultImage ?: imageGroups.firstOrNull()
        if (target != null) {
            defaultSubtitleApplied = true
            selectNativeSubtitle(target)
        }
    }

    private fun attachPlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                applyDefaultAudioTrack()
                applyDefaultSubtitle()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                subTitle.text = nowPlayingText(isPlaying)
                if (isPlaying) { if (controlsVisible) scheduleHide() } else handler.removeCallbacks(autoHide)
                report("progress")
            }
            override fun onPlaybackStateChanged(state: Int) {
                spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    updateProgress()
                    if (startupStartedAt > 0L) {
                        DiagnosticLog.record(this@PlayerActivity,
                            "PLAYBACK_READY after ${SystemClock.elapsedRealtime() - startupStartedAt}ms")
                        startupStartedAt = 0L
                    }
                }
                if (state == Player.STATE_ENDED) {
                    if (offlinePath != null) saveOfflinePosition()
                    if (autoPlayNext && hasNext) switchTo(siblings[siblingIndex + 1].id) else finish()
                }
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) { attachEnhancer(audioSessionId) }
            override fun onPlayerError(error: PlaybackException) {
            DiagnosticLog.record(this@PlayerActivity, "ExoPlayer onPlayerError code=${error.errorCode} name=${error.errorCodeName}: ${error.message}", error)
                // Auto engine: retry decoder/codec failures in LibVLC before giving up.
                if (playbackEngine == PlaybackEngine.AUTO && !vlcFallbackAttempted &&
                    error.errorCode in 4001..4005) {
                    vlcFallbackAttempted = true
                    showHud("Android decoder failed. Switching to VLC…", 2200)
                    launchVlc(player.currentPosition, plan?.url)
                    return
                }
                // Auto quality mode: if this phone's decoder chokes on a direct-played file, switch to a transcode once
                if (quality == Quality.AUTO && plan?.transcoding == false && !autoRetried && offlinePath == null &&
                    error.errorCode in 4001..4005) {
                    fallbackTranscode(); return
                }
                val http = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                val detail = http?.let { " [HTTP ${it.responseCode} ${it.dataSpec.uri.lastPathSegment}]" } ?: ""
                val hint = if (error.errorCodeName.contains("DECOD")) " Try another decoder (HW / HW+ / SW)." else ""
                showHud(if (isTranscoding) "Transcode failed: ${error.errorCodeName}$detail"
                        else "Playback error: ${error.errorCodeName}$detail$hint", 8000)
            }
        })
    }

    private fun loadAndPlay() = lifecycleScope.launch {
        defaultAudioApplied = false
        defaultSubtitleApplied = false
        startupStartedAt = SystemClock.elapsedRealtime()
        DiagnosticLog.record(this@PlayerActivity, "loadAndPlay: begin; offline=" + (offlinePath != null))
        val file = offlinePath
        if (file != null) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(file))))
            val saved = Downloads.position(this@PlayerActivity, itemId)
            if (saved > 5_000) { player.seekTo(saved); showHud("Resuming at " + fmt(saved), 1500) }
            player.playWhenReady = true
            player.prepare()
            handler.postDelayed(progressTick, 10_000)
            return@launch
        }
        try {
            // Intro detection is optional UI metadata; it must never block first-frame startup.
            lifecycleScope.launch {
                introSegments = runCatching { Api.introSegments(itemId) }.getOrDefault(emptyList())
                updateIntroSkip()
            }

            // Start the common direct-play path immediately. Jellyfin can choose its
            // default media source when MediaSourceId is omitted, so metadata loading
            // no longer sits in front of the first video request.
            val canStartDirectly = quality in listOf(Quality.AUTO, Quality.ORIGINAL) &&
                audioMode == AudioMode.ORIGINAL
            if (canStartDirectly) {
                val url = Api.streamUrl(itemId)
                plan = PlaybackPlan(url, false, false, sessionId, "Direct play · metadata loading in parallel")
                player.setMediaItem(MediaItem.fromUri(url))
                val requestedStart = intent.getLongExtra("start_position_ms", 0L)
                if (requestedStart > 5_000) player.seekTo(requestedStart)
                player.playWhenReady = true
                player.prepare()
                DiagnosticLog.record(this@PlayerActivity, "loadAndPlay: direct stream request started before metadata")
            }

            val metadataStarted = SystemClock.elapsedRealtime()
            DiagnosticLog.record(this@PlayerActivity, "loadAndPlay: requesting item metadata")
            val item = Api.item(itemId)
            DiagnosticLog.record(this@PlayerActivity, "loadAndPlay: metadata received in " + (SystemClock.elapsedRealtime() - metadataStarted) + "ms; mediaSources=" + (item.mediaSources?.size ?: 0))
            val src = item.mediaSources?.firstOrNull() ?: error("No media source")
            sourceId = src.id
            if (item.type == "Episode" && item.seriesName != null) {
                titleView.text = item.seriesName
                nowTag = "S${item.parentIndex ?: 0}:E${item.index ?: 0} · ${item.name}"
            } else {
                titleView.text = item.name
                nowTag = ""
            }
            subTitle.text = nowPlayingText(true)
            val video = src.streams.firstOrNull { it.type == "Video" }
            val h = video?.height ?: 0
            val range = video?.videoRange.orEmpty()
            val hdrContent = range.contains("HDR", true) || range.contains("HLG", true) || range.contains("DOVI", true)
            if (hdrContent && Build.VERSION.SDK_INT >= 26 && Device.supportsHdr(this@PlayerActivity)) {
                window.colorMode = ActivityInfo.COLOR_MODE_HDR   // lightweight display check; avoid scanning codecs here
            }
            val res = when { h >= 4000 -> "8K"; h >= 2000 -> "4K"; h >= 1000 -> "1080p"; h >= 700 -> "720p"; else -> "" }
            badgeRow.removeAllViews()
            if (res.isNotEmpty()) badgeRow.addView(badge(res, false))
            if (video?.videoRange?.contains("HDR", ignoreCase = true) == true) {
                badgeRow.addView(badge("HDR", true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
            }
            badgeRow.visibility = if (badgeRow.childCount > 0) View.VISIBLE else View.GONE
            subStreams = src.streams.filter { it.type == "Subtitle" }
            audioStreams = src.streams.filter { it.type == "Audio" }
            DiagnosticLog.record(this@PlayerActivity, "loadAndPlay: streams audio=${audioStreams.size} subtitles=${subStreams.size}")

            // AUTO starts with Jellyfin's static stream immediately. If this device cannot decode it,
            // the existing decoder-error handler falls back to a Jellyfin transcode.
            val resumeMs = (item.userData?.positionTicks ?: 0L) / 10_000
            val requestedStart = intent.getLongExtra("start_position_ms", 0L)
            val resumeAt = if (requestedStart > 0L) requestedStart else resumeMs
            if (canStartDirectly) {
                if (resumeAt > 5_000) player.seekTo(resumeAt)
                applyDefaultAudioTrack()
                applyDefaultSubtitle()
                DiagnosticLog.record(this@PlayerActivity, "loadAndPlay: continuing early direct stream with resolved media metadata")
            } else {
                player.setMediaItem(buildMediaItem())
                if (resumeAt > 5_000) player.seekTo(resumeAt)
                player.playWhenReady = true
                DiagnosticLog.record(this@PlayerActivity, "loadAndPlay: media item configured; preparing player")
                val prepareStarted = SystemClock.elapsedRealtime()
                player.prepare()
                DiagnosticLog.record(this@PlayerActivity, "loadAndPlay: prepare() returned in " + (SystemClock.elapsedRealtime() - prepareStarted) + "ms")
            }
            report("start")
            handler.postDelayed(progressTick, 10_000)

            applyDefaultSubtitle()
            siblings = emptyList(); siblingIndex = -1
            if (item.type == "Episode" && item.parentId != null) {
                siblings = runCatching { Api.children(item.parentId) }.getOrDefault(emptyList())
                    .filter { it.type == "Episode" }
                siblingIndex = siblings.indexOfFirst { it.id == itemId }
            }
            updateEpisodeButtons()
            updateIntroSkip()
        } catch (e: Exception) {
            DiagnosticLog.record(this@PlayerActivity, "loadAndPlay exception: ${e.message}", e)
            showHud("Error: ${e.message}", 4000)
        }
    }

    private fun switchTo(newId: String) {
        report("stop")
        handler.removeCallbacks(progressTick)
        sourceId = ""; itemId = newId; introSegments = emptyList()
        defaultAudioApplied = false; defaultSubtitleApplied = false
        updateIntroSkip()
        sessionId = UUID.randomUUID().toString().replace("-", "")
        autoRetried = false; plan = null
        cues = emptyList(); subOn = false; activeSub = -1; subStreams = emptyList(); shownText = ""
        defaultAudioApplied = false; defaultSubtitleApplied = false
        setNativeText(null)
        player.stop(); player.clearMediaItems()
        loadAndPlay()
    }

    private fun applyChoice(nq: Quality, na: AudioMode) {
        if (nq == quality && na == audioMode) return
        quality = nq; audioMode = na
        prefs.edit().putString("quality", quality.name).putString("audio", audioMode.name).apply()
        applyPlayback()
    }

    private fun applyPlayback() {
        if (sourceId.isEmpty()) return
        val pos = player.currentPosition
        showHud("Switching to ${quality.label} · Audio: ${audioMode.label}…", 2500)
        lifecycleScope.launch {
            val mi = buildMediaItem()
            player.setMediaItem(mi, pos)
            player.prepare(); player.play()
        }
    }

    // ================================================================= misc buttons
    private fun toggleAudioOnly() {
        audioOnly = !audioOnly
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build()
        showHud(if (audioOnly) "Audio only" else "Video on")
    }

    private fun toggleLoop() {
        loopOne = !loopOne
        player.repeatMode = if (loopOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        showHud(if (loopOne) "Repeat: on" else "Repeat: off")
    }

    private fun rotateScreen() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeNames = arrayOf("Fit", "Stretch", "Crop")

    private fun aspectName(): String {
        val i = resizeModes.indexOfFirst { it == playerView.resizeMode }
        return resizeNames[if (i < 0) 0 else i]
    }

    private fun cycleAspect() {
        val i = resizeModes.indexOfFirst { it == playerView.resizeMode }
        val n = (i + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[n]; showHud(resizeNames[n])
    }

    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Picture-in-picture window uses the video's real shape. */
    private fun enterPip() {
        if (!pipSupported()) { showHud("Picture-in-picture isn't supported on this phone"); return }
        val b = PictureInPictureParams.Builder()
        val vs = player.videoSize
        if (vs.width > 0 && vs.height > 0) {
            val r = (vs.width * vs.pixelWidthHeightRatio / vs.height).coerceIn(0.45f, 2.3f)
            b.setAspectRatio(Rational((r * 1000).toInt(), 1000))
        }
        try { enterPictureInPictureMode(b.build()) } catch (e: Exception) { showHud("Can't enter picture-in-picture") }
    }

    private fun openCast() {
        try { startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }
        catch (e: Exception) { showHud("Casting isn't available on this device") }
    }

    private fun takeScreenshot() {
        val sv = playerView.videoSurfaceView as? SurfaceView
        if (sv == null || sv.width == 0 || sv.height == 0) { showHud("Screenshot unavailable"); return }
        if (Build.VERSION.SDK_INT < 29) { showHud("Screenshots need Android 10+"); return }
        val bmp = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sv, bmp, { result ->
            if (result == PixelCopy.SUCCESS) saveBitmap(bmp) else showHud("Screenshot failed")
        }, handler)
    }

    @RequiresApi(29)
    private fun saveBitmap(bmp: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Allervue_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Allervue")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            showHud("Screenshot saved")
        } else showHud("Couldn't save screenshot")
    }

    private fun toggleLock() {
        locked = !locked
        root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (locked) { hideControls(); showLockOverlay(); showHud("Locked") }
        else { lockOverlay.visibility = View.GONE; showControls(); showHud("Unlocked") }
    }

    private fun showLockOverlay() {
        lockOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideLockOverlay); handler.postDelayed(hideLockOverlay, 2000)
    }

    // ================================================================= gestures
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (d.scaleFactor > 1.03f && playerView.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; showHud("Crop")
                } else if (d.scaleFactor < 0.97f && playerView.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; showHud("Fit")
                }
                return true
            }
        })
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) showLockOverlay()
                else if (controlsVisible) hideControls() else showControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = playerView.width
                when {
                    e.x < w / 3f -> { seekBy(-10_000); showHud("-10s") }
                    e.x > w * 2 / 3f -> { seekBy(10_000); showHud("+10s") }
                    else -> if (player.isPlaying) player.pause() else player.play()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (locked) return
                longPressing = true; player.setPlaybackSpeed(2f); showHud("2x", 100_000)
                root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || e1 == null || scale.isInProgress || longPressing) return false
                if (mode == Mode.NONE) {
                    mode = when {
                        abs(dx) > abs(dy) -> { startPos = player.currentPosition; Mode.SEEK }
                        e1.x < playerView.width / 2f -> {
                            val b = window.attributes.screenBrightness
                            brightness = if (b < 0) 0.5f else b; Mode.BRIGHT
                        }
                        else -> { volF = currentVolumeLevel(); Mode.VOLUME }
                    }
                }
                when (mode) {
                    Mode.SEEK -> {
                        val dur = player.duration.coerceAtLeast(0L)
                        seekTarget = (startPos + (e2.x - e1.x) * 120_000f / playerView.width).toLong().coerceIn(0L, dur)
                        val sign = if (seekTarget >= startPos) "+" else "-"
                        showHud("${fmt(seekTarget)}  ($sign${fmt(abs(seekTarget - startPos))})", 1500)
                    }
                    Mode.BRIGHT -> {
                        brightness = (brightness + dy / playerView.height).coerceIn(0.01f, 1f)
                        window.attributes = window.attributes.also { it.screenBrightness = brightness }
                        showHud("Brightness ${(brightness * 100).toInt()}%")
                    }
                    Mode.VOLUME -> {
                        volF = (volF + dy / playerView.height * maxVol() * 2.4f).coerceIn(0f, maxVol() * 2f)
                        setVolumeLevel(volF)
                        showHud("Volume ${volumePercent()}%")
                    }
                    else -> {}
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, ev ->
            if (handleSubtitleTouch(ev)) return@setOnTouchListener true
            scale.onTouchEvent(ev)
            gd.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mode == Mode.SEEK) player.seekTo(seekTarget)
                mode = Mode.NONE
                if (longPressing) { longPressing = false; player.setPlaybackSpeed(speed); hud.visibility = View.GONE }
            }
            true
        }
    }

    private fun seekBy(ms: Long) =
        player.seekTo((player.currentPosition + ms).coerceIn(0L, player.duration.coerceAtLeast(0L)))

    private fun showHud(text: String, hideAfter: Long = 800) {
        hud.text = text; hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud); handler.postDelayed(hideHud, hideAfter)
    }

    private fun fmt(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    // ================================================================= phone-aware helpers
    private fun showDeviceSheet() = sheet("This phone & playback") { box, _ ->
        Device.describe(this, plan?.label).forEach { (k, v) ->
            box.addView(TextView(this).apply {
                text = k; setTextColor(0xFF8A8A90.toInt()); textSize = 12f; setPadding(0, dp(12), 0, 0)
            })
            box.addView(TextView(this).apply { text = v; setTextColor(Color.WHITE); textSize = 14f })
        }
        box.addView(noteText(
            "Auto mode uses this information: it direct-plays what your phone can decode and only transcodes " +
                "what it can't, and it eases off on mobile data, low battery or heat."))
    }

    private fun fallbackTranscode() {
        autoRetried = true
        showHud("This phone can't decode the original directly. Switching to transcoding…", 3500)
        val pos = player.currentPosition
        lifecycleScope.launch {
            try {
                val t = Api.transcodeUrl(itemId, sourceId, Quality.P1080, AudioMode.ORIGINAL, sessionId)
                sessionId = t.sessionId
                plan = PlaybackPlan(t.url, true, true, t.sessionId, "Transcoding · 1080p (automatic fallback)")
                player.setMediaItem(MediaItem.Builder().setUri(t.url).setMimeType(MimeTypes.APPLICATION_M3U8).build(), pos)
                player.prepare(); player.play()
            } catch (e: Exception) { showHud("Fallback failed: ${e.message}", 4000) }
        }
    }

    // ================================================================= reporting & lifecycle
    private fun saveOfflinePosition() {
        if (offlinePath == null || !::player.isInitialized) return
        Downloads.savePosition(this, itemId, player.currentPosition, player.duration)
    }

    private fun report(kind: String) {
        if (offlinePath != null) { if (kind != "start") saveOfflinePosition(); return }
        if (sourceId.isEmpty()) return
        val id = itemId; val src = sourceId; val sid = sessionId
        val pos = player.currentPosition; val paused = !player.isPlaying; val tc = isTranscoding
        lifecycleScope.launch { Api.report(kind, id, src, pos, paused, tc, sid) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::player.isInitialized) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SPACE -> {
                    if (player.isPlaying) player.pause() else player.play()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (hasNext) switchTo(siblings[siblingIndex + 1].id) else seekBy(30_000)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (hasPrev) switchTo(siblings[siblingIndex - 1].id) else player.seekTo(0)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    seekBy(10_000); showHud("+10s"); return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    seekBy(-10_000); showHud("-10s"); return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) >= maxVol() && boostPct < 200) {
                        boostPct = minOf(200, boostPct + 10); applyBoost()
                        showHud("Volume ${volumePercent()}%")
                        return true
                    }
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (boostPct > 100) {
                        boostPct = maxOf(100, boostPct - 10); applyBoost()
                        showHud("Volume ${volumePercent()}%")
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onUserLeaveHint() {
        if (::player.isInitialized && player.isPlaying && pipSupported()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (isInPip) { hideControls(); lockOverlay.visibility = View.GONE }
        else if (!locked) showControls()
    }

    override fun onStop() {
        super.onStop()
        saveOfflinePosition()
        if (::player.isInitialized && !isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!::player.isInitialized) { super.onDestroy(); return }
        if (offlinePath != null) {
            saveOfflinePosition()
            // best effort: tell the server too, so Continue Watching stays in step when you're online
            val pos = player.currentPosition; val id = itemId
            CoroutineScope(Dispatchers.IO).launch { Api.report("stop", id, id, pos, true, false, "") }
        }
        if (sourceId.isNotEmpty()) {
            val pos = player.currentPosition; val id = itemId; val src = sourceId
            val tc = isTranscoding; val sid = sessionId
            CoroutineScope(Dispatchers.IO).launch { Api.report("stop", id, src, pos, true, tc, sid) }
        }
        try { enhancer?.release() } catch (e: Exception) { }
        player.release()
        super.onDestroy()
    }
}
