package com.darkroot.ytdownloader

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.darkroot.ytdownloader.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var isAudioMode = false

    private val progressHandler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        private const val SEEK_STEP_MS = 10_000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_URI)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: ""
        binding.playerTitle.text = title

        isAudioMode = mimeType.startsWith("audio/")
        if (isAudioMode) {
            binding.audioModeOverlay.visibility = View.VISIBLE
            binding.audioModeTitle.text = title
            setupAudioControls()
        }

        binding.backButton.setOnClickListener { finish() }

        if (uriString == null) {
            finish()
            return
        }

        initializePlayer(Uri.parse(uriString))
    }

    private fun initializePlayer(uri: Uri) {
        val exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        player = exoPlayer

        if (isAudioMode) {
            exoPlayer.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseIcon(isPlaying)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        val durationMs = exoPlayer.duration.coerceAtLeast(0L)
                        binding.audioSeekBar.max = (durationMs / 1000).toInt()
                        binding.audioDurationText.text = formatMillis(durationMs)
                    }
                }
            })
            startProgressUpdates()
        }
    }

    // ---------------- Audio controls ----------------
    private fun setupAudioControls() {
        binding.audioPlayPauseButton.setOnClickListener {
            val exoPlayer = player ?: return@setOnClickListener
            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
        }

        binding.audioSeekBackButton.setOnClickListener {
            val exoPlayer = player ?: return@setOnClickListener
            exoPlayer.seekTo((exoPlayer.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L))
        }

        binding.audioSeekForwardButton.setOnClickListener {
            val exoPlayer = player ?: return@setOnClickListener
            val target = exoPlayer.currentPosition + SEEK_STEP_MS
            val duration = exoPlayer.duration
            exoPlayer.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        }

        binding.audioSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.audioPositionText.text = formatMillis(progress * 1000L)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                val progress = seekBar?.progress ?: return
                player?.seekTo(progress * 1000L)
            }
        })
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        binding.audioPlayPauseButton.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val exoPlayer = player
            if (exoPlayer != null && !isUserSeeking) {
                val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                binding.audioSeekBar.progress = (positionMs / 1000).toInt()
                binding.audioPositionText.text = formatMillis(positionMs)
            }
            progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    private fun startProgressUpdates() {
        progressHandler.post(progressRunnable)
    }

    private fun formatMillis(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    // ---------------- Lifecycle ----------------
    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacks(progressRunnable)
        player?.release()
        player = null
    }
}
