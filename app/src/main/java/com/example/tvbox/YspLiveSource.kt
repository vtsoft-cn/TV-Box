package com.example.tvbox

import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class YspChannel(
    val slug: String,
    val title: String,
    val sid: String,
    val pid: String,
    val quality: String
)

internal data class YspResponse(val url: HttpUrl, val bytes: ByteArray) {
    fun text() = bytes.toString(Charsets.UTF_8)
}

/** Lets both Media3 and VLC reload a live manifest. Only the manifest passes through loopback. */
internal class YspLiveSource(
    val channel: YspChannel,
    client: OkHttpClient,
    guid: String
) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val path = "/${UUID.randomUUID()}/${channel.slug}.m3u8"
    val url = "http://127.0.0.1:${server.localPort}$path"

    @Volatile private var closed = false
    @Volatile private var socket: Socket? = null
    @Volatile private var inFlight: Call? = null

    private val playlist = YspPlaylist(channel, YspProtocol(guid, ::fetch), ::fetch, ::probeSegment)

    private val httpClient = client

    init {
        thread(name = "YspPlaylist", isDaemon = true) {
            while (!closed) {
                val connection = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                connection.use {
                    socket = it
                    try {
                        if (!closed) serve(it)
                    } catch (_: IOException) {
                        // A player may cancel a reload when switching channels.
                    } finally {
                        socket = null
                    }
                }
            }
        }
    }

    override fun close() {
        closed = true
        inFlight?.cancel()
        runCatching { socket?.close() }
        runCatching { server.close() }
    }

    private fun serve(connection: Socket) {
        connection.soTimeout = 2500
        val input = connection.source().buffer()
        val request = input.readUtf8LineStrict(4096).split(' ', limit = 3)
        var headerEnded = false
        for (i in 0 until 40) {
            if (input.readUtf8LineStrict(4096).isEmpty()) {
                headerEnded = true
                break
            }
        }
        if (!headerEnded) return
        val method = request.first()
        val valid = request.getOrNull(1) == path && (method == "GET" || method == "HEAD")
        val result = if (valid) {
            try {
                "200 OK" to playlist.load()
            } catch (e: Exception) {
                if (!closed) Log.w("YspLive", "${channel.slug} playlist unavailable (${e.javaClass.simpleName})")
                "502 Bad Gateway" to ""
            }
        } else "404 Not Found" to ""
        val body = result.second.toByteArray()
        connection.sink().buffer().use { output ->
            output.writeUtf8("HTTP/1.1 ${result.first}\r\n")
            output.writeUtf8("Content-Type: application/vnd.apple.mpegurl\r\n")
            output.writeUtf8("Cache-Control: no-store\r\n")
            output.writeUtf8("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            if (method != "HEAD") output.write(body)
        }
    }

    private fun fetch(request: Request): YspResponse = execute(request, sampleOnly = false)

    private fun probeSegment(url: HttpUrl) {
        execute(Request.Builder().url(url).header("Range", "bytes=0-187").build(), sampleOnly = true)
    }

    private fun execute(request: Request, sampleOnly: Boolean): YspResponse {
        val call = httpClient.newCall(request)
        inFlight = call
        try {
            if (closed) throw IOException("Playback stopped")
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Upstream HTTP ${response.code}")
                val source = response.body?.source() ?: throw IOException("Empty upstream body")
                if (sampleOnly) return YspResponse(response.request.url, source.readByteArray(1))
                if (source.request(524289)) throw IOException("Upstream response too large")
                return YspResponse(response.request.url, source.readByteArray())
            }
        } finally {
            inFlight = null
        }
    }
}

