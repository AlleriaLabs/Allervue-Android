package com.allerix.app

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A lightweight, private recommendation ranker for Jellyfin libraries.
 * It learns genre, tag, cast/crew, studio and country affinities from watched titles
 * and favorites, then ranks unseen titles from the user's own library.
 */
object RecommendationEngine {
    fun rank(
        candidates: List<Item>,
        history: List<Item>,
        favorites: List<Item>,
        excludeIds: Set<String>,
        limit: Int = 12
    ): List<Item> {
        val watchedIds = history.mapTo(HashSet()) { it.id }
        val favoriteIds = favorites.mapTo(HashSet()) { it.id }
        val weights = HashMap<String, Double>()

        fun addFeatures(item: Item, multiplier: Double) {
            item.genres.orEmpty().forEach { add(weights, "genre", it, multiplier * 1.8) }
            item.tags.orEmpty().forEach { add(weights, "tag", it, multiplier * 0.9) }
            item.people.orEmpty().take(10).forEach { person ->
                val kind = if (person.type.equals("Director", true)) "director" else "person"
                add(weights, kind, person.name, multiplier * if (kind == "director") 1.0 else 0.25)
            }
            item.studios.orEmpty().forEach { add(weights, "studio", it.name, multiplier * 0.65) }
            item.productionLocations.orEmpty().forEach { add(weights, "country", it, multiplier * 0.35) }
        }

        history.forEachIndexed { index, item ->
            // Recent watches count more, while a larger play count strengthens the signal.
            val recency = 1.0 / (1.0 + index / 16.0)
            val repeatBoost = 1.0 + ln(1.0 + item.userData?.playCount?.coerceAtLeast(0)?.toDouble().orZero()) * 0.22
            addFeatures(item, recency * repeatBoost)
        }
        favorites.forEach { addFeatures(it, 2.2) }

        val seen = HashSet<String>()
        val eligible = candidates.filter { item ->
            item.id !in excludeIds && item.id !in watchedIds &&
                item.userData?.isPlayed != true && item.type in setOf("Movie", "Series") &&
                seen.add(item.id)
        }
        if (eligible.isEmpty()) return emptyList()

        val hasTasteSignal = weights.isNotEmpty()
        return eligible.map { item ->
            val features = features(item)
            var score = 0.0
            features.forEach { key -> score += weights[key] ?: 0.0 }
            // A modest quality prior helps break ties without overpowering personal taste.
            score += ((item.rating ?: 0.0).coerceIn(0.0, 10.0) / 10.0) * 0.35
            if (item.year != null) {
                val age = (2026 - item.year).coerceAtLeast(0)
                score += (1.0 / (1.0 + age / 18.0)) * 0.08
            }
            // With no history, return a useful starter shelf based on library quality.
            if (!hasTasteSignal) score += (item.rating ?: 0.0).coerceIn(0.0, 10.0)
            item to (score / sqrt(features.size.coerceAtLeast(1).toDouble()))
        }.sortedWith(compareByDescending<Pair<Item, Double>> { it.second }.thenBy { it.first.name })
            .take(limit).map { it.first }
    }

    private fun features(item: Item): Set<String> = buildSet {
        item.genres.orEmpty().forEach { add(key("genre", it)) }
        item.tags.orEmpty().forEach { add(key("tag", it)) }
        item.people.orEmpty().take(10).forEach {
            add(key(if (it.type.equals("Director", true)) "director" else "person", it.name))
        }
        item.studios.orEmpty().forEach { add(key("studio", it.name)) }
        item.productionLocations.orEmpty().forEach { add(key("country", it)) }
    }

    private fun add(weights: MutableMap<String, Double>, type: String, value: String?, amount: Double) {
        if (value.isNullOrBlank()) return
        val k = key(type, value)
        weights[k] = (weights[k] ?: 0.0) + amount
    }

    private fun key(type: String, value: String): String = "$type:${value.trim().lowercase()}"
    private fun Double?.orZero(): Double = this ?: 0.0
}
