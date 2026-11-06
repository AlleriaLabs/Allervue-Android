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
    private val handler = Handler(Looper.getMainLooper())
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
        val options = arrayListOf("--network-caching=1200", "--file-caching=1000", "--no-drop-late-frames")
        libVLC = LibVLC(this, options)
        vlc = MediaPlayer(libVLC)
        vlc.setEventListener { event ->
            runOnUiThread {
                when (event.type) {
                    MediaPlayer.Event.Opening, MediaPlayer.Event.Buffering -> spinner.visibility = View.VISIBLE
                    MediaPlayer.Event.Playing -> {
                        spinner.visibility = View.GONE
                        play.text = "Ⅱ"
                        state.text = "VLC · Playing"
                        if (!reporting && sourceId.isNotEmpty()) {
                            reporting = true
                            val id = itemId; val src = sourceId; val pos = vlc.time; val transcode = transcoding; val sid = sessionId
                            lifecycleScope.launch(Dispatchers.IO) { Api.report("start", id, src, pos, false, transcode, sid) }
                        }
                    }
                    MediaPlayer.Event.Paused -> { play.text = "▶"; state.text = "VLC · Paused"; reportProgress() }
                    MediaPlayer.Event.Stopped -> { spinner.visibility = View.GONE; play.text = "▶" }
                    MediaPlayer.Event.EndReached -> {
                        reportStopped()
                        if (autoPlayNext && siblingIndex >= 0 && siblingIndex < siblings.size - 1) {
                            playItem(siblings[siblingIndex + 1].id, 0L)
                        } else finish()
                    }
                    MediaPlayer.Event.EncounteredError -> {
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
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VLCVideoLayout(this)
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        title = TextView(this).apply {
            text = intent.getStringExtra("title") ?: "Allervue"
            setTextColor(Color.WHITE); textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        state = TextView(this).apply { text = "VLC · Connecting"; setTextColor(0xFFCCCCCC.toInt()); textSize = 12f }
        top.addView(title); top.addView(state)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xDD08080A.toInt(), 0x00000000))
        }
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        current = TextView(this).apply { text = "00:00"; setTextColor(Color.WHITE); textSize = 12f }
        duration = TextView(this).apply { text = "--:--"; setTextColor(Color.WHITE); textSize = 12f }
        seek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) current.text = clock(progress.toLong())
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) { if (::vlc.isInitialized) vlc.time = bar.progress.toLong() }
        })
        times.addView(current)
        times.addView(seek, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
            leftMargin = dp(10); rightMargin = dp(10)
        })
        times.addView(duration)
        bottom.addView(times)

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(0xCC1B1B20.toInt()); cornerRadius = dp(18).toFloat() }
            setOnClickListener { action() }
        }
        actions.addView(button("−10s") { vlc.time = (vlc.time - 10_000).coerceAtLeast(0) })
        play = button("▶") { if (vlc.isPlaying) vlc.pause() else vlc.play() }
        actions.addView(play, LinearLayout.LayoutParams(dp(58), dp(48)).apply { leftMargin = dp(12); rightMargin = dp(12) })
        actions.addView(button("+10s") { vlc.time = (vlc.time + 10_000).coerceAtMost(vlc.length) })
        actions.addView(button("1×") { chooseSpeed() }.apply { tag = "speed" })
        bottom.addView(actions)

        skipIntro = button("SKIP INTRO  ›") { 
            val active = introSegments.firstOrNull { vlc.time >= it.first && vlc.time < it.second }
            if (active != null) vlc.time = active.second
            skipIntro.visibility = View.GONE
        }.apply {
            textSize = 13f; visibility = View.GONE
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(20).toFloat() }
        }
        spinner = ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(accent) }
        root.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        root.addView(skipIntro, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(142) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

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
                    addOption(":network-caching=1200")
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
        prefs.edit().putString("quality", Quality.AUTO.name).apply()
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
        super.onDestroy()
    }
}
