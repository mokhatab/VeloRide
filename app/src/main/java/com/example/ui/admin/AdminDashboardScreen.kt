package com.example.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocaleFormatter
import com.example.core.i18n.StringsProvider
import com.example.domain.model.AuditLog
import com.example.domain.model.ComplaintRecord
import com.example.domain.model.DriverDocument
import com.example.domain.model.DriverEarning
import com.example.domain.model.DriverProfile
import com.example.domain.model.OperationalConfig
import com.example.domain.model.PaymentRecord
import com.example.domain.model.PricingPolicy
import com.example.domain.model.Ride
import com.example.domain.model.UserAccount

@Composable
fun AdminDashboardScreen(
    language: AppLanguage,
    users: List<UserAccount>,
    drivers: List<DriverProfile>,
    documents: List<DriverDocument>,
    rides: List<Ride>,
    payments: List<PaymentRecord>,
    earnings: List<DriverEarning>,
    complaints: List<ComplaintRecord>,
    auditLogs: List<AuditLog>,
    pricingPolicies: List<PricingPolicy>,
    operationalConfig: OperationalConfig,
    adminViewModel: AdminViewModel
) {
    val strings = remember(language) { StringsProvider.get(language) }
    var tabIndex by remember { mutableIntStateOf(0) }
    var editingPolicy by remember { mutableStateOf<PricingPolicy?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 12.dp) {
            listOf("Drivers & Docs", "Users & Reports", "Live Rides", "Pricing & Audit", "Finance", "Config").forEachIndexed { i, title ->
                Tab(
                    selected = tabIndex == i,
                    onClick = { tabIndex = i },
                    text = { Text(title) },
                    modifier = Modifier.testTag("admin_tab_$i")
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (tabIndex) {
                0 -> {
                    items(drivers, key = { it.userId }) { d ->
                        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${d.fullName} • ${d.approvalStatus}", fontWeight = FontWeight.Bold)
                                Text("${d.vehicleMake} ${d.vehicleModel} (${d.plateNumber}) • ${d.vehicleCategory}")
                                Text("Licence: ${d.licenceNumber} • Nat ID: ${d.nationalId}", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { adminViewModel.approveDriver(d.userId) },
                                        modifier = Modifier.testTag("admin_approve_${d.userId}")
                                    ) { Text(strings.approve) }
                                    OutlinedButton(
                                        onClick = { adminViewModel.rejectDriver(d.userId, "Documents incomplete") }
                                    ) { Text(strings.reject) }
                                    OutlinedButton(
                                        onClick = { adminViewModel.suspendDriver(d.userId, "Policy suspension") },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) { Text(strings.suspend) }
                                }
                            }
                        }
                    }
                    items(documents, key = { it.id }) { doc ->
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${doc.documentType} (${doc.driverId.take(6)}) • ${doc.verificationStatus}", fontWeight = FontWeight.SemiBold)
                                Text("Ref: ${doc.documentNumber} • Exp: ${doc.expiryDate}", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { adminViewModel.verifyDocument(doc.id, true, "Verified by admin") }) {
                                        Text(strings.approve)
                                    }
                                    OutlinedButton(onClick = { adminViewModel.verifyDocument(doc.id, false, "Unclear image") }) {
                                        Text(strings.reject)
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    items(users, key = { it.userId }) { u ->
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${u.fullName} (${u.activeMode})", fontWeight = FontWeight.Bold)
                                    Text("${u.email} • Suspended=${u.isSuspended}", style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = { adminViewModel.toggleUserSuspension(u.userId, !u.isSuspended) }) {
                                    Text(if (u.isSuspended) "Activate" else strings.suspend)
                                }
                            }
                        }
                    }
                    items(complaints, key = { it.id }) { c ->
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Complaint ${c.category} • ${c.status}", fontWeight = FontWeight.Bold)
                                Text(c.description)
                                Button(onClick = { adminViewModel.resolveComplaint(c.id, "RESOLVED", "Reviewed by ops") }) {
                                    Text("Resolve")
                                }
                            }
                        }
                    }
                }
                2 -> {
                    items(rides, key = { it.id }) { r ->
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("${r.status} • ${r.city}, ${r.countryCode} • Round ${r.dispatchRound}", fontWeight = FontWeight.Bold)
                                Text("Rider: ${r.riderName} | Driver: ${r.driverName ?: "Unassigned"}")
                                Text("Estimate: ${LocaleFormatter.formatCurrencyMinor(r.estimatedFareMinor, r.currencyCode, r.currencySymbol, r.currencyDecimals.toInt(), language)}")
                            }
                        }
                    }
                }
                3 -> {
                    items(pricingPolicies, key = { it.id }) { policy ->
                        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "${policy.city}, ${policy.countryCode} • ${policy.serviceCategory}" +
                                        if (policy.isTaxiReference) " [LOCAL TAXI REF]" else " [v${policy.pricingVersion}]",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Base ${policy.baseFareMinor} minor | Per Km ${policy.pricePerKmMinor} | Wait ${policy.waitingChargePerMinMinor} | Surge ${policy.surgeMultiplier}x",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(
                                    onClick = { editingPolicy = policy },
                                    modifier = Modifier.testTag("edit_policy_${policy.id}")
                                ) { Text("Edit & Bump Version") }
                            }
                        }
                    }
                    item {
                        Text("Audit Trail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    items(auditLogs, key = { it.id }) { log ->
                        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("${log.action} • ${log.targetCollection}/${log.targetId}", fontWeight = FontWeight.SemiBold)
                                Text("${log.previousValueSummary} → ${log.newValueSummary}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                4 -> {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Payments (${payments.size}) • Driver Earnings (${earnings.size})", fontWeight = FontWeight.Bold)
                                Text("Manual payouts record offline settlements cleanly alongside Stripe ledgers.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    items(earnings, key = { it.id }) { e ->
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Driver ${e.driverId.take(6)} • ${e.payoutStatus}", fontWeight = FontWeight.Bold)
                                Text("Net: ${LocaleFormatter.formatCurrencyMinor(e.netEarningsMinor, e.currencyCode, e.currencyCode, e.currencyDecimals.toInt(), language)}")
                                if (e.payoutStatus != "PAID_MANUAL") {
                                    Button(onClick = { adminViewModel.recordManualPayout(e, "BANK-${e.id.take(6)}") }) {
                                        Text("Record Manual Payout")
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    item {
                        OperationalConfigCard(operationalConfig, adminViewModel)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    editingPolicy?.let { target ->
        var baseText by remember(target) { mutableStateOf(target.baseFareMinor.toString()) }
        var kmText by remember(target) { mutableStateOf(target.pricePerKmMinor.toString()) }
        var waitText by remember(target) { mutableStateOf(target.waitingChargePerMinMinor.toString()) }
        var minText by remember(target) { mutableStateOf(target.minimumFareMinor.toString()) }
        var surgeText by remember(target) { mutableStateOf(target.surgeMultiplier.toString()) }

        AlertDialog(
            onDismissRequest = { editingPolicy = null },
            title = { Text("Update ${target.city} ${target.serviceCategory} (${target.currencyCode})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(baseText, { baseText = it }, label = { Text("Base Fare (minor units)") })
                    OutlinedTextField(kmText, { kmText = it }, label = { Text("Price per Km (minor units)") })
                    OutlinedTextField(waitText, { waitText = it }, label = { Text("Waiting/min (minor units)") })
                    OutlinedTextField(minText, { minText = it }, label = { Text("Minimum Fare (minor units)") })
                    OutlinedTextField(surgeText, { surgeText = it }, label = { Text("Surge Multiplier") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    adminViewModel.updatePricingPolicy(
                        policy = target,
                        baseMinor = baseText.toLongOrNull() ?: target.baseFareMinor,
                        perKmMinor = kmText.toLongOrNull() ?: target.pricePerKmMinor,
                        waitingMinor = waitText.toLongOrNull() ?: target.waitingChargePerMinMinor,
                        minFareMinor = minText.toLongOrNull() ?: target.minimumFareMinor,
                        surge = surgeText.toDoubleOrNull() ?: target.surgeMultiplier
                    )
                    editingPolicy = null
                }) { Text(strings.save) }
            },
            dismissButton = {
                TextButton(onClick = { editingPolicy = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun OperationalConfigCard(config: OperationalConfig, adminViewModel: AdminViewModel) {
    var r1 by remember(config) { mutableStateOf(config.dispatchRound1RadiusKm.toString()) }
    var r2 by remember(config) { mutableStateOf(config.dispatchRound2RadiusKm.toString()) }
    var r3 by remember(config) { mutableStateOf(config.dispatchRound3RadiusKm.toString()) }
    var offerSec by remember(config) { mutableStateOf(config.offerDurationSeconds.toString()) }
    var commPct by remember(config) { mutableStateOf(config.platformCommissionPercent.toString()) }

    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Operational Parameters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(r1, { r1 = it }, label = { Text("Round 1 Radius (km)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(r2, { r2 = it }, label = { Text("Round 2 Radius (km)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(r3, { r3 = it }, label = { Text("Round 3 Radius (km)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(offerSec, { offerSec = it }, label = { Text("Offer Window (seconds)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(commPct, { commPct = it }, label = { Text("Platform Commission (%)") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    adminViewModel.updateOperationalConfig(
                        config.copy(
                            dispatchRound1RadiusKm = r1.toDoubleOrNull() ?: config.dispatchRound1RadiusKm,
                            dispatchRound2RadiusKm = r2.toDoubleOrNull() ?: config.dispatchRound2RadiusKm,
                            dispatchRound3RadiusKm = r3.toDoubleOrNull() ?: config.dispatchRound3RadiusKm,
                            offerDurationSeconds = offerSec.toLongOrNull() ?: config.offerDurationSeconds,
                            platformCommissionPercent = commPct.toDoubleOrNull() ?: config.platformCommissionPercent
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("save_ops_config_button")
            ) {
                Text("Save Configuration")
            }
        }
    }
}
