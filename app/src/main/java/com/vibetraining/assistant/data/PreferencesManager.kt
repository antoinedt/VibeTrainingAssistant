package com.vibetraining.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vibe_prefs")

object PreferencesKeys {
    val STRAVA_CLIENT_ID = stringPreferencesKey("strava_client_id")
    val STRAVA_CLIENT_SECRET = stringPreferencesKey("strava_client_secret")
}

data class AppPreferences(
    val stravaClientId: String = "",
    val stravaClientSecret: String = ""
)

class PreferencesManager(private val context: Context) {

    val preferences: Flow<AppPreferences> = context.dataStore.data.map { prefs ->
        AppPreferences(
            stravaClientId = prefs[PreferencesKeys.STRAVA_CLIENT_ID] ?: "",
            stravaClientSecret = prefs[PreferencesKeys.STRAVA_CLIENT_SECRET] ?: ""
        )
    }

    suspend fun saveStravaCredentials(clientId: String, clientSecret: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.STRAVA_CLIENT_ID] = clientId
            prefs[PreferencesKeys.STRAVA_CLIENT_SECRET] = clientSecret
        }
    }
}
