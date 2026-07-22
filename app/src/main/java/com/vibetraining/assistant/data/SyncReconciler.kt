package com.vibetraining.assistant.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure logic for reconciling freshly-synced Strava activities against the
 * planned training in `training_data.js`. No Android/Drive dependencies so the
 * matching, classification and JSON rewriting stay easy to reason about.
 *
 * Data model (single source of truth — `training_data.js`):
 *   week  = { n, dates, phase, status, runKm, longKm, acts:[…] }
 *   act   = { d, ic, cls, nm, km?, sub?, flags, desc, strava_id?, …feedback }
 * An activity is "done" iff it carries a `strava_id`; a planned/undone activity
 * has none. Reconciling a synced activity replaces the planned act it matches.
 */
object SyncReconciler {

    /** Berlin race day (a Sunday); the 26-week grid is anchored to it. */
    private val RACE_DAY: LocalDate = LocalDate.of(2026, 9, 27)
    private const val TOTAL_WEEKS = 26
    private val RUN_CLASSES = setOf("easy", "tempo", "long", "race-r")
    private val DAY_FMT = DateTimeFormatter.ofPattern("EEE MMM d", Locale.ENGLISH)

    /** Run-type choices offered when the activity matches no plan. */
    val RUN_TYPES = listOf(
        "easy" to "Easy",
        "tempo" to "Tempo / Quality",
        "long" to "Long",
        "race-r" to "Race"
    )

    /** 1–10 cardio/breathing intensity (RPE) descriptions; index 0 == level 1.
     *  This is the "how hard was the breathing" axis — distinct from EFFORT. */
    val INTENSITY = listOf(
        "Very easy — recovery, could go all day",
        "Easy — comfortable, full conversation",
        "Moderate — steady, breathing slightly up",
        "Somewhat hard — short sentences only",
        "Hard — comfortably hard, focused",
        "Harder — breathing heavy, few words",
        "Vigorous — tough but sustainable",
        "Very hard — near threshold, can't talk",
        "Extremely hard — almost maximal",
        "Maximal — all-out, couldn't sustain"
    )

    /** 1–10 overall effort/difficulty; index 0 == level 1. The "how hard on the
     *  legs / how tough overall given fatigue" axis, independent of breathing —
     *  e.g. an easy-breathing 25 km can still be a high-effort day, and an easy
     *  5 km can feel hard when you're tired from the day before. */
    val EFFORT = listOf(
        "Trivial — felt completely fresh",
        "Very easy — legs light, no effort",
        "Easy — comfortable, legs fine",
        "Moderate — some work, still easy",
        "Noticeable — legs working, manageable",
        "Hard — legs loaded, took real focus",
        "Tough — legs heavy, having to push",
        "Very tough — grinding, legs really tired",
        "Brutal — barely holding form",
        "Maximal — everything I had"
    )

    /** 1–10 injury/pain scale; 1 == fully healthy, 10 == badly hurt. */
    val INJURY = listOf(
        "Fully healthy — no niggles at all",
        "Fine — barely-there awareness",
        "Minor niggle — noticeable, not limiting",
        "Mild — present during the effort",
        "Moderate — altered how I ran",
        "Notable — sore, had to back off",
        "Painful — cut the session short",
        "Quite painful — struggled to continue",
        "Severe — had to stop",
        "Injured — could not run"
    )

    data class RunFeedback(
        val intensity: Int,
        val effort: Int,
        val injury: Int,
        val injuryComment: String,
        val recap: String
    )

    /** A planned, not-yet-done activity in a week, offered in the match picker. */
    data class PendingOption(val index: Int, val label: String, val cls: String)

    // ── file <-> WEEKS array ────────────────────────────────────────────────

    /** Pulls the `WEEKS = [ … ]` array literal out of training_data.js. */
    fun parseWeeks(dataJs: String): JSONArray {
        val s = dataJs.indexOf('[')
        val e = dataJs.lastIndexOf(']')
        require(s >= 0 && e > s) { "training_data.js has no WEEKS array" }
        return JSONArray(dataJs.substring(s, e + 1))
    }

    /** Re-emits the file, preserving the original prefix/suffix around the array. */
    fun serialize(original: String, weeks: JSONArray): String {
        val s = original.indexOf('[')
        val e = original.lastIndexOf(']')
        val prefix = if (s >= 0) original.substring(0, s) else "// AUTO-GENERATED\n\nconst WEEKS = "
        val suffix = if (e >= 0) original.substring(e + 1) else ";\n"
        return prefix + serializeArray(weeks, WEEK_KEY_ORDER, 0) + suffix
    }

