package com.gleeshyyy.videoplayer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val splash = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.gleeshyyy_splash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setPadding((16 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt())
            contentDescription = "Gleeshyyy"
        }
        splash.addView(logo, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
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