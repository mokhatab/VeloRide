package com.example.domain

import com.example.core.engine.ConditionalUpdateEngine
import com.example.core.engine.ConditionalUpdateResult
import com.example.core.engine.EnqueueResult
import com.example.core.engine.H3Core
import com.example.core.engine.H3SpatialIndex
import com.example.core.engine.MessagePriority
import com.example.core.engine.RideEvent
import com.example.core.engine.RideStateMachine
import com.example.core.engine.RiderSyncEngine
import com.example.core.engine.SyncedRiderRecord
import com.example.core.engine.TransitionResult
import com.example.core.engine.VeloInMemoryStore
import com.example.core.engine.VeloMemoryCache
import com.example.core.engine.VeloMessageQueue
import com.example.core.network.LocationUpdatePacket
import com.example.core.network.LocationWebSocketClient
import com.example.core.network.WebSocketState
import com.example.domain.mapping.GeoPoint
import com.example.domain.model.RideStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ScaleAndEngineUnitTest {

    @Test
    fun sync5000Riders_hydratesCacheH3IndexAndQueueIn500Chunks() = runBlocking {
        VeloInMemoryStore.syncedRiders.clear()
        val engine = RiderSyncEngine()
        val queue = VeloMessageQueue()

        val snap = engine.runScaleBenchmark5000(
            centerLat = 30.0444,
            centerLng = 31.2357,
            city = "Cairo",
            count = 5_000,
            queue = queue
        )

        assertEquals(5_000, snap.syncedCount)
        assertEquals(10, snap.chunksCompleted)
        assertEquals(10, snap.totalChunks)
        assertEquals(0, snap.conflictsSkipped)
        assertEquals(5_000, VeloInMemoryStore.syncedRiders.size())
        assertEquals(5_000, engine.spatialIndex.size())
        assertEquals(10, queue.snapshotStats().pendingCount)

        // Verify H3 radius search over all 5,000 synced riders finds nearby matches accurately
        val center = GeoPoint(30.0444, 31.2357)
        val h3Results = engine.spatialIndex.searchNearby(center, radiusKm = 3.0, limit = 5_000)
        val bruteForceCount = VeloInMemoryStore.syncedRiders.values().count { r ->
            H3Core.haversineKm(center.latitude, center.longitude, r.latitude, r.longitude) <= 3.0
        }
        assertTrue(h3Results.matches.isNotEmpty())
        assertEquals(bruteForceCount, h3Results.matches.size)
        engine.clear()
    }

    @Test
    fun locationWebSocketClient_connectsStreamsAndDropsStalePackets() = runBlocking {
        val unconfiguredClient = LocationWebSocketClient()
        unconfiguredClient.connect("")
        assertTrue(unconfiguredClient.state.value is WebSocketState.Unconfigured)

        val server = MockWebServer()
        val serverReceivedText = AtomicReference<String?>()
        val serverReceivedLatch = CountDownLatch(1)
        val serverOpenedLatch = CountDownLatch(1)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverOpenedLatch.countDown()
                    // Send a fresh inbound location packet followed by an out-of-order stale packet
                    webSocket.send(
                        """{"entityId":"drv_1","role":"DRIVER","rideId":"ride_9","latitude":30.05,"longitude":31.24,"heading":90.0,"speedKmh":42.0,"h3CellHex":"","sequence":1,"timestampMs":2000}"""
                    )
                    webSocket.send(
                        """{"entityId":"drv_1","role":"DRIVER","rideId":"ride_9","latitude":30.01,"longitude":31.20,"heading":0.0,"speedKmh":10.0,"h3CellHex":"","sequence":2,"timestampMs":1500}"""
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    serverReceivedText.set(text)
                    serverReceivedLatch.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            })
        )
        server.start()

        try {
            val wsClient = LocationWebSocketClient()
            val inboundList = mutableListOf<LocationUpdatePacket>()
            val collector = launch {
                wsClient.inboundLocations.collect { inboundList.add(it) }
            }

            val wsUrl = server.url("/ws/location").toString().replaceFirst("http", "ws")
            wsClient.connect(wsUrl)

            withTimeout(5_000L) {
                wsClient.state.first { it is WebSocketState.Connected }
            }
            assertTrue(serverOpenedLatch.await(3, TimeUnit.SECONDS))

            val sentOk = wsClient.sendLocationUpdate(
                LocationUpdatePacket(
                    entityId = "drv_2",
                    role = "DRIVER",
                    rideId = "ride_1",
                    latitude = 30.0444,
                    longitude = 31.2357,
                    timestampMs = 5_000L
                )
            )
            assertTrue(sentOk)
            assertTrue(serverReceivedLatch.await(3, TimeUnit.SECONDS))
            val sentPayload = serverReceivedText.get().orEmpty()
            assertTrue(sentPayload.contains("drv_2"))
            assertTrue(sentPayload.contains("h3CellHex"))

            delay(250L)
            assertEquals(1, inboundList.size)
            assertEquals(2000L, inboundList.first().timestampMs)
            assertTrue(wsClient.telemetry.value.stalePacketsDropped >= 1L)

            collector.cancel()
            wsClient.disconnect()
            delay(150L)
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun inMemoryCache_enforcesLruEvictionAndTtlExpiration() {
        var fakeClockMs = 1_000L
        val cache = VeloMemoryCache<String, String>(
            name = "test_cache",
            maxEntries = 3,
            defaultTtlMs = 500L,
            clockMs = { fakeClockMs }
        )

        cache.put("a", "alpha")
        cache.put("b", "bravo")
        cache.put("c", "charlie")
        // Touch "a" so "b" becomes least-recently-used
        assertEquals("alpha", cache.get("a"))
        cache.put("d", "delta")

        assertEquals(3, cache.size())
        assertEquals(null, cache.get("b"))
        assertEquals("alpha", cache.get("a"))
        assertEquals(1L, cache.stats().evictions)

        // Advance clock past TTL (1000 + 500 = 1500)
        fakeClockMs = 2_000L
        assertEquals(null, cache.get("a"))
        assertTrue(cache.stats().expirations >= 1L)
    }

    @Test
    fun h3SpatialIndex_encodesCellsRingsAndRebucketsMovement() {
        val origin = GeoPoint(30.0444, 31.2357)
        val cell = H3Core.latLngToCell(origin.latitude, origin.longitude, H3Core.DEFAULT_RESOLUTION)
        val roundTrip = H3Core.cellToLatLng(cell)
        assertTrue(H3Core.haversineKm(origin.latitude, origin.longitude, roundTrip.latitude, roundTrip.longitude) < 1.0)

        assertEquals(7, H3Core.gridDisk(cell, 1).size)
        assertEquals(19, H3Core.gridDisk(cell, 2).size)
        assertEquals(4, H3Core.getResolution(H3Core.cellToParent(cell, 4)))

        val index = H3SpatialIndex<String>()
        index.upsert("drv_A", 30.0444, 31.2357, "Driver A")
        assertEquals(1, index.size())
        // Move driver far away (> 10 km) and verify old hex bucket is cleaned and distance reflects new position
        index.upsert("drv_A", 30.2000, 31.5000, "Driver A Moved")
        assertEquals(1, index.size())
        val nearOldOrigin = index.searchNearby(origin, radiusKm = 2.0)
        assertEquals(0, nearOldOrigin.matches.size)
        val nearNewSpot = index.searchNearby(GeoPoint(30.2000, 31.5000), radiusKm = 2.0)
        assertEquals(1, nearNewSpot.matches.size)
    }

    @Test
    fun conditionalUpdateEngine_blocksConcurrentRaceConditionsAndStaleWrites() = runBlocking {
        val engine = ConditionalUpdateEngine<String, Int>()
        val initRes = engine.compareAndSet("ride_lock", expectedVersion = 0L, incomingTimestampMs = 100L) { 0 }
        assertTrue(initRes is ConditionalUpdateResult.Success)

        val outcomes = (1..100).map { writerId ->
            async(Dispatchers.Default) {
                engine.compareAndSet("ride_lock", expectedVersion = 1L, incomingTimestampMs = 200L) {
                    writerId
                }
            }
        }.awaitAll()

        assertEquals(1, outcomes.count { it is ConditionalUpdateResult.Success })
        assertEquals(99, outcomes.count { it is ConditionalUpdateResult.Conflict })
        assertEquals(99L, engine.stats().versionConflicts)

        val stale = engine.compareAndSet("ride_lock", expectedVersion = 2L, incomingTimestampMs = 150L) { 999 }
        assertTrue(stale is ConditionalUpdateResult.StaleTimestamp)
    }

    @Test
    fun rideStateMachine_enforcesLifecycleAndRejectsIllegalTransitions() {
        val rId = "ride_fsm_1"
        assertTrue(RideStateMachine.transition(rId, RideStatus.REQUESTED, "", RideEvent.StartSearch) is TransitionResult.Transitioned)
        assertTrue(RideStateMachine.transition(rId, RideStatus.SEARCHING_FOR_DRIVER, "", RideEvent.DispatchOffer(1)) is TransitionResult.Transitioned)
        assertTrue(RideStateMachine.transition(rId, RideStatus.OFFERED, "", RideEvent.DriverAccept("drv_1")) is TransitionResult.Transitioned)

        // Second driver trying to accept an already-assigned ride must be rejected
        val doubleAccept = RideStateMachine.transition(rId, RideStatus.ACCEPTED, "drv_1", RideEvent.DriverAccept("drv_2"))
        assertTrue(doubleAccept is TransitionResult.Rejected)

        // Skipping ARRIVED / IN_PROGRESS directly from ACCEPTED -> COMPLETED must be rejected
        val skipTrip = RideStateMachine.transition(rId, RideStatus.ACCEPTED, "drv_1", RideEvent.CompleteTrip)
        assertTrue(skipTrip is TransitionResult.Rejected)

        // Completing legally and verifying COMPLETED is terminal
        assertTrue(RideStateMachine.transition(rId, RideStatus.IN_PROGRESS, "drv_1", RideEvent.CompleteTrip) is TransitionResult.Transitioned)
        val afterCompleted = RideStateMachine.transition(rId, RideStatus.COMPLETED, "drv_1", RideEvent.Cancel("RIDER", "too late"))
        assertTrue(afterCompleted is TransitionResult.Rejected)
    }

    @Test
    fun messageQueue_ordersByPriorityDeduplicatesAndRoutesFailedToDlq() = runBlocking {
        var nowMs = 10_000L
        val queue = VeloMessageQueue(baseBackoffMs = 50L, clockMs = { nowMs })

        val e1 = queue.enqueue("sync", "low", MessagePriority.LOW, idempotencyKey = "k_low")
        val e2 = queue.enqueue("gps", "urgent", MessagePriority.URGENT, idempotencyKey = "k_urgent")
        val dup = queue.enqueue("gps", "urgent_dup", MessagePriority.URGENT, idempotencyKey = "k_urgent")
        assertTrue(e1 is EnqueueResult.Enqueued)
        assertTrue(e2 is EnqueueResult.Enqueued)
        assertTrue(dup is EnqueueResult.Deduplicated)

        val order = mutableListOf<String>()
        queue.drainOnce { msg ->
            order.add(msg.payloadJson)
            Result.success(Unit)
        }
        assertEquals(listOf("urgent", "low"), order)
        assertEquals(1L, queue.snapshotStats().deduplicatedCount)

        // Test exponential-backoff retry -> Dead-Letter Queue after 2 maxAttempts
        queue.enqueue("failing", "bad_payload", MessagePriority.NORMAL, idempotencyKey = "k_fail", maxAttempts = 2)
        queue.drainOnce { Result.failure(IllegalStateException("Network 503")) }
        assertEquals(1L, queue.snapshotStats().retriedCount)
        assertEquals(0, queue.deadLetterItems().size)

        nowMs += 500L
        queue.drainOnce { Result.failure(IllegalStateException("Network 503")) }
        assertEquals(1, queue.deadLetterItems().size)
        assertEquals("k_fail", queue.deadLetterItems().first().first.idempotencyKey)
    }
}
