package com.example.domain

import com.example.core.push.FcmPushCoordinator
import com.example.data.firestore.FcmTokenRepository
import com.example.domain.model.FcmPayloadParser
import com.example.domain.model.FcmPushEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmPushUnitTest {

    @Test
    fun `payload parser maps ride offer and target route home`() {
        val payload = FcmPayloadParser.parse(
            data = mapOf(
                "eventType" to "DRIVER_RIDE_OFFER",
                "rideId" to "ride_101",
                "offerId" to "ride_101_drv_1",
                "pickupAddress" to "Tahrir Square",
                "destinationAddress" to "Cairo Airport",
                "fareDisplay" to "150 EGP",
                "zoneHex" to "883e612345fffff"
            )
        )
        assertEquals(FcmPushEventType.DRIVER_RIDE_OFFER, payload.eventType)
        assertEquals("home", payload.targetRoute)
        assertEquals("ride_101_drv_1", payload.offerId)
        assertEquals("Tahrir Square", payload.pickupAddress)
        assertEquals("Cairo Airport", payload.destinationAddress)
        assertEquals("Tahrir Square → Cairo Airport", payload.body)
    }

    @Test
    fun `payload parser routes terminal trip updates to trip_history`() {
        listOf("TRIP_COMPLETED", "RIDE_CANCELLED", "NO_DRIVER_AVAILABLE").forEach { rawType ->
            val payload = FcmPayloadParser.parse(
                data = mapOf(
                    "eventType" to rawType,
                    "rideId" to "ride_202"
                )
            )
            assertEquals("trip_history", payload.targetRoute)
            assertTrue(payload.idempotencyKey.contains("ride_202"))
        }
    }

    @Test
    fun `payload parser routes active trip updates to activity and falls back cleanly`() {
        listOf(
            "DRIVER_ACCEPTED" to FcmPushEventType.DRIVER_ACCEPTED,
            "DRIVER_ARRIVING" to FcmPushEventType.DRIVER_ARRIVING,
            "DRIVER_ARRIVED" to FcmPushEventType.DRIVER_ARRIVED,
            "TRIP_STARTED" to FcmPushEventType.TRIP_STARTED
        ).forEach { (raw, expectedType) ->
            val payload = FcmPayloadParser.parse(
                data = mapOf("status" to raw, "rideId" to "ride_303"),
                fallbackTitle = null,
                fallbackBody = null
            )
            assertEquals(expectedType, payload.eventType)
            assertEquals("activity", payload.targetRoute)
            assertTrue(payload.title.isNotBlank())
            assertTrue(payload.body.contains("ride_303"))
        }
    }

    @Test
    fun `deterministicTokenId produces stable prefixed hash ids`() {
        val id1 = FcmTokenRepository.deterministicTokenId("fcm_token_alpha_12345")
        val id2 = FcmTokenRepository.deterministicTokenId("fcm_token_alpha_12345")
        val idOther = FcmTokenRepository.deterministicTokenId("fcm_token_beta_67890")

        assertEquals(id1, id2)
        assertNotEquals(id1, idOther)
        assertTrue(id1.startsWith("tok_"))
        assertEquals(28, id1.length)
    }

    @Test
    fun `coordinator topic helpers sanitize uid and country code`() {
        assertEquals(
            "veloride_user_usr_123_abc",
            FcmPushCoordinator.userTopicName("usr:123/abc")
        )
        assertEquals(
            "veloride_drivers_eg",
            FcmPushCoordinator.driverZoneTopicName("EG")
        )
        assertEquals(
            "veloride_drivers_de",
            FcmPushCoordinator.driverZoneTopicName(" de ")
        )
    }
}
