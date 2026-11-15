package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocaleFormatter
import com.example.core.i18n.StringsProvider
import com.example.domain.model.AccountMode
import com.example.domain.model.FeedbackTag
import com.example.domain.model.ProfileRatingSummary
import com.example.domain.model.RatingRecord
import com.example.domain.model.Ride
import java.util.Locale

private val AmberStar = Color(0xFFF59E0B)

fun FeedbackTag.localizedLabel(language: AppLanguage): String = when (language) {
    AppLanguage.ARABIC -> when (this) {
        FeedbackTag.CLEAN_VEHICLE -> "سيارة نظيفة"
        FeedbackTag.SMOOTH_DRIVE -> "قيادة هادئة"
        FeedbackTag.POLITE_DRIVER -> "سائق مهذب"
        FeedbackTag.ON_TIME_PICKUP -> "وصول في الموعد"
        FeedbackTag.SAFE_ROUTE -> "مسار آمن"
        FeedbackTag.FRIENDLY_RIDER -> "راكب لطيف"
        FeedbackTag.PUNCTUAL_RIDER -> "دقيق في الموعد"
        FeedbackTag.RESPECTFUL -> "محترم ومتعاون"
        FeedbackTag.CLEAR_PICKUP -> "نقطة انطلاق واضحة"
        FeedbackTag.GREAT_TIPPER -> "تقدير ممتاز"
    }
    AppLanguage.GERMAN -> when (this) {
        FeedbackTag.CLEAN_VEHICLE -> "Sauberes Auto"
        FeedbackTag.SMOOTH_DRIVE -> "Ruhige Fahrt"
        FeedbackTag.POLITE_DRIVER -> "Höflich"
        FeedbackTag.ON_TIME_PICKUP -> "Pünktlich"
        FeedbackTag.SAFE_ROUTE -> "Sichere Route"
        FeedbackTag.FRIENDLY_RIDER -> "Freundlich"
        FeedbackTag.PUNCTUAL_RIDER -> "Rechtzeitig da"
        FeedbackTag.RESPECTFUL -> "Respektvoll"
        FeedbackTag.CLEAR_PICKUP -> "Klarer Abholort"
        FeedbackTag.GREAT_TIPPER -> "Großzügig"
    }
    AppLanguage.FRENCH -> when (this) {
        FeedbackTag.CLEAN_VEHICLE -> "Véhicule propre"
        FeedbackTag.SMOOTH_DRIVE -> "Conduite fluide"
        FeedbackTag.POLITE_DRIVER -> "Chauffeur poli"
        FeedbackTag.ON_TIME_PICKUP -> "À l'heure"
        FeedbackTag.SAFE_ROUTE -> "Trajet sûr"
        FeedbackTag.FRIENDLY_RIDER -> "Passager aimable"
        FeedbackTag.PUNCTUAL_RIDER -> "Ponctuel"
        FeedbackTag.RESPECTFUL -> "Respectueux"
        FeedbackTag.CLEAR_PICKUP -> "Point clair"
        FeedbackTag.GREAT_TIPPER -> "Généreux"
    }
    AppLanguage.SPANISH -> when (this) {
        FeedbackTag.CLEAN_VEHICLE -> "Auto limpio"
        FeedbackTag.SMOOTH_DRIVE -> "Conducción suave"
        FeedbackTag.POLITE_DRIVER -> "Muy amable"
        FeedbackTag.ON_TIME_PICKUP -> "Puntual"
        FeedbackTag.SAFE_ROUTE -> "Ruta segura"
        FeedbackTag.FRIENDLY_RIDER -> "Pasajero amable"
        FeedbackTag.PUNCTUAL_RIDER -> "Puntual"
        FeedbackTag.RESPECTFUL -> "Respetuoso"
        FeedbackTag.CLEAR_PICKUP -> "Punto claro"
        FeedbackTag.GREAT_TIPPER -> "Gran propina"
    }
    AppLanguage.ITALIAN -> when (this) {
        FeedbackTag.CLEAN_VEHICLE -> "Auto pulita"
        FeedbackTag.SMOOTH_DRIVE -> "Guida fluida"
        FeedbackTag.POLITE_DRIVER -> "Molto cortese"
        FeedbackTag.ON_TIME_PICKUP -> "Puntuale"
        FeedbackTag.SAFE_ROUTE -> "Percorso sicuro"
        FeedbackTag.FRIENDLY_RIDER -> "Cortese"
        FeedbackTag.PUNCTUAL_RIDER -> "Puntuale"
        FeedbackTag.RESPECTFUL -> "Rispettoso"
        FeedbackTag.CLEAR_PICKUP -> "Ritiro chiaro"
        FeedbackTag.GREAT_TIPPER -> "Generoso"
    }
    AppLanguage.RUSSIAN -> when (this) {
        FeedbackTag.CLEAN_VEHICLE -> "Чистый салон"
        FeedbackTag.SMOOTH_DRIVE -> "Плавная езда"
        FeedbackTag.POLITE_DRIVER -> "Вежливый"
        FeedbackTag.ON_TIME_PICKUP -> "Вовремя"
        FeedbackTag.SAFE_ROUTE -> "Безопасно"
        FeedbackTag.FRIENDLY_RIDER -> "Дружелюбный"
        FeedbackTag.PUNCTUAL_RIDER -> "Пунктуальный"
        FeedbackTag.RESPECTFUL -> "Вежливый"
        FeedbackTag.CLEAR_PICKUP -> "Точная посадка"
        FeedbackTag.GREAT_TIPPER -> "Щедрый"
    }
    AppLanguage.ENGLISH -> when (this) {
        FeedbackTag.CLEAN_VEHICLE -> "Clean Car"
        FeedbackTag.SMOOTH_DRIVE -> "Smooth Drive"
        FeedbackTag.POLITE_DRIVER -> "Polite & Helpful"
        FeedbackTag.ON_TIME_PICKUP -> "On Time"
        FeedbackTag.SAFE_ROUTE -> "Safe Route"
        FeedbackTag.FRIENDLY_RIDER -> "Friendly"
        FeedbackTag.PUNCTUAL_RIDER -> "On Time"
        FeedbackTag.RESPECTFUL -> "Respectful"
        FeedbackTag.CLEAR_PICKUP -> "Clear Pickup"
        FeedbackTag.GREAT_TIPPER -> "Great Attitude"
    }
}

