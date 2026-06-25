package com.vibetraining.assistant.ui.screens

import android.app.Activity
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
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.services.drive.DriveScopes
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
    // null until the stored prefs have loaded, so launch logic can tell
    // "still loading" apart from "loaded but empty" (which means → Settings).
    val prefs by prefsManager.preferences.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    // The interactive reconciliation set, populated once new activities are found.
    var reconcileActivities by remember { mutableStateOf<List<StravaActivity>?>(null) }
    var reconcileWeeks by remember { mutableStateOf<JSONArray?>(null) }
    var reconcileOriginal by remember { mutableStateOf("") }
    // The action to resume once Google sign-in completes (sync or coach review).
    var pendingAuthAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Drive can demand a one-time consent screen (UserRecoverableAuthIOException);
    // we hold its recovery intent here and launch it from a LaunchedEffect, plus
    // the action to retry once permission is granted.
    var pendingConsent by remember { mutableStateOf<Intent?>(null) }
    var afterConsent by remember { mutableStateOf<(() -> Unit)?>(null) }

    val busy = syncState is SyncState.Loading || reconcileActivities != null

    // Pulls activities from Strava, diffs them against the Drive training log, and
    // hands any new ones to the reconciliation wizard. Assumes Google is signed in.
    fun runSync() {
        scope.launch {
            var stage = "starting"
            try {
                val p = prefs ?: return@launch
                val clientId = p.stravaClientId
                val clientSecret = p.stravaClientSecret
                var accessToken = p.stravaAccessToken
                val nowSec = System.currentTimeMillis() / 1000

                if (p.stravaRefreshToken.isBlank()) {
                    stage = "Strava authorization"
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
                } else if (p.stravaExpiresAt - 60 <= nowSec) {
                    stage = "refreshing Strava session"
                    syncState = SyncState.Loading("Refreshing Strava session…")
                    val tokens = StravaService.refresh(clientId, clientSecret, p.stravaRefreshToken)
                    prefsManager.saveStravaTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
                    accessToken = tokens.accessToken
                }

                stage = "fetching activities from Strava"
                syncState = SyncState.Loading("Fetching activities from Strava…")
                val activities = StravaService.listActivities(accessToken)

                stage = "reading training log from Drive"
                syncState = SyncState.Loading("Reading training log…")
                val original = driveService.downloadTrainingDataText().getOrThrow()
                stage = "parsing training log"
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
                val consent = recoverableConsentIntent(e)
                if (consent != null) {
                    afterConsent = { runSync() }
                    syncState = SyncState.Loading("Waiting for Google Drive permission…")
                    pendingConsent = consent
                } else {
                    syncState = SyncState.Error("Sync failed while $stage — ${describe(e)}")
                }
            }
        }
    }

    // Fires the on-demand coaching Routine (reads its URL+token from Drive and
    // POSTs to the /fire endpoint). Assumes Google is signed in.
    fun runCoaching() {
        scope.launch {
            try {
                syncState = SyncState.Loading("Asking the coach to review…")
                driveService.triggerCoaching(
                    prefs?.coachFireUrl.orEmpty(), prefs?.coachFireToken.orEmpty()
                ).getOrThrow()
                syncState = SyncState.Success(
                    "Coach review started. New ratings will appear in your Training Log shortly."
                )
            } catch (e: Exception) {
                val consent = recoverableConsentIntent(e)
                if (consent != null) {
                    afterConsent = { runCoaching() }
                    syncState = SyncState.Loading("Waiting for Google Drive permission…")
                    pendingConsent = consent
                } else {
                    syncState = SyncState.Error("Couldn't start coach review — ${describe(e)}")
                }
            }
        }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        val action = pendingAuthAction
        pendingAuthAction = null
        if (task.isSuccessful) {
            action?.invoke()
        } else {
            val code = (task.exception as? ApiException)?.statusCode
            syncState = SyncState.Error(
                "Google sign-in is required to update the training log" +
                    (code?.let { " (code $it: ${GoogleSignInStatusCodes.getStatusCodeString(it)})" } ?: "") + "."
            )
        }
    }

    // Launches Drive's consent screen; on approval the sync is retried, this time
    // with the granted permission so the Drive read/write succeeds.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val retry = afterConsent
            afterConsent = null
            (retry ?: { runSync() }).invoke()
        } else {
            afterConsent = null
            syncState = SyncState.Error(
                "Google Drive access was declined. The app needs permission to your training folder — tap the button again to retry."
            )
        }
    }

    // Fire the held consent intent exactly once when one is queued.
    LaunchedEffect(pendingConsent) {
        pendingConsent?.let { intent ->
            pendingConsent = null
            consentLauncher.launch(intent)
        }
    }

    // Entry point for the Sync button: ensure credentials and a Google session,
    // then run the sync (which may route through Strava authorization first).
    fun ensureSignIn(then: () -> Unit) {
        if (driveService.isSignedIn()) {
            then()
        } else {
            pendingAuthAction = then
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DriveScopes.DRIVE))
                .build()
            googleSignInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
        }
    }

    fun startSync() {
        if (prefs?.stravaClientId.isNullOrBlank() || prefs?.stravaClientSecret.isNullOrBlank()) {
            onSettings()
            return
        }
        ensureSignIn { runSync() }
    }

    // With the routine URL+token in Settings, coaching needs no Google sign-in;
    // only the Drive-config fallback path does, so gate on that.
    fun startCoaching() {
        if (!prefs?.coachFireUrl.isNullOrBlank() && !prefs?.coachFireToken.isNullOrBlank()) {
            runCoaching()
        } else {
            ensureSignIn { runCoaching() }
        }
    }

    // No manual Sync button: sync automatically once the screen opens. When the
    // prefs have loaded, redirect to Settings if Strava isn't configured yet,
    // otherwise start a sync. Keyed on the credentials so it fires on launch and
    // re-fires once after they're first filled in.
    LaunchedEffect(prefs?.stravaClientId, prefs?.stravaClientSecret) {
        val p = prefs ?: return@LaunchedEffect
        if (p.stravaClientId.isBlank() || p.stravaClientSecret.isBlank()) {
            onSettings()
        } else if (syncState is SyncState.Idle && reconcileActivities == null) {
            startSync()
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
            try {
                syncState = SyncState.Loading("Saving training log to Drive…")
                val today = LocalDate.now()
                SyncReconciler.cleanPastPending(weeks, today)
                SyncReconciler.normalizeStatuses(weeks, today)
                val text = SyncReconciler.serialize(original, weeks)
                val saved = driveService.saveTrainingDataText(text)
                if (saved.isFailure) {
                    syncState = SyncState.Error("Drive save failed — ${describe(saved.exceptionOrNull())}")
                    return@launch
                }
                val s = if (count == 1) "activity" else "activities"
                syncState = SyncState.Success("Training log updated · $count new $s logged.")
            } catch (e: Exception) {
                syncState = SyncState.Error("Saving failed — ${describe(e)}")
            }
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
                    label = "Coach Review",
                    description = "Rate new runs · Update log",
                    icon = "🧠",
                    enabled = !busy,
                    onClick = { startCoaching() }
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

/** Drive access can fail with a recoverable consent error whose recovery intent,
 *  when launched, shows the user Google's permission screen. Returns that intent
 *  if the error (or any cause) is one, else null. */
private fun recoverableConsentIntent(e: Throwable?): Intent? =
    generateSequence(e) { it.cause }
        .mapNotNull { (it as? UserRecoverableAuthIOException)?.intent }
        .firstOrNull()

/** Renders a throwable into something actionable even when its message is null
 *  (e.g. SocketTimeoutException), walking up to three causes so the underlying
 *  reason — a timeout, an auth problem, a missing file — is never swallowed. */
private fun describe(e: Throwable?): String {
    if (e == null) return "unknown error"
    return generateSequence(e) { it.cause }
        .take(3)
        .map { t -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName }
        .distinct()
        .joinToString(" ← ")
}

sealed class SyncState {
    object Idle : SyncState()
    data class Loading(val step: String) : SyncState()
    data class Success(val message: String) : SyncState()
    data class Error(val message: String) : SyncState()
}
