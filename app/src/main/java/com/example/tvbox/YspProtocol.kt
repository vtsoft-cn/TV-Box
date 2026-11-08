package com.example.tvbox

import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.GzipSource
import okio.buffer
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** The JCE and BK live requests in ysp-live-v9.5.py. */
internal class YspProtocol(private val guid: String, private val execute: (Request) -> YspResponse) {

    fun timeshift(channel: YspChannel): HttpUrl {
        val millis = System.currentTimeMillis()
        val now = millis / 1000
        val requestId = (millis and 0x7fffffff).toInt()
        val body = JceWriter().apply {
            string(0, channel.pid)
            string(1, channel.sid)
            number(2, now - 300)
            number(3, now)
            string(4, channel.quality)
        }
        val request = Request.Builder()
            .url("https://jacc.ysp.cctv.cn/")
            .post(jcePacket(requestId, body.buffer.readByteArray()).toRequestBody(OCTET_STREAM))
            .build()
        val response = readJceResponse(execute(request).bytes)
        if ((response[0] as? Long ?: 0L) != 0L) throw IOException("JCE returned an error")
        val url = (response[2] as? String)?.toHttpUrlOrNull()
            ?: throw IOException("JCE returned no playlist")
        if (url.host == "liverecord.video.cloud.cctv.com") throw IOException("JCE CDN unavailable")
        return url
    }

    private fun jcePacket(requestId: Int, body: ByteArray): ByteArray {
        val command = 25312
        val request = JceWriter().apply {
            struct(0) {
                number(0, requestId.toLong())
                number(1, command.toLong())
                struct(2) { deviceInfo() }
                string(3, "1200013")
                string(4, guid)
                emptyList(5)
                struct(6) {}
                emptyList(7)
                for (tag in 8..10) number(tag, 0)
            }
            bytes(1, body)
        }.buffer
        val inner = Buffer().writeByte(38).writeInt(request.size.toInt() + 17)
            .writeByte(1).write(ByteArray(10))
            .apply { writeAll(request) }.writeByte(40)
        val compressed = Buffer()
        val innerLength = inner.size.toInt()
        GZIPOutputStream(compressed.outputStream()).use { it.write(inner.readByteArray()) }

        return Buffer().writeByte(19).writeInt(90 + compressed.size.toInt())
            .writeShort(2).writeShort(65281).writeShort(command).writeShort(0)
            .writeLong(requestId.toLong()).writeInt(531).writeInt(10012).writeLong(0)
            .writeUtf8(guid).writeByte(1).writeInt(302070).write(ByteArray(6))
            .writeByte(0).writeShort(0).writeShort(0).writeInt(innerLength)
            .apply { writeAll(compressed) }.writeByte(3).readByteArray()
    }

    private fun JceWriter.deviceInfo() {
        string(0, "3.2.7.26212")
        string(1, "302070")
        number(2, 1080)
        number(3, 2400)
        number(4, 3)
        string(5, "12")
        number(6, 1)
        number(7, 1)
        number(8, 420)
        string(9, "10070")
        for (tag in 10..14) string(tag, "")
        struct(15) {
            number(0, 0)
            number(1, 0)
            string(2, "")
        }
        for (tag in 16..18) string(tag, "")
        struct(19) {
            number(0, 0)
            head(1, 4)
            buffer.writeInt(0)
            head(2, 4)
            buffer.writeInt(0)
            head(3, 5)
            buffer.writeLong(0)
        }
        string(20, guid.take(16))
        string(21, "Pixel 6")
        number(22, 1)
        for (tag in 23..26) number(tag, 0)
        string(27, "")
        string(28, "")
        string(29, guid)
    }

    private fun readJceResponse(packet: ByteArray): Map<Int, Any?> {
        require(packet.size >= 90 && packet[0] == 19.toByte() && packet.last() == 3.toByte())
        val flags = ByteBuffer.wrap(packet).getInt(21)
        val wirePayload = packet.copyOfRange(89, packet.lastIndex)
        val payload = if (flags and 2 != 0) {
            GzipSource(Buffer().write(wirePayload)).buffer().use {
                if (it.request(262145)) throw IOException("JCE response too large")
                it.readByteArray()
            }
        } else wirePayload
        require(payload.size >= 17 && payload[0] == 38.toByte() && payload.last() == 40.toByte())
        val body = JceReader(payload.copyOfRange(16, payload.lastIndex)).struct()[1] as? ByteArray
            ?: throw IOException("JCE response missing body")
        return JceReader(body).struct()
    }

