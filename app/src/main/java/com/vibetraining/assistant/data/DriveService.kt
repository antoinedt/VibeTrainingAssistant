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
private const val COMPARE_NOTES_BASE = "compare_notes"
private const val TRAINING_DATA_NAME = "training_data.js"
// Per-week overlay files (week_NN.js). Folded over the base training_data at
// read time for the view paths so a single week can be revised by uploading one
// small file. See readWeekOverlays / readAssembledTrainingData.
private const val WEEK_OVERLAY_PREFIX = "week_"
// Per-week athlete input written when a week is closed ("End week"): the coach
// reads week_input_<n>.json as its own guidance channel, separate from the
// Strava-driven training data and the coach's own notes.
private const val WEEK_INPUT_BASE = "week_input"

// On-demand coaching: the app fires a Claude Code Routine through its per-routine
// /fire endpoint. The URL and bearer token live in this small JSON file in the
// Drive folder ({"url","token","text"?}) rather than in the APK, so the secret
// stays in the user's private Drive and can be rotated without a rebuild.
private const val ROUTINE_TRIGGER_NAME = "routine_trigger.json"
private const val ROUTINE_BETA_HEADER = "experimental-cc-routine-2026-04-01"
private const val ANTHROPIC_VERSION = "2023-06-01"

// Bundled Compare template (still rendered by the app). The Training Log
// template is required to live in Drive — see fetchTrainingHtml.
private const val COMPARE_ASSET = "training_comparison.html"
private const val TRAINING_DATA_PLACEHOLDER = "/*__TRAINING_DATA__*/"
private const val COACH_DATA_PLACEHOLDER = "__COACH_DATA__"

/** Outcome of opening the Compare screen: the HTML to render plus whether the
 *  insights were freshly generated and written back to Drive. */
data class CompareResult(val html: String, val regenerated: Boolean)

