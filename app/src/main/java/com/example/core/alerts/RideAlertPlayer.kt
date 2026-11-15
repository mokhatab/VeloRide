package com.example.core.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a repeating zone-based dispatch alert tone & vibration throughout the 30-second
 * ride-offer countdown, and displays a high-priority status-bar notification with an interactive
 * ACCEPT button and live remaining seconds until accepted, rejected, expired, or revoked by another driver.
 */
class RideAlertPlayer(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeAlertJobs = ConcurrentHashMap<String, Job>()
    private val startedOfferIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var lastActiveNotificationOfferId: String? = null
        private set

    init {
        ensureNotificationChannel()
    }

    fun isAlertActive(offerId: String): Boolean = activeAlertJobs[offerId]?.isActive == true

    @Synchronized
    fun notifyNewRideOffer(
        offerId: String,
        pickup: String,
        destination: String,
        fareDisplay: String,
        expiresAtMs: Long = System.currentTimeMillis() + DEFAULT_OFFER_WINDOW_MS,
        zoneHex: String = ""
    ) {
        if (!startedOfferIds.add(offerId)) {
            return // Already ringing / showing 30s status-bar countdown for this offer
        }
        activeOfferIdsRegistry.add(offerId)
        lastActiveNotificationOfferId = offerId

        val durationMs = (expiresAtMs - System.currentTimeMillis()).coerceIn(1_000L, DEFAULT_OFFER_WINDOW_MS)
        val deadlineMs = System.currentTimeMillis() + durationMs

        val job = scope.launch {
            var tickIndex = 0
            try {
                while (isActive) {
                    val remainingMs = deadlineMs - System.currentTimeMillis()
                    if (remainingMs <= 0L) break

                    val remainingSec = ((remainingMs + 999L) / 1000L).toInt().coerceIn(1, 30)
                    postCountdownNotification(
                        offerId = offerId,
                        pickup = pickup,
                        destination = destination,
                        fareDisplay = fareDisplay,
                        zoneHex = zoneHex,
                        remainingSec = remainingSec,
                        remainingMs = remainingMs,
                        isInitialAlert = tickIndex == 0
                    )

                    // Play distinctive dispatch chime + haptic pulse every ~2 seconds during the 30s window
                    if (tickIndex % 2 == 0) {
                        triggerVibration()
                        playDistinctiveChimeOnce()
                    }
                    tickIndex++
                    delay(1_000L)
                }
            } finally {
                cancelSystemNotification(context, offerId)
                activeAlertJobs.remove(offerId)
                activeOfferIdsRegistry.remove(offerId)
                if (lastActiveNotificationOfferId == offerId) {
                    lastActiveNotificationOfferId = null
                }
            }
        }
        activeAlertJobs[offerId] = job
    }

    @Synchronized
    fun stopOfferAlert(offerId: String) {
        activeAlertJobs.remove(offerId)?.cancel()
        activeOfferIdsRegistry.remove(offerId)
        cancelSystemNotification(context, offerId)
        if (lastActiveNotificationOfferId == offerId) {
            lastActiveNotificationOfferId = null
        }
    }

    @Synchronized
    fun stopAllAlerts() {
        val ids = (activeAlertJobs.keys + activeOfferIdsRegistry).toSet()
        ids.forEach { id ->
            activeAlertJobs.remove(id)?.cancel()
            activeOfferIdsRegistry.remove(id)
            cancelSystemNotification(context, id)
        }
        lastActiveNotificationOfferId = null
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VeloRide Driver Dispatch Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "30-second zone ride-offer alerts with Accept button"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun postCountdownNotification(
        offerId: String,
        pickup: String,
        destination: String,
        fareDisplay: String,
        zoneHex: String,
        remainingSec: Int,
        remainingMs: Long,
        isInitialAlert: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        try {
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(RideOfferActionReceiver.EXTRA_OFFER_ID, offerId)
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                offerId.hashCode(),
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val acceptIntent = Intent(context, RideOfferActionReceiver::class.java).apply {
                action = RideOfferActionReceiver.ACTION_ACCEPT_OFFER
                putExtra(RideOfferActionReceiver.EXTRA_OFFER_ID, offerId)
            }
            val acceptPendingIntent = PendingIntent.getBroadcast(
                context,
                ("accept_$offerId").hashCode(),
                acceptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val rejectIntent = Intent(context, RideOfferActionReceiver::class.java).apply {
                action = RideOfferActionReceiver.ACTION_REJECT_OFFER
                putExtra(RideOfferActionReceiver.EXTRA_OFFER_ID, offerId)
            }
            val rejectPendingIntent = PendingIntent.getBroadcast(
                context,
                ("reject_$offerId").hashCode(),
                rejectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val zoneLabel = if (zoneHex.isNotBlank()) " • Zone $zoneHex" else ""
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentTitle("Ride Offer (${remainingSec}s) • $fareDisplay")
                .setContentText("$pickup → $destination$zoneLabel")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("$pickup → $destination$zoneLabel\nTap Accept within ${remainingSec}s before offer expires.")
                )
                .setProgress(30, remainingSec, false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(!isInitialAlert)
                .setTimeoutAfter(remainingMs)
                .setContentIntent(openAppPendingIntent)
                .addAction(R.mipmap.ic_launcher_round, "Accept (${remainingSec}s)", acceptPendingIntent)
                .addAction(R.mipmap.ic_launcher_round, "Decline", rejectPendingIntent)
                .build()

            NotificationManagerCompat.from(context).notify(offerId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission denied by OS
        } catch (_: Exception) {
            // Headless / Robolectric safeguard
        }
    }

    private fun triggerVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pattern = longArrayOf(0, 180, 90, 200)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(300)
            }
        } catch (_: Exception) {
            // Ignore in headless/test environments
        }
    }

    private suspend fun playDistinctiveChimeOnce() {
        try {
            val sampleRate = 22050
            // Distinctive 3-note ascending dispatch chime: E5 -> G#5 -> B5
            val notesHz = doubleArrayOf(659.25, 830.61, 987.77)
            val noteSamples = (sampleRate * 0.12).toInt()
            val totalSamples = noteSamples * notesHz.size
            val pcm = ShortArray(totalSamples)

            for (n in notesHz.indices) {
                val freq = notesHz[n]
                for (i in 0 until noteSamples) {
                    val env = (1.0 - i.toDouble() / noteSamples).coerceIn(0.0, 1.0)
                    val sample = sin(2.0 * PI * freq * i / sampleRate) * env * 0.55
                    pcm[n * noteSamples + i] = (sample * Short.MAX_VALUE).toInt().toShort()
                }
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.play()
            delay(420)
            track.stop()
            track.release()
        } catch (_: Exception) {
            // Safe fallback if audio device unavailable in emulator/test
        }
    }

    companion object {
        const val CHANNEL_ID = "veloride_driver_offers"
        const val DEFAULT_OFFER_WINDOW_MS = 30_000L

        private val activeOfferIdsRegistry = ConcurrentHashMap.newKeySet<String>()

        fun stopOfferAlertStatic(context: Context, offerId: String) {
            activeOfferIdsRegistry.remove(offerId)
            cancelSystemNotification(context, offerId)
        }

        private fun cancelSystemNotification(context: Context, offerId: String) {
            try {
                NotificationManagerCompat.from(context).cancel(offerId.hashCode())
            } catch (_: Exception) {
                // Headless safeguard
            }
        }
    }
}
