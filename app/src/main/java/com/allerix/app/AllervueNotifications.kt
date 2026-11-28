package com.allerix.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal data class AllervueNotification(
    val id: String,
    val title: String,
    val message: String,
    val category: String,
    val itemId: String? = null,
    val updateTag: String? = null,
    val updateVersion: String? = null,
    val apkUrl: String? = null,
    val releaseNotes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val read: Boolean = false
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("message", message)
        put("category", category); put("itemId", itemId)
        put("updateTag", updateTag); put("updateVersion", updateVersion)
        put("apkUrl", apkUrl); put("releaseNotes", releaseNotes)
        put("createdAt", createdAt); put("read", read)
    }

    companion object {
        fun fromJson(json: JSONObject) = AllervueNotification(
            id = json.optString("id"),
            title = json.optString("title"),
            message = json.optString("message"),
            category = json.optString("category"),
            itemId = json.optString("itemId").takeIf { it.isNotBlank() && it != "null" },
            updateTag = json.optString("updateTag").takeIf { it.isNotBlank() && it != "null" },
            updateVersion = json.optString("updateVersion").takeIf { it.isNotBlank() && it != "null" },
            apkUrl = json.optString("apkUrl").takeIf { it.isNotBlank() && it != "null" },
            releaseNotes = json.optString("releaseNotes").takeIf { it.isNotBlank() && it != "null" },
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            read = json.optBoolean("read", false)
        )
    }
}

internal object AllervueNotificationStore {
    private const val PREFS = "allervue_notification_center"
    private const val FEED = "feed"
    private const val KNOWN_CONTENT = "known_content"
    private const val KNOWN_UPDATES = "known_updates"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS + "_" + Api.userId, Context.MODE_PRIVATE)

    fun load(context: Context): List<AllervueNotification> {
        val array = runCatching { JSONArray(prefs(context).getString(FEED, "[]")) }.getOrNull()
            ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching { AllervueNotification.fromJson(item) }.getOrNull()?.let(::add)
            }
        }.sortedByDescending { it.createdAt }
    }

    private fun save(context: Context, entries: List<AllervueNotification>) {
        val array = JSONArray()
        entries.sortedByDescending { it.createdAt }.take(60).forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(FEED, array.toString()).apply()
    }

    fun markRead(context: Context, id: String) =
        save(context, load(context).map { if (it.id == id) it.copy(read = true) else it })

    fun markAllRead(context: Context) =
        save(context, load(context).map { it.copy(read = true) })

    fun clear(context: Context) = save(context, emptyList())

    suspend fun sync(context: Context): List<AllervueNotification> {
        val preferences = prefs(context)
        val known = preferences.getStringSet(KNOWN_CONTENT, emptySet())?.toMutableSet() ?: mutableSetOf()
        val knownUpdates = preferences.getStringSet(KNOWN_UPDATES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val current = load(context).toMutableList()

        // Use the user's actual Jellyfin library folders. No genre-based guessing.
        val views = runCatching { Api.views() }.getOrDefault(emptyList())
        fun normalized(value: String) = value.lowercase()
            .replace("jellyfin", "").replace("library", "")
            .replace(Regex("[^a-z0-9]"), "")

        val movieLibraries = views.filter { normalized(it.name) in setOf("movie", "movies", "films") }
        val seriesLibraries = views.filter { normalized(it.name) in setOf("series", "tvshows", "television") }
        val animeLibraries = views.filter { normalized(it.name) in setOf("anime", "animeepisodes") }

        val latest = mutableListOf<Pair<String, Item>>()
        suspend fun collect(libraries: List<Item>, category: String, expectedType: String) {
            libraries.forEach { library ->
                runCatching { Api.latest(library.id) }.getOrDefault(emptyList())
                    .filter { it.type.equals(expectedType, true) }
                    .take(12)
                    .forEach { latest += category to it }
            }
        }
        collect(movieLibraries, "movie", "Movie")
        collect(seriesLibraries, "series", "Series")
        collect(animeLibraries, "anime", "Episode")

        // First run establishes a baseline so the entire existing library is not
        // incorrectly presented as newly arrived content.
        if (known.isEmpty()) {
            latest.forEach { (_, item) -> known += item.id }
        } else {
            latest.forEach { (category, item) ->
                if (known.add(item.id)) {
                    val label = when (category) {
                        "movie" -> "New movie"
                        "series" -> "New series"
                        else -> "New anime episode"
                    }
                    val detail = when (category) {
                        "anime" -> item.seriesName?.takeIf { it.isNotBlank() } ?: item.name
                        else -> item.name
                    }
                    current.add(
                        AllervueNotification(
                            id = "content:${item.id}",
                            title = label,
                            message = detail,
                            category = category,
                            itemId = item.id
                        )
                    )
                }
            }
        }
        preferences.edit().putStringSet(KNOWN_CONTENT, known.toList().takeLast(500).toSet()).apply()

        // App updates are first-party Allervue notifications, separate from Jellyfin alerts.
        val update = runCatching {
            UpdateChecker.checkForUpdate(context, includeDismissed = true)
        }.getOrNull()
        if (update != null && knownUpdates.add(update.tag)) {
            current.add(
                AllervueNotification(
                    id = "update:${update.tag}",
                    title = "Allervue update available",
                    message = "Version ${update.versionName} is ready.",
                    category = "update",
                    updateTag = update.tag,
                    updateVersion = update.versionName,
                    apkUrl = update.apkUrl,
                    releaseNotes = update.releaseNotes
                )
            )
        }
        preferences.edit().putStringSet(KNOWN_UPDATES, knownUpdates).apply()
        save(context, current.distinctBy { it.id })
        return load(context)
    }
}

