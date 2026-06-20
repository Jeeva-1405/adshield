package com.jeeva.adshield.ui.home

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jeeva.adshield.core.detector.TargetAppStatus
import com.jeeva.adshield.core.detector.TargetApps
import com.jeeva.adshield.core.orchestrator.SetupStep
import com.jeeva.adshield.core.orchestrator.SetupStepState
import com.jeeva.adshield.ui.components.PrimarySetupCard

private data class AppDisplayInfo(
    val name: String,
    val packageName: String,
    val icon: ImageVector,
    val actionLabel: String,
    val setupStep: SetupStep,
)

private val APP_DISPLAY_INFO = listOf(
    AppDisplayInfo("Chrome",        TargetApps.CHROME,        Icons.Rounded.Language,     "Enable DNS",  SetupStep.ChromeDns),
    AppDisplayInfo("YouTube",       TargetApps.YOUTUBE,       Icons.Rounded.PlayCircle,   "Patch",       SetupStep.YouTubePatch),
    AppDisplayInfo("YouTube Music", TargetApps.YOUTUBE_MUSIC, Icons.Rounded.LibraryMusic, "Patch",       SetupStep.YtMusicPatch),
    AppDisplayInfo("Spotify",       TargetApps.SPOTIFY,       Icons.Rounded.Headset,      "Patch",       SetupStep.SpotifyPatch),
)

/** Main dashboard: hero setup card + per-app status cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Single launcher for VPN consent — serves both standalone DNS and the orchestrator
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK, context)
    }
    LaunchedEffect(uiState.vpnPermissionIntent) {
        uiState.vpnPermissionIntent?.let { vpnLauncher.launch(it) }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AdShield", fontWeight = FontWeight.Bold) },
                actions = {
                    if (uiState.isDnsRunning || uiState.isSetupRunning) {
                        IconButton(onClick = { viewModel.onStopAll(context) }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Stop all blocking",
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Hero setup card ────────────────────────────────────────────────
            PrimarySetupCard(
                state        = uiState.setupState,
                isDnsRunning = uiState.isDnsRunning,
                onStart      = { viewModel.onBlockAllAds(context) },
                onRerun      = { viewModel.startSetup() },
                onStopAll    = { viewModel.onStopAll(context) },
            )

            // ── Recently blocked list (visible when blocker is running) ─────────
            if (uiState.isDnsRunning && uiState.recentlyBlocked.isNotEmpty()) {
                RecentlyBlockedCard(
                    domains = uiState.recentlyBlocked,
                    onAllow = { viewModel.onAllowBlocked(it) },
                )
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 16.dp),
                )
            } else {
                // ── Per-app cards ──────────────────────────────────────────────
                APP_DISPLAY_INFO.forEach { info ->
                    val status        = uiState.appStatuses[info.packageName] ?: TargetAppStatus.NotInstalled
                    val orchestratedStep = uiState.setupStepStates[info.setupStep]
                    val isOrchestratorActive = orchestratedStep is SetupStepState.Running
                    val isManualPatching     = uiState.patchingPkg == info.packageName
                    val isChrome             = info.packageName == TargetApps.CHROME
                    val isSpotify            = info.packageName == TargetApps.SPOTIFY

                    val actionLabel = when {
                        isChrome && uiState.isDnsRunning          -> "Disable DNS"
                        isChrome && isOrchestratorActive          -> "Setting up…"
                        isChrome                                   -> "Enable DNS"
                        isOrchestratorActive                       -> "Patching…"
                        isManualPatching                           -> "Patching…"
                        isSpotify && uiState.xManagerInstalled    -> "Open xManager"
                        isSpotify                                  -> "Install xManager"
                        else                                       -> info.actionLabel
                    }

                    val progressStep: String? = when {
                        isOrchestratorActive ->
                            (uiState.setupState as? SetupUiState.InProgress)?.stepDetail
                        isManualPatching     -> uiState.patchStep
                        else                 -> null
                    }

                    val cardSubtitle = if (isSpotify) "Via xManager" else null

                    // Buttons are disabled while the orchestrator owns the flow
                    val actionEnabled = !uiState.isSetupRunning && !isManualPatching

                    AppCard(
                        name          = info.name,
                        icon          = info.icon,
                        status        = status,
                        actionLabel   = actionLabel,
                        actionEnabled = actionEnabled,
                        progressStep  = progressStep,
                        subtitle      = cardSubtitle,
                        onAction      = {
                            if (isChrome) {
                                if (uiState.isDnsRunning) viewModel.onDisableDns(context)
                                else viewModel.onEnableDns(context)
                            } else if (isSpotify && uiState.xManagerInstalled) {
                                viewModel.onOpenXManager(context)
                            } else {
                                viewModel.onPatch(context, info.packageName)
                            }
                        },
                    )
                }
            }

            // ── Upcoming updates card ──────────────────────────────────────────
            UpcomingCard()
        }
    }

}

/** Card showing install/block status and action button for a single target app. */
@Composable
fun AppCard(
    name: String,
    icon: ImageVector,
    status: TargetAppStatus,
    actionLabel: String,
    actionEnabled: Boolean = true,
    progressStep: String? = null,
    subtitle: String? = null,
    onAction: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = name,
                tint = statusTint(status),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = statusLabel(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusTint(status),
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when (status) {
                is TargetAppStatus.NotInstalled ->
                    OutlinedButton(onClick = {}, enabled = false) { Text("Not Installed") }
                else ->
                    Button(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
            }
        }

        if (progressStep != null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Text(
                text = progressStep,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
            )
        } else {
            Spacer(Modifier.height(0.dp))
        }
    }
}

@Composable
private fun statusTint(status: TargetAppStatus): Color = when (status) {
    is TargetAppStatus.NotInstalled  -> MaterialTheme.colorScheme.outline
    is TargetAppStatus.Installed     -> MaterialTheme.colorScheme.primary
    is TargetAppStatus.BlockerActive -> Color(0xFF2E7D32)
    is TargetAppStatus.Patched       -> Color(0xFF1B5E20)
}

private fun statusLabel(status: TargetAppStatus): String = when (status) {
    is TargetAppStatus.NotInstalled  -> "Not installed"
    is TargetAppStatus.Installed     -> "Installed • v${status.versionName}"
    is TargetAppStatus.BlockerActive -> "DNS blocker active"
    is TargetAppStatus.Patched       -> "Patched"
}

/** Scrollable list of the last 20 blocked domains with a one-tap Allow button per row. */
@Composable
private fun RecentlyBlockedCard(
    domains: List<String>,
    onAllow: (String) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Recently Blocked (${domains.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            domains.forEachIndexed { index, domain ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = domain,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onAllow(domain) }) {
                        Text("Allow", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingCard() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.NewReleases,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Upcoming",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "We silenced their ads in 24 hours. Guess who’s next.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

