package com.gleeshyyy.videoplayer

import android.Manifest
import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
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
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)

class MainActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var libraryScroll: ScrollView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var refreshStartedAt = 0L
    private lateinit var libraryBackground: ImageView
    private var selectedTab = 0
    private val palette get() = ThemeManager.palette(this)
    private var allVideos: List<VideoItem> = emptyList()
    private var selectedFolder: String? = null
    private var sortMode = 0
    private var sortAscending = false
    private val sortPrefs by lazy { getSharedPreferences("gleeshplayer_sort", MODE_PRIVATE) }
    private var searchQuery = ""
    private var searchPage = false
    private var displayedVideoLimit = 40
    private var libraryLoaded = false
    private var scanInProgress = false
    private val sortLabels = listOf("Longest", "A–Z", "Date modified")
    private val libraryExecutor = Executors.newSingleThreadExecutor()
    private var libraryLoadGeneration = 0
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val thumbnailHandler = Handler(Looper.getMainLooper())
    private val wallpaperExecutor = Executors.newSingleThreadExecutor()
    private var wallpaperRequest = 0
    private val thumbnailCache = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val thumbnailLoads = ConcurrentHashMap.newKeySet<String>()
    private val recentPrefs by lazy { getSharedPreferences("gleeshplayer_history", MODE_PRIVATE) }
    private var pendingWriteAction: (() -> Unit)? = null
    private var selectionMode = false
    private val selectedVideoUris = linkedSetOf<String>()
    private var selectionScopeVideos: List<VideoItem> = emptyList()
    private val deletePermissionRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            selectionMode = false
            selectedVideoUris.clear()
            selectionScopeVideos = emptyList()
            android.widget.Toast.makeText(this, "Deleted", android.widget.Toast.LENGTH_SHORT).show()
        }
        loadLibrary()
    }
    private val writePermissionRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) pendingWriteAction?.invoke()
        pendingWriteAction = null
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasVideoPermission()) {
            allVideos = readCachedVideos()
            libraryLoaded = true
            renderHome()
        }
        loadLibrary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applySystemMode(this)
        sortMode = sortPrefs.getInt("sort_mode", 2).coerceIn(0, 2)
        sortAscending = sortPrefs.getBoolean("sort_ascending", false)
        if (savedInstanceState != null) {
            selectedTab = savedInstanceState.getInt("library_tab", 0)
            selectedFolder = savedInstanceState.getString("library_folder")
            searchPage = savedInstanceState.getBoolean("library_search_page", false)
            searchQuery = savedInstanceState.getString("library_search_query", "")
            displayedVideoLimit = savedInstanceState.getInt("library_video_limit", 40)
        } else if (intent.getBooleanExtra("restore_library_context", false)) {
            restoreLibraryContext(intent)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    searchPage -> {
                        searchPage = false
                        selectedFolder = null
                        selectedTab = 0
                        renderHome()
                    }
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
        showVideoLibrary()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("restore_library_context", false)) {
            restoreLibraryContext(intent)
            if (::libraryScroll.isInitialized) {
                renderHome()
                libraryScroll.post { libraryScroll.scrollTo(0, intent.getIntExtra("return_scroll_y", 0).coerceAtLeast(0)) }
            }
        }
    }

    private fun restoreLibraryContext(source: Intent) {
        selectedFolder = source.getStringExtra("return_folder")
        selectedTab = source.getIntExtra("return_tab", 0).coerceIn(0, 1)
        searchPage = source.getBooleanExtra("return_search_page", false)
        searchQuery = source.getStringExtra("return_search_query") ?: ""
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("library_tab", selectedTab)
        outState.putString("library_folder", selectedFolder)
        outState.putBoolean("library_search_page", searchPage)
        outState.putString("library_search_query", searchQuery)
        outState.putInt("library_video_limit", displayedVideoLimit)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (libraryLoaded) {
            applyLibraryTheme()
            val settings = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
            if (settings.getBoolean("scan_settings_changed", false)) {
                settings.edit().putBoolean("scan_settings_changed", false).apply()
                loadLibrary()
            } else {
                val previousScroll = if (::libraryScroll.isInitialized) libraryScroll.scrollY else 0
                renderHome()
                if (::libraryScroll.isInitialized) {
                    libraryScroll.post { libraryScroll.scrollTo(0, previousScroll) }
                }
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
        val frame = FrameLayout(this)
        libraryBackground = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(palette.background)
        }
        frame.addView(libraryBackground, FrameLayout.LayoutParams(-1, -1))
        libraryScroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
        }
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(screen, FrameLayout.LayoutParams(-1, -1))
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(palette.accent)
            setOnRefreshListener {
                refreshStartedAt = System.currentTimeMillis()
                if (hasVideoPermission()) loadLibrary() else requestVideoPermission()
            }
        }
        swipeRefresh.addView(libraryScroll, ViewGroup.LayoutParams(-1, -1))
        screen.addView(swipeRefresh, LinearLayout.LayoutParams(-1, 0, 1f))
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(18))
        }
        libraryScroll.addView(content)
        screen.addView(createBottomNavigation(), LinearLayout.LayoutParams(-1, dp(62)))
        setContentView(frame)
        applyLibraryTheme()
        if (hasVideoPermission()) {
            allVideos = readCachedVideos()
            libraryLoaded = true
            renderHome()
            loadLibrary()
        } else requestVideoPermission()
    }


    private fun createBottomNavigation(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(palette.surface)
            elevation = dp(8).toFloat()
        }
        fun item(icon: String, label: String, tab: Int): LinearLayout {
            val active = selectedTab == tab
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(0, dp(5), 0, dp(4))
                val tint = if (active) palette.accent else palette.muted
                addView(TextView(this@MainActivity).apply {
                    text = icon
                    textSize = 21f
                    gravity = Gravity.CENTER
                    setTextColor(tint)
                }, LinearLayout.LayoutParams(-1, dp(27)))
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(tint)
                    if (active) setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                setOnClickListener {
                    if (selectedTab != tab || selectedFolder != null || searchPage) {
                        searchPage = false
                        selectedTab = tab
                        selectedFolder = null
                        val parent = bar.parent as? LinearLayout
                        if (parent != null) {
                            val oldIndex = parent.indexOfChild(bar)
                            parent.removeView(bar)
                            parent.addView(createBottomNavigation(), oldIndex, LinearLayout.LayoutParams(-1, dp(62)))
                        }
                        libraryScroll.scrollTo(0, 0)
                        renderHome()
                    }
                }
            }
        }
        bar.addView(item("▰", "Folders", 0), LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(item("▶", "Videos", 1), LinearLayout.LayoutParams(0, -1, 1f))
        return bar
    }

    private fun panelBackground(): Int {
        if (getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE).getString("theme_mode", "dark") != "picture") return palette.surface
        return if (palette.isDark) 0x66000000 else 0x66FFFFFF
    }

    private fun applyLibraryTheme() {
        val prefs = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
        val isPicture = prefs.getString("theme_mode", "dark") == "picture"
        val pictureUri = prefs.getString("theme_picture", null)
        val transparency = prefs.getInt("theme_transparency", 35).coerceIn(0, 100)
        val blurRadius = prefs.getInt("theme_blur", 0).coerceIn(0, 6).toFloat()
        val request = ++wallpaperRequest
        libraryBackground.setImageDrawable(null)
        libraryBackground.setBackgroundColor(palette.background)
        libraryBackground.alpha = 1f
        libraryScroll.setBackgroundColor(if (isPicture) Color.TRANSPARENT else palette.background)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val systemUi = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility = if (palette.isDark) {
            systemUi and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            systemUi or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        if (!isPicture || pictureUri.isNullOrBlank()) return
        // Decode and read the wallpaper on a worker thread, never on the UI thread.
        wallpaperExecutor.execute {
            val bitmap = ThemeManager.loadPicture(this, pictureUri)
            thumbnailHandler.post {
                if (isFinishing || isDestroyed || request != wallpaperRequest) return@post
                libraryBackground.setImageBitmap(bitmap)
                libraryBackground.alpha = (1f - transparency / 100f * .55f).coerceIn(.35f, 1f)
                ThemeManager.blur(libraryBackground, blurRadius)
            }
        }
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
        scanInProgress = true
        if (libraryLoaded) renderHome()
        if (!libraryLoaded) {
            content.removeAllViews()
            content.addView(text("Scanning your videos…", 15f, palette.muted).apply {
                setPadding(0, dp(28), 0, dp(12))
            })
        }
        libraryExecutor.execute {
            val videos = if (hasVideoPermission()) queryVideos() else emptyList()
            thumbnailHandler.post {
                if (isFinishing || isDestroyed || generation != libraryLoadGeneration) return@post
                allVideos = videos
                writeCachedVideos(videos)
                libraryLoaded = true
                scanInProgress = false
                if (selectedFolder != null && videos.none { it.folderPath == selectedFolder }) selectedFolder = null
                renderHome()
                if (swipeRefresh.isRefreshing) {
                    val remaining = (1200L - (System.currentTimeMillis() - refreshStartedAt)).coerceAtLeast(0L)
                    thumbnailHandler.postDelayed({ if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false }, remaining)
                }
            }
        }
    }

    private fun readCachedVideos(): List<VideoItem> {
        val raw = getSharedPreferences("gleeshplayer_cache", MODE_PRIVATE).getString("videos", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.getJSONObject(index)
                val uri = android.net.Uri.parse(item.getString("uri"))
                VideoItem(item.getLong("id"), item.getString("title"), uri, item.getLong("duration"),
                    item.getString("folder"), item.getString("folderPath"), item.getLong("dateAdded"),
                    item.optLong("dateModified", item.getLong("dateAdded")), item.optInt("width"), item.optInt("height"), item.optLong("sizeBytes"))
            }.filterNot { it.folderPath in (getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
                .getStringSet("excluded_scan_folders", emptySet()) ?: emptySet()) }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeCachedVideos(videos: List<VideoItem>) {
        val array = JSONArray()
        videos.take(2000).forEach { video ->
            array.put(org.json.JSONObject().apply {
                put("id", video.id); put("title", video.title); put("uri", video.uri.toString())
                put("duration", video.duration); put("folder", video.folder); put("folderPath", video.folderPath)
                put("dateAdded", video.dateAdded); put("dateModified", video.dateModified); put("width", video.width); put("height", video.height)
                put("sizeBytes", video.sizeBytes)
            })
        }
        getSharedPreferences("gleeshplayer_cache", MODE_PRIVATE).edit().putString("videos", array.toString()).apply()
    }

    private fun queryVideos(): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
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
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
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
                        cursor.getLong(modifiedCol),
                        if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                        if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                        if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    )
                }
            }
        } catch (_: SecurityException) { }
        val excludedFolders = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
            .getStringSet("excluded_scan_folders", emptySet()) ?: emptySet()
        return result.filterNot { it.folderPath in excludedFolders }
    }

    private fun renderHome() {
        content.removeAllViews()
        if (searchPage) {
            val searchHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val back = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_media_previous)
                imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                contentDescription = "Back to folders"
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { searchPage = false; searchQuery = ""; selectedFolder = null; selectedTab = 0; renderHome() }
            }
            searchHeader.addView(back, LinearLayout.LayoutParams(dp(56), dp(56)))
            val searchInput = android.widget.EditText(this).apply {
                hint = "Search videos and folders"
                setSingleLine(true)
                textSize = 16f
                setTextColor(palette.text)
                setHintTextColor(palette.muted)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackground(android.graphics.drawable.GradientDrawable().apply {
                    setColor(palette.raised)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), palette.muted)
                })
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setText(searchQuery)
                setSelection(text?.length ?: 0)
            }
            searchHeader.addView(searchInput, LinearLayout.LayoutParams(0, dp(48), 1f))
            content.addView(searchHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            if (selectionMode) addSelectionToolbar()
            val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(results, LinearLayout.LayoutParams(-1, -2))
            fun updateResults(query: String) {
                results.removeAllViews()
                if (query.isBlank()) {
                    results.addView(text("Search your videos by name or folder", 13f, palette.muted))
                    return
                }
                val matches = sortVideos(allVideos.filter {
                    it.title.contains(query, true) || it.folder.contains(query, true) || it.folderPath.contains(query, true)
                })
                if (matches.isEmpty()) results.addView(text("No matching videos found.", 14f, palette.muted))
                else matches.forEach { addVideoRow(it, matches, results) }
            }
            var pendingSearch: Runnable? = null
            updateResults(searchQuery)
            searchInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    pendingSearch?.let { thumbnailHandler.removeCallbacks(it) }
                    val querySnapshot = searchQuery
                    val task = Runnable { if (searchPage && searchInput.isAttachedToWindow && querySnapshot == searchQuery) updateResults(querySnapshot) }
                    pendingSearch = task
                    thumbnailHandler.postDelayed(task, 180L)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            return
        }
        val folder = selectedFolder
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(this).apply {
            text = android.text.SpannableString("GleeshPlayer").apply {
                setSpan(android.text.style.ForegroundColorSpan(palette.accent), 0, 6, 0)
                setSpan(android.text.style.ForegroundColorSpan(palette.brandText), 6, length, 0)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
            }
            textSize = 22f
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val searchIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            contentDescription = "Search"
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { searchPage = true; selectedFolder = null; selectedTab = 0; renderHome() }
        }
        header.addView(searchIcon, LinearLayout.LayoutParams(dp(48), dp(48)))
        val settings = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            contentDescription = "Settings"
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(header)
        if (selectionMode) addSelectionToolbar()

        if (folder != null) {
            val back = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(0, dp(8), dp(12), dp(8))
                contentDescription = "Back to all folders"
                setOnClickListener { selectedFolder = null; renderHome() }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_media_previous)
                    imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                    contentDescription = null
                    scaleType = ImageView.ScaleType.CENTER
                    setPadding(dp(4), dp(4), dp(8), dp(4))
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(text("ALL FOLDERS", 16f, palette.accent, true))
            }
            content.addView(back, LinearLayout.LayoutParams(-1, dp(58)))
            val folderVideos = allVideos.filter { it.folderPath == folder }
            val folderName = folderVideos.firstOrNull()?.folder ?: "Folder"
            content.addView(text(folderName, 26f, palette.text, true))
            val videos = sortVideos(folderVideos.filter {
                searchQuery.isBlank() || it.title.contains(searchQuery, true)
            })
            content.addView(text("${videos.size} videos", 13f, palette.muted).apply { setPadding(0, dp(5), 0, dp(16)) })
            videos.forEach { addVideoRow(it, videos) }
            return
        }

        if (scanInProgress) content.addView(text("Updating video library…", 11f, palette.muted).apply { setPadding(0, 0, 0, dp(14)) })

        if (searchQuery.isNotBlank()) {
            val matches = sortVideos(allVideos.filter {
                it.title.contains(searchQuery, true) || it.folder.contains(searchQuery, true)
            })
            sectionTitle("Search results")
            content.addView(text("${matches.size} matches", 12f, palette.muted))
            if (matches.isEmpty()) {
                content.addView(text("No videos match “$searchQuery”.", 14f, palette.muted).apply {
                    setPadding(0, dp(12), 0, dp(12))
                })
            } else {
                matches.forEach { addVideoRow(it, matches) }
            }
            return
        }

        if (selectedTab == 1) {
            addRecentlyPlayedSection()
            val videoHeading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(15), 0, dp(8))
            }
            videoHeading.addView(text("All videos · " + allVideos.size, 17f, palette.text, true), LinearLayout.LayoutParams(0, -2, 1f))
            videoHeading.addView(ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_sort_by_size)
                imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                contentDescription = "Sort videos"
                setPadding(dp(12), dp(8), dp(5), dp(8))
                setOnClickListener { showSortOptions() }
            }, LinearLayout.LayoutParams(dp(48), dp(44)))
            content.addView(videoHeading)
            val videos = sortVideos(allVideos.filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) || it.folder.contains(searchQuery, true) })
            if (videos.isEmpty()) content.addView(text("No videos found.", 14f, palette.muted).apply { setPadding(0, dp(8), 0, dp(18)) })
            else {
                videos.take(displayedVideoLimit).forEach { addVideoRow(it, videos) }
                if (videos.size > displayedVideoLimit) {
                    val remaining = videos.size - displayedVideoLimit
                    content.addView(android.widget.Button(this).apply {
                        text = "Load more ($remaining remaining)"
                        isAllCaps = false
                        setOnClickListener {
                            displayedVideoLimit = (displayedVideoLimit + 40).coerceAtMost(videos.size)
                            renderHome()
                        }
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }
            }
            return
        }

        addRecentlyPlayedSection()


        val folders = allVideos.groupBy { it.folderPath }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val folderHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(15), 0, dp(8))
        }
        folderHeading.addView(text("Folders · ${folders.size}", 17f, palette.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        folderHeading.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            contentDescription = "Sort folders and videos"
            setPadding(dp(12), dp(8), dp(5), dp(8))
            setOnClickListener { showSortOptions() }
        }, LinearLayout.LayoutParams(dp(48), dp(44)))
        content.addView(folderHeading)
        if (folders.isEmpty()) {
            content.addView(text("No videos found. Allow video access or add videos to your device.", 14f, palette.muted).apply { setPadding(0, dp(8), 0, dp(18)) })
        } else {
            folders.forEach { (path, videos) ->
                val name = videos.firstOrNull()?.folder ?: "Folder"
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(11), dp(9), dp(8), dp(9))
                    setBackgroundColor(if (selectionMode && videos.any { it.uri.toString() in selectedVideoUris }) selectionHighlight() else panelBackground())
                    setOnClickListener { if (selectionMode) toggleFolderSelection(path, videos) else { selectedFolder = path; renderHome() } }
                    setOnLongClickListener {
                        if (!selectionMode) { selectionScopeVideos = allVideos.distinctBy { it.uri }; selectionMode = true }
                        toggleFolderSelection(path, videos)
                        true
                    }
                }
                val icon = text("▣", 25f, palette.accent, true)
                row.addView(icon)
                val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
                labels.addView(text(name, 14f, palette.text, true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val parentPath = path.trimEnd('/').substringBeforeLast('/', "Internal storage").ifBlank { "Internal storage" }
                labels.addView(text(parentPath, 10f, palette.muted).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
                labels.addView(text("${videos.size} videos", 11f, palette.muted))
                row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(this).apply {
                    text = "⋮"
                    textSize = 21f
                    gravity = Gravity.CENTER
                    setTextColor(palette.muted)
                    contentDescription = "Folder options"
                    isClickable = true
                    isFocusable = true
                    minWidth = dp(40)
                    minHeight = dp(44)
                    setOnClickListener { showFolderOptions(path, videos) }
                }, LinearLayout.LayoutParams(dp(40), dp(44)))
                val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                content.addView(row, params)
            }
        }

    }


    private fun selectionHighlight(): Int = if (palette.isDark) 0x5530D878 else 0x4430A85F

    private fun toggleVideoSelection(video: VideoItem, scope: List<VideoItem>) {
        if (selectionScopeVideos.isEmpty()) selectionScopeVideos = scope.distinctBy { it.uri }
        val key = video.uri.toString()
        if (!selectedVideoUris.add(key)) selectedVideoUris.remove(key)
        if (selectedVideoUris.isEmpty()) { selectionMode = false; selectionScopeVideos = emptyList() }
        renderHome()
    }

    private fun toggleFolderSelection(path: String, videos: List<VideoItem>) {
        val keys = videos.map { it.uri.toString() }
        if (keys.all { it in selectedVideoUris }) selectedVideoUris.removeAll(keys.toSet()) else selectedVideoUris.addAll(keys)
        if (selectedVideoUris.isEmpty()) { selectionMode = false; selectionScopeVideos = emptyList() }
        renderHome()
    }

    private fun addSelectionToolbar() {
        val selected = allVideos.filter { it.uri.toString() in selectedVideoUris }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(palette.surface); cornerRadius = dp(12).toFloat(); setStroke(dp(1), selectionHighlight())
            }
        }
        bar.addView(text("${selected.size} selected  ·  ${formatFileSize(selected.sumOf { it.sizeBytes })}", 14f, palette.text, true))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun action(label: String, callback: () -> Unit) {
            actions.addView(TextView(this).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(palette.accent)
                setPadding(dp(5), dp(10), dp(5), dp(8)); isClickable = true; isFocusable = true; setOnClickListener { callback() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        action("Select All") {
            selectedVideoUris.addAll((selectionScopeVideos.ifEmpty { allVideos }).map { it.uri.toString() })
            renderHome()
        }
        action("Invert") {
            (selectionScopeVideos.ifEmpty { allVideos }).forEach { item ->
                val key = item.uri.toString()
                if (!selectedVideoUris.add(key)) selectedVideoUris.remove(key)
            }
            if (selectedVideoUris.isEmpty()) { selectionMode = false; selectionScopeVideos = emptyList() }
            renderHome()
        }
        action("Delete") {
            val chosen = allVideos.filter { it.uri.toString() in selectedVideoUris }
            if (chosen.isNotEmpty()) confirmDeleteVideos(chosen, "Delete ${chosen.size} selected videos?")
        }
        action("Cancel") {
            selectionMode = false; selectedVideoUris.clear(); selectionScopeVideos = emptyList(); renderHome()
        }
        bar.addView(actions)
        content.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun showThemedOptionsDialog(dialog: AlertDialog) {
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.surface))
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(palette.text)
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(palette.muted)
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
                dialog.getButton(which)?.setTextColor(palette.accent)
            }
            val list = dialog.listView
            for (index in 0 until list.childCount) {
                (list.getChildAt(index) as? TextView)?.setTextColor(palette.text)
            }
            list.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    (child as? TextView)?.setTextColor(palette.text)
                }
                override fun onChildViewRemoved(parent: View?, child: View?) {}
            })
        }
        dialog.show()
    }

    private fun showFolderOptions(path: String, videos: List<VideoItem>) {
        val name = videos.firstOrNull()?.folder ?: "Folder"
        val dialog = AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Share", "Rename", "Properties", "Hide from Scan list", "Delete")) { _, which ->
                when (which) {
                    0 -> shareVideos(videos)
                    1 -> promptRenameFolder(path, videos)
                    2 -> showFolderProperties(path, videos)
                    3 -> {
                        val prefs = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
                        val excluded = (prefs.getStringSet("excluded_scan_folders", emptySet()) ?: emptySet()).toMutableSet()
                        excluded.add(path)
                        prefs.edit().putStringSet("excluded_scan_folders", excluded).putBoolean("scan_settings_changed", false).apply()
                        allVideos = allVideos.filterNot { it.folderPath == path }
                        writeCachedVideos(allVideos)
                        selectedFolder = null
                        renderHome()
                    }
                    4 -> confirmDeleteVideos(videos, "Delete this folder and all its videos?")
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        showThemedOptionsDialog(dialog)
    }

    private fun showVideoOptions(video: VideoItem) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(video.title)
            .setItems(arrayOf("Share", "Rename", "Properties", "Delete")) { _, which ->
                when (which) {
                    0 -> shareVideos(listOf(video))
                    1 -> promptRenameVideo(video)
                    2 -> showVideoProperties(video)
                    3 -> confirmDeleteVideos(listOf(video), "Delete this video?")
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        showThemedOptionsDialog(dialog)
    }

    private fun confirmDeleteVideos(videos: List<VideoItem>, message: String) {
        if (videos.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("$message This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val uris = videos.map { it.uri }.distinct()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val request = MediaStore.createDeleteRequest(contentResolver, uris)
                        deletePermissionRequest.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(this, "Could not request delete permission", android.widget.Toast.LENGTH_LONG).show()
                    }
                } else {
                    try {
                        uris.forEach { contentResolver.delete(it, null, null) }
                        selectionMode = false
                        selectedVideoUris.clear()
                        selectionScopeVideos = emptyList()
                        android.widget.Toast.makeText(this, "Deleted", android.widget.Toast.LENGTH_SHORT).show()
                        loadLibrary()
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(this, "Delete failed", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun shareVideos(videos: List<VideoItem>) {
        if (videos.isEmpty()) return
        val uris = ArrayList(videos.map { it.uri })
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share video"))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Unable to share these videos", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptRenameVideo(video: VideoItem) {
        val input = android.widget.EditText(this).apply {
            setSingleLine(true)
            setText(video.title)
            setSelection(text.length)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this).setTitle("Rename video").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rename") { _, _ ->
                var newName = input.text.toString().trim()
                if (newName.isBlank()) return@setPositiveButton
                if (!newName.contains('.') && video.title.contains('.')) newName += "." + video.title.substringAfterLast('.')
                requestMediaWrite(listOf(video.uri)) {
                    try {
                        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                        contentResolver.update(video.uri, values, null, null)
                        loadLibrary()
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(this, "Rename failed", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun promptRenameFolder(path: String, videos: List<VideoItem>) {
        val oldName = videos.firstOrNull()?.folder ?: return
        val input = android.widget.EditText(this).apply {
            setSingleLine(true)
            setText(oldName)
            setSelection(text.length)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this).setTitle("Rename folder").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim().trim('/')
                if (newName.isBlank() || newName == oldName) return@setPositiveButton
                val parent = path.substringBeforeLast('/', "")
                val newPath = if (parent.isBlank()) "$newName/" else "$parent/$newName/"
                requestMediaWrite(videos.map { it.uri }) {
                    try {
                        videos.forEach { video ->
                            val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, newPath) }
                            contentResolver.update(video.uri, values, null, null)
                        }
                        loadLibrary()
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(this, "Folder rename failed", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun requestMediaWrite(uris: List<android.net.Uri>, action: () -> Unit) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                pendingWriteAction = action
                val request = MediaStore.createWriteRequest(contentResolver, uris)
                writePermissionRequest.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: Exception) {
                pendingWriteAction = null
                android.widget.Toast.makeText(this, "Could not request permission to edit this media", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            action()
        }
    }

    private fun showVideoProperties(video: VideoItem) {
        val resolution = if (video.width > 0 && video.height > 0) "${video.width} × ${video.height}" else "Unknown"
        val details = "Name: ${video.title}\nFolder: ${video.folderPath}\nDuration: ${formatDuration(video.duration)}\nResolution: $resolution\nSize: ${formatFileSize(video.sizeBytes)}"
        AlertDialog.Builder(this).setTitle("Video properties").setMessage(details).setPositiveButton("OK", null).show()
    }

    private fun showFolderProperties(path: String, videos: List<VideoItem>) {
        val totalSize = videos.sumOf { it.sizeBytes }
        val details = "Folder: ${videos.firstOrNull()?.folder ?: "Folder"}\nPath: $path\nVideos: ${videos.size}\nTotal size: ${formatFileSize(totalSize)}"
        AlertDialog.Builder(this).setTitle("Folder properties").setMessage(details).setPositiveButton("OK", null).show()
    }

    private fun sortVideos(videos: List<VideoItem>): List<VideoItem> {
        val sorted = when (sortMode) {
            0 -> videos.sortedBy { it.duration }
            1 -> videos.sortedBy { it.title.lowercase() }
            else -> videos.sortedBy { it.dateModified }
        }
        return if (sortAscending) sorted else sorted.asReversed()
    }

    private fun showSortOptions() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val choices = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        sortLabels.forEachIndexed { index, label ->
            val option = android.widget.RadioButton(this).apply {
                text = label
                textSize = 15f
                setTextColor(palette.text)
                buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                id = View.generateViewId()
                isChecked = sortMode == index
                tag = index
            }
            choices.addView(option)
        }
        choices.setOnCheckedChangeListener { group, checkedId ->
            val chosen = group.findViewById<android.widget.RadioButton>(checkedId)
            if (chosen != null) {
                sortMode = chosen.tag as Int
                sortPrefs.edit().putInt("sort_mode", sortMode).apply()
                renderHome()
            }
        }
        panel.addView(choices)
        panel.addView(text("Order", 13f, palette.muted, true).apply { setPadding(0, dp(12), 0, dp(6)) })
        val orderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ascending = android.widget.Button(this).apply { text = "Ascending"; isAllCaps = false }
        val descending = android.widget.Button(this).apply { text = "Descending"; isAllCaps = false }
        fun updateOrderButtons() {
            val activeColor = android.content.res.ColorStateList.valueOf(palette.accent)
            val inactiveColor = android.content.res.ColorStateList.valueOf(palette.raised)
            ascending.backgroundTintList = if (sortAscending) activeColor else inactiveColor
            descending.backgroundTintList = if (sortAscending) inactiveColor else activeColor
            ascending.setTextColor(if (sortAscending) Color.BLACK else palette.text)
            descending.setTextColor(if (sortAscending) palette.text else Color.BLACK)
            ascending.alpha = 1f
            descending.alpha = 1f
        }
        ascending.setOnClickListener {
            sortAscending = true
            sortPrefs.edit().putBoolean("sort_ascending", true).apply()
            updateOrderButtons()
            renderHome()
        }
        descending.setOnClickListener {
            sortAscending = false
            sortPrefs.edit().putBoolean("sort_ascending", false).apply()
            updateOrderButtons()
            renderHome()
        }
        orderRow.addView(ascending, LinearLayout.LayoutParams(0, -2, 1f))
        orderRow.addView(descending, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(orderRow)
        updateOrderButtons()
        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(text("Sort videos", 20f, palette.text, true))
            .setView(panel)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.background))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(palette.accent)
        }
        dialog.show()
    }

    private fun addRecentlyPlayedSection() {
        if (!getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE).getBoolean("show_history", true)) return
        sectionTitle("Recently played")
        val recentIds = recentPrefs.getString("recent", "[]") ?: "[]"
        val recentUris = try {
            JSONArray(recentIds).let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        } catch (_: Exception) { emptyList() }
        val recent = recentUris.mapNotNull { uri -> allVideos.firstOrNull { it.uri.toString() == uri } }
            .distinctBy { it.uri }.take(10)
        if (recent.isEmpty()) {
            content.addView(text("Your recently watched videos will appear here.", 13f, palette.muted).apply {
                setPadding(0, dp(4), 0, dp(14))
            })
        } else {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val horizontal = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            }
            recent.forEach { video ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, dp(12), 0)
                    setOnClickListener { openVideo(video, sortVideos(allVideos)) }
                }
                card.addView(thumbnail(video, dp(104), dp(62)))
                card.addView(text(video.title, 12f, palette.text, true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                strip.addView(card, LinearLayout.LayoutParams(dp(104), -2))
            }
            content.addView(horizontal)
        }
    }

    private fun sectionTitle(value: String) {
        content.addView(text(value, 17f, palette.text, true).apply {
            setPadding(0, dp(15), 0, dp(8))
        })
    }

    private fun addVideoRow(video: VideoItem, playlist: List<VideoItem> = sortVideos(allVideos), target: LinearLayout = content) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(8), dp(9))
            setBackgroundColor(if (selectionMode && video.uri.toString() in selectedVideoUris) selectionHighlight() else panelBackground())
            setOnClickListener { if (selectionMode) toggleVideoSelection(video, playlist) else openVideo(video, playlist) }
            setOnLongClickListener {
                if (!selectionMode) { selectionScopeVideos = playlist.distinctBy { it.uri }; selectionMode = true }
                toggleVideoSelection(video, playlist)
                true
            }
        }
        row.addView(thumbnail(video, dp(104), dp(66)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        val titleLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleLine.addView(text(video.title, 14f, palette.text, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val hasSavedSrt = getSharedPreferences("gleeshplayer_settings", MODE_PRIVATE)
            .contains("external_subtitle_" + video.uri.toString())
        if (hasSavedSrt) {
            titleLine.addView(TextView(this).apply {
                text = "SRT"
                textSize = 8f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF159447.toInt())
                    cornerRadius = dp(3).toFloat()
                }
                contentDescription = "External SRT subtitle saved"
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
        }
        labels.addView(titleLine)
        val details = mutableListOf(formatDuration(video.duration))
        val resolution = video.width.coerceAtLeast(0).takeIf { it > 0 }?.let { "${it}×${video.height}" }
        if (resolution != null) details.add(resolution)
        if (video.sizeBytes > 0L) details.add(formatFileSize(video.sizeBytes))
        details.add(video.folder)
        labels.addView(text(details.joinToString("  •  "), 11f, palette.muted).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "⋮"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
            contentDescription = "Video options"
            isClickable = true
            isFocusable = true
            minWidth = dp(40)
            minHeight = dp(44)
            setOnClickListener { showVideoOptions(video) }
        }, LinearLayout.LayoutParams(dp(40), dp(44)))
        target.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
    }

    private fun thumbnail(video: VideoItem, width: Int, height: Int): FrameLayout {
        val cacheKey = "${video.uri}|${width}x${height}"
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, height)
            setBackgroundColor(palette.raised)
            clipChildren = true
        }
        val image = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_media_play)
            tag = cacheKey
        }
        frame.addView(image)
        val history = getSharedPreferences("gleeshplayer_history", MODE_PRIVATE)
        val watchedPosition = history.getLong("position_${video.uri}", 0L).coerceAtLeast(0L)
        if (video.duration > 0L && watchedPosition > 1000L && watchedPosition < video.duration - 1500L) {
            val track = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = (watchedPosition * 100L / video.duration).toInt().coerceIn(0, 100)
                progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x99000000.toInt())
            }
            frame.addView(track, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
        }
        val cached = thumbnailCache.get(cacheKey)
        if (cached != null) {
            image.setImageBitmap(cached)
        } else if (Build.VERSION.SDK_INT >= 29 && thumbnailLoads.add(cacheKey)) {
            thumbnailExecutor.execute {
                val bitmap = try {
                    contentResolver.loadThumbnail(video.uri, Size(width, height), null)
                } catch (_: Exception) { null }
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
        return frame
    }

    override fun onDestroy() {
        libraryLoadGeneration++
        wallpaperRequest++
        wallpaperExecutor.shutdownNow()
        libraryExecutor.shutdownNow()
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun openVideo(video: VideoItem, playlist: List<VideoItem> = sortVideos(allVideos)) {
        val ordered = playlist.ifEmpty { listOf(video) }
        val safeIndex = ordered.indexOfFirst { it.uri == video.uri }.let { if (it < 0) 0 else it }
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            data = video.uri
            putExtra("title", video.title)
            putParcelableArrayListExtra("playlist_uris", ArrayList(ordered.map { it.uri }))
            putStringArrayListExtra("playlist_titles", ArrayList(ordered.map { it.title }))
            putExtra("playlist_index", safeIndex)
            putExtra("return_folder", selectedFolder)
            putExtra("return_tab", selectedTab)
            putExtra("return_search_page", searchPage)
            putExtra("return_search_query", searchQuery)
            putExtra("return_scroll_y", if (::libraryScroll.isInitialized) libraryScroll.scrollY else 0)
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
