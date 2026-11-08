package com.example.tvbox

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.IOException

class MainActivity : FragmentActivity() {

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private var hlsPlayback: HlsPlayback? = null
    private lateinit var videoContainer: FrameLayout
    private lateinit var playbackStatus: TextView
    private lateinit var playingTitle: TextView
    private lateinit var fullscreenButton: View
    private lateinit var insetsController: WindowInsetsControllerCompat

    private var started = false
    private var initialFocusPending = true
    private var currentChannel: Channel? = null
    private var retryCount = 0
    private var softwareDecoder = false
    private var useVlcForHls = false
    private var sourceRefreshAttempted = false
    private var playingSince = 0L
    private var playbackSession = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val fullscreenBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFullscreen(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        videoContainer = findViewById(R.id.vlc_preview_container)
        playbackStatus = findViewById(R.id.playback_status)
        playingTitle = findViewById(R.id.playing_title)
        fullscreenButton = findViewById(R.id.fullscreen_button)
        insetsController = WindowInsetsControllerCompat(window, videoContainer).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, fullscreenBack)

        fullscreenButton.setOnClickListener { setFullscreen(true) }
        videoContainer.setOnClickListener { setFullscreen(!fullscreenBack.isEnabled) }
        videoContainer.isFocusable = false
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.list_container, MainFragment())
                .commitNow()
        }
    }

    private fun initializeVlc() {
        val options = arrayListOf(
            "--network-caching=3000",
            "--live-caching=1500",
            "--file-caching=2000",
            "--rtsp-tcp",
            "--drop-late-frames",
            "--skip-frames",
            "--avcodec-hw=any",
            "--avcodec-fast",
            "--avcodec-threads=4",
            "--no-stats",
            "--no-video-title-show",
            "--no-osd",
            "--avcodec-skiploopfilter=4",
            "--http-reconnect"
        )
        LegacyTrust.vlcTrustOption(this)?.let(options::add)

        try {
            libVLC = LibVLC(this, options)
            mediaPlayer = MediaPlayer(libVLC).apply {
                setEventListener { event ->
                    if (!started || hlsPlayback != null) return@setEventListener
                    when (event.type) {
                        MediaPlayer.Event.Playing -> markPlaying("VLC")
                        MediaPlayer.Event.EncounteredError -> retryPlayback(decoderError = true)
                        MediaPlayer.Event.EndReached -> retryPlayback(decoderError = false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize LibVLC", e)
            showPlaybackStatus(R.string.player_unavailable)
        }
    }

    fun showChannelsState(count: Int) {
        findViewById<TextView>(R.id.channel_summary).text = getString(R.string.channel_count, count)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusInitialChannel()
    }

    internal fun focusInitialChannel() {
        if (!initialFocusPending || !hasWindowFocus()) return
        val list = supportFragmentManager.findFragmentById(R.id.list_container) as? MainFragment
        if (list?.focusPlayingChannel() == true) initialFocusPending = false
    }

    fun playChannel(channel: Channel) {
        if (channel.url == currentChannel?.url &&
            (hlsPlayback?.isPlaying == true || mediaPlayer?.isPlaying == true)
        ) return
        mainHandler.removeCallbacksAndMessages(null)
        playbackSession++
        retryCount = 0
        softwareDecoder = false
        useVlcForHls = false
        sourceRefreshAttempted = false
        rememberChannel(channel)
        startPlayback()
    }

    private fun rememberChannel(channel: Channel) {
        val previous = currentChannel
        currentChannel = channel
        playingTitle.text = channel.title
        if (previous != null && previous.url != channel.url && playingTitle.isShown) {
            playingTitle.animate().cancel()
            playingTitle.alpha = 0.55f
            playingTitle.animate().alpha(1f).setDuration(150).start()
        }
        MainApplication.channelRepository.saveLastPlayedChannel(channel.title, channel.url)
    }

    private fun startPlayback(status: Int = R.string.playback_connecting) {
        val channel = currentChannel ?: return
        if (!started) return
        playingSince = 0L
        showPlaybackStatus(status)
        Log.d("MainActivity", "Playing: ${channel.url}")

        val uri = try {
            Uri.parse(MainApplication.channelRepository.playbackUrl(channel))
        } catch (e: IOException) {
            Log.w("MainActivity", "Could not start live source for ${channel.url}", e)
            retryPlayback(decoderError = false)
            return
        }
        // Android 5.x 的旧解码器在部分 1080p 频道上会卡在初始化阶段。
        if (!useVlcForHls && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startHlsPlayback(uri)
                return
            } catch (e: Exception) {
                Log.w("MainActivity", "Could not start Media3, falling back to VLC", e)
                useVlcForHls = true
            }
        }

        releaseHlsPlayback()
        if (mediaPlayer == null) initializeVlc()
        val player = mediaPlayer ?: run {
            showPlaybackStatus(R.string.player_unavailable)
            return
        }
        val layout = videoLayout ?: VLCVideoLayout(this).also {
            videoLayout = it
            videoContainer.addView(it, 0)
        }
        layout.visibility = View.VISIBLE

        try {
            if (player.hasMedia()) player.stop()
            val media = Media(libVLC, uri)
            try {
                // 设置在硬解选项之前，避免 LibVLC 将缓存改为 1500 毫秒。
                media.addOption(":network-caching=5000")
                media.setHWDecoderEnabled(!softwareDecoder, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                player.media = media
            } finally {
                media.release()
            }

            if (!player.vlcVout.areViewsAttached()) {
                player.attachViews(layout, null, true, false)
            }
            player.play()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error playing video", e)
            retryPlayback(decoderError = true)
        }
    }

    private fun startHlsPlayback(uri: Uri) {
        val playback = hlsPlayback ?: run {
            mediaPlayer?.stop()
            mediaPlayer?.detachViews()
            videoLayout?.visibility = View.GONE
            HlsPlayback(this, MainApplication.channelRepository.httpClient, object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (started && isPlaying) markPlaying("Media3")
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (started && playbackState == Player.STATE_ENDED) retryPlayback(decoderError = false)
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w("MainActivity", "Media3 playback failed, falling back to VLC", error)
                    val session = playbackSession
                    mainHandler.post {
                        if (started && session == playbackSession && hlsPlayback != null) {
                            useVlcForHls = true
                            startPlayback(R.string.playback_retrying)
                        }
                    }
                }
            }).also {
                hlsPlayback = it
                videoContainer.addView(it.view, 0)
            }
        }
        playback.play(uri)
    }

    private fun markPlaying(engine: String) {
        if (playingSince == 0L) playingSince = SystemClock.elapsedRealtime()
        playbackStatus.visibility = View.GONE
        Log.d("MainActivity", "$engine: Playing")
    }

    private fun releaseHlsPlayback() {
        val playback = hlsPlayback ?: return
        hlsPlayback = null
        playback.release()
        videoContainer.removeView(playback.view)
    }

    private fun retryPlayback(decoderError: Boolean) {
        if (!started || currentChannel == null) return
        mainHandler.removeCallbacksAndMessages(null)
        if (playingSince != 0L && SystemClock.elapsedRealtime() - playingSince >= 15000) {
            retryCount = 0
            sourceRefreshAttempted = false
        }
        playingSince = 0L
        showPlaybackStatus(R.string.playback_retrying)

        if (retryCount < 3) {
            retryCount++
            if (decoderError && retryCount >= 2) softwareDecoder = true
            mainHandler.postDelayed({ startPlayback(R.string.playback_retrying) }, 3000)
            return
        }

        if (sourceRefreshAttempted) {
            MainApplication.channelRepository.stopLive()
            showPlaybackStatus(R.string.playback_unavailable)
            return
        }

        sourceRefreshAttempted = true
        MainApplication.channelRepository.stopLive()
        startPlayback(R.string.playback_retrying)
    }

    private fun showPlaybackStatus(message: Int) {
        playbackStatus.setText(message)
        playbackStatus.visibility = View.VISIBLE
    }

    private fun setFullscreen(enabled: Boolean) {
        fullscreenBack.isEnabled = enabled
        val visibility = if (enabled) View.GONE else View.VISIBLE
        findViewById<View>(R.id.channel_panel).visibility = visibility
        findViewById<View>(R.id.playback_header).visibility = visibility
        val padding = if (enabled) 0 else resources.getDimensionPixelSize(R.dimen.live_padding)
        findViewById<View>(R.id.live_layout).setPadding(padding, padding, padding, padding)
        val inset = if (enabled) 0 else resources.getDimensionPixelSize(R.dimen.preview_inset)
        videoContainer.setBackgroundResource(if (enabled) android.R.color.black else R.drawable.preview_frame)
        videoContainer.setPadding(inset, inset, inset, inset)
        videoContainer.isFocusable = enabled

        if (enabled) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            videoContainer.requestFocus()
            Toast.makeText(this, R.string.fullscreen_hint, Toast.LENGTH_SHORT).show()
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            fullscreenButton.requestFocus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (fullscreenBack.isEnabled && keyCode == KeyEvent.KEYCODE_ESCAPE) {
            setFullscreen(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        started = true
        retryCount = 0
        sourceRefreshAttempted = false
        if (currentChannel != null) startPlayback()
    }

    override fun onStop() {
        started = false
        playbackSession++
        mainHandler.removeCallbacksAndMessages(null)
        MainApplication.channelRepository.stopLive()
        releaseHlsPlayback()
        mediaPlayer?.stop()
        mediaPlayer?.detachViews()
        super.onStop()
    }

    override fun onDestroy() {
        releaseHlsPlayback()
        mediaPlayer?.setEventListener(null)
        mediaPlayer?.release()
        libVLC?.release()
        super.onDestroy()
    }
}
