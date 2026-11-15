package com.example.core.engine

import com.example.domain.mapping.GeoPoint
import com.example.domain.model.Ride
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlin.math.cos
import kotlin.math.sin

data class RiderSyncSnapshot(
    val syncedCount: Int = 0,
    val targetCapacity: Int = RiderSyncEngine.DEFAULT_SYNC_TARGET,
    val chunksCompleted: Int = 0,
    val totalChunks: Int = 0,
    val conflictsSkipped: Int = 0,
    val lastSyncDurationMs: Long = 0L,
    val isSimulationBenchmark: Boolean = false,
    val lastSyncedAtMs: Long = 0L
)

/**
 * Chunked rider synchronization engine supporting 5,000+ concurrent riders.
 * Hydrates bounded LRU+TTL cache (`VeloInMemoryStore.syncedRiders`),
 * 64-bit hexagonal H3 spatial index (`H3SpatialIndex`), optimistic concurrency tracker
 * (`ConditionalUpdateEngine`), and async batch telemetry via `VeloMessageQueue`.
 */
class RiderSyncEngine(
    private val riderCache: VeloMemoryCache<String, SyncedRiderRecord> = VeloInMemoryStore.syncedRiders,
    val spatialIndex: H3SpatialIndex<SyncedRiderRecord> = H3SpatialIndex(H3Core.DEFAULT_RESOLUTION),
    val conditionalEngine: ConditionalUpdateEngine<String, SyncedRiderRecord> = ConditionalUpdateEngine()
) {
    companion object {
        const val DEFAULT_SYNC_TARGET = 5_000
        const val DEFAULT_CHUNK_SIZE = 500
    }

    private val _syncSnapshot = MutableStateFlow(RiderSyncSnapshot())
    val syncSnapshot: StateFlow<RiderSyncSnapshot> = _syncSnapshot.asStateFlow()

    fun ingestChunk(
        chunk: List<SyncedRiderRecord>,
        chunkIndex: Int,
        totalChunks: Int,
        isSimulation: Boolean = false
    ): Int {
        var accepted = 0
        var skipped = 0
        val cacheMap = HashMap<String, SyncedRiderRecord>(chunk.size)
        val spatialItems = ArrayList<Triple<String, GeoPoint, SyncedRiderRecord>>(chunk.size)

        for (raw in chunk) {
            val cell = if (raw.h3Cell != 0L) raw.h3Cell else H3Core.latLngToCell(raw.latitude, raw.longitude, spatialIndex.resolution)
            val record = if (raw.h3Cell == cell) raw else raw.copy(h3Cell = cell)

            when (conditionalEngine.upsertIfNewer(record.riderId, record.version, record.updatedAtMs, record)) {
                is ConditionalUpdateResult.Success -> {
                    cacheMap[record.riderId] = record
                    spatialItems.add(Triple(record.riderId, GeoPoint(record.latitude, record.longitude), record))
                    accepted++
                }
                is ConditionalUpdateResult.Conflict,
                is ConditionalUpdateResult.StaleTimestamp -> {
                    skipped++
                }
            }
        }

        if (cacheMap.isNotEmpty()) {
            riderCache.putAll(cacheMap)
            spatialIndex.batchUpsert(spatialItems)
        }

        val current = _syncSnapshot.value
        _syncSnapshot.value = current.copy(
            syncedCount = spatialIndex.size(),
            chunksCompleted = chunkIndex + 1,
            totalChunks = totalChunks,
            conflictsSkipped = current.conflictsSkipped + skipped,
            isSimulationBenchmark = isSimulation,
            lastSyncedAtMs = System.currentTimeMillis()
        )
        return accepted
    }

    fun syncRidersInChunks(
        riders: List<SyncedRiderRecord>,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        queue: VeloMessageQueue? = null,
        isSimulation: Boolean = false
    ): RiderSyncSnapshot {
        val startedMs = System.currentTimeMillis()
        val safeChunkSize = chunkSize.coerceIn(50, 1_000)
        val chunks = if (riders.isEmpty()) emptyList() else riders.chunked(safeChunkSize)
        val totalChunks = chunks.size
        var totalSkipped = 0

        for ((idx, chunk) in chunks.withIndex()) {
            val accepted = ingestChunk(chunk, idx, totalChunks, isSimulation)
            totalSkipped += (chunk.size - accepted)
            queue?.enqueue(
                topic = "rider.sync.batch",
                payloadJson = "{\"chunk\":${idx + 1},\"total\":$totalChunks,\"accepted\":$accepted}",
                priority = MessagePriority.NORMAL,
                idempotencyKey = "sync_batch_${startedMs}_${idx}"
            )
        }

        val elapsed = (System.currentTimeMillis() - startedMs).coerceAtLeast(1L)
        val snapshot = RiderSyncSnapshot(
            syncedCount = spatialIndex.size(),
            targetCapacity = maxOf(DEFAULT_SYNC_TARGET, riders.size),
            chunksCompleted = totalChunks,
            totalChunks = totalChunks,
            conflictsSkipped = totalSkipped,
            lastSyncDurationMs = elapsed,
            isSimulationBenchmark = isSimulation,
            lastSyncedAtMs = System.currentTimeMillis()
        )
        _syncSnapshot.value = snapshot
        return snapshot
    }

    /**
     * Hydrates active & recent Firestore rider trips into the local cache & H3 index.
     */
    suspend fun syncFromFirestoreRides(
        db: FirebaseFirestore,
        isAdmin: Boolean,
        userId: String,
        queue: VeloMessageQueue? = null
    ): RiderSyncSnapshot {
        return try {
            val query = if (isAdmin) {
                db.collection("rides").limit(500)
            } else {
                db.collection("rides").whereEqualTo("riderId", userId).limit(200)
            }
            val snap = query.get().await()
            val nowMs = System.currentTimeMillis()
            val records = snap.toObjects(Ride::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .map { ride ->
                    val cell = H3Core.latLngToCell(ride.pickupLat, ride.pickupLng, spatialIndex.resolution)
                    SyncedRiderRecord(
                        riderId = ride.riderId.ifBlank { ride.id },
                        displayName = ride.riderName.ifBlank { "Rider" },
                        latitude = ride.pickupLat,
                        longitude = ride.pickupLng,
                        h3Cell = cell,
                        city = ride.city,
                        status = ride.status,
                        version = (conditionalEngine.get(ride.riderId)?.version ?: 0L) + 1L,
                        updatedAtMs = nowMs
                    )
                }
            if (records.isNotEmpty()) {
                syncRidersInChunks(records, DEFAULT_CHUNK_SIZE, queue, isSimulation = false)
            } else {
                _syncSnapshot.value
            }
        } catch (_: Exception) {
            _syncSnapshot.value
        }
    }

    /**
     * Explicit Admin/Diagnostics scale benchmark syncing up to 5,000 riders across 10 chunks
     * of 500 to validate H3 indexing, cache capacity, conditional CAS, and queue throughput.
     */
    fun runScaleBenchmark5000(
        centerLat: Double,
        centerLng: Double,
        city: String,
        count: Int = DEFAULT_SYNC_TARGET,
        queue: VeloMessageQueue? = null
    ): RiderSyncSnapshot {
        val nowMs = System.currentTimeMillis()
        val batch = ArrayList<SyncedRiderRecord>(count)
        for (i in 0 until count) {
            // Golden-angle spiral distribution within ~8 km city metro radius
            val angle = i * 2.399963229728653
            val radialDeg = 0.002 + 0.072 * kotlin.math.sqrt(i.toDouble() / count.coerceAtLeast(1))
            val lat = (centerLat + radialDeg * sin(angle)).coerceIn(-89.9, 89.9)
            val lng = (centerLng + radialDeg * cos(angle)).coerceIn(-180.0, 180.0)
            val riderId = "sim_rider_${i + 1}"
            val nextVersion = (conditionalEngine.get(riderId)?.version ?: 0L) + 1L
            batch.add(
                SyncedRiderRecord(
                    riderId = riderId,
                    displayName = "Rider #${i + 1}",
                    latitude = lat,
                    longitude = lng,
                    h3Cell = H3Core.latLngToCell(lat, lng, spatialIndex.resolution),
                    city = city,
                    status = if (i % 5 == 0) "SEARCHING" else "ACTIVE",
                    version = nextVersion,
                    updatedAtMs = nowMs + i
                )
            )
        }
        return syncRidersInChunks(batch, DEFAULT_CHUNK_SIZE, queue, isSimulation = true)
    }

    fun clear() {
        riderCache.clear()
        spatialIndex.clear()
        conditionalEngine.clear()
        _syncSnapshot.value = RiderSyncSnapshot()
    }
}
