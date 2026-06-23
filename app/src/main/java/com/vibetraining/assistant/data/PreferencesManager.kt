package com.vibetraining.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vibe_prefs")

object PreferencesKeys {
    val STRAVA_CLIENT_ID = stringPreferencesKey("strava_client_id")
    val STRAVA_CLIENT_SECRET = stringPreferencesKey("strava_client_secret")
    val STRAVA_ACCESS_TOKEN = stringPreferencesKey("strava_access_token")
    val STRAVA_REFRESH_TOKEN = stringPreferencesKey("strava_refresh_token")
    val STRAVA_EXPIRES_AT = longPreferencesKey("strava_expires_at")
    val CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")
}

data class AppPreferences(
    val stravaClientId: String = "",
    val stravaClientSecret: String = "",
    val stravaAccessToken: String = "",
    val stravaRefreshToken: String = "",
    val stravaExpiresAt: Long = 0L,
    val claudeApiKey: String = ""
)

class PreferencesManager(private val context: Context) {

    val preferences: Flow<AppPreferences> = context.dataStore.data.map { prefs ->
        AppPreferences(
            stravaClientId = prefs[PreferencesKeys.STRAVA_CLIENT_ID] ?: "",
            stravaClientSecret = prefs[PreferencesKeys.STRAVA_CLIENT_SECRET] ?: "",
            stravaAccessToken = prefs[PreferencesKeys.STRAVA_ACCESS_TOKEN] ?: "",
            stravaRefreshToken = prefs[PreferencesKeys.STRAVA_REFRESH_TOKEN] ?: "",
            stravaExpiresAt = prefs[PreferencesKeys.STRAVA_EXPIRES_AT] ?: 0L,
            claudeApiKey = prefs[PreferencesKeys.CLAUDE_API_KEY] ?: ""
        )
    }

    suspend fun saveStravaCredentials(clientId: String, clientSecret: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.STRAVA_CLIENT_ID] = clientId
            prefs[PreferencesKeys.STRAVA_CLIENT_SECRET] = clientSecret
        }
    }

    suspend fun saveClaudeApiKey(apiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.CLAUDE_API_KEY] = apiKey
        }
    }

    suspend fun saveStravaTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.STRAVA_ACCESS_TOKEN] = accessToken
            prefs[PreferencesKeys.STRAVA_REFRESH_TOKEN] = refreshToken
            prefs[PreferencesKeys.STRAVA_EXPIRES_AT] = expiresAt
        }
    }
}
