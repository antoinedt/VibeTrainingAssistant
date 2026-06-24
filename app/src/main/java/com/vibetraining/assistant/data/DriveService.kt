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
// Base names of versioned files. The app always uses the highest-numbered one:
// `<base>.<ext>` is version 0, `<base>_<n>.<ext>` is version n. This lets a new
// version be added without overwriting the previous file.
private const val TRAINING_DATA_BASE = "training_data"
private const val COACH_NOTES_BASE = "coach_notes"
private const val TRAINING_DATA_NAME = "training_data.js"

// On-demand coaching: the app fires a Claude Code Routine through its per-routine
// /fire endpoint. The URL and bearer token live in this small JSON file in the
// Drive folder ({"url","token","text"?}) rather than in the APK, so the secret
// stays in the user's private Drive and can be rotated without a rebuild.
private const val ROUTINE_TRIGGER_NAME = "routine_trigger.json"
private const val ROUTINE_BETA_HEADER = "experimental-cc-routine-2026-04-01"
private const val ANTHROPIC_VERSION = "2023-06-01"

// Bundled presentation templates; the app owns the rendering so the popups,
// charts, and insights are correct regardless of what HTML lives in Drive.
private const val TRAINING_LOG_ASSET = "training_log.html"
private const val COMPARE_ASSET = "training_comparison.html"
private const val TRAINING_DATA_PLACEHOLDER = "/*__TRAINING_DATA__*/"
private const val COACH_DATA_PLACEHOLDER = "__COACH_DATA__"

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
        return downloadById(drive, id)
    }

    private fun downloadById(drive: Drive, id: String): String =
        drive.files().get(id).executeMediaAsInputStream().use { it.bufferedReader().readText() }

    /**
     * Id of the highest-numbered file matching `<base>.<ext>` / `<base>_<n>.<ext>`
     * in the folder (bare name counts as version 0), or null if none exist.
     */
    private fun latestVersionId(drive: Drive, base: String, ext: String): String? {
        val regex = Regex("^" + Regex.escape(base) + "(?:_(\\d+))?\\." + Regex.escape(ext) + "$")
        val files = drive.files().list()
            .setQ("'$DRIVE_FOLDER_ID' in parents and name contains '$base' and trashed = false")
            .setFields("files(id, name)")
            .execute().files
        return files
            .mapNotNull { f -> regex.matchEntire(f.name)?.let { f to (it.groupValues[1].toIntOrNull() ?: 0) } }
            .maxByOrNull { it.second }
            ?.first?.id
    }

    /** Reads the latest training_data*.js from the folder. */
    private fun readLatestTrainingData(drive: Drive): String {
        val id = latestVersionId(drive, TRAINING_DATA_BASE, "js")
            ?: error("No training_data*.js found in the Drive folder")
        return downloadById(drive, id)
    }

    /** Reads the latest coach_notes*.json overlay, or null when none exists. */
    private fun readLatestCoachNotes(drive: Drive): String? =
        latestVersionId(drive, COACH_NOTES_BASE, "json")?.let { downloadById(drive, it) }

    /** The latest training_log*.html template hosted in Drive, or null when none
     *  exists. Lets the Training Log presentation be updated without an app build. */
    private fun readLatestTemplate(drive: Drive): String? =
        latestVersionId(drive, "training_log", "html")
            ?.let { runCatching { downloadById(drive, it) }.getOrNull() }

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
            val dataJs = readLatestTrainingData(drive)
            // Coach analysis lives in a separate, assistant-maintained overlay so
            // it never collides with the Strava-driven training data; the template
            // merges it into each activity/week before rendering.
            val coachJson = readLatestCoachNotes(drive) ?: "{}"
            // Prefer a Drive-hosted template (so the presentation can be updated
            // without an app build); fall back to the bundled asset, which always
            // carries the data placeholder.
            val template = readLatestTemplate(drive)?.takeIf { it.contains(TRAINING_DATA_PLACEHOLDER) }
                ?: loadAsset(TRAINING_LOG_ASSET)
            // Kotlin's String.replace(String, String) is a literal replacement,
            // so '$' or '\' in the data are inserted verbatim.
            template
                .replace(TRAINING_DATA_PLACEHOLDER, dataJs)
                .replace(COACH_DATA_PLACEHOLDER, coachJson)
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
                val dataJs = readLatestTrainingData(drive)
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

    /** Latest training_data*.js from Drive, for diffing newly-synced activities. */
    suspend fun downloadTrainingDataText(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = buildDrive() ?: error("Not signed in to Google")
            readLatestTrainingData(drive)
        }
    }

    /** Writes the reconciled data back over the latest training_data*.js (in
     *  place), or creates `training_data.js` if none exists yet. */
    suspend fun saveTrainingDataText(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = buildDrive() ?: error("Not signed in to Google")
            val media = ByteArrayContent("text/javascript", text.toByteArray(Charsets.UTF_8))
            val id = latestVersionId(drive, TRAINING_DATA_BASE, "js")
            if (id != null) {
                drive.files().update(id, com.google.api.services.drive.model.File(), media).execute()
            } else {
                uploadText(drive, TRAINING_DATA_NAME, text, "text/javascript")
            }
            Unit  // keep the runCatching result as Result<Unit>
        }
    }

    /**
     * Fires the coaching Routine on demand by POSTing to its /fire endpoint,
     * returning the new session URL (or "started"). The URL+token come from the
     * app Settings; if either is blank, falls back to a `routine_trigger.json`
     * ({"url","token","text"?}) in the Drive folder. Either way the bearer token
     * lives outside the APK and can be changed without a rebuild.
     */
    suspend fun triggerCoaching(url: String = "", token: String = ""): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                var u = url.trim()
                var t = token.trim()
                var text = ""
                if (u.isBlank() || t.isBlank()) {
                    // Fall back to the Drive-hosted config for anything not supplied.
                    val drive = buildDrive() ?: error("Not signed in to Google")
                    val config = org.json.JSONObject(downloadText(drive, ROUTINE_TRIGGER_NAME))
                    if (u.isBlank()) u = config.optString("url").trim()
                    if (t.isBlank()) t = config.optString("token").trim()
                    text = config.optString("text").trim()
                }
                if (u.isBlank()) error("No routine URL set — add it in Settings.")
                if (t.isBlank()) error("No routine token set — add it in Settings.")
                postFire(u, t, text.ifBlank { "Run the coaching review now." })
            }
        }

    private fun postFire(url: String, token: String, text: String): String {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("anthropic-beta", ROUTINE_BETA_HEADER)
            setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = org.json.JSONObject().put("text", text).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("Routine fire failed (HTTP $code): ${resp.take(300)}")
            return org.json.JSONObject(resp).optString("claude_code_session_url")
                .ifBlank { "started" }
        } finally {
            conn.disconnect()
        }
    }
}
