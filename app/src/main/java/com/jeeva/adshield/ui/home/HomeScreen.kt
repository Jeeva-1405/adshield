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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
                state   = uiState.setupState,
                onStart = { viewModel.startSetup() },
                onRerun = { viewModel.startSetup() },
            )

            // ── DNS stats card (visible when blocker is running) ───────────────
            if (uiState.isDnsRunning) {
                DnsStatsCard(
                    blockedCount     = uiState.dnsBlockedCount,
                    whitelistedCount = uiState.dnsWhitelistedCount,
                    customEntries    = uiState.customWhitelist.size,
                    onManageWhitelist = { viewModel.openWhitelistDialog() },
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

                    val actionLabel = when {
                        isChrome && uiState.isDnsRunning -> "Disable DNS"
                        isChrome && isOrchestratorActive -> "Setting up…"
                        isChrome                          -> "Enable DNS"
                        isOrchestratorActive              -> "Patching…"
                        isManualPatching                  -> "Patching…"
                        else                              -> info.actionLabel
                    }

                    val progressStep: String? = when {
                        isOrchestratorActive ->
                            (uiState.setupState as? SetupUiState.InProgress)?.stepDetail
                        isManualPatching     -> uiState.patchStep
                        else                 -> null
                    }

                    // Buttons are disabled while the orchestrator owns the flow
                    val actionEnabled = !uiState.isSetupRunning && !isManualPatching

                    AppCard(
                        name          = info.name,
                        icon          = info.icon,
                        status        = status,
                        actionLabel   = actionLabel,
                        actionEnabled = actionEnabled,
                        progressStep  = progressStep,
                        onAction      = {
                            if (isChrome) {
                                if (uiState.isDnsRunning) viewModel.onDisableDns(context)
                                else viewModel.onEnableDns(context)
                            } else {
                                viewModel.onPatch(context, info.packageName)
                            }
                        },
                    )
                }
            }
        }
    }

    if (uiState.showWhitelistDialog) {
        WhitelistDialog(
            customEntries = uiState.customWhitelist,
            onAdd         = { viewModel.addToWhitelist(it) },
            onRemove      = { viewModel.removeFromWhitelist(it) },
            onDismiss     = { viewModel.closeWhitelistDialog() },
        )
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

/** Live DNS stats: blocked count, whitelisted count, and a button to manage the whitelist. */
@Composable
private fun DnsStatsCard(
    blockedCount: Long,
    whitelistedCount: Long,
    customEntries: Int,
    onManageWhitelist: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "DNS Blocker Stats",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatChip(
                    icon  = Icons.Rounded.Shield,
                    label = "Blocked",
                    value = blockedCount.toString(),
                    tint  = MaterialTheme.colorScheme.error,
                )
                StatChip(
                    icon  = Icons.Rounded.CheckCircle,
                    label = "Whitelisted",
                    value = whitelistedCount.toString(),
                    tint  = Color(0xFF2E7D32),
                )
                StatChip(
                    icon  = Icons.Rounded.CheckCircle,
                    label = "Custom rules",
                    value = customEntries.toString(),
                    tint  = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onManageWhitelist,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Manage whitelist")
            }
        }
    }
}

@Composable
private fun StatChip(icon: ImageVector, label: String, value: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Dialog for viewing and editing user-added whitelist entries. */
@Composable
private fun WhitelistDialog(
    customEntries: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Whitelist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Domains added here are never blocked. " +
                           "Wildcards are automatic — adding example.com also covers sub.example.com.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.trim().lowercase() },
                        placeholder = { Text("example.com") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        label = { Text("Add domain") },
                    )
                    Button(
                        onClick = {
                            if (input.isNotEmpty()) { onAdd(input); input = "" }
                        },
                        enabled = input.isNotEmpty(),
                    ) { Text("Add") }
                }
                if (customEntries.isEmpty()) {
                    Text(
                        "No custom entries yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        customEntries.sorted().forEach { domain ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = domain,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { onRemove(domain) }) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Remove $domain",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
