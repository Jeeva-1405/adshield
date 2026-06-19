package com.jeeva.adshield.core.orchestrator

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.jeeva.adshield.core.detector.AppDetector
import com.jeeva.adshield.core.detector.TargetApps
import com.jeeva.adshield.core.dns.DnsVpnService
import com.jeeva.adshield.data.network.PatchedApkDownloader
import com.jeeva.adshield.data.prefs.AppPreferences
import com.jeeva.adshield.service.InstallReceiver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

// ── Domain types ──────────────────────────────────────────────────────────────

sealed class SetupStep {
    object ChromeDns    : SetupStep()
    object YouTubePatch : SetupStep()
    object YtMusicPatch : SetupStep()
    object SpotifyPatch : SetupStep()
}

sealed class SetupStepState {
    object Pending : SetupStepState()
    object Running : SetupStepState()
    data class Skipped(val reason: String) : SetupStepState()
    object Success : SetupStepState()
    data class Failed(val error: String) : SetupStepState()
}

data class SetupProgress(
    val step: SetupStep,
    val state: SetupStepState,
    val overallPercent: Int,
    val stepDetail: String = "",
)

/** Human-readable label for a step, used in summaries and card overlays. */
val SetupStep.label: String get() = when (this) {
    SetupStep.ChromeDns    -> "Chrome"
    SetupStep.YouTubePatch -> "YouTube"
    SetupStep.YtMusicPatch -> "YouTube Music"
    SetupStep.SpotifyPatch -> "Spotify"
}

/** The package name this step operates on. */
val SetupStep.pkg: String get() = when (this) {
    SetupStep.ChromeDns    -> TargetApps.CHROME
    SetupStep.YouTubePatch -> TargetApps.YOUTUBE
    SetupStep.YtMusicPatch -> TargetApps.YOUTUBE_MUSIC
    SetupStep.SpotifyPatch -> TargetApps.SPOTIFY
}

// ── Orchestrator ──────────────────────────────────────────────────────────────

/**
 * Drives the "Block All Ads" one-click setup flow.
 *
 * @param requestVpnPermission Suspend callback that must launch the VPN consent dialog
 *   from the Activity and return true if granted, false if denied.
 */
