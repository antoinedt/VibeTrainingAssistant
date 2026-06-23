package com.vibetraining.assistant.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.vibetraining.assistant.data.AppPreferences
import com.vibetraining.assistant.data.DriveService
import com.vibetraining.assistant.data.PreferencesManager
import com.vibetraining.assistant.data.StravaActivity
import com.vibetraining.assistant.data.StravaAuthBus
import com.vibetraining.assistant.data.StravaService
import com.vibetraining.assistant.data.SyncReconciler
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.time.LocalDate

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
    val driveService = remember { DriveService(context) }
    val prefs by prefsManager.preferences.collectAsState(initial = AppPreferences())
    val scope = rememberCoroutineScope()

    // The interactive reconciliation set, populated once new activities are found.
    var reconcileActivities by remember { mutableStateOf<List<StravaActivity>?>(null) }
    var reconcileWeeks by remember { mutableStateOf<JSONArray?>(null) }
    var reconcileOriginal by remember { mutableStateOf("") }
    var pendingSync by remember { mutableStateOf(false) }

    val busy = syncState is SyncState.Loading || reconcileActivities != null

    // Pulls activities from Strava, diffs them against the Drive training log, and
    // hands any new ones to the reconciliation wizard. Assumes Google is signed in.
    fun runSync() {
        scope.launch {
            try {
                val clientId = prefs.stravaClientId
                val clientSecret = prefs.stravaClientSecret
                var accessToken = prefs.stravaAccessToken
                val nowSec = System.currentTimeMillis() / 1000

                if (prefs.stravaRefreshToken.isBlank()) {
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
                    syncState = SyncState.Loading("Refreshing Strava session…")
                    val tokens = StravaService.refresh(clientId, clientSecret, prefs.stravaRefreshToken)
                    prefsManager.saveStravaTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
                    accessToken = tokens.accessToken
                }

                syncState = SyncState.Loading("Fetching activities from Strava…")
                val activities = StravaService.listActivities(accessToken)

                syncState = SyncState.Loading("Reading training log…")
                val original = driveService.downloadTrainingDataText().getOrThrow()
                val weeks = SyncReconciler.parseWeeks(original)
                val known = SyncReconciler.existingStravaIds(weeks)
                val fresh = activities
                    .filter { it.id !in known }
                    .sortedBy { it.startDateLocal }

                if (fresh.isEmpty()) {
                    syncState = SyncState.Success("No new activities. Training log is up to date.")
                } else {
                    reconcileOriginal = original
                    reconcileWeeks = weeks
                    reconcileActivities = fresh
                    syncState = SyncState.Idle
                }
            } catch (e: Exception) {
                syncState = SyncState.Error(e.message ?: "Sync failed.")
            }
        }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            if (pendingSync) { pendingSync = false; runSync() }
        } else {
            pendingSync = false
            val code = (task.exception as? ApiException)?.statusCode
            syncState = SyncState.Error(
                "Google sign-in is required to update the training log" +
                    (code?.let { " (code $it: ${GoogleSignInStatusCodes.getStatusCodeString(it)})" } ?: "") + "."
            )
        }
    }

    // Entry point for the Sync button: ensure credentials and a Google session,
    // then run the sync (which may route through Strava authorization first).
    fun startSync() {
        if (prefs.stravaClientId.isBlank() || prefs.stravaClientSecret.isBlank()) {
            onSettings()
            return
        }
        if (driveService.isSignedIn()) {
            runSync()
        } else {
            pendingSync = true
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DriveScopes.DRIVE))
                .build()
            googleSignInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
        }
    }

    // Once every new activity has been processed, normalize and write back to Drive.
    fun finishReconcile() {
        val weeks = reconcileWeeks
        val original = reconcileOriginal
        val count = reconcileActivities?.size ?: 0
        reconcileActivities = null
        reconcileWeeks = null
        if (weeks == null) return
        scope.launch {
            syncState = SyncState.Loading("Saving training log to Drive…")
            val today = LocalDate.now()
            SyncReconciler.cleanPastPending(weeks, today)
            SyncReconciler.normalizeStatuses(weeks, today)
            val text = SyncReconciler.serialize(original, weeks)
            driveService.saveTrainingDataText(text).fold(
                onSuccess = {
                    val s = if (count == 1) "activity" else "activities"
                    syncState = SyncState.Success("Training log updated · $count new $s logged.")
                },
                onFailure = { syncState = SyncState.Error("Drive save failed: ${it.message}") }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    IconButton(onClick = onSettings, enabled = !busy) {
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
                    enabled = !busy,
                    onClick = { startSync() }
                )

                MainButton(
                    label = "Training Log",
                    description = "Berlin W1–W26",
                    icon = "🏃",
                    enabled = !busy,
                    onClick = onTraining
                )

                MainButton(
                    label = "Compare",
                    description = "Montréal · Chicago · Berlin",
                    icon = "📊",
                    enabled = !busy,
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

    val toReconcile = reconcileActivities
    val weeksToReconcile = reconcileWeeks
    if (toReconcile != null && weeksToReconcile != null) {
        SyncReconcileDialog(
            activities = toReconcile,
            weeks = weeksToReconcile,
            onFinished = { finishReconcile() }
        )
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
