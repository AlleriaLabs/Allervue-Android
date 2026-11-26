package com.allerix.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Fetches Jellyfin's audio-only transcode, then runs the existing offline Whisper pipeline. */
object AiSubtitlePlaybackGenerator {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun generate(
        context: Context,
        itemId: String,
        mediaSourceId: String,
        audioStreamIndex: Int?,
        model: AiSubtitleModel,
        language: String,
        outputName: String
    ): AiSubtitleFile = withContext(Dispatchers.IO) {
        require(Api.base.isNotBlank() && Api.userId.isNotBlank() && Api.token.isNotBlank()) {
            "Connect to your Jellyfin server before generating subtitles."
        }
        val query = buildList {
            add("UserId=${Api.userId}")
            add("DeviceId=allervue-android")
            if (mediaSourceId.isNotBlank()) add("MediaSourceId=${mediaSourceId}")
            if (audioStreamIndex != null) add("AudioStreamIndex=${audioStreamIndex}")
            add("Container=mp3")
            add("AudioCodec=mp3")
            add("api_key=${Api.token}")
        }.joinToString("&")
        val url = "${Api.base.trimEnd('/')}/Audio/$itemId/universal?$query"
        val audio = File(context.cacheDir, "ai_subtitle_work/${itemId}_audio.mp3")
        audio.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(url)
            .header("X-Emby-Token", Api.token)
            .header("X-MediaBrowser-Token", Api.token)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Jellyfin audio extraction failed (HTTP ${response.code}).")
            val body = response.body ?: error("Jellyfin returned an empty audio response.")
            FileOutputStream(audio).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        require(audio.isFile && audio.length() > 1024L) {
            "Jellyfin returned no usable audio. Check that the selected media has an audio track."
        }
        try {
            coroutineContext.ensureActive()
            AiSubtitleGenerator.generateFromAudioFile(
                context, audio, model, language, outputName
            )
        } finally {
            audio.delete()
        }
    }
}
