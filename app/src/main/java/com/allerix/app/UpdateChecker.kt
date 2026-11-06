package com.allerix.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val tag: String,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String
)

object UpdateChecker {
    private const val RELEASE_API =
        "https://api.github.com/repos/AlleriaLabs/Allervue-Android/releases/latest"
    private const val PREFS = "allervue_updates"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Allervue-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string().orEmpty())
            val tag = json.optString("tag_name").trim()
            val version = tag.removePrefix("v").removePrefix("V")
            if (tag.isBlank() || !isNewer(version, currentVersion(context))) return@withContext null
            val dismissed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("dismissed_tag", null)
            if (dismissed == tag) return@withContext null

            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (asset.optString("name") == "Allervue.apk") {
                    apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                    break
                }
            }
            val url = apkUrl ?: return@withContext null
            UpdateInfo(tag, version, url, json.optString("body").trim())
        }
    }

    fun dismissUpdate(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("dismissed_tag", tag).apply()
    }

    suspend fun downloadAndInstall(context: Context, update: UpdateInfo): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return "Allow Allervue to install updates, then tap Update again."
        }

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(update.apkUrl)
                .header("User-Agent", "Allervue-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub download failed (${response.code}).")
                val body = response.body ?: error("GitHub returned an empty APK.")
                val file = File(context.cacheDir, "Allervue-update.apk")
                body.byteStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                if (file.length() < 1_000_000L) {
                    file.delete()
                    error("The downloaded file does not look like a valid APK.")
                }
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                withContext(Dispatchers.Main) {
                    val install = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(install)
                }
            }
            null
        }
    }

    private fun currentVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        }.getOrDefault("1.0")

    private fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(value: String) = Regex("\\d+").findAll(value).mapNotNull {
            it.value.toIntOrNull()
        }.toList()
        val a = parts(candidate)
        val b = parts(installed)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}

@Composable
fun UpdateAvailableDialog(update: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Allervue update available") },
        text = {
            Column {
                Text("Version ${update.versionName} is ready to install.")
                if (update.releaseNotes.isNotBlank()) {
                    Text(
                        update.releaseNotes.take(700),
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp))
                }
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(top = 14.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Downloading update…", modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !working, onClick = {
                error = null
                working = true
                scope.launch {
                    error = runCatching {
                        UpdateChecker.downloadAndInstall(context, update)
                    }.exceptionOrNull()?.message
                    working = false
                }
            }) { Text(if (working) "Downloading…" else "Update") }
        },
        dismissButton = {
            TextButton(enabled = !working, onClick = onDismiss) { Text("Later") }
        }
    )
}
