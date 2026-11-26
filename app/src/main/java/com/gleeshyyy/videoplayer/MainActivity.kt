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
import android.util.LruCache
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class VideoItem(
    val id: Long,
    val title: String,
    val uri: android.net.Uri,
    val duration: Long,
    val folder: String,
    val folderPath: String,
    val dateAdded: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)

class MainActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var libraryScroll: ScrollView
    private var allVideos: List<VideoItem> = emptyList()
    private var selectedFolder: String? = null
    private var sortMode = 0
    private var searchQuery = ""
    private var libraryLoaded = false
    private val sortLabels = listOf("Recent", "A–Z", "Longest")
    private val libraryExecutor = Executors.newSingleThreadExecutor()
    private var libraryLoadGeneration = 0
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val thumbnailHandler = Handler(Looper.getMainLooper())
    private val thumbnailCache = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val thumbnailLoads = ConcurrentHashMap.newKeySet<String>()
    private val recentPrefs by lazy { getSharedPreferences("gleeshplayer_history", MODE_PRIVATE) }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        loadLibrary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selectedFolder != null -> {
                        selectedFolder = null
                        renderHome()
                    }
                    searchQuery.isNotBlank() -> {
                        searchQuery = ""
                        renderHome()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        showBrandSplash()
    }

    override fun onResume() {
        super.onResume()
        if (libraryLoaded) {
            val previousScroll = if (::libraryScroll.isInitialized) libraryScroll.scrollY else 0
            renderHome()
            if (::libraryScroll.isInitialized) {
                libraryScroll.post { libraryScroll.scrollTo(0, previousScroll) }
            }
        }
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
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_FULLSCREEN or
             View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
             View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        setContentView(splash)
        Handler(Looper.getMainLooper()).postDelayed({
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            showVideoLibrary()
        }, 2500L)
    }

    private fun showVideoLibrary() {
        libraryScroll = ScrollView(this).apply {
            setBackgroundColor(0xFF080D12.toInt())
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(30))
        }
        libraryScroll.addView(content)
        setContentView(libraryScroll)
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
        val generation = ++libraryLoadGeneration
        content.removeAllViews()
        content.addView(text("Scanning your videos…", 15f, 0xFF8997A1.toInt()).apply {
            setPadding(0, dp(28), 0, dp(12))
        })
        libraryExecutor.execute {
            val videos = if (hasVideoPermission()) queryVideos() else emptyList()
            thumbnailHandler.post {
                if (isFinishing || isDestroyed || generation != libraryLoadGeneration) return@post
                allVideos = videos
                libraryLoaded = true
                selectedFolder = null
                renderHome()
            }
        }
    }

    private fun queryVideos(): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE
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
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val path = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else "Videos/"
                    val folderPath = path.ifBlank { "Internal storage/" }
                    val folder = folderPath.trimEnd('/').substringAfterLast('/').ifBlank { "Internal storage" }
                    result += VideoItem(
                        id,
                        cursor.getString(nameCol) ?: "Untitled video",
                        android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        cursor.getLong(durationCol),
                        folder,
                        folderPath,
                        cursor.getLong(dateCol),
                        if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                        if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                        if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
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
        val sort = TextView(this).apply {
            text = "↕ ${sortLabels[sortMode]}"
            textSize = 12f
            setTextColor(0xFF20D66B.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                sortMode = (sortMode + 1) % sortLabels.size
                renderHome()
            }
        }
        header.addView(sort)
        val refresh = TextView(this).apply {
            text = "↻"
            textSize = 27f
            setTextColor(0xFF20D66B.toInt())
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { if (hasVideoPermission()) loadLibrary() else requestVideoPermission() }
        }
        header.addView(refresh)
        content.addView(header)

        val searchBox = android.widget.EditText(this).apply {
            hint = "Search videos or folders"
            setSingleLine(true)
            textSize = 14f
            setTextColor(0xFFF1F5F7.toInt())
            setHintTextColor(0xFF8997A1.toInt())
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setBackgroundColor(0xFF111A22.toInt())
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setText(searchQuery)
            setSelection(text.length)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    searchQuery = text.toString().trim()
                    renderHome()
                    true
                } else false
            }
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(searchBox, LinearLayout.LayoutParams(0, -2, 1f))
            val clearSearch = TextView(this@MainActivity).apply {
                text = "✕"
                textSize = 18f
                setTextColor(0xFF20D66B.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(4), dp(10))
                setOnClickListener {
                    searchQuery = ""
                    renderHome()
                }
            }
            addView(clearSearch)
        }
        content.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(14)
        })

        if (folder != null) {
            val back = text("‹  ALL FOLDERS", 13f, 0xFF20D66B.toInt(), true).apply {
                setPadding(0, dp(22), 0, dp(8))
                setOnClickListener { selectedFolder = null; renderHome() }
            }
            content.addView(back)
            val folderVideos = allVideos.filter { it.folderPath == folder }
            val folderName = folderVideos.firstOrNull()?.folder ?: "Folder"
            content.addView(text(folderName, 26f, 0xFFF1F5F7.toInt(), true))
            val videos = sortVideos(folderVideos.filter {
                searchQuery.isBlank() || it.title.contains(searchQuery, true)
            })
            content.addView(text("${videos.size} videos", 13f, 0xFF8997A1.toInt()).apply { setPadding(0, dp(5), 0, dp(16)) })
            videos.forEach { addVideoRow(it) }
            return
        }

        content.addView(text("Your videos", 29f, 0xFFF1F5F7.toInt(), true).apply { setPadding(0, dp(25), 0, dp(2)) })
        content.addView(text("${allVideos.size} videos on this device", 13f, 0xFF8997A1.toInt()).apply { setPadding(0, 0, 0, dp(20)) })

        if (searchQuery.isNotBlank()) {
            val matches = sortVideos(allVideos.filter {
                it.title.contains(searchQuery, true) || it.folder.contains(searchQuery, true)
            })
            sectionTitle("Search results")
            content.addView(text("${matches.size} matches", 12f, 0xFF8997A1.toInt()))
            if (matches.isEmpty()) {
                content.addView(text("No videos match “$searchQuery”.", 14f, 0xFF8997A1.toInt()).apply {
                    setPadding(0, dp(12), 0, dp(12))
                })
            } else {
                matches.forEach { addVideoRow(it) }
            }
            return
        }

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
        val folders = allVideos.groupBy { it.folderPath }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        if (folders.isEmpty()) {
            content.addView(text("No videos found. Allow video access or add videos to your device.", 14f, 0xFF8997A1.toInt()).apply { setPadding(0, dp(8), 0, dp(18)) })
        } else {
            folders.forEach { (path, videos) ->
                val name = videos.firstOrNull()?.folder ?: "Folder"
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(13), dp(14), dp(13))
                    setBackgroundColor(0xFF111A22.toInt())
                    setOnClickListener { selectedFolder = path; renderHome() }
                }
                val icon = text("▣", 25f, 0xFF20D66B.toInt(), true)
                row.addView(icon)
                val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
                labels.addView(text(name, 15f, 0xFFF0F4F6.toInt(), true))
                val parentPath = path.trimEnd('/').substringBeforeLast('/', "Internal storage").ifBlank { "Internal storage" }
                labels.addView(text(parentPath, 11f, 0xFF8997A1.toInt()))
                labels.addView(text("${videos.size} videos", 12f, 0xFF8997A1.toInt()))
                row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("›", 25f, 0xFF8997A1.toInt()))
                val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                content.addView(row, params)
            }
        }

        sectionTitle("All videos")
        sortVideos(allVideos).forEach { addVideoRow(it) }
    }

    private fun sortVideos(videos: List<VideoItem>): List<VideoItem> = when (sortMode) {
        1 -> videos.sortedBy { it.title.lowercase() }
        2 -> videos.sortedByDescending { it.duration }
        else -> videos.sortedByDescending { it.dateAdded }
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
        val details = mutableListOf(formatDuration(video.duration))
        val resolution = video.width.coerceAtLeast(0).takeIf { it > 0 }?.let { "${it}×${video.height}" }
        if (resolution != null) details.add(resolution)
        if (video.sizeBytes > 0L) details.add(formatFileSize(video.sizeBytes))
        details.add(video.folder)
        labels.addView(text(details.joinToString("  •  "), 11f, 0xFF8997A1.toInt()).apply { setPadding(0, dp(5), 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
    }

    private fun thumbnail(video: VideoItem, width: Int, height: Int): ImageView {
        val cacheKey = "${video.uri}|${width}x${height}"
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, height)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF202C35.toInt())
            setImageResource(android.R.drawable.ic_media_play)
            tag = cacheKey
        }
        val cached = thumbnailCache.get(cacheKey)
        if (cached != null) {
            image.setImageBitmap(cached)
        } else if (Build.VERSION.SDK_INT >= 29 && thumbnailLoads.add(cacheKey)) {
            thumbnailExecutor.execute {
                val bitmap = try {
                    contentResolver.loadThumbnail(video.uri, Size(width, height), null)
                } catch (_: Exception) {
                    null
                }
                if (bitmap != null) thumbnailCache.put(cacheKey, bitmap)
                thumbnailLoads.remove(cacheKey)
                thumbnailHandler.post {
                    if (image.tag == cacheKey) {
                        if (bitmap != null) image.setImageBitmap(bitmap)
                        else image.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
            }
        }
        return image
    }

    override fun onDestroy() {
        libraryLoadGeneration++
        libraryExecutor.shutdownNow()
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
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

    private fun formatFileSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format("%.1f GB", mb / 1024.0)
        else if (mb >= 1.0) String.format("%.1f MB", mb)
        else String.format("%.0f KB", bytes / 1024.0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
