package com.jeeva.adshield.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "adshield_prefs")

class AppPreferences(private val context: Context) {

    private val patchedKey = stringSetPreferencesKey("patched_apps")

    /** Returns the set of package names the user has successfully patched. */
    suspend fun getPatchedApps(): Set<String> =
        context.dataStore.data.map { it[patchedKey] ?: emptySet() }.first()

    /** Marks a package as patched. */
    suspend fun markPatched(pkg: String) {
        context.dataStore.edit { it[patchedKey] = (it[patchedKey] ?: emptySet()) + pkg }
    }

    /** Removes the patched mark so the user can re-patch. */
    suspend fun clearPatched(pkg: String) {
        context.dataStore.edit { it[patchedKey] = (it[patchedKey] ?: emptySet()) - pkg }
    }
}
