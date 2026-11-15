package com.example.ui.rider

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocaleFormatter
import com.example.core.i18n.StringsProvider
import com.example.domain.model.AccountMode
import com.example.domain.model.AppNotification
import com.example.domain.model.DriverAvailability
import com.example.domain.model.PaymentRecord
import com.example.domain.model.Ride
import com.example.domain.model.RideStatus
import com.example.domain.model.RiderPaymentChoice
import com.example.domain.payment.StripeConfigurationStatus
import com.example.ui.main.VeloRideViewModel

@Composable
fun RideActivityScreen(
    language: AppLanguage,
    rides: List<Ride>,
    payments: List<PaymentRecord>,
    notifications: List<AppNotification>,
    assignedDriverLocation: DriverAvailability?,
    stripeStatus: StripeConfigurationStatus,
    viewModel: VeloRideViewModel,
    onOpenTripHistory: () -> Unit = {}
) {
    val strings = remember(language) { StringsProvider.get(language) }
    val context = LocalContext.current
    var hasNotificationPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
        if (granted) {
            viewModel.refreshFcmToken()
        }
    }

    val userAccount by viewModel.userAccount.collectAsStateWithLifecycle()
    val ratingsGiven by viewModel.ratingsGiven.collectAsStateWithLifecycle()
    val raterRole = if (userAccount?.activeMode == AccountMode.DRIVER.name) "DRIVER" else "RIDER"

    var rideToCancel by remember { mutableStateOf<Ride?>(null) }
    var cancelReason by remember { mutableStateOf("Changed plans") }
    var rideForComplaint by remember { mutableStateOf<Ride?>(null) }
    var complaintText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = strings.fcmPushTitle)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.fcmPushTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(strings.fcmPushSubtitle, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier.testTag("activity_grant_notifications_button")
                        ) {
                            Text(strings.grantNotificationPermission)
                        }
                    }
                }
            }
        }
        // Honest Stripe Gateway Configuration Notice
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (stripeStatus.isConfigured) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Payments, contentDescription = strings.paymentMethods)
                    Column {
                        Text(
                            text = if (stripeStatus.isConfigured) "Stripe Active" else strings.stripeNotConfigured,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stripeStatus.message,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Notifications Feed
        if (notifications.isNotEmpty()) {
            item {
                Text(
                    text = strings.notificationsTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(notifications.take(5), key = { it.id }) { note ->
                Card(
                    onClick = {
                        viewModel.markNotificationRead(note.id)
                        if (note.eventType in listOf("TRIP_COMPLETED", "RIDE_CANCELLED", "NO_DRIVER_AVAILABLE")) {
                            onOpenTripHistory()
                        }
                    },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = if (note.isRead) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (language == AppLanguage.ARABIC) note.titleAr else note.titleEn,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (language == AppLanguage.ARABIC) note.bodyAr else note.bodyEn,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.tripHistoryTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = strings.tripHistorySubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpenTripHistory,
                        modifier = Modifier.testTag("open_trip_history_button")
                    ) {
                        Icon(Icons.Default.History, contentDescription = strings.tripHistoryTitle)
                        Spacer(modifier = Modifier.height(0.dp))
                        Text(" ${strings.viewTripHistory}")
                    }
                }
            }
        }

        item {
            Text(
                text = strings.navActivity,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (rides.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (language == AppLanguage.ARABIC) "لا توجد رحلات حتى الآن" else "No rides recorded yet.",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        } else {
            items(rides, key = { it.id }) { ride ->
                val paymentForRide = payments.firstOrNull { it.rideId == ride.id }
                val fareMinor = if (ride.finalFareMinor > 0L) ride.finalFareMinor else ride.estimatedFareMinor
                val formattedFare = LocaleFormatter.formatCurrencyMinor(
                    fareMinor,
                    ride.currencyCode,
                    ride.currencySymbol,
                    ride.currencyDecimals.toInt(),
                    language
                )

                Card(
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ride_card_${ride.id}")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = ride.status,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Text(
                                text = formattedFare,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text("${strings.pickupLabel}: ${ride.pickupAddress}", style = MaterialTheme.typography.bodyMedium)
                        Text("${strings.destinationLabel}: ${ride.destinationAddress}", style = MaterialTheme.typography.bodyMedium)
                        val rideEpochMs = (ride.updatedAt?.seconds ?: ride.createdAt?.seconds ?: 0L) * 1000L
                        val rideDateLabel = if (rideEpochMs > 0L) {
                            LocaleFormatter.formatDateTime(rideEpochMs, language)
                        } else ""
                        val choice = RiderPaymentChoice.fromRidePaymentMethodId(ride.paymentMethodId)
                        val settlementLabel = when (choice) {
                            RiderPaymentChoice.CASH -> strings.paymentCash
                            RiderPaymentChoice.E_WALLET -> strings.paymentEWallet
                            RiderPaymentChoice.CARD -> strings.paymentCard
                        }
                        Text(
                            text = listOfNotNull(
                                rideDateLabel.takeIf { it.isNotBlank() },
                                ride.vehicleCategory,
                                settlementLabel,
                                "${strings.pricingVersionLabel} v${ride.pricingVersion}",
                                ride.city
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!ride.driverName.isNullOrBlank()) {
                            HorizontalDivider()
                            Text(
                                text = "Driver: ${ride.driverName} • ${ride.driverVehicle.orEmpty()} (${ride.driverPlate.orEmpty()})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            assignedDriverLocation?.takeIf { it.driverId == ride.driverId }?.let { loc ->
                                Text(
                                    text = "Live GPS: %.4f, %.4f".format(loc.latitude, loc.longitude),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }

                        if (paymentForRide != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null)
                                Text(
                                    text = "Receipt ${paymentForRide.receiptNumber} (${paymentForRide.paymentMethodType}) • ${paymentForRide.status}" +
                                        (paymentForRide.failureReason?.let { " ($it)" } ?: ""),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        paymentForRide.status == "SUCCEEDED" -> MaterialTheme.colorScheme.primary
                                        paymentForRide.paymentMethodType == "CASH" -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }

                        // Action Buttons per lifecycle state
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (ride.status in listOf(
                                    RideStatus.REQUESTED.name,
                                    RideStatus.SEARCHING_FOR_DRIVER.name,
                                    RideStatus.OFFERED.name,
                                    RideStatus.ACCEPTED.name,
                                    RideStatus.DRIVER_ARRIVING.name,
                                    RideStatus.ARRIVED.name
                                )
                            ) {
                                OutlinedButton(
                                    onClick = { rideToCancel = ride },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.testTag("cancel_ride_${ride.id}")
                                ) {
                                    Text(strings.cancelRide)
                                }
                            }

                            if (ride.status == RideStatus.NO_DRIVER_AVAILABLE.name) {
                                Button(
                                    onClick = { viewModel.retryRideDispatch(ride) },
                                    modifier = Modifier.testTag("retry_ride_${ride.id}")
                                ) {
                                    Text(strings.retryDispatch)
                                }
                            }

                            if (ride.status == RideStatus.COMPLETED.name && ride.paymentStatus != "SUCCEEDED") {
                                Button(
                                    onClick = { viewModel.initiateRidePayment(ride) },
                                    modifier = Modifier.testTag("pay_ride_${ride.id}")
                                ) {
                                    Text(strings.payNow)
                                }
                            }

                            val givenRating = ratingsGiven.firstOrNull {
                                it.rideId == ride.id && it.fromRole.equals(raterRole, ignoreCase = true)
                            }
                            val alreadyRatedFlag = if (raterRole == "DRIVER") ride.driverRated else ride.riderRated
                            val isAlreadyRated = alreadyRatedFlag || givenRating != null
                            val counterpartyId = if (raterRole == "DRIVER") ride.riderId else ride.driverId

                            if (ride.status == RideStatus.COMPLETED.name && !isAlreadyRated && counterpartyId.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { viewModel.openRatingPrompt(ride, raterRole) },
                                    modifier = Modifier.testTag("rate_ride_${ride.id}")
                                ) {
                                    Icon(Icons.Default.Star, contentDescription = null)
                                    Text(if (raterRole == "DRIVER") " ${strings.ratePassenger}" else " ${strings.rateDriver}")
                                }
                            } else if (ride.status == RideStatus.COMPLETED.name && isAlreadyRated) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.testTag("rated_badge_${ride.id}")
                                ) {
                                    Text(
                                        text = if (givenRating != null) {
                                            "${strings.alreadyRatedLabel} ★ ${givenRating.stars}"
                                        } else {
                                            "★ ${strings.alreadyRatedLabel}"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { rideForComplaint = ride },
                                modifier = Modifier.testTag("report_ride_${ride.id}")
                            ) {
                                Icon(Icons.Default.ReportProblem, contentDescription = "Report issue")
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    rideToCancel?.let { target ->
        AlertDialog(
            onDismissRequest = { rideToCancel = null },
            title = { Text(strings.cancelRide) },
            text = {
                OutlinedTextField(
                    value = cancelReason,
                    onValueChange = { cancelReason = it },
                    label = { Text("Reason") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.cancelActiveRide(target, "RIDER", cancelReason)
                        rideToCancel = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(strings.cancelRide) }
            },
            dismissButton = {
                TextButton(onClick = { rideToCancel = null }) { Text("Back") }
            }
        )
    }

    rideForComplaint?.let { target ->
        AlertDialog(
            onDismissRequest = { rideForComplaint = null },
            title = { Text("Submit Support Report") },
            text = {
                OutlinedTextField(
                    value = complaintText,
                    onValueChange = { complaintText = it },
                    label = { Text("Issue details") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (complaintText.isNotBlank()) {
                        viewModel.submitComplaint(target.id, "TRIP_ISSUE", complaintText)
                        complaintText = ""
                        rideForComplaint = null
                    }
                }) { Text(strings.save) }
            },
            dismissButton = {
                TextButton(onClick = { rideForComplaint = null }) { Text("Close") }
            }
        )
    }
}
