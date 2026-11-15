package com.example.domain.pricing

import com.example.domain.model.FareQuote
import com.example.domain.model.PricingPolicy
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

data class FareCalculationInput(
    val riderId: String,
    val pickupAddress: String,
    val pickupLat: Double,
    val pickupLng: Double,
    val destinationAddress: String,
    val destinationLat: Double,
    val destinationLng: Double,
    val routeDistanceKm: Double,
    val routeDurationMin: Long,
    val waitingMinutes: Long = 0L,
    val isAirportTrip: Boolean = false,
    val isNightHours: Boolean = false,
    val isHighTraffic: Boolean = false,
    val discountMinor: Long = 0L,
    val nowMs: Long = System.currentTimeMillis()
)

data class EarningsBreakdown(
    val grossFareMinor: Long,
    val platformCommissionMinor: Long,
    val taxesMinor: Long,
    val netDriverEarningsMinor: Long
)

data class CurrencyConversionSnapshot(
    val originalCurrency: String,
    val originalAmountMinor: Long,
    val settlementCurrency: String,
    val settlementAmountMinor: Long,
    val conversionRate: Double,
    val conversionTimestampMs: Long,
    val conversionSource: String
)

/**
 * Deterministic financial pricing engine using BigDecimal and integer minor units.
 * Zero floating-point arithmetic is used on monetary values.
 */
object PricingEngine {

    private const val QUOTE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    fun calculateQuote(policy: PricingPolicy, input: FareCalculationInput): FareQuote {
        val routeDist = BigDecimal.valueOf(input.routeDistanceKm.coerceAtLeast(0.0))
        val includedDist = BigDecimal.valueOf(policy.includedDistanceKm.coerceAtLeast(0.0))
        val billableDist = routeDist.subtract(includedDist).max(BigDecimal.ZERO)

        val distanceChargeMinor = billableDist
            .multiply(BigDecimal.valueOf(policy.pricePerKmMinor))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()

        val waitingChargeMinor = BigDecimal.valueOf(input.waitingMinutes.coerceAtLeast(0L))
            .multiply(BigDecimal.valueOf(policy.waitingChargePerMinMinor))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()

        var surchargesMinor = 0L
        if (input.isAirportTrip) surchargesMinor += policy.airportSurchargeMinor
        if (input.isNightHours) surchargesMinor += policy.nightSurchargeMinor
        if (input.isHighTraffic) surchargesMinor += policy.trafficSurchargeMinor

        val subtotalMinor = policy.baseFareMinor +
            distanceChargeMinor +
            waitingChargeMinor +
            policy.bookingFeeMinor +
            surchargesMinor

        val multiplier = BigDecimal.valueOf(policy.surgeMultiplier.coerceAtLeast(1.0))
        val fareBeforeDiscountsMinor = BigDecimal.valueOf(subtotalMinor)
            .multiply(multiplier)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()

        val taxRate = BigDecimal.valueOf(policy.taxPercentage.coerceAtLeast(0.0))
            .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)

        val taxesMinor = BigDecimal.valueOf(fareBeforeDiscountsMinor)
            .multiply(taxRate)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()

        val appliedDiscount = input.discountMinor.coerceAtLeast(0L).coerceAtMost(fareBeforeDiscountsMinor)
        val rawTotalMinor = (fareBeforeDiscountsMinor + taxesMinor - appliedDiscount).coerceAtLeast(0L)
        val finalFareMinor = maxOf(policy.minimumFareMinor, rawTotalMinor)

        return FareQuote(
            id = "quote_${UUID.randomUUID().toString().replace("-", "").take(16)}",
            riderId = input.riderId,
            countryCode = policy.countryCode,
            countryName = policy.countryName,
            city = policy.city,
            currencyCode = policy.currencyCode,
            currencySymbol = policy.currencySymbol,
            currencyDecimals = policy.currencyDecimals,
            pickupAddress = input.pickupAddress,
            pickupLat = input.pickupLat,
            pickupLng = input.pickupLng,
            destinationAddress = input.destinationAddress,
            destinationLat = input.destinationLat,
            destinationLng = input.destinationLng,
            serviceCategory = policy.serviceCategory,
            estimatedDistanceKm = input.routeDistanceKm,
            estimatedDurationMin = input.routeDurationMin,
            baseFareMinor = policy.baseFareMinor,
            distanceChargeMinor = distanceChargeMinor,
            waitingChargeMinor = waitingChargeMinor,
            bookingFeeMinor = policy.bookingFeeMinor,
            surchargesMinor = surchargesMinor,
            taxesMinor = taxesMinor,
            discountsMinor = appliedDiscount,
            finalEstimatedFareMinor = finalFareMinor,
            pricingVersion = policy.pricingVersion,
            pricingPolicyId = policy.id,
            createdAtMs = input.nowMs,
            expiresAtMs = input.nowMs + QUOTE_TTL_MS
        )
    }

    fun calculateFinalFareFromPolicy(
        policy: PricingPolicy,
        actualDistanceKm: Double,
        waitingMinutes: Long,
        discountMinor: Long = 0L,
        isAirportTrip: Boolean = false
    ): Long {
        val quote = calculateQuote(
            policy = policy,
            input = FareCalculationInput(
                riderId = "settlement",
                pickupAddress = "settlement",
                pickupLat = 0.0,
                pickupLng = 0.0,
                destinationAddress = "settlement",
                destinationLat = 0.0,
                destinationLng = 0.0,
                routeDistanceKm = actualDistanceKm,
                routeDurationMin = 0L,
                waitingMinutes = waitingMinutes,
                isAirportTrip = isAirportTrip,
                discountMinor = discountMinor
            )
        )
        return quote.finalEstimatedFareMinor
    }

    fun calculateDriverEarnings(
        grossFareMinor: Long,
        taxesMinor: Long,
        commissionPercent: Double,
        adjustmentsMinor: Long = 0L,
        refundsMinor: Long = 0L
    ): EarningsBreakdown {
        val commissionable = (grossFareMinor - taxesMinor).coerceAtLeast(0L)
        val commissionMinor = BigDecimal.valueOf(commissionable)
            .multiply(BigDecimal.valueOf(commissionPercent.coerceIn(0.0, 50.0)))
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
            .toLong()
        val net = (grossFareMinor - commissionMinor - taxesMinor + adjustmentsMinor - refundsMinor)
            .coerceAtLeast(0L)
        return EarningsBreakdown(
            grossFareMinor = grossFareMinor,
            platformCommissionMinor = commissionMinor,
            taxesMinor = taxesMinor,
            netDriverEarningsMinor = net
        )
    }

    fun convertForSettlement(
        amountMinor: Long,
        originalCurrency: String,
        settlementCurrency: String,
        rate: Double,
        source: String,
        timestampMs: Long = System.currentTimeMillis()
    ): CurrencyConversionSnapshot {
        val convertedMinor = if (originalCurrency.equals(settlementCurrency, true)) {
            amountMinor
        } else {
            BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(rate.coerceAtLeast(0.0001)))
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
        }
        return CurrencyConversionSnapshot(
            originalCurrency = originalCurrency,
            originalAmountMinor = amountMinor,
            settlementCurrency = settlementCurrency,
            settlementAmountMinor = convertedMinor,
            conversionRate = if (originalCurrency.equals(settlementCurrency, true)) 1.0 else rate,
            conversionTimestampMs = timestampMs,
            conversionSource = source
        )
    }
}
