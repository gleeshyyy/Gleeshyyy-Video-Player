package com.gleeshyyy.videoplayer

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(0xFF080D12.toInt())
        }
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        showMenu()
    }

    private fun beginPage(title: String, backToMenu: Boolean = false) {
        root.removeAllViews()
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(label("‹", 32f, 0xFF20D66B.toInt(), true).apply {
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { if (backToMenu) showMenu() else finish() }
        })
        bar.addView(label(title, 24f, 0xFFF1F5F7.toInt(), true))
        root.addView(bar)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body)
    }

    private fun showMenu() {
        beginPage("Settings")
        addMenu("Manage Scan list", "Choose which folders GleeshPlayer scans", "manage")
        addMenu("Theme", "Appearance and colors", "theme")
        body.addView(label(" ", 14f, 0xFF8997A1.toInt()).apply { setPadding(0, dp(12), 0, dp(12)) })
        body.addView(label("GENERAL", 12f, 0xFF20D66B.toInt(), true).apply { setPadding(0, dp(8), 0, dp(10)) })
        addMenu("General", "Language, history and app information", "general")
        addMenu("Video", "Playback preferences", "video")
        addMenu("Subtitles", "Subtitle preferences", "subtitles")
    }

    private fun addMenu(title: String, subtitle: String, page: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            setBackgroundColor(0xFF111A22.toInt())
            setOnClickListener { showPage(page) }
        }
        row.addView(label(title, 16f, 0xFFF1F5F7.toInt(), true))
        row.addView(label(subtitle, 12f, 0xFF8997A1.toInt()).apply { setPadding(0, dp(4), 0, 0) })
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    private fun showPage(page: String) {
        when (page) {
            "general" -> showGeneral()
            "video" -> showVideo()
            "theme" -> showPlaceholder("Theme", "Theme options will be added in a future update.")
            "manage" -> showPlaceholder("Manage Scan list", "Folder scanning controls will be added here.")
            "subtitles" -> showPlaceholder("Subtitles", "Subtitle settings will be added in a future update.")
        }
    }

    private fun showGeneral() {
        beginPage("General", true)
        body.addView(label("LANGUAGE", 12f, 0xFF20D66B.toInt(), true).apply { setPadding(0, dp(28), 0, dp(12)) })
        val language = settingRow("Language", "Coming in the next update")
        body.addView(language)
        body.addView(label("HISTORY", 12f, 0xFF20D66B.toInt(), true).apply { setPadding(0, dp(28), 0, dp(12)) })
        val historyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            setBackgroundColor(0xFF111A22.toInt())
        }
        historyRow.addView(label("Show History", 16f, 0xFFF1F5F7.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = SwitchCompat(this).apply {
            isChecked = prefs.getBoolean("show_history", true)
            showText = false
            splitTrack = false
            thumbTintList = ColorStateList.valueOf(0xFFF1F5F7.toInt())
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF20D66B.toInt(), 0xFF46515A.toInt())
            )
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("show_history", checked).apply() }
        }
        historyRow.addView(toggle)
        body.addView(historyRow)
        body.addView(label("ABOUT", 12f, 0xFF20D66B.toInt(), true).apply { setPadding(0, dp(28), 0, dp(12)) })
        body.addView(settingRow("Version", "1.1"))
        body.addView(settingRow("Developer", "Muhirwa Elyse"))
        body.addView(settingRow("Assistant Developer", "Lil Ty"))
    }

    private fun showVideo() {
        beginPage("Video", true)
        body.addView(label("PLAYBACK", 12f, 0xFF20D66B.toInt(), true).apply { setPadding(0, dp(28), 0, dp(12)) })
        body.addView(label("Long-press playback speed", 17f, 0xFFF1F5F7.toInt(), true))
        body.addView(label("Select the temporary speed used while holding the video. The selected normal playback speed resumes when released.", 13f, 0xFF8997A1.toInt()).apply { setPadding(0, dp(7), 0, dp(16)) })
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

    private fun showPlaceholder(title: String, message: String) {
        beginPage(title, true)
        body.addView(label(message, 14f, 0xFF8997A1.toInt()).apply { setPadding(0, dp(28), 0, 0) })
    }

    private fun settingRow(title: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            setBackgroundColor(0xFF111A22.toInt())
        }
        row.addView(label(title, 15f, 0xFFF1F5F7.toInt(), true))
        row.addView(label(value, 12f, 0xFF8997A1.toInt()).apply { setPadding(0, dp(4), 0, 0) })
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
