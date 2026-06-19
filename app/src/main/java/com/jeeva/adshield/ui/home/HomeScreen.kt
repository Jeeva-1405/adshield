package com.jeeva.adshield.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    val phaseLabel: String,
)

private val APP_DISPLAY_INFO = listOf(
    AppDisplayInfo("Chrome",        TargetApps.CHROME,        Icons.Rounded.Language,    "Enable DNS", "Phase 2"),
    AppDisplayInfo("YouTube",       TargetApps.YOUTUBE,       Icons.Rounded.PlayCircle,  "Patch",      "Phase 3"),
    AppDisplayInfo("YouTube Music", TargetApps.YOUTUBE_MUSIC, Icons.Rounded.LibraryMusic,"Patch",      "Phase 3"),
    AppDisplayInfo("Spotify",       TargetApps.SPOTIFY,       Icons.Rounded.Headset,     "Patch",      "Phase 3"),
)

/** Main dashboard showing ad-blocking status for each of the 4 target apps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AdShield",
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                    val status = uiState.appStatuses[info.packageName] ?: TargetAppStatus.NotInstalled
                    AppCard(
                        name = info.name,
                        icon = info.icon,
                        status = status,
                        actionLabel = info.actionLabel,
                        onAction = {
                            Toast.makeText(context, "Coming in ${info.phaseLabel}", Toast.LENGTH_SHORT).show()
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
            Spacer(modifier = Modifier.width(16.dp))
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
            Spacer(modifier = Modifier.width(8.dp))
            when (status) {
                is TargetAppStatus.NotInstalled -> OutlinedButton(onClick = {}, enabled = false) {
                    Text("Not Installed")
                }
                else -> Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun statusTint(status: TargetAppStatus): Color = when (status) {
    is TargetAppStatus.NotInstalled -> MaterialTheme.colorScheme.outline
    is TargetAppStatus.Installed    -> MaterialTheme.colorScheme.primary
    is TargetAppStatus.BlockerActive -> Color(0xFF2E7D32)
    is TargetAppStatus.Patched      -> Color(0xFF1B5E20)
}

private fun statusLabel(status: TargetAppStatus): String = when (status) {
    is TargetAppStatus.NotInstalled  -> "Not installed"
    is TargetAppStatus.Installed     -> "Installed • v${status.versionName}"
    is TargetAppStatus.BlockerActive -> "Blocker active"
    is TargetAppStatus.Patched       -> "Patched"
}