    // ── querying ────────────────────────────────────────────────────────────

    fun existingStravaIds(weeks: JSONArray): Set<Long> {
        val ids = HashSet<Long>()
        forEachAct(weeks) { _, a -> a.optLong("strava_id", 0L).takeIf { it != 0L }?.let(ids::add) }
        return ids
    }

    /** Week (1..26) whose Mon–Sun span contains [date], clamped to the cycle. */
    fun weekForDate(date: LocalDate): Int {
        for (n in 1..TOTAL_WEEKS) {
            if (!date.isBefore(weekStart(n)) && !date.isAfter(weekEnd(n))) return n
        }
        return if (date.isBefore(weekStart(1))) 1 else TOTAL_WEEKS
    }

    /** Planned (no `strava_id`) activities in the given week, for the picker. */
    fun pendingOptions(weeks: JSONArray, weekN: Int): List<PendingOption> {
        val acts = weekByN(weeks, weekN)?.optJSONArray("acts") ?: return emptyList()
        val out = ArrayList<PendingOption>()
        for (j in 0 until acts.length()) {
            val a = acts.getJSONObject(j)
            if (a.has("strava_id")) continue
            val km = if (a.has("km")) " (${trimNum(a.optDouble("km"))} km)" else ""
            val label = "${a.optString("d")} · ${a.optString("nm")}$km"
            out.add(PendingOption(j, label, a.optString("cls")))
        }
        return out
    }

    /** Convenience for the UI: planned options in the activity's own week. */
    fun pendingOptionsForActivity(weeks: JSONArray, activity: StravaActivity): List<PendingOption> =
        pendingOptions(weeks, weekForDate(parseDate(activity.startDateLocal)))

    /** "Wed Jun 17"-style label for an activity's date. */
    fun dayLabelFor(activity: StravaActivity): String =
        parseDate(activity.startDateLocal).format(DAY_FMT)

    /** Best-effort run class from distance when the user skips classification. */
    fun distanceClass(activity: StravaActivity): String =
        if (activity.distanceMeters >= 18_000) "long" else "easy"

    // ── classification ──────────────────────────────────────────────────────

    fun isRun(type: String) = type.contains("Run", ignoreCase = true)
    private fun isRide(type: String) =
        type.contains("Ride", true) || type.contains("Bike", true) || type.contains("Zwift", true)
    private fun isWeights(type: String) =
        type.contains("Weight", true) || type.contains("Workout", true)

    /** The activity's class when it isn't a run; null when it can't be inferred. */
    fun nonRunClass(type: String): String? = when {
        isRide(type) -> "bike"
        isWeights(type) -> "wt"
        else -> null
    }

    // ── mutation ────────────────────────────────────────────────────────────

    /**
     * Records [activity] into the week of its date: removes the matched planned
     * act (if [matchedIndex] != null) and appends the synced act, then refreshes
     * that week's volume totals. Mutates [weeks] in place.
     */
    fun applyActivity(
        weeks: JSONArray,
        activity: StravaActivity,
        cls: String?,
        matchedIndex: Int?,
        feedback: RunFeedback?
    ) {
        val date = parseDate(activity.startDateLocal)
        val week = weekByN(weeks, weekForDate(date)) ?: return
        val acts = week.optJSONArray("acts") ?: JSONArray().also { week.put("acts", it) }
        if (matchedIndex != null && matchedIndex in 0 until acts.length()) acts.remove(matchedIndex)
        acts.put(buildAct(activity, cls, date, feedback))
        recomputeWeek(week)
    }

    /** Drops never-completed planned activities from weeks already in the past. */
    fun cleanPastPending(weeks: JSONArray, today: LocalDate) {
        for (i in 0 until weeks.length()) {
            val w = weeks.getJSONObject(i)
            if (!weekEnd(w.optInt("n", i + 1)).isBefore(today)) continue
            val acts = w.optJSONArray("acts") ?: continue
            var j = acts.length() - 1
            while (j >= 0) {
                if (!acts.getJSONObject(j).has("strava_id")) acts.remove(j)
                j--
            }
            recomputeWeek(w)
        }
    }

    /** Date-derived status for week [n]: actual (fully in the past) / current
     *  (today falls in it) / planned (still to come). */
    fun statusFor(n: Int, today: LocalDate): String = when {
        weekEnd(n).isBefore(today) -> "actual"
        !weekStart(n).isAfter(today) -> "current"
        else -> "planned"
    }

