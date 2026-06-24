package com.vibetraining.assistant.data

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DRIVE_FOLDER_ID = "16tc_gd-7k8kuBN-QjLuqfIUQ4SrGkGPs"
private const val COMPARE_HTML_NAME = "training_comparison.html"
private const val TRAINING_DATA_NAME = "training_data.js"

// Bundled presentation templates; the app owns the rendering so the popups,
// charts, and insights are correct regardless of what HTML lives in Drive.
private const val TRAINING_LOG_ASSET = "training_log.html"
private const val COMPARE_ASSET = "training_comparison.html"
private const val TRAINING_DATA_PLACEHOLDER = "/*__TRAINING_DATA__*/"

/** Outcome of opening the Compare screen: the HTML to render plus whether the
 *  insights were freshly generated and written back to Drive. */
data class CompareResult(val html: String, val regenerated: Boolean)

class DriveService(private val context: Context) {

    private fun buildDrive(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        // Full Drive scope: read the training data/log and write the regenerated
        // comparison back to the folder.
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE)
        ).also { it.selectedAccount = account.account }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("VibeTrainingAssistant")
            .build()
    }

    private fun findFileId(drive: Drive, fileName: String): String? {
        val result = drive.files().list()
            .setQ("'$DRIVE_FOLDER_ID' in parents and name = '$fileName' and trashed = false")
            .setFields("files(id, name)")
            .execute()
        return result.files.firstOrNull()?.id
    }

    /** Creates the file if absent, otherwise overwrites its contents in place. */
    private fun uploadBytes(drive: Drive, fileName: String, bytes: ByteArray, mimeType: String) {
        val media = ByteArrayContent(mimeType, bytes)
        val existingId = findFileId(drive, fileName)
        if (existingId != null) {
            drive.files().update(existingId, com.google.api.services.drive.model.File(), media).execute()
        } else {
            val metadata = com.google.api.services.drive.model.File().apply {
                name = fileName
                parents = listOf(DRIVE_FOLDER_ID)
            }
            drive.files().create(metadata, media).setFields("id").execute()
        }
    }

    private fun uploadText(drive: Drive, fileName: String, content: String, mimeType: String) =
        uploadBytes(drive, fileName, content.toByteArray(Charsets.UTF_8), mimeType)

    private fun downloadText(drive: Drive, fileName: String): String {
        val id = findFileId(drive, fileName) ?: error("File '$fileName' not found in Drive folder")
        return drive.files().get(id).executeMediaAsInputStream().use { stream ->
            stream.bufferedReader().readText()
        }
    }

    private fun loadAsset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    /**
     * Renders the Training Log from the app's own bundled template, injecting
     * the live `training_data.js` downloaded from Drive. This replaces loading
     * the externally-generated HTML so the activity popup reliably shows each
     * activity's full description.
     */
    suspend fun fetchTrainingHtml(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = buildDrive() ?: error("Not signed in to Google")
            val dataJs = downloadText(drive, TRAINING_DATA_NAME)
            val template = loadAsset(TRAINING_LOG_ASSET)
            if (!template.contains(TRAINING_DATA_PLACEHOLDER)) {
                error("Training Log template is missing its data placeholder")
            }
            // Kotlin's String.replace(String, String) is a literal replacement,
            // so '$' or '\' in the data are inserted verbatim.
            template.replace(TRAINING_DATA_PLACEHOLDER, dataJs)
        }
    }

    /**
     * Builds the Cycle Comparison page. Berlin's charts and stats are derived
     * from the live training log every time (Montreal/Chicago are fixed
     * references; the Key Insights are static text in the template). When the
     * training data has changed since the last build — tracked by a checksum
     * embedded in the page — the fresh page is written back to Drive.
     */
    suspend fun regenerateOrLoadComparison(): Result<CompareResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val drive = buildDrive() ?: error("Not signed in to Google")
                val dataJs = downloadText(drive, TRAINING_DATA_NAME)
                val checksum = CompareRenderer.checksum(dataJs)
                val facts = CompareRenderer.deriveFacts(CompareRenderer.parseWeeks(dataJs))
                val template = loadAsset(COMPARE_ASSET)
                val html = CompareRenderer.fillTemplate(template, facts, checksum)

                val existing = runCatching { downloadText(drive, COMPARE_HTML_NAME) }.getOrNull()
                val storedChecksum = existing?.let { CompareRenderer.extractChecksum(it) }
                val changed = storedChecksum == null || storedChecksum != checksum
                if (changed) {
                    runCatching { uploadText(drive, COMPARE_HTML_NAME, html, "text/html") }
                }
                CompareResult(html = html, regenerated = changed)
            }
        }

    fun isSignedIn(): Boolean = GoogleSignIn.getLastSignedInAccount(context) != null

    /** Raw `training_data.js` from Drive, for diffing newly-synced activities. */
    suspend fun downloadTrainingDataText(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = buildDrive() ?: error("Not signed in to Google")
            downloadText(drive, TRAINING_DATA_NAME)
        }
    }

    /** Writes the reconciled `training_data.js` back to the Drive folder. */
    suspend fun saveTrainingDataText(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = buildDrive() ?: error("Not signed in to Google")
            uploadText(drive, TRAINING_DATA_NAME, text, "text/javascript")
        }
    }
}