    fun backup(channel: YspChannel): List<HttpUrl> {
        val platform = "4330403"
        val version = "V8.22.1035.3031"
        val now = (System.currentTimeMillis() / 1000).toInt()
        val device = randomBytes(16).hex()
        val guard = Buffer().writeInt(now).sized(device.takeLast(5)).sized("").sized("").sized("-1")
        val guardBytes = Buffer().writeShort(guard.size.toInt()).apply { writeAll(guard) }.readByteArray()
        val guardKey = encodeTea(guardBytes, GUARD_TEA, GUARD_XOR).hex().uppercase(Locale.ROOT)
        val info = Buffer().writeInt(66).writeInt(4).writeInt(1234)
            .writeInt(platform.toInt()).writeInt(0).writeInt(now)
            .sized("dcgh").sized("_zj1A5Gh6QYcxWjIUGos2w==").sized(version)
            .sized(channel.sid).sized(device).writeInt(1).writeInt(1)
            .sized(randomBytes(4).hex().uppercase(Locale.ROOT)).sized("nil")
            .sized("57eab0c4-2c58-44c6-8ae9-dd2757525dc5").sized("nil").sized("v0.1.000")
            .sized("com.cctv.yangshipin.app.iphone").sized(platform)
            .sized("ex_json_bus").sized("ex_json_vs").sized(guardKey)
        val packet = Buffer().writeShort(info.size.toInt()).apply { writeAll(info) }.readByteArray()
        ByteBuffer.wrap(packet).putInt(18, checksum(packet))
        val key = "--01" + Base64.encodeToString(encodeTea(packet, LIVE_TEA, LIVE_XOR), Base64.NO_WRAP or Base64.NO_PADDING)
            .replace('+', '_').replace('/', '-')
        val query = mapOf(
            "atime" to "120", "livepid" to channel.pid, "cnlid" to channel.sid,
            "appVer" to version, "app_version" to "300090", "caplv" to "1", "cmd" to "2",
            "defn" to channel.quality, "device" to "iPhone", "encryptVer" to "4.2",
            "getpreviewinfo" to "0", "hevclv" to "0", "lang" to "zh-Hans_CN", "livequeue" to "0",
            "logintype" to "1", "nettype" to "1", "newnettype" to "1", "newplatform" to platform,
            "platform" to platform, "sdtfrom" to "v3021", "spacode" to "23", "spaudio" to "1",
            "spdemuxer" to "6", "spdrm" to "2", "spdynamicrange" to "1", "spflv" to "1",
            "spflvaudio" to "1", "sphdrfps" to "60", "sphttps" to "1", "spvcode" to BK_H264,
            "spvideo" to "4", "stream" to "1", "system" to "1", "sysver" to "ios18.2.1",
            "uhd_flag" to "0", "cKey" to key, "guid" to device, "fntick" to now.toString(),
            "flowid" to UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT) + "_" + platform,
            "playbacktime" to "0"
        )
        val url = "https://bkliveinfo.ysp.cctv.cn/".toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "qqlive").header("Accept", "application/json").build()
        val json = JSONObject(execute(request).text())
        if (json.optInt("iretcode", -1) != 0) throw IOException("BK returned an error")
        val candidates = mutableListOf(json.optString("playurl"))
        val alternate = json.opt("backurl_list") ?: json.opt("backurlList") ?: json.opt("backurl")
        when (alternate) {
            is String -> candidates.addAll(alternate.split(',', ';'))
            is org.json.JSONArray -> for (i in 0 until alternate.length()) {
                when (val value = alternate.opt(i)) {
                    is String -> candidates.add(value)
                    is JSONObject -> candidates.add(value.optString("url").ifEmpty { value.optString("playurl") })
                }
            }
        }
        return candidates.mapNotNull { it.trim().toHttpUrlOrNull() }
            .filter { ".cctv." in it.host }.distinct()
            .sortedWith(compareBy({ "bklive-" !in it.host }, { it.toString() }))
            .ifEmpty { throw IOException("BK returned no playlist") }
    }

    private fun encodeTea(data: ByteArray, key: ByteArray, mask: IntArray): ByteArray {
        val padding = (8 - (data.size + 10) % 8) % 8
        val plain = Buffer().writeByte((random.nextInt() and 248) or padding)
            .write(randomBytes(padding + 2)).write(data).write(ByteArray(7))
        val keys = Buffer().write(key)
        val k0 = keys.readInt()
        val k1 = keys.readInt()
        val k2 = keys.readInt()
        val k3 = keys.readInt()
        val output = Buffer()
        var previousMixed = 0L
        var previousCipher = 0L
        while (!plain.exhausted()) {
            val mixed = plain.readLong() xor previousCipher
            var left = (mixed ushr 32).toInt()
            var right = mixed.toInt()
            var sum = 0
            repeat(16) {
                sum += 0x9e3779b9.toInt()
                left += ((right shl 4) + k0) xor (right + sum) xor ((right ushr 5) + k1)
                right += ((left shl 4) + k2) xor (left + sum) xor ((left ushr 5) + k3)
            }
            previousCipher = ((left.toLong() shl 32) or (right.toLong() and 0xffffffffL)) xor previousMixed
            output.writeLong(previousCipher)
            previousMixed = mixed
        }
        val result = output.writeInt(checksum(data)).readByteArray()
        result.indices.forEach { result[it] = (result[it].toInt() xor mask[it % mask.size]).toByte() }
        return result
    }

    private fun checksum(bytes: ByteArray): Int =
        bytes.fold(0) { sum, byte -> (131 * sum + (byte.toInt() and 255)) and Int.MAX_VALUE }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)
    private fun Buffer.sized(value: String): Buffer {
        val bytes = value.toByteArray()
        return writeShort(bytes.size).write(bytes)
    }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private companion object {
        val random = SecureRandom()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val LIVE_TEA = Buffer().writeLong(0x59b2f7cf725ef43cL).writeLong(0x34fdd7c123411ed3L).readByteArray()
        val GUARD_TEA = Buffer().writeLong(0x110dbec10c23e7d2L).writeLong(0xe56a1cad6914ef1bUL.toLong()).readByteArray()
        val LIVE_XOR = intArrayOf(132, 46, 237, 8, 240, 102, 230, 234, 72, 180, 202, 169, 145, 237, 111, 243)
        val GUARD_XOR = intArrayOf(179, 201, 83, 160, 105, 19, 173, 77)
        const val BK_H264 = "SCgzMDoxMDgwLDYwOjEwODB8MzA6MTA4MCw2MDoxMDgwKQ=="
    }
}

