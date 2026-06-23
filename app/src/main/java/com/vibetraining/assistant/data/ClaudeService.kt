package com.vibetraining.assistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
private const val ANTHROPIC_VERSION = "2023-06-01"
// Sonnet balances quality and cost for short insight generation; change here to
// trade off. The key is the user's own Anthropic API key (separate billing).
private const val CLAUDE_MODEL = "claude-sonnet-4-6"

/** A single "Key Insight" card: a short label and a one-sentence finding. */
data class Insight(val label: String, val value: String)

object ClaudeService {

    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Asks Claude to compare the three marathon cycles and return exactly six
     * Key Insight cards. [factsJson] is a compact JSON object of the derived
     * stats (Montreal, Chicago, Berlin). Returns the insights, or fails with a
     * readable message the caller can surface.
     */
    suspend fun generateInsights(apiKey: String, factsJson: String): Result<List<Insight>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(apiKey.isNotBlank()) { "No Claude API key set (add one in Settings)." }

                val prompt = buildString {
                    append("You are an endurance-running coach analysing three of Antoine's ")
                    append("marathon training cycles. Race goal: Berlin 2026 sub-3:30.\n\n")
                    append("Here are the derived facts as JSON:\n")
                    append(factsJson)
                    append("\n\nWrite exactly 6 \"Key Insight\" cards comparing the cycles. ")
                    append("Each card has a short LABEL (2-4 words, e.g. \"Volume @ W12\", ")
                    append("\"Quality Work\", \"Peak Long Run\") and a VALUE: one specific, ")
                    append("data-grounded sentence (max ~30 words) citing real numbers from the facts. ")
                    append("Be concrete and useful; avoid generic praise.\n\n")
                    append("Respond with ONLY a JSON array of 6 objects, each {\"label\":\"…\",\"value\":\"…\"}. ")
                    append("No markdown, no prose outside the JSON.")
                }

                val body = JSONObject()
                    .put("model", CLAUDE_MODEL)
                    .put("max_tokens", 1024)
                    .put(
                        "messages",
                        JSONArray().put(
                            JSONObject().put("role", "user").put("content", prompt)
                        )
                    )
                    .toString()

                val req = Request.Builder()
                    .url(MESSAGES_URL)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .post(body.toRequestBody(JSON))
                    .build()

                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val apiMsg = runCatching {
                            JSONObject(text).getJSONObject("error").getString("message")
                        }.getOrNull()
                        error("Claude request failed (${resp.code})" + (apiMsg?.let { ": $it" } ?: "."))
                    }
                    parseInsights(text)
                }
            }
        }

    /** Extracts the text content from the Messages response and parses the
     *  embedded JSON array of insights. */
    private fun parseInsights(responseBody: String): List<Insight> {
        val content = JSONObject(responseBody).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        val raw = sb.toString().trim()
        // The model may wrap the array in stray text or a code fence; isolate it.
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        require(start >= 0 && end > start) { "Claude did not return a JSON array of insights." }
        val arr = JSONArray(raw.substring(start, end + 1))
        val out = ArrayList<Insight>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val label = o.optString("label").trim()
            val value = o.optString("value").trim()
            if (label.isNotEmpty() && value.isNotEmpty()) out.add(Insight(label, value))
        }
        require(out.isNotEmpty()) { "Claude returned no usable insights." }
        return out
    }
}
