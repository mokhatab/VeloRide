package com.example.domain

import com.example.domain.model.Ride
import com.example.domain.model.RideStatus
import com.example.domain.model.RiderPaymentChoice
import com.example.domain.model.TripHistoryFilter
import com.example.domain.model.TripHistoryMapper
import com.example.domain.model.TripHistoryRole
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripHistoryUnitTest {

    @Test
    fun tripHistoryMapper_filtersTerminalRidesSortsNewestFirstAndAggregatesSummary() {
        val activeRide = Ride(
            id = "ride_active",
            riderId = "r1",
            driverId = "d1",
            status = RideStatus.IN_PROGRESS.name,
            estimatedFareMinor = 5000L,
            updatedAt = Timestamp(500L, 0)
        )
        val completedOlder = Ride(
            id = "ride_done_1",
            riderId = "r1",
            driverId = "d1",
            status = RideStatus.COMPLETED.name,
            estimatedDistanceKm = 10.0,
            actualDistanceKm = 11.5,
            estimatedFareMinor = 8000L,
            finalFareMinor = 9200L,
            currencyCode = "EGP",
            currencySymbol = "EGP",
            currencyDecimals = 2L,
            paymentMethodId = "E_WALLET:pm_1",
            updatedAt = Timestamp(100L, 0)
        )
        val cancelledNewer = Ride(
            id = "ride_cancel_2",
            riderId = "r1",
            driverId = "d1",
            status = RideStatus.CANCELLED.name,
            estimatedDistanceKm = 4.0,
            estimatedFareMinor = 3500L,
            finalFareMinor = 0L,
            currencyCode = "EGP",
            currencySymbol = "EGP",
            currencyDecimals = 2L,
            paymentMethodId = "CASH",
            updatedAt = Timestamp(300L, 0)
        )
        val noDriverRiderOnly = Ride(
            id = "ride_nodriver",
            riderId = "r1",
            driverId = "",
            status = RideStatus.NO_DRIVER_AVAILABLE.name,
            estimatedFareMinor = 4000L,
            updatedAt = Timestamp(200L, 0)
        )

        val allRides = listOf(activeRide, completedOlder, cancelledNewer, noDriverRiderOnly)

        // Rider sees all 3 terminal rides sorted newest first, excluding IN_PROGRESS
        val riderAll = TripHistoryMapper.buildEntries(allRides, TripHistoryRole.RIDER, TripHistoryFilter.ALL)
        assertEquals(listOf("ride_cancel_2", "ride_nodriver", "ride_done_1"), riderAll.map { it.ride.id })
        assertEquals(RiderPaymentChoice.CASH, riderAll[0].paymentChoice)
        assertEquals(3500L, riderAll[0].fareMinor)
        assertEquals(9200L, riderAll[2].fareMinor)
        assertEquals(11.5, riderAll[2].distanceKm, 0.01)
        assertEquals(RiderPaymentChoice.E_WALLET, riderAll[2].paymentChoice)

        // Driver excludes NO_DRIVER_AVAILABLE and active rides
        val driverAll = TripHistoryMapper.buildEntries(allRides, TripHistoryRole.DRIVER, TripHistoryFilter.ALL)
        assertEquals(listOf("ride_cancel_2", "ride_done_1"), driverAll.map { it.ride.id })

        // Completed filter
        val completedOnly = TripHistoryMapper.buildEntries(allRides, TripHistoryRole.RIDER, TripHistoryFilter.COMPLETED)
        assertEquals(listOf("ride_done_1"), completedOnly.map { it.ride.id })
        assertTrue(completedOnly.first().isCompleted)

        // Cancelled filter
        val cancelledOnly = TripHistoryMapper.buildEntries(allRides, TripHistoryRole.RIDER, TripHistoryFilter.CANCELLED)
        assertEquals(listOf("ride_cancel_2", "ride_nodriver"), cancelledOnly.map { it.ride.id })

        // Summary computes completed trips, completed fare, and completed distance
        val summary = TripHistoryMapper.summarize(riderAll)
        assertEquals(3, summary.totalTrips)
        assertEquals(1, summary.completedTrips)
        assertEquals(9200L, summary.totalFareMinor)
        assertEquals(11.5, summary.totalDistanceKm, 0.01)
    }
}
