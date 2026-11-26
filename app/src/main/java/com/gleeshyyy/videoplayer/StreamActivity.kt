package com.gleeshyyy.videoplayer

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class StreamActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var webView: WebView? = null
    private lateinit var playerView: PlayerView
    private lateinit var contentFrame: FrameLayout
    private lateinit var linkInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = ThemeManager.palette(this)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 34f; gravity = Gravity.CENTER
            setTextColor(palette.text); contentDescription = "Back to videos"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        header.addView(TextView(this).apply {
            text = "Stream"; textSize = 21f
            setTextColor(palette.text); setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Paste a video or streaming link"
            textSize = 14f; setTextColor(palette.muted)
            setPadding(0, dp(12), 0, dp(8))
        })
        linkInput = EditText(this).apply {
            hint = "https://..."
            textSize = 14f
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), palette.muted)
            }
        }
        root.addView(linkInput, LinearLayout.LayoutParams(-1, dp(50)))
        val play = Button(this).apply {
            text = "Play"
            setOnClickListener { playLink(linkInput.text?.toString()?.trim().orEmpty()) }
        }
        root.addView(play, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(9) })
        contentFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(contentFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        setContentView(root)
    }

    private fun playLink(raw: String) {
        if (raw.isBlank()) {
            linkInput.error = "Paste a link first"
            return
        }
        val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
        if (uri?.host.isNullOrBlank()) {
            linkInput.error = "Enter a valid URL"
            return
        }
        player?.release()
        player = null
        webView?.destroy()
        webView = null
        contentFrame.removeAllViews()
        val path = uri?.lastPathSegment?.lowercase().orEmpty()
        val directMedia = path.endsWith(".mp4") || path.endsWith(".m4v") ||
            path.endsWith(".webm") || path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            path.endsWith(".mp3") || path.endsWith(".aac") || path.endsWith(".ts")
        if (directMedia) {
            playerView = PlayerView(this).apply { useController = true }
            contentFrame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
            player = ExoPlayer.Builder(this).build().also { exo ->
                playerView.player = exo
                exo.setMediaItem(MediaItem.fromUri(uri!!))
                exo.prepare()
                exo.playWhenReady = true
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Toast.makeText(this@StreamActivity, "This stream could not be played.", Toast.LENGTH_LONG).show()
                    }
                })
            }
        } else {
            val browser = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                webChromeClient = WebChromeClient()
            }
            webView = browser
            contentFrame.addView(browser, FrameLayout.LayoutParams(-1, -1))
            val youtubeId = when {
                uri?.host?.contains("youtu.be", true) == true -> uri.lastPathSegment
                uri?.host?.contains("youtube.com", true) == true ->
                    if (uri.path == "/watch") uri.getQueryParameter("v")
                    else Regex("/(?:shorts|live|embed)/([^/?]+)").find(uri.path.orEmpty())?.groupValues?.getOrNull(1)
                else -> null
            }
            val target = if (!youtubeId.isNullOrBlank())
                "https://www.youtube.com/embed/$youtubeId?autoplay=1"
            else normalized
            browser.loadUrl(target)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        playerViewOrNull()?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun playerViewOrNull(): PlayerView? =
        if (::playerView.isInitialized) playerView else null
}
