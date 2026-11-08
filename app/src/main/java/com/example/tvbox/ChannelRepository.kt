package com.example.tvbox

import android.content.Context
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class ChannelRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)
    private val catalog by lazy {
        context.resources.openRawResource(R.raw.ysp_channels).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val (slug, title, sid, pid, quality) = line.split('\t')
                YspChannel(slug, title, sid, pid, quality)
            }.associateBy { it.slug }
        }
    }

    private val guid by lazy {
        prefs.getString("ysp_guid", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString("ysp_guid", it).apply()
        }
    }

    internal val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .apply { LegacyTrust.configurePlaylistClient(context, this) }
        .build()

    private val sourceClient = httpClient.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    internal val channels: List<Channel> by lazy {
        catalog.values.map {
            Channel(it.title, "ysp://${it.slug}", "https://garysclub.sharewithyou.dpdns.org/logos/ysp-live-logos/${it.slug}.png")
        }
    }

    private var live: YspLiveSource? = null

    internal fun playbackUrl(channel: Channel): String {
        val source = catalog[channel.url.removePrefix("ysp://")] ?: throw IOException("Unknown live channel")
        live?.takeIf { it.channel.slug == source.slug }?.let { return it.url }
        stopLive()
        return YspLiveSource(source, sourceClient, guid).also { live = it }.url
    }

    internal fun stopLive() {
        live?.close()
        live = null
    }

    fun saveLastPlayedChannel(title: String, url: String) {
        prefs.edit()
            .putString("last_played_title", title)
            .putString("last_played_url", url)
            .apply()
    }

    fun getLastPlayedUrl(): String? = prefs.getString("last_played_url", null)
    fun getLastPlayedTitle(): String? = prefs.getString("last_played_title", null)
}
