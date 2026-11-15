package com.example.ui.rider

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
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AirportShuttle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocaleFormatter
import com.example.core.i18n.StringsProvider
import com.example.domain.model.DriverAvailability
import com.example.domain.model.Ride
import com.example.domain.model.RideStatus
import com.example.domain.model.RiderPaymentChoice
import com.example.domain.model.SavedLocation
import com.example.domain.model.VehicleCategory
import com.example.domain.pricing.MarketCatalog
import com.example.ui.components.VeloMapCanvas
import com.example.ui.main.RiderBookingState
import com.example.ui.main.VeloRideViewModel

@Composable
fun RiderHomeScreen(
    language: AppLanguage,
    bookingState: RiderBookingState,
    onlineDrivers: List<DriverAvailability>,
    assignedDriverLocation: DriverAvailability?,
    savedLocations: List<SavedLocation>,
    activeRide: Ride?,
    viewModel: VeloRideViewModel,
    onOpenActiveActivity: () -> Unit,
    onOpenAiCopilot: () -> Unit = {}
) {
    val strings = remember(language) { StringsProvider.get(language) }
    var searchQuery by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Active ride shortcut banner if rider already has a live ride
        if (activeRide != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${strings.statusPrefix}: ${activeRide.status}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = onOpenActiveActivity,
                            modifier = Modifier.testTag("open_active_ride_button")
                        ) {
                            Text(strings.navActivity)
                        }
                    }
                    if (activeRide.status == RideStatus.SEARCHING_FOR_DRIVER.name || activeRide.status == RideStatus.OFFERED.name) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "${strings.searchingDrivers} (Round ${activeRide.dispatchRound}/3)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else if (activeRide.status == RideStatus.NO_DRIVER_AVAILABLE.name) {
                        Text(
                            text = strings.noDriverAvailable,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                        Button(
                            onClick = { viewModel.retryRideDispatch(activeRide) },
                            modifier = Modifier.testTag("retry_dispatch_home_button")
                        ) {
                            Text(strings.retryDispatch)
                        }
                    }
                }
            }
        }

        // Dynamic Market / Country / Currency Selector (auto-detected from GPS or switchable for multi-market preview)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Public,
                contentDescription = strings.countryLabel,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            MarketCatalog.supportedMarkets.forEach { market ->
                val selected = bookingState.detectedMarket.countryCode == market.countryCode &&
                    bookingState.detectedMarket.city == market.city
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.switchMarketPreset(market.countryCode, market.city) },
                    label = { Text("${market.city} • ${market.currencyCode}") },
                    modifier = Modifier.testTag("market_chip_${market.countryCode}_${market.city}")
                )
            }
        }

        // Interactive Map Canvas
        VeloMapCanvas(
            language = language,
            pickup = bookingState.pickupPoint,
            destination = bookingState.destinationPoint,
            route = bookingState.routeResult,
            onlineDrivers = onlineDrivers,
            assignedDriverLocation = assignedDriverLocation,
            onSelectPickup = { viewModel.setPickupPoint(it) },
            onSelectDestination = { viewModel.setDestinationPoint(it) }
        )

        // Address summary + Save Pickup + Place Search
        Card(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.pickupLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(bookingState.pickupAddress, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    IconButton(
                        onClick = {
                            viewModel.addSavedLocation(
                                label = bookingState.detectedMarket.city,
                                address = bookingState.pickupAddress,
                                point = bookingState.pickupPoint
                            )
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("save_pickup_location_button")
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = strings.savedLocations)
                    }
                }

                HorizontalDivider()

                Column {
                    Text(strings.destinationLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    Text(bookingState.destinationAddress, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(strings.destinationLabel) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("destination_search_input")
                    )
                    Button(
                        onClick = { viewModel.searchDestination(searchQuery) },
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("destination_search_button")
                    ) {
                        Icon(Icons.Default.Search, contentDescription = strings.destinationLabel)
                    }
                }

                FilledTonalButton(
                    onClick = onOpenAiCopilot,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("open_ai_copilot_shortcut")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = strings.navAiCopilot,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("${strings.navAiCopilot} • ${strings.liveVoiceTitle}", fontWeight = FontWeight.SemiBold)
                }

                if (bookingState.searchResults.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        bookingState.searchResults.forEach { item ->
                            OutlinedButton(
                                onClick = {
                                    viewModel.setDestinationPoint(item.point, item.label)
                                    viewModel.clearSearchResults()
                                    searchQuery = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(item.address, maxLines = 2)
                            }
                        }
                    }
                }

                if (savedLocations.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        savedLocations.forEach { saved ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    viewModel.setPickupPoint(
                                        com.example.domain.mapping.GeoPoint(saved.latitude, saved.longitude)
                                    )
                                },
                                label = { Text("${saved.label}: ${saved.address.take(18)}") }
                            )
                        }
                    }
                }
            }
        }

        // Vehicle Category Selector
        Text(
            text = strings.chooseVehicle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VehicleCategory.entries.forEach { cat ->
                val selected = bookingState.selectedCategory == cat
                val icon = when (cat) {
                    VehicleCategory.ECONOMY -> Icons.Default.DirectionsCar
                    VehicleCategory.COMFORT -> Icons.Default.LocalTaxi
                    VehicleCategory.XL -> Icons.Default.AirportShuttle
                    VehicleCategory.VIP -> Icons.Default.Star
                }
                Card(
                    onClick = { viewModel.selectVehicleCategory(cat) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.testTag("vehicle_cat_${cat.name}")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(icon, contentDescription = cat.displayName)
                        Text(cat.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(cat.multiplierHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // Fare Breakdown Card + Local Taxi Reference
        bookingState.currentQuote?.let { quote ->
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.fareEstimate, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${LocaleFormatter.formatDistanceKm(quote.estimatedDistanceKm, language)} • ${quote.estimatedDurationMin} min",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    FareLine(
                        strings.baseFare,
                        LocaleFormatter.formatCurrencyMinor(quote.baseFareMinor, quote.currencyCode, quote.currencySymbol, quote.currencyDecimals.toInt(), language)
                    )
                    FareLine(
                        strings.distanceCharge,
                        LocaleFormatter.formatCurrencyMinor(quote.distanceChargeMinor, quote.currencyCode, quote.currencySymbol, quote.currencyDecimals.toInt(), language)
                    )
                    if (quote.bookingFeeMinor + quote.surchargesMinor > 0L) {
                        FareLine(
                            strings.surcharges,
                            LocaleFormatter.formatCurrencyMinor(quote.bookingFeeMinor + quote.surchargesMinor, quote.currencyCode, quote.currencySymbol, quote.currencyDecimals.toInt(), language)
                        )
                    }
                    FareLine(
                        strings.taxes,
                        LocaleFormatter.formatCurrencyMinor(quote.taxesMinor, quote.currencyCode, quote.currencySymbol, quote.currencyDecimals.toInt(), language)
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.totalFare, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = LocaleFormatter.formatCurrencyMinor(
                                quote.finalEstimatedFareMinor,
                                quote.currencyCode,
                                quote.currencySymbol,
                                quote.currencyDecimals.toInt(),
                                language
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("fare_estimate_total")
                        )
                    }

                    Text(
                        text = "${strings.pricingVersionLabel}: v${quote.pricingVersion} • ${quote.city}, ${quote.countryCode}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    bookingState.taxiReferenceFareMinor?.let { taxiMinor ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(strings.taxiReferenceLabel, style = MaterialTheme.typography.labelMedium)
                                Text(
                                    text = LocaleFormatter.formatCurrencyMinor(
                                        taxiMinor,
                                        quote.currencyCode,
                                        quote.currencySymbol,
                                        quote.currencyDecimals.toInt(),
                                        language
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Automatic Stripe / Cash Settlement Selector
        Text(
            text = strings.paymentMethodTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RiderPaymentChoice.entries.forEach { choice ->
                val selected = bookingState.selectedPaymentChoice == choice
                val icon = when (choice) {
                    RiderPaymentChoice.CASH -> Icons.Default.Payments
                    RiderPaymentChoice.E_WALLET -> Icons.Default.AccountBalanceWallet
                    RiderPaymentChoice.CARD -> Icons.Default.CreditCard
                }
                val title = when (choice) {
                    RiderPaymentChoice.CASH -> strings.paymentCash
                    RiderPaymentChoice.E_WALLET -> strings.paymentEWallet
                    RiderPaymentChoice.CARD -> strings.paymentCard
                }
                val subtitle = when (choice) {
                    RiderPaymentChoice.CASH -> strings.paymentCashHint
                    RiderPaymentChoice.E_WALLET -> strings.paymentEWalletHint
                    RiderPaymentChoice.CARD -> strings.stripeNotConfigured.takeIf { !bookingState.stripeAutoConfig.stripeGatewayLive } ?: "Stripe"
                }
                Card(
                    onClick = { viewModel.selectPaymentChoice(choice) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.testTag("payment_choice_${choice.name}")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(icon, contentDescription = title)
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (bookingState.stripeAutoConfig.summaryMessage.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (bookingState.selectedPaymentChoice == RiderPaymentChoice.CASH) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stripe_auto_config_summary")
            ) {
                Text(
                    text = bookingState.stripeAutoConfig.summaryMessage,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // Confirm Ride Button
        Button(
            onClick = { viewModel.confirmRideAndStartDispatch() },
            enabled = bookingState.currentQuote != null && !bookingState.isCalculatingRoute && !bookingState.isDispatching,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("confirm_ride_button"),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            if (bookingState.isCalculatingRoute || bookingState.isDispatching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(strings.confirmRide, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun FareLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