private class JceWriter {
    val buffer = Buffer()

    fun head(tag: Int, type: Int) {
        buffer.writeByte((minOf(tag, 15) shl 4) or type)
        if (tag >= 15) buffer.writeByte(tag)
    }

    fun number(tag: Int, value: Long) {
        when {
            value == 0L -> head(tag, 12)
            value in -128..127 -> { head(tag, 0); buffer.writeByte(value.toInt()) }
            value in -32768..32767 -> { head(tag, 1); buffer.writeShort(value.toInt()) }
            value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> { head(tag, 2); buffer.writeInt(value.toInt()) }
            else -> { head(tag, 3); buffer.writeLong(value) }
        }
    }

    fun string(tag: Int, value: String) {
        val bytes = value.toByteArray()
        head(tag, 6)
        require(bytes.size <= 255)
        buffer.writeByte(bytes.size).write(bytes)
    }

    fun struct(tag: Int, contents: JceWriter.() -> Unit) {
        head(tag, 10)
        contents()
        head(0, 11)
    }

    fun emptyList(tag: Int) {
        head(tag, 9)
        number(0, 0)
    }

    fun bytes(tag: Int, value: ByteArray) {
        head(tag, 13)
        head(0, 0)
        number(0, value.size.toLong())
        buffer.write(value)
    }
}

private class JceReader(bytes: ByteArray) {
    private val buffer = Buffer().write(bytes)

    private fun u8() = buffer.readByte().toInt() and 255

    fun struct(depth: Int = 0): Map<Int, Any?> {
        val fields = mutableMapOf<Int, Any?>()
        while (!buffer.exhausted()) {
            val head = u8()
            val type = head and 15
            val tag = if (head ushr 4 == 15) u8() else head ushr 4
            if (type == 11) break
            fields[tag] = value(type, depth)
        }
        return fields
    }

    private fun field(depth: Int): Any? {
        val head = u8()
        if (head ushr 4 == 15) u8()
        return value(head and 15, depth)
    }

    private fun count(depth: Int): Int {
        val number = field(depth) as? Long ?: throw IOException("Invalid JCE length")
        require(number in 0..buffer.size)
        return number.toInt()
    }

    private fun value(type: Int, depth: Int): Any? {
        require(depth < 16)
        val next = depth + 1
        return when (type) {
            0 -> buffer.readByte().toLong()
            1 -> buffer.readShort().toLong()
            2 -> buffer.readInt().toLong()
            3 -> buffer.readLong()
            4 -> Float.fromBits(buffer.readInt())
            5 -> Double.fromBits(buffer.readLong())
            6 -> buffer.readUtf8(u8().toLong())
            7 -> buffer.readUtf8(buffer.readInt().toLong())
            8 -> (0 until count(next)).associate { field(next) to field(next) }
            9 -> List(count(next)) { field(next) }
            10 -> struct(next)
            11 -> null
            12 -> 0L
            13 -> {
                require(u8() == 0)
                buffer.readByteArray(count(next).toLong())
            }
            else -> throw IOException("Unknown JCE field type")
        }
    }
}
