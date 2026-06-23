package com.vibetraining.assistant.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetraining.assistant.data.AppPreferences
import com.vibetraining.assistant.data.PreferencesManager
import com.vibetraining.assistant.data.StravaAuthBus
import com.vibetraining.assistant.data.StravaService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onTraining: () -> Unit,
    onCompare: () -> Unit,
    onSettings: () -> Unit
) {
    var syncState by remember { mutableStateOf<SyncState>(SyncState.Idle) }

    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager(context) }
    val prefs by prefsManager.preferences.collectAsState(initial = AppPreferences())
    val scope = rememberCoroutineScope()

    val syncing = syncState is SyncState.Loading

    fun runSync() {
        scope.launch {
            try {
                val clientId = prefs.stravaClientId
                val clientSecret = prefs.stravaClientSecret
                var accessToken = prefs.stravaAccessToken
                val nowSec = System.currentTimeMillis() / 1000

                if (prefs.stravaRefreshToken.isBlank()) {
                    // First time: send the user through Strava's authorization page.
                    syncState = SyncState.Loading("Authorizing with Strava…")
                    while (StravaAuthBus.codes.tryReceive().isSuccess) { /* drain stale codes */ }
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(StravaService.authorizeUrl(clientId)))
                    )
                    val code = withTimeoutOrNull(180_000) { StravaAuthBus.codes.receive() }
                    if (code.isNullOrBlank()) error("Strava authorization was cancelled or timed out.")
                    val tokens = StravaService.exchangeCode(clientId, clientSecret, code)
                    prefsManager.saveStravaTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
                    accessToken = tokens.accessToken
                } else if (prefs.stravaExpiresAt - 60 <= nowSec) {
                    // Access token expired (or about to) — refresh it.
                    syncState = SyncState.Loading("Refreshing Strava session…")
                    val tokens = StravaService.refresh(clientId, clientSecret, prefs.stravaRefreshToken)
                    prefsManager.saveStravaTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
                    accessToken = tokens.accessToken
                }

                syncState = SyncState.Loading("Fetching activities from Strava…")
                val activities = StravaService.listActivities(accessToken)
                val runKm = activities
                    .filter { it.type.contains("Run", ignoreCase = true) }
                    .sumOf { it.distanceMeters } / 1000.0

                syncState = SyncState.Success(
                    "Synced ${activities.size} activities · ${"%.0f".format(runKm)} km running.\n" +
                        "Writing back to the Training Log is coming next."
                )
            } catch (e: Exception) {
                syncState = SyncState.Error(e.message ?: "Sync failed.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    IconButton(onClick = onSettings, enabled = !syncing) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                    enabled = !syncing,
                    onClick = {
                        if (prefs.stravaClientId.isBlank() || prefs.stravaClientSecret.isBlank()) {
                            // No Strava credentials yet — send the user to set them up.
                            onSettings()
                        } else {
                            runSync()
                        }
                    }
                )

                MainButton(
                    label = "Training Log",
                    description = "Berlin W1–W26",
                    icon = "🏃",
                    enabled = !syncing,
                    onClick = onTraining
                )

                MainButton(
                    label = "Compare",
                    description = "Montréal · Chicago · Berlin",
                    icon = "📊",
                    enabled = !syncing,
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
                        Text(s.step, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is SyncState.Success -> {
                    Text(
                        text = s.message,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
                is SyncState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 24.dp)
                    ) {
                        Text(
                            text = s.message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = { syncState = SyncState.Idle }) { Text("Dismiss") }
                    }
                }
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
    data class Loading(val step: String) : SyncState()
    data class Success(val message: String) : SyncState()
    data class Error(val message: String) : SyncState()
}
