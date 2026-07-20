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
import java.io.File

private const val DRIVE_FOLDER_ID = "16tc_gd-7k8kuBN-QjLuqfIUQ4SrGkGPs"
private const val COMPARE_HTML_NAME = "training_comparison.html"
// Base names of versioned files. The app always uses the highest-numbered one:
// `<base>.<ext>` is version 0, `<base>_<n>.<ext>` is version n. This lets a new
// version be added without overwriting the previous file.
private const val TRAINING_DATA_BASE = "training_data"
private const val COACH_NOTES_BASE = "coach_notes"
private const val COMPARE_NOTES_BASE = "compare_notes"
private const val TRAINING_DATA_NAME = "training_data.js"
// Derived, coach-facing plan: base actuals + each week's overlay prescription.
// Overwritten in place (never versioned, never hand-edited). The coaching
// Routine should read THIS instead of the raw base so it sees the same plan
// the athlete sees — including the prescribed session for a week in progress.
private const val COACH_ASSEMBLED_NAME = "training_assembled.js"
// Per-week overlay files (week_NN.js). Folded over the base training_data at
// read time for the view paths so a single week can be revised by uploading one
// small file. See weekOverlayFiles / assembleTrainingData.
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

/**
 * Script injected into the Training Log page at fetch time (not baked into the
 * Drive template) so per-activity action buttons — "Edit ratings & notes" and
 * "Coach review this run" — appear in the popup, but only inside the app where
 * the AndroidBridge is present. It wraps the template's openPopup to track the
 * current logged run and hands its Strava id back to native
 * (TrainingScreen.onEditActivity / onCoachActivity) when tapped. Kept app-side
 * so the feature ships with the APK and doesn't depend on re-uploading the
 * Drive-hosted template.
 */
