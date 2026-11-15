package com.example.domain

import com.example.core.i18n.AppLanguage
import com.example.domain.model.AccountMode
import com.example.domain.model.FeedbackTag
import com.example.domain.model.RatingAggregator
import com.example.domain.model.RatingRecord
import com.example.ui.components.formatAverageRating
import com.example.ui.components.localizedLabel
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingUnitTest {

    @Test
    fun ratingAggregator_computesExactAverageBreakdownAndTopTags() {
        val ratings = listOf(
            RatingRecord(
                id = "r1",
                rideId = "ride_1",
                fromUserId = "u1",
                toUserId = "driver_1",
                fromRole = "RIDER",
                stars = 5L,
                comment = "Spotless car and safe highway driving.",
                tags = listOf("CLEAN_VEHICLE", "SMOOTH_DRIVE", "POLITE_DRIVER"),
                raterName = "Layla",
                createdAt = Timestamp(1_700_000_100L, 0)
            ),
            RatingRecord(
                id = "r2",
                rideId = "ride_2",
                fromUserId = "u2",
                toUserId = "driver_1",
                fromRole = "RIDER",
                stars = 4L,
                comment = "Arrived quickly.",
                tags = listOf("ON_TIME_PICKUP", "CLEAN_VEHICLE"),
                raterName = "Omar",
                createdAt = Timestamp(1_700_000_300L, 0)
            ),
            RatingRecord(
                id = "r3",
                rideId = "ride_3",
                fromUserId = "u3",
                toUserId = "driver_1",
                fromRole = "RIDER",
                stars = 5L,
                comment = "Great!",
                tags = listOf("CLEAN_VEHICLE", "SMOOTH_DRIVE"),
                raterName = "Hannah",
                createdAt = Timestamp(1_700_000_200L, 0)
            )
        )

        val summary = RatingAggregator.summarize(ratings)
        // (5 + 4 + 5) / 3 = 4.6666... -> 4.67
        assertEquals(4.67, summary.averageRating, 0.001)
        assertEquals(3, summary.ratingCount)
        assertEquals(2, summary.starCounts[5])
        assertEquals(1, summary.starCounts[4])
        assertEquals(0, summary.starCounts[3])
        assertEquals(0, summary.starCounts[2])
        assertEquals(0, summary.starCounts[1])

        // Top tags ordered by frequency desc: CLEAN_VEHICLE (3), SMOOTH_DRIVE (2)
        assertEquals("CLEAN_VEHICLE" to 3, summary.topTags[0])
        assertEquals("SMOOTH_DRIVE" to 2, summary.topTags[1])

        // Recent feedback sorted by createdAt descending: r2 (300), r3 (200), r1 (100)
        assertEquals(listOf("r2", "r3", "r1"), summary.recentFeedback.map { it.id })
    }

    @Test
    fun ratingAggregator_handlesEmptyRatingsWithFallback() {
        val emptyDefault = RatingAggregator.summarize(emptyList())
        assertEquals(5.0, emptyDefault.averageRating, 0.001)
        assertEquals(0, emptyDefault.ratingCount)
        assertTrue(emptyDefault.topTags.isEmpty())

        val emptyWithProfileFallback = RatingAggregator.summarize(emptyList(), fallbackAvg = 4.82, fallbackCount = 14)
        assertEquals(4.82, emptyWithProfileFallback.averageRating, 0.001)
        assertEquals(14, emptyWithProfileFallback.ratingCount)
    }

    @Test
    fun feedbackTags_separatedByTargetRoleAndLocalizedAcrossAll7Languages() {
        val driverTags = FeedbackTag.forTargetRole(AccountMode.DRIVER)
        val passengerTags = FeedbackTag.forTargetRole(AccountMode.RIDER)

        assertEquals(5, driverTags.size)
        assertEquals(5, passengerTags.size)
        assertTrue(driverTags.none { it in passengerTags })

        AppLanguage.entries.forEach { lang ->
            FeedbackTag.entries.forEach { tag ->
                assertTrue(
                    "Tag ${tag.key} must have non-blank label in ${lang.name}",
                    tag.localizedLabel(lang).isNotBlank()
                )
            }
        }
        assertEquals("4.67", formatAverageRating(4.67))
        assertEquals("5.0", formatAverageRating(5.0))
    }
}
