package com.jeeva.adshield.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "adshield_prefs")

class AppPreferences(private val context: Context) {

    private val patchedKey       = stringSetPreferencesKey("patched_apps")
    private val whitelistKey     = stringSetPreferencesKey("user_whitelist")
    private val setupCompleteKey = booleanPreferencesKey("setup_complete")

    // ── Patched apps ──────────────────────────────────────────────────────────

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

    // ── Setup completion ──────────────────────────────────────────────────────

    /** True once the user has completed the full one-click setup at least once. */
    suspend fun isSetupComplete(): Boolean =
        context.dataStore.data.map { it[setupCompleteKey] ?: false }.first()

    /** Marks setup as complete so future "Block All Ads" taps skip the orchestrator. */
    suspend fun markSetupComplete() {
        context.dataStore.edit { it[setupCompleteKey] = true }
    }

    // ── User whitelist ────────────────────────────────────────────────────────

    /** Emits the current user-added whitelist entries and any future changes. */
    val userWhitelistFlow: Flow<Set<String>> =
        context.dataStore.data.map { it[whitelistKey] ?: emptySet() }

    /** Returns the current user-added whitelist entries (one-shot). */
    suspend fun getUserWhitelist(): Set<String> = userWhitelistFlow.first()

    /** Adds a domain to the user whitelist. */
    suspend fun addToWhitelist(domain: String) {
        val normalized = domain.trim().lowercase().removePrefix("*.")
        if (normalized.isEmpty()) return
        context.dataStore.edit { it[whitelistKey] = (it[whitelistKey] ?: emptySet()) + normalized }
    }

    /** Removes a domain from the user whitelist. */
    suspend fun removeFromWhitelist(domain: String) {
        context.dataStore.edit { it[whitelistKey] = (it[whitelistKey] ?: emptySet()) - domain }
    }
}
