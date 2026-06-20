package com.jeeva.adshield.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DownloadProgress(val bytesReceived: Long, val totalBytes: Long) {
    val percent: Int get() = if (totalBytes > 0) (bytesReceived * 100 / totalBytes).toInt() else 0
}

/**
 * Downloads pre-patched APKs from GitHub Releases using direct asset URLs.
 * Emits [DownloadProgress] events and saves the file to the app's cache directory.
 */
class PatchedApkDownloader(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Streams download progress and saves the file; collect to completion before reading [getFile]. */
    fun download(filename: String): Flow<DownloadProgress> = flow {
        val outFile = getFile(filename)
        outFile.parentFile?.mkdirs()

        val url = "https://github.com/Jeeva-1405/adshield/releases/latest/download/$filename"
        val response = http.newCall(Request.Builder().url(url).build()).execute()

        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code} downloading $filename — " +
                "make sure '$filename' is attached to your latest GitHub release.")
        }

        val body = response.body ?: throw IOException("Empty response body for $filename")
        val total = body.contentLength()
        var received = 0L

        outFile.outputStream().use { sink ->
            body.byteStream().use { src ->
                val buf = ByteArray(8192)
                var n: Int
                while (src.read(buf).also { n = it } != -1) {
                    sink.write(buf, 0, n)
                    received += n
                    emit(DownloadProgress(received, total))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Downloads from an arbitrary URL (used for third-party sources like xManager). */
    fun downloadFrom(url: String, filename: String): Flow<DownloadProgress> = flow {
        val outFile = getFile(filename)
        outFile.parentFile?.mkdirs()
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading $filename")
        val body = response.body ?: throw IOException("Empty response body for $filename")
        val total = body.contentLength()
        var received = 0L
        outFile.outputStream().use { sink ->
            body.byteStream().use { src ->
                val buf = ByteArray(8192)
                var n: Int
                while (src.read(buf).also { n = it } != -1) {
                    sink.write(buf, 0, n)
                    received += n
                    emit(DownloadProgress(received, total))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Resolves the latest xManager APK download URL via the GitHub releases API.
     * Returns null if the API call fails or no APK asset is found.
     */
    suspend fun resolveXManagerUrl(): String? = withContext(Dispatchers.IO) {
        val body = http.newCall(
            Request.Builder()
                .url("https://api.github.com/repos/Team-xManager/xManager/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
        ).execute().use { it.body?.string() } ?: return@withContext null
        val assets = JSONObject(body).optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return@withContext a.optString("browser_download_url").takeIf { it.isNotEmpty() }
            }
        }
        null
    }

    /** Returns the local cache file for a given asset filename (may not exist yet). */
    fun getFile(filename: String): File =
        File(File(context.cacheDir, "apks"), filename)
}