    /** Sets each week's `status` from today's date (actual / current / planned). */
    fun normalizeStatuses(weeks: JSONArray, today: LocalDate) {
        for (i in 0 until weeks.length()) {
            val w = weeks.getJSONObject(i)
            w.put("status", statusFor(w.optInt("n", i + 1), today))
        }
    }

    /** runKm (sum) and longKm (max) over the run-class acts of [acts]. */
    fun volumeFor(acts: JSONArray): Pair<Double, Double> {
        var run = 0.0
        var lng = 0.0
        for (j in 0 until acts.length()) {
            val a = acts.getJSONObject(j)
            if (a.optString("cls") in RUN_CLASSES && a.has("km")) {
                val km = a.optDouble("km", 0.0)
                run += km
                if (km > lng) lng = km
            }
        }
        return round2(run) to round2(lng)
    }

    /** The logged (synced) acts of a week — those carrying a `strava_id`. */
    fun loggedActs(week: JSONObject): JSONArray {
        val acts = week.optJSONArray("acts") ?: return JSONArray()
        val out = JSONArray()
        for (j in 0 until acts.length()) {
            val a = acts.getJSONObject(j)
            if (a.has("strava_id")) out.put(a)
        }
        return out
    }

    /** The set of week numbers the given activities fall into (for deciding which
     *  per-week `done` files a sync must rewrite). */
    fun weeksOf(activities: List<StravaActivity>): Set<Int> =
        activities.map { weekForDate(parseDate(it.startDateLocal)) }.toSet()

    /** Week [n]'s logged (strava_id) acts within [weeks] — the complete actuals to
     *  write to that week's `done` file. Empty if the week isn't present. */
    fun loggedActsForWeek(weeks: JSONArray, n: Int): JSONArray =
        weekByN(weeks, n)?.let { loggedActs(it) } ?: JSONArray()

