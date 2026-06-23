package com.vibetraining.assistant.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Berlin cycle figures derived from the live training log. */
data class BerlinFacts(
    val actualN: Int,
    val km: List<Double>,
    val lng: List<Double>,
    val cum: List<Double>,
    val phase: List<Double>,
    val phaseAvg: List<Double>,
    val mix: List<Int>,            // [easy, tempo, long, race, bike, weights]
    val kmSoFar: Int,
    val peakWeek: String,
    val peakLong: String,
    val avgWeekly: String,
    val actCount: Int
)

/**
 * Pure logic for the Cycle Comparison page: derive Berlin figures from
 * training_data.js, fill the bundled template, and compute the data checksum
 * used to decide when insights need regenerating. No Android/Drive dependencies
 * so it stays straightforward to reason about and test.
 */
object CompareRenderer {

    private val MIX_KEYS = listOf("easy", "tempo", "long", "race-r", "bike", "wt")
    private val MIX_LABELS = listOf("Easy", "Tempo", "Long", "Race", "Bike", "Weights")
    private val MIX_SWATCHES = listOf("#27ae60", "#f1c40f", "#e74c3c", "#ff6b6b", "#3498db", "#9b59b6")

    /** SHA-256 of the raw data file, hex-encoded — stable across identical data. */
    fun checksum(dataJs: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(dataJs.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Pulls the `WEEKS = [ … ]` array literal out of training_data.js. */
    fun parseWeeks(dataJs: String): JSONArray {
        val start = dataJs.indexOf('[')
        val end = dataJs.lastIndexOf(']')
        require(start >= 0 && end > start) { "training_data.js has no WEEKS array" }
        return JSONArray(dataJs.substring(start, end + 1))
    }

    fun deriveFacts(weeks: JSONArray): BerlinFacts {
        val n = weeks.length()
        val km = ArrayList<Double>(n)
        val lng = ArrayList<Double>(n)
        val statuses = ArrayList<String>(n)
        val nums = ArrayList<Int>(n)
        val mix = IntArray(6)
        var actCount = 0

        for (i in 0 until n) {
            val w = weeks.getJSONObject(i)
            km.add(w.optDouble("runKm", 0.0))
            lng.add(w.optDouble("longKm", 0.0))
            val status = w.optString("status", "")
            statuses.add(status)
            nums.add(w.optInt("n", i + 1))
            val acts = w.optJSONArray("acts") ?: JSONArray()
            val isActual = status == "actual"
            for (j in 0 until acts.length()) {
                val a = acts.getJSONObject(j)
                if (isActual) {
                    actCount++
                    val idx = MIX_KEYS.indexOf(a.optString("cls"))
                    if (idx >= 0) mix[idx]++
                }
            }
        }

        // Cumulative running volume across all 26 weeks.
        val cum = ArrayList<Double>(n)
        var running = 0.0
        for (v in km) { running += v; cum.add(round1(running)) }

        val actualN = statuses.count { it == "actual" }

        // Phase buckets: BASE W1-8, BUILD W9-16, PEAK W17-22, TAPER W23-26.
        fun bucket(i: Int) = when { i < 8 -> 0; i < 16 -> 1; i < 22 -> 2; else -> 3 }
        val phase = DoubleArray(4)
        val phaseWeeks = IntArray(4)
        for (i in 0 until n) {
            if (statuses[i] == "actual") {
                phase[bucket(i)] += km[i]
                phaseWeeks[bucket(i)]++
            }
        }
        val phaseList = phase.map { round1(it) }
        val phaseAvg = phase.mapIndexed { i, total ->
            if (phaseWeeks[i] > 0) round1(total / phaseWeeks[i]) else 0.0
        }

        // Stats over actual weeks only.
        var kmSoFar = 0.0
        var peakKm = 0.0; var peakKmWk = 0
        var peakLng = 0.0; var peakLngWk = 0
        for (i in 0 until n) {
            if (statuses[i] != "actual") continue
            kmSoFar += km[i]
            if (km[i] > peakKm) { peakKm = km[i]; peakKmWk = nums[i] }
            if (lng[i] > peakLng) { peakLng = lng[i]; peakLngWk = nums[i] }
        }
        val avgWeekly = if (actualN > 0) round1(kmSoFar / actualN) else 0.0

        return BerlinFacts(
            actualN = actualN,
            km = km, lng = lng, cum = cum,
            phase = phaseList, phaseAvg = phaseAvg,
            mix = mix.toList(),
            kmSoFar = Math.round(kmSoFar).toInt(),
            peakWeek = "${num(peakKm)} km (W$peakKmWk)",
            peakLong = "${num(peakLng)} km (W$peakLngWk)",
            avgWeekly = "${num(avgWeekly)} km",
            actCount = actCount
        )
    }

    /** Builds the `<div class="mix-item">…` legend for Berlin from its mix counts. */
    fun mixLegendHtml(mix: List<Int>): String = buildString {
        for (i in MIX_LABELS.indices) {
            append("<div class=\"mix-item\"><div class=\"mix-swatch\" style=\"background:")
            append(MIX_SWATCHES[i]).append("\"></div> ")
            append(MIX_LABELS[i]).append(" (").append(mix.getOrElse(i) { 0 }).append(")</div>")
        }
    }

    /** Renders the Key Insights cards. */
    fun insightsHtml(insights: List<Insight>): String = buildString {
        for (it in insights) {
            append("<div class=\"insight\"><div class=\"i-label\">")
            append(escape(it.label)).append("</div><div class=\"i-val\">")
            append(escape(it.value)).append("</div></div>")
        }
    }

    /** Compact JSON of all three cycles' headline numbers, for the Claude prompt. */
    fun factsJson(facts: BerlinFacts): String {
        val berlin = JSONObject()
            .put("actual_weeks", facts.actualN)
            .put("km_so_far", facts.kmSoFar)
            .put("avg_weekly_km", facts.avgWeekly)
            .put("peak_week", facts.peakWeek)
            .put("peak_long_run", facts.peakLong)
            .put("activities", facts.actCount)
            .put("weekly_km", JSONArray(facts.km))
            .put("long_runs", JSONArray(facts.lng))
            .put("mix_easy_tempo_long_race_bike_weights", JSONArray(facts.mix))
        val montreal = JSONObject(
            mapOf(
                "race_time" to "3:58:37", "total_km" to 1051, "peak_week_km" to 64.3,
                "peak_long_km" to 29.3, "avg_weekly_km" to 40.4, "activities" to 109,
                "result" to "first marathon"
            )
        )
        val chicago = JSONObject(
            mapOf(
                "race_time" to "3:43:55", "total_km" to 698, "peak_week_km" to 53.9,
                "peak_long_km" to 23.5, "avg_weekly_km" to 26.9, "activities" to 73,
                "result" to "15 min PR"
            )
        )
        return JSONObject()
            .put("goal", "Berlin 2026 sub-3:30 (target 3:25-3:29)")
            .put("montreal_2022", montreal)
            .put("chicago_2024", chicago)
            .put("berlin_2026", berlin)
            .toString()
    }

    /** Substitutes every placeholder in the bundled template. */
    fun fillTemplate(
        template: String,
        facts: BerlinFacts,
        insightsHtml: String,
        checksum: String
    ): String = template
        .replace("__DATA_CHECKSUM__", checksum)
        .replace("__BER_ACTUAL_N__", facts.actualN.toString())
        .replace("__BER_KM__", jsArray(facts.km))
        .replace("__BER_LNG__", jsArray(facts.lng))
        .replace("__BER_CUM__", jsArray(facts.cum))
        .replace("__BER_PHASE__", jsArray(facts.phase))
        .replace("__BER_PHASE_AVG__", jsArray(facts.phaseAvg))
        .replace("__BER_MIX__", JSONArray(facts.mix).toString())
        .replace("__BER_MIX_LEGEND__", mixLegendHtml(facts.mix))
        .replace("__BER_KM_SOFAR__", facts.kmSoFar.toString())
        .replace("__BER_PEAK_WK__", facts.peakWeek)
        .replace("__BER_PEAK_LONG__", facts.peakLong)
        .replace("__BER_AVG__", facts.avgWeekly)
        .replace("__BER_ACT_COUNT__", facts.actCount.toString())
        .replace("__INSIGHTS__", insightsHtml)

    /** Reads the checksum stored in a previously generated comparison page. */
    fun extractChecksum(html: String): String? =
        Regex("""<meta name="data-checksum" content="([^"]*)"""")
            .find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it != "__DATA_CHECKSUM__" }

    /** Reads back the previously rendered insight cards so we can reuse them
     *  when the data hasn't changed (no Claude call needed). */
    fun extractInsightsHtml(html: String): String? =
        Regex("""<div class="insight-grid">(.*?)</div>\s*</div>\s*<script""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it != "__INSIGHTS__" }

    // ── formatting helpers ──────────────────────────────────────────────────
    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    /** Trims a trailing ".0" so whole numbers render cleanly. */
    private fun num(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else round1(v).toString()

    private fun jsArray(values: List<Double>): String =
        values.joinToString(",", "[", "]") { num(it) }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
