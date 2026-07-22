package com.vibetraining.assistant.data

import org.json.JSONArray
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

    /** Built-in Key Insights, used when no `compare_notes` overlay exists in Drive
     *  yet. Once the coach writes an overlay, these are replaced by its analysis. */
    private val DEFAULT_INSIGHTS = listOf(
        "Volume Comparison" to "Berlin is tracking well ahead of both prior cycles at the same point — the strongest base of the three.",
        "Quality Work (Tempo %)" to "Montreal: 17% tempo/long. Chicago: 19% — nearly pure easy volume. Berlin: highest share of quality work of all three.",
        "Peak Long Run" to "Montreal peaked at 29 km (W23) but loaded the taper heavy. Chicago peaked at 23.5 km. Berlin planned peak is 32 km (W19).",
        "Cross-Training" to "Zero bike or weights in the Chicago cycle. Montreal had 7 short rides. Berlin adds Zwift + weights for injury resilience.",
        "Consistency" to "Chicago had a zero-km week (W15) and several sub-15 km weeks. Montreal dropped to ~11 km in W5–6. Berlin has been more consistent.",
        "Race Trajectory" to "Chicago improved by 15 min on lower volume. Berlin targets a further 18–22 min improvement on higher quality and consistency."
    )

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

        // "So far" = fully completed (actual) weeks only. Statuses are now
        // date-derived, so a just-ended week is "actual" and counts, while the
        // in-progress "current" week (still mostly planned) is left out until it
        // completes — the honest cross-cycle comparison point.
        fun counted(s: String) = s == "actual"

        for (i in 0 until n) {
            val w = weeks.getJSONObject(i)
            km.add(w.optDouble("runKm", 0.0))
            lng.add(w.optDouble("longKm", 0.0))
            val status = w.optString("status", "")
            statuses.add(status)
            nums.add(w.optInt("n", i + 1))
            val acts = w.optJSONArray("acts") ?: JSONArray()
            val isActual = counted(status)
            for (j in 0 until acts.length()) {
                val a = acts.getJSONObject(j)
                // Only count real, logged activities — not the current week's
                // still-pending planned sessions.
                if (isActual && a.has("strava_id")) {
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

        val actualN = statuses.count { counted(it) }

        // Phase buckets: BASE W1-8, BUILD W9-16, PEAK W17-22, TAPER W23-26.
        fun bucket(i: Int) = when { i < 8 -> 0; i < 16 -> 1; i < 22 -> 2; else -> 3 }
        val phase = DoubleArray(4)
        val phaseWeeks = IntArray(4)
        for (i in 0 until n) {
            if (counted(statuses[i])) {
                phase[bucket(i)] += km[i]
                phaseWeeks[bucket(i)]++
            }
        }
        val phaseList = phase.map { round1(it) }
        val phaseAvg = phase.mapIndexed { i, total ->
            if (phaseWeeks[i] > 0) round1(total / phaseWeeks[i]) else 0.0
        }

        // Stats over logged-to-date weeks (completed + in progress).
        var kmSoFar = 0.0
        var peakKm = 0.0; var peakKmWk = 0
        var peakLng = 0.0; var peakLngWk = 0
        for (i in 0 until n) {
            if (!counted(statuses[i])) continue
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

    /** Parses a `compare_notes.json` overlay ({"insights":[{"label","text"}…]})
     *  into (label, text) pairs, or returns the built-in defaults when the
     *  overlay is absent or malformed. */
    fun parseInsights(overlayJson: String?): List<Pair<String, String>> {
        if (overlayJson.isNullOrBlank()) return DEFAULT_INSIGHTS
        return runCatching {
            val arr = org.json.JSONObject(overlayJson).optJSONArray("insights") ?: return DEFAULT_INSIGHTS
            val out = ArrayList<Pair<String, String>>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val label = o.optString("label").trim()
                val text = o.optString("text").trim()
                if (label.isNotEmpty() || text.isNotEmpty()) out.add(label to text)
            }
            out.ifEmpty { DEFAULT_INSIGHTS }
        }.getOrDefault(DEFAULT_INSIGHTS)
    }

    /** Renders the Key Insights cards from (label, text) pairs. */
    fun insightsHtml(insights: List<Pair<String, String>>): String = buildString {
        for ((label, text) in insights) {
            append("<div class=\"insight\"><div class=\"i-label\">")
            append(escape(label)).append("</div><div class=\"i-val\">")
            append(escape(text)).append("</div></div>")
        }
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Substitutes every placeholder in the bundled template, including the Key
     *  Insights (from the Drive overlay when present, else the built-in text). */
    fun fillTemplate(
        template: String,
        facts: BerlinFacts,
        checksum: String,
        insights: List<Pair<String, String>> = DEFAULT_INSIGHTS
    ): String = template
        .replace("__INSIGHTS__", insightsHtml(insights))
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

    /** Reads the checksum stored in a previously generated comparison page, used
     *  to skip rewriting Drive when the data hasn't changed. */
    fun extractChecksum(html: String): String? =
        Regex("""<meta name="data-checksum" content="([^"]*)"""")
            .find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it != "__DATA_CHECKSUM__" }

    // ── formatting helpers ──────────────────────────────────────────────────
    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    /** Trims a trailing ".0" so whole numbers render cleanly. */
    private fun num(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else round1(v).toString()

    private fun jsArray(values: List<Double>): String =
        values.joinToString(",", "[", "]") { num(it) }
}