fun formatAverageRating(avg: Double): String = String.format(Locale.US, "%.2f", avg).trimEnd('0').let {
    if (it.endsWith(".")) "${it}0" else it
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostTripRatingDialog(
    language: AppLanguage,
    ride: Ride,
    raterRole: String,
    counterpartyName: String,
    isSubmitting: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (stars: Long, comment: String, tags: List<String>) -> Unit
) {
    val strings = remember(language) { StringsProvider.get(language) }
    val targetRole = if (raterRole.equals("RIDER", ignoreCase = true)) AccountMode.DRIVER else AccountMode.RIDER
    val availableTags = remember(targetRole) { FeedbackTag.forTargetRole(targetRole) }

    var selectedStars by remember(ride.id, raterRole) { mutableLongStateOf(5L) }
    var selectedTagKeys by remember(ride.id, raterRole) { mutableStateOf(setOf<String>()) }
    var comment by remember(ride.id, raterRole) { mutableStateOf("") }

    val starCaption = when (selectedStars) {
        1L -> strings.ratingPoor
        2L -> strings.ratingFair
        3L -> strings.ratingGood
        4L -> strings.ratingGreat
        else -> strings.ratingExcellent
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .testTag("post_trip_rating_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            imageVector = if (targetRole == AccountMode.DRIVER) Icons.Default.DirectionsCar else Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(10.dp).size(26.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (targetRole == AccountMode.DRIVER) strings.rateDriver else strings.ratePassenger,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = counterpartyName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Route & Fare summary pill
                val fareMinor = if (ride.finalFareMinor > 0L) ride.finalFareMinor else ride.estimatedFareMinor
                val formattedFare = LocaleFormatter.formatCurrencyMinor(
                    fareMinor,
                    ride.currencyCode,
                    ride.currencySymbol,
                    ride.currencyDecimals.toInt(),
                    language
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${ride.pickupAddress} → ${ride.destinationAddress}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${strings.tripFareLabel}: $formattedFare",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Text(
                    text = strings.rateTripTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                // Interactive 5-Star Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    (1L..5L).forEach { star ->
                        val isFilled = star <= selectedStars
                        val animatedScale by animateFloatAsState(
                            targetValue = if (isFilled) 1.12f else 0.94f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "star_scale_$star"
                        )
                        IconButton(
                            onClick = { selectedStars = star },
                            modifier = Modifier
                                .size(52.dp)
                                .testTag("rating_star_$star")
                        ) {
                            Icon(
                                imageVector = if (isFilled) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "$star stars",
                                tint = if (isFilled) AmberStar else MaterialTheme.colorScheme.outline,
                                modifier = Modifier
                                    .size(38.dp)
                                    .scale(animatedScale)
                            )
                        }
                    }
                }

                Text(
                    text = "$selectedStars / 5 • $starCaption",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                // Quick Feedback Chips
                Text(
                    text = strings.quickTagsLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableTags.forEach { tag ->
                        val selected = tag.key in selectedTagKeys
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedTagKeys = if (selected) {
                                    selectedTagKeys - tag.key
                                } else {
                                    selectedTagKeys + tag.key
                                }
                            },
                            label = { Text(tag.localizedLabel(language)) },
                            modifier = Modifier.testTag("rating_tag_${tag.key}")
                        )
                    }
                }

                // Written Feedback
                OutlinedTextField(
                    value = comment,
                    onValueChange = { if (it.length <= 400) comment = it },
                    label = { Text(strings.feedbackOptionalHint) },
                    minLines = 2,
                    maxLines = 4,
                    supportingText = {
                        Text("${comment.length}/400", style = MaterialTheme.typography.labelSmall)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rating_comment_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isSubmitting,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("dismiss_rating_button")
                    ) {
                        Text(strings.skipLabel)
                    }
                    Button(
                        onClick = {
                            onSubmit(selectedStars, comment, selectedTagKeys.toList())
                        },
                        enabled = !isSubmitting,
                        modifier = Modifier
                            .weight(1.6f)
                            .height(48.dp)
                            .testTag("submit_rating_button")
                    ) {
                        Text(strings.submitFeedback, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileRatingSummaryCard(
    language: AppLanguage,
    riderSummary: ProfileRatingSummary,
    driverSummary: ProfileRatingSummary,
    showDriverSummary: Boolean,
    defaultToDriverTab: Boolean
) {
    val strings = remember(language) { StringsProvider.get(language) }
    var inspectDriverTab by remember(defaultToDriverTab, showDriverSummary) {
        mutableStateOf(defaultToDriverTab && showDriverSummary)
    }
    val activeSummary = if (inspectDriverTab && showDriverSummary) driverSummary else riderSummary

    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_rating_summary_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        imageVector = Icons.Default.Verified,
                        contentDescription = strings.ratingSummaryTitle,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = strings.ratingSummaryTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Dual Role Average Summary Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RatingScoreTile(
                    label = strings.riderRatingLabel,
                    summary = riderSummary,
                    reviewsLabel = strings.reviewsCountLabel,
                    selected = !inspectDriverTab || !showDriverSummary,
                    onClick = { inspectDriverTab = false },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("profile_rider_rating_tile")
                )
                if (showDriverSummary) {
                    RatingScoreTile(
                        label = strings.driverRatingLabel,
                        summary = driverSummary,
                        reviewsLabel = strings.reviewsCountLabel,
                        selected = inspectDriverTab,
                        onClick = { inspectDriverTab = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("profile_driver_rating_tile")
                    )
                }
            }

            if (activeSummary.ratingCount == 0 && activeSummary.recentFeedback.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = strings.noRatingsYetTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = strings.noRatingsYetHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Star Breakdown Bars (5 -> 1)
                val totalForBars = activeSummary.starCounts.values.sum().coerceAtLeast(1)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (5 downTo 1).forEach { star ->
                        val count = activeSummary.starCounts[star] ?: 0
                        val fraction = (count.toFloat() / totalForBars.toFloat()).coerceIn(0f, 1f)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "$star★",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(28.dp)
                            )
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(8.dp)
                            )
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp)
                            )
                        }
                    }
                }

                // Top Feedback Tags
                if (activeSummary.topTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        activeSummary.topTags.forEach { (key, count) ->
                            val tagLabel = FeedbackTag.entries
                                .firstOrNull { it.key == key }
                                ?.localizedLabel(language)
                                ?: key.replace("_", " ")
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "$tagLabel • $count",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                // Recent Written Reviews
                if (activeSummary.recentFeedback.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = strings.recentFeedbackTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        activeSummary.recentFeedback.take(4).forEach { item ->
                            FeedbackReviewItem(language = language, rating = item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingScoreTile(
    label: String,
    summary: ProfileRatingSummary,
    reviewsLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = label,
                    tint = AmberStar,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = formatAverageRating(summary.averageRating),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Text(
                text = "${summary.ratingCount} $reviewsLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackReviewItem(
    language: AppLanguage,
    rating: RatingRecord
) {
    val dateLabel = rating.createdAt?.seconds?.let { sec ->
        if (sec > 0L) LocaleFormatter.formatDateTime(sec * 1000L, language) else ""
    }.orEmpty()
    val author = rating.raterName.ifBlank {
        if (rating.fromRole.equals("DRIVER", ignoreCase = true)) "Driver" else "Passenger"
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("feedback_item_${rating.id}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = author,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    (1L..5L).forEach { s ->
                        Icon(
                            imageVector = if (s <= rating.stars) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            tint = if (s <= rating.stars) AmberStar else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            if (rating.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rating.tags.forEach { key ->
                        val label = FeedbackTag.entries
                            .firstOrNull { it.key == key }
                            ?.localizedLabel(language)
                            ?: key.replace("_", " ")
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
            if (rating.comment.isNotBlank()) {
                Text(
                    text = rating.comment,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (dateLabel.isNotBlank()) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
