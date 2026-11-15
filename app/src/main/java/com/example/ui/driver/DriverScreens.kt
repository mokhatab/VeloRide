package com.example.ui.driver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.engine.H3Core
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocaleFormatter
import com.example.core.i18n.StringsProvider
import com.example.domain.mapping.GeoPoint
import com.example.domain.model.DriverApprovalStatus
import com.example.domain.model.DriverAvailability
import com.example.domain.model.DriverDocument
import com.example.domain.model.DriverDocumentType
import com.example.domain.model.DriverEarning
import com.example.domain.model.DriverOnlineStatus
import com.example.domain.model.DriverPayout
import com.example.domain.model.DriverProfile
import com.example.domain.model.PaymentRecord
import com.example.domain.model.Ride
import com.example.domain.model.RideOffer
import com.example.domain.model.RideStatus
import com.example.domain.model.TripHistoryRole
import com.example.domain.model.UserAccount
import com.example.domain.model.VehicleCategory
import com.example.ui.components.formatAverageRating
import com.example.ui.history.TripHistoryScreen
import com.example.ui.main.TripHistoryUiState
import com.example.ui.main.VeloRideViewModel

@Composable
fun DriverWorkspaceScreen(
    language: AppLanguage,
    account: UserAccount,
    driverProfile: DriverProfile?,
    documents: List<DriverDocument>,
    availability: DriverAvailability?,
    pendingOffers: List<RideOffer>,
    driverRides: List<Ride>,
    driverHistoryState: TripHistoryUiState = TripHistoryUiState.Loading,
    payments: List<PaymentRecord> = emptyList(),
    earnings: List<DriverEarning>,
    payouts: List<DriverPayout>,
    nowMs: Long,
    currentGpsPoint: GeoPoint,
    viewModel: VeloRideViewModel
) {
    val strings = remember(language) { StringsProvider.get(language) }
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(strings.driverMode) },
                modifier = Modifier.testTag("driver_tab_dispatch")
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(strings.documentsTitle) },
                modifier = Modifier.testTag("driver_tab_onboarding")
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text(strings.earningsTitle) },
                modifier = Modifier.testTag("driver_tab_earnings")
            )
            Tab(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 },
                text = { Text(strings.tripHistoryTitle) },
                modifier = Modifier.testTag("driver_tab_history")
            )
        }

        when (selectedTab) {
            0 -> DriverDispatchTab(
                language = language,
                driverProfile = driverProfile,
                availability = availability,
                pendingOffers = pendingOffers,
                driverRides = driverRides,
                nowMs = nowMs,
                currentGpsPoint = currentGpsPoint,
                viewModel = viewModel,
                onOpenOnboarding = { selectedTab = 1 }
            )
            1 -> DriverOnboardingTab(
                language = language,
                account = account,
                driverProfile = driverProfile,
                documents = documents,
                viewModel = viewModel
            )
            2 -> DriverEarningsTab(
                language = language,
                earnings = earnings,
                payouts = payouts
            )
            else -> {
                val ratingsGiven by viewModel.ratingsGiven.collectAsStateWithLifecycle()
                TripHistoryScreen(
                    language = language,
                    initialRole = TripHistoryRole.DRIVER,
                    showRoleSwitcher = false,
                    riderHistoryState = driverHistoryState,
                    driverHistoryState = driverHistoryState,
                    payments = payments,
                    ratingsGiven = ratingsGiven,
                    onRateTrip = { ride, role ->
                        viewModel.openRatingPrompt(ride, if (role == TripHistoryRole.DRIVER) "DRIVER" else "RIDER")
                    },
                    onBack = null
                )
            }
        }
    }
}

