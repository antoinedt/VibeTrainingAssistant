package com.vibetraining.assistant.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.vibetraining.assistant.data.DriveService
import com.vibetraining.assistant.data.PreferencesManager
import com.vibetraining.assistant.data.StravaActivity
import com.vibetraining.assistant.data.StravaAuthBus
import com.vibetraining.assistant.data.StravaService
import com.vibetraining.assistant.data.SyncReconciler
import com.vibetraining.assistant.data.WeekSummary
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.time.LocalDate

/** The activity being edited: its Strava id, header text and current feedback. */
private data class EditTarget(
    val stravaId: Long,
    val title: String,
    val subtitle: String,
    val initial: SyncReconciler.RunFeedback
)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrainingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val driveService = remember { DriveService(context) }
    val prefsManager = remember { PreferencesManager(context) }
    val prefs by prefsManager.preferences.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var htmlContent by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    // End-week flow.
    var firing by remember { mutableStateOf(false) }
    // Manual coach-review trigger (sync no longer fires it automatically).
    var reviewing by remember { mutableStateOf(false) }
    var showEndWeek by remember { mutableStateOf(false) }
    var loadingWeeks by remember { mutableStateOf(false) }
    var weekOptions by remember { mutableStateOf<List<WeekSummary>>(emptyList()) }
    var selectedWeek by remember { mutableStateOf<Int?>(null) }
    var guidelines by remember { mutableStateOf("") }

    // Manual Strava sync flow (moved off Home — no longer automatic).
    var syncState by remember { mutableStateOf<SyncState>(SyncState.Idle) }
    var reconcileActivities by remember { mutableStateOf<List<StravaActivity>?>(null) }
    var reconcileWeeks by remember { mutableStateOf<JSONArray?>(null) }
    var reconcileOriginal by remember { mutableStateOf("") }
    var pendingAuthAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingConsent by remember { mutableStateOf<Intent?>(null) }
    var afterConsent by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Activity rating/notes editing.
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var editSaving by remember { mutableStateOf(false) }

    val syncing = syncState is SyncState.Loading || reconcileActivities != null
    val busy = loading || syncing || firing

    fun loadHtml() {
        scope.launch {
            loading = true
            // Bound the wait so a stalled Drive/network call surfaces an error
            // instead of spinning forever, and always show a non-empty message.
            val result = withTimeoutOrNull(45_000) { driveService.fetchTrainingHtml() }
            when {
                result == null ->
                    error = "Google Drive didn't respond (timed out). Check your connection, then tap Reload."
                result.isSuccess -> { htmlContent = result.getOrNull(); error = null }
                else -> {
                    val e = result.exceptionOrNull()
                    val consent = recoverableConsentIntent(e)
                    if (consent != null) { afterConsent = { loadHtml() }; pendingConsent = consent }
                    else error = "Couldn't load the training log — ${describe(e)}"
                }
            }
            loading = false
        }
    }

    // Refresh the log in place after a sync or an edit (keeps the current view if
    // the fetch fails rather than dropping to the error screen).
    fun reloadHtml() {
        scope.launch {
            val result = withTimeoutOrNull(45_000) { driveService.fetchTrainingHtml() }
            when {
                result == null -> snackbar.showSnackbar("Reload timed out — check your connection.")
                result.isSuccess -> { htmlContent = result.getOrNull(); error = null }
                else -> snackbar.showSnackbar("Couldn't reload — ${describe(result.exceptionOrNull())}")
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
            error = "Google sign-in failed" +
                (code?.let { " (code $it: ${GoogleSignInStatusCodes.getStatusCodeString(it)})" } ?: "")
        }
    }

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

    fun loadOrSignIn() = ensureSignIn { loadHtml() }

    // Pulls activities from Strava, diffs them against the Drive log, and hands
    // any new ones to the reconciliation wizard. Assumes Google is signed in.
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
                "Google Drive access was declined. Tap Sync again to retry."
            )
        }
    }

    LaunchedEffect(pendingConsent) {
        pendingConsent?.let { intent ->
            pendingConsent = null
            consentLauncher.launch(intent)
        }
    }

    fun startSync() {
        if (syncing) return
        if (prefs?.stravaClientId.isNullOrBlank() || prefs?.stravaClientSecret.isNullOrBlank()) {
            scope.launch { snackbar.showSnackbar("Add your Strava keys in Settings first.") }
            return
        }
        syncState = SyncState.Idle
        ensureSignIn { runSync() }
    }

    // Once every new activity has been processed, normalize and write back to
    // Drive, then refresh the log. (Coaching is triggered from "End week", not
    // from every sync.)
    fun finishReconcile() {
        val weeks = reconcileWeeks
        val original = reconcileOriginal
        val count = reconcileActivities.orEmpty().size
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
                reloadHtml()
            } catch (e: Exception) {
                syncState = SyncState.Error("Saving failed — ${describe(e)}")
            }
        }
    }

    // Bridge target: the WebView's Edit button hands us a Strava id; we pull the
    // current feedback from Drive and open the edit dialog pre-filled.
    fun onEditActivity(stravaId: Long) {
        if (editSaving || editTarget != null) return
        scope.launch {
            try {
                val original = driveService.downloadTrainingDataText().getOrThrow()
                val weeks = SyncReconciler.parseWeeks(original)
                val fb = SyncReconciler.feedbackFor(weeks, stravaId)
                val label = SyncReconciler.labelFor(weeks, stravaId)
                if (fb == null || label == null) {
                    snackbar.showSnackbar("Couldn't find that activity to edit.")
                    return@launch
                }
                editTarget = EditTarget(stravaId, label.first, label.second, fb)
            } catch (e: Exception) {
                snackbar.showSnackbar("Couldn't open the editor — ${describe(e)}")
            }
        }
    }

    fun saveEdit(feedback: SyncReconciler.RunFeedback) {
        val target = editTarget ?: return
        scope.launch {
            editSaving = true
            try {
                val original = driveService.downloadTrainingDataText().getOrThrow()
                val weeks = SyncReconciler.parseWeeks(original)
                if (!SyncReconciler.updateFeedback(weeks, target.stravaId, feedback)) {
                    snackbar.showSnackbar("That activity is no longer in the log.")
                } else {
                    val text = SyncReconciler.serialize(original, weeks)
                    driveService.saveTrainingDataText(text).getOrThrow()
                    editTarget = null
                    reloadHtml()
                    snackbar.showSnackbar("Ratings updated.")
                }
            } catch (e: Exception) {
                snackbar.showSnackbar("Couldn't save — ${describe(e)}")
            }
            editSaving = false
        }
    }

    fun openEndWeek() {
        if (firing) return
        guidelines = ""
        showEndWeek = true
        loadingWeeks = true
        scope.launch {
            driveService.readWeekSummaries().fold(
                onSuccess = { list ->
                    weekOptions = list
                    selectedWeek = (list.firstOrNull { it.status == "current" }
                        ?: list.lastOrNull { it.status != "planned" }
                        ?: list.lastOrNull())?.n
                },
                onFailure = {
                    snackbar.showSnackbar("Couldn't load weeks — ${it.message}")
                    showEndWeek = false
                }
            )
            loadingWeeks = false
        }
    }

    fun submitEndWeek() {
        val wk = selectedWeek ?: return
        if (firing) return
        firing = true
        showEndWeek = false
        scope.launch {
            driveService.endWeek(
                wk, guidelines,
                prefs?.coachFireUrl.orEmpty(), prefs?.coachFireToken.orEmpty()
            ).fold(
                onSuccess = {
                    snackbar.showSnackbar("Week $wk closed — coach is evaluating & replanning. Reload shortly.")
                },
                onFailure = { snackbar.showSnackbar("Couldn't close the week — ${it.message}") }
            )
            firing = false
        }
    }

    // Bridge target: the popup's "Coach review this run" button. Fires the
    // coaching Routine on demand to rate that specific logged run (sync no longer
    // does this automatically).
    fun onCoachActivity(stravaId: Long) {
        if (reviewing) return
        reviewing = true
        scope.launch {
            driveService.triggerCoaching(
                prefs?.coachFireUrl.orEmpty(), prefs?.coachFireToken.orEmpty(),
                "Run the coaching review now and rate the logged run with Strava id $stravaId " +
                    "(plus any other newly logged runs or unanalysed completed weeks)."
            ).fold(
                onSuccess = { snackbar.showSnackbar("Coach review started — reload in a moment to see the rating.") },
                onFailure = { snackbar.showSnackbar("Couldn't start coach review — ${describe(it)}") }
            )
            reviewing = false
        }
    }

    LaunchedEffect(Unit) { loadOrSignIn() }

    // Surface sync progress/results as snackbars so the WebView stays visible.
    LaunchedEffect(syncState) {
        when (val s = syncState) {
            is SyncState.Success -> { snackbar.showSnackbar(s.message); syncState = SyncState.Idle }
            is SyncState.Error -> { snackbar.showSnackbar(s.message); syncState = SyncState.Idle }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Training Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadOrSignIn() }, enabled = !busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                    TextButton(onClick = { startSync() }, enabled = !busy) {
                        Text(if (syncing) "…" else "Sync")
                    }
                    TextButton(onClick = { openEndWeek() }, enabled = !firing && !syncing) {
                        Text(if (firing) "…" else "End week")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { error = null; loadOrSignIn() }) { Text("Retry") }
                }
                htmlContent != null -> HtmlWebView(
                    html = htmlContent!!,
                    onEditActivity = { onEditActivity(it) },
                    onCoachActivity = { onCoachActivity(it) }
                )
            }

            // A thin progress line while a sync is in flight (the WebView stays up).
            if (syncState is SyncState.Loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }

            if (showEndWeek) {
                EndWeekDialog(
                    weeks = weekOptions,
                    selectedWeek = selectedWeek,
                    onSelectWeek = { selectedWeek = it },
                    guidelines = guidelines,
                    onGuidelines = { guidelines = it },
                    loading = loadingWeeks,
                    onConfirm = { submitEndWeek() },
                    onDismiss = { showEndWeek = false }
                )
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

    editTarget?.let { t ->
        EditActivityDialog(
            title = t.title,
            subtitle = t.subtitle,
            initial = t.initial,
            saving = editSaving,
            onSave = { saveEdit(it) },
            onCancel = { if (!editSaving) editTarget = null }
        )
    }
}

