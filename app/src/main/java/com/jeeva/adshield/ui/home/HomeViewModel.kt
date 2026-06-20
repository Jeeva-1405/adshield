package com.jeeva.adshield.ui.home

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jeeva.adshield.core.detector.AppDetector
import com.jeeva.adshield.core.detector.TargetAppStatus
import com.jeeva.adshield.core.detector.TargetApps
import com.jeeva.adshield.core.dns.DnsVpnService
import com.jeeva.adshield.core.orchestrator.SetupOrchestrator
import com.jeeva.adshield.core.orchestrator.SetupProgress
import com.jeeva.adshield.core.orchestrator.SetupStep
import com.jeeva.adshield.core.orchestrator.SetupStepState
import com.jeeva.adshield.core.orchestrator.label
import com.jeeva.adshield.core.patcher.PatcherService
import com.jeeva.adshield.data.network.PatchedApkDownloader
import com.jeeva.adshield.data.prefs.AppPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── Setup UI state ────────────────────────────────────────────────────────────

sealed class SetupUiState {
    object Idle : SetupUiState()

    data class InProgress(
        val step: SetupStep,
        val overallPercent: Int,
        val stepDetail: String,
    ) : SetupUiState()

    data class Complete(
        val protectedCount: Int,
        val totalApplicable: Int,
        val failedSteps: List<String>,
    ) : SetupUiState()
}

// ── Home UI state ─────────────────────────────────────────────────────────────

