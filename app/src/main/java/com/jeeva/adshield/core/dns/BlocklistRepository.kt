package com.jeeva.adshield.core.dns

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BlocklistRepository {

    /** Loads the curated ad-only blocklist from the bundled asset file. */
    suspend fun load(context: Context): Set<String> = withContext(Dispatchers.IO) {
        context.assets.open("blocklist_default.txt").bufferedReader().useLines { lines ->
            buildSet {
                for (line in lines) {
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith('#')) continue
                    add(t.lowercase())
                }
            }
        }
    }
}
