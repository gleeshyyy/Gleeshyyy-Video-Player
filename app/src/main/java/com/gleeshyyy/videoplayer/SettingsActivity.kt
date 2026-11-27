package com.gleeshyyy.videoplayer

import android.os.Bundle
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.widget.LinearLayout
import android.content.res.ColorStateList
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE) }
    private val speeds = listOf(1f, 2f, 4f, 8f)
    private val speedLabels = listOf("1×", "2×", "4×", "8×")
    private val seekIntervals = listOf(3, 5, 10, 15, 20, 30, 60)
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var settingsBackground: android.widget.ImageView
    private val palette get() = ThemeManager.palette(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(palette.background)
        }
        val frame = android.widget.FrameLayout(this)
        settingsBackground = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
        frame.addView(settingsBackground, android.widget.FrameLayout.LayoutParams(-1, -1))
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; setBackgroundColor(android.graphics.Color.TRANSPARENT); addView(root) }
        frame.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        applySettingsTheme()
        showMenu()
    }

    override fun onResume() {
        super.onResume()
        if (::settingsBackground.isInitialized) applySettingsTheme()
    }

    private fun applySettingsTheme() {
        val prefs = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
        val isPicture = prefs.getString("theme_mode", "dark") == "picture"
        val image = if (isPicture) ThemeManager.loadPicture(this, prefs.getString("theme_picture", null)) else null
        settingsBackground.setImageBitmap(image)
        settingsBackground.alpha = if (image == null) 1f else (1f - prefs.getInt("theme_transparency", 35).coerceIn(0,100) / 100f * .55f).coerceIn(.35f,1f)
        ThemeManager.blur(settingsBackground, prefs.getInt("theme_blur", 0).toFloat())
        root.setBackgroundColor(if (image == null) palette.background else android.graphics.Color.TRANSPARENT)
    }

    private fun panelBackground(): Int {
        if (getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE).getString("theme_mode", "dark") != "picture") return palette.surface
        return if (palette.isDark) 0x66000000 else 0x66FFFFFF
    }

    private fun beginPage(title: String, backToMenu: Boolean = false) {
        root.removeAllViews()
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(label("‹", 32f, palette.accent, true).apply {
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { if (backToMenu) showMenu() else finish() }
        })
        bar.addView(label(title, 24f, palette.text, true))
        root.addView(bar)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body)
    }

    private fun showMenu() {
        beginPage("Settings")
        addMenu("Manage Scan list", "Choose which folders GleeshPlayer scans", "manage")
        addMenu("Theme", "Appearance and colors", "theme")
        body.addView(label(" ", 14f, palette.muted).apply { setPadding(0, dp(12), 0, dp(12)) })
        body.addView(label("GENERAL", 12f, palette.accent, true).apply { setPadding(0, dp(8), 0, dp(10)) })
        addMenu("General", "Language, history and app information", "general")
        addMenu("Video", "Playback preferences", "video")
        addMenu("Subtitles", "Subtitle preferences", "subtitles")
    }

    private fun addMenu(title: String, subtitle: String, page: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            setBackgroundColor(panelBackground())
            setOnClickListener { showPage(page) }
        }
        row.addView(label(title, 16f, palette.text, true))
        row.addView(label(subtitle, 12f, palette.muted).apply { setPadding(0, dp(4), 0, 0) })
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    private fun showPage(page: String) {
        when (page) {
            "general" -> showGeneral()
            "video" -> showVideo()
            "theme" -> startActivity(Intent(this, ThemeActivity::class.java))
            "manage" -> showManageScanList()
            "subtitles" -> showSubtitles()
        }
    }

    private fun showManageScanList() {
        beginPage("Manage Scan list", true)
        body.addView(label("Choose which folders appear in your video library. Disabled folders are excluded from GleeshPlayer scan.", 13f, palette.muted).apply { setPadding(0, dp(18), 0, dp(18)) })
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            body.addView(label("Allow video access on the home screen to manage scanned folders.", 14f, palette.text).apply { setPadding(dp(14), dp(16), dp(14), dp(16)); setBackgroundColor(panelBackground()) })
            return
        }
        val folders = linkedMapOf<String, Pair<String, Int>>()
        try {
            contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media.RELATIVE_PATH), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val path = if (column >= 0) cursor.getString(column).orEmpty() else "Videos/"
                    val folderPath = path.ifBlank { "Internal storage/" }
                    val name = folderPath.trimEnd('/').substringAfterLast('/').ifBlank { "Internal storage" }
                    val previous = folders[folderPath]
                    folders[folderPath] = name to ((previous?.second ?: 0) + 1)
                }
            }
        } catch (_: SecurityException) { }
        if (folders.isEmpty()) {
            body.addView(label("No video folders found on this device.", 14f, palette.muted).apply { setPadding(0, dp(10), 0, 0) })
            return
        }
        val excluded = prefs.getStringSet("excluded_scan_folders", emptySet())?.toMutableSet() ?: mutableSetOf()
        body.addView(label(folders.size.toString() + " folders found", 12f, palette.accent, true).apply { setPadding(0, 0, 0, dp(12)) })
        folders.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (path, details) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(12), dp(13))
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val folderName = label(details.first, 15f, palette.text, true)
            val videoCount = label(details.second.toString() + " videos", 12f, palette.muted).apply { setPadding(0, dp(4), 0, 0) }
            labels.addView(folderName)
            labels.addView(videoCount)
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            fun styleFolderRow(enabled: Boolean) {
                row.setBackgroundColor(if (enabled) palette.surface else palette.raised)
                folderName.setTextColor(if (enabled) palette.text else palette.muted)
                videoCount.setTextColor(if (enabled) palette.muted else palette.muted)
            }
            val toggle = SwitchCompat(this).apply {
                isChecked = path !in excluded
                showText = false
                splitTrack = false
                thumbTintList = ColorStateList.valueOf(palette.text)
                trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(palette.accent, 0xFF46515A.toInt()))
                styleFolderRow(isChecked)
                setOnCheckedChangeListener { _, enabled ->
                    if (enabled) excluded.remove(path) else excluded.add(path)
                    styleFolderRow(enabled)
                    prefs.edit().putStringSet("excluded_scan_folders", excluded.toSet()).putBoolean("scan_settings_changed", true).apply()
                }
            }
            row.addView(toggle)
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun showGeneral() {
        beginPage("General", true)
        body.addView(label("LANGUAGE", 12f, palette.accent, true).apply { setPadding(0, dp(28), 0, dp(12)) })
        val languageNames = listOf("English", "Français", "Español", "Kiswahili", "Русский", "中文（简体）", "हिन्दी", "తెలుగు", "தமிழ்")
        val languageTags = listOf("en", "fr", "es", "sw", "ru", "zh-CN", "hi", "te", "ta")
        val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank {
            prefs.getString("app_language", "en") ?: "en"
        }
        val currentIndex = languageTags.indexOfFirst { currentTag.startsWith(it, ignoreCase = true) }.coerceAtLeast(0)
        val language = settingRow("Language", languageNames[currentIndex] + "  ›").apply {
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Choose language")
                    .setSingleChoiceItems(languageNames.toTypedArray(), currentIndex) { dialog, which ->
                        val tag = languageTags[which]
                        prefs.edit().putString("app_language", tag).apply()
                        LanguageManager.apply(tag)
                        dialog.dismiss()
                    }
                    .setNegativeButton(LanguageManager.translate(this@SettingsActivity, "Cancel"), null)
                    .show()
            }
        }
        body.addView(language)
        body.addView(label("HISTORY", 12f, palette.accent, true).apply { setPadding(0, dp(28), 0, dp(12)) })
        val historyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            setBackgroundColor(panelBackground())
        }
        historyRow.addView(label("Show History", 16f, palette.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = SwitchCompat(this).apply {
            isChecked = prefs.getBoolean("show_history", true)
            showText = false
            splitTrack = false
            thumbTintList = ColorStateList.valueOf(palette.text)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(palette.accent, 0xFF46515A.toInt())
            )
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("show_history", checked).apply() }
        }
        historyRow.addView(toggle)
        body.addView(historyRow)
        body.addView(label("ABOUT", 12f, palette.accent, true).apply { setPadding(0, dp(28), 0, dp(12)) })
        body.addView(settingRow("Version", "GleeshPlayer v1.2"))
        body.addView(settingRow("Developer", "Muhirwa Elyse"))
        body.addView(settingRow("Assistant Developer", "Lil Ty"))
        val instagramRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            setBackgroundColor(panelBackground())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val profile = android.net.Uri.parse("https://www.instagram.com/blocioooo")
                startActivity(Intent(Intent.ACTION_VIEW, profile))
            }
        }
        instagramRow.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_instagram)
            imageTintList = ColorStateList.valueOf(palette.text)
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        instagramRow.addView(label("@blocioooo", 14f, palette.text, true).apply {
            setPadding(dp(12), 0, 0, 0)
        })
        body.addView(instagramRow)
    }

    private fun showVideo() {
        beginPage("Video", true)
        body.addView(label("PLAYBACK", 12f, palette.accent, true).apply { setPadding(0, dp(24), 0, dp(12)) })
        addChoice("Controls auto-hide", "Choose how long player controls and the video title stay visible in portrait and landscape modes.", "controls_auto_hide",
            listOf("3 seconds", "5 seconds", "7 seconds", "10 seconds", "15 seconds", "30 seconds", "60 seconds", "Never hide"),
            listOf("3000", "5000", "7000", "10000", "15000", "30000", "60000", "-1"),
            prefs.getInt("controls_auto_hide", 7000).toString(), "7 seconds")
        addToggle("Remember aspect", "Keep the last selected video fit mode (FIT, ZOOM or FILL).", "remember_aspect", true)
        addToggle("Remember screen orientation", "Keep the last orientation selected in the player.", "remember_orientation", true)
        addToggle("Long press vibration", "Vibrate briefly when long-press playback speed activates.", "long_press_vibration", true)
        addChoice("Long press speed", "Temporary playback speed while holding the video.", "long_press_speed",
            speedLabels, speeds.map { it.toString() }, prefs.getFloat("long_press_speed", 2f).toString(), "2×")
        addChoice("Fast forward/rewind interval", "How far a double-tap skips forward or backward.", "seek_interval",
            seekIntervals.map { "${it}s" }, seekIntervals.map { it.toString() }, prefs.getInt("seek_interval", 10).toString(), "10s")
        addChoice("Resume", "Choose what GleeshPlayer does when a video has a saved playback position.", "resume_behavior",
            listOf("Ask every time", "Always", "Never"), listOf("0", "1", "2"), prefs.getInt("resume_behavior", 0).toString(), "Ask every time")
        addToggle("Remember brightness", "Keep the brightness level last set with the player's left-side swipe gesture.", "remember_brightness", true)
    }

    private fun addToggle(title: String, description: String, key: String, default: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(12), dp(13))
            setBackgroundColor(panelBackground())
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(label(title, 15f, palette.text, true))
        labels.addView(label(description, 12f, palette.muted).apply { setPadding(0, dp(4), dp(8), 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = SwitchCompat(this).apply {
            isChecked = prefs.getBoolean(key, default)
            showText = false
            splitTrack = false
            thumbTintList = ColorStateList.valueOf(palette.text)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(palette.accent, 0xFF46515A.toInt()))
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }
        row.addView(toggle)
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun addChoice(title: String, description: String, key: String, labels: List<String>,
                          values: List<String>, selected: String, defaultLabel: String) {
        val currentLabel = labels.getOrNull(values.indexOf(selected)) ?: defaultLabel
        val row = settingRow(title, currentLabel + "  ›").apply {
            setOnClickListener {
                val index = values.indexOf(selected).coerceAtLeast(0)
                val dialog = androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(LanguageManager.translate(this@SettingsActivity, title))
                    .setSingleChoiceItems(labels.map { LanguageManager.translate(this@SettingsActivity, it) }.toTypedArray(), index) { d, which ->
                        val editor = prefs.edit()
                        when (key) {
                            "long_press_speed" -> editor.putFloat(key, values[which].toFloat())
                            "seek_interval", "resume_behavior", "controls_auto_hide" -> editor.putInt(key, values[which].toInt())
                            else -> editor.putString(key, values[which])
                        }
                        editor.apply()
                        d.dismiss()
                        showVideo()
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                dialog.setOnShowListener {
                    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(panelBackground()))
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(palette.accent)
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(palette.accent)
                    dialog.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(palette.text)
                    dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(palette.muted)
                    // Android's default single-choice row text can stay dark when the
                    // dialog surface is overridden for GleeshPlayer's dark palette.
                    val list = dialog.listView
                    for (i in 0 until list.childCount) {
                        (list.getChildAt(i) as? android.widget.TextView)?.setTextColor(palette.text)
                    }
                    list.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                        override fun onChildViewAdded(parent: android.view.View?, child: android.view.View?) {
                            (child as? android.widget.TextView)?.setTextColor(palette.text)
                        }
                        override fun onChildViewRemoved(parent: android.view.View?, child: android.view.View?) {}
                    })
                }
                dialog.show()
            }
        }
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }


    private fun showSubtitles() {
        beginPage("Subtitles", true)
        body.addView(label("REMEMBER SUBTITLES", 12f, palette.accent, true).apply {
            setPadding(0, dp(28), 0, dp(12))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            setBackgroundColor(panelBackground())
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(label("Remember Subtitles", 16f, palette.text, true))
        labels.addView(label("Keep your subtitle size, color and position settings for all videos.", 12f, palette.muted).apply {
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = SwitchCompat(this).apply {
            isChecked = prefs.getBoolean("remember_subtitles", true)
            showText = false
            splitTrack = false
            thumbTintList = ColorStateList.valueOf(palette.text)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(palette.accent, 0xFF46515A.toInt())
            )
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("remember_subtitles", checked).apply()
            }
        }
        row.addView(toggle)
        body.addView(row)
        body.addView(label("Customize subtitle size, color and position from the controls while a video is playing.", 13f, palette.muted).apply {
            setPadding(dp(2), dp(14), dp(2), 0)
        })
    }

    private fun showPlaceholder(title: String, message: String) {
        beginPage(title, true)
        body.addView(label(message, 14f, palette.muted).apply { setPadding(0, dp(28), 0, 0) })
    }

    private fun settingRow(title: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            setBackgroundColor(panelBackground())
            isClickable = true
            isFocusable = true
        }
        row.addView(label(title, 15f, palette.text, true).apply { isClickable = false })
        row.addView(label(value, 12f, palette.muted).apply { setPadding(0, dp(4), 0, 0); isClickable = false })
        return row
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = LanguageManager.translate(this@SettingsActivity, value)
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