data class HomeUiState(
    val appStatuses: Map<String, TargetAppStatus> = emptyMap(),
    val isLoading: Boolean = true,
    val isDnsRunning: Boolean = false,
    val vpnPermissionIntent: Intent? = null,
    // Manual per-card patch progress
    val patchingPkg: String? = null,
    val patchStep: String? = null,
    val patchProgress: Int = 0,
    // One-click setup
    val setupState: SetupUiState = SetupUiState.Idle,
    val setupStepStates: Map<SetupStep, SetupStepState> = emptyMap(),
    val isSetupRunning: Boolean = false,
    val errorMessage: String? = null,
    // DNS stats
    val dnsBlockedCount: Long = 0L,
    val dnsWhitelistedCount: Long = 0L,
    // Whitelist management
    val customWhitelist: Set<String> = emptySet(),
    val showWhitelistDialog: Boolean = false,
    // xManager state for Spotify card
    val xManagerInstalled: Boolean = false,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val detector = AppDetector(application.packageManager)
    private val prefs    = AppPreferences(application)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Used to bridge the VPN permission dialog result back to the orchestrator.
    // Non-null only while the orchestrator (or standalone DNS) is awaiting consent.
    private var vpnDeferred: CompletableDeferred<Boolean>? = null
    private var setupJob: Job? = null

    private val orchestrator by lazy {
        SetupOrchestrator(
            context              = getApplication(),
            detector             = detector,
            prefs                = prefs,
            downloader           = PatchedApkDownloader(getApplication()),
            requestVpnPermission = { requestVpnPermissionSuspend() },
        )
    }

    // ── PatcherService broadcast (manual per-card patching) ───────────────────

    private val patchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val step     = intent.getStringExtra(PatcherService.EXTRA_STEP)
            val progress = intent.getIntExtra(PatcherService.EXTRA_PROGRESS, 0)
            val done     = intent.getBooleanExtra(PatcherService.EXTRA_DONE, false)
            val error    = intent.getStringExtra(PatcherService.EXTRA_ERROR)
            val pkg      = intent.getStringExtra(PatcherService.EXTRA_PACKAGE)

            _uiState.update { s ->
                when {
                    done        -> s.copy(patchingPkg = null, patchStep = null)
                    error != null -> s.copy(patchingPkg = null, patchStep = null, errorMessage = error)
                    else        -> s.copy(patchStep = step, patchProgress = progress)
                }
            }
            if (done && pkg != null) {
                viewModelScope.launch(Dispatchers.IO) { prefs.markPatched(pkg); refresh() }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            application,
            patchReceiver,
            IntentFilter(PatcherService.ACTION_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    // ── Public API: status refresh ────────────────────────────────────────────

    /** Re-scans installed apps, overlays live engine state, and reads DNS counters. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val patched   = prefs.getPatchedApps()
            val statuses  = detector.detectAll(DnsVpnService.isRunning, patched)
            val whitelist = prefs.getUserWhitelist()
            val xManagerInstalled = try {
                getApplication<Application>().packageManager
                    .getPackageInfo(TargetApps.XMANAGER, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
            _uiState.update { it.copy(
                appStatuses         = statuses,
                isLoading           = false,
                isDnsRunning        = DnsVpnService.isRunning,
                dnsBlockedCount     = DnsVpnService.blockedCount.get(),
                dnsWhitelistedCount = DnsVpnService.whitelistedCount.get(),
                customWhitelist     = whitelist,
                xManagerInstalled   = xManagerInstalled,
            )}
        }
    }

    // ── Public API: one-click setup ───────────────────────────────────────────

    /** Starts (or re-starts) the full one-click setup flow. */
    fun startSetup() {
        setupJob?.cancel()
        vpnDeferred?.cancel()
        vpnDeferred = null

        val stepStates = mutableMapOf<SetupStep, SetupStepState>()

        _uiState.update { it.copy(
            setupState      = SetupUiState.InProgress(SetupStep.ChromeDns, 0, "Starting…"),
            setupStepStates = emptyMap(),
            isSetupRunning  = true,
        )}

        setupJob = viewModelScope.launch {
            orchestrator.runSetup().collect { progress ->
                stepStates[progress.step] = progress.state
                _uiState.update { s ->
                    s.copy(
                        setupState = SetupUiState.InProgress(
                            step          = progress.step,
                            overallPercent = progress.overallPercent,
                            stepDetail    = progress.stepDetail,
                        ),
                        setupStepStates = stepStates.toMap(),
                    )
                }
                // Refresh per-app cards whenever a step resolves
                if (progress.state !is SetupStepState.Running &&
                    progress.state !is SetupStepState.Pending) {
                    refresh()
                }
            }

            // Flow completed → compute summary
            val succeeded = stepStates.values.count { it == SetupStepState.Success }
            val alreadyOk = stepStates.values.count {
                it is SetupStepState.Skipped &&
                (it.reason == "Already active" || it.reason == "Already patched")
            }
            val failed = stepStates.filterValues { it is SetupStepState.Failed }
            val applicable = stepStates.values.count { it !is SetupStepState.Skipped ||
                    (it as SetupStepState.Skipped).reason != "Not installed" }

            _uiState.update { s ->
                s.copy(
                    setupState = SetupUiState.Complete(
                        protectedCount  = succeeded + alreadyOk,
                        totalApplicable = applicable,
                        failedSteps     = failed.keys.map { it.label },
                    ),
                    isSetupRunning  = false,
                    setupStepStates = stepStates.toMap(),
                )
            }
            refresh()
        }
    }

    // ── Public API: VPN permission result ─────────────────────────────────────

    /**
     * Must be called when the system VPN-consent Activity returns a result.
     * Handles both the orchestrator path and the standalone "Enable DNS" path.
     */
    fun onVpnPermissionResult(granted: Boolean, context: Context) {
        _uiState.update { it.copy(vpnPermissionIntent = null) }
        val deferred = vpnDeferred
        vpnDeferred = null
        if (deferred != null) {
            // Orchestrator (or standalone DNS launch that went through deferred) is waiting
            deferred.complete(granted)
        } else if (granted) {
            // Standalone path (user tapped "Enable DNS" on the Chrome card directly)
            startDns(context)
        }
    }

    // ── Public API: standalone DNS toggle ────────────────────────────────────

    /** Called when user taps "Enable DNS" on the Chrome card (not via setup flow). */
    fun onEnableDns(context: Context) {
        val perm = VpnService.prepare(context)
        if (perm == null) {
            startDns(context)
        } else {
            // No vpnDeferred here — handled by the standalone branch in onVpnPermissionResult
            _uiState.update { it.copy(vpnPermissionIntent = perm) }
        }
    }

    /** Called when user taps "Disable DNS". */
    fun onDisableDns(context: Context) {
        context.startService(Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_STOP
        })
        viewModelScope.launch { delay(400); refresh() }
    }

    // ── Public API: manual per-card patching ──────────────────────────────────

    /** Launches xManager so the user can select and install a modded Spotify version. */
    fun onOpenXManager(context: Context) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).setPackage(TargetApps.XMANAGER)
            )
        } catch (_: Exception) {
            _uiState.update { it.copy(errorMessage = "xManager is not installed") }
        }
    }

    /** Stops DNS blocker and cancels any in-progress setup or patching. */
    fun onStopAll(context: Context) {
        setupJob?.cancel()
        setupJob = null
        vpnDeferred?.cancel()
        vpnDeferred = null
        if (DnsVpnService.isRunning) {
            context.startService(Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_STOP
            })
        }
        _uiState.update { it.copy(
            isSetupRunning  = false,
            setupState      = SetupUiState.Idle,
            setupStepStates = emptyMap(),
            patchingPkg     = null,
            patchStep       = null,
        )}
        viewModelScope.launch { delay(400); refresh() }
    }

    /** Called when user taps "Patch" on a YouTube / YT Music / Spotify card. */
    fun onPatch(context: Context, pkg: String) {
        _uiState.update { it.copy(patchingPkg = pkg, patchStep = "Starting…", patchProgress = 0) }
        context.startForegroundService(Intent(context, PatcherService::class.java).apply {
            putExtra(PatcherService.EXTRA_PACKAGE, pkg)
        })
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    // ── Public API: whitelist management ──────────────────────────────────────

    fun openWhitelistDialog() = _uiState.update { it.copy(showWhitelistDialog = true) }
    fun closeWhitelistDialog() = _uiState.update { it.copy(showWhitelistDialog = false) }

    fun addToWhitelist(domain: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.addToWhitelist(domain)
            refresh()
        }
    }

    fun removeFromWhitelist(domain: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.removeFromWhitelist(domain)
            refresh()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Suspends until the user approves or denies the VPN consent dialog.
     * Sets [vpnPermissionIntent] in state so the Activity-bound launcher fires.
     */
    private suspend fun requestVpnPermissionSuspend(): Boolean {
        val intent = VpnService.prepare(getApplication()) ?: return true  // already granted
        vpnDeferred = CompletableDeferred()
        _uiState.update { it.copy(vpnPermissionIntent = intent) }
        return vpnDeferred!!.await()
    }

    private fun startDns(context: Context) {
        context.startForegroundService(Intent(context, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_START
        })
        viewModelScope.launch { delay(600); refresh() }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(patchReceiver)
    }
}
