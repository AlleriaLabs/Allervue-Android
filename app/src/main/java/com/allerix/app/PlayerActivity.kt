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
import android.graphics.Canvas
import android.graphics.Paint
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
import android.view.ViewGroup
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
    private val accent = 0xFFFF4F78.toInt()

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var hud: TextView
    private lateinit var aspectHud: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var controls: FrameLayout
    private lateinit var lockOverlay: ImageView
    private lateinit var titleView: TextView
    private lateinit var subTitle: TextView
    private lateinit var badgeRow: LinearLayout
    private lateinit var topPlaybackInfo: TextView
    private lateinit var seek: SeekBar
    private lateinit var curTime: TextView
    private lateinit var durTime: TextView
    private lateinit var playBtn: ImageView
    private var progressRing: View? = null
    private lateinit var prevEpisodeBtn: TextView
    private lateinit var nextEpisodeBtn: TextView
    private lateinit var episodesAction: TextView
    private lateinit var nextEpisodeAction: TextView
    private var isSeriesEpisode = false
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
    private var hasPlayedOnce = false
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
    private var brightnessRailLevel: ((Int) -> Unit)? = null
    private var volumeRailLevel: ((Int) -> Unit)? = null

    private val isTranscoding get() = offlinePath == null && plan?.transcoding == true
    private val hasNext get() = siblingIndex >= 0 && siblingIndex < siblings.size - 1
    private val hasPrev get() = siblingIndex > 0

    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val hideAspectHud = Runnable { if (::aspectHud.isInitialized) aspectHud.visibility = View.GONE }
    private val autoHide = Runnable { hideControls() }
    private val hideLockOverlay = Runnable { lockOverlay.visibility = View.GONE }
    private val progressTick = object : Runnable {
        override fun run() { report("progress"); handler.postDelayed(this, 10_000) }
    }
    private val uiTick = object : Runnable {
        override fun run() {
            if (!scrubbing) updateProgress()
            updateIntroSkip()
            handler.postDelayed(this, 500)
        }
    }
    private val subTick = object : Runnable {
        override fun run() {

            handler.postDelayed(this, 100)
        }
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
        speed = prefs.getFloat("speed", 1f)
        autoPlayNext = prefs.getBoolean("autoplay", true)
        subPos = prefs.getFloat("sub_pos", 0.12f)
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

        DiagnosticLog.record(this, "PlayerActivity: creating ExoPlayer; decoder=$decoder engine=Auto")
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

    /** Media3 player with hardware-first codec selection, decoder diagnostics and a larger adaptive buffer. */
    private fun createPlayer(): ExoPlayer {
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            val selected = when (decoder) {
                DecoderMode.SW -> all.filter { it.softwareOnly }.ifEmpty { all }
                DecoderMode.HW -> all.filter { it.hardwareAccelerated }.ifEmpty { all }
                DecoderMode.HW_PLUS -> all.sortedBy { if (it.hardwareAccelerated) 0 else 1 }
            }
            DiagnosticLog.record(
                applicationContext,
                "MediaCodec candidates mime=$mime mode=$decoder selected=" +
                    selected.joinToString { "${it.name}(hw=${it.hardwareAccelerated},sw=${it.softwareOnly})" }
            )
            selected
        }
        val factory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(true)
        if (decoder == DecoderMode.HW_PLUS) factory.forceEnableMediaCodecAsynchronousQueueing()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(25_000, 90_000, 3_000, 7_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val p = ExoPlayer.Builder(this, factory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.setPlaybackSpeed(speed)
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
        )
        p.setHandleAudioBecomingNoisy(true)
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                DiagnosticLog.record(
                    applicationContext,
                    "VIDEO_DECODER name=$decoderName init=${initializationDurationMs}ms device=${Build.MANUFACTURER} ${Build.MODEL}"
                )
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                DiagnosticLog.record(applicationContext, "VIDEO_DROPPED frames=$droppedFrames window=${elapsedMs}ms")
            }
        })
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

    /** 10-second skip button: a clean solid charcoal disc, with no glass treatment. */
    private fun skipButton(forward: Boolean): FrameLayout = FrameLayout(this).apply {
        background = circleBg(0xFF202024.toInt())
        setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_replay); setColorFilter(Color.WHITE)
            scaleX = if (forward) -1f else 1f
        }, FrameLayout.LayoutParams(-1, -1))
        addView(TextView(context).apply {
            text = "10"; setTextColor(Color.WHITE); textSize = 9f
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
                setColor(0xFF18232C.toInt())
            }
            addView(ImageView(context).apply {
                setImageResource(res); setColorFilter(0xFFFF4F78.toInt())
                setPadding(dp(9), dp(9), dp(9), dp(9))
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
        val backBtn = icon(R.drawable.ic_back, 24, 0).apply {
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(0xFFF4F5F7.toInt())
        }
        titleView = TextView(this).apply {
            text = intent.getStringExtra("title") ?: ""
            setTextColor(0xFFF4F5F7.toInt()); textSize = 17f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.NORMAL)
            maxLines = 1; maxWidth = dp(250); ellipsize = TextUtils.TruncateAt.END
        }
        subTitle = TextView(this).apply {
            textSize = 10f; setTextColor(0xFF65D6D0.toInt())
            text = nowPlayingText(true); letterSpacing = 0.04f; maxLines = 1
        }
        topPlaybackInfo = TextView(this).apply {
            textSize = 9f; setTextColor(0xFF9AA6B5.toInt()); visibility = View.GONE
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            text = ""
            setOnClickListener { showDeviceSheet() }
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(3), 0)
            addView(titleView)
            addView(subTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(topPlaybackInfo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val topRight = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            fun action(res: Int, click: () -> Unit) {
                addView(ImageView(this@PlayerActivity).apply {
                    setImageResource(res); setColorFilter(0xFFF4F5F7.toInt())
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = circleBg(0x4420162C, 0x667D718C)
                    setOnClickListener { click() }
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) })
            }
            action(R.drawable.ic_cast) { openCast() }
            action(R.drawable.ic_aspect) { cycleAspect(); showHud("Aspect · " + aspectName()) }
            action(R.drawable.ic_tune) { showPlaybackSettings("QUALITY") }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), dp(16), dp(8))
            addView(backBtn, LinearLayout.LayoutParams(dp(24), dp(36)))
            addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
            addView(badgeRow, LinearLayout.LayoutParams(-2, -2))
            addView(topRight, LinearLayout.LayoutParams(-2, -2))
        }
        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xE6110C20.toInt(), 0x88110C20.toInt(), 0x00110C20))
            addView(topBar, LinearLayout.LayoutParams(-1, -2))
        }

        fun dockAction(res: Int, label: String): LinearLayout =
            LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(5))
                isClickable = true
                addView(ImageView(this@PlayerActivity).apply {
                    setImageResource(res)
                    setColorFilter(accent)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(TextView(this@PlayerActivity).apply {
                    text = label; textSize = 9f; gravity = Gravity.CENTER
                    setTextColor(0xFFE9EDF2.toInt()); maxLines = 1
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }

        val lockAction = dockAction(R.drawable.ic_lock, "Lock")
        val qualityAction = dockAction(R.drawable.ic_tune, "Quality")
        val subtitlesAction = dockAction(R.drawable.ic_subtitles, "Subs")
        val audioAction = dockAction(R.drawable.ic_music, "Audio")
        val speedAction = dockAction(R.drawable.ic_schedule, "Speed")
        speedLabel = speedAction.getChildAt(1) as TextView
        val moreAction = dockAction(R.drawable.ic_settings, "More")

        prevEpisodeBtn = episodeNavButton("‹|").apply { textSize = 17f; background = null; setPadding(0, 0, 0, 0) }
        nextEpisodeBtn = episodeNavButton("|›").apply { textSize = 17f; background = null; setPadding(0, 0, 0, 0) }
        val rewBtn = skipButton(false).apply {
            background = circleBg(0xAA21182F.toInt(), 0x55FFFFFF); setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        playBtn = icon(R.drawable.ic_pause, 84, 18).apply {
            background = GradientDrawable().apply { colors = intArrayOf(0xFFFF704F.toInt(), 0xFFFF3F7B.toInt()); gradientType = GradientDrawable.LINEAR_GRADIENT; shape = GradientDrawable.OVAL }
            setColorFilter(0xFF210D19.toInt()); elevation = dp(3).toFloat()
        }
        val ring = object : View(this) {
            private val trackPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(3).toFloat()
                color = 0x667F718C
            }
            private val progressPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(3).toFloat()
                strokeCap = android.graphics.Paint.Cap.ROUND
                color = 0xFFFF4F78.toInt()
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val inset = dp(5).toFloat()
                val bounds = android.graphics.RectF(inset, inset, width - inset, height - inset)
                canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
                val fraction = if (duration > 0L) (playbackPositionMs().toFloat() / duration).coerceIn(0f, 1f) else 0f
                canvas.drawArc(bounds, -90f, fraction * 360f, false, progressPaint)
            }
        }.also { progressRing = it }
        val playControl = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
            addView(playBtn, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
        }
        val fwdBtn = skipButton(true).apply {
            background = circleBg(0xAA21182F.toInt(), 0x55FFFFFF); (getChildAt(0) as? ImageView)?.setColorFilter(0xFFE7EBF0.toInt()); setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
            fun slot(view: View, width: Int, height: Int, left: Int = 0, right: Int = 0) {
                addView(view, LinearLayout.LayoutParams(dp(width), dp(height)).apply {
                    leftMargin = dp(left); rightMargin = dp(right); gravity = Gravity.CENTER_VERTICAL
                })
            }
            slot(prevEpisodeBtn, 26, 48, right = 10)
            slot(rewBtn, 46, 46, right = 10)
            slot(playControl, 112, 112, right = 10)
            slot(fwdBtn, 46, 46, right = 8)
            slot(nextEpisodeBtn, 26, 48)
        }
        val centerControls = FrameLayout(this).apply {
            addView(transport, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }

        curTime = TextView(this).apply {
            text = "00:00"; setTextColor(0xFFF4F0F7.toInt()); textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER_VERTICAL
        }
        durTime = TextView(this).apply {
            text = "--:--"; setTextColor(0xFFF4F0F7.toInt()); textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER_VERTICAL
        }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(0xFFFF4F78.toInt())
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x667F8A98)
            secondaryProgressTintList = ColorStateList.valueOf(0x998F9EAD.toInt())
            minimumHeight = dp(18); setPadding(dp(2), 0, dp(2), 0)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) curTime.text = fmt(p.toLong()) }
            override fun onStartTrackingTouch(s: SeekBar) { scrubbing = true; handler.removeCallbacks(autoHide) }
            override fun onStopTrackingTouch(s: SeekBar) { scrubbing = false; seekPlaybackTo(s.progress.toLong()); scheduleHide() }
        })
        val timeline = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(7), dp(28), dp(1))
            addView(curTime, LinearLayout.LayoutParams(-2, dp(26)))
            addView(seek, LinearLayout.LayoutParams(0, dp(26), 1f).apply { leftMargin = dp(10); rightMargin = dp(10) })
            addView(durTime, LinearLayout.LayoutParams(-2, dp(26)))
        }
        val utilityDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(5), dp(16), dp(6)); clipToPadding = false
            fun pill(label: String, click: () -> Unit, emphasized: Boolean = false, width: Int = 0) {
                val v = TextView(this@PlayerActivity).apply {
                    text = label; textSize = 10f; gravity = Gravity.CENTER
                    setTextColor(if (emphasized) 0xFF180D19.toInt() else 0xFFF5F1F8.toInt())
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(30).toFloat()
                        if (emphasized) {
                            colors = intArrayOf(0xFFFF704F.toInt(), 0xFFFF3F7B.toInt())
                            gradientType = GradientDrawable.LINEAR_GRADIENT
                        } else {
                            setColor(0x331A1429); setStroke(dp(1), 0x887D718C.toInt())
                        }
                    }
                    setOnClickListener { click() }
                }
                addView(v, LinearLayout.LayoutParams(if (width > 0) dp(width) else -2, dp(32)).apply {
                    rightMargin = dp(8)
                })
            }
            pill("▤  Subtitles", { showPlaybackSettings("SUBTITLES") })
            pill("♫  Audio", { showPlaybackSettings("AUDIO") })
            pill(speedText(speed), { showPlaybackSettings("SPEED") }, width = 76)
            pill(qualityText(), { showPlaybackSettings("QUALITY") }, width = 112)
            addView(View(this@PlayerActivity), LinearLayout.LayoutParams(0, 1, 1f))
            episodesAction = TextView(this@PlayerActivity).apply {
                text = "☷  Episodes"; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(0xFFF5F1F8.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(11), dp(6), dp(11), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(30).toFloat(); setColor(0x331A1429)
                    setStroke(dp(1), 0x887D718C.toInt())
                }
                visibility = View.GONE
                setOnClickListener { showEpisodeSheet() }
            }
            addView(episodesAction, LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) })
            addView(lockAction, LinearLayout.LayoutParams(dp(42), -2).apply {
                leftMargin = dp(2)
            })
            nextEpisodeAction = TextView(this@PlayerActivity).apply {
                text = "Next ep  ›"; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(0xFF180D19.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(30).toFloat()
                    colors = intArrayOf(0xFFFF704F.toInt(), 0xFFFF3F7B.toInt())
                }
                visibility = View.GONE
                setOnClickListener { if (hasNext) switchTo(siblings[siblingIndex + 1].id) }
            }
            addView(nextEpisodeAction, LinearLayout.LayoutParams(-2, dp(32)).apply { leftMargin = dp(9) })
        }
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00110C20, 0xE90B0715.toInt(), 0xFF0B0715.toInt()))
            addView(timeline, LinearLayout.LayoutParams(-1, -2))
            addView(utilityDock, LinearLayout.LayoutParams(-1, -2))
        }
        fun sideRail(iconRes: Int, initial: Int, onValue: (Int) -> Unit): LinearLayout {
            val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            val slider = object : View(this@PlayerActivity) {
                private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x663D344B }
                private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
                var level = initial.coerceIn(0, 100)
                    set(value) { field = value.coerceIn(0, 100); invalidate() }

                private fun sliderTop() = 0f
                private fun sliderBottom() = height.toFloat()

                private fun updateFromTouch(y: Float) {
                    val top = sliderTop()
                    val bottom = sliderBottom()
                    val span = (bottom - top).coerceAtLeast(1f)
                    level = (((bottom - y) / span) * 100f).toInt().coerceIn(0, 100)
                    onValue(level)
                }

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val left = 0f
                    val right = width.toFloat()
                    val top = sliderTop()
                    val bottom = sliderBottom()
                    val radius = (width / 2f).coerceAtLeast(1f)
                    canvas.drawRoundRect(left, top, right, bottom, radius, radius, trackPaint)
                    val fillTop = bottom - (bottom - top) * (level / 100f)
                    if (level > 0) {
                        // Fill the complete rail width. The straight horizontal fill edge
                        // is the level indicator; deliberately draw no dot or center line.
                        val clip = android.graphics.Path().apply {
                            addRoundRect(left, top, right, bottom, radius, radius, android.graphics.Path.Direction.CW)
                        }
                        canvas.save()
                        canvas.clipPath(clip)
                        canvas.drawRect(left, fillTop, right, bottom, fillPaint)
                        canvas.restore()
                    }
                }

                override fun onTouchEvent(event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            handler.removeCallbacks(autoHide)
                            updateFromTouch(event.y)
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            updateFromTouch(event.y)
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            updateFromTouch(event.y)
                            scheduleHide()
                            performClick()
                            return true
                        }
                    }
                    return true
                }

                override fun performClick(): Boolean {
                    super.performClick()
                    return true
                }
            }
            if (iconRes == R.drawable.ic_brightness) {
                brightnessRailLevel = { value -> slider.level = value }
            } else if (iconRes == R.drawable.ic_volume) {
                volumeRailLevel = { value -> slider.level = value }
            }
            val railFace = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(28).toFloat(); setColor(0xB3130B20.toInt())
                }
                addView(slider, FrameLayout.LayoutParams(-1, -1))
                addView(ImageView(this@PlayerActivity).apply {
                    setImageResource(iconRes); setColorFilter(Color.WHITE)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            }
            rail.addView(railFace, LinearLayout.LayoutParams(dp(40), dp(124)))
            return rail
        }
        val leftRail = sideRail(R.drawable.ic_brightness, 55) { value ->
            brightness = (value / 100f).coerceIn(.05f, 1f)
            window.attributes = window.attributes.also { it.screenBrightness = brightness }
            brightnessRailLevel?.invoke((brightness * 100).toInt())
        }
        val rightRail = sideRail(R.drawable.ic_volume,
            (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 /
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1))) { value ->
            val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value * maxVolume / 100, 0)
            volumeRailLevel?.invoke(value)
        }
        val rightRailWithFullscreen = rightRail
        controls = FrameLayout(this).apply {
            addView(topPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(centerControls, FrameLayout.LayoutParams(-1, dp(150), Gravity.CENTER))
            addView(leftRail, FrameLayout.LayoutParams(dp(48), -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(22); topMargin = -dp(22) })
            addView(rightRailWithFullscreen, FrameLayout.LayoutParams(dp(48), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(22); topMargin = -dp(22) })
            addView(bottomPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        subText = TextView(this).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        hud = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(18), dp(11), dp(18), dp(11))
            background = GradientDrawable().apply { setColor(0xFF1A2731.toInt()); cornerRadius = dp(12).toFloat() }
            visibility = View.GONE
        }
        aspectHud = TextView(this).apply {
            text = "FIT"; textSize = 12f; letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFF180D19.toInt()); gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                colors = intArrayOf(0xFFFF704F.toInt(), 0xFFFF3F7B.toInt())
            }
            visibility = View.GONE; elevation = dp(8).toFloat()
        }
        spinner = ProgressBar(this).apply {
            isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(0xFF65D6D0.toInt())
            visibility = View.GONE
        }
        lockOverlay = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock); setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(0xFF17212B.toInt()); cornerRadius = dp(24).toFloat() }
            visibility = View.GONE
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(subText, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            addView(aspectHud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(28) })
            addView(controls, FrameLayout.LayoutParams(-1, -1))
            skipIntroBtn = TextView(this@PlayerActivity).apply {
                text = "SKIP INTRO  ›"; setTextColor(0xFF071014.toInt())
                textSize = 11f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.05f
                setPadding(dp(18), dp(10), dp(18), dp(10))
                background = GradientDrawable().apply { setColor(0xFF65D6D0.toInt()); cornerRadius = dp(20).toFloat() }
                visibility = View.GONE; elevation = dp(3).toFloat()
                setOnClickListener {
                    val active = introSegments.firstOrNull { playbackPositionMs() >= it.first && playbackPositionMs() < it.second }
                    if (active != null) { seekPlaybackTo(active.second); showHud("Intro skipped"); visibility = View.GONE }
                }
            }
            addView(skipIntroBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
                .apply { bottomMargin = dp(128); rightMargin = dp(112) })
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(lockOverlay, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.END)
                .apply { rightMargin = dp(18) })
        }
        setContentView(root)
        applySubStyle(); layoutSubtitle()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(i.left, dp(12) + i.top, dp(16) + i.right, dp(8))
            (bottomPanel.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = 0; rightMargin = 0; bottomMargin = i.bottom }
            bottomPanel.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(root)

        act(backBtn) { finish() }
        act(rewBtn) { seekBy(-10_000); showHud("-10s") }
        act(fwdBtn) { seekBy(10_000); showHud("+10s") }
        act(playBtn) { togglePlayback(); showControls() }
        act(prevEpisodeBtn) { if (hasPrev) switchTo(siblings[siblingIndex - 1].id) }
        act(nextEpisodeBtn) { if (hasNext) switchTo(siblings[siblingIndex + 1].id) }
        act(lockAction) { toggleLock() }
        act(qualityAction) { showPlaybackSettings("QUALITY") }
        act(subtitlesAction) { showPlaybackSettings("SUBTITLES") }
        act(audioAction) { showPlaybackSettings("AUDIO") }
        act(speedAction) { showPlaybackSettings("SPEED") }
        act(moreAction) { showMoreSheet() }
        lockOverlay.setOnClickListener { toggleLock() }
    }

    private fun showPlaybackSettings(section: String) {
        if (!::root.isInitialized) return
        controls.visibility = View.GONE
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x26060010)
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(22), dp(26), dp(22))
            background = GradientDrawable().apply { setColor(0xF9161028.toInt()) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@PlayerActivity).apply {
                text = "Playback settings"; textSize = 22f; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@PlayerActivity).apply {
                text = "×"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = circleBg(0x22140D24, 0x667F718C)
                setOnClickListener { root.removeView(overlay); if (controlsVisible) controls.visibility = View.VISIBLE }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        panel.addView(header)
        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val leftScroll = ScrollView(this).apply {
            isFillViewport = false; isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(left, ViewGroup.LayoutParams(-1, -2))
        }
        val rightScroll = ScrollView(this).apply {
            isFillViewport = false; isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(right, ViewGroup.LayoutParams(-1, -2))
        }
        fun label(parent: LinearLayout, text: String) {
            parent.addView(TextView(this).apply {
                this.text = text; textSize = 13f; letterSpacing = .15f
                setTextColor(0xFFBEB4CC.toInt()); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(6), dp(14), 0, dp(8))
            })
        }
        fun option(parent: LinearLayout, text: String, selected: Boolean, action: () -> Unit) {
            val v = TextView(this).apply {
                val label = SpannableString((if (selected) "●" else "○") + "   " + text)
                if (selected) label.setSpan(ForegroundColorSpan(0xFFFF4F78.toInt()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                this.text = label
                textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(40); setPadding(dp(14), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat()
                    setColor(if (selected) 0x443F354E else Color.TRANSPARENT)
                }
                setOnClickListener { action() }
            }
            parent.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        }
        fun choice(parent: LinearLayout, text: String, selected: Boolean, action: () -> Unit) {
            val v = TextView(this).apply {
                this.text = text; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(if (selected) 0xFF210D19.toInt() else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(30).toFloat()
                    if (selected) {
                        colors = intArrayOf(0xFFFF704F.toInt(), 0xFFFF3F7B.toInt())
                        gradientType = GradientDrawable.LINEAR_GRADIENT
                    } else {
                        setColor(0x222C243B); setStroke(dp(1), 0x667D718C)
                    }
                }
                setOnClickListener { action() }
            }
            parent.addView(v, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3); bottomMargin = dp(7)
            })
        }
        label(left, "SUBTITLES")
        option(left, "Off", !subOn) { subtitlesOff(); root.removeView(overlay); showControls() }
        subStreams.forEachIndexed { i, stream ->
            option(left, streamLabel(stream), subOn && activeSub == i) {
                selectSubStream(i); root.removeView(overlay); showControls()
            }
        }
        label(left, "AUDIO")
        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        audioGroups.forEachIndexed { i, group ->
            option(left, trackLabel(group, i), group.isSelected) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
                root.removeView(overlay); showControls()
            }
        }
        label(right, "SPEED")
        val speeds = floatArrayOf(.5f,.75f,1f,1.25f,1.5f,2f)
        speeds.toList().chunked(3).forEach { rowValues ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowValues.forEach { value -> choice(row, speedText(value), speed == value) {
                speed = value; setPlaybackSpeed(value); speedLabel.text = speedText(value)
                root.removeView(overlay); showControls()
            } }
            right.addView(row)
        }
        label(right, "QUALITY")
        val qualities = listOf(Quality.AUTO, Quality.K4, Quality.P1080, Quality.P720)
        qualities.chunked(2).forEach { rowValues ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowValues.forEach { value -> choice(row,
                when(value){ Quality.K4 -> "4K HDR"; Quality.AUTO -> "Auto"; else -> value.label },
                quality == value) {
                applyChoice(value, audioMode); root.removeView(overlay); showControls()
            } }
            right.addView(row)
        }
        columns.addView(leftScroll, LinearLayout.LayoutParams(0, -1, 1.12f).apply { rightMargin = dp(20) })
        columns.addView(rightScroll, LinearLayout.LayoutParams(0, -1, .94f))
        panel.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        overlay.addView(panel, FrameLayout.LayoutParams(
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                (resources.displayMetrics.widthPixels * .66f).toInt() else -1,
            -1, Gravity.END
        ))
        overlay.setOnClickListener { root.removeView(overlay); if (controlsVisible) controls.visibility = View.VISIBLE }
        panel.setOnClickListener { }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun cycleAspectRatio() {
        val modes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        val labels = arrayOf("FIT", "STRETCH", "CROP")
        val current = modes.indexOf(playerView.resizeMode).let { if (it < 0) 0 else it }
        val next = (current + 1) % modes.size
        playerView.resizeMode = modes[next]
        aspectHud.text = labels[next]
        aspectHud.visibility = View.VISIBLE
        handler.removeCallbacks(hideAspectHud)
        handler.postDelayed(hideAspectHud, 1400)
        aspectHud.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun showEpisodeSheet() = sheet("Episodes") { box, dlg ->
        if (siblings.isEmpty()) box.addView(noteText("No episode list is available for this item."))
        siblings.forEachIndexed { i, item ->
            box.addView(sheetRow(item.name, if (i == siblingIndex) "Now playing" else "") {
                dlg.dismiss(); switchTo(item.id)
            })
        }
    }

    private fun showSleepTimerSheet() = sheet("Sleep timer") { box, dlg ->
        listOf(15, 30, 45, 60, 90).forEach { minutes ->
            box.addView(sheetRow("$minutes minutes", "Stop playback after $minutes minutes") {
                handler.postDelayed({ if (!isFinishing) { player.pause(); showHud("Sleep timer ended") } }, minutes * 60_000L)
                dlg.dismiss(); showHud("Sleep timer · $minutes min")
            })
        }
    }

    private fun showMoreSheet() = sheet("More playback tools") { box, dlg ->
        val actions = listOf(
            "Audio output" to { showAudioOutputSheet() },
            "Aspect ratio" to { cycleAspect(); showHud("Aspect · " + aspectName()) },
            "Decoder" to { showDecoderSheet() },
            "Subtitle sync" to { showSubtitleSyncSheet() },
            "Volume boost" to { showVolumeSheet() },
            "Picture in picture" to { enterPip() },
            "Cast" to { openCast() },
            "Rotate screen" to { rotateScreen() },
            "Screenshot" to { takeScreenshot() },
            "Audio only" to { toggleAudioOnly() },
            "Repeat" to { toggleLoop() }
        )
        actions.forEach { (label, action) -> box.addView(sheetRow(label, "") { dlg.dismiss(); action() }) }
        box.addView(noteText("Episode navigation and Skip Intro appear automatically when available."))
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
        // Keep the outer transport slots in the layout even when unavailable.
        visibility = View.INVISIBLE
    }

    private fun updateEpisodeButtons() {
        prevEpisodeBtn.visibility = if (isSeriesEpisode && hasPrev) View.VISIBLE else View.GONE
        nextEpisodeBtn.visibility = if (isSeriesEpisode && hasNext) View.VISIBLE else View.GONE
        if (::episodesAction.isInitialized) episodesAction.visibility = if (isSeriesEpisode) View.VISIBLE else View.GONE
        if (::nextEpisodeAction.isInitialized) nextEpisodeAction.visibility = if (isSeriesEpisode && hasNext) View.VISIBLE else View.GONE
    }

    private fun updateIntroSkip() {
        if (!::skipIntroBtn.isInitialized || locked || offlinePath != null || introSegments.isEmpty()) {
            if (::skipIntroBtn.isInitialized) skipIntroBtn.visibility = View.GONE
            return
        }
        val active = introSegments.firstOrNull {
            playbackPositionMs() >= it.first && playbackPositionMs() < it.second
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
                setColor(0xFF111923.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
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
            speed = speeds[i]; setPlaybackSpeed(speed); speedLabel.text = speedText(speed); dlg.dismiss()
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

    private fun qualityText() = when (quality) {
        Quality.AUTO -> "Auto"
        Quality.ORIGINAL -> "Original"
        else -> quality.label.substringBefore(' ')
    }
    private fun speedText(f: Float) = (if (f % 1f == 0f) f.toInt().toString() else f.toString()) + "x"

    // ================================================================= subtitles
    private fun applySubStyle() {
        // Allervue subtitle standard: clean white text with a restrained black shadow.
        subText.textSize = if (isInPictureInPictureMode) 12f else 20f
        subText.setTextColor(Color.WHITE)
        subText.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        subText.setPadding(0, 0, 0, 0)
        subText.background = null
        subText.setShadowLayer(2.5f, 0f, 1.5f, Color.BLACK)
    }

    private fun layoutSubtitle() {
        val lp = subText.layoutParams as FrameLayout.LayoutParams
        val pip = isInPictureInPictureMode
        val h = if (root.height > 0) root.height else resources.displayMetrics.heightPixels
        val w = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
        subText.textSize = if (pip) 12f else 20f
        lp.bottomMargin = if (pip) dp(8) else (subPos * h).toInt() + (if (controlsVisible) dp(110) else 0)
        subText.maxWidth = (w * if (pip) 0.82f else 0.85f).toInt()
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
        val t = playbackPositionMs() - subDelayMs
        val idx = SubParser.indexAt(cues, t)
        val target: SubCue? = if (dir > 0) cues.getOrNull(idx + 1) else {
            val active = idx >= 0 && t <= cues[idx].endMs
            cues.getOrNull(if (active) idx - 1 else idx)
        }
        if (target != null) {
            seekPlaybackTo((target.startMs + subDelayMs).coerceAtLeast(0L))
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
        controlsVisible = true
        controls.visibility = View.VISIBLE
        layoutSubtitle(); updateProgress(); scheduleHide()
    }

    private fun hideControls() {
        controlsVisible = false; controls.visibility = View.GONE
        handler.removeCallbacks(autoHide)
        layoutSubtitle()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(autoHide)
        if (isPlaybackPlaying()) handler.postDelayed(autoHide, 4000)
    }

    private fun updateProgress() {
        val pos = playbackPositionMs()
        val dur = duration
        if (dur > 0) seek.max = dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        seek.progress = pos.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        curTime.text = fmt(pos)
        durTime.text = if (dur > 0) fmt(dur) else "--:--"
        progressRing?.invalidate()

    }

    // ================================================================= playback
    private suspend fun buildMediaItem(): MediaItem {
        val q = quality
        val chosen: PlaybackPlan = when {
            q == Quality.AUTO && audioMode == AudioMode.ORIGINAL ->
                Api.autoPlan(this, itemId, sourceId, sessionId)
            q == Quality.ORIGINAL && audioMode == AudioMode.ORIGINAL ->
                PlaybackPlan(Api.streamUrl(itemId, sourceId), false, false, sessionId, "Direct play (original file)")
            else -> {
                val useQ = if (q == Quality.AUTO) Quality.ORIGINAL else q
                val t = Api.transcodeUrl(itemId, sourceId, useQ, audioMode, sessionId)
                PlaybackPlan(t.url, true, true, t.sessionId, "Transcoding · ${useQ.label} · audio ${audioMode.label}")
            }
        }
        plan = chosen
        updateTopPlaybackInfo()
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
                    if (player.playWhenReady) hasPlayedOnce = true
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
                val http = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                                val directPlayFailure = plan?.transcoding != true && offlinePath == null && !autoRetried
                if (directPlayFailure) {
                    DiagnosticLog.record(this@PlayerActivity, "ExoPlayer direct-play failed; attempting Jellyfin H.264 fallback")
                    fallbackTranscode()
                    return
                }
                val detail = http?.let { " [HTTP ${it.responseCode} ${it.dataSpec.uri.lastPathSegment}]" } ?: "";
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
            val savedSubs = Downloads.subtitles(this@PlayerActivity, itemId)
            val preferredSub = savedSubs.firstOrNull { it.language.startsWith("en", true) }
                ?: savedSubs.firstOrNull { it.isDefault }
                ?: savedSubs.firstOrNull()
            if (preferredSub != null) {
                val parsed = runCatching { SubParser.parse(Api.decodeText(File(preferredSub.file).readBytes())) }.getOrNull()
                if (parsed != null) {
                    cues = parsed; subOn = true; activeSub = -2
                    showHud("Offline subtitles · ${preferredSub.title}", 1800)
                }
            }
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
            // AUTO must use Api.autoPlan so Jellyfin receives the actual hardware/profile limits.
            // Explicit ORIGINAL remains a direct stream request.
            val canStartDirectly = quality == Quality.ORIGINAL && audioMode == AudioMode.ORIGINAL
            if (canStartDirectly) {
                val url = Api.streamUrl(itemId)
                plan = PlaybackPlan(url, false, false, sessionId, "Direct play · metadata loading in parallel")
                updateTopPlaybackInfo()
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
            isSeriesEpisode = item.type == "Episode"
            updateEpisodeButtons()
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
        isSeriesEpisode = false
        if (::episodesAction.isInitialized) episodesAction.visibility = View.GONE
        if (::nextEpisodeAction.isInitialized) nextEpisodeAction.visibility = View.GONE
        defaultAudioApplied = false; defaultSubtitleApplied = false
        updateIntroSkip()
        sessionId = UUID.randomUUID().toString().replace("-", "")


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
        val pos = playbackPositionMs()


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
                    else -> togglePlayback()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (locked) return
                longPressing = true; setPlaybackSpeed(2f); showHud("2x", 100_000)
                root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || e1 == null || scale.isInProgress || longPressing) return false
                if (mode == Mode.NONE) {
                    mode = when {
                        abs(dx) > abs(dy) -> { startPos = playbackPositionMs(); Mode.SEEK }
                        e1.x < playerView.width / 2f -> {
                            val b = window.attributes.screenBrightness
                            brightness = if (b < 0) 0.5f else b; Mode.BRIGHT
                        }
                        else -> { volF = currentVolumeLevel(); Mode.VOLUME }
                    }
                }
                when (mode) {
                    Mode.SEEK -> {
                        seekTarget = (startPos + (e2.x - e1.x) * 120_000f / playerView.width).toLong().coerceIn(0L, duration)
                        val sign = if (seekTarget >= startPos) "+" else "-"
                        showHud("${fmt(seekTarget)}  ($sign${fmt(abs(seekTarget - startPos))})", 1500)
                    }
                    Mode.BRIGHT -> {
                        brightness = (brightness + dy / playerView.height).coerceIn(0.01f, 1f)
                        window.attributes = window.attributes.also { it.screenBrightness = brightness }
                        brightnessRailLevel?.invoke((brightness * 100).toInt())
                        showHud("Brightness ${(brightness * 100).toInt()}%")
                    }
                    Mode.VOLUME -> {
                        volF = (volF + dy / playerView.height * maxVol() * 2.4f).coerceIn(0f, maxVol() * 2f)
                        setVolumeLevel(volF)
                        volumeRailLevel?.invoke((volF / maxVol() * 100f).toInt().coerceIn(0, 100))
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
                if (mode == Mode.SEEK) seekPlaybackTo(seekTarget)
                mode = Mode.NONE
                if (longPressing) { longPressing = false; setPlaybackSpeed(speed); hud.visibility = View.GONE }
            }
            true
        }
    }

    private val duration: Long
        get() = if (::player.isInitialized) player.duration.coerceAtLeast(0L) else 0L

    private fun playbackPositionMs(): Long =
        if (::player.isInitialized) player.currentPosition.coerceAtLeast(0L) else 0L

    private fun isPlaybackPlaying(): Boolean = ::player.isInitialized && player.isPlaying

    private fun seekPlaybackTo(positionMs: Long) {
        if (::player.isInitialized) player.seekTo(positionMs.coerceAtLeast(0L))
    }

    private fun pausePlayback() { if (::player.isInitialized) player.pause() }
    private fun resumePlayback() { if (::player.isInitialized) player.play() }
    private fun togglePlayback() {
        if (!::player.isInitialized) return
        if (player.isPlaying) player.pause() else player.play()
    }
    private fun setPlaybackSpeed(value: Float) {
        speed = value.coerceIn(0.25f, 3f)
        if (::player.isInitialized) player.setPlaybackSpeed(speed)
    }

    private fun seekBy(ms: Long) {
        seekPlaybackTo((playbackPositionMs() + ms).coerceIn(0L, duration))
    }


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
    private fun updateTopPlaybackInfo() {
        if (!::topPlaybackInfo.isInitialized) return
        val phone = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val mode = when {
            offlinePath != null -> "Offline playback"

            plan?.transcoding == true -> "Transcoding"
            plan != null -> "Direct play"
            else -> "Auto playback"
        }
        topPlaybackInfo.text = "$phone · $mode"
    }

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
        if (autoRetried || offlinePath != null) return
        autoRetried = true
        val resumeAt = playbackPositionMs().coerceAtLeast(0L)
        DiagnosticLog.record(this, "ExoPlayer playback failed; retrying through Jellyfin server-side H.264 1080p transcode")
        startServerTranscodeFallback(resumeAt)
    }

    private fun startServerTranscodeFallback(pos: Long) {
        showHud("Playback failed. Retrying as H.264 1080p…", 3500)
        lifecycleScope.launch {
            try {
                val resolvedSourceId = sourceId.ifBlank {
                    Api.item(itemId).mediaSources?.firstOrNull()?.id ?: error("Jellyfin returned no media source")
                }
                sourceId = resolvedSourceId
                val t = Api.transcodeUrl(itemId, resolvedSourceId, Quality.P1080, AudioMode.ORIGINAL, sessionId)
                sessionId = t.sessionId
                plan = PlaybackPlan(t.url, true, true, t.sessionId, "Transcoding · H.264 1080p (fallback)")
                updateTopPlaybackInfo()
                player.setMediaItem(MediaItem.Builder().setUri(t.url).setMimeType(MimeTypes.APPLICATION_M3U8).build(), pos)
                player.prepare()
                player.playWhenReady = true
                DiagnosticLog.record(this@PlayerActivity, "Fallback HLS started at ${pos}ms")
            } catch (e: Exception) {
                DiagnosticLog.record(this@PlayerActivity, "H.264 fallback failed: ${e.message}", e)
                showHud("H.264 fallback failed: ${e.message}", 6000)
            }
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
        val pos = playbackPositionMs(); val paused = !isPlaybackPlaying(); val tc = isTranscoding
        lifecycleScope.launch { Api.report(kind, id, src, pos, paused, tc, sid) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::player.isInitialized) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SPACE -> {
                    togglePlayback()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { resumePlayback(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { pausePlayback(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (hasNext) switchTo(siblings[siblingIndex + 1].id) else seekBy(30_000)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (hasPrev) switchTo(siblings[siblingIndex - 1].id) else seekPlaybackTo(0)
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
        if (::player.isInitialized && isPlaybackPlaying() && pipSupported()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (isInPip) {
            hideControls()
            lockOverlay.visibility = View.GONE
            root.post { applySubStyle(); layoutSubtitle() }
        } else {
            root.post { applySubStyle(); layoutSubtitle() }
            if (!locked) showControls()
        }
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
            val pos = playbackPositionMs(); val id = itemId
            CoroutineScope(Dispatchers.IO).launch { Api.report("stop", id, id, pos, true, false, "") }
        }
        if (sourceId.isNotEmpty()) {
            val pos = playbackPositionMs(); val id = itemId; val src = sourceId
            val tc = isTranscoding; val sid = sessionId
            CoroutineScope(Dispatchers.IO).launch { Api.report("stop", id, src, pos, true, tc, sid) }
        }
        try { enhancer?.release() } catch (e: Exception) { }
        player.release()
        super.onDestroy()
    }
}