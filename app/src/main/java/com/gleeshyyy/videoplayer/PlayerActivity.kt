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
    private var subtitleDelayMs = 0L
    private var subtitlePositionPercent = 18
    private var externalSubtitleDragStartY = 0f
    private var externalSubtitleDragStartPosition = 18f
    private var externalSubtitleDragging = false
    private var subtitleSizeSp = 22f
    private var subtitleColor = android.graphics.Color.WHITE
    private lateinit var portraitActionRow: LinearLayout
    private lateinit var landscapeActions: LinearLayout
    private var autoEmbeddedSubtitleApplied = false
    private var pendingSubtitleCue: Runnable? = null
    private fun subtitleKey(uri: Uri) = "external_subtitle_" + uri.toString()
    private fun embeddedSubtitleKey(uri: Uri) = "embedded_subtitles_" + uri.toString()
    private fun savedSubtitleUri(uri: Uri): Uri? = subtitlePrefs().getString(subtitleKey(uri), null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
    private fun mediaItemFor(uri: Uri): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)
        savedSubtitleUri(uri)?.let { subtitleUri ->
            builder.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("und")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            ))
        }
        return builder.build()
    }
    private lateinit var subtitleFileLabel: TextView
    private var currentSubtitleValueLabel: TextView? = null
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
    private val resizeModes = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_FIT)
    private val resizeLabels = listOf("FIT", "ZOOM", "FILL", "ORIGINAL")
    private var resizeModeIndex = 0
    private var nativeVideoWidth = 0
    private var nativeVideoHeight = 0
    private var selectedAspectRatio: Float? = null
    private var aspectModeName = "Fit"
    private var customWidthPercent = 100
    private var customHeightPercent = 100

    private fun saveAspectSettings() {
        val prefs = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
        prefs.edit()
            .putString("aspect_mode_name", aspectModeName)
            .putFloat("aspect_ratio_value", selectedAspectRatio ?: 0f)
            .putInt("aspect_custom_width", customWidthPercent)
            .putInt("aspect_custom_height", customHeightPercent)
            .apply()
        if (prefs.getBoolean("remember_aspect", true)) {
            prefs.edit().putString("last_aspect_name", aspectModeName).apply()
        }
    }

    private fun applyAspectMode() {
        if (!::playerView.isInitialized || !::playbackArea.isInitialized) return
        val areaWidth = playbackArea.width
        val areaHeight = playbackArea.height
        when (aspectModeName) {
            "Fit" -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                playerView.layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            "Fill" -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                playerView.layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            "Original" -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                if (nativeVideoWidth > 0 && nativeVideoHeight > 0) {
                    val density = resources.displayMetrics.density
                    playerView.layoutParams = FrameLayout.LayoutParams(
                        (nativeVideoWidth / density).toInt().coerceAtLeast(1),
                        (nativeVideoHeight / density).toInt().coerceAtLeast(1),
                        Gravity.CENTER
                    )
                } else playerView.layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            "Stretch" -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                playerView.layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            else -> {
                val baseRatio = selectedAspectRatio
                if (baseRatio == null || areaWidth <= 0 || areaHeight <= 0) {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    playerView.layoutParams = FrameLayout.LayoutParams(-1, -1)
                } else {
                    val targetRatio = baseRatio * customWidthPercent.coerceIn(50, 200) / customHeightPercent.coerceIn(50, 200).toFloat()
                    val areaRatio = areaWidth.toFloat() / areaHeight.toFloat()
                    val targetWidth: Int
                    val targetHeight: Int
                    if (areaRatio > targetRatio) {
                        targetHeight = areaHeight
                        targetWidth = (areaHeight * targetRatio).toInt()
                    } else {
                        targetWidth = areaWidth
                        targetHeight = (areaWidth / targetRatio).toInt()
                    }
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                    playerView.layoutParams = FrameLayout.LayoutParams(
                        targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1), Gravity.CENTER
                    )
                }
            }
        }
        playerView.requestLayout()
    }

    private fun setAspectPreset(name: String, ratio: Float? = null) {
        aspectModeName = name
        selectedAspectRatio = ratio
        customWidthPercent = 100
        customHeightPercent = 100
        saveAspectSettings()
        applyAspectMode()
    }

    private fun handleAspectButtonTap() {
        val settings = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
        if (!settings.getBoolean("tap_aspect_directly", false)) {
            showAspectRatioMenu()
            return
        }
        val presets = listOf(
            "Fit" to null, "Fill" to null, "Original" to null, "Stretch" to null,
            "16:9" to 16f/9f, "4:3" to 4f/3f, "18:9" to 2f, "19.5:9" to 19.5f/9f,
            "20:9" to 20f/9f, "21:9" to 21f/9f, "1.85:1" to 1.85f,
            "2.21:1" to 2.21f, "2.35:1" to 2.35f, "2.39:1" to 2.39f
        )
        val next = (presets.indexOfFirst { it.first == aspectModeName } + 1).let { if (it <= 0) 0 else it % presets.size }
        setAspectPreset(presets[next].first, presets[next].second)
    }

    private fun showAspectRatioMenu() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val prefs = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dpPlayer(8), dpPlayer(8), dpPlayer(8), dpPlayer(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpPlayer(18).toFloat()
                setColor(0xD910171D.toInt())
                setStroke(dpPlayer(1), 0x55FFFFFF)
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPlayer(16), dpPlayer(12), dpPlayer(16), dpPlayer(14))
        }
        scroll.addView(panel)

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "▣   Aspect Ratio"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.WHITE)
        }, LinearLayout.LayoutParams(0, dpPlayer(42), 1f))
        titleRow.addView(TextView(this).apply {
            text = "×"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(0xCCFFFFFF.toInt())
            setOnClickListener { aspectDialog?.dismiss() }
        }, LinearLayout.LayoutParams(dpPlayer(34), dpPlayer(42)))
        panel.addView(titleRow)

        fun divider(top: Int = 8, bottom: Int = 8) {
            panel.addView(View(this).apply { setBackgroundColor(0x557F929B) },
                LinearLayout.LayoutParams(-1, dpPlayer(1)).apply {
                    topMargin = dpPlayer(top)
                    bottomMargin = dpPlayer(bottom)
                })
        }
        fun section(label: String) {
            panel.addView(TextView(this).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
                setTextColor(0xBFFFFFFF.toInt())
                setPadding(0, dpPlayer(9), 0, dpPlayer(5))
            })
        }
        fun option(label: String, ratio: Float? = null) {
            val selected = aspectModeName == label
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpPlayer(10), 0, dpPlayer(10), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpPlayer(7).toFloat()
                    setColor(if (selected) 0x5530C982 else android.graphics.Color.TRANSPARENT)
                }
                minimumHeight = dpPlayer(34)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    setAspectPreset(label, ratio)
                    aspectDialog?.dismiss()
                }
            }
            item.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xEFFFFFFF.toInt())
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) item.addView(TextView(this).apply {
                text = "✓"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.accent)
            })
            panel.addView(item, LinearLayout.LayoutParams(-1, dpPlayer(34)))
        }
        section("SCREEN")
        listOf("Fit" to null, "Fill" to null, "Original" to null, "Stretch" to null).forEach { option(it.first, it.second) }
        divider()
        section("STANDARD RATIOS")
        listOf(
            "16:9" to 16f / 9f, "4:3" to 4f / 3f, "18:9" to 2f,
            "19.5:9" to 19.5f / 9f, "20:9" to 20f / 9f, "21:9" to 21f / 9f
        ).forEach { option(it.first, it.second) }
        divider()
        section("CINEMA RATIOS")
        listOf("1.85:1" to 1.85f, "2.21:1" to 2.21f, "2.35:1" to 2.35f, "2.39:1" to 2.39f)
            .forEach { option(it.first, it.second) }

        divider(10, 6)
        var customExpanded = false
        val customContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val customHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpPlayer(2), dpPlayer(8), dpPlayer(2), dpPlayer(8))
            isClickable = true
            isFocusable = true
        }
        customHeader.addView(TextView(this).apply {
            text = "⚙   Custom Adjustment"
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val expandMark = TextView(this).apply {
            text = "›"
            textSize = 22f
            setTextColor(0xDDFFFFFF.toInt())
        }
        customHeader.addView(expandMark)
        customHeader.setOnClickListener {
            customExpanded = !customExpanded
            customContent.visibility = if (customExpanded) View.VISIBLE else View.GONE
            expandMark.text = if (customExpanded) "⌄" else "›"
        }
        panel.addView(customHeader)

        fun adjustment(label: String, initial: Int, onValue: (Int) -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val heading = TextView(this).apply {
                text = "$label  $initial%"
                textSize = 12f
                setTextColor(0xDDFFFFFF.toInt())
            }
            val seek = SeekBar(this).apply {
                max = 150
                progress = (initial - 50).coerceIn(0, 150)
                progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        val percent = value + 50
                        heading.text = "$label  $percent%"
                        onValue(percent)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            }
            row.addView(heading)
            row.addView(seek)
            customContent.addView(row)
        }
        adjustment("Width", customWidthPercent) {
            customWidthPercent = it
            if (selectedAspectRatio != null) { saveAspectSettings(); applyAspectMode() }
        }
        adjustment("Height", customHeightPercent) {
            customHeightPercent = it
            if (selectedAspectRatio != null) { saveAspectSettings(); applyAspectMode() }
        }
        customContent.addView(TextView(this).apply {
            text = "Reset"
            textSize = 12f
            gravity = Gravity.END
            setTextColor(palette.accent)
            setPadding(0, dpPlayer(4), 0, dpPlayer(8))
            setOnClickListener {
                customWidthPercent = 100
                customHeightPercent = 100
                saveAspectSettings()
                applyAspectMode()
                aspectDialog?.dismiss()
                showAspectRatioMenu()
            }
        })
        panel.addView(customContent)

        divider(6, 6)
        fun settingRow(label: String, key: String, defaultValue: Boolean) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpPlayer(38)
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(0xEFFFFFFF.toInt())
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val toggle = android.widget.Switch(this).apply {
                isChecked = prefs.getBoolean(key, defaultValue)
                buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(key, checked).apply()
                }
            }
            row.addView(toggle)
            panel.addView(row)
        }
        settingRow("Remember ratio for all videos", "remember_aspect", true)
        settingRow("Tap ratios to switch directly", "tap_aspect_directly", true)

        aspectDialog = AlertDialog.Builder(this).setView(scroll).create()
        aspectDialog?.setOnShowListener {
            val window = aspectDialog?.window ?: return@setOnShowListener
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attrs = window.attributes
            attrs.dimAmount = 0.08f
            window.attributes = attrs
            val width = if (landscape) dpPlayer(360) else (resources.displayMetrics.widthPixels - dpPlayer(32)).coerceAtLeast(dpPlayer(280))
            val height = if (landscape) -1 else (resources.displayMetrics.heightPixels * 0.86f).toInt()
            window.setLayout(width, height)
            window.setGravity(if (landscape) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER)
        }
        aspectDialog?.show()
        aspectDialog?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val width = if (landscape) dpPlayer(360) else (resources.displayMetrics.widthPixels - dpPlayer(32)).coerceAtLeast(dpPlayer(280))
        val height = if (landscape) -1 else (resources.displayMetrics.heightPixels * 0.86f).toInt()
        aspectDialog?.window?.setLayout(width, height)
        aspectDialog?.window?.setGravity(if (landscape) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER)
    }
    private var aspectDialog: AlertDialog? = null
    private lateinit var gestureLayer: View
    private lateinit var gestureDetector: GestureDetector
    private var gestureVolume = 0f
    private var gestureMode = 0
    private var lastGestureX = 0f
    private var lastGestureY = 0f
    private var gestureBrightness = 0.5f
    private var longPressSpeedActive = false
    private var longPressSpeed = 2.0f
    private var seekIntervalMs = 10_000L
    private val pendingSeekHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSeekTarget: Long? = null
    private var pendingSeekStart = 0L
    private var pendingSeekDirection = 0
    private val commitPendingSeek = Runnable {
        val target = pendingSeekTarget
        pendingSeekTarget = null
        pendingSeekDirection = 0
        if (target != null) player?.seekTo(target)
    }
    private var longPressVibrator: android.os.Vibrator? = null
    private lateinit var audioManager: AudioManager
    private lateinit var feedbackLabel: TextView
    private lateinit var feedbackBar: android.widget.ProgressBar
    private lateinit var seekFeedbackOverlay: SeekFeedbackOverlay
    private val feedbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var scrubStartPosition = 0L
    private var scrubPreviewPosition = 0L
    private var gestureStartX = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val title = intent.getStringExtra("title") ?: uri.lastPathSegment ?: "Video"
        videoKey = uri.toString()
        val videoSettings = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
        longPressSpeed = videoSettings.getFloat("long_press_speed", 2.0f)
        seekIntervalMs = videoSettings.getInt("seek_interval", 10).coerceIn(3, 60) * 1000L
        longPressVibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
        if (videoSettings.getBoolean("remember_orientation", true) && videoSettings.contains("last_orientation")) {
            requestedOrientation = if (videoSettings.getBoolean("last_orientation", false)) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
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

        portraitActionRow = LinearLayout(this).apply {
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
        portraitActionRow.addView(orientationIcon, LinearLayout.LayoutParams(dpPlayer(44), dpPlayer(42)))
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
        portraitActionRow.addView(subtitleButton, LinearLayout.LayoutParams(dpPlayer(46), dpPlayer(36)).apply {
            marginStart = dpPlayer(12)
        })
        val fitButton = TextView(this).apply {
            text = "RATIO"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(palette.text)
            contentDescription = "Change video aspect ratio"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(palette.raised)
                cornerRadius = dpPlayer(8).toFloat()
            }
            setOnClickListener { handleAspectButtonTap() }
            setOnLongClickListener { showAspectRatioMenu(); true }
        }
        portraitActionRow.addView(fitButton, LinearLayout.LayoutParams(dpPlayer(46), dpPlayer(36)).apply {
            marginStart = dpPlayer(12)
        })
        playerHeader.addView(portraitActionRow, LinearLayout.LayoutParams(-1, -2))
        rootLayout.addView(playerHeader, LinearLayout.LayoutParams(-1, -2))
        aspectModeName = if (videoSettings.getBoolean("remember_aspect", true))
            videoSettings.getString("last_aspect_name", "Fit") ?: "Fit" else "Fit"
        selectedAspectRatio = when (aspectModeName) {
            "16:9" -> 16f/9f; "4:3" -> 4f/3f; "18:9" -> 2f; "19.5:9" -> 19.5f/9f
            "20:9" -> 20f/9f; "21:9" -> 21f/9f; "1.85:1" -> 1.85f; "2.21:1" -> 2.21f
            "2.35:1" -> 2.35f; "2.39:1" -> 2.39f; else -> null
        }
        customWidthPercent = videoSettings.getInt("aspect_custom_width", 100).coerceIn(50, 200)
        customHeightPercent = videoSettings.getInt("aspect_custom_height", 100).coerceIn(50, 200)
        playerView = PlayerView(this).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowRewindButton(false)
            setShowFastForwardButton(false)
            setBackgroundColor(0xFF000000.toInt())
        }
        playbackArea = FrameLayout(this)
        playbackArea.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        playbackArea.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyAspectMode()
            if (::captionLabel.isInitialized) {
                val prefs = subtitlePrefs()
                applySubtitleStyle(subtitleSizeSp, subtitleColor, subtitlePositionPercent, false)
            }
        }
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
                    zone < 1f / 3f -> queueDoubleTapSeek(-1)
                    zone > 2f / 3f -> queueDoubleTapSeek(1)
                    else -> {
                        flushPendingSeek()
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                gestureLayer.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                val settings = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
                if (settings.getBoolean("long_press_vibration", true)) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 26) longPressVibrator?.vibrate(android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        else longPressVibrator?.vibrate(35)
                    } catch (_: Exception) { }
                }
                player?.setPlaybackSpeed(longPressSpeed)
                longPressSpeedActive = true
                showGestureFeedback("${longPressSpeed.toInt()}× playing…", 100, true)
            }
        })
        gestureLayer = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                handleExternalSubtitleDrag(event) || handlePlaybackGesture(event)
            }
        }
        playbackArea.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        seekFeedbackOverlay = SeekFeedbackOverlay(this).apply { visibility = View.GONE; isClickable = false; isFocusable = false }
        playbackArea.addView(seekFeedbackOverlay, FrameLayout.LayoutParams(-1, -1))
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
        landscapeActions = LinearLayout(this).apply {
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
            text = "RATIO"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE)
            contentDescription = "Change video aspect ratio"
            setOnClickListener { handleAspectButtonTap() }
            setOnLongClickListener { showAspectRatioMenu(); true }
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
                            view.translationY = (values[1] + event.rawY - values[0])
                                .coerceIn(-playbackArea.height.toFloat() * 0.85f, 0f)
                            val height = playbackArea.height.toFloat().coerceAtLeast(1f)
                            subtitlePositionPercent = ((-view.translationY / height) * 100f).toInt().coerceIn(0, 85)
                            if (subtitleRemembered()) {
                                subtitlePrefs().edit().putInt("subtitle_position", subtitlePositionPercent).apply()
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        playbackArea.addView(captionLabel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // Keep Media3's native subtitle renderer inside PlayerView.
        // Embedded tracks use the renderer's untouched defaults; only external SRT
        // captions receive the player's custom styling and drag interaction.
        playerView.subtitleView?.apply {
            visibility = View.VISIBLE
            setOnTouchListener { view, event ->
                if (externalSubtitleUri == null) return@setOnTouchListener false
                val height = playbackArea.height.toFloat().coerceAtLeast(1f)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val threshold = (1f - subtitlePositionPercent / 100f - 0.22f).coerceIn(0f, 0.78f)
                        if (event.y / height < threshold) return@setOnTouchListener false
                        view.tag = floatArrayOf(event.rawY, subtitlePositionPercent.toFloat())
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val start = view.tag as? FloatArray ?: return@setOnTouchListener false
                        subtitlePositionPercent = (start[1] - (event.rawY - start[0]) / height * 100f).toInt().coerceIn(0, 85)
                        applySubtitleStyle(subtitleSizeSp, subtitleColor, subtitlePositionPercent, true)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        subtitleDock.visibility = View.GONE
        playbackContainer.addView(subtitleDock, LinearLayout.LayoutParams(-1, 0))
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
            val currentIndex = player?.currentMediaItemIndex ?: playlistIndex
            if (currentIndex > 0) openPlaylistItem(currentIndex - 1)
            else player?.seekTo(0L)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        playPauseButton = controlButton("Ⅱ", "Play or pause") {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        controlsBar.addView(playPauseButton, LinearLayout.LayoutParams(0, -2, 1f))
        controlsBar.addView(controlButton("⏭", "Next video") {
            val currentIndex = player?.currentMediaItemIndex ?: playlistIndex
            if (currentIndex < playlistUris.lastIndex) openPlaylistItem(currentIndex + 1)
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
        // SubtitleView is hosted by playbackArea, outside the resized video frame.
        playerView.subtitleView?.visibility = View.VISIBLE
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        updateSystemBars()
        applyOrientationLayout()

        val prefs = getSharedPreferences("gleeshplayer_history", MODE_PRIVATE)
        resumePosition = prefs.getLong("position_$videoKey", 0L).coerceAtLeast(0L)
        val resumeMode = videoSettings.getInt("resume_behavior", 0)
        if (resumeMode == 2) resumePosition = 0L
        playlistUris = intent.getParcelableArrayListExtra<Uri>("playlist_uris")?.takeIf { it.isNotEmpty() } ?: arrayListOf(uri)
        playlistIndex = intent.getIntExtra("playlist_index", 0).coerceIn(0, playlistUris.lastIndex)
        resumePromptVisible = resumePosition > 1000L && resumeMode == 0
        if (resumeMode == 1) resumeBanner.visibility = View.GONE
        if (resumePromptVisible && !isLandscapeNow()) {
            resumeBanner.visibility = View.VISIBLE
            controlsHandler.postDelayed({
                resumePromptVisible = false
                resumeBanner.visibility = View.GONE
            }, 5000L)
        }
        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                .setSelectUndeterminedTextLanguage(true)
                .build()
            it.addListener(object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    if (externalSubtitleUri == null) {
                        // Embedded subtitles stay on Media3's native renderer and retain defaults.
                        pendingSubtitleCue?.let { captionLabel.removeCallbacks(it) }
                        captionLabel.text = ""
                        captionLabel.visibility = View.GONE
                        playerView.subtitleView?.visibility = View.VISIBLE
                        return
                    }
                    // External SRT uses the draggable overlay, which sits above the gesture layer.
                    playerView.subtitleView?.visibility = View.GONE
                    val line = cueGroup.cues.mapNotNull { cue ->
                        cue.text?.toString()?.takeIf { it.isNotBlank() }
                    }.joinToString("\n")
                    pendingSubtitleCue?.let { captionLabel.removeCallbacks(it) }
                    val update = Runnable {
                        captionLabel.text = line
                        captionLabel.visibility = if (line.isBlank()) View.GONE else View.VISIBLE
                    }
                    pendingSubtitleCue = update
                    if (line.isBlank() || subtitleDelayMs <= 0L) update.run()
                    else captionLabel.postDelayed(update, subtitleDelayMs)
                }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    if (tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }) {
                        subtitlePrefs().edit().putBoolean(embeddedSubtitleKey(Uri.parse(videoKey)), true).apply()
                    }
                    playbackArea.post { autoSelectFirstEmbeddedSubtitle() }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playPauseButton.text = if (isPlaying) "Ⅱ" else "▶"
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    val rotated = videoSize.unappliedRotationDegrees % 180 != 0
                    videoIsLandscape = if (rotated) videoSize.height > videoSize.width else videoSize.width > videoSize.height
                    nativeVideoWidth = if (rotated) videoSize.height else videoSize.width
                    nativeVideoHeight = if (rotated) videoSize.width else videoSize.height
                    applyAspectMode()
                    if (::orientationIcon.isInitialized) orientationIcon.setLandscape(videoIsLandscape)
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    autoEmbeddedSubtitleApplied = false
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
                    if (mode == 2 && reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) finishToLibrary()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) finishToLibrary()
                }
            })
            it.setMediaItems(playlistUris.map { itemUri -> mediaItemFor(itemUri) }, playlistIndex, resumePosition)
            externalSubtitleUri = savedSubtitleUri(uri)
            externalSubtitleName = externalSubtitleUri?.let { queryDisplayName(it) }
            it.prepare()
            applyEndBehavior(false)
            progressUpdater.run()
            applySubtitleStyle(
                if (subtitleRemembered()) subtitlePrefs().getFloat("subtitle_size", 22f) else 22f,
                android.graphics.Color.WHITE,
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
            android.widget.Toast.makeText(this, "This file provider does not allow saved subtitle access; it may not remain available after closing.", android.widget.Toast.LENGTH_LONG).show()
        }
        val currentPlayer = player ?: return
        val currentPosition = currentPlayer.currentPosition.coerceAtLeast(0L)
        val wasPlaying = currentPlayer.isPlaying || currentPlayer.playWhenReady
        val videoUri = intent.data ?: return
        val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("und")
            .setLabel(displayName)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val updatedItem = MediaItem.Builder()
            .setUri(videoUri)
            .setSubtitleConfigurations(listOf(subtitle))
            .build()
        subtitlePrefs().edit().putString(subtitleKey(videoUri), uri.toString()).apply()
        externalSubtitleUri = uri
        externalSubtitleName = displayName
        applySubtitleStyle(subtitleSizeSp, subtitleColor, subtitlePositionPercent, false)
        if (::subtitleFileLabel.isInitialized) {
            subtitleFileLabel.text = "Loaded: $displayName"
            subtitleFileLabel.setTextColor(palette.accent)
        }
        currentSubtitleValueLabel?.text = displayName
        currentSubtitleValueLabel?.setTextColor(palette.accent)
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

    private fun subtitleTrackLabel(format: androidx.media3.common.Format, index: Int): String {
        val label = format.label?.takeIf { it.isNotBlank() }
        val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.getDefault()) }
            ?.takeIf { it.isNotBlank() }
        return label ?: language ?: "Subtitle ${index + 1}"
    }

    private fun applyEmbeddedSubtitleSelection(trackGroup: androidx.media3.common.TrackGroup, trackIndex: Int) {
        val currentPlayer = player ?: return
        currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .addOverride(androidx.media3.common.TrackSelectionOverride(trackGroup, listOf(trackIndex)))
            .build()
    }

    private fun autoSelectFirstEmbeddedSubtitle() {
        val currentPlayer = player ?: return
        if (autoEmbeddedSubtitleApplied || externalSubtitleUri != null) return
        val textGroups = currentPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (textGroups.isEmpty()) return
        autoEmbeddedSubtitleApplied = true
        for (group in textGroups) {
            val firstSupported = (0 until group.length).firstOrNull { group.isTrackSupported(it) }
            if (firstSupported != null) {
                applyEmbeddedSubtitleSelection(group.mediaTrackGroup, firstSupported)
                return
            }
        }
    }

    private fun showEmbeddedSubtitleTracks() {
        val currentPlayer = player ?: return
        val choices = mutableListOf("Off")
        val selections = mutableListOf<Pair<androidx.media3.common.TrackGroup, Int>?>(null)
        val supported = mutableListOf(true)
        currentPlayer.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEach
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val isSupported = group.isTrackSupported(index)
                val label = subtitleTrackLabel(format, index)
                choices.add(if (isSupported) label else "${label} (unsupported)")
                selections.add(group.mediaTrackGroup to index)
                supported.add(isSupported)
            }
        }
        if (choices.size == 1) {
            AlertDialog.Builder(this).setTitle("Embedded subtitles")
                .setMessage("No embedded subtitle tracks were detected in this video. If XPlayer can see them, the MKV may use a subtitle codec this player still cannot decode.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Embedded subtitles")
            .setItems(choices.toTypedArray()) { _, which ->
                if (which == 0) {
                    currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                } else if (!supported.getOrElse(which) { false }) {
                    android.widget.Toast.makeText(this, "This subtitle format is not supported on this device.", android.widget.Toast.LENGTH_SHORT).show()
                    return@setItems
                } else {
                    val selected = selections.getOrNull(which) ?: return@setItems
                    applyEmbeddedSubtitleSelection(selected.first, selected.second)
                }
            }.show()
    }

    private fun subtitlePrefs() = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)

    private fun subtitleRemembered(): Boolean = subtitlePrefs().getBoolean("remember_subtitles", true)

    private fun handleExternalSubtitleDrag(event: MotionEvent): Boolean {
        if (externalSubtitleUri == null) {
            externalSubtitleDragging = false
            return false
        }
        val height = playbackArea.height.toFloat().coerceAtLeast(1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The gesture layer sits above PlayerView's native SubtitleView.
                // Capture only touches in the lower subtitle zone, leaving the rest
                // of the screen's playback gestures unchanged.
                val subtitleZoneTop = (1f - subtitlePositionPercent / 100f - 0.25f).coerceIn(0.62f, 0.88f)
                if (event.y / height < subtitleZoneTop) return false
                externalSubtitleDragStartY = event.rawY
                externalSubtitleDragStartPosition = subtitlePositionPercent.toFloat()
                externalSubtitleDragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!externalSubtitleDragging) return false
                subtitlePositionPercent = (
                    externalSubtitleDragStartPosition -
                        (event.rawY - externalSubtitleDragStartY) / height * 100f
                    ).toInt().coerceIn(0, 85)
                applySubtitleStyle(subtitleSizeSp, subtitleColor, subtitlePositionPercent, true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = externalSubtitleDragging
                externalSubtitleDragging = false
                return wasDragging
            }
        }
        return externalSubtitleDragging
    }

    private fun applySubtitleStyle(size: Float, color: Int, position: Int, save: Boolean) {
        // Embedded subtitles deliberately retain Media3's default renderer and position.
        // External SRT uses the legacy overlay so its drag target stays above gestureLayer.
        if (externalSubtitleUri == null) {
            playerView.subtitleView?.visibility = View.VISIBLE
            captionLabel.visibility = View.GONE
            return
        }
        subtitleSizeSp = size
        subtitleColor = color
        subtitlePositionPercent = position.coerceIn(0, 85)
        playerView.subtitleView?.visibility = View.GONE
        captionLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        captionLabel.setTextColor(color)
        playbackArea.post {
            captionLabel.translationY = -(subtitlePositionPercent / 100f) * playbackArea.height.toFloat()
        }
        if (save && subtitleRemembered()) {
            subtitlePrefs().edit().putFloat("subtitle_size", size).putInt("subtitle_color", color)
                .putInt("subtitle_position", subtitlePositionPercent).apply()
        }
    }

    private fun showSubtitleCustomization() {
        if (playbackArea.findViewWithTag<View>("subtitle_settings_panel") != null) return
        val prefs = subtitlePrefs()
        val remembered = subtitleRemembered()
        var currentSize = if (remembered) prefs.getFloat("subtitle_size", 22f) else 22f
        var currentPosition = if (remembered) prefs.getInt("subtitle_position", 18) else 18
        subtitlePositionPercent = currentPosition.coerceIn(0, 85)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPlayer(18), dpPlayer(12), dpPlayer(18), dpPlayer(16))
            setBackgroundColor(if (palette.isDark) 0xA8182028.toInt() else 0xA8F5F7F8.toInt())
            tag = "subtitle_settings_panel"
            elevation = dpPlayer(14).toFloat()
        }
        val wasHeaderVisible = landscapeHeader.visibility == View.VISIBLE
        val wasControlsVisible = bottomControls.visibility == View.VISIBLE
        val wasPlayerHeaderVisible = playerHeader.visibility == View.VISIBLE
        val wasPortraitActionsVisible = portraitActionRow.visibility == View.VISIBLE
        val wasLandscapeActionsVisible = landscapeActions.visibility == View.VISIBLE
        fun removePanel() {
            (panel.parent as? android.view.ViewGroup)?.removeView(panel)
            landscapeHeader.visibility = if (wasHeaderVisible) View.VISIBLE else View.GONE
            landscapeActions.visibility = if (wasLandscapeActionsVisible) View.VISIBLE else View.GONE
            bottomControls.visibility = if (wasControlsVisible) View.VISIBLE else View.GONE
            playerHeader.visibility = if (wasPlayerHeaderVisible) View.VISIBLE else View.GONE
            portraitActionRow.visibility = if (wasPortraitActionsVisible) View.VISIBLE else View.GONE
            if (isLandscapeNow() && (wasHeaderVisible || wasControlsVisible)) scheduleControlsHide()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(palette.text)
            contentDescription = "Back to video player"
            setOnClickListener { removePanel() }
        }, LinearLayout.LayoutParams(dpPlayer(42), dpPlayer(48)))
        header.addView(TextView(this).apply {
            text = "Subtitle"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "×"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
            contentDescription = "Close subtitle settings"
            setOnClickListener { removePanel() }
        }, LinearLayout.LayoutParams(dpPlayer(36), dpPlayer(42)))
        panel.addView(header)

        fun sectionLabel(value: String) {
            panel.addView(TextView(this).apply {
                text = value
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.muted)
                setPadding(0, dpPlayer(15), 0, dpPlayer(5))
            })
        }
        fun rowAction(title: String, detail: String? = null, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpPlayer(2), dpPlayer(10), dpPlayer(2), dpPlayer(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            row.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(palette.text)
            })
            if (detail != null) {
                val detailView = TextView(this).apply {
                    text = detail
                    textSize = 12f
                    setTextColor(palette.muted)
                    setPadding(0, dpPlayer(3), 0, 0)
                }
                if (title == "Current subtitles") currentSubtitleValueLabel = detailView
                row.addView(detailView)
            }
            panel.addView(row, LinearLayout.LayoutParams(-1, -2))
            panel.addView(View(this).apply { setBackgroundColor(0x334C5962) },
                LinearLayout.LayoutParams(-1, dpPlayer(1)))
        }

        sectionLabel("SUBTITLES")
        rowAction("Current subtitles", currentSubtitleSummary()) {
            showCurrentSubtitleChoices()
        }
        rowAction("Open file", externalSubtitleName ?: "Add an external .srt subtitle file") {
            launchSubtitlePicker()
        }

        sectionLabel("SUBTITLE STYLE")
        val sizeLabel = TextView(this).apply {
            text = "Size: ${currentSize.toInt()} sp"
            textSize = 13f
            setTextColor(palette.text)
        }
        panel.addView(sizeLabel)
        panel.addView(SeekBar(this).apply {
            max = 16
            progress = ((currentSize - 12f) / 1.5f).toInt().coerceIn(0, 16)
            progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    currentSize = 12f + value * 1.5f
                    sizeLabel.text = "Size: ${currentSize.toInt()} sp"
                    applySubtitleStyle(currentSize, android.graphics.Color.WHITE, currentPosition, true)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
        val positionLabel = TextView(this).apply {
            text = "Position: $currentPosition%"
            textSize = 13f
            setTextColor(palette.text)
            setPadding(0, dpPlayer(10), 0, 0)
        }
        panel.addView(positionLabel)
        panel.addView(SeekBar(this).apply {
            max = 100
            progress = currentPosition.coerceIn(0, 100)
            progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    currentPosition = value
                    positionLabel.text = "Position: $currentPosition%"
                    applySubtitleStyle(currentSize, android.graphics.Color.WHITE, currentPosition, true)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })

        sectionLabel("SUBTITLE DELAY")
        rowAction("Subtitle delay", String.format(java.util.Locale.US, "%+.1fs", subtitleDelayMs / 1000f)) {
            showSubtitleDelayController()
        }
        val scrollPanel = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            addView(panel, android.view.ViewGroup.LayoutParams(-1, -2))
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        // Keep the title/back row visible while hiding playback action buttons.
        controlsHandler.removeCallbacks(hideControlsRunnable)
        val landscapeCc = isLandscapeNow()
        playerHeader.visibility = if (landscapeCc) View.GONE else View.VISIBLE
        portraitActionRow.visibility = View.GONE
        // Exactly one title row stays visible: portrait header in portrait,
        // landscape header in landscape. Hide its action buttons while CC is open.
        landscapeHeader.visibility = if (landscapeCc) View.VISIBLE else View.GONE
        landscapeActions.visibility = View.GONE
        bottomControls.visibility = View.GONE
        playbackArea.findViewWithTag<View>("subtitle_delay_controller")?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        if (isLandscapeNow()) {
            playbackArea.addView(scrollPanel, FrameLayout.LayoutParams(dpPlayer(330), -1, Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply {
                topMargin = dpPlayer(4)
                bottomMargin = dpPlayer(4)
            })
        } else {
            playbackArea.addView(scrollPanel, FrameLayout.LayoutParams(-1, dpPlayer(430), Gravity.BOTTOM))
        }
        playbackArea.bringChildToFront(scrollPanel)
    }

    private fun currentSubtitleSummary(): String {
        val current = player ?: return externalSubtitleName ?: "None"
        if (externalSubtitleUri != null) return externalSubtitleName ?: "External subtitle"
        current.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEach
            for (index in 0 until group.length) {
                if (!group.isTrackSelected(index)) continue
                val format = group.getTrackFormat(index)
                return subtitleTrackLabel(format, index)
            }
        }
        return "None"
    }

    private fun showCurrentSubtitleChoices() {
        val currentPlayer = player ?: return
        val choices = mutableListOf("None")
        val selections = mutableListOf<Pair<androidx.media3.common.TrackGroup, Int>?>(null)
        val supported = mutableListOf(true)
        var selectedChoice = 0
        currentPlayer.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEach
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val isSupported = group.isTrackSupported(index)
                val isExternal = externalSubtitleUri != null &&
                    (format.label == externalSubtitleName || format.sampleMimeType == MimeTypes.APPLICATION_SUBRIP)
                val baseLabel = if (isExternal) externalSubtitleName ?: "External subtitle"
                    else subtitleTrackLabel(format, index)
                choices.add(if (isSupported) baseLabel else "${baseLabel} (unsupported)")
                selections.add(group.mediaTrackGroup to index)
                supported.add(isSupported)
                if (group.isTrackSelected(index)) selectedChoice = choices.lastIndex
            }
        }
        if (externalSubtitleUri != null && choices.size == 1) {
            choices.add(externalSubtitleName ?: "External subtitle")
            selections.add(null)
            supported.add(false)
        }
        AlertDialog.Builder(this)
            .setTitle("Current subtitles")
            .setSingleChoiceItems(choices.toTypedArray(), selectedChoice) { dialog, which ->
                if (which > 0 && !supported.getOrElse(which) { false }) {
                    android.widget.Toast.makeText(this, "This subtitle format is not supported on this device.", android.widget.Toast.LENGTH_SHORT).show()
                    return@setSingleChoiceItems
                }
                if (which == 0) {
                    currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                } else {
                    val selected = selections.getOrNull(which)
                    if (selected != null) {
                        applyEmbeddedSubtitleSelection(selected.first, selected.second)
                    } else {
                        android.widget.Toast.makeText(this, "Subtitle track is not available yet. Reopen the list after loading completes.", android.widget.Toast.LENGTH_SHORT).show()
                        return@setSingleChoiceItems
                    }
                }
                if (which == 0) {
                    captionLabel.text = ""
                    captionLabel.visibility = View.GONE
                }
                currentSubtitleValueLabel?.text = if (which == 0) "None" else choices[which].removeSuffix(" (unsupported)")
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubtitleDelayController() {
        if (playbackArea.findViewWithTag<View>("subtitle_delay_controller") != null) return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpPlayer(7), dpPlayer(5), dpPlayer(7), dpPlayer(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xB91B2229.toInt())
                cornerRadius = dpPlayer(9).toFloat()
                setStroke(dpPlayer(1), 0x55FFFFFF.toInt())
            }
            tag = "subtitle_delay_controller"
            elevation = dpPlayer(16).toFloat()
        }
        fun close() { (card.parent as? android.view.ViewGroup)?.removeView(card) }
        val minus = TextView(this).apply {
            text = "−"; textSize = 21f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFF5700.toInt()); cornerRadius = dpPlayer(5).toFloat()
            }
        }
        val value = android.widget.EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setTextColor(android.graphics.Color.WHITE)
            setSelectAllOnFocus(true)
            setPadding(0, 0, 0, 0)
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            contentDescription = "Editable subtitle delay in seconds"
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    subtitleDelayMs = ((text?.toString()?.trim()?.toFloatOrNull() ?: 0f) * 1000f)
                        .toLong().coerceIn(-10_000L, 10_000L)
                    if (subtitleDelayMs == 0L) setText("0.0")
                    else setText(String.format(java.util.Locale.US, "%.1f", subtitleDelayMs / 1000f))
                }
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s.isNullOrEmpty()) subtitleDelayMs = 0L
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        val plus = TextView(this).apply {
            text = "+"; textSize = 21f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFF5700.toInt()); cornerRadius = dpPlayer(5).toFloat()
            }
        }
        val closeButton = TextView(this).apply {
            text = "×"; textSize = 19f; gravity = Gravity.CENTER
            setTextColor(0xFFCCCCCC.toInt()); contentDescription = "Close subtitle delay"
            setOnClickListener { close() }
        }
        fun refreshValue() {
            val shown = String.format(java.util.Locale.US, "%.1f", subtitleDelayMs / 1000f)
            if (!value.hasFocus()) value.setText(shown)
        }
        minus.setOnClickListener {
            commitDelayValue()
            subtitleDelayMs = (subtitleDelayMs - 100L).coerceAtLeast(-10_000L)
            refreshValue()
        }
        plus.setOnClickListener {
            commitDelayValue()
            subtitleDelayMs = (subtitleDelayMs + 100L).coerceAtMost(10_000L)
            refreshValue()
        }
        card.addView(minus, LinearLayout.LayoutParams(dpPlayer(32), dpPlayer(32)))
        card.addView(value, LinearLayout.LayoutParams(dpPlayer(62), dpPlayer(32)))
        card.addView(plus, LinearLayout.LayoutParams(dpPlayer(32), dpPlayer(32)))
        card.addView(closeButton, LinearLayout.LayoutParams(dpPlayer(24), dpPlayer(32)))
        refreshValue()
        val gravity = if (isLandscapeNow()) Gravity.RIGHT or Gravity.CENTER_VERTICAL else Gravity.RIGHT or Gravity.BOTTOM
        playbackArea.addView(card, FrameLayout.LayoutParams(-2, -2, gravity).apply {
            rightMargin = dpPlayer(8)
            bottomMargin = if (isLandscapeNow()) 0 else dpPlayer(10)
        })
        playbackArea.bringChildToFront(card)
    }

    private fun commitDelayValue() {
        // The editable value is committed on focus loss; clearing it means normal timing.
        if (subtitleDelayMs == 0L) pendingSubtitleCue?.let { captionLabel.removeCallbacks(it) }
    }

    private class SeekFeedbackOverlay(context: Context) : View(context) {
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3444444.toInt(); style = Paint.Style.FILL }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 25f * resources.displayMetrics.density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f * resources.displayMetrics.density, 0f, 1f * resources.displayMetrics.density, 0x99000000.toInt())
        }
        private val hideHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var direction = 1
        private var seconds = 0L
        private val hide = Runnable { visibility = View.GONE }

        fun show(direction: Int, seconds: Long) {
            this.direction = direction
            this.seconds = seconds
            hideHandler.removeCallbacks(hide)
            visibility = View.VISIBLE
            invalidate()
            hideHandler.postDelayed(hide, 850L)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val path = android.graphics.Path()
            val edge = w * 0.40f
            if (direction < 0) {
                path.moveTo(0f, 0f)
                path.lineTo(edge * 0.55f, 0f)
                path.quadTo(edge * 1.08f, h * 0.50f, edge * 0.55f, h)
                path.lineTo(0f, h)
            } else {
                path.moveTo(w, 0f)
                path.lineTo(w - edge * 0.55f, 0f)
                path.quadTo(w - edge * 1.08f, h * 0.50f, w - edge * 0.55f, h)
                path.lineTo(w, h)
            }
            path.close()
            canvas.drawPath(path, shade)

            val cx = if (direction < 0) w * 0.22f else w * 0.78f
            val cy = h * 0.50f
            val d = resources.displayMetrics.density
            val iconTop = cy - 18f * d
            val iconBottom = cy + 18f * d
            val barWidth = 7f * d
            val barGap = 5f * d
            val barHeight = 28f * d
            val barX = if (direction < 0) cx + 7f * d else cx - 7f * d
            canvas.drawRoundRect(barX - barWidth - barGap, cy - barHeight / 2f, barX - barGap, cy + barHeight / 2f, 2f * d, 2f * d, iconPaint)
            canvas.drawRoundRect(barX, cy - barHeight / 2f, barX + barWidth, cy + barHeight / 2f, 2f * d, 2f * d, iconPaint)
            val triangle = android.graphics.Path()
            if (direction > 0) {
                triangle.moveTo(cx + 25f * d, iconTop)
                triangle.lineTo(cx + 25f * d, iconBottom)
                triangle.lineTo(cx + 42f * d, cy)
            } else {
                triangle.moveTo(cx - 25f * d, iconTop)
                triangle.lineTo(cx - 25f * d, iconBottom)
                triangle.lineTo(cx - 42f * d, cy)
            }
            triangle.close()
            canvas.drawPath(triangle, iconPaint)
            textPaint.textSize = 24f * d
            canvas.drawText((if (direction > 0) "+" else "−") + seconds + "s", cx, cy + 66f * d, textPaint)
        }
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
                        val prefs = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
                        if (prefs.getBoolean("remember_brightness", true)) prefs.edit().putFloat("last_brightness", gestureBrightness).apply()
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
                    player?.setPlaybackSpeed(1.0f)
                    longPressSpeedActive = false
                }
                if (completedMode != 0 || wasLongPress) hideGestureFeedbackSoon()
                return true
            }
        }
        return true
    }

    private fun queueDoubleTapSeek(direction: Int) {
        val currentPlayer = player ?: return
        val duration = currentPlayer.duration
        if (duration <= 0L) return

        val existingTarget = pendingSeekTarget
        if (existingTarget != null && pendingSeekDirection != direction) {
            flushPendingSeek()
        }

        val base = pendingSeekTarget ?: currentPlayer.currentPosition.coerceIn(0L, duration)
        if (pendingSeekTarget == null) pendingSeekStart = base
        pendingSeekDirection = direction
        val target = (base + direction * seekIntervalMs).coerceIn(0L, duration)
        pendingSeekTarget = target

        val totalMoved = kotlin.math.abs(target - pendingSeekStart)
        val shownSeconds = (totalMoved / 1000L).coerceAtLeast(1L)
        seekFeedbackOverlay.show(direction, shownSeconds)

        pendingSeekHandler.removeCallbacks(commitPendingSeek)
        pendingSeekHandler.postDelayed(commitPendingSeek, 450L)
    }

    private fun flushPendingSeek() {
        pendingSeekHandler.removeCallbacks(commitPendingSeek)
        commitPendingSeek.run()
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
        val settings = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
        if (settings.getBoolean("remember_brightness", true) && settings.contains("last_brightness"))
            return settings.getFloat("last_brightness", 0.5f).coerceIn(0.05f, 1f)
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
        val settings = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
        if (settings.getBoolean("remember_orientation", true))
            settings.edit().putBoolean("last_orientation", newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE).apply()
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

    private fun finishToLibrary() {
        val returnIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("restore_library_context", true)
            putExtra("return_folder", intent.getStringExtra("return_folder"))
            putExtra("return_tab", intent.getIntExtra("return_tab", 0))
            putExtra("return_search_page", intent.getBooleanExtra("return_search_page", false))
            putExtra("return_search_query", intent.getStringExtra("return_search_query") ?: "")
            putExtra("return_scroll_y", intent.getIntExtra("return_scroll_y", 0))
        }
        startActivity(returnIntent)
        finish()
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
            current.setMediaItems(playlistUris.map { mediaItemFor(it) }, playlistIndex, position)
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

    private fun openPlaylistItem(targetIndex: Int) {
        if (targetIndex !in playlistUris.indices) return
        val currentPlayer = player
        currentPlayer?.let {
            // Persist the outgoing item's exact position before changing sessions.
            savePosition()
        }
        val targetUri = playlistUris[targetIndex]
        val titles = intent.getStringArrayListExtra("playlist_titles")
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            data = targetUri
            putExtra("title", titles?.getOrNull(targetIndex) ?: targetUri.lastPathSegment ?: "Video")
            putParcelableArrayListExtra("playlist_uris", ArrayList(playlistUris))
            putStringArrayListExtra("playlist_titles", titles)
            putExtra("playlist_index", targetIndex)
            putExtra("return_folder", intent.getStringExtra("return_folder"))
            putExtra("return_tab", intent.getIntExtra("return_tab", 0))
            putExtra("return_search_page", intent.getBooleanExtra("return_search_page", false))
            putExtra("return_search_query", intent.getStringExtra("return_search_query") ?: "")
            putExtra("return_scroll_y", intent.getIntExtra("return_scroll_y", 0))
        })
        finish()
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
