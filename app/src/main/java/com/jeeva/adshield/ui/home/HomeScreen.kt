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
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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

private data class AppDisplayInfo(
    val name: String,
    val packageName: String,
    val icon: ImageVector,
    val actionLabel: String,
)

private val APP_DISPLAY_INFO = listOf(
    AppDisplayInfo("Chrome",        TargetApps.CHROME,        Icons.Rounded.Language,     "Enable DNS"),
    AppDisplayInfo("YouTube",       TargetApps.YOUTUBE,       Icons.Rounded.PlayCircle,   "Patch"),
    AppDisplayInfo("YouTube Music", TargetApps.YOUTUBE_MUSIC, Icons.Rounded.LibraryMusic, "Patch"),
    AppDisplayInfo("Spotify",       TargetApps.SPOTIFY,       Icons.Rounded.Headset,      "Patch"),
)

/** Main dashboard showing ad-blocking status for each of the 4 target apps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Launch system VPN permission dialog when the ViewModel requests it
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK, context)
    }
    LaunchedEffect(uiState.vpnPermissionIntent) {
        uiState.vpnPermissionIntent?.let { vpnLauncher.launch(it) }
    }

    // Show errors in a snackbar
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
            Text(
                text = "Block ads in Chrome, YouTube, YouTube Music, and Spotify.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 32.dp),
                )
            } else {
                APP_DISPLAY_INFO.forEach { info ->
                    val status       = uiState.appStatuses[info.packageName] ?: TargetAppStatus.NotInstalled
                    val isPatching   = uiState.patchingPkg == info.packageName
                    val isChrome     = info.packageName == TargetApps.CHROME

                    val actionLabel = when {
                        isChrome && uiState.isDnsRunning -> "Disable DNS"
                        isChrome                          -> "Enable DNS"
                        isPatching                        -> "Patching…"
                        else                              -> info.actionLabel
                    }

                    AppCard(
                        name          = info.name,
                        icon          = info.icon,
                        status        = status,
                        actionLabel   = actionLabel,
                        actionEnabled = !isPatching,
                        progressStep  = if (isPatching) uiState.patchStep else null,
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
