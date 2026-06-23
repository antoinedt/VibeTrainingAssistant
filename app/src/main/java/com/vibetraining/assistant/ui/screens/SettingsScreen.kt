package com.vibetraining.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vibetraining.assistant.data.PreferencesManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager(context) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val prefs by prefsManager.preferences.collectAsState(
        initial = com.vibetraining.assistant.data.AppPreferences()
    )

    var clientId by remember(prefs.stravaClientId) { mutableStateOf(prefs.stravaClientId) }
    var clientSecret by remember(prefs.stravaClientSecret) { mutableStateOf(prefs.stravaClientSecret) }
    var secretVisible by remember { mutableStateOf(false) }

    // Only enable Save when the fields differ from what's already stored, so an
    // unchanged form leaves the button greyed out and inert.
    val hasChanges = clientId.trim() != prefs.stravaClientId ||
        clientSecret.trim() != prefs.stravaClientSecret

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Strava API",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Create an app at strava.com/settings/api to get your Client ID and Secret.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Client ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = clientSecret,
                onValueChange = { clientSecret = it },
                label = { Text("Client Secret") },
                singleLine = true,
                visualTransformation = if (secretVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { secretVisible = !secretVisible }) {
                        Text(if (secretVisible) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    scope.launch {
                        prefsManager.saveStravaCredentials(clientId.trim(), clientSecret.trim())
                        snackbarHost.showSnackbar("Saved")
                    }
                },
                enabled = hasChanges,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Google Drive",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Drive access uses your Google account sign-in on the Training and Compare screens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