class SetupOrchestrator(
    private val context: Context,
    private val detector: AppDetector,
    private val prefs: AppPreferences,
    private val downloader: PatchedApkDownloader,
    private val requestVpnPermission: suspend () -> Boolean,
) {
    private val steps = listOf(
        SetupStep.ChromeDns,
        SetupStep.YouTubePatch,
        SetupStep.YtMusicPatch,
        SetupStep.SpotifyPatch,
    )

    /**
     * Runs all 4 setup steps sequentially, emitting [SetupProgress] for each state change.
     * Failures on individual steps are caught and reported; the flow continues to the next step.
     */
    fun runSetup(): Flow<SetupProgress> = channelFlow {
        val total = steps.size
        for ((idx, step) in steps.withIndex()) {
            val basePercent = idx * 100 / total
            val endPercent  = (idx + 1) * 100 / total

            val skip = skipReason(step)
            if (skip != null) {
                send(SetupProgress(step, SetupStepState.Skipped(skip), endPercent, skip))
                continue
            }

            send(SetupProgress(step, SetupStepState.Running, basePercent))

            val state = try {
                executeStep(step, basePercent, endPercent) { send(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e                    // propagate cancellation
            } catch (e: Exception) {
                SetupStepState.Failed(e.message ?: "Unexpected error")
            }

            send(SetupProgress(step, state, endPercent))
        }
    }

    // ── Step dispatch ─────────────────────────────────────────────────────────

    private suspend fun executeStep(
        step: SetupStep,
        basePercent: Int,
        endPercent: Int,
        emit: suspend (SetupProgress) -> Unit,
    ): SetupStepState = when (step) {
        SetupStep.ChromeDns    -> executeChromeDns(step, emit)
        SetupStep.YouTubePatch -> executePatch(step, "youtube-revanced.apk",      basePercent, endPercent, emit)
        SetupStep.YtMusicPatch -> executePatch(step, "youtube-music-revanced.apk", basePercent, endPercent, emit)
        SetupStep.SpotifyPatch -> executePatch(step, "spotify-revanced.apk",       basePercent, endPercent, emit)
    }

    // ── Chrome DNS step ───────────────────────────────────────────────────────

    private suspend fun executeChromeDns(
        step: SetupStep,
        emit: suspend (SetupProgress) -> Unit,
    ): SetupStepState {
        emit(SetupProgress(step, SetupStepState.Running, 5, "Requesting VPN permission…"))
        val granted = requestVpnPermission()
        if (!granted) return SetupStepState.Failed("VPN permission denied")

        emit(SetupProgress(step, SetupStepState.Running, 20, "Starting DNS blocker…"))
        context.startForegroundService(Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_START
        })
        delay(800)   // allow tunnel to establish before we check its status
        return SetupStepState.Success
    }

    // ── Patch step ────────────────────────────────────────────────────────────

    private suspend fun executePatch(
        step: SetupStep,
        filename: String,
        basePercent: Int,
        endPercent: Int,
        emit: suspend (SetupProgress) -> Unit,
    ): SetupStepState {
        val range = endPercent - basePercent

        // Download (0 → 70 % of this step's share)
        downloader.download(filename).collect { dp ->
            val p = basePercent + dp.percent * range * 70 / 10000
            emit(SetupProgress(step, SetupStepState.Running, p, "Downloading… ${dp.percent}%"))
        }

        emit(SetupProgress(step, SetupStepState.Running, basePercent + range * 75 / 100, "Preparing installer…"))

        val apkFile = downloader.getFile(filename)
        val sessionId = writeSession(apkFile)
            ?: return SetupStepState.Failed("Failed to create installer session")

        // ── Race-free: subscribe to result BEFORE committing ──────────────────
        // coroutineScope lets us use async inside a suspend function.
        val success = coroutineScope {
            val resultJob = async {
                withTimeoutOrNull(120_000L) {
                    InstallReceiver.results
                        .filter { it.sessionId == sessionId }
                        .first()
                        .success
                } ?: false
            }
            emit(SetupProgress(step, SetupStepState.Running, basePercent + range * 85 / 100, "Waiting for install…"))
            commitSession(sessionId)   // triggers STATUS_PENDING_USER_ACTION → system dialog
            resultJob.await()
        }

        if (success) prefs.markPatched(step.pkg)
        return if (success) SetupStepState.Success
               else SetupStepState.Failed("Install was cancelled or failed")
    }

    // ── PackageInstaller helpers ───────────────────────────────────────────────

    /** Creates a session and writes the APK bytes; returns the session ID or null on error. */
    private fun writeSession(apkFile: File): Int? = try {
        val pi     = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id     = pi.createSession(params)
        pi.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                apkFile.inputStream().copyTo(out)
                session.fsync(out)
            }
        }
        id
    } catch (e: Exception) { null }

    /** Commits the session; the system will send STATUS_PENDING_USER_ACTION to [InstallReceiver]. */
    private fun commitSession(sessionId: Int) {
        val callbackIntent = Intent(context, InstallReceiver::class.java).apply {
            putExtra(InstallReceiver.EXTRA_SESSION_ID, sessionId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, sessionId, callbackIntent, PendingIntent.FLAG_MUTABLE
        )
        context.packageManager.packageInstaller
            .openSession(sessionId)
            .use { it.commit(pendingIntent.intentSender) }
    }

    // ── Skip-condition checks ─────────────────────────────────────────────────

    private suspend fun skipReason(step: SetupStep): String? = when (step) {
        SetupStep.ChromeDns -> when {
            !isInstalled(TargetApps.CHROME) -> "Not installed"
            DnsVpnService.isRunning          -> "Already active"
            else                             -> null
        }
        else -> patchSkipReason(step.pkg)
    }

    private suspend fun patchSkipReason(pkg: String): String? = when {
        !isInstalled(pkg)                    -> "Not installed"
        prefs.getPatchedApps().contains(pkg) -> "Already patched"
        isSignedByUs(pkg)                    -> "Already patched"
        else                                 -> null
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /**
     * Returns true if the target app is signed with the same certificate as AdShield,
     * meaning it was built and installed by us previously.
     */
    private fun isSignedByUs(targetPkg: String): Boolean = try {
        val ourCert    = signingCert(context.packageName) ?: return false
        val targetCert = signingCert(targetPkg)           ?: return false
        ourCert.contentEquals(targetCert)
    } catch (_: Exception) { false }

    @Suppress("DEPRECATION")
    private fun signingCert(pkg: String): ByteArray? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager
                .getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            context.packageManager
                .getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()?.toByteArray()
        }
    }
}
