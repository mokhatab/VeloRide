package com.example.domain.model

import kotlin.math.roundToLong

enum class FeedbackTag(val key: String, val targetRole: AccountMode) {
    // Tags when Rider rates Driver
    CLEAN_VEHICLE("CLEAN_VEHICLE", AccountMode.DRIVER),
    SMOOTH_DRIVE("SMOOTH_DRIVE", AccountMode.DRIVER),
    POLITE_DRIVER("POLITE_DRIVER", AccountMode.DRIVER),
    ON_TIME_PICKUP("ON_TIME_PICKUP", AccountMode.DRIVER),
    SAFE_ROUTE("SAFE_ROUTE", AccountMode.DRIVER),

    // Tags when Driver rates Passenger (Rider)
    FRIENDLY_RIDER("FRIENDLY_RIDER", AccountMode.RIDER),
    PUNCTUAL_RIDER("PUNCTUAL_RIDER", AccountMode.RIDER),
    RESPECTFUL("RESPECTFUL", AccountMode.RIDER),
    CLEAR_PICKUP("CLEAR_PICKUP", AccountMode.RIDER),
    GREAT_TIPPER("GREAT_TIPPER", AccountMode.RIDER);

    companion object {
        fun forTargetRole(role: AccountMode): List<FeedbackTag> =
            entries.filter { it.targetRole == role }
    }
}

data class ProfileRatingSummary(
    val averageRating: Double = 5.0,
    val ratingCount: Int = 0,
    val starCounts: Map<Int, Int> = mapOf(5 to 0, 4 to 0, 3 to 0, 2 to 0, 1 to 0),
    val topTags: List<Pair<String, Int>> = emptyList(),
    val recentFeedback: List<RatingRecord> = emptyList()
)

object RatingAggregator {

    fun summarize(
        ratings: List<RatingRecord>,
        fallbackAvg: Double = 5.0,
        fallbackCount: Int = 0
    ): ProfileRatingSummary {
        if (ratings.isEmpty()) {
            return ProfileRatingSummary(
                averageRating = if (fallbackCount > 0) roundTwoDecimals(fallbackAvg.coerceIn(1.0, 5.0)) else 5.0,
                ratingCount = fallbackCount.coerceAtLeast(0),
                starCounts = mapOf(5 to 0, 4 to 0, 3 to 0, 2 to 0, 1 to 0),
                topTags = emptyList(),
                recentFeedback = emptyList()
            )
        }

        val clampedStars = ratings.map { it.stars.toInt().coerceIn(1, 5) }
        val avg = roundTwoDecimals(clampedStars.average())

        val counts = mutableMapOf(5 to 0, 4 to 0, 3 to 0, 2 to 0, 1 to 0)
        clampedStars.forEach { star ->
            counts[star] = (counts[star] ?: 0) + 1
        }

        val tagFreq = mutableMapOf<String, Int>()
        ratings.forEach { record ->
            record.tags.distinct().forEach { tag ->
                if (tag.isNotBlank()) {
                    tagFreq[tag] = (tagFreq[tag] ?: 0) + 1
                }
            }
        }
        val sortedTags = tagFreq.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(5)
            .map { it.key to it.value }

        val recent = ratings
            .sortedByDescending { it.createdAt?.seconds ?: 0L }
            .take(10)

        return ProfileRatingSummary(
            averageRating = avg,
            ratingCount = ratings.size,
            starCounts = counts,
            topTags = sortedTags,
            recentFeedback = recent
        )
    }

    private fun roundTwoDecimals(value: Double): Double =
        (value * 100.0).roundToLong() / 100.0
}
