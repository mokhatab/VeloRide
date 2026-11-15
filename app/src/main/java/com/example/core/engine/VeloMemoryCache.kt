package com.example.core.engine

import com.example.domain.mapping.RouteResult
import com.example.domain.model.DriverAvailability
import com.example.domain.model.FareQuote
import com.example.domain.model.Ride
import java.util.concurrent.atomic.AtomicLong

data class CacheEntry<V>(
    val value: V,
    val version: Long,
    val createdAtMs: Long,
    val expiresAtMs: Long
)

data class CacheStats(
    val name: String,
    val size: Int,
    val maxEntries: Int,
    val hits: Long,
    val misses: Long,
    val evictions: Long,
    val expirations: Long
) {
    val hitRatePercent: Double
        get() {
            val total = hits + misses
            return if (total == 0L) 0.0 else (hits * 100.0) / total.toDouble()
        }
}

/**
 * Thread-safe bounded LRU + TTL in-memory cache with O(1) lookup and hit/miss telemetry.
 */
class VeloMemoryCache<K : Any, V : Any>(
    private val name: String,
    val maxEntries: Int = 6_000,
    private val defaultTtlMs: Long = 300_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val map = object : LinkedHashMap<K, CacheEntry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, CacheEntry<V>>?): Boolean {
            val shouldRemove = size > maxEntries
            if (shouldRemove) {
                evictions.incrementAndGet()
            }
            return shouldRemove
        }
    }

    private val hits = AtomicLong(0L)
    private val misses = AtomicLong(0L)
    private val evictions = AtomicLong(0L)
    private val expirations = AtomicLong(0L)

    fun get(key: K): V? {
        val now = clockMs()
        synchronized(lock) {
            val entry = map[key]
            if (entry == null) {
                misses.incrementAndGet()
                return null
            }
            if (now > entry.expiresAtMs) {
                map.remove(key)
                expirations.incrementAndGet()
                misses.incrementAndGet()
                return null
            }
            hits.incrementAndGet()
            return entry.value
        }
    }

    fun getEntry(key: K): CacheEntry<V>? {
        val now = clockMs()
        synchronized(lock) {
            val entry = map[key] ?: return null
            if (now > entry.expiresAtMs) {
                map.remove(key)
                expirations.incrementAndGet()
                return null
            }
            return entry
        }
    }

    fun put(key: K, value: V, ttlMs: Long = defaultTtlMs, version: Long = 1L): CacheEntry<V> {
        val now = clockMs()
        val entry = CacheEntry(
            value = value,
            version = version,
            createdAtMs = now,
            expiresAtMs = now + ttlMs.coerceAtLeast(1L)
        )
        synchronized(lock) {
            map[key] = entry
        }
        return entry
    }

    fun putAll(entries: Map<K, V>, ttlMs: Long = defaultTtlMs) {
        val now = clockMs()
        val expires = now + ttlMs.coerceAtLeast(1L)
        synchronized(lock) {
            for ((k, v) in entries) {
                val prevVer = map[k]?.version ?: 0L
                map[k] = CacheEntry(
                    value = v,
                    version = prevVer + 1L,
                    createdAtMs = now,
                    expiresAtMs = expires
                )
            }
        }
    }

    fun remove(key: K): V? = synchronized(lock) { map.remove(key)?.value }

    fun values(): List<V> {
        val now = clockMs()
        synchronized(lock) {
            val iterator = map.entries.iterator()
            val list = ArrayList<V>(map.size)
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (now > item.value.expiresAtMs) {
                    iterator.remove()
                    expirations.incrementAndGet()
                } else {
                    list.add(item.value.value)
                }
            }
            return list
        }
    }

    fun size(): Int = synchronized(lock) { map.size }

    fun clear() {
        synchronized(lock) {
            map.clear()
        }
    }

    fun stats(): CacheStats = synchronized(lock) {
        CacheStats(
            name = name,
            size = map.size,
            maxEntries = maxEntries,
            hits = hits.get(),
            misses = misses.get(),
            evictions = evictions.get(),
            expirations = expirations.get()
        )
    }
}

data class SyncedRiderRecord(
    val riderId: String,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val h3Cell: Long,
    val city: String,
    val status: String,
    val version: Long,
    val updatedAtMs: Long
)

/**
 * Shared in-memory store holding bounded LRU+TTL caches for 5,000+ riders,
 * online drivers, active rides, routes, and fare quotes.
 */
object VeloInMemoryStore {
    val syncedRiders = VeloMemoryCache<String, SyncedRiderRecord>(
        name = "synced_riders",
        maxEntries = 6_000,
        defaultTtlMs = 15 * 60_000L
    )
    val driverLocations = VeloMemoryCache<String, DriverAvailability>(
        name = "driver_locations",
        maxEntries = 5_000,
        defaultTtlMs = 5 * 60_000L
    )
    val activeRides = VeloMemoryCache<String, Ride>(
        name = "active_rides",
        maxEntries = 2_000,
        defaultTtlMs = 30 * 60_000L
    )
    val routeCache = VeloMemoryCache<String, RouteResult>(
        name = "routes",
        maxEntries = 1_000,
        defaultTtlMs = 10 * 60_000L
    )
    val quoteCache = VeloMemoryCache<String, FareQuote>(
        name = "fare_quotes",
        maxEntries = 500,
        defaultTtlMs = 5 * 60_000L
    )

    fun aggregateStats(): List<CacheStats> = listOf(
        syncedRiders.stats(),
        driverLocations.stats(),
        activeRides.stats(),
        routeCache.stats(),
        quoteCache.stats()
    )
}
