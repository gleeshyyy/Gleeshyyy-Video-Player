package com.gleeshyyy.videoplayer

import android.content.res.Configuration
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val title = intent.getStringExtra("title") ?: uri.lastPathSegment ?: "Video"
        videoKey = uri.toString()
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
            setBackgroundColor(0xFF000000.toInt())
        }
        root.addView(playerView, LinearLayout.LayoutParams(-1, 0, 1f))
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
        super.onDestroy()
    }
}
