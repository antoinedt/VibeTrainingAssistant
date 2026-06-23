package com.vibetraining.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.vibetraining.assistant.data.StravaAuthBus
import com.vibetraining.assistant.ui.navigation.AppNavigation
import com.vibetraining.assistant.ui.theme.VibeTrainingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleStravaRedirect(intent)
        setContent {
            VibeTrainingTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStravaRedirect(intent)
    }

    /** Forwards the Strava OAuth result (vibe://strava-auth?code=… or ?error=…)
     *  to the sync coroutine. An empty string signals denial/cancellation. */
    private fun handleStravaRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "vibe" && data.host == "strava-auth") {
            val code = data.getQueryParameter("code")
            StravaAuthBus.codes.trySend(code ?: "")
        }
    }
}