/** Lightweight week descriptor for the "End week" picker. */
data class WeekSummary(val n: Int, val dates: String, val status: String)

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

    /**
     * Per-week overlay files let a single week be revised by uploading one small
     * file (`week_NN.js`, ~2 KB) instead of rewriting the whole plan. They are a
     * read-time fold over the base training_data for the *view* paths only — the
     * Strava sync read/write keep using the monolithic base, so actuals are never
     * touched by this. A week number maps to the highest-version file
     * (`week_NN.js` is v0, `week_NN_v.js` is v_); each holds one week's JSON
     * object. Returns week-number → file contents.
     */
    private fun readWeekOverlays(drive: Drive): Map<Int, String> {
        val rx = Regex("^${WEEK_OVERLAY_PREFIX}(\\d{2})(?:_(\\d+))?\\.js$")
        val files = drive.files().list()
            .setQ("'$DRIVE_FOLDER_ID' in parents and name contains '$WEEK_OVERLAY_PREFIX' and trashed = false")
            .setFields("files(id, name)")
            .execute().files
        val best = HashMap<Int, Pair<Int, String>>()  // week -> (version, fileId)
        for (f in files) {
            val m = rx.matchEntire(f.name) ?: continue
            val week = m.groupValues[1].toInt()
            val ver = m.groupValues[2].toIntOrNull() ?: 0
            val cur = best[week]
            if (cur == null || ver > cur.first) best[week] = ver to f.id
        }
        return best.mapValues { (_, v) -> downloadById(drive, v.second) }
    }

    /**
     * Base training_data*.js with any per-week overlays folded in, returned as a
     * `const WEEKS=[…]` string identical in shape to the monolithic file. An
     * overlay replaces a week only when the base week is still `planned`: once a
     * week is `current`/`actual` the live Strava data owns it, so a stale future
     * plan can never mask real activities. Falls back to the raw base when no
     * overlays apply.
     */
    private fun readAssembledTrainingData(drive: Drive): String {
        val baseJs = readLatestTrainingData(drive)
        val overlays = runCatching { readWeekOverlays(drive) }.getOrDefault(emptyMap())
        if (overlays.isEmpty()) return baseJs
        val weeks = CompareRenderer.parseWeeks(baseJs)
        var applied = 0
        for (i in 0 until weeks.length()) {
            val w = weeks.getJSONObject(i)
            if (w.optString("status") != "planned") continue  // live data owns actual/current weeks
            val ov = overlays[w.optInt("n", -1)] ?: continue
            val obj = runCatching {
                org.json.JSONObject(ov.substring(ov.indexOf('{'), ov.lastIndexOf('}') + 1))
            }.getOrNull() ?: continue
            weeks.put(i, obj)
            applied++
        }
        return if (applied == 0) baseJs else "// AUTO-GENERATED\n\nconst WEEKS = $weeks;\n"
    }

    /** Reads the latest coach_notes*.json overlay, or null when none exists. */
    private fun readLatestCoachNotes(drive: Drive): String? =
        latestVersionId(drive, COACH_NOTES_BASE, "json")?.let { downloadById(drive, it) }

    /** Reads the latest compare_notes*.json overlay (the coach-authored Key
     *  Insights for the Cycle Comparison), or null when none exists. */
    private fun readLatestCompareNotes(drive: Drive): String? =
        latestVersionId(drive, COMPARE_NOTES_BASE, "json")?.let { downloadById(drive, it) }

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
            val dataJs = readAssembledTrainingData(drive)
            // Coach analysis lives in a separate, assistant-maintained overlay so
            // it never collides with the Strava-driven training data; the template
            // merges it into each activity/week before rendering.
            val coachJson = readLatestCoachNotes(drive) ?: "{}"
            // The template must live in Drive (so the presentation is updated
            // without an app build, and it's unambiguous which one is in use).
            // No bundled fallback: a missing/invalid template fails loudly so it's
            // obvious the Drive file needs (re)uploading.
            val template = readLatestTemplate(drive)?.takeIf { it.contains(TRAINING_DATA_PLACEHOLDER) }
                ?: error("Missing training_log.html in your Drive folder. Upload the template (with the data placeholder) there to view the Training Log.")
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
     * references). The Key Insights come from a coach-authored compare_notes*.json
     * overlay in Drive when present (so the analysis can be regenerated
     * autonomously), falling back to the built-in text. When the data or the
     * overlay has changed since the last build — tracked by a checksum embedded
     * in the page — the fresh page is written back to Drive.
     */
    suspend fun regenerateOrLoadComparison(): Result<CompareResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val drive = buildDrive() ?: error("Not signed in to Google")
                val dataJs = readAssembledTrainingData(drive)
                val compareJson = readLatestCompareNotes(drive)
                // Fold the overlay into the checksum so refreshed insights also
                // trigger a rewrite, not just changed training data.
                val checksum = CompareRenderer.checksum(dataJs + (compareJson ?: ""))
                val facts = CompareRenderer.deriveFacts(CompareRenderer.parseWeeks(dataJs))
                val insights = CompareRenderer.parseInsights(compareJson)
                val template = loadAsset(COMPARE_ASSET)
                val html = CompareRenderer.fillTemplate(template, facts, checksum, insights)

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
    suspend fun triggerCoaching(url: String = "", token: String = "", text: String = ""): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                var u = url.trim()
                var t = token.trim()
                var x = text.trim()
                if (u.isBlank() || t.isBlank()) {
                    // Fall back to the Drive-hosted config for anything not supplied.
                    val drive = buildDrive() ?: error("Not signed in to Google")
                    val config = org.json.JSONObject(downloadText(drive, ROUTINE_TRIGGER_NAME))
                    if (u.isBlank()) u = config.optString("url").trim()
                    if (t.isBlank()) t = config.optString("token").trim()
                    if (x.isBlank()) x = config.optString("text").trim()
                }
                if (u.isBlank()) error("No routine URL set — add it in Settings.")
                if (t.isBlank()) error("No routine token set — add it in Settings.")
                postFire(u, t, x.ifBlank { "Run the coaching review now." })
            }
        }

    /** Weeks (number, dates, status) parsed from the live training data, for the
     *  "End week" picker. Ordered as stored (chronological). */
    suspend fun readWeekSummaries(): Result<List<WeekSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = buildDrive() ?: error("Not signed in to Google")
            val weeks = CompareRenderer.parseWeeks(readAssembledTrainingData(drive))
            (0 until weeks.length()).map { i ->
                val w = weeks.getJSONObject(i)
                WeekSummary(w.optInt("n", i + 1), w.optString("dates"), w.optString("status"))
            }
        }
    }

    /**
     * Closes a week: writes the athlete's next-week guidelines to
     * `week_input_<n>.json` in Drive (overwriting any prior close of the same
     * week), then fires the coaching Routine to evaluate the week and replan the
     * upcoming weeks. Returns the new coaching session URL (or "started").
     */
    suspend fun endWeek(
        week: Int,
        guidelines: String,
        url: String = "",
        token: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = buildDrive() ?: error("Not signed in to Google")
            val payload = org.json.JSONObject()
                .put("week", week)
                .put("guidelines", guidelines.trim())
                .put("closedAt", java.time.Instant.now().toString())
            uploadText(drive, "${WEEK_INPUT_BASE}_$week.json", payload.toString(2), "application/json")
            triggerCoaching(url, token, endWeekTask(week)).getOrThrow()
        }
    }

    /** Task handed to the coaching Routine when a week is closed. */
    private fun endWeekTask(week: Int): String =
        "The athlete just closed week $week of the Berlin build. " +
        "1) Read week_input_$week.json in the Drive training folder for their guidelines for the coming weeks. " +
        "2) Read the latest training_data (including this week's logged runs — note the new intensity vs effort " +
        "split, plus injury ratings and recaps) and the latest coach_notes overlay. " +
        "3) Evaluate week $week: write a week rating, analysis and updated goal probabilities into a new " +
        "coach_notes overlay version in Drive. " +
        "4) Replan the next ~3–4 upcoming planned weeks in a new training_data version, weighing the athlete's " +
        "guidelines together with your own evaluation (fatigue, the intensity/effort gap, injury signals). " +
        "Keep the peak/taper periodization and the race date intact unless something clearly warrants a change. " +
        "Apply the changes directly by writing the new files."

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
