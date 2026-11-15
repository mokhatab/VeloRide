package com.example.domain.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class RideStatus {
    REQUESTED,
    SEARCHING_FOR_DRIVER,
    OFFERED,
    ACCEPTED,
    DRIVER_ARRIVING,
    ARRIVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    NO_DRIVER_AVAILABLE,
    EXPIRED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED || this == NO_DRIVER_AVAILABLE || this == EXPIRED
}

enum class OfferStatus {
    PENDING, ACCEPTED, REJECTED, EXPIRED, CANCELLED_BY_OTHER
}

@IgnoreExtraProperties
data class Ride(
    @DocumentId val id: String = "",
    val riderId: String = "",
    val driverId: String = "",
    val riderName: String = "",
    val driverName: String? = null,
    val driverPlate: String? = null,
    val driverVehicle: String? = null,
    val pickupAddress: String = "",
    val pickupLat: Double = 0.0,
    val pickupLng: Double = 0.0,
    val destinationAddress: String = "",
    val destinationLat: Double = 0.0,
    val destinationLng: Double = 0.0,
    val countryCode: String = "EG",
    val countryName: String = "Egypt",
    val city: String = "Cairo",
    val currencyCode: String = "EGP",
    val currencySymbol: String = "EGP",
    val currencyDecimals: Long = 2L,
    val vehicleCategory: String = VehicleCategory.ECONOMY.name,
    val status: String = RideStatus.REQUESTED.name,
    val dispatchRound: Long = 1L,
    val estimatedDistanceKm: Double = 0.0,
    val estimatedDurationMin: Long = 0L,
    val actualDistanceKm: Double = 0.0,
    val waitingMinutes: Long = 0L,
    val estimatedFareMinor: Long = 0L,
    val finalFareMinor: Long = 0L,
    val pricingVersion: Long = 1L,
    val pricingPolicyId: String = "",
    val quoteId: String = "",
    val paymentStatus: String = "PENDING",
    val paymentMethodId: String? = null,
    val offeredDriverIds: List<String> = emptyList(),
    val cancellationInitiator: String? = null,
    val cancellationReason: String? = null,
    val cancellationFeeMinor: Long = 0L,
    val riderRated: Boolean = false,
    val driverRated: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class RideOffer(
    @DocumentId val id: String = "",
    val rideId: String = "",
    val riderId: String = "",
    val driverId: String = "",
    val round: Long = 1L,
    val status: String = OfferStatus.PENDING.name,
    val pickupAddress: String = "",
    val pickupLat: Double = 0.0,
    val pickupLng: Double = 0.0,
    val destinationAddress: String = "",
    val destinationLat: Double = 0.0,
    val destinationLng: Double = 0.0,
    val distanceToPickupKm: Double = 0.0,
    val estimatedTripDistanceKm: Double = 0.0,
    val estimatedDurationMin: Long = 0L,
    val estimatedFareMinor: Long = 0L,
    val currencyCode: String = "EGP",
    val currencySymbol: String = "EGP",
    val currencyDecimals: Long = 2L,
    val vehicleCategory: String = VehicleCategory.ECONOMY.name,
    val riderRatingAvg: Double = 5.0,
    val issuedAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val respondedAtMs: Long? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class RideStatusHistory(
    @DocumentId val id: String = "",
    val rideId: String = "",
    val actorId: String = "",
    val actorRole: String = "RIDER",
    val fromStatus: String = "",
    val toStatus: String = "",
    val note: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class RatingRecord(
    @DocumentId val id: String = "",
    val rideId: String = "",
    val fromUserId: String = "",
    val toUserId: String = "",
    val fromRole: String = "RIDER",
    val stars: Long = 5L,
    val comment: String = "",
    val tags: List<String> = emptyList(),
    val raterName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
