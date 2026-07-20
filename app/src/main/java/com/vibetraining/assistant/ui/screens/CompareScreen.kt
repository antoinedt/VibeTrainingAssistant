package com.vibetraining.assistant.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.vibetraining.assistant.data.DriveService
import com.vibetraining.assistant.data.PreferencesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CompareScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val driveService = remember { DriveService(context) }
    val prefsManager = remember { PreferencesManager(context) }
    val prefs by prefsManager.preferences.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var htmlContent by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var firing by remember { mutableStateOf(false) }
    // Banner text while the fired analysis Routine runs on the server; null = idle.
    var analysing by remember { mutableStateOf<String?>(null) }
    // Drive can demand a one-time consent screen on first access; hold its
    // recovery intent here and launch it from a LaunchedEffect.
    var pendingConsent by remember { mutableStateOf<Intent?>(null) }

    suspend fun regenerate() {
        loading = true
        // Bound the wait so a stalled Drive/network call surfaces an error
        // instead of spinning forever, and always show a non-empty message.
        val result = withTimeoutOrNull(45_000) { driveService.regenerateOrLoadComparison() }
        when {
            result == null ->
                error = "Google Drive didn't respond (timed out). Check your connection, then tap Reload."
            result.isSuccess -> { htmlContent = result.getOrNull()?.html; error = null }
            else -> {
                val e = result.exceptionOrNull()
                val consent = recoverableConsentIntent(e)
                if (consent != null) pendingConsent = consent
                else error = "Couldn't load the comparison — ${describe(e)}"
            }
        }
        loading = false
    }

    // Fires the Routine to regenerate the cycle-comparison Key Insights. The
    // coach writes a compare_notes overlay to Drive; the new analysis shows on
    // next reload of this page (which folds the overlay into the render).
    fun redoAnalysis() {
        if (firing || analysing != null) return
        firing = true
        scope.launch {
            val baseline = driveService.compareNotesVersion()
            driveService.triggerCoaching(
                prefs?.coachFireUrl.orEmpty(), prefs?.coachFireToken.orEmpty(),
                "Regenerate the cycle comparison analysis: write fresh Key Insights to the " +
                    "compare_notes overlay in Drive, comparing Berlin against the Montreal and Chicago cycles."
            ).fold(
                onSuccess = {
                    // The Routine takes minutes; keep a banner up and poll Drive for
                    // the new compare_notes version, then reload so the fresh
                    // insights appear on their own.
                    analysing = "Coach is rewriting the cycle comparison — this takes a few " +
                        "minutes. The insights refresh automatically when ready."
                    scope.launch {
                        val deadline = System.currentTimeMillis() + 30 * 60_000L
                        while (System.currentTimeMillis() < deadline) {
                            delay(20_000)
                            if (driveService.compareNotesVersion() > baseline) {
                                regenerate()
                                analysing = null
                                snackbar.showSnackbar("Comparison updated with fresh insights.")
                                return@launch
                            }
                        }
                        analysing = null
                        snackbar.showSnackbar("Coach is still working — tap Reload in a few minutes.")
                    }
                },
                onFailure = { snackbar.showSnackbar("Couldn't start analysis — ${it.message}") }
            )
            firing = false
        }
    }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            scope.launch { regenerate() }
        } else {
            val code = (task.exception as? ApiException)?.statusCode
            error = "Google sign-in failed" +
                (code?.let { " (code $it: ${GoogleSignInStatusCodes.getStatusCodeString(it)})" } ?: "")
        }
    }

    // Launches Drive's consent screen; on approval the load is retried, now with
    // the granted permission so the Drive read succeeds.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch { regenerate() }
        } else {
            error = "Google Drive access was declined. Tap Reload to try again."
        }
    }

    LaunchedEffect(pendingConsent) {
        pendingConsent?.let { intent ->
            pendingConsent = null
            consentLauncher.launch(intent)
        }
    }

    fun loadOrSignIn() {
        if (driveService.isSignedIn()) {
            scope.launch { regenerate() }
        } else {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DriveScopes.DRIVE))
                .build()
            signInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
        }
    }

    LaunchedEffect(Unit) { loadOrSignIn() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Cycle Comparison") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadOrSignIn() }, enabled = !loading && analysing == null) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                    TextButton(onClick = { redoAnalysis() }, enabled = !firing && analysing == null) {
                        Text(if (firing || analysing != null) "…" else "Redo analysis")
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
                htmlContent != null -> HtmlWebView(html = htmlContent!!)
            }

            // Persistent banner while the analysis Routine runs in the background.
            analysing?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 3.dp
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}
