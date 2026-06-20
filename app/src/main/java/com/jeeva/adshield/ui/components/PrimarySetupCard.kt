package com.jeeva.adshield.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jeeva.adshield.core.orchestrator.label
import com.jeeva.adshield.ui.home.SetupUiState

/**
 * Hero card at the top of the home screen that drives the one-click setup flow.
 * Shows "Protection Active" + Stop when DNS is running; "Block All Ads" otherwise.
 */
@Composable
fun PrimarySetupCard(
    state: SetupUiState,
    isDnsRunning: Boolean,
    onStart: () -> Unit,
    onRerun: () -> Unit,
    onStopAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // In-progress takes highest priority; then active DNS; then idle/complete → idle button
    val cardKey = when {
        state is SetupUiState.InProgress -> 2
        isDnsRunning                     -> 1
        else                             -> 0
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        AnimatedContent(
            targetState = cardKey,
            transitionSpec = {
                (fadeIn() + slideInVertically { it / 4 }) togetherWith
                (fadeOut() + slideOutVertically { -it / 4 })
            },
            label = "setup_card",
        ) { key ->
            when (key) {
                1    -> ActiveContent(onStopAll)
                2    -> InProgressContent(state as SetupUiState.InProgress)
                else -> IdleContent(onStart)
            }
        }
    }
}

// ── State-specific content ────────────────────────────────────────────────────

@Composable
private fun IdleContent(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "AdShield",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    text = "One tap — block ads everywhere",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onPrimary,
                contentColor   = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text("Block All Ads", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Set up in 1 minute  •  Chrome + YouTube + Spotify",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun ActiveContent(onStopAll: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = Color(0xFF81C784),
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Protection Active",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    text = "DNS blocker is running — ads are blocked",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onStopAll,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFFEF9A9A),
            ),
            border = BorderStroke(1.dp, Color(0xFFEF9A9A).copy(alpha = 0.7f)),
        ) {
            Icon(
                imageVector = Icons.Rounded.Block,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Stop All", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InProgressContent(state: SetupUiState.InProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
    ) {
        Text(
            text = "Setting up AdShield…",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = state.stepDetail.ifBlank { "Working on ${state.step.label}…" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { state.overallPercent / 100f },
            modifier = Modifier.fillMaxWidth(),
            color      = MaterialTheme.colorScheme.onPrimary,
            trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${state.overallPercent}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
        )
    }
}

