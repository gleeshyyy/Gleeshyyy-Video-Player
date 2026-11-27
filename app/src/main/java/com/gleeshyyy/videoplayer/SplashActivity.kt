package com.gleeshyyy.videoplayer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.graphics.Typeface
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val splash = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val brandStack = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.gleeshyyy_splash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Gleeshyyy logo"
        }
        brandStack.addView(logo, android.widget.LinearLayout.LayoutParams((92 * resources.displayMetrics.density).toInt(), (92 * resources.displayMetrics.density).toInt()))
        val wordmark = TextView(this).apply {
            text = android.text.SpannableString("GLEESHYYY").apply {
                setSpan(android.text.style.ForegroundColorSpan(0xFF20D66B.toInt()), 0, length, 0)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
            }
            textSize = 31f
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setPadding(0, (15 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        brandStack.addView(wordmark)
        val subtitle = TextView(this).apply {
            text = LanguageManager.translate(this@SplashActivity, "VIDEO PLAYER")
            textSize = 11f
            letterSpacing = 0.28f
            gravity = Gravity.CENTER
            setTextColor(0xFF9AA6AD.toInt())
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        brandStack.addView(subtitle)
        splash.addView(brandStack, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setContentView(splash)
        handler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }, 2200L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}