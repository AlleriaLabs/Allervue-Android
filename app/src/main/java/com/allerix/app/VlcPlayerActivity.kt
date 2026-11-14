package com.allerix.app

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.UUID

/** Standalone LibVLC backend. It is used by explicit VLC selection and Auto-mode decoder fallback. */
class VlcPlayerActivity : ComponentActivity() {
    private val accent = 0xFFD8B77E.toInt()
    private lateinit var libVLC: LibVLC
    private lateinit var vlc: MediaPlayer
    private lateinit var video: VLCVideoLayout
    private lateinit var title: TextView
    private lateinit var state: TextView
    private lateinit var play: TextView
    private lateinit var seek: SeekBar
    private lateinit var current: TextView
    private lateinit var duration: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var controls: FrameLayout
    private var locked = false
    private val handler = Handler(Looper.getMainLooper())
    private val autoHideControls = Runnable { if (!locked && ::controls.isInitialized) controls.visibility = View.GONE }
    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }
    private var itemId = ""
    private var sourceId = ""
    private var sessionId = ""
    private var filePath: String? = null
    private var streamUrl: String? = null
    private var positionMs = 0L
    private var durationMs = 0L
    private var speed = 1f
    private var autoPlayNext = true
    private var started = false
    private var reporting = false
    private var transcoding = false
    private var bufferingSince = 0L
    private var bufferingFallbackTriggered = false
    private val bufferingFallback = Runnable {
        if (bufferingSince > 0L && !bufferingFallbackTriggered && filePath == null && !transcoding) {
            bufferingFallbackTriggered = true
            DiagnosticLog.record(this, "VLC sustained buffering for 25s; requesting H.264 1080p transcode")
            fallbackToExoTranscode()
        }
    }
    private var siblings: List<Item> = emptyList()
    private var siblingIndex = -1
    private var introSegments: List<Pair<Long, Long>> = emptyList()
    private lateinit var skipIntro: TextView

    private val tick = object : Runnable {
        override fun run() {
            if (!::vlc.isInitialized) return
            val time = vlc.time.coerceAtLeast(0L)
            val length = vlc.length.coerceAtLeast(0L)
            if (!seek.isPressed && length > 0) {
                seek.max = length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                seek.progress = time.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                current.text = clock(time)
                duration.text = clock(length)
            }
            positionMs = time
            val segment = introSegments.firstOrNull { time >= it.first && time < it.second }
            skipIntro.visibility = if (segment == null) View.GONE else View.VISIBLE
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.load(this)
        itemId = intent.getStringExtra("id").orEmpty()
        if (itemId.isEmpty()) { finish(); return }
        sourceId = intent.getStringExtra("source_id").orEmpty()
        sessionId = intent.getStringExtra("session_id") ?: UUID.randomUUID().toString().replace("-", "")
        filePath = intent.getStringExtra("file")
        streamUrl = intent.getStringExtra("stream_url")
        positionMs = intent.getLongExtra("position_ms", 0L)
        autoPlayNext = intent.getBooleanExtra("autoplay_next", true)
        transcoding = intent.getBooleanExtra("transcoding", false)
        speed = prefs.getFloat("speed", 1f)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        buildUi()
        val options = arrayListOf("--network-caching=6000", "--file-caching=3000", "--avcodec-hw=any")
        libVLC = LibVLC(this, options)
        vlc = MediaPlayer(libVLC)
        vlc.setEventListener { event ->
            runOnUiThread {
                when (event.type) {
                    MediaPlayer.Event.Opening -> spinner.visibility = View.VISIBLE
                    MediaPlayer.Event.Buffering -> {
                        spinner.visibility = View.VISIBLE
                        if (bufferingSince == 0L) {
                            bufferingSince = SystemClock.elapsedRealtime()
                            handler.postDelayed(bufferingFallback, 25_000)
                        }
                    }
                    MediaPlayer.Event.Playing -> {
                        bufferingSince = 0L
                        handler.removeCallbacks(bufferingFallback)
                        spinner.visibility = View.GONE
                        play.text = "Ⅱ"
                        state.text = "Now Playing"
                        if (!reporting && sourceId.isNotEmpty()) {
                            reporting = true
                            val id = itemId; val src = sourceId; val pos = vlc.time; val transcode = transcoding; val sid = sessionId
                            lifecycleScope.launch(Dispatchers.IO) { Api.report("start", id, src, pos, false, transcode, sid) }
                        }
                    }
                    MediaPlayer.Event.Paused -> { play.text = "▶"; state.text = "Paused"; reportProgress() }
                    MediaPlayer.Event.Stopped -> { spinner.visibility = View.GONE; play.text = "▶" }
                    MediaPlayer.Event.EndReached -> {
                        reportStopped()
                        if (autoPlayNext && siblingIndex >= 0 && siblingIndex < siblings.size - 1) {
                            playItem(siblings[siblingIndex + 1].id, 0L)
                        } else finish()
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        handler.removeCallbacks(bufferingFallback)
                        spinner.visibility = View.GONE
                        if (prefs.getString("playback_engine", "AUTO") == PlaybackEngine.AUTO.name &&
                            !transcoding && filePath == null) {
                            fallbackToExoTranscode()
                        } else {
                            state.text = "VLC couldn't open this stream"
                        }
                    }
                }
            }
        }
        lifecycleScope.launch { loadAndPlay() }
        handler.post(tick)
    }

    private fun buildUi() {
        // Keep VLC visually identical to the primary ExoPlayer surface.
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VLCVideoLayout(this)
        root.addView(video, FrameLayout.LayoutParams(-1, -1))

        fun icon(res: Int, size: Int, pad: Int = 7) = ImageView(this).apply {
            setImageResource(res); setColorFilter(0xFFF4EFE5.toInt())
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
        fun featureAction(res: Int, label: String, action: () -> Unit) =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(1), dp(5), dp(1), dp(5))
                addView(ImageView(this@VlcPlayerActivity).apply {
                    setImageResource(res); setColorFilter(accent)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(19), dp(19)))
                addView(TextView(this@VlcPlayerActivity).apply {
                    text = label; textSize = 8f; gravity = Gravity.CENTER
                    setTextColor(0xFFF4EFE5.toInt()); maxLines = 1
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
                setOnClickListener { action() }
            }

        val back = icon(R.drawable.ic_back, 34).apply { setOnClickListener { finish() } }
        title = TextView(this).apply {
            text = intent.getStringExtra("title") ?: "Allervue"
            setTextColor(0xFFF4EFE5.toInt()); textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        state = TextView(this).apply {
            text = "Now Playing"; setTextColor(accent); textSize = 10f
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(4), 0); addView(title); addView(state)
        }
        val cast = icon(R.drawable.ic_cast, 34).apply {
            setOnClickListener { android.widget.Toast.makeText(context, "Casting is not available in VLC playback", android.widget.Toast.LENGTH_SHORT).show() }
        }
        val pip = icon(R.drawable.ic_pip, 34).apply {
            setOnClickListener {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    try { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) } catch (_: Exception) {}
                }
            }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            addView(back, LinearLayout.LayoutParams(dp(34), dp(38)))
            addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
            addView(cast, LinearLayout.LayoutParams(dp(34), dp(38)))
            addView(pip, LinearLayout.LayoutParams(dp(34), dp(38)))
        }
        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xE6101013.toInt(), 0x99101013.toInt(), 0x00101013))
            addView(topBar, LinearLayout.LayoutParams(-1, -2))
        }

        current = TextView(this).apply {
            text = "00:00"; setTextColor(0xFFF4EFE5.toInt()); textSize = 10f
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        duration = TextView(this).apply {
            text = "--:--"; setTextColor(0xFFAAA69E.toInt()); textSize = 10f
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        seek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFF4EFE5.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x667A7772)
            minimumHeight = dp(18); setPadding(dp(2), 0, dp(2), 0)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) current.text = clock(progress.toLong())
            }
            override fun onStartTrackingTouch(bar: SeekBar) { handler.removeCallbacks(autoHideControls) }
            override fun onStopTrackingTouch(bar: SeekBar) { if (::vlc.isInitialized) vlc.time = bar.progress.toLong(); scheduleHideControls() }
        })
        val timeline = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(2))
            addView(current, LinearLayout.LayoutParams(-2, dp(28)))
            addView(seek, LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                leftMargin = dp(12); rightMargin = dp(12)
            })
            addView(duration, LinearLayout.LayoutParams(-2, dp(28)))
        }

        val previous = TextView(this).apply {
            text = "‹|"; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            visibility = View.INVISIBLE
        }
        val next = TextView(this).apply {
            text = "|›"; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            visibility = View.INVISIBLE
        }
        fun skipButton(forward: Boolean) = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF202024.toInt()) }
            addView(ImageView(this@VlcPlayerActivity).apply {
                setImageResource(R.drawable.ic_replay); setColorFilter(Color.WHITE)
                scaleX = if (forward) -1f else 1f
            }, FrameLayout.LayoutParams(-1, -1))
            addView(TextView(this@VlcPlayerActivity).apply {
                text = "10"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val rewind = skipButton(false)
        val forward = skipButton(true)
        play = TextView(this).apply {
            text = "Ⅱ"; textSize = 24f; gravity = Gravity.CENTER
            setTextColor(0xFF101013.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            elevation = dp(5).toFloat()
        }
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            addView(previous, LinearLayout.LayoutParams(dp(30), dp(48)).apply { rightMargin = dp(12) })
            addView(rewind, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(16) })
            addView(play, LinearLayout.LayoutParams(dp(68), dp(68)).apply { rightMargin = dp(16) })
            addView(forward, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
            addView(next, LinearLayout.LayoutParams(dp(30), dp(48)))
        }
        val centerControls = FrameLayout(this).apply {
            addView(transport, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; weightSum = 7f
            setPadding(0, dp(3), 0, dp(10)); clipToPadding = false
            val entries = listOf(
                featureAction(R.drawable.ic_lock, "Lock") { toggleVlcLock() },
                featureAction(R.drawable.ic_tune, "Quality") { showQualityInfo() },
                featureAction(R.drawable.ic_subtitles, "Subs") { showTrackInfo("Subtitle") },
                featureAction(R.drawable.ic_music, "Audio") { showTrackInfo("Audio") },
                featureAction(R.drawable.ic_volume, "Output") { android.widget.Toast.makeText(context, "Audio output follows the selected device", android.widget.Toast.LENGTH_SHORT).show() },
                featureAction(R.drawable.ic_aspect, "Aspect") { android.widget.Toast.makeText(context, "Aspect ratio: fit", android.widget.Toast.LENGTH_SHORT).show() },
                featureAction(R.drawable.ic_schedule, "${speed}×") { chooseSpeed() }
            )
            entries.forEach { addView(it, LinearLayout.LayoutParams(0, dp(62), 1f)) }
        }
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00101013, 0xB8101013.toInt(), 0xF5101013.toInt()))
            addView(timeline, LinearLayout.LayoutParams(-1, -2))
            addView(dock, LinearLayout.LayoutParams(-1, -2))
        }

        controls = FrameLayout(this).apply {
            addView(topPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(centerControls, FrameLayout.LayoutParams(-1, dp(112), Gravity.CENTER))
            addView(bottomPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
            visibility = View.GONE
        }
        skipIntro = TextView(this).apply {
            text = "SKIP INTRO  ›"; setTextColor(0xFF101013.toInt())
            textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.06f
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(22).toFloat() }
            visibility = View.GONE; elevation = dp(5).toFloat()
            setOnClickListener {
                val active = introSegments.firstOrNull { vlc.time >= it.first && vlc.time < it.second }
                if (active != null) vlc.time = active.second
                visibility = View.GONE
            }
        }
        root.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        root.addView(skipIntro, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(132) })
        root.setOnClickListener { if (locked) toggleVlcLock() else toggleControls() }
        controls.setOnClickListener { }
        setContentView(root)
        root.keepScreenOn = true
        showControls()
        handler.postDelayed(autoHideControls, 5000)
        play.setOnClickListener { if (vlc.isPlaying) vlc.pause() else vlc.play() }
        rewind.setOnClickListener { vlc.time = (vlc.time - 10_000).coerceAtLeast(0L) }
        forward.setOnClickListener { vlc.time = (vlc.time + 10_000).coerceAtMost(vlc.length) }
    }

    private fun showControls() { if (::controls.isInitialized) controls.visibility = View.VISIBLE; handler.removeCallbacks(autoHideControls); scheduleHideControls() }
    private fun toggleControls() { if (!::controls.isInitialized) return; if (controls.visibility == View.VISIBLE) controls.visibility = View.GONE else showControls() }
    private fun scheduleHideControls() { handler.removeCallbacks(autoHideControls); if (!locked) handler.postDelayed(autoHideControls, 5000) }
    private fun toggleVlcLock() { locked = !locked; if (locked) controls.visibility = View.GONE else showControls(); android.widget.Toast.makeText(this, if (locked) "Controls locked" else "Controls unlocked", android.widget.Toast.LENGTH_SHORT).show() }
    private fun showQualityInfo() { android.app.AlertDialog.Builder(this).setTitle("Playback quality").setMessage("VLC is using the stream selected by Allervue. If playback cannot decode it, Auto mode will request a compatible Jellyfin transcode.").setPositiveButton("OK", null).show() }
    private fun showTrackInfo(kind: String) { android.app.AlertDialog.Builder(this).setTitle("$kind tracks").setMessage("Track selection is managed by the active VLC stream.").setPositiveButton("OK", null).show() }

    private fun loadAndPlay() {
        lifecycleScope.launch {
            try {
                var url = streamUrl
                val local = filePath
                if (local == null) {
                    val item = Api.item(itemId)
                    title.text = item.name
                    if (sourceId.isEmpty()) sourceId = item.mediaSources?.firstOrNull()?.id
                        ?: error("No playable media source")
                    introSegments = runCatching { Api.introSegments(itemId) }.getOrDefault(emptyList())
                    if (url.isNullOrBlank()) {
                        val qualityName = prefs.getString("quality", "AUTO") ?: "AUTO"
                        val q = runCatching { Quality.valueOf(qualityName) }.getOrDefault(Quality.AUTO)
                        val audio = runCatching { AudioMode.valueOf(prefs.getString("audio", "ORIGINAL") ?: "ORIGINAL") }
                            .getOrDefault(AudioMode.ORIGINAL)
                        val plan = when {
                            q == Quality.AUTO && audio == AudioMode.ORIGINAL ->
                                Api.autoPlan(this@VlcPlayerActivity, itemId, sourceId, sessionId)
                            q == Quality.ORIGINAL && audio == AudioMode.ORIGINAL ->
                                PlaybackPlan(Api.streamUrl(itemId, sourceId), false, false, sessionId, "Original")
                            else -> Api.transcodeUrl(itemId, sourceId, q, audio, sessionId)
                                .let { PlaybackPlan(it.url, true, true, it.sessionId, "Transcode") }
                        }
                        url = plan.url
                        sessionId = plan.sessionId
                        transcoding = plan.transcoding
                    }
                    val parentId = item.parentId
                    if (item.type == "Episode" && parentId != null) {
                        siblings = runCatching { Api.children(parentId) }.getOrDefault(emptyList())
                            .filter { it.type == "Episode" }
                        siblingIndex = siblings.indexOfFirst { it.id == itemId }
                    }
                    val resume = (item.userData?.positionTicks ?: 0L) / 10_000L
                    if (positionMs <= 0L && resume > 5_000L) positionMs = resume
                }
                val uri = if (local != null) Uri.fromFile(File(local)) else Uri.parse(url ?: error("No stream URL"))
                vlc.attachViews(video, null, false, false)
                Media(libVLC, uri).apply {
                    setHWDecoderEnabled(true, false)
                    addOption(":network-caching=6000")
                    addOption(":file-caching=3000")
                    addOption(":avcodec-hw=any")
                    vlc.media = this
                    release()
                }
                if (positionMs > 0L) vlc.setTime(positionMs)
                vlc.setRate(speed)
                vlc.play()
            } catch (e: Exception) {
                spinner.visibility = View.GONE
                state.text = "Unable to start VLC: ${e.message ?: "unknown error"}"
            }
        }
    }

    private fun playItem(id: String, startMs: Long) {
        reportStopped()
        itemId = id; positionMs = startMs; sourceId = ""; streamUrl = null; sessionId = UUID.randomUUID().toString()
        reporting = false
        vlc.stop()
        lifecycleScope.launch { loadAndPlay() }
    }

    private fun fallbackToExoTranscode() {
        state.text = "VLC couldn't decode this file. Trying Jellyfin transcoding…"
        reportStopped()
        // Return to ExoPlayer in Auto mode; its decoder-error handler will request a Jellyfin HLS transcode.
        // Explicitly request a broadly compatible H.264 1080p stream; Auto could select the same failing HEVC source again.
        prefs.edit().putString("quality", Quality.P1080.name).apply()
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra("id", itemId)
            .putExtra("start_position_ms", vlc.time)
            .putExtra("skip_vlc_fallback", true)
        startActivity(intent)
        finish()
    }

    private fun chooseSpeed() {
        val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val labels = values.map { "${it}×" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Playback speed").setSingleChoiceItems(labels, values.indexOfFirst { it == speed }.coerceAtLeast(0)) { d, i ->
            speed = values[i]; prefs.edit().putFloat("speed", speed).apply()
            vlc.setRate(speed); d.dismiss()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun reportProgress() {
        if (sourceId.isEmpty() || !reporting) return
        val id = itemId; val src = sourceId; val pos = vlc.time; val paused = !vlc.isPlaying
        val transcode = transcoding; val sid = sessionId
        lifecycleScope.launch(Dispatchers.IO) { Api.report("progress", id, src, pos, paused, transcode, sid) }
    }

    private fun reportStopped() {
        if (sourceId.isEmpty() || !reporting || !::vlc.isInitialized) return
        val id = itemId; val src = sourceId; val pos = vlc.time; val transcode = transcoding; val sid = sessionId
        reporting = false
        lifecycleScope.launch(Dispatchers.IO) { Api.report("stop", id, src, pos, true, transcode, sid) }
    }

    private fun clock(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        if (::vlc.isInitialized && vlc.isPlaying && !isInPictureInPictureMode) vlc.pause()
        reportProgress()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::vlc.isInitialized) {
            reportStopped()
            vlc.stop()
            vlc.detachViews()
            vlc.release()
            libVLC.release()
        }
        handler.removeCallbacks(bufferingFallback)
        super.onDestroy()
    }
}
