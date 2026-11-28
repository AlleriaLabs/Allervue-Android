package com.allerix.app

/** One selectable Media3 track, retaining its group and in-group index. */
internal data class PlaybackTrackCandidate(
    val groupIndex: Int,
    val trackIndex: Int,
    val formatId: String?,
    val language: String?,
    val label: String?
)

internal data class PlaybackTrackMatch(val groupIndex: Int, val trackIndex: Int)

/**
 * Matches Jellyfin stream metadata to Media3 tracks.
 * Exact stream IDs take priority over language and display-title fallbacks.
 */
internal object PlaybackTrackMatcher {
    fun find(
        candidates: List<PlaybackTrackCandidate>,
        selection: PlaybackTrackSelection
    ): PlaybackTrackMatch? {
        candidates.firstOrNull {
            it.formatId?.toIntOrNull() == selection.streamIndex
        }?.let { return PlaybackTrackMatch(it.groupIndex, it.trackIndex) }

        val language = normalizeLanguage(selection.language)
        if (language.isNotEmpty()) {
            candidates.firstOrNull {
                normalizeLanguage(it.language) == language
            }?.let { return PlaybackTrackMatch(it.groupIndex, it.trackIndex) }
        }

        val title = selection.title?.trim()?.takeIf { it.isNotEmpty() }
        if (title != null) {
            candidates.firstOrNull {
                it.label?.contains(title, ignoreCase = true) == true
            }?.let { return PlaybackTrackMatch(it.groupIndex, it.trackIndex) }
        }
        return null
    }

    private fun normalizeLanguage(value: String?): String {
        val tag = value.orEmpty().lowercase(java.util.Locale.ROOT)
            .substringBefore('-').substringBefore('_')
        return when (tag) {
            "eng" -> "en"; "spa" -> "es"; "jpn" -> "ja"; "kor" -> "ko"
            "zho", "chi" -> "zh"; "fra", "fre" -> "fr"; "deu", "ger" -> "de"
            "ita" -> "it"; "por" -> "pt"; "rus" -> "ru"; "ara" -> "ar"
            "fil", "tgl" -> "fil"; else -> tag
        }
    }
}
