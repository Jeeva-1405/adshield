package com.jeeva.adshield.core.dns

import android.content.Context
import com.jeeva.adshield.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WhitelistRepository {

    /**
     * Returns only user-added whitelist entries.
     * The bundled CDN whitelist was removed because the curated blocklist no longer
     * contains any CDN or content-serving domains — it is not needed.
     * Power users can still add domains via the in-app whitelist dialog.
     */
    suspend fun load(context: Context, prefs: AppPreferences): Set<String> =
        withContext(Dispatchers.IO) {
            prefs.getUserWhitelist()
        }
}
