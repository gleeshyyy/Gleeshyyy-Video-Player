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

class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE) }
    private val speeds = listOf(2f, 4f, 8f)
    private val speedLabels = listOf("2×", "4×", "8×")
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private val palette get() = ThemeManager.palette(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(palette.background)
        }
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        showMenu()
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
            setBackgroundColor(palette.surface)
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
            body.addView(label("Allow video access on the home screen to manage scanned folders.", 14f, palette.text).apply { setPadding(dp(14), dp(16), dp(14), dp(16)); setBackgroundColor(palette.surface) })
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
        val language = settingRow("Language", "Coming in the next update")
        body.addView(language)
        body.addView(label("HISTORY", 12f, palette.accent, true).apply { setPadding(0, dp(28), 0, dp(12)) })
        val historyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            setBackgroundColor(palette.surface)
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
        body.addView(settingRow("Version", "1.1"))
        body.addView(settingRow("Developer", "Muhirwa Elyse"))
        body.addView(settingRow("Assistant Developer", "Lil Ty"))
    }

    private fun showVideo() {
        beginPage("Video", true)
        body.addView(label("PLAYBACK", 12f, palette.accent, true).apply { setPadding(0, dp(28), 0, dp(12)) })
        body.addView(label("Long-press playback speed", 17f, palette.text, true))
        body.addView(label("Select the temporary speed used while holding the video. The selected normal playback speed resumes when released.", 13f, palette.muted).apply { setPadding(0, dp(7), 0, dp(16)) })
        speeds.forEachIndexed { index, speed ->
            val active = prefs.getFloat("long_press_speed", 2f) == speed
            val row = settingRow(speedLabels[index], if (active) "Selected ✓" else "Tap to select")
            row.setOnClickListener {
                prefs.edit().putFloat("long_press_speed", speed).apply()
                showVideo()
            }
            body.addView(row)
        }
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
            setBackgroundColor(palette.surface)
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
            setBackgroundColor(palette.surface)
        }
        row.addView(label(title, 15f, palette.text, true))
        row.addView(label(value, 12f, palette.muted).apply { setPadding(0, dp(4), 0, 0) })
        return row
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
