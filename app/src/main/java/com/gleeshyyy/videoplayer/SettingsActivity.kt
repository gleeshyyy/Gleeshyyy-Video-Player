package com.gleeshyyy.videoplayer

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private val speeds = listOf(2f, 4f, 8f)
    private val speedLabels = listOf("2×", "4×", "8×")
    private val prefs by lazy { getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE) }
    private lateinit var options: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(24))
            setBackgroundColor(0xFF080D12.toInt())
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(0xFF20D66B.toInt())
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
        }
        toolbar.addView(back)
        toolbar.addView(label("Settings", 25f, 0xFFF1F5F7.toInt(), true))
        root.addView(toolbar)

        root.addView(label("PLAYER", 12f, 0xFF20D66B.toInt(), true).apply {
            setPadding(0, dp(34), 0, dp(14))
        })
        root.addView(label("Long-press playback speed", 18f, 0xFFF1F5F7.toInt(), true))
        root.addView(label(
            "Choose how fast a video plays while you press and hold the screen. Normal speed returns when you release.",
            13f, 0xFF8997A1.toInt()
        ).apply { setPadding(0, dp(7), 0, dp(18)) })

        options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(options)
        setContentView(root)
        renderOptions()
    }

    private fun renderOptions() {
        options.removeAllViews()
        val selected = prefs.getFloat("long_press_speed", 2f)
        speeds.forEachIndexed { index, speed ->
            val active = selected == speed
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(17), dp(16), dp(17))
                setBackgroundColor(if (active) 0xFF153322.toInt() else 0xFF111A22.toInt())
                setOnClickListener {
                    prefs.edit().putFloat("long_press_speed", speed).apply()
                    renderOptions()
                }
            }
            val title = label(speedLabels[index], 17f, if (active) 0xFF20D66B.toInt() else 0xFFF1F5F7.toInt(), true)
            row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(if (active) "✓" else "○", 20f, if (active) 0xFF20D66B.toInt() else 0xFF8997A1.toInt(), true))
            options.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
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
