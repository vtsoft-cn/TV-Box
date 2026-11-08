package com.example.tvbox

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
internal class HlsPlayback(context: Context, client: OkHttpClient, private val listener: Player.Listener) {

    val view = PlayerView(context).apply {
        useController = false
        isFocusable = false
    }

    private val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(HlsMediaSource.Factory(OkHttpDataSource.Factory(client)))
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .build()
        .apply { addListener(listener) }

    init {
        view.player = player
    }

    val isPlaying get() = player.isPlaying

    fun play(uri: Uri) {
        val item = MediaItem.Builder()
            .setUri(uri)
            // 由 HLS 播放列表决定起播位置，保持直播声音按原速播放。
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMinPlaybackSpeed(1f)
                    .setMaxPlaybackSpeed(1f)
                    .build()
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    fun release() {
        player.removeListener(listener)
        view.player = null
        player.release()
    }
}
