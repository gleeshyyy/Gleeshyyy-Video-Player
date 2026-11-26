package com.gleeshyyy.videoplayer

import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.GestureDetector
import android.widget.FrameLayout
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var videoKey: String = ""
    private val playbackSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var playbackSpeedIndex = 2
    private lateinit var gestureLayer: View
    private lateinit var gestureDetector: GestureDetector
    private var gestureVolume = 0f
    private var gestureMode = 0
    private var lastGestureX = 0f
    private var lastGestureY = 0f
    private var gestureBrightness = 0.5f
    private var longPressSpeedActive = false
    private var longPressSpeed = 2.0f
    private lateinit var audioManager: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val title = intent.getStringExtra("title") ?: uri.lastPathSegment ?: "Video"
        videoKey = uri.toString()
        longPressSpeed = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE).getFloat("long_press_speed", 2.0f)
        remember(videoKey)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF080D12.toInt())
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 8, 18, 8)
        }
        val backTitle = TextView(this).apply {
            text = "‹    $title"
            textSize = 16f
            setTextColor(0xFFF1F5F7.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, 10, 8, 10)
            setOnClickListener { finish() }
        }
        heading.addView(backTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val speedButton = TextView(this).apply {
            text = "1.0×"
            textSize = 14f
            setTextColor(0xFF20D66B.toInt())
            setPadding(12, 10, 4, 10)
            contentDescription = "Playback speed"
            setOnClickListener {
                playbackSpeedIndex = (playbackSpeedIndex + 1) % playbackSpeeds.size
                val speed = playbackSpeeds[playbackSpeedIndex]
                text = "${speed}×"
                player?.setPlaybackSpeed(speed)
            }
        }
        heading.addView(speedButton)
        root.addView(heading)
        playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowRewindButton(false)
            setShowFastForwardButton(false)
            setBackgroundColor(0xFF000000.toInt())
        }
        val playbackArea = FrameLayout(this)
        playbackArea.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) playerView.hideController()
                else playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val zone = e.x / gestureLayer.width.coerceAtLeast(1).toFloat()
                when {
                    zone < 1f / 3f -> {
                        val current = player?.currentPosition ?: 0L
                        val duration = player?.duration ?: 0L
                        if (duration > 0L) player?.seekTo((current - 10_000L).coerceIn(0L, duration))
                    }
                    zone > 2f / 3f -> {
                        val current = player?.currentPosition ?: 0L
                        val duration = player?.duration ?: 0L
                        if (duration > 0L) player?.seekTo((current + 10_000L).coerceIn(0L, duration))
                    }
                    else -> {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                player?.setPlaybackSpeed(longPressSpeed)
                longPressSpeedActive = true
            }
        })
        gestureLayer = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (playerView.isControllerFullyVisible) false
                else handlePlaybackGesture(event)
            }
        }
        playbackArea.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        root.addView(playbackArea, LinearLayout.LayoutParams(-1, 0, 1f))
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        gestureBrightness = currentBrightness()
        setContentView(root)
        updateSystemBars()

        val prefs = getSharedPreferences("gleeshplayer_history", MODE_PRIVATE)
        val resumeAt = prefs.getLong("position_$videoKey", 0L).coerceAtLeast(0L)
        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.setMediaItem(MediaItem.fromUri(uri), resumeAt)
            it.prepare()
            it.play()
        }
    }


    private fun handlePlaybackGesture(event: MotionEvent): Boolean {
        val width = gestureLayer.width.coerceAtLeast(1)
        val height = gestureLayer.height.coerceAtLeast(1)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastGestureX = event.x
                lastGestureY = event.y
                gestureMode = 0
                gestureVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastGestureX
                val dy = event.y - lastGestureY
                if (gestureMode == 0 && (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f)) {
                    gestureMode = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) 1
                    else if (lastGestureX < width / 2f) 2 else 3
                }
                when (gestureMode) {
                    1 -> {
                        val duration = player?.duration ?: 0L
                        if (duration > 0L) {
                            val target = ((player?.currentPosition ?: 0L) + (dx * 55f).toLong())
                                .coerceIn(0L, duration)
                            player?.seekTo(target)
                        }
                    }
                    2 -> {
                        gestureBrightness = (gestureBrightness - dy / height).coerceIn(0.05f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = gestureBrightness }
                    }
                    3 -> {
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        gestureVolume = (gestureVolume - dy / height).coerceIn(0f, 1f)
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            (gestureVolume * max).toInt().coerceIn(0, max),
                            0
                        )
                    }
                }
                lastGestureX = event.x
                lastGestureY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureMode = 0
                if (longPressSpeedActive) {
                    player?.setPlaybackSpeed(playbackSpeeds[playbackSpeedIndex])
                    longPressSpeedActive = false
                }
                return true
            }
        }
        return true
    }

    private fun currentBrightness(): Float {
        val windowBrightness = window.attributes.screenBrightness
        if (windowBrightness >= 0f) return windowBrightness.coerceIn(0.05f, 1f)
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }.coerceIn(0.05f, 1f)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemBars()
    }

    private fun updateSystemBars() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        window.decorView.systemUiVisibility = if (landscape) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun remember(uri: String) {
        val prefs = getSharedPreferences("gleeshplayer_history", MODE_PRIVATE)
        val old = try {
            val array = JSONArray(prefs.getString("recent", "[]"))
            (0 until array.length()).map { array.getString(it) }
        } catch (_: Exception) { emptyList() }
        val next = (listOf(uri) + old.filterNot { it == uri }).take(10)
        prefs.edit().putString("recent", JSONArray(next).toString()).apply()
    }

    private fun savePosition() {
        val current = player ?: return
        val position = current.currentPosition.coerceAtLeast(0L)
        val duration = current.duration
        val savedPosition = if (duration > 0 && position >= duration - 1500L) 0L else position
        getSharedPreferences("gleeshplayer_history", MODE_PRIVATE)
            .edit().putLong("position_$videoKey", savedPosition).apply()
    }

    override fun onPause() {
        if (longPressSpeedActive) {
            player?.setPlaybackSpeed(playbackSpeeds[playbackSpeedIndex])
            longPressSpeedActive = false
        }
        savePosition()
        player?.pause()
        if (::playerView.isInitialized) playerView.onPause()
        super.onPause()
    }

    override fun onStop() {
        savePosition()
        super.onStop()
    }

    override fun onDestroy() {
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        window.attributes = window.attributes.apply { screenBrightness = -1f }
        super.onDestroy()
    }
}
