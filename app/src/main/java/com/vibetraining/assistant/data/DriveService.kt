package com.vibetraining.assistant.data

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DRIVE_FOLDER_ID = "16tc_gd-7k8kuBN-QjLuqfIUQ4SrGkGPs"
private const val TRAINING_HTML_NAME = "berlin_training_log.html"
private const val COMPARE_HTML_NAME = "training_comparison.html"

class DriveService(private val context: Context) {

    private fun buildDrive(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_READONLY)
        ).also { it.selectedAccount = account.account }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("VibeTrainingAssistant")
            .build()
    }

    suspend fun fetchHtmlContent(fileName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = buildDrive() ?: error("Not signed in to Google")
            val result = drive.files().list()
                .setQ("'$DRIVE_FOLDER_ID' in parents and name = '$fileName' and trashed = false")
                .setFields("files(id, name)")
                .execute()
            val file = result.files.firstOrNull() ?: error("File '$fileName' not found in Drive folder")
            drive.files().get(file.id).executeMediaAsInputStream().use { stream ->
                stream.bufferedReader().readText()
            }
        }
    }

    suspend fun fetchTrainingHtml(): Result<String> = fetchHtmlContent(TRAINING_HTML_NAME)
    suspend fun fetchCompareHtml(): Result<String> = fetchHtmlContent(COMPARE_HTML_NAME)

    fun isSignedIn(): Boolean = GoogleSignIn.getLastSignedInAccount(context) != null
}
