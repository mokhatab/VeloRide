package com.example.core.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class VersionedRecord<T>(
    val key: String,
    val value: T,
    val version: Long,
    val updatedAtMs: Long
)

sealed interface ConditionalUpdateResult<out T> {
    data class Success<T>(val record: VersionedRecord<T>) : ConditionalUpdateResult<T>
    data class Conflict<T>(
        val key: String,
        val expectedVersion: Long,
        val actualVersion: Long,
        val current: VersionedRecord<T>?
    ) : ConditionalUpdateResult<T>
    data class StaleTimestamp<T>(
        val key: String,
        val incomingTimestampMs: Long,
        val latestTimestampMs: Long
    ) : ConditionalUpdateResult<T>
}

data class ConditionalEngineStats(
    val trackedKeys: Int,
    val successfulUpdates: Long,
    val versionConflicts: Long,
    val staleTimestampRejections: Long
)

/**
 * Optimistic Concurrency Control (Compare-And-Set) engine to prevent race conditions
 * and lost updates under high concurrency. Supports version-guarded conditional writes
 * and monotonic timestamp guards.
 */
class ConditionalUpdateEngine<K : Any, T : Any> {
    private val records = ConcurrentHashMap<K, VersionedRecord<T>>()
    private val keyLocks = ConcurrentHashMap<K, Any>()
    private val successCount = AtomicLong(0L)
    private val conflictCount = AtomicLong(0L)
    private val staleTsCount = AtomicLong(0L)

    private fun lockFor(key: K): Any = keyLocks.computeIfAbsent(key) { Any() }

    fun get(key: K): VersionedRecord<T>? = records[key]

    /**
     * Inserts initial record if absent (expectedVersion = 0), or conditionally updates
     * only when `current.version == expectedVersion`.
     */
    fun compareAndSet(
        key: K,
        expectedVersion: Long,
        incomingTimestampMs: Long = System.currentTimeMillis(),
        transform: (T?) -> T
    ): ConditionalUpdateResult<T> {
        synchronized(lockFor(key)) {
            val current = records[key]
            val actualVersion = current?.version ?: 0L
            if (actualVersion != expectedVersion) {
                conflictCount.incrementAndGet()
                return ConditionalUpdateResult.Conflict(
                    key = key.toString(),
                    expectedVersion = expectedVersion,
                    actualVersion = actualVersion,
                    current = current
                )
            }
            if (current != null && incomingTimestampMs < current.updatedAtMs) {
                staleTsCount.incrementAndGet()
                return ConditionalUpdateResult.StaleTimestamp(
                    key = key.toString(),
                    incomingTimestampMs = incomingTimestampMs,
                    latestTimestampMs = current.updatedAtMs
                )
            }
            val nextValue = transform(current?.value)
            val nextRecord = VersionedRecord(
                key = key.toString(),
                value = nextValue,
                version = actualVersion + 1L,
                updatedAtMs = incomingTimestampMs
            )
            records[key] = nextRecord
            successCount.incrementAndGet()
            return ConditionalUpdateResult.Success(nextRecord)
        }
    }

    /**
     * Upserts monotonically using caller version or timestamp without throwing away newer writes.
     */
    fun upsertIfNewer(
        key: K,
        incomingVersion: Long,
        incomingTimestampMs: Long,
        value: T
    ): ConditionalUpdateResult<T> {
        synchronized(lockFor(key)) {
            val current = records[key]
            if (current != null) {
                if (incomingTimestampMs < current.updatedAtMs) {
                    staleTsCount.incrementAndGet()
                    return ConditionalUpdateResult.StaleTimestamp(
                        key = key.toString(),
                        incomingTimestampMs = incomingTimestampMs,
                        latestTimestampMs = current.updatedAtMs
                    )
                }
                if (incomingVersion > 0L && incomingVersion <= current.version && incomingTimestampMs == current.updatedAtMs) {
                    conflictCount.incrementAndGet()
                    return ConditionalUpdateResult.Conflict(
                        key = key.toString(),
                        expectedVersion = incomingVersion,
                        actualVersion = current.version,
                        current = current
                    )
                }
            }
            val nextVer = maxOf(incomingVersion, (current?.version ?: 0L) + 1L)
            val nextRecord = VersionedRecord(
                key = key.toString(),
                value = value,
                version = nextVer,
                updatedAtMs = incomingTimestampMs
            )
            records[key] = nextRecord
            successCount.incrementAndGet()
            return ConditionalUpdateResult.Success(nextRecord)
        }
    }

    fun clear() {
        records.clear()
        keyLocks.clear()
    }

    fun stats(): ConditionalEngineStats = ConditionalEngineStats(
        trackedKeys = records.size,
        successfulUpdates = successCount.get(),
        versionConflicts = conflictCount.get(),
        staleTimestampRejections = staleTsCount.get()
    )
}