@Composable
private fun DriverDispatchTab(
    language: AppLanguage,
    driverProfile: DriverProfile?,
    availability: DriverAvailability?,
    pendingOffers: List<RideOffer>,
    driverRides: List<Ride>,
    nowMs: Long,
    currentGpsPoint: GeoPoint,
    viewModel: VeloRideViewModel,
    onOpenOnboarding: () -> Unit
) {
    val context = LocalContext.current
    val strings = remember(language) { StringsProvider.get(language) }
    val ratingsGiven by viewModel.ratingsGiven.collectAsStateWithLifecycle()
    val driverRatingSummary by viewModel.driverRatingSummary.collectAsStateWithLifecycle()
    val isApproved = driverProfile?.approvalStatus == DriverApprovalStatus.APPROVED.name
    val isOnline = availability?.status in listOf(
        DriverOnlineStatus.ONLINE.name,
        DriverOnlineStatus.OFFER_RECEIVED.name,
        DriverOnlineStatus.ACCEPTED.name,
        DriverOnlineStatus.ARRIVING.name,
        DriverOnlineStatus.ON_TRIP.name
    )
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Go online even if permission was denied; in-app 30s alert + chime still runs.
        viewModel.toggleDriverOnline(true, currentGpsPoint)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Approval & Online Status Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isOnline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (driverProfile == null) "Driver Profile Not Registered" else "${strings.statusPrefix}: ${driverProfile.approvalStatus}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${availability?.status ?: DriverOnlineStatus.OFFLINE.name} • ★ ${formatAverageRating(driverRatingSummary.averageRating)} (${driverRatingSummary.ratingCount})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("driver_profile_avg_rating")
                        )
                    }
                    Switch(
                        checked = isOnline,
                        enabled = isApproved,
                        onCheckedChange = { checked ->
                            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.toggleDriverOnline(true, currentGpsPoint)
                                }
                            } else {
                                viewModel.toggleDriverOnline(checked, currentGpsPoint)
                            }
                        },
                        modifier = Modifier.testTag("driver_online_switch")
                    )
                }

                if (!isApproved) {
                    Text(
                        text = driverProfile?.rejectionReason?.takeIf { it.isNotBlank() } ?: strings.driverApprovalRequired,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(
                        onClick = onOpenOnboarding,
                        modifier = Modifier.testTag("open_driver_onboarding_button")
                    ) {
                        Text(strings.documentsTitle)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.pushDriverGpsUpdate(currentGpsPoint) },
                            modifier = Modifier.testTag("driver_push_gps_button")
                        ) {
                            Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Sync GPS")
                        }
                    }
                }
            }
        }

        // Urgent 30-second Incoming Ride Offer Card
        val activeOffer = pendingOffers.firstOrNull { nowMs <= it.expiresAtMs }
        if (activeOffer != null) {
            val remainingMs = (activeOffer.expiresAtMs - nowMs).coerceAtLeast(0L)
            val remainingSec = (remainingMs / 1000L).toInt()
            val progress = (remainingMs / 30_000f).coerceIn(0f, 1f)

            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.secondary),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("incoming_offer_card")
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
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
                            Icon(
                                Icons.Default.NotificationsActive,
                                contentDescription = strings.incomingRideOffer,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = strings.incomingRideOffer,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.error
                        ) {
                            Text(
                                text = "$remainingSec ${strings.secondsRemaining}",
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )

                    Text(
                        text = LocaleFormatter.formatCurrencyMinor(
                            activeOffer.estimatedFareMinor,
                            activeOffer.currencyCode,
                            activeOffer.currencySymbol,
                            activeOffer.currencyDecimals.toInt(),
                            language
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val zoneHex = remember(activeOffer.pickupLat, activeOffer.pickupLng) {
                        H3Core.cellToString(
                            H3Core.latLngToCell(activeOffer.pickupLat, activeOffer.pickupLng, H3Core.DEFAULT_RESOLUTION)
                        )
                    }
                    Text("${strings.pickupLabel}: ${activeOffer.pickupAddress}", fontWeight = FontWeight.SemiBold)
                    Text("${strings.destinationLabel}: ${activeOffer.destinationAddress}")
                    Text(
                        text = "${strings.zoneAlertLabel}: $zoneHex • Pickup: ${LocaleFormatter.formatDistanceKm(activeOffer.distanceToPickupKm, language)} • Trip: ${LocaleFormatter.formatDistanceKm(activeOffer.estimatedTripDistanceKm, language)} (${activeOffer.estimatedDurationMin} min) • Rider ★ ${activeOffer.riderRatingAvg}",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.rejectRideOffer(activeOffer) },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("reject_offer_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(strings.rejectRide, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { viewModel.acceptRideOffer(activeOffer) },
                            modifier = Modifier
                                .weight(1.4f)
                                .height(52.dp)
                                .testTag("accept_offer_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(strings.acceptRide, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }

        // Active Driver Trip Controls
        val activeTrip = driverRides.firstOrNull {
            it.status in listOf(
                RideStatus.ACCEPTED.name,
                RideStatus.DRIVER_ARRIVING.name,
                RideStatus.ARRIVED.name,
                RideStatus.IN_PROGRESS.name
            )
        }
        if (activeTrip != null) {
            Card(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("driver_active_trip_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Active Trip • ${activeTrip.status}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Rider: ${activeTrip.riderName}")
                    Text("${strings.pickupLabel}: ${activeTrip.pickupAddress}")
                    Text("${strings.destinationLabel}: ${activeTrip.destinationAddress}")

                    when (activeTrip.status) {
                        RideStatus.ACCEPTED.name -> {
                            Button(
                                onClick = { viewModel.advanceDriverTrip(activeTrip, RideStatus.DRIVER_ARRIVING) },
                                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("driver_enroute_button")
                            ) { Text("EN ROUTE TO PICKUP") }
                        }
                        RideStatus.DRIVER_ARRIVING.name -> {
                            Button(
                                onClick = { viewModel.advanceDriverTrip(activeTrip, RideStatus.ARRIVED) },
                                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("driver_arrived_button")
                            ) { Text(strings.markArrived) }
                        }
                        RideStatus.ARRIVED.name -> {
                            Button(
                                onClick = { viewModel.advanceDriverTrip(activeTrip, RideStatus.IN_PROGRESS) },
                                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("driver_start_trip_button")
                            ) { Text(strings.startTrip) }
                        }
                        RideStatus.IN_PROGRESS.name -> {
                            Button(
                                onClick = {
                                    viewModel.advanceDriverTrip(
                                        ride = activeTrip,
                                        nextStatus = RideStatus.COMPLETED,
                                        actualKm = activeTrip.estimatedDistanceKm,
                                        waitMins = 2L
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("driver_complete_trip_button")
                            ) { Text(strings.completeTrip) }
                        }
                    }
                }
            }
        }

        // Prompt Driver to rate recently completed unrated passenger trip
        val unratedCompletedTrip = driverRides.firstOrNull { ride ->
            ride.status == RideStatus.COMPLETED.name &&
                !ride.driverRated &&
                ride.riderId.isNotBlank() &&
                ratingsGiven.none { it.rideId == ride.id && it.fromRole.equals("DRIVER", ignoreCase = true) }
        }
        if (unratedCompletedTrip != null) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("driver_rate_rider_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = strings.ratePassenger,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = strings.ratePassenger,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "${unratedCompletedTrip.riderName.ifBlank { "Passenger" }} • ${unratedCompletedTrip.pickupAddress} → ${unratedCompletedTrip.destinationAddress}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = { viewModel.openRatingPrompt(unratedCompletedTrip, "DRIVER") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("driver_rate_passenger_button")
                    ) {
                        Text(strings.ratePassenger, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DriverOnboardingTab(
    language: AppLanguage,
    account: UserAccount,
    driverProfile: DriverProfile?,
    documents: List<DriverDocument>,
    viewModel: VeloRideViewModel
) {
    val strings = remember(language) { StringsProvider.get(language) }

    var fullName by remember(driverProfile) { mutableStateOf(driverProfile?.fullName ?: account.fullName) }
    var mobile by remember(driverProfile) { mutableStateOf(driverProfile?.mobileNumber ?: account.mobileNumber) }
    var nationalId by remember(driverProfile) { mutableStateOf(driverProfile?.nationalId ?: "") }
    var licenceNum by remember(driverProfile) { mutableStateOf(driverProfile?.licenceNumber ?: "") }
    var licenceExpiry by remember(driverProfile) { mutableStateOf(driverProfile?.licenceExpiry ?: "2029-12-31") }
    var vehicleMake by remember(driverProfile) { mutableStateOf(driverProfile?.vehicleMake ?: "") }
    var vehicleModel by remember(driverProfile) { mutableStateOf(driverProfile?.vehicleModel ?: "") }
    var vehicleYear by remember(driverProfile) { mutableStateOf((driverProfile?.vehicleYear ?: 2023L).toString()) }
    var vehicleColor by remember(driverProfile) { mutableStateOf(driverProfile?.vehicleColor ?: "") }
    var regNum by remember(driverProfile) { mutableStateOf(driverProfile?.registrationNumber ?: "") }
    var plateNum by remember(driverProfile) { mutableStateOf(driverProfile?.plateNumber ?: "") }
    var category by remember(driverProfile) {
        mutableStateOf(
            VehicleCategory.entries.firstOrNull { it.name == driverProfile?.vehicleCategory }
                ?: VehicleCategory.ECONOMY
        )
    }

    var pendingDocType by remember { mutableStateOf(DriverDocumentType.DRIVING_LICENCE) }
    var docNumberInput by remember { mutableStateOf("") }

    // Android Photo Picker (Zero Storage Permission)
    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadDriverDocument(
                docType = pendingDocType,
                countryCode = account.countryCode,
                docNumber = docNumberInput.ifBlank { licenceNum.ifBlank { "DOC-REF" } },
                expiryDate = licenceExpiry,
                imageUri = uri.toString()
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = "${strings.statusPrefix}: ${driverProfile?.approvalStatus ?: DriverApprovalStatus.REGISTERED.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        OutlinedTextField(fullName, { fullName = it }, label = { Text(strings.fullName) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(mobile, { mobile = it }, label = { Text(strings.mobileNumber) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(nationalId, { nationalId = it }, label = { Text(strings.nationalId) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(licenceNum, { licenceNum = it }, label = { Text(strings.licenceNumber) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(vehicleMake, { vehicleMake = it }, label = { Text(strings.vehicleMake) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(vehicleModel, { vehicleModel = it }, label = { Text(strings.vehicleModel) }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(vehicleYear, { vehicleYear = it }, label = { Text(strings.vehicleYear) }, modifier = Modifier.weight(1f))
            OutlinedTextField(vehicleColor, { vehicleColor = it }, label = { Text(strings.vehicleColor) }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(regNum, { regNum = it }, label = { Text(strings.regNumber) }, modifier = Modifier.weight(1f))
            OutlinedTextField(plateNum, { plateNum = it }, label = { Text(strings.plateNumber) }, modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VehicleCategory.entries.forEach { cat ->
                FilterChip(
                    selected = category == cat,
                    onClick = { category = cat },
                    label = { Text(cat.displayName) }
                )
            }
        }

        Button(
            onClick = {
                viewModel.saveDriverOnboarding(
                    fullName = fullName,
                    mobileNumber = mobile,
                    nationalId = nationalId,
                    licenceNumber = licenceNum,
                    licenceExpiry = licenceExpiry,
                    profilePhotoUrl = driverProfile?.profilePhotoUrl,
                    vehicleMake = vehicleMake,
                    vehicleModel = vehicleModel,
                    vehicleYear = vehicleYear.toLongOrNull() ?: 2023L,
                    vehicleCategory = category,
                    vehicleColor = vehicleColor,
                    registrationNumber = regNum,
                    plateNumber = plateNum,
                    vehiclePhotoUrls = driverProfile?.vehiclePhotoUrls.orEmpty(),
                    submitForReview = true
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("submit_driver_onboarding_button")
        ) {
            Text(strings.submitForReview, fontWeight = FontWeight.Bold)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Text(strings.documentsTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = docNumberInput,
            onValueChange = { docNumberInput = it },
            label = { Text("Document Reference Number") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DriverDocumentType.entries.forEach { docType ->
                OutlinedButton(
                    onClick = {
                        pendingDocType = docType
                        docPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.testTag("upload_doc_${docType.name}")
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = docType.name, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(docType.name.replace("_", " "))
                }
            }
        }

        documents.forEach { doc ->
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(doc.documentType, fontWeight = FontWeight.Bold)
                        Text("${doc.documentNumber} • ${doc.verificationStatus}", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = doc.verificationStatus,
                        tint = if (doc.verificationStatus == "VERIFIED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DriverEarningsTab(
    language: AppLanguage,
    earnings: List<DriverEarning>,
    payouts: List<DriverPayout>
) {
    val strings = remember(language) { StringsProvider.get(language) }
    val totalNet = earnings.sumOf { it.netEarningsMinor }
    val currency = earnings.firstOrNull()?.currencyCode ?: "EGP"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(strings.netEarnings, style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = LocaleFormatter.formatCurrencyMinor(totalNet, currency, currency, 2, language),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Icon(Icons.Default.MonetizationOn, contentDescription = strings.earningsTitle, modifier = Modifier.size(34.dp))
            }
        }

        earnings.forEach { e ->
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Ride ${e.rideId.take(8)} • ${e.payoutStatus}", fontWeight = FontWeight.Bold)
                    Text("${strings.grossEarnings}: ${LocaleFormatter.formatCurrencyMinor(e.grossFareMinor, e.currencyCode, e.currencyCode, e.currencyDecimals.toInt(), language)}")
                    Text("${strings.commissionLabel}: -${LocaleFormatter.formatCurrencyMinor(e.platformCommissionMinor, e.currencyCode, e.currencyCode, e.currencyDecimals.toInt(), language)}")
                    Text(
                        text = "${strings.netEarnings}: ${LocaleFormatter.formatCurrencyMinor(e.netEarningsMinor, e.currencyCode, e.currencyCode, e.currencyDecimals.toInt(), language)}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (payouts.isNotEmpty()) {
            Text("Payout Settlements", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            payouts.forEach { p ->
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${p.payoutMethod} • ${p.status}", fontWeight = FontWeight.Bold)
                        Text("Ref: ${p.payoutReference} • ${LocaleFormatter.formatCurrencyMinor(p.amountMinor, p.currencyCode, p.currencyCode, 2, language)}")
                    }
                }
            }
        }
    }
}