@Composable
private fun EndWeekDialog(
    weeks: List<WeekSummary>,
    selectedWeek: Int?,
    onSelectWeek: (Int) -> Unit,
    guidelines: String,
    onGuidelines: (String) -> Unit,
    loading: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val sel = weeks.firstOrNull { it.n == selectedWeek }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("End week") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Loading weeks…")
                    }
                } else {
                    Text("Which week are you closing?", style = MaterialTheme.typography.titleSmall)
                    Box {
                        OutlinedButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(sel?.let { "Week ${it.n} · ${it.dates}" } ?: "Select a week")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            weeks.forEach { w ->
                                DropdownMenuItem(
                                    text = {
                                        Text("Week ${w.n} · ${w.dates}" +
                                            if (w.status == "current") "  • current" else "")
                                    },
                                    onClick = { onSelectWeek(w.n); menuOpen = false }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = guidelines,
                        onValueChange = onGuidelines,
                        label = { Text("Guidelines for next week (optional)") },
                        placeholder = { Text("e.g. traveling Mon–Wed, keep it flat, foot still tender") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    Text(
                        "The coach will evaluate this week and replan the next few, using these notes plus its own read of your runs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !loading && selectedWeek != null) {
                Text("Close week & replan")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlWebView(
    html: String,
    onEditActivity: (Long) -> Unit = {},
    onCoachActivity: (Long) -> Unit = {}
) {
    // Keep the bridge pointed at the latest callbacks across recompositions even
    // though the JavascriptInterface is installed once in the factory.
    val editCb = rememberUpdatedState(onEditActivity)
    val coachCb = rememberUpdatedState(onCoachActivity)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // The pages bring their own dark theme; stop WebView from applying
                // its own algorithmic darkening on top (which hides popup text).
                // The color-scheme meta covers newer WebViews; this covers API 29–32.
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    settings.forceDark = WebSettings.FORCE_DARK_OFF
                }
                addJavascriptInterface(object {
                    // The bridge runs on a WebView worker thread; hop to the UI
                    // thread before touching Compose state.
                    @JavascriptInterface
                    fun editActivity(stravaId: String) {
                        val id = stravaId.toLongOrNull() ?: return
                        post { editCb.value(id) }
                    }
                    @JavascriptInterface
                    fun coachActivity(stravaId: String) {
                        val id = stravaId.toLongOrNull() ?: return
                        post { coachCb.value(id) }
                    }
                }, "AndroidBridge")
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    )
}
