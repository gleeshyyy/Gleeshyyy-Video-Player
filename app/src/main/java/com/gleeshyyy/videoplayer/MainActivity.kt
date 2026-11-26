package com.gleeshyyy.videoplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var emptyMessage: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var statusLabel: TextView

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { playVideo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        emptyMessage = findViewById(R.id.emptyMessage)
        nowPlaying = findViewById(R.id.nowPlaying)
        statusLabel = findViewById(R.id.statusLabel)

        findViewById<MaterialButton>(R.id.browseButton).setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }
    }

    private fun playVideo(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable permissions.
        }

        if (player == null) {
            player = ExoPlayer.Builder(this).build().also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        statusLabel.text = when (playbackState) {
                            Player.STATE_BUFFERING -> "LOADING"
                            Player.STATE_READY -> if (exo.isPlaying) "PLAYING" else "READY"
                            Player.STATE_ENDED -> "FINISHED"
                            else -> "READY"
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        statusLabel.text = if (isPlaying) "PLAYING" else "PAUSED"
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        statusLabel.text = "PLAYBACK ERROR"
                        nowPlaying.text = "This video could not be played"
                    }
                })
            }
        }

        emptyMessage.visibility = android.view.View.GONE
        nowPlaying.text = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected video"
        statusLabel.text = "LOADING"
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
        player?.play()
    }

    override fun onStart() {
        super.onStart()
        playerView.onResume()
    }

    override fun onStop() {
        playerView.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
