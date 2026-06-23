package com.vibetraining.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    onTraining: () -> Unit,
    onCompare: () -> Unit
) {
    var syncState by remember { mutableStateOf<SyncState>(SyncState.Idle) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Berlin 2026",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Training Assistant",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            MainButton(
                label = "Sync Activities",
                description = "Pull from Strava · Update Drive",
                icon = "🔄",
                enabled = syncState !is SyncState.Loading,
                onClick = { syncState = SyncState.Loading }
            )

            MainButton(
                label = "Training Log",
                description = "Berlin W1–W26",
                icon = "🏃",
                onClick = onTraining
            )

            MainButton(
                label = "Compare",
                description = "Montréal · Chicago · Berlin",
                icon = "📊",
                onClick = onCompare
            )
        }

        when (val s = syncState) {
            is SyncState.Idle -> Spacer(modifier = Modifier.height(48.dp))
            is SyncState.Loading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Syncing…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is SyncState.Success -> {
                Text(
                    text = s.message,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
            is SyncState.Error -> {
                Text(
                    text = s.message,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun MainButton(
    label: String,
    description: String,
    icon: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    ElevatedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = icon, fontSize = 28.sp)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = label,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

sealed class SyncState {
    object Idle : SyncState()
    object Loading : SyncState()
    data class Success(val message: String) : SyncState()
    data class Error(val message: String) : SyncState()
}
