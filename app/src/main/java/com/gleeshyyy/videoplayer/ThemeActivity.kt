package com.gleeshyyy.videoplayer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class ThemeActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE) }
    private var mode = "dark"
    private var picture: String? = null
    private var transparency = 35
    private var blur = 0
    private lateinit var page: LinearLayout
    private lateinit var previewImage: ImageView
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            picture = uri.toString()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = prefs.getString("theme_mode", "dark") ?: "dark"
        picture = prefs.getString("theme_picture", null)
        transparency = prefs.getInt("theme_transparency", 35)
        blur = prefs.getInt("theme_blur", 0)
        render()
    }

    private fun render() {
        val dark = when (mode) {
            "light" -> false
            "auto" -> (resources.configuration.uiMode and 0x30) != 0x10
            "picture" -> ThemeManager.pictureIsDark(this, picture)
            else -> true
        }
        val bg = if (dark) Color.rgb(8,13,18) else Color.rgb(246,248,249)
        val fg = if (dark) Color.WHITE else Color.rgb(25,32,38)
        val surface = if (dark) Color.rgb(20,29,37) else Color.WHITE
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(28))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }
        setContentView(scroll)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "‹   Theme"; textSize = 23f; setTextColor(fg); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(TextView(this).apply {
            text = "SAVE"; textSize = 14f; setTextColor(Color.rgb(32,214,107)); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(12), 0, dp(12))
            setOnClickListener {
                val finalMode = if (mode == "picture" && picture.isNullOrBlank()) "dark" else mode
                prefs.edit().putString("theme_mode", finalMode).putString("theme_picture", picture)
                    .putInt("theme_transparency", transparency).putInt("theme_blur", blur).apply()
                ThemeManager.applySystemMode(this@ThemeActivity)
                setResult(Activity.RESULT_OK)
                finish()
            }
        })
        page.addView(header)
        page.addView(text("Choose your appearance", 13f, muted(dark)).apply { setPadding(0, dp(8), 0, dp(12)) })
        val choices = listOf("dark" to "Dark", "light" to "Light", "auto" to "Auto", "picture" to "Picture")
        choices.forEach { (value, name) ->
            val selected = mode == value
            page.addView(TextView(this).apply {
                text = (if (selected) "✓   " else "") + name
                textSize = 15f; setTextColor(if (selected) Color.rgb(32,214,107) else fg)
                setPadding(dp(14), dp(13), dp(14), dp(13)); setBackgroundColor(if (selected) if (dark) Color.rgb(37,53,61) else Color.rgb(224,239,230) else surface)
                setOnClickListener { mode = value; render() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        page.addView(text("PREVIEW", 12f, Color.rgb(32,214,107), true).apply { setPadding(0, dp(17), 0, dp(8)) })
        val frame = FrameLayout(this).apply { setBackgroundColor(bg) }
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (mode == "picture" && !picture.isNullOrBlank()) setImageBitmap(ThemeManager.loadPicture(this@ThemeActivity, picture))
            alpha = 1f - transparency / 100f * .55f
        }
        frame.addView(previewImage, FrameLayout.LayoutParams(-1, -1))
        ThemeManager.blur(previewImage, blur.toFloat())
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            setBackgroundColor(if (mode == "picture") { if (dark) 0x66000000 else 0x66FFFFFF } else if (dark) 0xCC101820.toInt() else 0xDDF5F7F8.toInt())
        }
        overlay.addView(text("GleeshPlayer", 20f, Color.rgb(32,214,107), true))
        overlay.addView(text("Your videos      ⚙", 14f, fg, true).apply { setPadding(0, dp(9), 0, dp(8)) })
        overlay.addView(text("Recently played", 12f, fg, true))
        overlay.addView(text("▰       ▰       ▰", 22f, fg).apply { setPadding(0, dp(3), 0, dp(7)) })
        overlay.addView(text("Folders", 15f, fg, true))
        overlay.addView(text("▣   Camera          12 videos", 13f, fg).apply { setPadding(dp(8), dp(8), 0, dp(6)) })
        overlay.addView(text("▣   Downloads        4 videos", 13f, fg).apply { setPadding(dp(8), dp(5), 0, dp(6)) })
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        page.addView(frame, LinearLayout.LayoutParams(-1, dp(235)))
        if (mode == "picture") {
            page.addView(TextView(this).apply {
                text = "CHANGE BACKGROUND PICTURE"; textSize = 13f; setTextColor(Color.rgb(32,214,107)); setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(17), 0, dp(10)); setOnClickListener { picker.launch(arrayOf("image/*")) }
            })
            page.addView(text("Transparency", 14f, fg, true).apply { setPadding(0, dp(5), 0, 0) })
            page.addView(slider(transparency, 100) { transparency = it; refreshPreview() })
            page.addView(text("Blur", 14f, fg, true).apply { setPadding(0, dp(8), 0, 0) })
            page.addView(slider(blur, 25) { blur = it; refreshPreview() })
            page.addView(text(if (picture.isNullOrBlank()) "Choose a picture to see it here." else "Image colours will guide the app's accent and dark/light surfaces.", 12f, muted(dark)).apply { setPadding(0, dp(8), 0, 0) })
        }
    }

    private fun refreshPreview() {
        previewImage.alpha = 1f - transparency / 100f * .55f
        ThemeManager.blur(previewImage, blur.toFloat())
    }
    private fun slider(value: Int, max: Int, change: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = value.coerceIn(0, max)
        setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) change(progress) }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    private fun text(value:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply {
        text=value; textSize=size; setTextColor(color); if(bold) setTypeface(typeface,android.graphics.Typeface.BOLD)
    }
    private fun muted(dark:Boolean)=if(dark) Color.rgb(153,166,175) else Color.rgb(91,103,112)
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
