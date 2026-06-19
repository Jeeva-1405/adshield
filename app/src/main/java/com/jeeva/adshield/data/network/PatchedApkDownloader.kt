package com.jeeva.adshield.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
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

    /** Returns the local cache file for a given asset filename (may not exist yet). */
    fun getFile(filename: String): File =
        File(File(context.cacheDir, "apks"), filename)
}
