package com.example.core.network

import com.example.BuildConfig
import com.example.core.engine.H3Core
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class LocationUpdatePacket(
    val entityId: String,
    val role: String,
    val rideId: String = "",
    val latitude: Double,
    val longitude: Double,
    val heading: Double = 0.0,
    val speedKmh: Double = 0.0,
    val h3CellHex: String = "",
    val sequence: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
)

sealed interface WebSocketState {
    data object Unconfigured : WebSocketState
    data object Disconnected : WebSocketState
    data object Connecting : WebSocketState
    data class Connected(val endpoint: String, val connectedAtMs: Long) : WebSocketState
    data class Reconnecting(val attempt: Int, val reason: String) : WebSocketState
    data class Error(val reason: String) : WebSocketState
}

data class WebSocketTelemetry(
    val stateLabel: String = "Unconfigured",
    val messagesSent: Long = 0L,
    val messagesReceived: Long = 0L,
    val stalePacketsDropped: Long = 0L,
    val reconnectAttempts: Int = 0,
    val lastSequence: Long = 0L
)

/**
 * Real OkHttp WebSocket client for bidirectional driver/rider GPS telemetry streaming.
 * Includes heartbeat pings, exponential-backoff reconnect, and monotonic sequence/timestamp guards.
 */
class LocationWebSocketClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val packetAdapter = moshi.adapter(LocationUpdatePacket::class.java)

    private val _state = MutableStateFlow<WebSocketState>(
        if (BuildConfig.WS_LOCATION_URL.isBlank()) WebSocketState.Unconfigured else WebSocketState.Disconnected
    )
    val state: StateFlow<WebSocketState> = _state.asStateFlow()

    private val _inboundLocations = MutableSharedFlow<LocationUpdatePacket>(extraBufferCapacity = 256)
    val inboundLocations: SharedFlow<LocationUpdatePacket> = _inboundLocations.asSharedFlow()

    private val _telemetry = MutableStateFlow(WebSocketTelemetry())
    val telemetry: StateFlow<WebSocketTelemetry> = _telemetry.asStateFlow()

    private val outboundSeq = AtomicLong(0L)
    private val sentCount = AtomicLong(0L)
    private val receivedCount = AtomicLong(0L)
    private val staleDroppedCount = AtomicLong(0L)
    private val lastInboundTsByEntity = ConcurrentHashMap<String, Long>()
    private val lastOutboundTsByEntity = ConcurrentHashMap<String, Long>()

    @Volatile
    private var activeSocket: WebSocket? = null
    @Volatile
    private var currentUrl: String = BuildConfig.WS_LOCATION_URL
    @Volatile
    private var shouldKeepAlive: Boolean = false
    @Volatile
    private var reconnectAttempt: Int = 0
    private var reconnectJob: Job? = null

    fun connect(wsUrl: String = BuildConfig.WS_LOCATION_URL) {
        val trimmed = wsUrl.trim()
        if (trimmed.isEmpty()) {
            shouldKeepAlive = false
            _state.value = WebSocketState.Unconfigured
            publishTelemetry("Unconfigured")
            return
        }
        currentUrl = trimmed
        shouldKeepAlive = true
        reconnectJob?.cancel()
        openSocket(trimmed)
    }

    private fun openSocket(url: String) {
        activeSocket?.cancel()
        _state.value = WebSocketState.Connecting
        publishTelemetry("Connecting")

        val request = Request.Builder().url(url).build()
        activeSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                _state.value = WebSocketState.Connected(url, System.currentTimeMillis())
                publishTelemetry("Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val packet = runCatching { packetAdapter.fromJson(text) }.getOrNull() ?: return
                val prevTs = lastInboundTsByEntity[packet.entityId] ?: 0L
                if (packet.timestampMs < prevTs) {
                    staleDroppedCount.incrementAndGet()
                    publishTelemetry("Connected")
                    return
                }
                lastInboundTsByEntity[packet.entityId] = packet.timestampMs
                receivedCount.incrementAndGet()
                _inboundLocations.tryEmit(packet)
                publishTelemetry("Connected")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                activeSocket = null
                if (shouldKeepAlive && code != 1000) {
                    scheduleReconnect(reason.ifBlank { "Closed ($code)" })
                } else {
                    _state.value = WebSocketState.Disconnected
                    publishTelemetry("Disconnected")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                activeSocket = null
                val reason = t.localizedMessage ?: "Socket error"
                if (shouldKeepAlive) {
                    scheduleReconnect(reason)
                } else {
                    _state.value = WebSocketState.Error(reason)
                    publishTelemetry("Error: $reason")
                }
            }
        })
    }

    private fun scheduleReconnect(reason: String) {
        if (!shouldKeepAlive || reconnectAttempt >= 5) {
            _state.value = WebSocketState.Error(reason)
            publishTelemetry("Error: $reason")
            return
        }
        reconnectAttempt++
        _state.value = WebSocketState.Reconnecting(reconnectAttempt, reason)
        publishTelemetry("Reconnecting (#$reconnectAttempt)")
        val backoffMs = (500L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(15_000L)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            if (shouldKeepAlive && currentUrl.isNotBlank()) {
                openSocket(currentUrl)
            }
        }
    }

    fun sendLocationUpdate(packet: LocationUpdatePacket): Boolean {
        val prevTs = lastOutboundTsByEntity[packet.entityId] ?: 0L
        if (packet.timestampMs < prevTs) {
            staleDroppedCount.incrementAndGet()
            publishTelemetry(_telemetry.value.stateLabel)
            return false
        }
        lastOutboundTsByEntity[packet.entityId] = packet.timestampMs

        val cellHex = packet.h3CellHex.ifBlank {
            H3Core.cellToString(H3Core.latLngToCell(packet.latitude, packet.longitude))
        }
        val seq = if (packet.sequence > 0L) packet.sequence else outboundSeq.incrementAndGet()
        val enriched = packet.copy(h3CellHex = cellHex, sequence = seq)
        val socket = activeSocket ?: return false
        val json = packetAdapter.toJson(enriched)
        val accepted = socket.send(json)
        if (accepted) {
            sentCount.incrementAndGet()
            publishTelemetry("Connected")
        }
        return accepted
    }

    fun disconnect() {
        shouldKeepAlive = false
        reconnectJob?.cancel()
        activeSocket?.close(1000, "Client disconnected")
        activeSocket = null
        _state.value = if (currentUrl.isBlank()) WebSocketState.Unconfigured else WebSocketState.Disconnected
        publishTelemetry(if (currentUrl.isBlank()) "Unconfigured" else "Disconnected")
    }

    private fun publishTelemetry(label: String) {
        _telemetry.value = WebSocketTelemetry(
            stateLabel = label,
            messagesSent = sentCount.get(),
            messagesReceived = receivedCount.get(),
            stalePacketsDropped = staleDroppedCount.get(),
            reconnectAttempts = reconnectAttempt,
            lastSequence = outboundSeq.get()
        )
    }
}
