package com.allerix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    @Test
    fun ranksTitlesThatMatchWatchedGenresHigher() {
        val history = listOf(item("watched", genres = listOf("Drama")))
        val drama = item("drama", genres = listOf("Drama"))
        val comedy = item("comedy", genres = listOf("Comedy"))

        val result = RecommendationEngine.rank(
            candidates = listOf(comedy, drama),
            history = history,
            favorites = emptyList(),
            excludeIds = emptySet(),
            currentYear = 2026
        )

        assertEquals("drama", result.first().id)
    }

    @Test
    fun excludesWatchedFavoritesAndExplicitlyExcludedTitles() {
        val history = listOf(item("watched", genres = listOf("Drama")))
        val favorite = item("favorite", genres = listOf("Drama"))
        val candidates = listOf(
            item("watched", genres = listOf("Drama")),
            favorite,
            item("hidden", genres = listOf("Drama")),
            item("new", genres = listOf("Drama"))
        )

        val result = RecommendationEngine.rank(
            candidates = candidates,
            history = history,
            favorites = listOf(favorite),
            excludeIds = setOf("hidden"),
            currentYear = 2026
        )

        assertEquals(listOf("new"), result.map { it.id })
    }

    @Test
    fun removesDuplicateCandidatesAndUnsupportedMediaTypes() {
        val movie = item("movie", genres = listOf("Drama"))
        val result = RecommendationEngine.rank(
            candidates = listOf(movie, movie, item("folder", type = "Folder")),
            history = emptyList(),
            favorites = emptyList(),
            excludeIds = emptySet(),
            currentYear = 2026
        )

        assertEquals(listOf("movie"), result.map { it.id })
    }

    @Test
    fun usesCommunityRatingAsStarterSignalWithoutWatchHistory() {
        val low = item("low", rating = 4.0)
        val high = item("high", rating = 9.0)

        val result = RecommendationEngine.rank(
            candidates = listOf(low, high),
            history = emptyList(),
            favorites = emptyList(),
            excludeIds = emptySet(),
            currentYear = 2026
        )

        assertEquals("high", result.first().id)
    }

    @Test
    fun respectsRequestedResultLimitAndZeroLimit() {
        val candidates = (1..4).map { item("item-$it", rating = it.toDouble()) }
        val result = RecommendationEngine.rank(
            candidates = candidates,
            history = emptyList(),
            favorites = emptyList(),
            excludeIds = emptySet(),
            limit = 2,
            currentYear = 2026
        )
        val empty = RecommendationEngine.rank(
            candidates = candidates,
            history = emptyList(),
            favorites = emptyList(),
            excludeIds = emptySet(),
            limit = 0,
            currentYear = 2026
        )

        assertEquals(2, result.size)
        assertTrue(empty.isEmpty())
    }

    @Test
    fun returnsEmptyWhenNoEligibleCandidatesRemain() {
        val result = RecommendationEngine.rank(
            candidates = listOf(item("watched")),
            history = listOf(item("watched")),
            favorites = emptyList(),
            excludeIds = emptySet(),
            currentYear = 2026
        )

        assertFalse(result.isNotEmpty())
    }

    private fun item(
        id: String,
        type: String = "Movie",
        genres: List<String> = emptyList(),
        rating: Double? = 7.0
    ) = Item(id = id, name = id, type = type, genres = genres, rating = rating)
}
