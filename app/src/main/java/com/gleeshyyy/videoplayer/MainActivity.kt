package com.gleeshyyy.videoplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray

data class VideoItem(
    val id: Long,
    val title: String,
    val uri: android.net.Uri,
    val duration: Long,
    val folder: String,
    val dateAdded: Long
)

class MainActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private var allVideos: List<VideoItem> = emptyList()
    private var selectedFolder: String? = null
    private val recentPrefs by lazy { getSharedPreferences("gleeshplayer_history", MODE_PRIVATE) }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        loadLibrary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showBrandSplash()
    }

    private fun showBrandSplash() {
        val splash = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.gleeshyyy_splash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        splash.addView(logo, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        setContentView(splash)
        Handler(Looper.getMainLooper()).postDelayed({
            showVideoLibrary()
        }, 1400L)
    }

    private fun showVideoLibrary() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF080D12.toInt())
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(30))
        }
        scroll.addView(content)
        setContentView(scroll)
        if (hasVideoPermission()) loadLibrary() else requestVideoPermission()
    }

    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasVideoPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 ->
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= 33 ->
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        else -> ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestVideoPermission() {
        permissionRequest.launch(requiredPermissions())
    }

    private fun loadLibrary() {
        allVideos = if (hasVideoPermission()) queryVideos() else emptyList()
        selectedFolder = null
        renderHome()
    }

    private fun queryVideos(): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED
        )
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sort
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val pathCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val path = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else "Videos/"
                    val folder = path.trimEnd('/').substringAfterLast('/').ifBlank { "Internal storage" }
                    result += VideoItem(
                        id,
                        cursor.getString(nameCol) ?: "Untitled video",
                        android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        cursor.getLong(durationCol),
                        folder,
                        cursor.getLong(dateCol)
                    )
                }
            }
        } catch (_: SecurityException) { }
        return result
    }

    private fun renderHome() {
        content.removeAllViews()
        val folder = selectedFolder
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(this).apply {
            text = android.text.SpannableString("GleeshPlayer").apply {
                setSpan(android.text.style.ForegroundColorSpan(0xFF20D66B.toInt()), 0, 6, 0)
                setSpan(android.text.style.ForegroundColorSpan(0xFF101820.toInt()), 6, length, 0)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
            }
            textSize = 25f
            setBackgroundColor(0xFFF3F6F4.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = TextView(this).apply {
            text = "↻"
            textSize = 27f
            setTextColor(0xFF20D66B.toInt())
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { loadLibrary() }
        }
        header.addView(refresh)
        content.addView(header)
        if (folder != null) {
            val back = text("‹  ALL FOLDERS", 13f, 0xFF20D66B.toInt(), true).apply {
                setPadding(0, dp(22), 0, dp(8))
                setOnClickListener { selectedFolder = null; renderHome() }
            }
            content.addView(back)
            content.addView(text(folder, 26f, 0xFFF1F5F7.toInt(), true))
            val videos = allVideos.filter { it.folder == folder }
            content.addView(text("${videos.size} videos", 13f, 0xFF8997A1.toInt()).apply { setPadding(0, dp(5), 0, dp(16)) })
            videos.forEach { addVideoRow(it) }
            return
        }

        content.addView(text("Your videos", 29f, 0xFFF1F5F7.toInt(), true).apply { setPadding(0, dp(25), 0, dp(2)) })
        content.addView(text("${allVideos.size} videos on this device", 13f, 0xFF8997A1.toInt()).apply { setPadding(0, 0, 0, dp(20)) })

        sectionTitle("Recently played")
        val recentIds = recentPrefs.getString("recent", "[]") ?: "[]"
        val recentUris = try { JSONArray(recentIds).let { arr -> (0 until arr.length()).map { arr.getString(it) } } } catch (_: Exception) { emptyList() }
        val recent = recentUris.mapNotNull { uri -> allVideos.firstOrNull { it.uri.toString() == uri } }.distinctBy { it.uri }.take(10)
        if (recent.isEmpty()) {
            content.addView(text("Your recently watched videos will appear here.", 13f, 0xFF8997A1.toInt()).apply { setPadding(0, dp(4), 0, dp(14)) })
        } else {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val horizontal = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) }
            recent.forEach { video ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, dp(12), 0)
                    setOnClickListener { openVideo(video) }
                }
                card.addView(thumbnail(video, dp(148), dp(88)))
                card.addView(text(video.title, 12f, 0xFFE7ECEF.toInt(), true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                strip.addView(card, LinearLayout.LayoutParams(dp(148), -2))
            }
            content.addView(horizontal)
        }

        sectionTitle("Folders")
        val folders = allVideos.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        if (folders.isEmpty()) {
            content.addView(text("No videos found. Allow video access or add videos to your device.", 14f, 0xFF8997A1.toInt()).apply { setPadding(0, dp(8), 0, dp(18)) })
        } else {
            folders.forEach { (name, videos) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(13), dp(14), dp(13))
                    setBackgroundColor(0xFF111A22.toInt())
                    setOnClickListener { selectedFolder = name; renderHome() }
                }
                val icon = text("▣", 25f, 0xFF20D66B.toInt(), true)
                row.addView(icon)
                val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
                labels.addView(text(name, 15f, 0xFFF0F4F6.toInt(), true))
                labels.addView(text("${videos.size} videos", 12f, 0xFF8997A1.toInt()))
                row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("›", 25f, 0xFF8997A1.toInt()))
                val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                content.addView(row, params)
            }
        }

        sectionTitle("All videos")
        allVideos.take(50).forEach { addVideoRow(it) }
    }

    private fun sectionTitle(value: String) {
        content.addView(text(value, 19f, 0xFFF1F5F7.toInt(), true).apply {
            setPadding(0, dp(24), 0, dp(12))
        })
    }

    private fun addVideoRow(video: VideoItem) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setBackgroundColor(0xFF111A22.toInt())
            setOnClickListener { openVideo(video) }
        }
        row.addView(thumbnail(video, dp(104), dp(66)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        labels.addView(text(video.title, 14f, 0xFFEAF0F3.toInt(), true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        labels.addView(text("${formatDuration(video.duration)}  •  ${video.folder}", 11f, 0xFF8997A1.toInt()).apply { setPadding(0, dp(5), 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
    }

    private fun thumbnail(video: VideoItem, width: Int, height: Int): ImageView {
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, height)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF202C35.toInt())
            try {
                if (Build.VERSION.SDK_INT >= 29) setImageBitmap(contentResolver.loadThumbnail(video.uri, Size(width, height), null))
                else setImageResource(android.R.drawable.ic_media_play)
            } catch (_: Exception) { setImageResource(android.R.drawable.ic_media_play) }
        }
    }

    private fun openVideo(video: VideoItem) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            data = video.uri
            putExtra("title", video.title)
        })
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return if (total >= 3600) String.format("%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
        else String.format("%d:%02d", total / 60, total % 60)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
