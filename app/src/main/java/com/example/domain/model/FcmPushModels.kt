package com.example.domain.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@IgnoreExtraProperties
data class UserFcmToken(
    @DocumentId val id: String = "",
    val userId: String = "",
    val token: String = "",
    val platform: String = "ANDROID",
    val deviceModel: String = "",
    val appVersion: String = "1.0",
    val activeMode: String = AccountMode.RIDER.name,
    val preferredLanguage: String = "ar",
    val notificationsEnabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

enum class FcmPushEventType {
    DRIVER_RIDE_OFFER,
    OFFER_REVOKED,
    DRIVER_ACCEPTED,
    DRIVER_ARRIVING,
    DRIVER_ARRIVED,
    TRIP_STARTED,
    TRIP_COMPLETED,
    RIDE_CANCELLED,
    NO_DRIVER_AVAILABLE,
    TRIP_STATUS_UPDATE
}

data class FcmRidePushPayload(
    val eventType: FcmPushEventType,
    val rideId: String = "",
    val offerId: String = "",
    val idempotencyKey: String = "",
    val title: String = "",
    val body: String = "",
    val status: String = "",
    val pickupAddress: String = "",
    val destinationAddress: String = "",
    val fareDisplay: String = "",
    val expiresAtMs: Long = 0L,
    val zoneHex: String = "",
    val targetRoute: String = "activity"
)

object FcmPayloadParser {

    fun parse(
        data: Map<String, String>,
        fallbackTitle: String? = null,
        fallbackBody: String? = null
    ): FcmRidePushPayload {
        val rawType = (data["eventType"] ?: data["type"] ?: data["status"] ?: "").trim().uppercase()
        val eventType = when (rawType) {
            "DRIVER_RIDE_OFFER", "RIDE_OFFER", "OFFERED" -> FcmPushEventType.DRIVER_RIDE_OFFER
            "OFFER_REVOKED", "CANCELLED_BY_OTHER", "EXPIRED" -> FcmPushEventType.OFFER_REVOKED
            "DRIVER_ACCEPTED", "ACCEPTED" -> FcmPushEventType.DRIVER_ACCEPTED
            "DRIVER_ARRIVING" -> FcmPushEventType.DRIVER_ARRIVING
            "DRIVER_ARRIVED", "ARRIVED" -> FcmPushEventType.DRIVER_ARRIVED
            "TRIP_STARTED", "IN_PROGRESS" -> FcmPushEventType.TRIP_STARTED
            "TRIP_COMPLETED", "COMPLETED" -> FcmPushEventType.TRIP_COMPLETED
            "RIDE_CANCELLED", "CANCELLED" -> FcmPushEventType.RIDE_CANCELLED
            "NO_DRIVER_AVAILABLE" -> FcmPushEventType.NO_DRIVER_AVAILABLE
            else -> FcmPushEventType.TRIP_STATUS_UPDATE
        }

        val rideId = data["rideId"].orEmpty()
        val offerId = data["offerId"].orEmpty()
        val pickup = data["pickupAddress"].orEmpty()
        val destination = data["destinationAddress"].orEmpty()
        val fareDisplay = data["fareDisplay"].orEmpty()
        val expiresAtMs = data["expiresAtMs"]?.toLongOrNull()
            ?: (System.currentTimeMillis() + 30_000L)
        val zoneHex = data["zoneHex"].orEmpty()
        val status = data["status"]?.takeIf { it.isNotBlank() } ?: eventType.name

        val defaultTitle = when (eventType) {
            FcmPushEventType.DRIVER_RIDE_OFFER -> "New Ride Request"
            FcmPushEventType.OFFER_REVOKED -> "Ride Offer Revoked"
            FcmPushEventType.DRIVER_ACCEPTED -> "Driver Accepted Your Ride"
            FcmPushEventType.DRIVER_ARRIVING -> "Driver On The Way"
            FcmPushEventType.DRIVER_ARRIVED -> "Your Driver Has Arrived"
            FcmPushEventType.TRIP_STARTED -> "Trip In Progress"
            FcmPushEventType.TRIP_COMPLETED -> "Trip Completed"
            FcmPushEventType.RIDE_CANCELLED -> "Ride Cancelled"
            FcmPushEventType.NO_DRIVER_AVAILABLE -> "No Drivers Available"
            FcmPushEventType.TRIP_STATUS_UPDATE -> "VeloRide Trip Update"
        }
        val title = data["title"]?.takeIf { it.isNotBlank() }
            ?: fallbackTitle?.takeIf { it.isNotBlank() }
            ?: defaultTitle

        val defaultBody = when {
            pickup.isNotBlank() && destination.isNotBlank() -> "$pickup → $destination"
            rideId.isNotBlank() -> "Ride $rideId updated: $status"
            else -> "Open VeloRide for live trip status."
        }
        val body = data["body"]?.takeIf { it.isNotBlank() }
            ?: fallbackBody?.takeIf { it.isNotBlank() }
            ?: defaultBody

        val targetRoute = data["targetRoute"]?.takeIf { it.isNotBlank() } ?: when (eventType) {
            FcmPushEventType.DRIVER_RIDE_OFFER,
            FcmPushEventType.OFFER_REVOKED -> "home"
            FcmPushEventType.TRIP_COMPLETED,
            FcmPushEventType.RIDE_CANCELLED,
            FcmPushEventType.NO_DRIVER_AVAILABLE -> "trip_history"
            else -> "activity"
        }

        val idempotencyKey = data["idempotencyKey"]?.takeIf { it.isNotBlank() }
            ?: "${eventType.name}_${rideId.ifBlank { offerId.ifBlank { title.hashCode().toString() } }}"

        return FcmRidePushPayload(
            eventType = eventType,
            rideId = rideId,
            offerId = offerId,
            idempotencyKey = idempotencyKey,
            title = title,
            body = body,
            status = status,
            pickupAddress = pickup,
            destinationAddress = destination,
            fareDisplay = fareDisplay,
            expiresAtMs = expiresAtMs,
            zoneHex = zoneHex,
            targetRoute = targetRoute
        )
    }
}

sealed interface FcmPushEvent {
    data class MessageReceived(val payload: FcmRidePushPayload) : FcmPushEvent
    data class TokenRefreshed(val token: String) : FcmPushEvent
}

object FcmPushEventBus {
    private val _events = MutableSharedFlow<FcmPushEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<FcmPushEvent> = _events.asSharedFlow()

    fun emit(event: FcmPushEvent) {
        _events.tryEmit(event)
    }
}
