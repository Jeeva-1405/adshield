package com.jeeva.adshield.core.dns

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object BlocklistRepository {

    // Highest-traffic ad networks bundled for instant blocking before the download completes
    private val BUILTIN: Set<String> = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "imasdk.googleapis.com", "tpc.googlesyndication.com",
        "pagead2.googlesyndication.com", "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net", "www.googletagservices.com",
        "partner.googleadservices.com", "admob.com", "adnxs.com",
        "advertising.com", "amazon-adsystem.com", "adsrvr.org",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "pubmatic.com", "rubiconproject.com", "openx.net",
        "scorecardresearch.com", "moatads.com", "quantserve.com",
        "chartbeat.com", "an.facebook.com", "connect.facebook.net",
        "ads.twitter.com", "ads.youtube.com",
    )

    private const val HOSTS_URL =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val CACHE_FILENAME = "blocklist.txt"
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Loads the blocklist; uses local cache when fresh, downloads otherwise. */
    suspend fun load(context: Context): Set<String> = withContext(Dispatchers.IO) {
        BUILTIN + loadOrFetch(context)
    }

    private fun parseHostsFile(text: String): Set<String> = buildSet {
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith('#')) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
                val d = parts[1].lowercase()
                if (d != "localhost" && !d.startsWith("local") && d.contains('.')) add(d)
            }
        }
    }

    private fun staleOrEmpty(cache: File): Set<String> =
        if (cache.exists()) parseHostsFile(cache.readText()) else emptySet()

    private fun loadOrFetch(context: Context): Set<String> {
        val cache = File(context.cacheDir, CACHE_FILENAME)
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS) {
            return parseHostsFile(cache.readText())
        }
        return try {
            val bodyStr = http.newCall(Request.Builder().url(HOSTS_URL).build())
                .execute().use { it.body?.string() }
            if (bodyStr == null) {
                staleOrEmpty(cache)
            } else {
                cache.writeText(bodyStr)
                parseHostsFile(bodyStr)
            }
        } catch (e: Exception) {
            staleOrEmpty(cache)
        }
    }
}