    /**
     * Merges logged runs into a plan for the week in progress: keeps every logged
     * act at its day, substitutes it for the plan's item on that day, and takes
     * the plan for every day not yet logged. A logged day the plan doesn't mention
     * is kept up front. Day labels share the `"EEE MMM d"` format on both sides.
     */
    fun mergeDoneIntoPlan(done: JSONArray, plan: JSONArray): JSONArray {
        val loggedByDay = LinkedHashMap<String, MutableList<JSONObject>>()
        for (j in 0 until done.length()) {
            val a = done.getJSONObject(j)
            loggedByDay.getOrPut(a.optString("d")) { mutableListOf() }.add(a)
        }
        val planDays = HashSet<String>()
        for (j in 0 until plan.length()) planDays.add(plan.getJSONObject(j).optString("d"))
        val merged = JSONArray()
        val emitted = HashSet<String>()
        for ((day, list) in loggedByDay) {
            if (day !in planDays) { list.forEach { merged.put(it) }; emitted.add(day) }
        }
        for (j in 0 until plan.length()) {
            val p = plan.getJSONObject(j)
            val day = p.optString("d")
            if (loggedByDay.containsKey(day)) {
                if (emitted.add(day)) loggedByDay[day]!!.forEach { merged.put(it) }
            } else {
                merged.put(p)
            }
        }
        return merged
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun weekEnd(n: Int): LocalDate = RACE_DAY.minusWeeks((TOTAL_WEEKS - n).toLong())
    private fun weekStart(n: Int): LocalDate = weekEnd(n).minusDays(6)

    private fun weekByN(weeks: JSONArray, n: Int): JSONObject? {
        for (i in 0 until weeks.length()) {
            val w = weeks.getJSONObject(i)
            if (w.optInt("n", -1) == n) return w
        }
        return null
    }

    private inline fun forEachAct(weeks: JSONArray, block: (JSONObject, JSONObject) -> Unit) {
        for (i in 0 until weeks.length()) {
            val w = weeks.getJSONObject(i)
            val acts = w.optJSONArray("acts") ?: continue
            for (j in 0 until acts.length()) block(w, acts.getJSONObject(j))
        }
    }

    private fun buildAct(
        activity: StravaActivity,
        cls: String?,
        date: LocalDate,
        feedback: RunFeedback?
    ): JSONObject {
        val km = round2(activity.distanceMeters / 1000.0)
        val minutes = Math.max(1, Math.round(activity.movingTimeSec / 60.0).toInt())
        val isRun = cls != null && cls in RUN_CLASSES
        val o = JSONObject()
        o.put("d", date.format(DAY_FMT))
        o.put("ic", iconFor(cls))
        if (cls != null) o.put("cls", cls)
        o.put("nm", activity.name)
        if (isRun && km > 0) {
            val pace = pace(activity.distanceMeters, activity.movingTimeSec)
            o.put("km", km)
            if (pace.isNotEmpty()) o.put("sub", pace)
            o.put("desc", "$km km" + if (pace.isNotEmpty()) " @ $pace." else ".")
        } else {
            o.put("sub", "$minutes min")
            o.put("desc", "${activity.name}. $minutes min.")
        }
        o.put("flags", JSONArray())
        o.put("strava_id", activity.id)
        if (feedback != null) putFeedback(o, feedback)
        return o
    }

    /**
     * Writes an activity's athlete feedback (both perceived-effort axes, injury
     * and recap) onto [o], regenerating the human-readable athleteNotes and
     * clearing any legacy single-axis `difficulty` fields. Shared by the initial
     * sync ([buildAct]) and later edits ([updateFeedback]).
     */
    private fun putFeedback(o: JSONObject, f: RunFeedback) {
        o.put("intensity", f.intensity)
        o.put("intensityLabel", INTENSITY[f.intensity - 1])
        o.put("effort", f.effort)
        o.put("effortLabel", EFFORT[f.effort - 1])
        o.put("injury", f.injury)
        o.put("injuryLabel", INJURY[f.injury - 1])
        if (f.injuryComment.isNotBlank()) o.put("injuryComment", f.injuryComment.trim())
        else o.remove("injuryComment")
        if (f.recap.isNotBlank()) o.put("recap", f.recap.trim()) else o.remove("recap")
        o.put("athleteNotes", buildAthleteNotes(f))
        // Drop the pre-split single-axis fields so an edited run carries only the
        // current intensity/effort model.
        o.remove("difficulty")
        o.remove("difficultyLabel")
    }

    private fun actById(weeks: JSONArray, stravaId: Long): JSONObject? {
        for (i in 0 until weeks.length()) {
            val acts = weeks.getJSONObject(i).optJSONArray("acts") ?: continue
            for (j in 0 until acts.length()) {
                val a = acts.getJSONObject(j)
                if (a.optLong("strava_id", 0L) == stravaId) return a
            }
        }
        return null
    }

    /**
     * The athlete's currently-stored feedback for a logged activity, for
     * pre-filling the edit UI. Legacy runs that predate the effort split fall
     * back to the old single-axis `difficulty` for both intensity and effort.
     * Returns null when the id isn't found.
     */
    fun feedbackFor(weeks: JSONArray, stravaId: Long): RunFeedback? {
        val a = actById(weeks, stravaId) ?: return null
        val legacy = a.optInt("difficulty", 0)
        return RunFeedback(
            intensity = a.optInt("intensity", legacy),
            effort = a.optInt("effort", legacy),
            injury = a.optInt("injury", 0),
            injuryComment = a.optString("injuryComment", ""),
            recap = a.optString("recap", "")
        )
    }

    /** A logged activity's display name and "day · Week n" subtitle, for the edit
     *  dialog header. Returns null when the id isn't found. */
    fun labelFor(weeks: JSONArray, stravaId: Long): Pair<String, String>? {
        for (i in 0 until weeks.length()) {
            val w = weeks.getJSONObject(i)
            val acts = w.optJSONArray("acts") ?: continue
            for (j in 0 until acts.length()) {
                val a = acts.getJSONObject(j)
                if (a.optLong("strava_id", 0L) != stravaId) continue
                val name = a.optString("nm", "Activity")
                val sub = listOf(a.optString("d", ""), "Week ${w.optInt("n", i + 1)}")
                    .filter { it.isNotBlank() }.joinToString(" · ")
                return name to sub
            }
        }
        return null
    }

    /** Overwrites a logged activity's feedback in place. Returns true if the
     *  activity was found and updated. Volume totals are unaffected. */
    fun updateFeedback(weeks: JSONArray, stravaId: Long, feedback: RunFeedback): Boolean {
        val a = actById(weeks, stravaId) ?: return false
        putFeedback(a, feedback)
        return true
    }

    /** A human-readable Athlete-Notes string (the athlete's own input) shown in
     *  the log popup. The separate `notes` field is reserved for coach notes. */
    private fun buildAthleteNotes(f: RunFeedback): String = buildString {
        append("Intensity ${f.intensity}/10 — ${INTENSITY[f.intensity - 1]}\n")
        append("Effort ${f.effort}/10 — ${EFFORT[f.effort - 1]}\n")
        append("Injury ${f.injury}/10 — ${INJURY[f.injury - 1]}")
        if (f.injuryComment.isNotBlank()) append(" (${f.injuryComment.trim()})")
        if (f.recap.isNotBlank()) append("\n\n${f.recap.trim()}")
    }

    private fun recomputeWeek(week: JSONObject) {
        val acts = week.optJSONArray("acts") ?: return
        var run = 0.0
        var long = 0.0
        for (j in 0 until acts.length()) {
            val a = acts.getJSONObject(j)
            if (a.optString("cls") in RUN_CLASSES && a.has("km")) {
                val km = a.optDouble("km", 0.0)
                run += km
                if (km > long) long = km
            }
        }
        week.put("runKm", round2(run))
        week.put("longKm", round2(long))
    }

    private fun iconFor(cls: String?): String = when {
        cls != null && cls in RUN_CLASSES -> "🏃"
        cls == "bike" -> "🚴"
        cls == "wt" -> "💪"
        else -> "🏅"
    }

    private fun parseDate(startDateLocal: String): LocalDate =
        LocalDate.parse(startDateLocal.take(10))

    private fun pace(distanceMeters: Double, movingSec: Int): String {
        if (distanceMeters <= 0 || movingSec <= 0) return ""
        val secPerKm = movingSec / (distanceMeters / 1000.0)
        var m = (secPerKm / 60).toInt()
        var s = Math.round(secPerKm - m * 60).toInt()
        if (s == 60) { m += 1; s = 0 }
        return "%d:%02d/km".format(m, s)
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun trimNum(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else round2(v).toString()

    // ── deterministic, key-ordered serializer ───────────────────────────────
    // org.json doesn't preserve key order, so we emit known keys in a fixed
    // order (then any extras) to keep the written file clean and diff-friendly.

    private val WEEK_KEY_ORDER = listOf("n", "dates", "phase", "status", "runKm", "longKm", "acts")
    private val ACT_KEY_ORDER = listOf(
        "d", "ic", "cls", "nm", "km", "sub", "flags", "desc", "strava_id", "notes", "athleteNotes",
        "intensity", "intensityLabel", "effort", "effortLabel",
        // legacy single-axis fields, kept so older logged runs pass through unchanged
        "difficulty", "difficultyLabel",
        "injury", "injuryLabel", "injuryComment", "recap"
    )

    private fun childOrder(key: String): List<String> =
        if (key == "acts") ACT_KEY_ORDER else emptyList()

    private fun orderedKeys(obj: JSONObject, order: List<String>): List<String> {
        val remaining = obj.keys().asSequence().toMutableSet()
        val result = ArrayList<String>(remaining.size)
        for (k in order) if (remaining.remove(k)) result.add(k)
        result.addAll(remaining.sorted())
        return result
    }

    private fun pad(n: Int) = "  ".repeat(n)

    private fun serializeObject(obj: JSONObject, order: List<String>, indent: Int): String {
        val keys = orderedKeys(obj, order)
        if (keys.isEmpty()) return "{}"
        val sb = StringBuilder("{\n")
        keys.forEachIndexed { i, k ->
            sb.append(pad(indent + 1)).append(JSONObject.quote(k)).append(": ")
            sb.append(serializeValue(obj.get(k), childOrder(k), indent + 1))
            if (i < keys.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append(pad(indent)).append("}")
        return sb.toString()
    }

    private fun serializeArray(arr: JSONArray, elemOrder: List<String>, indent: Int): String {
        if (arr.length() == 0) return "[]"
        val scalar = (0 until arr.length()).all {
            arr.get(it) !is JSONObject && arr.get(it) !is JSONArray
        }
        if (scalar) {
            return "[" + (0 until arr.length()).joinToString(", ") {
                serializeValue(arr.get(it), emptyList(), indent)
            } + "]"
        }
        val sb = StringBuilder("[\n")
        for (i in 0 until arr.length()) {
            sb.append(pad(indent + 1)).append(serializeValue(arr.get(i), elemOrder, indent + 1))
            if (i < arr.length() - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append(pad(indent)).append("]")
        return sb.toString()
    }

    private fun serializeValue(v: Any?, order: List<String>, indent: Int): String = when (v) {
        is JSONObject -> serializeObject(v, order, indent)
        is JSONArray -> serializeArray(v, order, indent)
        is String -> JSONObject.quote(v)
        is Boolean, is Int, is Long -> v.toString()
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        null -> "null"
        else -> if (v === JSONObject.NULL) "null" else JSONObject.quote(v.toString())
    }
}
