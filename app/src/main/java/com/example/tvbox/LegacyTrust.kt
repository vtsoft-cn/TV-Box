package com.example.tvbox

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import okhttp3.OkHttpClient
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal object LegacyTrust {
    // API 25 包含尚未预装 ISRG Root X1 的 Android 7.1 设备。
    private val needsRoot get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1

    fun configurePlaylistClient(context: Context, builder: OkHttpClient.Builder) {
        if (!needsRoot) return
        try {
            val root = context.resources.openRawResource(R.raw.isrg_root_x1).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            trustManager(null).acceptedIssuers.forEachIndexed { index, issuer ->
                store.setCertificateEntry("system-$index", issuer)
            }
            store.setCertificateEntry("isrg-root-x1", root)
            val manager = trustManager(store)
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            builder.sslSocketFactory(ssl.socketFactory, manager)
        } catch (e: Exception) {
            Log.w("LegacyTrust", "Could not add the playlist CA", e)
        }
    }

    fun vlcTrustOption(context: Context): String? {
        if (!needsRoot) return null
        val directory = context.getDir("vlc_ca", Context.MODE_PRIVATE)
        val file = File(directory, "isrg-root-x1.pem")
        return try {
            if (!file.exists()) {
                val atomic = AtomicFile(file)
                val output = atomic.startWrite()
                try {
                    context.resources.openRawResource(R.raw.isrg_root_x1).use { it.copyTo(output) }
                    atomic.finishWrite(output)
                } catch (e: Exception) {
                    atomic.failWrite(output)
                    throw e
                }
            }
            "--gnutls-dir-trust=${directory.absolutePath}"
        } catch (e: Exception) {
            Log.w("LegacyTrust", "Could not add the VLC CA", e)
            null
        }
    }

    private fun trustManager(store: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(store)
            trustManagers.filterIsInstance<X509TrustManager>().single()
        }
}
