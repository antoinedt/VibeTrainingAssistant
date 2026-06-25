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

    // Fires the coaching Routine to rate newly logged runs/weeks; the new ratings
    // land in the coach_notes overlay and show on next reload.
    fun runCoachReview() {
        if (firing) return
        firing = true
        scope.launch {
            driveService.triggerCoaching(
                prefs?.coachFireUrl.orEmpty(), prefs?.coachFireToken.orEmpty(),
                "Run the coaching review now: rate newly logged runs and any unanalysed completed weeks."
            ).fold(
                onSuccess = {
                    snackbar.showSnackbar("Coach review started — reload in a moment to see new ratings.")
                },
                onFailure = { snackbar.showSnackbar("Couldn't start coach review — ${it.message}") }
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
                    TextButton(onClick = { runCoachReview() }, enabled = !firing) {
                        Text(if (firing) "…" else "Coach")
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
        }
    }
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
