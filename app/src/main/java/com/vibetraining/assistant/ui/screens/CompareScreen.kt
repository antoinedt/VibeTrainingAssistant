package com.vibetraining.assistant.ui.screens

import android.annotation.SuppressLint
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.vibetraining.assistant.data.DriveService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CompareScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val driveService = remember { DriveService(context) }
    val scope = rememberCoroutineScope()
    var htmlContent by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            scope.launch {
                loading = true
                driveService.fetchCompareHtml().fold(
                    onSuccess = { htmlContent = it },
                    onFailure = { error = it.message }
                )
                loading = false
            }
        } else {
            error = "Google sign-in failed"
        }
    }

    fun loadOrSignIn() {
        if (driveService.isSignedIn()) {
            scope.launch {
                loading = true
                driveService.fetchCompareHtml().fold(
                    onSuccess = { htmlContent = it },
                    onFailure = { error = it.message }
                )
                loading = false
            }
        } else {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DriveScopes.DRIVE_READONLY))
                .build()
            signInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
        }
    }

    LaunchedEffect(Unit) { loadOrSignIn() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cycle Comparison") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
