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

data class SeerrRequest(
    val id: Int,
    val title: String,
    val posterPath: String,
    val mediaType: String,
    val requestStatus: String,
    val availabilityStatus: String
)

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
                error(message ?: "Allervue sign-in failed (HTTP ${response.code}).")
            }
            val cookie = response.headers("Set-Cookie")
                .firstOrNull { it.substringBefore("=") == "connect.sid" }
                ?.substringBefore(";")
                ?: error("Allervue sign-in completed but did not return a session cookie.")
            sessionCookie = cookie
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(COOKIE_KEY, cookie).apply()
        }
    }

    private suspend fun call(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            check(sessionCookie.isNotBlank()) { "Connect your Allervue account first." }
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
                    error(message ?: "Allervue returned HTTP ${response.code}.")
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
        return result["message"]?.jsonPrimitive?.contentOrNull ?: "Request submitted through Allervue."
    }

    private suspend fun fetchMediaDetails(mediaId: Int, mediaTypeHint: String): JsonObject? {
        val paths = if (mediaTypeHint.equals("tv", true) || mediaTypeHint.equals("series", true)) {
            listOf("/tv/$mediaId", "/movie/$mediaId")
        } else {
            listOf("/movie/$mediaId", "/tv/$mediaId")
        }
        for (path in paths) {
            val details = runCatching {
                json.parseToJsonElement(call("GET", path)).jsonObject
            }.getOrNull()
            if (details != null) return details
        }
        return null
    }

    suspend fun myRequests(): List<SeerrRequest> {
        val root = json.parseToJsonElement(call("GET", "/request?take=20&skip=0")).jsonObject
        val results = root["results"]?.jsonArray ?: JsonArray(emptyList())
        val requests = mutableListOf<SeerrRequest>()
        results.forEachIndexed { index, value ->
            val obj = runCatching { value.jsonObject }.getOrNull() ?: return@forEachIndexed
            val media = obj["media"]?.jsonObject
            val requestId = obj["id"]?.jsonPrimitive?.intOrNull ?: index + 1

            // Seerr's request payload contains the TMDB ID and media state, but
            // does not include title/poster metadata. Those fields come from
            // the dedicated /movie/:id or /tv/:id detail endpoints.
            val mediaId = media?.get("tmdbId")?.jsonPrimitive?.intOrNull
            val typeHint = obj["mediaType"]?.jsonPrimitive?.contentOrNull
                ?: obj["type"]?.jsonPrimitive?.contentOrNull
                ?: media?.get("mediaType")?.jsonPrimitive?.contentOrNull
                ?: ""

            val details = mediaId?.let { fetchMediaDetails(it, typeHint) }
            val resolvedType = when {
                details?.get("name") != null -> "tv"
                details?.get("title") != null -> "movie"
                typeHint.equals("tv", true) || typeHint.equals("series", true) -> "tv"
                else -> "movie"
            }
            val title = details?.get("title")?.jsonPrimitive?.contentOrNull
                ?: details?.get("name")?.jsonPrimitive?.contentOrNull
                ?: media?.get("title")?.jsonPrimitive?.contentOrNull
                ?: media?.get("name")?.jsonPrimitive?.contentOrNull
                ?: obj["title"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
                ?: mediaId?.let { "Requested title (TMDB #$it)" }
                ?: "Requested item #$requestId"
            val poster = details?.get("posterPath")?.jsonPrimitive?.contentOrNull
                ?: media?.get("posterPath")?.jsonPrimitive?.contentOrNull
                ?: obj["posterPath"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val requestStatus = when (obj["status"]?.jsonPrimitive?.intOrNull) {
                1 -> "Pending approval"
                2 -> "Approved"
                3 -> "Declined"
                else -> "Submitted"
            }
            val availability = when (media?.get("status")?.jsonPrimitive?.intOrNull) {
                2 -> "Queued"
                3 -> "Processing"
                4 -> "Partially available"
                5 -> "Available"
                6 -> "Deleted"
                else -> ""
            }
            requests += SeerrRequest(
                requestId, title, poster, resolvedType, requestStatus, availability
            )
        }
        return requests
    }}