@Composable
internal fun AllervueNotificationBell(
    entries: List<AllervueNotification>,
    onRefresh: suspend () -> Unit,
    onOpenContent: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf<UpdateInfo?>(null) }
    val unread = entries.count { !it.read }

    Box {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxSize().clickable {
                    expanded = true
                    scope.launch { onRefresh() }
                },
                contentAlignment = Alignment.Center
            ) {
                CanvasBell(Modifier.size(22.dp))
            }
            if (unread > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-3).dp)
                        .defaultMinSize(minWidth = 21.dp, minHeight = 21.dp)
                        .clip(CircleShape).background(Color(0xFFFF527F))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (unread > 99) "99+" else unread.toString(),
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                        maxLines = 1)
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(320.dp).heightIn(max = 440.dp)
                .background(Color(0xFF21172F), RoundedCornerShape(20.dp))
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Notifications", color = Color.White, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold)
                        Text(if (unread == 0) "You're all caught up" else "$unread unread",
                            color = Color(0xFFBBAACB), fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        AllervueNotificationStore.markAllRead(context)
                        scope.launch { onRefresh() }
                    }, enabled = unread > 0) { Text("Read all", color = Color(0xFFFF91AA)) }
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
                if (entries.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(25.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("✦", color = Color(0xFFFF91AA), fontSize = 27.sp)
                        Spacer(Modifier.height(7.dp))
                        Text("No notifications yet", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("New movies, series, anime episodes and app updates will appear here.",
                            color = Color(0xFFBBAACB), fontSize = 11.sp)
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    ) {
                        entries.forEach { entry ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (!entry.read) Color(0x1EFF7192) else Color.Transparent)
                                    .clickable {
                                        AllervueNotificationStore.markRead(context, entry.id)
                                        expanded = false
                                        if (entry.category == "update" &&
                                            !entry.updateTag.isNullOrBlank() &&
                                            !entry.updateVersion.isNullOrBlank() &&
                                            !entry.apkUrl.isNullOrBlank()
                                        ) {
                                            updateDialog = UpdateInfo(
                                                entry.updateTag, entry.updateVersion,
                                                entry.apkUrl, entry.releaseNotes.orEmpty()
                                            )
                                        } else {
                                            entry.itemId?.let(onOpenContent)
                                        }
                                        scope.launch { onRefresh() }
                                    }
                                    .padding(horizontal = 15.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape)
                                        .background(
                                            when (entry.category) {
                                                "movie" -> Color(0xFF754C9D)
                                                "series" -> Color(0xFF347C83)
                                                "anime" -> Color(0xFFB64D83)
                                                else -> Color(0xFFFF718E)
                                            }.copy(alpha = .24f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        when (entry.category) {
                                            "movie" -> "M"
                                            "series" -> "S"
                                            "anime" -> "A"
                                            else -> "↑"
                                        },
                                        color = Color.White, fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, color = Color.White, fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold)
                                    Text(entry.message, color = Color(0xFFBBAACB), fontSize = 11.sp,
                                        maxLines = 2)
                                }
                                if (!entry.read) {
                                    Box(Modifier.size(7.dp).clip(CircleShape)
                                        .background(Color(0xFFFF718E)))
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0x22FFFFFF))
                    TextButton(
                        onClick = {
                            AllervueNotificationStore.clear(context)
                            expanded = false
                            scope.launch { onRefresh() }
                        },
                        modifier = Modifier.align(Alignment.End).padding(end = 8.dp)
                    ) { Text("Clear all", color = Color(0xFFBBAACB), fontSize = 11.sp) }
                }
            }
        }
    }

    updateDialog?.let { update ->
        UpdateAvailableDialog(
            update = update,
            onDismiss = {
                UpdateChecker.dismissUpdate(context, update.tag)
                updateDialog = null
            }
        )
    }
}

@Composable
private fun CanvasBell(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bell = Path().apply {
            moveTo(w * .22f, h * .72f)
            lineTo(w * .29f, h * .62f)
            lineTo(w * .31f, h * .40f)
            cubicTo(w * .31f, h * .16f, w * .69f, h * .16f, w * .69f, h * .40f)
            lineTo(w * .71f, h * .62f)
            lineTo(w * .78f, h * .72f)
            close()
        }
        drawPath(bell, Color.White, style = Stroke(width = 1.8.dp.toPx()))
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * .18f, h * .73f),
            androidx.compose.ui.geometry.Offset(w * .82f, h * .73f), 1.8.dp.toPx())
        drawCircle(Color.White, radius = 1.7.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(w * .5f, h * .82f))
        drawArc(Color.White, 205f, 130f, false,
            topLeft = androidx.compose.ui.geometry.Offset(w * .39f, h * .04f),
            size = androidx.compose.ui.geometry.Size(w * .22f, h * .20f),
            style = Stroke(width = 1.6.dp.toPx()))
    }
}
