package com.jeeva.adshield.core.patcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jeeva.adshield.MainActivity
import com.jeeva.adshield.core.detector.TargetApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Foreground service that downloads a pre-patched APK from GitHub Releases and
 * hands it to the system installer. Upload patched APKs to your GitHub release
 * using the naming convention below before triggering this service.
 */
class PatcherService : Service() {

    companion object {
        const val EXTRA_PACKAGE   = "extra_pkg"
        const val ACTION_PROGRESS = "com.jeeva.adshield.PATCHER_PROGRESS"
        const val EXTRA_STEP      = "extra_step"
        const val EXTRA_PROGRESS  = "extra_progress"   // 0-100
        const val EXTRA_DONE      = "extra_done"
        const val EXTRA_ERROR     = "extra_error"

        private const val NOTIF_CHANNEL = "adshield_patcher"
        private const val NOTIF_ID = 1002

        // Update this URL to point to your own GitHub repo
        private const val RELEASES_API =
            "https://api.github.com/repos/jeeva-1405/adshield/releases/latest"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(NOTIF_CHANNEL, "App Patcher", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)
            ?: run { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotification("Preparing…"))
        scope.launch { runPatcher(pkg) }
        return START_NOT_STICKY
    }

    private suspend fun runPatcher(pkg: String) {
        try {
            if (pkg == TargetApps.SPOTIFY) {
                runXManagerInstall()
                broadcastDone(pkg)
                return
            }

            val assetName = assetFor(pkg)

            emit("Looking up latest release…", 5)
            val url = resolveDownloadUrl(assetName)
                ?: throw IOException(
                    "Asset '$assetName' not found in your GitHub release.\n" +
                    "Build and upload the patched APK first."
                )

            emit("Downloading $assetName…", 15)
            val apk = download(url, assetName) { p -> emit("Downloading… $p%", 15 + p * 70 / 100) }

            emit("Launching installer…", 90)
            withContext(Dispatchers.Main) { ApkInstaller.install(applicationContext, apk) }

            broadcastDone(pkg)
        } catch (e: Exception) {
            broadcastError(e.message ?: "Unknown error")
        } finally {
            stopSelf()
        }
    }

    private suspend fun runXManagerInstall() {
        val filename = "xManager.apk"
        emit("Fetching latest xManager release…", 5)
        val url = resolveXManagerUrl()
            ?: throw IOException("Could not resolve xManager download URL")
        emit("Downloading xManager…", 15)
        val apk = download(url, filename) { p -> emit("Downloading… $p%", 15 + p * 70 / 100) }
        emit("Launching installer…", 90)
        withContext(Dispatchers.Main) { ApkInstaller.install(applicationContext, apk) }
    }

    private fun resolveXManagerUrl(): String? {
        val body = http.newCall(
            Request.Builder()
                .url("https://api.github.com/repos/Team-xManager/xManager/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
        ).execute().use { it.body?.string() } ?: return null
        val assets = JSONObject(body).optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return a.optString("browser_download_url").takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun assetFor(pkg: String): String = when (pkg) {
        TargetApps.YOUTUBE       -> "youtube-revanced.apk"
        TargetApps.YOUTUBE_MUSIC -> "youtube-music-revanced.apk"
        else -> throw IllegalArgumentException("No asset mapping for $pkg")
    }

    private fun resolveDownloadUrl(assetName: String): String? {
        val body = http.newCall(
            Request.Builder().url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .build()
        ).execute().use { it.body?.string() } ?: return null

        val assets = JSONObject(body).optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == assetName) {
                return a.optString("browser_download_url").takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun download(url: String, name: String, onProgress: (Int) -> Unit): File {
        val dir = File(applicationContext.cacheDir, "apks").also { it.mkdirs() }
        val out = File(dir, name)
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body ?: throw IOException("Empty download response")
            val total = body.contentLength()
            var received = 0L
            out.outputStream().use { sink ->
                body.byteStream().use { src ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        sink.write(buf, 0, n)
                        received += n
                        if (total > 0) onProgress((received * 100 / total).toInt())
                    }
                }
            }
        }
        return out
    }

    private fun emit(step: String, progress: Int) {
        sendBroadcast(Intent(ACTION_PROGRESS).apply {
            putExtra(EXTRA_STEP, step)
            putExtra(EXTRA_PROGRESS, progress)
            `package` = packageName
        })
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(step))
    }

    private fun broadcastDone(pkg: String) {
        sendBroadcast(Intent(ACTION_PROGRESS).apply {
            putExtra(EXTRA_DONE, true)
            putExtra(EXTRA_PACKAGE, pkg)
            `package` = packageName
        })
    }

    private fun broadcastError(msg: String) {
        sendBroadcast(Intent(ACTION_PROGRESS).apply {
            putExtra(EXTRA_ERROR, msg)
            `package` = packageName
        })
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("AdShield Patcher")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
