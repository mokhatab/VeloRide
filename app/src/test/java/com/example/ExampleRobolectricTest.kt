package com.example

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.core.alerts.OfferNotificationAction
import com.example.core.alerts.RideAlertPlayer
import com.example.core.alerts.RideOfferActionBus
import com.example.core.alerts.RideOfferActionReceiver
import com.example.core.push.TripStatusNotificationHelper
import com.example.domain.model.FcmPayloadParser
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("VeloRide", appName)
  }

  @Test
  fun `ride alert player starts 30s zone alert and stops cleanly on revocation`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val player = RideAlertPlayer(context)
    val offerId = "ride_123_drv_1"
    player.notifyNewRideOffer(
      offerId = offerId,
      pickup = "Tahrir Square",
      destination = "Cairo Airport",
      fareDisplay = "120.00 EGP",
      expiresAtMs = System.currentTimeMillis() + 30_000L,
      zoneHex = "883e612345fffff"
    )
    assertTrue(player.isAlertActive(offerId))
    assertEquals(offerId, player.lastActiveNotificationOfferId)

    player.stopOfferAlert(offerId)
    assertFalse(player.isAlertActive(offerId))
  }

  @Test
  fun `notification accept action receiver emits Accept event to bus`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val receiver = RideOfferActionReceiver()
    val deferredAction = async {
      withTimeout(2000L) {
        RideOfferActionBus.actions.first()
      }
    }
    kotlinx.coroutines.yield()

    val intent = Intent(context, RideOfferActionReceiver::class.java).apply {
      action = RideOfferActionReceiver.ACTION_ACCEPT_OFFER
      putExtra(RideOfferActionReceiver.EXTRA_OFFER_ID, "offer_accept_77")
    }
    receiver.onReceive(context, intent)

    val event = deferredAction.await()
    assertTrue(event is OfferNotificationAction.Accept)
    assertEquals("offer_accept_77", event.offerId)
  }

  @Test
  fun `trip status notification helper creates channel and deduplicates by idempotency key`() {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    TripStatusNotificationHelper.resetDeduplicationForTest()

    val payload = FcmPayloadParser.parse(
      data = mapOf(
        "eventType" to "TRIP_COMPLETED",
        "rideId" to "ride_robo_99",
        "idempotencyKey" to "trip_done_robo_99",
        "title" to "Trip Completed",
        "body" to "Fare 140.00 EGP"
      )
    )

    // Denied on Android 13+ without runtime permission
    assertFalse(TripStatusNotificationHelper.showTripStatusNotification(app, payload, dedupeKey = ""))

    // Grant POST_NOTIFICATIONS permission and verify delivery + deduplication
    org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    val firstPosted = TripStatusNotificationHelper.showTripStatusNotification(app, payload)
    val secondPosted = TripStatusNotificationHelper.showTripStatusNotification(app, payload)

    assertTrue(firstPosted)
    assertFalse(secondPosted)

    val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = manager.getNotificationChannel(TripStatusNotificationHelper.CHANNEL_TRIP_UPDATES)
    assertEquals("VeloRide Trip Status Updates", channel?.name?.toString())
  }
}
