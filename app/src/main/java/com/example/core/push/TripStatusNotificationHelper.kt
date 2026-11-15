package com.example.core.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.i18n.AppLanguage
import com.example.domain.model.AppNotification
import com.example.domain.model.FcmPayloadParser
import com.example.domain.model.FcmPushEventType
import com.example.domain.model.FcmRidePushPayload
import java.util.concurrent.ConcurrentHashMap

object TripStatusNotificationHelper {

    const val CHANNEL_TRIP_UPDATES = "veloride_trip_updates"
    const val EXTRA_TARGET_ROUTE = "extra_target_route"
    const val EXTRA_RIDE_ID = "extra_ride_id"
    const val EXTRA_EVENT_TYPE = "extra_event_type"

    private val shownKeys = ConcurrentHashMap.newKeySet<String>()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val channel = NotificationChannel(
                CHANNEL_TRIP_UPDATES,
                "VeloRide Trip Status Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time notifications for ride acceptance, driver arrival, trip start, completion, and cancellations"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun showFromAppNotification(
        context: Context,
        notification: AppNotification,
        language: AppLanguage
    ): Boolean {
        val isArabic = language == AppLanguage.ARABIC
        val title = if (isArabic) notification.titleAr else notification.titleEn
        val body = if (isArabic) notification.bodyAr else notification.bodyEn
        val payload = FcmPayloadParser.parse(
            data = mapOf(
                "eventType" to notification.eventType,
                "rideId" to notification.rideId,
                "idempotencyKey" to notification.idempotencyKey.ifBlank { notification.id },
                "title" to title,
                "body" to body
            ),
            fallbackTitle = title,
            fallbackBody = body
        )
        return showTripStatusNotification(
            context = context,
            payload = payload,
            dedupeKey = payload.idempotencyKey
        )
    }

    fun showTripStatusNotification(
        context: Context,
        payload: FcmRidePushPayload,
        dedupeKey: String = payload.idempotencyKey
    ): Boolean {
        ensureChannel(context)
        if (dedupeKey.isNotBlank() && !shownKeys.add(dedupeKey)) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }

        return try {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_TARGET_ROUTE, payload.targetRoute)
                putExtra(EXTRA_RIDE_ID, payload.rideId)
                putExtra(EXTRA_EVENT_TYPE, payload.eventType.name)
            }
            val requestCode = (payload.rideId.ifBlank { payload.idempotencyKey }).hashCode()
            val pendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationId = dedupeKey.ifBlank { payload.rideId.ifBlank { payload.title } }.hashCode()
            val builder = NotificationCompat.Builder(context, CHANNEL_TRIP_UPDATES)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentTitle(payload.title)
                .setContentText(payload.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            if (payload.eventType == FcmPushEventType.TRIP_COMPLETED) {
                builder.setCategory(NotificationCompat.CATEGORY_STATUS)
            } else {
                builder.setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            }

            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun resetDeduplicationForTest() {
        shownKeys.clear()
    }
}
