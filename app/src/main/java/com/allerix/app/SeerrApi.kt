package com.allerix.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

data class SeerrTitle(
    val id: Int,
    val mediaType: String,
    val title: String,
    val year: String,
    val overview: String,
    val posterPath: String,
    val requestStatus: String
)

object SeerrApi {
    const val BASE_URL = "https://request.alleriafilmotheque.fyi"
    private const val PREFS = "seerr"
    private const val COOKIE_KEY = "session_cookie"
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    @Volatile private var sessionCookie: String = ""

    private val http = OkHttpClient.Builder().build()

    fun load(ctx: Context) {
        sessionCookie = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(COOKIE_KEY, "") ?: ""
    }

    fun isConnected(): Boolean = sessionCookie.isNotBlank()

    fun disconnect(ctx: Context) {
        sessionCookie = ""
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(COOKIE_KEY).apply()
    }

    suspend fun login(ctx: Context, username: String, password: String) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
        }.toString()
        val request = Request.Builder()
            .url("$BASE_URL/api/v1/auth/jellyfin")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    obj["message"]?.jsonPrimitive?.content ?: obj["error"]?.jsonPrimitive?.content
                }.getOrNull()
                error(message ?: "Seerr sign-in failed (HTTP ${response.code}).")
            }
            val cookie = response.headers("Set-Cookie")
                .firstOrNull { it.substringBefore("=") == "connect.sid" }
                ?.substringBefore(";")
                ?: error("Seerr signed in but did not return a session cookie.")
            sessionCookie = cookie
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(COOKIE_KEY, cookie).apply()
        }
    }

    private suspend fun call(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            check(sessionCookie.isNotBlank()) { "Connect your Seerr account first." }
            val builder = Request.Builder().url(BASE_URL + "/api/v1" + path)
                .header("Accept", "application/json")
                .header("Cookie", sessionCookie)
            if (method == "GET") builder.get()
            else builder.method(method, (body ?: "{}").toRequestBody("application/json".toMediaType()))
            http.newCall(builder.build()).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        val obj = json.parseToJsonElement(payload).jsonObject
                        obj["message"]?.jsonPrimitive?.content ?: obj["error"]?.jsonPrimitive?.content
                    }.getOrNull()
                    error(message ?: "Seerr returned HTTP ${response.code}.")
                }
                payload
            }
        }

    suspend fun search(query: String): List<SeerrTitle> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val root = json.parseToJsonElement(call("GET", "/search?query=$encoded&page=1")).jsonObject
        val results = root["results"]?.jsonArray ?: JsonArray(emptyList())
        return results.mapNotNull { value ->
            runCatching {
                val obj = value.jsonObject
                val mediaType = obj["mediaType"]?.jsonPrimitive?.content ?: return@runCatching null
                if (mediaType != "movie" && mediaType != "tv") return@runCatching null
                val title = obj["title"]?.jsonPrimitive?.content
                    ?: obj["name"]?.jsonPrimitive?.content ?: return@runCatching null
                val mediaInfo = obj["mediaInfo"]?.jsonObject
                SeerrTitle(
                    id = obj["id"]?.jsonPrimitive?.int ?: return@runCatching null,
                    mediaType = mediaType,
                    title = title,
                    year = (obj["releaseDate"]?.jsonPrimitive?.content
                        ?: obj["firstAirDate"]?.jsonPrimitive?.content ?: "").take(4),
                    overview = obj["overview"]?.jsonPrimitive?.content ?: "",
                    posterPath = obj["posterPath"]?.jsonPrimitive?.content ?: "",
                    requestStatus = mediaInfo?.get("status")?.jsonPrimitive?.contentOrNull ?: ""
                )
            }.getOrNull()
        }
    }

    suspend fun request(item: SeerrTitle): String {
        val body = buildJsonObject {
            put("mediaId", item.id)
            put("mediaType", item.mediaType)
        }.toString()
        val result = json.parseToJsonElement(call("POST", "/request", body)).jsonObject
        return result["message"]?.jsonPrimitive?.contentOrNull ?: "Request submitted to Seerr."
    }

    suspend fun myRequests(): List<String> {
        val root = json.parseToJsonElement(call("GET", "/request?take=20&skip=0")).jsonObject
        val results = root["results"]?.jsonArray ?: JsonArray(emptyList())
        return results.mapIndexedNotNull { index, value ->
            runCatching {
                val obj = value.jsonObject
                val media = obj["media"]?.jsonObject
                val id = obj["id"]?.jsonPrimitive?.intOrNull
                val mediaId = media?.get("tmdbId")?.jsonPrimitive?.intOrNull
                    ?: media?.get("id")?.jsonPrimitive?.intOrNull
                val mediaType = obj["mediaType"]?.jsonPrimitive?.contentOrNull
                    ?: obj["type"]?.jsonPrimitive?.contentOrNull
                    ?: "media"
                val title = obj["title"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: media?.get("title")?.jsonPrimitive?.contentOrNull
                    ?: media?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: mediaId?.let { "${mediaType.replaceFirstChar { c -> c.uppercase() }} request · TMDB #$it" }
                    ?: "${mediaType.replaceFirstChar { c -> c.uppercase() }} request${id?.let { " #$it" } ?: " #${index + 1}"}"

                val requestState = when (obj["status"]?.jsonPrimitive?.intOrNull) {
                    1 -> "Pending approval"
                    2 -> "Approved"
                    3 -> "Declined"
                    else -> "Submitted"
                }
                val availability = when (media?.get("status")?.jsonPrimitive?.intOrNull) {
                    2 -> " · Queued"
                    3 -> " · Processing"
                    4 -> " · Partially available"
                    5 -> " · Available"
                    6 -> " · Deleted"
                    else -> ""
                }
                "$title · $requestState$availability"
            }.getOrNull()
        }
    }
}
