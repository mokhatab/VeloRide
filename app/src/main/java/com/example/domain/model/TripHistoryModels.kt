package com.example.domain.model

enum class TripHistoryRole {
    RIDER,
    DRIVER
}

enum class TripHistoryFilter {
    ALL,
    COMPLETED,
    CANCELLED
}

data class TripHistoryEntry(
    val ride: Ride,
    val timestampMs: Long,
    val fareMinor: Long,
    val distanceKm: Double,
    val paymentChoice: RiderPaymentChoice,
    val isCompleted: Boolean,
    val isCancelled: Boolean
)

data class TripHistorySummary(
    val totalTrips: Int = 0,
    val completedTrips: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val totalFareMinor: Long = 0L,
    val currencyCode: String = "EGP",
    val currencySymbol: String = "EGP",
    val currencyDecimals: Int = 2
)

object TripHistoryMapper {

    private val terminalStatusNames = setOf(
        RideStatus.COMPLETED.name,
        RideStatus.CANCELLED.name,
        RideStatus.NO_DRIVER_AVAILABLE.name,
        RideStatus.EXPIRED.name
    )

    private val driverTerminalStatusNames = setOf(
        RideStatus.COMPLETED.name,
        RideStatus.CANCELLED.name,
        RideStatus.EXPIRED.name
    )

    fun isTerminalForRole(status: String, role: TripHistoryRole): Boolean {
        return when (role) {
            TripHistoryRole.RIDER -> status in terminalStatusNames
            TripHistoryRole.DRIVER -> status in driverTerminalStatusNames
        }
    }

    fun toHistoryEntry(ride: Ride): TripHistoryEntry {
        val tsSeconds = ride.updatedAt?.seconds ?: ride.createdAt?.seconds ?: 0L
        val fareMinor = if (ride.finalFareMinor > 0L) ride.finalFareMinor else ride.estimatedFareMinor
        val distanceKm = if (ride.actualDistanceKm > 0.0) ride.actualDistanceKm else ride.estimatedDistanceKm
        val isCompleted = ride.status == RideStatus.COMPLETED.name
        val isCancelled = ride.status == RideStatus.CANCELLED.name ||
            ride.status == RideStatus.NO_DRIVER_AVAILABLE.name ||
            ride.status == RideStatus.EXPIRED.name
        return TripHistoryEntry(
            ride = ride,
            timestampMs = tsSeconds * 1000L,
            fareMinor = fareMinor,
            distanceKm = distanceKm,
            paymentChoice = RiderPaymentChoice.fromRidePaymentMethodId(ride.paymentMethodId),
            isCompleted = isCompleted,
            isCancelled = isCancelled
        )
    }

    fun buildEntries(
        rides: List<Ride>,
        role: TripHistoryRole,
        filter: TripHistoryFilter = TripHistoryFilter.ALL
    ): List<TripHistoryEntry> {
        return rides
            .asSequence()
            .filter { isTerminalForRole(it.status, role) }
            .sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
            .map(::toHistoryEntry)
            .filter { entry ->
                when (filter) {
                    TripHistoryFilter.ALL -> true
                    TripHistoryFilter.COMPLETED -> entry.isCompleted
                    TripHistoryFilter.CANCELLED -> entry.isCancelled
                }
            }
            .toList()
    }

    fun summarize(entries: List<TripHistoryEntry>): TripHistorySummary {
        if (entries.isEmpty()) return TripHistorySummary()
        val completed = entries.filter { it.isCompleted }
        val primaryCurrency = (completed.firstOrNull() ?: entries.first()).ride
        val sameCurrencyCompleted = completed.filter { it.ride.currencyCode == primaryCurrency.currencyCode }
        return TripHistorySummary(
            totalTrips = entries.size,
            completedTrips = completed.size,
            totalDistanceKm = completed.sumOf { it.distanceKm },
            totalFareMinor = sameCurrencyCompleted.sumOf { it.fareMinor },
            currencyCode = primaryCurrency.currencyCode,
            currencySymbol = primaryCurrency.currencySymbol,
            currencyDecimals = primaryCurrency.currencyDecimals.toInt()
        )
    }
}
