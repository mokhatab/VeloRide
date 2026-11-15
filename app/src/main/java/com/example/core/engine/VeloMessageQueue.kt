package com.example.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class MessagePriority(val rank: Int) {
    URGENT(0),
    NORMAL(1),
    LOW(2)
}

data class QueuedMessage(
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val idempotencyKey: String,
    val priority: MessagePriority = MessagePriority.NORMAL,
    val payloadJson: String,
    val attempt: Int = 0,
    val maxAttempts: Int = 3,
    val nextAttemptAtMs: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis()
) : Comparable<QueuedMessage> {
    override fun compareTo(other: QueuedMessage): Int {
        val priCmp = priority.rank.compareTo(other.priority.rank)
        if (priCmp != 0) return priCmp
        val timeCmp = nextAttemptAtMs.compareTo(other.nextAttemptAtMs)
        if (timeCmp != 0) return timeCmp
        return createdAtMs.compareTo(other.createdAtMs)
    }
}

data class MessageQueueStats(
    val pendingCount: Int = 0,
    val processedCount: Long = 0L,
    val retriedCount: Long = 0L,
    val deduplicatedCount: Long = 0L,
    val deadLetterCount: Int = 0,
    val lastTopic: String = ""
)

sealed interface EnqueueResult {
    data class Enqueued(val messageId: String) : EnqueueResult
    data class Deduplicated(val idempotencyKey: String) : EnqueueResult
    data class BackpressureRejected(val maxCapacity: Int) : EnqueueResult
}

/**
 * Priority-ordered, deduplicated asynchronous Message Queue with exponential-backoff
 * retries and Dead-Letter Queue (DLQ) capture for ride telemetry, sync batches, and dispatch events.
 */
class VeloMessageQueue(
    private val maxCapacity: Int = 10_000,
    private val baseBackoffMs: Long = 200L,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val queue = PriorityQueue<QueuedMessage>()
    private val seenKeys = ConcurrentHashMap.newKeySet<String>()
    private val deadLetter = ArrayList<Pair<QueuedMessage, String>>()

    private val processedCounter = AtomicLong(0L)
    private val retriedCounter = AtomicLong(0L)
    private val deduplicatedCounter = AtomicLong(0L)
    @Volatile
    private var lastProcessedTopic: String = ""

    private val _stats = MutableStateFlow(MessageQueueStats())
    val stats: StateFlow<MessageQueueStats> = _stats.asStateFlow()

    private var workerJob: Job? = null

    fun enqueue(
        topic: String,
        payloadJson: String,
        priority: MessagePriority = MessagePriority.NORMAL,
        idempotencyKey: String = "${topic}_${UUID.randomUUID()}",
        maxAttempts: Int = 3
    ): EnqueueResult {
        synchronized(lock) {
            if (!seenKeys.add(idempotencyKey)) {
                deduplicatedCounter.incrementAndGet()
                publishStats()
                return EnqueueResult.Deduplicated(idempotencyKey)
            }
            if (queue.size >= maxCapacity) {
                seenKeys.remove(idempotencyKey)
                return EnqueueResult.BackpressureRejected(maxCapacity)
            }
            val msg = QueuedMessage(
                topic = topic,
                idempotencyKey = idempotencyKey,
                priority = priority,
                payloadJson = payloadJson,
                maxAttempts = maxAttempts.coerceAtLeast(1),
                nextAttemptAtMs = clockMs()
            )
            queue.offer(msg)
            publishStats()
            return EnqueueResult.Enqueued(msg.id)
        }
    }

    suspend fun drainOnce(handler: suspend (QueuedMessage) -> Result<Unit>): Int {
        var processedThisPass = 0
        while (true) {
            val nextMsg = popReadyMessage() ?: break
            val outcome = runCatching { handler(nextMsg) }.getOrElse { Result.failure(it) }
            if (outcome.isSuccess) {
                processedCounter.incrementAndGet()
                lastProcessedTopic = nextMsg.topic
                processedThisPass++
                publishStats()
            } else {
                val errMsg = outcome.exceptionOrNull()?.message ?: "Delivery error"
                handleFailure(nextMsg, errMsg)
            }
        }
        return processedThisPass
    }

    fun startWorker(
        scope: CoroutineScope,
        pollIntervalMs: Long = 150L,
        handler: suspend (QueuedMessage) -> Result<Unit>
    ) {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            while (isActive) {
                drainOnce(handler)
                delay(pollIntervalMs)
            }
        }
    }

    fun stopWorker() {
        workerJob?.cancel()
        workerJob = null
    }

    fun deadLetterItems(): List<Pair<QueuedMessage, String>> =
        synchronized(lock) { deadLetter.toList() }

    fun snapshotStats(): MessageQueueStats = synchronized(lock) {
        MessageQueueStats(
            pendingCount = queue.size,
            processedCount = processedCounter.get(),
            retriedCount = retriedCounter.get(),
            deduplicatedCount = deduplicatedCounter.get(),
            deadLetterCount = deadLetter.size,
            lastTopic = lastProcessedTopic
        )
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            seenKeys.clear()
            deadLetter.clear()
        }
        processedCounter.set(0L)
        retriedCounter.set(0L)
        deduplicatedCounter.set(0L)
        lastProcessedTopic = ""
        publishStats()
    }

    private fun popReadyMessage(): QueuedMessage? {
        val now = clockMs()
        synchronized(lock) {
            val head = queue.peek() ?: return null
            if (head.nextAttemptAtMs > now) return null
            return queue.poll()
        }
    }

    private fun handleFailure(msg: QueuedMessage, reason: String) {
        synchronized(lock) {
            val nextAttempt = msg.attempt + 1
            if (nextAttempt >= msg.maxAttempts) {
                deadLetter.add(msg.copy(attempt = nextAttempt) to reason)
            } else {
                retriedCounter.incrementAndGet()
                val delayMs = baseBackoffMs * (1L shl nextAttempt.coerceAtMost(6))
                queue.offer(
                    msg.copy(
                        attempt = nextAttempt,
                        nextAttemptAtMs = clockMs() + delayMs
                    )
                )
            }
            publishStats()
        }
    }

    private fun publishStats() {
        _stats.value = MessageQueueStats(
            pendingCount = queue.size,
            processedCount = processedCounter.get(),
            retriedCount = retriedCounter.get(),
            deduplicatedCount = deduplicatedCounter.get(),
            deadLetterCount = deadLetter.size,
            lastTopic = lastProcessedTopic
        )
    }
}
