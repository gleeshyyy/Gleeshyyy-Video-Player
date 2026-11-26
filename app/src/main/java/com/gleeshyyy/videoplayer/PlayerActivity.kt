package com.gleeshyyy.videoplayer

import android.content.res.Configuration
import android.net.Uri
import android.database.Cursor
import android.provider.OpenableColumns
import android.content.pm.ActivityInfo
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
import android.widget.SeekBar
import android.widget.Button
import android.graphics.Typeface
import android.util.TypedValue
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray

class PlayerActivity : AppCompatActivity() {
    private val palette get() = ThemeManager.palette(this)
    private var player: ExoPlayer? = null
    private var externalSubtitleUri: Uri? = null
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachExternalSubtitle(uri)
    }
    private lateinit var playerView: PlayerView
    private var videoKey: String = ""
    private val playbackSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var playbackSpeedIndex = 2
    private val resizeModes = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
    private val resizeLabels = listOf("FIT", "ZOOM", "FILL")
    private var resizeModeIndex = 0
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
    private lateinit var feedbackLabel: TextView
    private lateinit var feedbackBar: android.widget.ProgressBar
    private val feedbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var scrubStartPosition = 0L
    private var scrubPreviewPosition = 0L
    private var gestureStartX = 0f

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
            setBackgroundColor(palette.background)
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 8, 18, 8)
        }
        val backTitle = TextView(this).apply {
            text = "‹    $title"
            textSize = 16f
            setTextColor(palette.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, 10, 8, 10)
            setOnClickListener { finish() }
        }
        heading.addView(backTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val speedButton = TextView(this).apply {
            text = "1.0×"
            textSize = 14f
            setTextColor(palette.accent)
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
        val subtitleButton = TextView(this).apply {
            text = "CC"
            textSize = 14f
            setTextColor(palette.accent)
            setPadding(14, 10, 0, 10)
            contentDescription = "Subtitle customization"
            setOnClickListener { showSubtitleCustomization() }
        }
        heading.addView(subtitleButton)
        val fitButton = TextView(this).apply {
            text = "FIT"
            textSize = 12f
            setTextColor(palette.text)
            setPadding(12, 10, 4, 10)
            contentDescription = "Change video aspect ratio"
            setOnClickListener {
                resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                playerView.resizeMode = resizeModes[resizeModeIndex]
                text = resizeLabels[resizeModeIndex]
            }
        }
        heading.addView(fitButton)
        val rotateButton = TextView(this).apply {
            text = "↻"
            textSize = 22f
            setTextColor(palette.accent)
            setPadding(12, 4, 0, 4)
            contentDescription = "Rotate screen"
            setOnClickListener {
                requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
        }
        heading.addView(rotateButton)
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
                showGestureFeedback("${longPressSpeed.toInt()}× playing…", 100, true)
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
        val feedback = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpPlayer(20), dpPlayer(12), dpPlayer(20), dpPlayer(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCC101820.toInt())
                cornerRadius = dpPlayer(14).toFloat()
            }
            visibility = View.GONE
            isClickable = false
        }
        feedbackLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        feedbackBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 50
            progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF52606A.toInt())
        }
        feedback.addView(feedbackLabel)
        feedback.addView(feedbackBar, LinearLayout.LayoutParams(dpPlayer(150), dpPlayer(7)).apply { topMargin = dpPlayer(9) })
        playbackArea.addView(feedback, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(playbackArea, LinearLayout.LayoutParams(-1, 0, 1f))
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        gestureBrightness = currentBrightness()
        setContentView(root)
        playerView.subtitleView?.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.tag = event.rawY - view.translationY; true }
                MotionEvent.ACTION_MOVE -> {
                    val start = (view.tag as? Float) ?: event.rawY
                    view.translationY = (event.rawY - start).coerceIn(-playerView.height * .45f, playerView.height * .45f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        updateSystemBars()

        val prefs = getSharedPreferences("gleeshplayer_history", MODE_PRIVATE)
        val resumeAt = prefs.getLong("position_$videoKey", 0L).coerceAtLeast(0L)
        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.setMediaItem(MediaItem.fromUri(uri), resumeAt)
            it.prepare()
            applySubtitleStyle(
                if (subtitleRemembered()) subtitlePrefs().getFloat("subtitle_size", 22f) else 22f,
                if (subtitleRemembered()) subtitlePrefs().getInt("subtitle_color", android.graphics.Color.WHITE) else android.graphics.Color.WHITE,
                if (subtitleRemembered()) subtitlePrefs().getInt("subtitle_position", 18) else 18,
                false
            )
            it.play()
        }
    }


    private fun attachExternalSubtitle(uri: Uri) {
        val displayName = queryDisplayName(uri)
        if (!displayName.endsWith(".srt", ignoreCase = true)) {
            android.widget.Toast.makeText(this, "Please choose an .srt subtitle file", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Some document providers grant temporary access only; the current playback can still use it.
        }
        val currentPlayer = player ?: return
        val currentPosition = currentPlayer.currentPosition.coerceAtLeast(0L)
        val wasPlaying = currentPlayer.isPlaying || currentPlayer.playWhenReady
        val videoUri = intent.data ?: return
        val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("und")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val updatedItem = MediaItem.Builder()
            .setUri(videoUri)
            .setSubtitleConfigurations(listOf(subtitle))
            .build()
        externalSubtitleUri = uri
        currentPlayer.setMediaItem(updatedItem, currentPosition)
        currentPlayer.prepare()
        currentPlayer.playWhenReady = wasPlaying
        android.widget.Toast.makeText(this, "Subtitle loaded: $displayName", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "subtitle"
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index) ?: name
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return name
    }

    private fun showEmbeddedSubtitleTracks() {
        val currentPlayer = player ?: return
        val choices = mutableListOf("Off")
        val selections = mutableListOf<Pair<androidx.media3.common.TrackGroup, Int>?> (null)
        currentPlayer.currentTracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (index in 0 until group.length) {
                    if (!group.isTrackSupported(index)) continue
                    val format = group.getTrackFormat(index)
                    val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
                    val label = format.label?.takeIf { it.isNotBlank() }
                    val description = label ?: language ?: ("Subtitle " + selections.size)
                    choices.add(description)
                    selections.add(group.mediaTrackGroup to index)
                }
            }
        }
        if (choices.size == 1) {
            AlertDialog.Builder(this).setTitle("Embedded subtitles")
                .setMessage("No embedded subtitle tracks were found in this video.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Embedded subtitles")
            .setItems(choices.toTypedArray()) { _, which ->
                val builder = currentPlayer.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                if (which == 0) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val selected = selections[which] ?: return@setItems
                    builder.addOverride(androidx.media3.common.TrackSelectionOverride(selected.first, listOf(selected.second)))
                }
                currentPlayer.trackSelectionParameters = builder.build()
            }.show()
    }

    private fun subtitlePrefs() = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)

    private fun subtitleRemembered(): Boolean = subtitlePrefs().getBoolean("remember_subtitles", true)

    private fun applySubtitleStyle(size: Float, color: Int, position: Int, save: Boolean) {
        val subtitleView = playerView.subtitleView ?: return
        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        subtitleView.setStyle(CaptionStyleCompat(color, android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK, Typeface.DEFAULT_BOLD))
        subtitleView.setBottomPaddingFraction(0.02f + (position.coerceIn(0, 100) / 100f) * 0.33f)
        if (save && subtitleRemembered()) {
            subtitlePrefs().edit().putFloat("subtitle_size", size).putInt("subtitle_color", color)
                .putInt("subtitle_position", position).apply()
        }
    }

    private fun showSubtitleCustomization() {
        val prefs = subtitlePrefs()
        val remembered = subtitleRemembered()
        var currentSize = if (remembered) prefs.getFloat("subtitle_size", 22f) else 22f
        var currentColor = if (remembered) prefs.getInt("subtitle_color", android.graphics.Color.WHITE) else android.graphics.Color.WHITE
        var currentPosition = if (remembered) prefs.getInt("subtitle_position", 18) else 18
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpPlayer(22), dpPlayer(8), dpPlayer(22), dpPlayer(4)) }
        fun caption(text: String) {
            panel.addView(TextView(this).apply { this.text = text; textSize = 13f; setTextColor(palette.muted); setPadding(0, dpPlayer(10), 0, dpPlayer(4)) })
        }
        fun slider(title: String, initial: Int, maxValue: Int, changed: (Int) -> Unit) {
            caption(title)
            panel.addView(SeekBar(this).apply {
                max = maxValue
                progress = initial.coerceIn(0, maxValue)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) changed(value) }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            })
        }
        val trackActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        trackActions.addView(Button(this).apply {
            text = "Choose embedded subtitles"
            setOnClickListener { showEmbeddedSubtitleTracks() }
        }, LinearLayout.LayoutParams(-1, dpPlayer(46)).apply { bottomMargin = dpPlayer(6) })
        trackActions.addView(Button(this).apply {
            text = "Load external .srt file"
            setOnClickListener { subtitlePicker.launch(arrayOf("*/*")) }
        }, LinearLayout.LayoutParams(-1, dpPlayer(46)))
        panel.addView(trackActions)
        caption("Subtitle appearance")
        slider("Subtitle size", ((currentSize - 12f) / 1.5f).toInt(), 16) { value ->
            currentSize = 12f + value * 1.5f
            applySubtitleStyle(currentSize, currentColor, currentPosition, true)
        }
        caption("Subtitle color")
        val colors = listOf("White" to android.graphics.Color.WHITE, "Yellow" to 0xFFFFFF00.toInt(), "Cyan" to 0xFF00E5FF.toInt(), "Green" to 0xFF20D66B.toInt())
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        colors.forEach { (name, value) ->
            colorRow.addView(Button(this).apply {
                text = name
                textSize = 10f
                setTextColor(if (value == android.graphics.Color.WHITE || value == 0xFFFFFF00.toInt()) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(value)
                setOnClickListener { currentColor = value; applySubtitleStyle(currentSize, currentColor, currentPosition, true) }
            }, LinearLayout.LayoutParams(0, dpPlayer(42), 1f).apply { marginEnd = dpPlayer(4) })
        }
        panel.addView(colorRow)
        slider("Vertical position (higher to lower)", currentPosition, 100) { value ->
            currentPosition = value
            applySubtitleStyle(currentSize, currentColor, currentPosition, true)
        }
        AlertDialog.Builder(this).setTitle("Subtitle customization").setView(panel).setPositiveButton("Done", null).show()
    }

    private fun dpPlayer(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun handlePlaybackGesture(event: MotionEvent): Boolean {
        val width = gestureLayer.width.coerceAtLeast(1)
        val height = gestureLayer.height.coerceAtLeast(1)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastGestureX = event.x
                lastGestureY = event.y
                gestureStartX = event.x
                scrubStartPosition = player?.currentPosition ?: 0L
                scrubPreviewPosition = scrubStartPosition
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
                            scrubPreviewPosition = (scrubStartPosition + ((event.x - gestureStartX) * 55f).toLong())
                                .coerceIn(0L, duration)
                            showGestureFeedback("SEEK  ${formatGestureTime(scrubPreviewPosition)} / ${formatGestureTime(duration)}",
                                if (duration > 0) (scrubPreviewPosition * 100 / duration).toInt() else 0, true)
                        }
                    }
                    2 -> {
                        gestureBrightness = (gestureBrightness - dy / height).coerceIn(0.05f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = gestureBrightness }
                        showGestureFeedback("BRIGHTNESS  ${(gestureBrightness * 100).toInt()}%", (gestureBrightness * 100).toInt(), true)
                    }
                    3 -> {
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        gestureVolume = (gestureVolume - dy / height).coerceIn(0f, 1f)
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            (gestureVolume * max).toInt().coerceIn(0, max),
                            0
                        )
                        showGestureFeedback("VOLUME  ${(gestureVolume * 100).toInt()}%", (gestureVolume * 100).toInt(), true)
                    }
                }
                lastGestureX = event.x
                lastGestureY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val completedMode = gestureMode
                if (completedMode == 1 && event.actionMasked == MotionEvent.ACTION_UP) {
                    player?.seekTo(scrubPreviewPosition)
                }
                gestureMode = 0
                if (longPressSpeedActive) {
                    player?.setPlaybackSpeed(playbackSpeeds[playbackSpeedIndex])
                    longPressSpeedActive = false
                }
                if (completedMode != 0 || longPressSpeedActive) hideGestureFeedbackSoon()
                return true
            }
        }
        return true
    }

    private fun showGestureFeedback(label: String, progress: Int, keepVisible: Boolean) {
        if (!::feedbackLabel.isInitialized) return
        feedbackLabel.text = label
        feedbackBar.progress = progress.coerceIn(0, 100)
        (feedbackLabel.parent as? View)?.visibility = View.VISIBLE
        feedbackHandler.removeCallbacksAndMessages(null)
        if (!keepVisible) feedbackHandler.postDelayed({ (feedbackLabel.parent as? View)?.visibility = View.GONE }, 1100L)
    }

    private fun hideGestureFeedbackSoon() {
        feedbackHandler.removeCallbacksAndMessages(null)
        feedbackHandler.postDelayed({ (feedbackLabel.parent as? View)?.visibility = View.GONE }, 650L)
    }

    private fun formatGestureTime(position: Long): String {
        val seconds = position / 1000L
        return String.format("%d:%02d", seconds / 60L, seconds % 60L)
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
