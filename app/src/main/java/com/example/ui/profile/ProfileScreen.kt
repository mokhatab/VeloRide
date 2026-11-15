package com.example.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.StringsProvider
import com.example.domain.model.AccountMode
import com.example.domain.model.DriverProfile
import com.example.domain.model.PaymentMethodRecord
import com.example.domain.model.SavedLocation
import com.example.domain.model.UserAccount
import com.example.ui.main.VeloRideViewModel

@Composable
fun ProfileScreen(
    language: AppLanguage,
    account: UserAccount,
    driverProfile: DriverProfile?,
    savedLocations: List<SavedLocation>,
    paymentMethods: List<PaymentMethodRecord>,
    viewModel: VeloRideViewModel,
    onLanguageSelected: (AppLanguage) -> Unit,
    onNeedDriverOnboarding: () -> Unit,
    onSignOut: () -> Unit
) {
    val strings = remember(language) { StringsProvider.get(language) }
    var fullName by remember(account) { mutableStateOf(account.fullName) }
    var mobile by remember(account) { mutableStateOf(account.mobileNumber) }
    var countryCode by remember(account) { mutableStateOf(account.countryCode) }
    var photoUrl by remember(account) { mutableStateOf(account.photoUrl) }
    var showAddCardDialog by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            photoUrl = uri.toString()
            viewModel.updateProfile(fullName, mobile, countryCode, language, photoUrl)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Unified User Header
        Card(shape = RoundedCornerShape(20.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier
                        .size(64.dp)
                        .testTag("pick_profile_photo_button")
                ) {
                    if (!photoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = photoUrl,
                            contentDescription = account.fullName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(CircleShape)
                        )
                    } else {
                        Icon(Icons.Default.AccountCircle, contentDescription = account.fullName, modifier = Modifier.size(56.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(account.fullName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(account.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${account.activeMode} • ★ ${account.riderRatingAvg}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Account Settings -> Switch Account Type (Unified Identity)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = strings.switchAccountType)
                    Text(strings.switchAccountType, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                val isCurrentlyRider = account.activeMode == AccountMode.RIDER.name
                val targetMode = if (isCurrentlyRider) AccountMode.DRIVER else AccountMode.RIDER
                val buttonLabel = if (isCurrentlyRider) strings.switchToDriver else strings.switchToRider

                if (driverProfile != null) {
                    Text(
                        text = "Driver Status: ${driverProfile.approvalStatus}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = { viewModel.switchAccountMode(targetMode, onNeedDriverOnboarding) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("switch_account_type_button")
                ) {
                    Text(buttonLabel, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 7-Language Switcher (Arabic RTL Default + 6 LTR Languages)
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(strings.languageLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = lang == language,
                            onClick = {
                                onLanguageSelected(lang)
                                viewModel.changeLanguage(lang)
                            },
                            label = { Text(lang.nativeLabel) },
                            modifier = Modifier.testTag("profile_lang_${lang.code}")
                        )
                    }
                }
            }
        }

        // Editable Profile Details
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(fullName, { fullName = it }, label = { Text(strings.fullName) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mobile, { mobile = it }, label = { Text(strings.mobileNumber) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(countryCode, { countryCode = it.uppercase().take(2) }, label = { Text(strings.countryLabel) }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { viewModel.updateProfile(fullName, mobile, countryCode, language, photoUrl) },
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("save_profile_button")
                ) {
                    Text(strings.save)
                }
            }
        }

        // Saved Pickup Locations
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(strings.savedLocations, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (savedLocations.isEmpty()) {
                    Text("Use the bookmark icon on the map screen to save favorite pickups.", style = MaterialTheme.typography.bodySmall)
                } else {
                    savedLocations.forEach { loc ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(loc.label, fontWeight = FontWeight.SemiBold)
                                Text(loc.address, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { viewModel.deleteSavedLocation(loc.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove saved location")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        // Tokenized Payment Methods (No raw card storage)
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.paymentMethods, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(
                        onClick = { showAddCardDialog = true },
                        modifier = Modifier.testTag("add_payment_method_button")
                    ) {
                        Icon(Icons.Default.AddCard, contentDescription = "Add tokenized payment method")
                    }
                }

                if (paymentMethods.isEmpty()) {
                    Text("No tokenized payment methods linked yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    paymentMethods.forEach { pm ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CreditCard, contentDescription = null)
                                Column {
                                    Text("${pm.brand} •••• ${pm.last4}", fontWeight = FontWeight.Bold)
                                    Text("Exp ${pm.expiryMonth}/${pm.expiryYear} • Tokenized", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sign Out
        OutlinedButton(
            onClick = onSignOut,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("sign_out_button")
        ) {
            Icon(Icons.Default.Logout, contentDescription = strings.signOut)
            Spacer(Modifier.size(8.dp))
            Text(strings.signOut, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showAddCardDialog) {
        var brand by remember { mutableStateOf("Visa") }
        var last4 by remember { mutableStateOf("4242") }
        AlertDialog(
            onDismissRequest = { showAddCardDialog = false },
            title = { Text("Link Tokenized Card Metadata") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Only card brand, last 4 digits, and Stripe token ID are persisted — never full PAN or CVV.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(brand, { brand = it }, label = { Text("Brand (Visa / Mastercard)") })
                    OutlinedTextField(last4, { last4 = it.filter(Char::isDigit).take(4) }, label = { Text("Last 4 digits") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (last4.length == 4) {
                        viewModel.addTokenizedPaymentMethod(brand, last4, 12L, 2029L)
                        showAddCardDialog = false
                    }
                }) { Text(strings.save) }
            },
            dismissButton = {
                TextButton(onClick = { showAddCardDialog = false }) { Text("Cancel") }
            }
        )
    }
}
