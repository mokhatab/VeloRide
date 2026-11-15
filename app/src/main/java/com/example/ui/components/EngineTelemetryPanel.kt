package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.main.EngineTelemetrySnapshot
import java.util.Locale

@Composable
fun EngineTelemetrySummaryCard(
    telemetry: EngineTelemetrySnapshot,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalCached = telemetry.cacheStats.sumOf { it.size }
    val totalHits = telemetry.cacheStats.sumOf { it.hits }
    val totalLookups = totalHits + telemetry.cacheStats.sumOf { it.misses }
    val hitRate = if (totalLookups == 0L) 0.0 else (totalHits * 100.0) / totalLookups.toDouble()

    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Hub, contentDescription = "Real-time engine telemetry")
                    Text("Real-Time Core Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.testTag("refresh_engine_telemetry_button")
                ) {
                    Text("Refresh")
                }
            }

            TelemetryRow(
                icon = Icons.Default.Hexagon,
                label = "H3 Hex Cell (Res 8)",
                value = "${telemetry.pickupH3CellHex.ifBlank { "-" }} (${telemetry.h3SearchLatencyMicros} us)"
            )
            TelemetryRow(
                icon = Icons.Default.Sync,
                label = "Rider Sync Store",
                value = "${telemetry.riderSync.syncedCount} / ${telemetry.riderSync.targetCapacity}" +
                    if (telemetry.riderSync.isSimulationBenchmark) " [BENCHMARK]" else ""
            )
            TelemetryRow(
                icon = Icons.Default.Cached,
                label = "In-Memory Cache",
                value = "$totalCached items • ${String.format(Locale.US, "%.1f%%", hitRate)} hits"
            )
            TelemetryRow(
                icon = Icons.Default.Bolt,
                label = "Location WebSocket",
                value = "${telemetry.webSocket.stateLabel} (Tx ${telemetry.webSocket.messagesSent} / Rx ${telemetry.webSocket.messagesReceived})"
            )
            TelemetryRow(
                icon = Icons.Default.Lock,
                label = "CAS Race Guard",
                value = "${telemetry.conditionalUpdates.successfulUpdates} ok • ${telemetry.conditionalUpdates.versionConflicts} conflicts blocked"
            )
            TelemetryRow(
                icon = Icons.Default.Queue,
                label = "Async Message Queue",
                value = "Pending ${telemetry.queueStats.pendingCount} • Processed ${telemetry.queueStats.processedCount} • DLQ ${telemetry.queueStats.deadLetterCount}"
            )
        }
    }
}

@Composable
fun AdminScaleEngineConsole(
    telemetry: EngineTelemetrySnapshot,
    onRun5000RiderBenchmark: () -> Unit,
    onRunRaceGuardCheck: () -> Unit,
    onConnectWebSocket: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var wsEndpointInput by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EngineTelemetrySummaryCard(telemetry = telemetry, onRefresh = onRefresh)

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Scale & Concurrency Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Benchmark 5,000-rider chunked H3 + cache ingestion and verify atomic CAS conflict blocking under 40 concurrent writers.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = onRun5000RiderBenchmark,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("benchmark_sync_5000_button")
                ) {
                    Text("Run 5,000-Rider Sync Benchmark (Simulation)")
                }
                OutlinedButton(
                    onClick = onRunRaceGuardCheck,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("verify_cas_race_button")
                ) {
                    Text("Verify 40-Thread CAS Race Protection")
                }
            }
        }

        Card(shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("WebSocket Telemetry Stream", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Status: ${telemetry.webSocket.stateLabel} • Stale dropped: ${telemetry.webSocket.stalePacketsDropped}",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = wsEndpointInput,
                    onValueChange = { wsEndpointInput = it },
                    label = { Text("wss:// endpoint URL (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = { onConnectWebSocket(wsEndpointInput) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("connect_ws_button")
                ) {
                    Text("Apply WebSocket Endpoint")
                }
            }
        }

        Card(shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("In-Memory LRU + TTL Stores", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                telemetry.cacheStats.forEach { stat ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stat.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${stat.size}/${stat.maxEntries} • ${String.format(Locale.US, "%.0f%%", stat.hitRatePercent)} hit",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    HorizontalDivider()
                }
            }
        }

        Card(shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ride State Machine Audit Trail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (telemetry.recentStateTransitions.isEmpty()) {
                    Text("No state transitions recorded in this session yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    telemetry.recentStateTransitions.forEach { rec ->
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                Text("${rec.fromStatus} -> ${rec.toStatus} (${rec.eventName})", fontWeight = FontWeight.SemiBold)
                                Text("Ride ${rec.rideId.take(10)} • ${rec.actorNote}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
