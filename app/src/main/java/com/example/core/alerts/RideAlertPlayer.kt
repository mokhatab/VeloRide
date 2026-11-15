package com.example.core.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a distinctive multi-tone ride-request chime and triggers high-priority
 * notification + vibration without overlapping alerts for the same offerId.
 */
class RideAlertPlayer(private val context: Context) {

    private val playedOfferIds = mutableSetOf<String>()
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        ensureNotificationChannel()
    }

    @Synchronized
    fun notifyNewRideOffer(
        offerId: String,
        pickup: String,
        destination: String,
        fareDisplay: String
    ) {
        if (!playedOfferIds.add(offerId)) {
            return // Prevent duplicate/overlapping alert for the same offer
        }
        playDistinctiveChime()
        triggerVibration()
        postHighPriorityNotification(offerId, pickup, destination, fareDisplay)
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
                description = "High-priority 30-second ride offer alerts for online drivers"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun postHighPriorityNotification(
        offerId: String,
        pickup: String,
        destination: String,
        fareDisplay: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentTitle("New Ride Offer • $fareDisplay")
                .setContentText("$pickup → $destination (30s to accept)")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(offerId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission denied by OS
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
                val pattern = longArrayOf(0, 220, 110, 220, 110, 320)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(400)
            }
        } catch (_: Exception) {
            // Ignore in headless/test environments
        }
    }

    private fun playDistinctiveChime() {
        scope.launch {
            try {
                val sampleRate = 22050
                // Distinctive 3-note ascending dispatch chime: E5 -> G#5 -> B5
                val notesHz = doubleArrayOf(659.25, 830.61, 987.77)
                val noteSamples = (sampleRate * 0.14).toInt()
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
                kotlinx.coroutines.delay(550)
                track.stop()
                track.release()
            } catch (_: Exception) {
                // Safe fallback if audio device unavailable in emulator/test
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "veloride_driver_offers"
    }
}