private class YspPlaylist(
    private val channel: YspChannel,
    private val protocol: YspProtocol,
    private val execute: (Request) -> YspResponse,
    private val probeSegment: (HttpUrl) -> Unit
) {
    private data class Segment(
        val duration: Double,
        val timestamp: String,
        val url: HttpUrl,
        val discontinuity: Boolean = false
    ) {
        val key: String = timestamp.ifEmpty { "${url.host}${url.encodedPath}" }
    }

    private val window = ArrayDeque<Segment>()
    private var sequence = 0L
    private var discontinuitySequence = 0L
    private var targetDuration = 12
    private var useBackup = false
    private var backupUrls = emptyList<HttpUrl>()
    private var verifiedBackup: HttpUrl? = null
    private var backupExpires = 0L
    private var cached: String? = null
    private var lastAttempt = -10000L
    private var lastSuccess = 0L

    fun load(): String {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttempt < 6500) return usableCache(now)
            ?: throw IOException("Waiting to retry playlist")
        lastAttempt = now
        return try {
            val manifest = if (useBackup) backupPlaylist() else {
                try {
                    primaryPlaylist()
                } catch (e: Exception) {
                    // Keep a running playlist on the same timeline. A fresh playback may pick BK.
                    if (cached != null) throw e
                    backupPlaylist().also {
                        useBackup = true
                        Log.d("YspLive", "${channel.slug}: BK")
                    }
                }
            }
            if (manifest == cached) {
                return usableCache(SystemClock.elapsedRealtime()) ?: run {
                    backupUrls = emptyList()
                    throw IOException("Live playlist stopped advancing")
                }
            }
            if (cached == null && !useBackup) Log.d("YspLive", "${channel.slug}: JCE")
            cached = manifest
            lastSuccess = SystemClock.elapsedRealtime()
            manifest
        } catch (e: Exception) {
            usableCache(SystemClock.elapsedRealtime()) ?: throw e
        }
    }

    private fun usableCache(now: Long): String? = cached?.takeIf { now - lastSuccess < 25000 }

    private fun primaryPlaylist(): String {
        val response = fetch(protocol.timeshift(channel), false)
        val lines = response.text().lineSequence().map { it.trim() }.toList()
        if (lines.firstOrNull() != "#EXTM3U") throw IOException("Invalid JCE playlist")
        if (lines.any { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-BYTERANGE") ||
                it.startsWith("#EXT-X-MAP") }) throw IOException("Unsupported JCE segment format")
        val snapshot = mutableListOf<Segment>()
        var duration: Double? = null
        var timestamp = ""
        var discontinuity = false
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> timestamp = line.substringAfter(':')
                line == "#EXT-X-DISCONTINUITY" -> discontinuity = true
                line.isNotEmpty() && !line.startsWith('#') -> {
                    val seconds = duration
                    val url = response.url.resolve(line)
                    if (seconds != null && seconds.isFinite() && seconds > 0 && url != null) {
                        snapshot.add(Segment(seconds, timestamp, url, discontinuity))
                    }
                    duration = null
                    timestamp = ""
                    discontinuity = false
                }
            }
        }
        // The last segment may be cut short by the end of the upstream request window.
        val complete = snapshot.dropLast(1)
        if (complete.isEmpty()) throw IOException("No complete JCE segments")
        val last = window.peekLast()
        val overlap = if (last == null) -1 else complete.indexOfLast { it.key == last.key }
        val additions = when {
            last == null -> complete.takeLast(WINDOW_SIZE)
            overlap >= 0 -> complete.drop(overlap + 1)
            last.timestamp.isNotEmpty() -> complete.filter { it.timestamp > last.timestamp }.takeLast(WINDOW_SIZE)
            else -> emptyList()
        }
        if (last == null) targetDuration = maxOf(targetDuration, ceil(additions.maxOf { it.duration }).toInt())
        if (additions.any { it.duration.roundToInt() > targetDuration }) {
            throw IOException("JCE segment exceeds target duration")
        }
        additions.forEachIndexed { index, segment ->
            if (window.none { it.key == segment.key }) {
                val gap = last != null && overlap < 0 && index == 0
                window.addLast(if (gap) segment.copy(discontinuity = true) else segment)
                if (window.size > WINDOW_SIZE) {
                    if (window.removeFirst().discontinuity) discontinuitySequence++
                    sequence++
                }
            }
        }
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(sequence).append('\n')
            if (discontinuitySequence > 0) {
                append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discontinuitySequence).append('\n')
            }
            for (segment in window) {
                if (segment.discontinuity) append("#EXT-X-DISCONTINUITY\n")
                if (segment.timestamp.isNotEmpty()) {
                    append("#EXT-X-PROGRAM-DATE-TIME:").append(segment.timestamp).append('\n')
                }
                append("#EXTINF:").append(segment.duration).append(",\n")
                append(segment.url).append('\n')
            }
        }
    }

    private fun backupPlaylist(): String {
        val failedHosts = mutableSetOf<String>()
        repeat(3) {
            val now = SystemClock.elapsedRealtime()
            if (backupUrls.isEmpty() || now >= backupExpires) {
                backupUrls = protocol.backup(channel)
                backupExpires = now + 300000
            }
            for (url in backupUrls) {
                if (url.host in failedHosts) continue
                try {
                    val result = readBackupMedia(url, verify = url != verifiedBackup)
                    if (url != verifiedBackup) {
                        verifiedBackup = url
                        backupUrls = listOf(url) + backupUrls.filter { it != url }
                    }
                    return result
                } catch (_: IOException) {
                    failedHosts.add(url.host)
                    if (verifiedBackup == url) verifiedBackup = null
                }
            }
            // The upstream API may return a different CDN on the next request.
            backupUrls = emptyList()
        }
        throw IOException("BK playlists unavailable")
    }

    private fun readBackupMedia(url: HttpUrl, verify: Boolean): String {
        var current = url
        repeat(3) {
            val response = fetch(current, true)
            val lines = response.text().lineSequence().map { it.trim() }.toList()
            if (lines.firstOrNull() != "#EXTM3U") throw IOException("Invalid BK playlist")
            val master = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
            if (master >= 0) {
                val variant = lines.drop(master + 1).firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
                current = variant?.let(response.url::resolve) ?: throw IOException("No BK variant")
            } else {
                if (lines.none { it.startsWith("#EXTINF:") }) throw IOException("No BK segments")
                val manifest = lines.map { line ->
                    if (line.isNotEmpty() && !line.startsWith('#')) {
                        response.url.resolve(line)?.toString() ?: throw IOException("Invalid BK segment")
                    } else URI_ATTRIBUTE.replace(line) { match ->
                        val absolute = response.url.resolve(match.groupValues[1])
                            ?: throw IOException("Invalid BK URI")
                        "URI=\"$absolute\""
                    }
                }
                if (verify) {
                    val segments = manifest.filter { it.isNotEmpty() && !it.startsWith('#') }
                    val sample = segments.getOrNull(segments.size - 2) ?: segments.lastOrNull()
                    val segmentUrl = sample?.let(response.url::resolve) ?: throw IOException("No BK segment URL")
                    probeSegment(segmentUrl)
                }
                return manifest.joinToString("\n", postfix = "\n")
            }
        }
        throw IOException("Too many BK variants")
    }

    private fun fetch(url: HttpUrl, backup: Boolean): YspResponse =
        execute(Request.Builder().url(url).apply {
            if (backup) {
                header("User-Agent", "qqlive")
                header("Referer", "https://live.cctv.cn/")
                header("Accept", "application/vnd.apple.mpegurl,application/json,*/*")
            } else {
                header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            }
        }.build())

    private companion object {
        const val WINDOW_SIZE = 14
        val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")
    }
}
