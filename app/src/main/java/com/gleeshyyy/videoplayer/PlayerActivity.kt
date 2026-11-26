package com.gleeshyyy.videoplayer

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.database.Cursor
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.content.Context
import android.util.TypedValue
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray

class PlayerActivity : AppCompatActivity() {
    private val palette get() = ThemeManager.palette(this)
    private var player: ExoPlayer? = null
    private var externalSubtitleUri: Uri? = null
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { attachExternalSubtitle(it) }
    }
    private var externalSubtitleName: String? = null
    private lateinit var subtitleFileLabel: TextView
    private lateinit var progressSeekBar: SeekBar
    private lateinit var currentTimeLabel: TextView
    private lateinit var durationTimeLabel: TextView
    private var showingRemainingTime = false
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            val current = player
            if (current != null && ::progressSeekBar.isInitialized) {
                val duration = current.duration.takeIf { it > 0L } ?: 0L
                val position = current.currentPosition.coerceAtLeast(0L).coerceAtMost(duration)
                if (!progressSeekBar.isPressed && duration > 0L) progressSeekBar.progress = (position * 1000L / duration).toInt()
                currentTimeLabel.text = formatClock(position)
                durationTimeLabel.text = if (showingRemainingTime) "-${formatClock((duration - position).coerceAtLeast(0L))}" else formatClock(duration)
            }
            progressHandler.removeCallbacks(this)
            progressHandler.postDelayed(this, 500L)
        }
    }
    private lateinit var playerView: PlayerView
    private lateinit var playbackArea: FrameLayout
    private lateinit var captionLabel: TextView
    private lateinit var controlsBar: LinearLayout
    private lateinit var playPauseButton: TextView
    private lateinit var orientationIcon: PhoneOrientationIcon
    private lateinit var playerHeader: LinearLayout
    private lateinit var playbackContainer: LinearLayout
    private lateinit var bottomControls: LinearLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var resumeBanner: LinearLayout
    private lateinit var subtitleDock: FrameLayout
    private lateinit var endModeButton: TextView
    private lateinit var landscapeHeader: LinearLayout
    private var playlistUris: ArrayList<Uri> = arrayListOf()
    private var playlistIndex = 0
    private var resumePosition = 0L
    private var resumePromptVisible = false
    private val controlsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { if (isLandscapeNow()) { bottomControls.visibility = View.GONE; landscapeHeader.visibility = View.GONE } }
    private var videoIsLandscape = false
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

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.background)
        }
        playerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPlayer(16), dpPlayer(7), dpPlayer(16), dpPlayer(8))
            setBackgroundColor(palette.background)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backButton = TextView(this).apply {
            text = "‹"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(palette.text)
            contentDescription = "Back to video library"
            setOnClickListener { finish() }
        }
        titleRow.addView(backButton, LinearLayout.LayoutParams(dpPlayer(46), dpPlayer(50)))
        val videoTitle = TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(palette.text)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpPlayer(5), dpPlayer(3), 0, dpPlayer(3))
        }
        titleRow.addView(videoTitle, LinearLayout.LayoutParams(0, -2, 1f))
        playerHeader.addView(titleRow, LinearLayout.LayoutParams(-1, -2))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpPlayer(38), dpPlayer(4), 0, dpPlayer(2))
        }
        orientationIcon = PhoneOrientationIcon(this).apply {
            setLandscape(videoIsLandscape)
            contentDescription = "Switch screen orientation"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
        }
        actionRow.addView(orientationIcon, LinearLayout.LayoutParams(dpPlayer(44), dpPlayer(42)))
        val subtitleButton = TextView(this).apply {
            text = "CC"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
            contentDescription = "Subtitle customization"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(palette.raised)
                cornerRadius = dpPlayer(8).toFloat()
                setStroke(dpPlayer(1), palette.muted)
            }
            setOnClickListener { showSubtitleCustomization() }
        }
        actionRow.addView(subtitleButton, LinearLayout.LayoutParams(dpPlayer(46), dpPlayer(36)).apply {
            marginStart = dpPlayer(12)
        })
        val fitButton = TextView(this).apply {
            text = "FIT"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(palette.text)
            contentDescription = "Change video aspect ratio"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(palette.raised)
                cornerRadius = dpPlayer(8).toFloat()
            }
            setOnClickListener {
                resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                playerView.resizeMode = resizeModes[resizeModeIndex]
                text = resizeLabels[resizeModeIndex]
            }
        }
        actionRow.addView(fitButton, LinearLayout.LayoutParams(dpPlayer(46), dpPlayer(36)).apply {
            marginStart = dpPlayer(12)
        })
        playerHeader.addView(actionRow, LinearLayout.LayoutParams(-1, -2))
        rootLayout.addView(playerHeader, LinearLayout.LayoutParams(-1, -2))
        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowRewindButton(false)
            setShowFastForwardButton(false)
            setBackgroundColor(0xFF000000.toInt())
        }
        playbackArea = FrameLayout(this)
        playbackArea.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLandscapeNow()) {
                    val show = bottomControls.visibility != View.VISIBLE || landscapeHeader.visibility != View.VISIBLE
                    bottomControls.visibility = if (show) View.VISIBLE else View.GONE
                    landscapeHeader.visibility = if (show) View.VISIBLE else View.GONE
                    if (show) scheduleControlsHide() else controlsHandler.removeCallbacks(hideControlsRunnable)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val zone = e.x / gestureLayer.width.coerceAtLeast(1).toFloat()
                when {
                    zone < 1f / 3f -> {
                        val current = player?.currentPosition ?: 0L
                        val duration = player?.duration ?: 0L
                        if (duration > 0L) {
                            val target = (current - 10_000L).coerceIn(0L, duration)
                            val moved = current - target
                            player?.seekTo(target)
                            if (moved > 0L) showGestureFeedback("−${(moved / 1000L).coerceAtLeast(1L)} seconds", 0, false)
                        }
                    }
                    zone > 2f / 3f -> {
                        val current = player?.currentPosition ?: 0L
                        val duration = player?.duration ?: 0L
                        if (duration > 0L) {
                            val target = (current + 10_000L).coerceIn(0L, duration)
                            val moved = target - current
                            player?.seekTo(target)
                            if (moved > 0L) showGestureFeedback("+${(moved / 1000L).coerceAtLeast(1L)} seconds", 100, false)
                        }
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
            setOnTouchListener { _, event -> handlePlaybackGesture(event) }
        }
        playbackArea.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        landscapeHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpPlayer(10), dpPlayer(3), dpPlayer(16), dpPlayer(3))
            setBackgroundColor(0x55000000)
            visibility = View.GONE
        }
        landscapeHeader.addView(TextView(this).apply {
            text = "‹"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            contentDescription = "Back to video library"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dpPlayer(48), dpPlayer(48)))
        landscapeHeader.addView(TextView(this).apply {
            text = title
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val landscapeActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        landscapeActions.addView(PhoneOrientationIcon(this).apply {
            setLandscape(videoIsLandscape)
            contentDescription = "Switch screen orientation"
            setOnClickListener {
                requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }, LinearLayout.LayoutParams(dpPlayer(42), dpPlayer(42)))
        landscapeActions.addView(TextView(this).apply {
            text = "CC"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE)
            contentDescription = "Subtitle customization"
            setOnClickListener { showSubtitleCustomization() }
        }, LinearLayout.LayoutParams(dpPlayer(42), dpPlayer(42)))
        landscapeActions.addView(TextView(this).apply {
            text = "FIT"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE)
            contentDescription = "Change video aspect ratio"
            setOnClickListener {
                resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                playerView.resizeMode = resizeModes[resizeModeIndex]
                text = resizeLabels[resizeModeIndex]
            }
        }, LinearLayout.LayoutParams(dpPlayer(42), dpPlayer(42)))
        landscapeHeader.addView(landscapeActions)
        playbackArea.addView(landscapeHeader, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
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
        playbackContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        playbackContainer.addView(playbackArea, LinearLayout.LayoutParams(-1, 0, 1f))
        resumeBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpPlayer(12), dpPlayer(3), dpPlayer(8), dpPlayer(3))
            setBackgroundColor(palette.raised)
            visibility = View.GONE
        }
        resumeBanner.addView(TextView(this).apply {
            text = "Continued from where you stopped"
            textSize = 12f
            setTextColor(palette.text)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        resumeBanner.addView(TextView(this).apply {
            text = "START"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            setPadding(dpPlayer(10), dpPlayer(8), dpPlayer(10), dpPlayer(8))
            setOnClickListener { player?.seekTo(0L); resumePromptVisible = false; resumeBanner.visibility = View.GONE }
        })
        resumeBanner.addView(TextView(this).apply {
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
            setPadding(dpPlayer(10), dpPlayer(3), dpPlayer(5), dpPlayer(3))
            contentDescription = "Dismiss resume message"
            setOnClickListener { resumePromptVisible = false; resumeBanner.visibility = View.GONE }
        })
        playbackContainer.addView(resumeBanner, LinearLayout.LayoutParams(-1, -2))
        subtitleDock = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(palette.background)
        }
        captionLabel = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(dpPlayer(3).toFloat(), 0f, 1f, android.graphics.Color.BLACK)
            visibility = View.GONE
            setPadding(dpPlayer(12), dpPlayer(6), dpPlayer(12), dpPlayer(6))
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.tag = floatArrayOf(event.rawY, view.translationY)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val values = view.tag as? FloatArray
                        if (values != null) {
                            view.translationY = (values[1] + event.rawY - values[0]).coerceIn(-playbackArea.height.toFloat(), 0f)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        subtitleDock.addView(captionLabel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        playbackContainer.addView(subtitleDock, LinearLayout.LayoutParams(-1, dpPlayer(62)))
        rootLayout.addView(playbackContainer, LinearLayout.LayoutParams(-1, 0, 1f))

        controlsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpPlayer(18), dpPlayer(5), dpPlayer(18), dpPlayer(5))
            setBackgroundColor(palette.surface)
        }
        fun controlButton(symbol: String, description: String, action: () -> Unit): TextView =
            TextView(this).apply {
                text = symbol
                contentDescription = description
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(palette.text)
                setPadding(dpPlayer(12), dpPlayer(8), dpPlayer(12), dpPlayer(8))
                setOnClickListener { action() }
            }
        controlsBar.addView(controlButton("⏮", "Previous video") {
            player?.let { current -> if (current.currentMediaItemIndex > 0) current.seekTo(current.currentMediaItemIndex - 1, 0L) else current.seekTo(0L) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        playPauseButton = controlButton("Ⅱ", "Play or pause") {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        controlsBar.addView(playPauseButton, LinearLayout.LayoutParams(0, -2, 1f))
        controlsBar.addView(controlButton("⏭", "Next video") {
            player?.seekToNextMediaItem()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        endModeButton = controlButton("🔁", "Play next video when finished") { showEndBehaviorOptions() }
        endModeButton.textSize = 23f
        endModeButton.setTextColor(palette.accent)
        controlsBar.addView(endModeButton, LinearLayout.LayoutParams(0, -2, 1f))
        val timeline = LinearLayout(this).apply {
            tag = "timeline_controls"
            orientation = LinearLayout.VERTICAL
            setPadding(dpPlayer(14), dpPlayer(2), dpPlayer(14), dpPlayer(2))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        currentTimeLabel = TextView(this).apply {
            text = "0:00"; textSize = 11f; setTextColor(palette.text)
        }
        durationTimeLabel = TextView(this).apply {
            text = "0:00"; textSize = 11f; setTextColor(palette.text)
            gravity = Gravity.END
            contentDescription = "Tap to switch between total and remaining time"
            setOnClickListener { showingRemainingTime = !showingRemainingTime; progressUpdater.run() }
        }
        timeRow.addView(currentTimeLabel, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(durationTimeLabel)
        timeline.addView(timeRow)
        progressSeekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = player?.duration ?: 0L
                        if (duration > 0L) currentTimeLabel.text = formatClock(duration * value / 1000L)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val duration = player?.duration ?: 0L
                    if (duration > 0L) player?.seekTo(duration * (bar?.progress ?: 0) / 1000L)
                }
            })
        }
        timeline.addView(progressSeekBar, LinearLayout.LayoutParams(-1, dpPlayer(25)))
        bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addView(timeline, LinearLayout.LayoutParams(-1, -2))
            addView(controlsBar, LinearLayout.LayoutParams(-1, -2))
        }
        rootLayout.addView(bottomControls, LinearLayout.LayoutParams(-1, -2))
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        gestureBrightness = currentBrightness()
        setContentView(rootLayout)
        playerView.subtitleView?.visibility = View.GONE
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        updateSystemBars()
        applyOrientationLayout()

        val prefs = getSharedPreferences("gleeshplayer_history", MODE_PRIVATE)
        resumePosition = prefs.getLong("position_$videoKey", 0L).coerceAtLeast(0L)
        playlistUris = intent.getParcelableArrayListExtra<Uri>("playlist_uris")?.takeIf { it.isNotEmpty() } ?: arrayListOf(uri)
        playlistIndex = intent.getIntExtra("playlist_index", 0).coerceIn(0, playlistUris.lastIndex)
        resumePromptVisible = resumePosition > 1000L
        if (resumePromptVisible && !isLandscapeNow()) resumeBanner.visibility = View.VISIBLE
        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.addListener(object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    val line = cueGroup.cues.mapNotNull { cue -> cue.text?.toString()?.takeIf { value -> value.isNotBlank() } }
                        .joinToString("\n")
                    captionLabel.text = line
                    captionLabel.visibility = if (line.isBlank()) View.GONE else View.VISIBLE
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playPauseButton.text = if (isPlaying) "Ⅱ" else "▶"
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    val rotated = videoSize.unappliedRotationDegrees % 180 != 0
                    videoIsLandscape = if (rotated) videoSize.height > videoSize.width else videoSize.width > videoSize.height
                    if (::orientationIcon.isInitialized) orientationIcon.setLandscape(videoIsLandscape)
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val activeUri = mediaItem?.localConfiguration?.uri ?: return
                    videoKey = activeUri.toString()
                    playlistIndex = playlistUris.indexOf(activeUri).takeIf { it >= 0 } ?: playlistIndex
                    remember(videoKey)
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                        resumePromptVisible = false
                        resumeBanner.visibility = View.GONE
                    }
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    val mode = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE).getInt("playback_end_behavior", 0)
                    if (mode == 2 && reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) finish()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) finish()
                }
            })
            it.setMediaItems(playlistUris.map { itemUri -> MediaItem.fromUri(itemUri) }, playlistIndex, resumePosition)
            it.prepare()
            applyEndBehavior(false)
            progressUpdater.run()
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
        externalSubtitleName = displayName
        if (::subtitleFileLabel.isInitialized) {
            subtitleFileLabel.text = "Loaded: $displayName"
            subtitleFileLabel.setTextColor(palette.accent)
        }
        currentPlayer.setMediaItem(updatedItem, currentPosition)
        currentPlayer.prepare()
        currentPlayer.playWhenReady = wasPlaying
        android.widget.Toast.makeText(this, "Subtitle loaded: $displayName", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun launchSubtitlePicker() {
        val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-subrip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-subrip", "text/plain"))
            initialSubtitleFolderUri()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        subtitlePicker.launch(pickerIntent)
    }

    private fun initialSubtitleFolderUri(): Uri? {
        val videoUri = intent.data ?: return null
        return try {
            contentResolver.query(videoUri, arrayOf(MediaStore.Video.Media.RELATIVE_PATH), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                if (index < 0) return@use null
                val relative = cursor.getString(index)?.trim('/') ?: return@use null
                if (relative.isBlank()) return@use null
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:$relative"
                )
            }
        } catch (_: Exception) { null }
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
        captionLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        captionLabel.setTextColor(color)
        playbackArea.post {
            captionLabel.translationY = -(position.coerceIn(0, 100) / 100f) * playbackArea.height.toFloat()
        }
        if (save && subtitleRemembered()) {
            subtitlePrefs().edit().putFloat("subtitle_size", size).putInt("subtitle_color", color)
                .putInt("subtitle_position", position).apply()
        }
    }

    private fun showSubtitleCustomization() {
        if (playbackArea.findViewWithTag<View>("subtitle_customization_sheet") != null) return
        val prefs = subtitlePrefs()
        val remembered = subtitleRemembered()
        var currentSize = if (remembered) prefs.getFloat("subtitle_size", 22f) else 22f
        var currentColor = if (remembered) prefs.getInt("subtitle_color", android.graphics.Color.WHITE) else android.graphics.Color.WHITE
        var currentPosition = if (remembered) prefs.getInt("subtitle_position", 18) else 18

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPlayer(18), dpPlayer(10), dpPlayer(18), dpPlayer(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (palette.isDark) 0xB5121A20.toInt() else 0xB5F3F6F7.toInt())
                cornerRadius = dpPlayer(18).toFloat()
            }
            tag = "subtitle_customization_sheet"
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Subtitle customization"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "DONE"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            setPadding(dpPlayer(12), dpPlayer(8), dpPlayer(4), dpPlayer(8))
            setOnClickListener { (panel.parent as? View)?.let { playbackArea.removeView(it) } }
        })
        panel.addView(header)
        fun caption(text: String) {
            panel.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(palette.muted)
                setPadding(0, dpPlayer(8), 0, dpPlayer(2))
            })
        }
        fun slider(title: String, initial: Int, maxValue: Int, changed: (Int) -> Unit) {
            caption(title)
            panel.addView(SeekBar(this).apply {
                max = maxValue
                progress = initial.coerceIn(0, maxValue)
                progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) changed(value) }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            })
        }
        fun actionButton(title: String, action: () -> Unit) {
            panel.addView(TextView(this).apply {
                text = title
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(palette.text)
                setPadding(dpPlayer(10), dpPlayer(9), dpPlayer(10), dpPlayer(9))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF27343D.toInt())
                    cornerRadius = dpPlayer(8).toFloat()
                }
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpPlayer(5) })
        }
        actionButton("Choose embedded subtitles") { showEmbeddedSubtitleTracks() }
        actionButton("Load external .srt file") { launchSubtitlePicker() }
        subtitleFileLabel = TextView(this).apply {
            text = externalSubtitleName?.let { "Loaded: $it" } ?: "No external subtitle file loaded"
            textSize = 11f
            setTextColor(if (externalSubtitleName == null) palette.muted else palette.accent)
            setPadding(0, dpPlayer(2), 0, dpPlayer(5))
        }
        panel.addView(subtitleFileLabel)
        caption("Subtitle appearance")
        slider("Size", ((currentSize - 12f) / 1.5f).toInt(), 16) { value ->
            currentSize = 12f + value * 1.5f
            applySubtitleStyle(currentSize, currentColor, currentPosition, true)
        }
        caption("Color")
        val colors = listOf("White" to android.graphics.Color.WHITE, "Yellow" to 0xFFFFFF00.toInt(), "Cyan" to 0xFF00E5FF.toInt(), "Green" to 0xFF20D66B.toInt())
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        colors.forEach { (name, value) ->
            colorRow.addView(TextView(this).apply {
                text = name
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (value == android.graphics.Color.WHITE || value == 0xFFFFFF00.toInt()) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(value)
                    cornerRadius = dpPlayer(7).toFloat()
                }
                setOnClickListener {
                    currentColor = value
                    applySubtitleStyle(currentSize, currentColor, currentPosition, true)
                }
            }, LinearLayout.LayoutParams(0, dpPlayer(38), 1f).apply { marginEnd = dpPlayer(5) })
        }
        panel.addView(colorRow)
        slider("Position (drag subtitle label below the video to move it)", currentPosition, 100) { value ->
            currentPosition = value
            applySubtitleStyle(currentSize, currentColor, currentPosition, true)
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            addView(panel)
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }
        playbackArea.addView(scroll, FrameLayout.LayoutParams(-1, dpPlayer(350), Gravity.BOTTOM))
        scroll.tag = "subtitle_customization_scroll"
        playbackArea.bringChildToFront(scroll)
    }

    private class PhoneOrientationIcon(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var landscape = false

        fun setLandscape(value: Boolean) {
            if (landscape != value) {
                landscape = value
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            paint.color = ThemeManager.palette(context).accent
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f * density
            val bodyWidth = (if (landscape) 29f else 19f) * density
            val bodyHeight = (if (landscape) 19f else 29f) * density
            val left = (width - bodyWidth) / 2f
            val top = (height - bodyHeight) / 2f
            val body = RectF(left, top, left + bodyWidth, top + bodyHeight)
            canvas.drawRoundRect(body, 3.5f * density, 3.5f * density, paint)
            paint.strokeWidth = 1.2f * density
            val inset = 3f * density
            canvas.drawRoundRect(
                RectF(body.left + inset, body.top + inset, body.right - inset, body.bottom - inset),
                1.5f * density, 1.5f * density, paint
            )
            paint.style = Paint.Style.FILL
            if (landscape) {
                canvas.drawCircle(body.right - 1.7f * density, body.centerY(), 0.8f * density, paint)
            } else {
                canvas.drawCircle(body.centerX(), body.bottom - 1.7f * density, 0.8f * density, paint)
            }
        }
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
                val wasLongPress = longPressSpeedActive
                if (completedMode == 1 && event.actionMasked == MotionEvent.ACTION_UP) {
                    player?.seekTo(scrubPreviewPosition)
                }
                gestureMode = 0
                if (longPressSpeedActive) {
                    player?.setPlaybackSpeed(playbackSpeeds[playbackSpeedIndex])
                    longPressSpeedActive = false
                }
                if (completedMode != 0 || wasLongPress) hideGestureFeedbackSoon()
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

    private fun formatGestureTime(position: Long): String = formatClock(position)

    private fun formatClock(position: Long): String {
        val totalSeconds = (position / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) String.format("%d:%02d:%02d", hours, minutes, seconds)
            else String.format("%d:%02d", minutes, seconds)
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
        applyOrientationLayout()
    }

    private fun isLandscapeNow(): Boolean = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun updateSystemBars() {
        window.decorView.systemUiVisibility = if (isLandscapeNow()) {
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun applyOrientationLayout() {
        if (!::rootLayout.isInitialized || !::bottomControls.isInitialized) return
        val landscape = isLandscapeNow()
        (bottomControls.parent as? android.view.ViewGroup)?.removeView(bottomControls)
        if (landscape) {
            playerHeader.visibility = View.GONE
            resumeBanner.visibility = View.GONE
            subtitleDock.visibility = View.GONE
            playbackArea.addView(bottomControls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            bottomControls.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            controlsBar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            bottomControls.findViewWithTag<View>("timeline_controls")?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            landscapeHeader.visibility = View.VISIBLE
            for (i in 0 until controlsBar.childCount) (controlsBar.getChildAt(i) as? TextView)?.setTextColor(android.graphics.Color.WHITE)
            endModeButton.setTextColor(0xFF65E58B.toInt())
            bottomControls.visibility = View.VISIBLE
            landscapeHeader.visibility = View.VISIBLE
            scheduleControlsHide()
        } else {
            controlsHandler.removeCallbacks(hideControlsRunnable)
            playbackArea.removeView(bottomControls)
            landscapeHeader.visibility = View.GONE
            playerHeader.visibility = View.VISIBLE
            resumeBanner.visibility = if (resumePromptVisible) View.VISIBLE else View.GONE
            subtitleDock.visibility = View.VISIBLE
            bottomControls.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            controlsBar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            bottomControls.findViewWithTag<View>("timeline_controls")?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            for (i in 0 until controlsBar.childCount) (controlsBar.getChildAt(i) as? TextView)?.setTextColor(palette.text)
            endModeButton.setTextColor(palette.accent)
            rootLayout.addView(bottomControls, LinearLayout.LayoutParams(-1, -2))
            bottomControls.visibility = View.VISIBLE
        }
    }

    private fun scheduleControlsHide() {
        controlsHandler.removeCallbacks(hideControlsRunnable)
        if (isLandscapeNow()) controlsHandler.postDelayed(hideControlsRunnable, 5000L)
    }

    private fun showEndBehaviorOptions() {
        val options = arrayOf("Play next video", "Repeat current video", "Stop and return to list")
        val prefs = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
        val selected = prefs.getInt("playback_end_behavior", 0).coerceIn(0, 2)
        AlertDialog.Builder(this).setTitle("When this video ends")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                prefs.edit().putInt("playback_end_behavior", which).apply()
                applyEndBehavior(true)
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun applyEndBehavior(reload: Boolean) {
        val current = player ?: return
        val mode = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE).getInt("playback_end_behavior", 0).coerceIn(0, 2)
        endModeButton.text = when (mode) { 1 -> "🔂"; 2 -> "🔁̸"; else -> "🔁" }
        endModeButton.contentDescription = when (mode) {
            1 -> "Repeat current video when finished"
            2 -> "Stop and return to list when finished"
            else -> "Play next video when finished"
        }
        current.repeatMode = if (mode == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (reload) {
            val position = current.currentPosition.coerceAtLeast(0L)
            val wasPlaying = current.isPlaying || current.playWhenReady
            current.setMediaItems(playlistUris.map { MediaItem.fromUri(it) }, playlistIndex, position)
            current.prepare()
            current.playWhenReady = wasPlaying
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
        controlsHandler.removeCallbacks(hideControlsRunnable)
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
        progressHandler.removeCallbacks(progressUpdater)
        controlsHandler.removeCallbacksAndMessages(null)
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        window.attributes = window.attributes.apply { screenBrightness = -1f }
        super.onDestroy()
    }
}
