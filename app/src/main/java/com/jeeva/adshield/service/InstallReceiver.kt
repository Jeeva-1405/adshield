package com.jeeva.adshield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Receives PackageInstaller session callbacks and pipes results to [results].
 * For STATUS_PENDING_USER_ACTION the system install dialog is launched immediately;
 * the final SUCCESS / FAILURE result arrives in a second broadcast.
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_SESSION_ID = "adshield_session_id"

        /** Emits one [InstallResult] per session after the user confirms or cancels the install. */
        val results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 16)
    }

    data class InstallResult(val sessionId: Int, val success: Boolean)

    override fun onReceive(context: Context, intent: Intent) {
        val status    = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Launch the system "Do you want to install?" dialog
                val userIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                context.startActivity(userIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                // Do NOT emit yet — wait for the final status broadcast
            }
            PackageInstaller.STATUS_SUCCESS ->
                results.tryEmit(InstallResult(sessionId, true))
            else ->
                results.tryEmit(InstallResult(sessionId, false))
        }
    }
}
