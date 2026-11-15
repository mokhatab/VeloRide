package com.example.core.push

import android.util.Log
import com.example.core.alerts.RideAlertPlayer
import com.example.data.firestore.FcmTokenRepository
import com.example.domain.model.AccountMode
import com.example.domain.model.FcmPayloadParser
import com.example.domain.model.FcmPushEvent
import com.example.domain.model.FcmPushEventBus
import com.example.domain.model.FcmPushEventType
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Handles real-time Firebase Cloud Messaging registration token refreshes
 * and inbound push notifications for ride offers and trip status transitions.
 */
class VeloMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM token (${token.take(8)}...)")
        FcmPushEventBus.emit(FcmPushEvent.TokenRefreshed(token))

        val currentUser = runCatching { Firebase.auth.currentUser }.getOrNull() ?: return
        serviceScope.launch {
            runCatching {
                val repo = FcmTokenRepository(applicationContext)
                repo.upsertFcmToken(
                    token = token,
                    activeMode = AccountMode.RIDER.name,
                    preferredLanguage = "ar",
                    notificationsEnabled = true
                )
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val payload = FcmPayloadParser.parse(
            data = remoteMessage.data,
            fallbackTitle = remoteMessage.notification?.title,
            fallbackBody = remoteMessage.notification?.body
        )

        when (payload.eventType) {
            FcmPushEventType.DRIVER_RIDE_OFFER -> {
                val offerId = payload.offerId.ifBlank { payload.rideId.ifBlank { payload.idempotencyKey } }
                if (offerId.isNotBlank()) {
                    RideAlertPlayer(applicationContext).notifyNewRideOffer(
                        offerId = offerId,
                        pickup = payload.pickupAddress.ifBlank { payload.title },
                        destination = payload.destinationAddress.ifBlank { payload.body },
                        fareDisplay = payload.fareDisplay.ifBlank { "New Offer" },
                        expiresAtMs = payload.expiresAtMs,
                        zoneHex = payload.zoneHex
                    )
                }
            }
            FcmPushEventType.OFFER_REVOKED -> {
                val offerId = payload.offerId.ifBlank { payload.rideId }
                if (offerId.isNotBlank()) {
                    RideAlertPlayer.stopOfferAlertStatic(applicationContext, offerId)
                }
            }
            else -> {
                TripStatusNotificationHelper.showTripStatusNotification(
                    context = applicationContext,
                    payload = payload,
                    dedupeKey = payload.idempotencyKey
                )
            }
        }

        FcmPushEventBus.emit(FcmPushEvent.MessageReceived(payload))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "VeloMessagingService"
    }
}
