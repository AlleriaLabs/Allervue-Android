package com.allerix.app

import android.content.Context

/**
 * Per-install hidden Continue Watching entries. This only hides a card in Allervue;
 * it does not delete media or alter the Jellyfin server's playback history.
 */
object ContinueWatching {
    private const val PREFS = "continue_watching"
    private const val HIDDEN_IDS = "hidden_ids"

    fun hiddenIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(HIDDEN_IDS, emptySet())
            ?.toSet()
            ?: emptySet()

    fun hide(context: Context, itemId: String) {
        val updated = hiddenIds(context) + itemId
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(HIDDEN_IDS, updated)
            .apply()
    }
}
