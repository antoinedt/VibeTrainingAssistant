package com.vibetraining.assistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

/** OAuth redirect target — must match the intent filter in AndroidManifest.xml
 *  and the Authorization Callback Domain (`strava-auth`) in the Strava app. */
const val STRAVA_REDIRECT_URI = "vibe://strava-auth"
private const val STRAVA_SCOPE = "activity:read_all"
private const val TOKEN_URL = "https://www.strava.com/oauth/token"
private const val ACTIVITIES_URL = "https://www.strava.com/api/v3/athlete/activities"

/** Berlin Marathon race day. */
private val RACE_DAY: LocalDate = LocalDate.of(2026, 9, 27)
/** Number of weeks in the training cycle we care about. */
private const val TRAINING_WEEKS = 26L
/** Epoch-second lower bound for synced activities: the start of the 26-week
 *  cycle leading into Berlin. Anything earlier is outside the training plan and
 *  only bloats the JSON, so we ask Strava to skip it entirely. */
private val CYCLE_START_EPOCH: Long =
    RACE_DAY.minusWeeks(TRAINING_WEEKS).atStartOfDay(ZoneOffset.UTC).toEpochSecond()

data class StravaTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long // epoch seconds
)

data class StravaActivity(
    val id: Long,
    val name: String,
    val type: String,
    val distanceMeters: Double,
    val movingTimeSec: Int,
    val startDateLocal: String
)

object StravaService {

    private val client = OkHttpClient()

    fun authorizeUrl(clientId: String): String =
        "https://www.strava.com/oauth/authorize" +
            "?client_id=$clientId" +
            "&response_type=code" +
            "&redirect_uri=$STRAVA_REDIRECT_URI" +
            "&approval_prompt=auto" +
            "&scope=$STRAVA_SCOPE"

    suspend fun exchangeCode(clientId: String, clientSecret: String, code: String): StravaTokens =
        tokenRequest(
            FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("code", code)
                .add("grant_type", "authorization_code")
                .build()
        )

    suspend fun refresh(clientId: String, clientSecret: String, refreshToken: String): StravaTokens =
        tokenRequest(
            FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build()
        )

    private suspend fun tokenRequest(body: FormBody): StravaTokens = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(TOKEN_URL).post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Strava token request failed (${resp.code}). Check your Client ID/Secret.")
            val json = JSONObject(text)
            StravaTokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresAt = json.getLong("expires_at")
            )
        }
    }

    /** Fetches the athlete's activities within the 26-week Berlin training
     *  window (race day − 26 weeks → now), paging until exhausted. The `after`
     *  filter keeps the sync — and the resulting JSON — light by excluding
     *  anything before the cycle started. */
    suspend fun listActivities(accessToken: String): List<StravaActivity> = withContext(Dispatchers.IO) {
        val all = mutableListOf<StravaActivity>()
        var page = 1
        val perPage = 200
        while (true) {
            val req = Request.Builder()
                .url("$ACTIVITIES_URL?per_page=$perPage&page=$page&after=$CYCLE_START_EPOCH")
                .header("Authorization", "Bearer $accessToken")
                .build()
            val items = client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Strava activities request failed (${resp.code}).")
                JSONArray(text)
            }
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                all.add(
                    StravaActivity(
                        id = o.optLong("id"),
                        name = o.optString("name"),
                        type = o.optString("sport_type", o.optString("type")),
                        distanceMeters = o.optDouble("distance", 0.0),
                        movingTimeSec = o.optInt("moving_time", 0),
                        startDateLocal = o.optString("start_date_local")
                    )
                )
            }
            if (items.length() < perPage) break
            page++
        }
        all
    }
}
