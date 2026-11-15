package com.example.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocaleFormatter
import com.example.core.i18n.StringsProvider
import com.example.domain.model.PaymentRecord
import com.example.domain.model.RatingRecord
import com.example.domain.model.Ride
import com.example.domain.model.RiderPaymentChoice
import com.example.domain.model.TripHistoryEntry
import com.example.domain.model.TripHistoryFilter
import com.example.domain.model.TripHistoryMapper
import com.example.domain.model.TripHistoryRole
import com.example.ui.main.TripHistoryUiState

@Composable
fun TripHistoryScreen(
    language: AppLanguage,
    initialRole: TripHistoryRole,
    showRoleSwitcher: Boolean,
    riderHistoryState: TripHistoryUiState,
    driverHistoryState: TripHistoryUiState,
    payments: List<PaymentRecord>,
    ratingsGiven: List<RatingRecord> = emptyList(),
    onRateTrip: ((Ride, TripHistoryRole) -> Unit)? = null,
    onBack: (() -> Unit)? = null
) {
    val strings = remember(language) { StringsProvider.get(language) }
    var selectedRole by remember(initialRole) { mutableStateOf(initialRole) }
    var selectedFilter by remember { mutableStateOf(TripHistoryFilter.ALL) }
    var inspectedEntry by remember { mutableStateOf<TripHistoryEntry?>(null) }

    val activeState = when (selectedRole) {
        TripHistoryRole.RIDER -> riderHistoryState
        TripHistoryRole.DRIVER -> driverHistoryState
    }

    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
        ) {
            if (onBack != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("trip_history_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.tripHistoryTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = strings.tripHistorySubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (showRoleSwitcher) {
                TabRow(selectedTabIndex = if (selectedRole == TripHistoryRole.RIDER) 0 else 1) {
                    Tab(
                        selected = selectedRole == TripHistoryRole.RIDER,
                        onClick = { selectedRole = TripHistoryRole.RIDER },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        text = { Text(strings.riderTripsTab) },
                        modifier = Modifier.testTag("history_role_rider_tab")
                    )
                    Tab(
                        selected = selectedRole == TripHistoryRole.DRIVER,
                        onClick = { selectedRole = TripHistoryRole.DRIVER },
                        icon = { Icon(Icons.Default.DirectionsCar, contentDescription = null) },
                        text = { Text(strings.driverTripsTab) },
                        modifier = Modifier.testTag("history_role_driver_tab")
                    )
                }
            }

            TripHistoryBody(
                language = language,
                role = selectedRole,
                filter = selectedFilter,
                onFilterChanged = { selectedFilter = it },
                state = activeState,
                payments = payments,
                ratingsGiven = ratingsGiven,
                onRateTrip = onRateTrip,
                onSelectEntry = { inspectedEntry = it }
            )
        }
    }

    inspectedEntry?.let { entry ->
        val paymentForRide = payments.firstOrNull { it.rideId == entry.ride.id }
        val raterRoleStr = if (selectedRole == TripHistoryRole.DRIVER) "DRIVER" else "RIDER"
        val givenRating = ratingsGiven.firstOrNull {
            it.rideId == entry.ride.id && it.fromRole.equals(raterRoleStr, ignoreCase = true)
        }
        TripHistoryDetailDialog(
            language = language,
            entry = entry,
            role = selectedRole,
            payment = paymentForRide,
            givenRating = givenRating,
            onRate = onRateTrip?.let { cb ->
                {
                    inspectedEntry = null
                    cb(entry.ride, selectedRole)
                }
            },
            onDismiss = { inspectedEntry = null }
        )
    }
}

