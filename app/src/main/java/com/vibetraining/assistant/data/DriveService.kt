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
private const val COMPARE_HTML_NAME = "training_comparison.html"
private const val TRAINING_DATA_NAME = "training_data.js"

// Bundled presentation template; the app owns the rendering so the popup and
// charts are guaranteed correct regardless of what HTML lives in Drive.
private const val TRAINING_LOG_ASSET = "training_log.html"
private const val TRAINING_DATA_PLACEHOLDER = "/*__TRAINING_DATA__*/"

// Matches <script src="..."></script> tags so relatively-referenced companion
// files can be inlined. Absolute (http/https/protocol-relative) srcs are kept.
private val SCRIPT_SRC_REGEX = Regex(
    """<script[^>]*\ssrc=["']([^"']+)["'][^>]*>\s*</script>""",
    RegexOption.IGNORE_CASE
)

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
            val html = downloadText(drive, fileName)
            inlineRelativeScripts(drive, html)
        }
    }

    private fun downloadText(drive: Drive, fileName: String): String {
        val result = drive.files().list()
            .setQ("'$DRIVE_FOLDER_ID' in parents and name = '$fileName' and trashed = false")
            .setFields("files(id, name)")
            .execute()
        val file = result.files.firstOrNull() ?: error("File '$fileName' not found in Drive folder")
        return drive.files().get(file.id).executeMediaAsInputStream().use { stream ->
            stream.bufferedReader().readText()
        }
    }

    /**
     * The HTML pages reference companion data files with relative paths (e.g.
     * `<script src="training_data.js"></script>`). A WebView loaded from an
     * in-memory string can't resolve those, so fetch each referenced file from
     * the same Drive folder and inline its contents. Absolute URLs (e.g. a CDN)
     * are left untouched. If a companion file can't be fetched, the original
     * tag is preserved.
     */
    private fun inlineRelativeScripts(drive: Drive, html: String): String =
        SCRIPT_SRC_REGEX.replace(html) { match ->
            val src = match.groupValues[1]
            val isAbsolute = src.startsWith("http://") ||
                src.startsWith("https://") ||
                src.startsWith("//")
            if (isAbsolute) {
                match.value
            } else {
                val js = runCatching { downloadText(drive, src.substringAfterLast('/')) }.getOrNull()
                if (js != null) "<script>\n$js\n</script>" else match.value
            }
        }

    suspend fun fetchCompareHtml(): Result<String> = fetchHtmlContent(COMPARE_HTML_NAME)

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
            val template = context.assets.open(TRAINING_LOG_ASSET).bufferedReader().use { it.readText() }
            if (!template.contains(TRAINING_DATA_PLACEHOLDER)) {
                error("Training Log template is missing its data placeholder")
            }
            // Kotlin's String.replace(String, String) is a literal replacement,
            // so '$' or '\' in the data are inserted verbatim.
            template.replace(TRAINING_DATA_PLACEHOLDER, dataJs)
        }
    }

    fun isSignedIn(): Boolean = GoogleSignIn.getLastSignedInAccount(context) != null
}
