package com.example.domain.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class PaymentStatus {
    PENDING, REQUIRES_ACTION, AUTHORIZED, SUCCEEDED, FAILED, CANCELLED, REFUNDED, PARTIALLY_REFUNDED
}

enum class PayoutStatus {
    PENDING, ELIGIBLE, PAID_MANUAL, PAID_STRIPE, HELD
}

@IgnoreExtraProperties
data class PricingPolicy(
    @DocumentId val id: String = "",
    val countryCode: String = "EG",
    val countryName: String = "Egypt",
    val city: String = "Cairo",
    val currencyCode: String = "EGP",
    val currencySymbol: String = "EGP",
    val currencyDecimals: Long = 2L,
    val serviceCategory: String = VehicleCategory.ECONOMY.name,
    val isTaxiReference: Boolean = false,
    val baseFareMinor: Long = 1200L,
    val includedDistanceKm: Double = 1.0,
    val pricePerKmMinor: Long = 650L,
    val waitingChargePerMinMinor: Long = 100L,
    val minimumFareMinor: Long = 2500L,
    val bookingFeeMinor: Long = 300L,
    val taxPercentage: Double = 14.0,
    val airportSurchargeMinor: Long = 2000L,
    val nightSurchargeMinor: Long = 500L,
    val trafficSurchargeMinor: Long = 0L,
    val surgeMultiplier: Double = 1.0,
    val effectiveStartMs: Long = 0L,
    val effectiveEndMs: Long = 4102444800000L,
    val pricingVersion: Long = 1L,
    val isActive: Boolean = true,
    val updatedByAdminId: String = "system",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class FareQuote(
    @DocumentId val id: String = "",
    val riderId: String = "",
    val countryCode: String = "EG",
    val countryName: String = "Egypt",
    val city: String = "Cairo",
    val currencyCode: String = "EGP",
    val currencySymbol: String = "EGP",
    val currencyDecimals: Long = 2L,
    val pickupAddress: String = "",
    val pickupLat: Double = 0.0,
    val pickupLng: Double = 0.0,
    val destinationAddress: String = "",
    val destinationLat: Double = 0.0,
    val destinationLng: Double = 0.0,
    val serviceCategory: String = VehicleCategory.ECONOMY.name,
    val estimatedDistanceKm: Double = 0.0,
    val estimatedDurationMin: Long = 0L,
    val baseFareMinor: Long = 0L,
    val distanceChargeMinor: Long = 0L,
    val waitingChargeMinor: Long = 0L,
    val bookingFeeMinor: Long = 0L,
    val surchargesMinor: Long = 0L,
    val taxesMinor: Long = 0L,
    val discountsMinor: Long = 0L,
    val finalEstimatedFareMinor: Long = 0L,
    val pricingVersion: Long = 1L,
    val pricingPolicyId: String = "",
    val createdAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PaymentRecord(
    @DocumentId val id: String = "",
    val rideId: String = "",
    val riderId: String = "",
    val driverId: String = "",
    val idempotencyKey: String = "",
    val stripePaymentIntentId: String = "",
    val paymentMethodType: String = "CARD",
    val currencyCode: String = "EGP",
    val currencyDecimals: Long = 2L,
    val amountMinor: Long = 0L,
    val refundedAmountMinor: Long = 0L,
    val platformFeeMinor: Long = 0L,
    val taxMinor: Long = 0L,
    val conversionRate: Double = 1.0,
    val settlementCurrency: String = "EGP",
    val settlementAmountMinor: Long = 0L,
    val conversionTimestampMs: Long = 0L,
    val conversionSource: String = "local_currency",
    val status: String = PaymentStatus.PENDING.name,
    val receiptNumber: String = "",
    val failureReason: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DriverEarning(
    @DocumentId val id: String = "",
    val driverId: String = "",
    val rideId: String = "",
    val paymentId: String = "",
    val currencyCode: String = "EGP",
    val currencyDecimals: Long = 2L,
    val grossFareMinor: Long = 0L,
    val platformCommissionMinor: Long = 0L,
    val taxesMinor: Long = 0L,
    val adjustmentsMinor: Long = 0L,
    val refundsMinor: Long = 0L,
    val netEarningsMinor: Long = 0L,
    val payoutStatus: String = PayoutStatus.PENDING.name,
    val payoutId: String = "",
    val payoutReference: String = "",
    val payoutDateMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DriverPayout(
    @DocumentId val id: String = "",
    val driverId: String = "",
    val adminId: String = "",
    val amountMinor: Long = 0L,
    val currencyCode: String = "EGP",
    val payoutMethod: String = "MANUAL_BANK_TRANSFER",
    val payoutReference: String = "",
    val status: String = "INITIATED",
    val notes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AppNotification(
    @DocumentId val id: String = "",
    val userId: String = "",
    val eventType: String = "",
    val idempotencyKey: String = "",
    val titleAr: String = "",
    val titleEn: String = "",
    val bodyAr: String = "",
    val bodyEn: String = "",
    val rideId: String = "",
    val isRead: Boolean = false,
    val channel: String = "IN_APP",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AuditLog(
    @DocumentId val id: String = "",
    val actorId: String = "",
    val actorEmail: String = "",
    val action: String = "",
    val targetCollection: String = "",
    val targetId: String = "",
    val previousValueSummary: String = "",
    val newValueSummary: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class OperationalConfig(
    @DocumentId val id: String = "",
    val dispatchRound1RadiusKm: Double = 3.0,
    val dispatchRound2RadiusKm: Double = 7.0,
    val dispatchRound3RadiusKm: Double = 15.0,
    val offerDurationSeconds: Long = 30L,
    val gpsFreshnessMaxSeconds: Long = 90L,
    val cancellationFreeWindowSeconds: Long = 120L,
    val cancellationFeeMinor: Long = 1500L,
    val platformCommissionPercent: Double = 15.0,
    val updatedByAdminId: String = "system",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ComplaintRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val rideId: String = "",
    val category: String = "",
    val description: String = "",
    val status: String = "OPEN",
    val adminNotes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
