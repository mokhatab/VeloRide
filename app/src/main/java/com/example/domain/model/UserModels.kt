package com.example.domain.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class AccountMode {
    RIDER, DRIVER
}

enum class VehicleCategory(val displayName: String, val multiplierHint: String) {
    ECONOMY("Velo Economy", "1.0x"),
    COMFORT("Velo Comfort", "1.35x"),
    XL("Velo XL", "1.65x"),
    VIP("Velo Executive", "2.2x")
}

enum class DriverApprovalStatus {
    REGISTERED, DOCUMENTS_PENDING, UNDER_REVIEW, APPROVED, REJECTED, SUSPENDED
}

enum class DriverOnlineStatus {
    OFFLINE, ONLINE, OFFER_RECEIVED, ACCEPTED, ARRIVING, ON_TRIP, TEMPORARILY_UNAVAILABLE
}

enum class DriverDocumentType {
    DRIVING_LICENCE, VEHICLE_REGISTRATION, NATIONAL_ID, COUNTRY_PERMIT
}

@IgnoreExtraProperties
data class UserAccount(
    @DocumentId val id: String = "",
    val userId: String = "",
    val fullName: String = "",
    val mobileNumber: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val countryCode: String = "EG",
    val preferredLanguage: String = "ar",
    val activeMode: String = AccountMode.RIDER.name,
    val hasRiderProfile: Boolean = true,
    val hasDriverProfile: Boolean = false,
    val isActive: Boolean = true,
    val isSuspended: Boolean = false,
    val emailVerified: Boolean = false,
    val phoneVerified: Boolean = false,
    val riderRatingAvg: Double = 5.0,
    val riderRatingCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SavedLocation(
    @DocumentId val id: String = "",
    val userId: String = "",
    val label: String = "",
    val address: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val countryCode: String = "EG",
    val city: String = "Cairo",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PaymentMethodRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val brand: String = "Visa",
    val last4: String = "4242",
    val expiryMonth: Long = 12L,
    val expiryYear: Long = 2028L,
    val stripePaymentMethodId: String = "",
    val methodType: String = "CARD",
    val isDefault: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DriverProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val fullName: String = "",
    val mobileNumber: String = "",
    val email: String = "",
    val nationalId: String = "",
    val licenceNumber: String = "",
    val licenceExpiry: String = "",
    val profilePhotoUrl: String? = null,
    val vehicleMake: String = "",
    val vehicleModel: String = "",
    val vehicleYear: Long = 2023L,
    val vehicleCategory: String = VehicleCategory.ECONOMY.name,
    val vehicleColor: String = "",
    val registrationNumber: String = "",
    val plateNumber: String = "",
    val vehiclePhotoUrls: List<String> = emptyList(),
    val approvalStatus: String = DriverApprovalStatus.REGISTERED.name,
    val rejectionReason: String? = null,
    val driverRatingAvg: Double = 5.0,
    val driverRatingCount: Long = 0L,
    val totalTrips: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DriverDocument(
    @DocumentId val id: String = "",
    val driverId: String = "",
    val documentType: String = DriverDocumentType.DRIVING_LICENCE.name,
    val countryCode: String = "EG",
    val documentNumber: String = "",
    val expiryDate: String = "",
    val imageUri: String = "",
    val verificationStatus: String = "PENDING",
    val reviewNotes: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DriverAvailability(
    @DocumentId val id: String = "",
    val driverId: String = "",
    val displayName: String = "",
    val vehicleMake: String = "",
    val vehicleModel: String = "",
    val vehicleColor: String = "",
    val plateNumber: String = "",
    val vehicleCategory: String = VehicleCategory.ECONOMY.name,
    val approvalStatus: String = DriverApprovalStatus.REGISTERED.name,
    val status: String = DriverOnlineStatus.OFFLINE.name,
    val latitude: Double = 30.0444,
    val longitude: Double = 31.2357,
    val heading: Double = 0.0,
    val locationTimestampMs: Long = 0L,
    val activeRideId: String = "",
    val countryCode: String = "EG",
    val city: String = "Cairo",
    val driverRatingAvg: Double = 5.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