@Composable
fun TripHistoryBody(
    language: AppLanguage,
    role: TripHistoryRole,
    filter: TripHistoryFilter,
    onFilterChanged: (TripHistoryFilter) -> Unit,
    state: TripHistoryUiState,
    payments: List<PaymentRecord>,
    ratingsGiven: List<RatingRecord> = emptyList(),
    onRateTrip: ((Ride, TripHistoryRole) -> Unit)? = null,
    onSelectEntry: (TripHistoryEntry) -> Unit
) {
    val strings = remember(language) { StringsProvider.get(language) }

    when (state) {
        is TripHistoryUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.testTag("trip_history_loading"))
            }
        }
        is TripHistoryUiState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }
        }
        is TripHistoryUiState.Success -> {
            val allRoleEntries = remember(state.rides, role) {
                TripHistoryMapper.buildEntries(state.rides, role, TripHistoryFilter.ALL)
            }
            val filteredEntries = remember(state.rides, role, filter) {
                TripHistoryMapper.buildEntries(state.rides, role, filter)
            }
            val summary = remember(allRoleEntries) {
                TripHistoryMapper.summarize(allRoleEntries)
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("trip_history_list"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Summary Banner
                item {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.colorScheme.secondaryContainer
                                        )
                                    )
                                )
                                .padding(18.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = strings.tripHistoryTitle,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = if (role == TripHistoryRole.DRIVER) strings.driverTripsTab else strings.riderTripsTab,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    SummaryMetricColumn(
                                        label = strings.totalTripsLabel,
                                        value = summary.totalTrips.toString()
                                    )
                                    SummaryMetricColumn(
                                        label = strings.completedTripsLabel,
                                        value = summary.completedTrips.toString()
                                    )
                                    SummaryMetricColumn(
                                        label = strings.tripFareLabel,
                                        value = LocaleFormatter.formatCompactCurrency(
                                            summary.totalFareMinor,
                                            summary.currencySymbol,
                                            summary.currencyDecimals,
                                            language
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Filter Chips
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = filter == TripHistoryFilter.ALL,
                            onClick = { onFilterChanged(TripHistoryFilter.ALL) },
                            label = { Text(strings.filterAll) },
                            modifier = Modifier.testTag("history_filter_all")
                        )
                        FilterChip(
                            selected = filter == TripHistoryFilter.COMPLETED,
                            onClick = { onFilterChanged(TripHistoryFilter.COMPLETED) },
                            label = { Text(strings.filterCompleted) },
                            modifier = Modifier.testTag("history_filter_completed")
                        )
                        FilterChip(
                            selected = filter == TripHistoryFilter.CANCELLED,
                            onClick = { onFilterChanged(TripHistoryFilter.CANCELLED) },
                            label = { Text(strings.filterCancelled) },
                            modifier = Modifier.testTag("history_filter_cancelled")
                        )
                    }
                }

                if (filteredEntries.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("trip_history_empty")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Route,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = strings.noPastTripsTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = strings.noPastTripsHint,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(filteredEntries, key = { it.ride.id }) { entry ->
                        val paymentForRide = payments.firstOrNull { it.rideId == entry.ride.id }
                        val raterRoleStr = if (role == TripHistoryRole.DRIVER) "DRIVER" else "RIDER"
                        val givenRating = ratingsGiven.firstOrNull {
                            it.rideId == entry.ride.id && it.fromRole.equals(raterRoleStr, ignoreCase = true)
                        }
                        TripHistoryCard(
                            language = language,
                            entry = entry,
                            role = role,
                            payment = paymentForRide,
                            givenRating = givenRating,
                            onRateClick = onRateTrip?.let { cb -> { cb(entry.ride, role) } },
                            onClick = { onSelectEntry(entry) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SummaryMetricColumn(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun TripHistoryCard(
    language: AppLanguage,
    entry: TripHistoryEntry,
    role: TripHistoryRole,
    payment: PaymentRecord?,
    givenRating: RatingRecord? = null,
    onRateClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val strings = remember(language) { StringsProvider.get(language) }
    val ride = entry.ride

    val formattedDate = remember(entry.timestampMs, language) {
        if (entry.timestampMs > 0L) {
            LocaleFormatter.formatDateTime(entry.timestampMs, language)
        } else {
            "—"
        }
    }
    val formattedFare = remember(entry.fareMinor, ride.currencyCode, ride.currencySymbol, ride.currencyDecimals, language) {
        LocaleFormatter.formatCurrencyMinor(
            entry.fareMinor,
            ride.currencyCode,
            ride.currencySymbol,
            ride.currencyDecimals.toInt(),
            language
        )
    }
    val formattedDistance = remember(entry.distanceKm, language) {
        LocaleFormatter.formatDistanceKm(entry.distanceKm, language)
    }
    val settlementLabel = when (entry.paymentChoice) {
        RiderPaymentChoice.CASH -> strings.paymentCash
        RiderPaymentChoice.E_WALLET -> strings.paymentEWallet
        RiderPaymentChoice.CARD -> strings.paymentCard
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_ride_card_${ride.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Row: Date + Status + Fare
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = strings.tripDateLabel,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("history_ride_date_${ride.id}")
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (entry.isCompleted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ) {
                    Text(
                        text = ride.status,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (entry.isCompleted) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Visual Route Block (Origin -> Destination)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .weight(1f)
                            .padding(vertical = 2.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = strings.destinationLabel,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("history_ride_route_${ride.id}"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column {
                        Text(
                            text = strings.pickupLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = ride.pickupAddress,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column {
                        Text(
                            text = strings.destinationLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = ride.destinationAddress,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider()

            // Bottom Row: Counterparty + Metadata + Fare
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val counterparty = if (role == TripHistoryRole.DRIVER) {
                        ride.riderName.ifBlank { "Rider" }
                    } else {
                        ride.driverName?.takeIf { it.isNotBlank() }?.let { dName ->
                            val plate = ride.driverPlate?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                            "$dName$plate"
                        } ?: ride.vehicleCategory
                    }
                    Text(
                        text = "$counterparty • $formattedDistance • ${ride.estimatedDurationMin} min",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$settlementLabel • ${ride.city}" +
                            (payment?.receiptNumber?.let { " • $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = formattedFare,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("history_ride_fare_${ride.id}")
                )
            }

            if (entry.isCompleted) {
                val alreadyRatedFlag = if (role == TripHistoryRole.DRIVER) ride.driverRated else ride.riderRated
                val isRated = alreadyRatedFlag || givenRating != null
                val counterpartyId = if (role == TripHistoryRole.DRIVER) ride.riderId else ride.driverId
                if (isRated) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.testTag("history_rated_badge_${ride.id}")
                    ) {
                        Text(
                            text = if (givenRating != null) {
                                "${strings.alreadyRatedLabel} ★ ${givenRating.stars}"
                            } else {
                                "★ ${strings.alreadyRatedLabel}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                } else if (counterpartyId.isNotBlank() && onRateClick != null) {
                    TextButton(
                        onClick = onRateClick,
                        modifier = Modifier.testTag("history_rate_ride_${ride.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (role == TripHistoryRole.DRIVER) strings.ratePassenger else strings.rateDriver,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TripHistoryDetailDialog(
    language: AppLanguage,
    entry: TripHistoryEntry,
    role: TripHistoryRole,
    payment: PaymentRecord?,
    givenRating: RatingRecord? = null,
    onRate: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val strings = remember(language) { StringsProvider.get(language) }
    val ride = entry.ride
    val formattedDate = if (entry.timestampMs > 0L) {
        LocaleFormatter.formatDateTime(entry.timestampMs, language)
    } else "—"
    val formattedFare = LocaleFormatter.formatCurrencyMinor(
        entry.fareMinor,
        ride.currencyCode,
        ride.currencySymbol,
        ride.currencyDecimals.toInt(),
        language
    )
    val settlementLabel = when (entry.paymentChoice) {
        RiderPaymentChoice.CASH -> strings.paymentCash
        RiderPaymentChoice.E_WALLET -> strings.paymentEWallet
        RiderPaymentChoice.CARD -> strings.paymentCard
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = strings.tripDetailsTitle) },
        title = { Text(strings.tripDetailsTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(strings.statusPrefix, ride.status)
                DetailRow(strings.tripDateLabel, formattedDate)
                DetailRow(strings.pickupLabel, ride.pickupAddress)
                DetailRow(strings.destinationLabel, ride.destinationAddress)
                DetailRow(
                    strings.tripRouteLabel,
                    "${LocaleFormatter.formatDistanceKm(entry.distanceKm, language)} • ${ride.estimatedDurationMin} min"
                )
                DetailRow(strings.chooseVehicle, ride.vehicleCategory)
                if (role == TripHistoryRole.DRIVER) {
                    DetailRow("Rider", ride.riderName.ifBlank { "Rider" })
                } else if (!ride.driverName.isNullOrBlank()) {
                    DetailRow(
                        "Driver",
                        "${ride.driverName} • ${ride.driverVehicle.orEmpty()} (${ride.driverPlate.orEmpty()})"
                    )
                }
                DetailRow(strings.tripFareLabel, formattedFare)
                DetailRow(strings.paymentMethodTitle, "$settlementLabel (${ride.paymentStatus})")
                if (payment != null) {
                    DetailRow("Receipt", "${payment.receiptNumber} (${payment.status})")
                }
                if (!ride.cancellationReason.isNullOrBlank()) {
                    DetailRow("Reason", "${ride.cancellationInitiator.orEmpty()}: ${ride.cancellationReason}")
                }
            }
        },
        confirmButton = {
            val alreadyRatedFlag = if (role == TripHistoryRole.DRIVER) ride.driverRated else ride.riderRated
            val isRated = alreadyRatedFlag || givenRating != null
            val counterpartyId = if (role == TripHistoryRole.DRIVER) ride.riderId else ride.driverId
            if (entry.isCompleted && !isRated && counterpartyId.isNotBlank() && onRate != null) {
                Button(
                    onClick = onRate,
                    modifier = Modifier.testTag("detail_rate_ride_button")
                ) {
                    Text(if (role == TripHistoryRole.DRIVER) strings.ratePassenger else strings.rateDriver)
                }
            } else {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_trip_details_button")
                ) {
                    Text("OK")
                }
            }
        },
        dismissButton = {
            val alreadyRatedFlag = if (role == TripHistoryRole.DRIVER) ride.driverRated else ride.riderRated
            val isRated = alreadyRatedFlag || givenRating != null
            val counterpartyId = if (role == TripHistoryRole.DRIVER) ride.riderId else ride.driverId
            if (entry.isCompleted && !isRated && counterpartyId.isNotBlank() && onRate != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_trip_details_button")
                ) {
                    Text("OK")
                }
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
