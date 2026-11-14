package com.example.tvbox

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class UpdateRepository(private val context: Context) {
    data class Update(
        val label: String,
        val versionCode: Long,
        val abi: String,
        val url: HttpUrl,
        val size: Long,
        val sha256: String
    )

    class NoCompatibleApk : IOException()
    class InvalidApk : IOException()

    private val client = MainApplication.channelRepository.httpClient.newBuilder()
        .followSslRedirects(false)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    @Suppress("DEPRECATION")
    private val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    fun check(track: (Call) -> Unit): Update? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPOSITORY/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val call = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(request)
        val release = call.also(track).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
            val source = response.body?.source() ?: throw IOException("Empty release response")
            if (source.request(524289)) throw IOException("Release response too large")
            JSONObject(source.readUtf8())
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val installedVersion = PackageInfoCompat.getLongVersionCode(installed)
        val label = release.getString("tag_name")
        val assets = release.getJSONArray("assets")
        val matching = (0 until assets.length()).mapNotNull { index ->
            val asset = assets.optJSONObject(index) ?: return@mapNotNull null
            val filename = asset.optString("name")
            val match = APK_NAME.matchEntire(filename) ?: return@mapNotNull null
            val versionCode = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val url = asset.optString("browser_download_url").toHttpUrlOrNull()
            val size = asset.optLong("size")
            val digest = SHA256.matchEntire(asset.optString("digest"))?.groupValues?.get(1)
            if (url == null || !isRepositoryAsset(url, label, filename) ||
                digest == null || size !in 1L..MAX_APK_SIZE || asset.optString("state") != "uploaded"
            ) return@mapNotNull null

            Update(label, versionCode, match.groupValues[2], url, size, digest)
        }
        val newest = matching.maxOfOrNull { it.versionCode } ?: throw NoCompatibleApk()
        if (newest <= installedVersion) return null
        val candidates = matching.filter { it.versionCode == newest }.associateBy { it.abi }
        return Build.SUPPORTED_ABIS.firstNotNullOfOrNull(candidates::get) ?: throw NoCompatibleApk()
    }

    fun download(update: Update, track: (Call) -> Unit, progress: (Int) -> Unit): File {
        val directory = File(context.cacheDir, "updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update cache")
        val partial = File.createTempFile("update-", ".apk", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val request = Request.Builder().url(update.url).build()
            client.newCall(request).also(track).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty download")
                val buffer = ByteArray(64 * 1024)
                partial.outputStream().buffered().use { output ->
                    val input = body.byteStream()
                    var total = 0L
                    var reported = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > update.size) throw InvalidApk()
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = (total * 100 / update.size).toInt()
                        if (percent != reported) {
                            progress(percent)
                            reported = percent
                        }
                    }
                    if (total != update.size) throw InvalidApk()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(update.sha256, ignoreCase = true)) throw InvalidApk()
            verifyPackage(partial, update)
            val apk = File(directory, "tvbox-update.apk")
            if (!partial.renameTo(apk)) throw IOException("Cannot save verified APK")
            return apk
        } finally {
            partial.delete()
        }
    }

    private fun verifyPackage(file: File, update: Update) {
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, signingFlags)
        val archive = manager.getPackageArchiveInfo(file.path, signingFlags) ?: throw InvalidApk()
        val version = PackageInfoCompat.getLongVersionCode(archive)
        val signatures = signers(installed)
        if (archive.packageName != context.packageName || version != update.versionCode ||
            version <= PackageInfoCompat.getLongVersionCode(installed) ||
            signatures.isEmpty() || signers(archive) != signatures
        ) throw InvalidApk()
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners.orEmpty().toSet()
    } else {
        info.signatures.orEmpty().toSet()
    }

    private fun isRepositoryAsset(url: HttpUrl, tag: String, filename: String): Boolean =
        url.isHttps && url.host == "github.com" &&
            url.pathSegments.take(2).joinToString("/").equals(REPOSITORY, ignoreCase = true) &&
            url.pathSegments.drop(2) == listOf("releases", "download", tag, filename)

    private companion object {
        const val REPOSITORY = "vtsoft-cn/TV-Box"
        const val MAX_APK_SIZE = 256L * 1024 * 1024
        val APK_NAME = Regex("""tvbox-([1-9][0-9]*)-(arm64-v8a|armeabi-v7a|x86_64|x86)\.apk""")
        val SHA256 = Regex("sha256:([a-fA-F0-9]{64})")
    }
}
