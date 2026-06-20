package com.jeeva.adshield.core.dns

import android.content.Context
import com.jeeva.adshield.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WhitelistRepository {

    /** Loads the combined whitelist: bundled defaults + user-added entries. */
    suspend fun load(context: Context, prefs: AppPreferences): Set<String> =
        withContext(Dispatchers.IO) {
            loadDefaults(context) + prefs.getUserWhitelist()
        }

    private fun loadDefaults(context: Context): Set<String> = buildSet {
        context.assets.open("whitelist_default.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
                add(trimmed.lowercase())
            }
        }
    }
}
