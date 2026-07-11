package com.vibetraining.assistant.ui.screens

import android.annotation.SuppressLint
import android.os.Build
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
import com.vibetraining.assistant.data.WeekSummary
import kotlinx.coroutines.launch

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
    var firing by remember { mutableStateOf(false) }
    var showEndWeek by remember { mutableStateOf(false) }
    var loadingWeeks by remember { mutableStateOf(false) }
    var weekOptions by remember { mutableStateOf<List<WeekSummary>>(emptyList()) }
    var selectedWeek by remember { mutableStateOf<Int?>(null) }
    var guidelines by remember { mutableStateOf("") }

    // Opens the "End week" dialog, loading the week list so the athlete can
    // confirm which week they're closing (defaults to the current week).
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

    // Saves the week's guidelines to Drive and fires the coach to evaluate the
    // closed week and replan the upcoming weeks.
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

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            scope.launch {
                loading = true
                driveService.fetchTrainingHtml().fold(
                    onSuccess = { htmlContent = it },
                    onFailure = { error = it.message }
                )
                loading = false
            }
        } else {
            val code = (task.exception as? ApiException)?.statusCode
            error = "Google sign-in failed" +
                (code?.let { " (code $it: ${GoogleSignInStatusCodes.getStatusCodeString(it)})" } ?: "")
        }
    }

    fun loadOrSignIn() {
        if (driveService.isSignedIn()) {
            scope.launch {
                loading = true
                driveService.fetchTrainingHtml().fold(
                    onSuccess = { htmlContent = it },
                    onFailure = { error = it.message }
                )
                loading = false
            }
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
                title = { Text("Training Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadOrSignIn() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                    TextButton(onClick = { openEndWeek() }, enabled = !firing) {
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
                htmlContent != null -> HtmlWebView(html = htmlContent!!)
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
fun HtmlWebView(html: String) {
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
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    )
}