private const val POPUP_BUTTONS_INJECTION = """
<script>
(function(){
  if(!window.AndroidBridge) return;
  if(typeof openPopup!=='function') return;
  var EDIT_CLS={easy:1,tempo:1,long:1,'race-r':1};
  var CUR=null;
  var pop=document.querySelector('#popup .popup');
  if(!pop) return;
  function mkBtn(label, accent){
    var b=document.createElement('button');
    b.textContent=label;
    b.style.cssText='display:none;width:100%;margin-top:10px;background:#0d1020;border:1px solid #1e2540;border-left:3px solid '+accent+';border-radius:8px;padding:11px 14px;cursor:pointer;color:#a8c0e8;font-size:.82rem;font-weight:700;font-family:inherit;text-align:left;letter-spacing:.3px;';
    pop.appendChild(b);
    return b;
  }
  var editBtn=window.AndroidBridge.editActivity ? mkBtn('✎ Edit ratings & notes','var(--easy)') : null;
  var coachBtn=window.AndroidBridge.coachActivity ? mkBtn('🧠 Coach review this run','var(--bike)') : null;
  if(editBtn) editBtn.onclick=function(){ if(CUR!=null){ AndroidBridge.editActivity(String(CUR)); document.getElementById('popup').classList.remove('open'); } };
  if(coachBtn) coachBtn.onclick=function(){ if(CUR!=null){ AndroidBridge.coachActivity(String(CUR)); document.getElementById('popup').classList.remove('open'); } };
  var orig=openPopup;
  window.openPopup=function(idx){
    orig(idx);
    var e=(typeof ACT_DATA!=='undefined')?ACT_DATA[idx]:null;
    CUR=(e&&e.a&&e.a.strava_id!=null&&EDIT_CLS[e.a.cls])?e.a.strava_id:null;
    var show=(CUR!=null)?'block':'none';
    if(editBtn) editBtn.style.display=show;
    if(coachBtn) coachBtn.style.display=show;
  };
})();
</script>
"""

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

    // ── folder listing + on-disk content cache ──────────────────────────────
    // The view path used to make a separate list() call per file base and then
    // download every file (incl. ~10 per-week overlays) on every open — slow.
    // Instead: list the whole folder once, resolve everything from that listing,
    // and download only files whose content we don't already have cached.

    private data class DriveFile(val id: String, val name: String, val modifiedTime: String?)

    /** Every non-trashed file in the training folder, in one paged request. */
    private fun listFolder(drive: Drive): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var pageToken: String? = null
        do {
            val resp = drive.files().list()
                .setQ("'$DRIVE_FOLDER_ID' in parents and trashed = false")
                .setFields("nextPageToken, files(id, name, modifiedTime)")
                .setPageSize(1000)
                .setPageToken(pageToken)
                .execute()
            resp.files.forEach { out.add(DriveFile(it.id, it.name, it.modifiedTime?.toStringRfc3339())) }
            pageToken = resp.nextPageToken
        } while (!pageToken.isNullOrEmpty())
        return out
    }

    /** Highest-versioned `<base>.<ext>` / `<base>_<n>.<ext>` in the listing. */
    private fun latestVersion(files: List<DriveFile>, base: String, ext: String): DriveFile? {
        val regex = Regex("^" + Regex.escape(base) + "(?:_(\\d+))?\\." + Regex.escape(ext) + "$")
        return files
            .mapNotNull { f -> regex.matchEntire(f.name)?.let { f to (it.groupValues[1].toIntOrNull() ?: 0) } }
            .maxByOrNull { it.second }
            ?.first
    }

    /** week-number → highest-version overlay file, from the listing. */
    private fun weekOverlayFiles(files: List<DriveFile>): Map<Int, DriveFile> {
        val rx = Regex("^${WEEK_OVERLAY_PREFIX}(\\d{2})(?:_(\\d+))?\\.js$")
        val best = HashMap<Int, Pair<Int, DriveFile>>()
        for (f in files) {
            val m = rx.matchEntire(f.name) ?: continue
            val week = m.groupValues[1].toInt()
            val ver = m.groupValues[2].toIntOrNull() ?: 0
            val cur = best[week]
            if (cur == null || ver > cur.first) best[week] = ver to f
        }
        return best.mapValues { it.value.second }
    }

    private fun cacheDir(): File = File(context.cacheDir, "drivecache").apply { mkdirs() }

    /**
     * Content of a file, served from disk when we already have this exact
     * (id, modifiedTime) — Drive content is immutable for a given version, and an
     * in-place update bumps modifiedTime, so this can never return stale data.
     * Falls back to a direct download on any cache miss/error.
     */
    private fun cachedDownload(drive: Drive, file: DriveFile): String {
        val mtime = file.modifiedTime ?: return downloadById(drive, file.id)
        val cached = File(cacheDir(), file.id + "_" + mtime.hashCode())
        runCatching { if (cached.exists()) return cached.readText(Charsets.UTF_8) }
        val content = downloadById(drive, file.id)
        runCatching {
            // Drop older cached versions of this file id, then store the new one.
            cacheDir().listFiles { f -> f.name.startsWith(file.id + "_") }?.forEach { it.delete() }
            cached.writeText(content, Charsets.UTF_8)
        }
        return content
    }

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

    /**
     * Highest version number of `<base>.<ext>` / `<base>_<n>.<ext>` in the folder
     * (a bare name counts as version 0), or -1 if none exist or on any error.
     * Lets the UI poll for a fired Routine's output: capture this before firing,
     * then wait for it to climb. Never throws.
     */
    private suspend fun latestVersionNumber(base: String, ext: String): Int =
        withContext(Dispatchers.IO) {
            runCatching {
                val drive = buildDrive() ?: return@runCatching -1
                val regex = Regex("^" + Regex.escape(base) + "(?:_(\\d+))?\\." + Regex.escape(ext) + "$")
                drive.files().list()
                    .setQ("'$DRIVE_FOLDER_ID' in parents and name contains '$base' and trashed = false")
                    .setFields("files(id, name)")
                    .execute().files
                    .mapNotNull { f -> regex.matchEntire(f.name)?.let { it.groupValues[1].toIntOrNull() ?: 0 } }
                    .maxOrNull() ?: -1
            }.getOrDefault(-1)
        }

    /** Highest coach_notes*.json version in the folder (-1 if none). */
    suspend fun coachNotesVersion(): Int = latestVersionNumber(COACH_NOTES_BASE, "json")

    /** Highest compare_notes*.json version in the folder (-1 if none). */
    suspend fun compareNotesVersion(): Int = latestVersionNumber(COMPARE_NOTES_BASE, "json")

    /** Reads the latest training_data*.js from the folder. */
    private fun readLatestTrainingData(drive: Drive): String {
        val id = latestVersionId(drive, TRAINING_DATA_BASE, "js")
            ?: error("No training_data*.js found in the Drive folder")
        return downloadById(drive, id)
    }

    /**
     * Base training_data*.js with any per-week overlays folded in, returned as a
     * `const WEEKS=[…]` string identical in shape to the monolithic file. An
     * overlay (a small `week_NN.js`, v0, or `week_NN_v.js`, vv) replaces a week
     * only when the base week is still `planned`: once a week is `current`/
     * `actual` the live Strava data owns it, so a stale future plan can never
     * mask real activities. Overlays are downloaded (from cache) only for the
     * planned weeks that actually use one. Falls back to the raw base.
     */
    private fun assembleTrainingData(drive: Drive, files: List<DriveFile>): String {
        val baseFile = latestVersion(files, TRAINING_DATA_BASE, "js")
            ?: error("No training_data*.js found in the Drive folder")
        val baseJs = cachedDownload(drive, baseFile)
        val overlays = runCatching { weekOverlayFiles(files) }.getOrDefault(emptyMap())
        if (overlays.isEmpty()) return baseJs
        val weeks = CompareRenderer.parseWeeks(baseJs)
        var applied = 0
        for (i in 0 until weeks.length()) {
            val w = weeks.getJSONObject(i)
            if (w.optString("status") != "planned") continue  // live data owns actual/current weeks
            val ovFile = overlays[w.optInt("n", -1)] ?: continue
            val ov = runCatching { cachedDownload(drive, ovFile) }.getOrNull() ?: continue
            val obj = runCatching {
                org.json.JSONObject(ov.substring(ov.indexOf('{'), ov.lastIndexOf('}') + 1))
            }.getOrNull() ?: continue
            weeks.put(i, obj)
            applied++
        }
        return if (applied == 0) baseJs else "// AUTO-GENERATED\n\nconst WEEKS = $weeks;\n"
    }

    /** Convenience for callers that only need the assembled data (one listing). */
    private fun readAssembledTrainingData(drive: Drive): String =
        assembleTrainingData(drive, listFolder(drive))

    /**
     * The coach-facing plan. Unlike the view assembly (which drops the overlay
     * for the week in progress so live runs aren't masked), this keeps every
     * week's synced actuals in `acts` AND attaches the overlay's prescribed
     * sessions as `plannedActs`, so the coach can judge a completed run against
     * what was actually prescribed — even for the current week. Future planned
     * weeks just get the overlay as their `acts`.
     */
    private fun assembleForCoach(drive: Drive, files: List<DriveFile>): String {
        val baseFile = latestVersion(files, TRAINING_DATA_BASE, "js")
            ?: error("No training_data*.js found in the Drive folder")
        val weeks = CompareRenderer.parseWeeks(cachedDownload(drive, baseFile))
        val overlays = runCatching { weekOverlayFiles(files) }.getOrDefault(emptyMap())
        for (i in 0 until weeks.length()) {
            val w = weeks.getJSONObject(i)
            val ovFile = overlays[w.optInt("n", -1)] ?: continue
            val ov = runCatching { cachedDownload(drive, ovFile) }.getOrNull() ?: continue
            val ovActs = runCatching {
                org.json.JSONObject(ov.substring(ov.indexOf('{'), ov.lastIndexOf('}') + 1))
                    .optJSONArray("acts")
            }.getOrNull() ?: continue
            if (w.optString("status") == "planned") w.put("acts", ovActs)  // future: overlay is the plan
            else w.put("plannedActs", ovActs)                              // done/current: keep actuals, add prescription
        }
        return "// AUTO-GENERATED (coach view: actuals + plannedActs)\n\nconst WEEKS = $weeks;\n"
    }

    /** Regenerates [COACH_ASSEMBLED_NAME] on Drive when it has drifted from the
     *  current base+overlays. Best-effort — never fails the caller. */
    private fun refreshCoachAssembled(drive: Drive, files: List<DriveFile>) {
        val coachJs = assembleForCoach(drive, files)
        val existing = latestVersion(files, "training_assembled", "js")
            ?.let { runCatching { cachedDownload(drive, it) }.getOrNull() }
        if (existing != coachJs) uploadText(drive, COACH_ASSEMBLED_NAME, coachJs, "text/javascript")
    }

    /** Reads the latest compare_notes*.json overlay (the coach-authored Key
     *  Insights for the Cycle Comparison), or null when none exists. */
    private fun readLatestCompareNotes(drive: Drive): String? =
        latestVersionId(drive, COMPARE_NOTES_BASE, "json")?.let { downloadById(drive, it) }

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
            // One folder listing drives everything below; each file is then served
            // from the on-disk cache unless its Drive version changed. This keeps
            // repeat opens fast even with ~10 per-week overlay files.
            val files = listFolder(drive)
            val dataJs = assembleTrainingData(drive, files)
            // Keep the coach-facing plan file in step with what the athlete sees.
            runCatching { refreshCoachAssembled(drive, files) }
            // Coach analysis lives in a separate, assistant-maintained overlay so
            // it never collides with the Strava-driven training data; the template
            // merges it into each activity/week before rendering.
            val coachJson = latestVersion(files, COACH_NOTES_BASE, "json")
                ?.let { cachedDownload(drive, it) } ?: "{}"
            // The template must live in Drive (so the presentation is updated
            // without an app build, and it's unambiguous which one is in use).
            // No bundled fallback: a missing/invalid template fails loudly so it's
            // obvious the Drive file needs (re)uploading.
            val templateFile = latestVersion(files, "training_log", "html")
                ?: error("Missing training_log.html in your Drive folder. Upload the template (with the data placeholder) there to view the Training Log.")
            val template = cachedDownload(drive, templateFile).takeIf { it.contains(TRAINING_DATA_PLACEHOLDER) }
                ?: error("training_log.html in Drive is missing the data placeholder; re-upload the template.")
            // Kotlin's String.replace(String, String) is a literal replacement,
            // so '$' or '\' in the data are inserted verbatim.
            val page = template
                .replace(TRAINING_DATA_PLACEHOLDER, dataJs)
                .replace(COACH_DATA_PLACEHOLDER, coachJson)
            // Add the in-app per-activity buttons just before </body> (falls back
            // to appending if the tag is absent).
            if (page.contains("</body>")) page.replaceFirst("</body>", "$POPUP_BUTTONS_INJECTION</body>")
            else page + POPUP_BUTTONS_INJECTION
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
        "4) Replan the next ~3–4 upcoming planned weeks as per-week overlay files (week_NN.js, or " +
        "week_NN_<v>.js for a higher version) — one JSON week object each, per your instructions — weighing the " +
        "athlete's guidelines together with your own evaluation (fatigue, the intensity/effort gap, injury signals). " +
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
