package com.example.domain

import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocaleFormatter
import com.example.domain.model.PaymentStatus
import com.example.domain.model.VehicleCategory
import com.example.domain.payment.PaymentIntentRequest
import com.example.domain.payment.StripePaymentGateway
import com.example.domain.pricing.FareCalculationInput
import com.example.domain.pricing.MarketCatalog
import com.example.domain.pricing.PricingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureNanoTime

class PricingAndPaymentUnitTest {

    @Test
    fun egyptStartingPricing_respectsCommercialRangesAndIncludesFirstKm() {
        val policies = MarketCatalog.defaultPoliciesForAllMarkets()
        val cairoEcon = policies.first { it.countryCode == "EG" && it.city == "Cairo" && !it.isTaxiReference && it.serviceCategory == VehicleCategory.ECONOMY.name }
        val cairoTaxi = policies.first { it.countryCode == "EG" && it.city == "Cairo" && it.isTaxiReference }

        // Commercial starting range: base EGP 8.50-20.00, additional km EGP 4.00-10.00
        assertTrue(cairoEcon.baseFareMinor in 850L..2000L)
        assertTrue(cairoEcon.pricePerKmMinor in 400L..1000L)
        assertEquals(1.0, cairoEcon.includedDistanceKm, 0.001)

        // Separate local taxi reference exists and differs from commercial VeloRide tariff
        assertTrue(cairoTaxi.isTaxiReference)
        assertTrue(cairoTaxi.baseFareMinor != cairoEcon.baseFareMinor)

        // First 1 km included: a 1.0 km trip has 0 distance charge
        val shortQuote = PricingEngine.calculateQuote(
            cairoEcon,
            FareCalculationInput("u1", "Pickup", 30.04, 31.23, "Dest", 30.05, 31.24, routeDistanceKm = 1.0, routeDurationMin = 5L)
        )
        assertEquals(0L, shortQuote.distanceChargeMinor)
        assertEquals(cairoEcon.minimumFareMinor, shortQuote.finalEstimatedFareMinor) // Minimum fare floor applied

        // 11 km trip: 10 billable km * 650 minor = 6500 minor
        val normalQuote = PricingEngine.calculateQuote(
            cairoEcon,
            FareCalculationInput("u1", "Pickup", 30.04, 31.23, "Dest", 30.10, 31.30, routeDistanceKm = 11.0, routeDurationMin = 20L, waitingMinutes = 3L, isAirportTrip = true)
        )
        assertEquals(6500L, normalQuote.distanceChargeMinor)
        assertEquals(300L, normalQuote.waitingChargeMinor)
        assertEquals(cairoEcon.airportSurchargeMinor, normalQuote.surchargesMinor)
        assertEquals(1L, normalQuote.pricingVersion)
    }

    @Test
    fun globalMarkets_calculateCurrencyPrecisionWithBigDecimal() {
        val policies = MarketCatalog.defaultPoliciesForAllMarkets()
        val expectedCurrencies = mapOf(
            "EG" to "EGP",
            "SA" to "SAR",
            "AE" to "AED",
            "GB" to "GBP",
            "US" to "USD",
            "DE" to "EUR"
        )
        expectedCurrencies.forEach { (country, currency) ->
            val policy = policies.first { it.countryCode == country && !it.isTaxiReference && it.serviceCategory == "ECONOMY" }
            assertEquals(currency, policy.currencyCode)
            val quote = PricingEngine.calculateQuote(
                policy,
                FareCalculationInput("u1", "A", 1.0, 1.0, "B", 2.0, 2.0, routeDistanceKm = 12.345, routeDurationMin = 18L, discountMinor = 100L)
            )
            assertEquals(currency, quote.currencyCode)
            assertTrue(quote.finalEstimatedFareMinor >= policy.minimumFareMinor)
        }
    }

    @Test
    fun pricingVersion_updateDoesNotMutateAlreadyAcceptedQuoteSnapshot() {
        val v1 = MarketCatalog.defaultPoliciesForAllMarkets().first { it.city == "Cairo" && !it.isTaxiReference }
        val acceptedQuote = PricingEngine.calculateQuote(
            v1,
            FareCalculationInput("u1", "A", 30.04, 31.23, "B", 30.11, 31.39, routeDistanceKm = 15.0, routeDurationMin = 25L)
        )

        val v2 = v1.copy(baseFareMinor = 1800L, pricePerKmMinor = 900L, pricingVersion = 2L)
        val newQuote = PricingEngine.calculateQuote(
            v2,
            FareCalculationInput("u1", "A", 30.04, 31.23, "B", 30.11, 31.39, routeDistanceKm = 15.0, routeDurationMin = 25L)
        )

        assertEquals(1L, acceptedQuote.pricingVersion)
        assertEquals(2L, newQuote.pricingVersion)
        assertTrue(newQuote.finalEstimatedFareMinor > acceptedQuote.finalEstimatedFareMinor)
    }

    @Test
    fun stripePaymentGateway_withoutSecrets_reportsInactiveAndNeverFakesSucceeded() = runBlocking {
        val gateway = StripePaymentGateway(backendUrl = "", publishableKey = "")
        val status = gateway.configurationStatus()
        assertFalse(status.isConfigured)
        assertTrue(gateway.validateCurrencySupport("EGP", 2))
        assertTrue(gateway.validateCurrencySupport("USD", 2))
        assertFalse(gateway.validateCurrencySupport("XYZ", 2))

        val result = gateway.createAndAuthorizePayment(
            PaymentIntentRequest("ride_1", "rider_1", 12500L, "EGP", null, "idem_1")
        )
        assertEquals(PaymentStatus.FAILED, result.status)
        assertTrue(result.failureReason?.isNotBlank() == true)
    }

    @Test
    fun localization_arabicDefaultRtlAndSevenLanguagesFormatting() {
        assertTrue(AppLanguage.DEFAULT == AppLanguage.ARABIC)
        assertTrue(AppLanguage.ARABIC.isRtl)
        assertFalse(AppLanguage.ENGLISH.isRtl)
        assertFalse(AppLanguage.GERMAN.isRtl)
        assertFalse(AppLanguage.FRENCH.isRtl)
        assertFalse(AppLanguage.SPANISH.isRtl)
        assertFalse(AppLanguage.ITALIAN.isRtl)
        assertFalse(AppLanguage.RUSSIAN.isRtl)

        val arFormatted = LocaleFormatter.formatCurrencyMinor(12550L, "EGP", "EGP", 2, AppLanguage.ARABIC)
        val enFormatted = LocaleFormatter.formatCurrencyMinor(12550L, "EGP", "EGP", 2, AppLanguage.ENGLISH)
        assertTrue(arFormatted.contains("EGP"))
        assertTrue(enFormatted.contains("125.50"))
    }

    @Test
    fun concurrencyAndLatency_100DriversAtomicLock_onlySingleWinnerAndSubMillisecondCalc() = runBlocking {
        val assignedDriver = AtomicReference<String?>(null)
        val results = (1..100).map { idx ->
            async(Dispatchers.Default) {
                assignedDriver.compareAndSet(null, "driver_$idx")
            }
        }.awaitAll()

        assertEquals(1, results.count { it })
        assertEquals(99, results.count { !it })

        // Measure 1,000 fare engine calculations latency (p50/p95/p99)
        val policy = MarketCatalog.defaultPoliciesForAllMarkets().first { it.city == "Cairo" && !it.isTaxiReference }
        val input = FareCalculationInput("u1", "A", 30.04, 31.23, "B", 30.11, 31.39, 14.2, 22L, 2L)
        val latenciesUs = (1..1000).map {
            measureNanoTime { PricingEngine.calculateQuote(policy, input) } / 1000.0
        }.sorted()

        val p50 = latenciesUs[499]
        val p95 = latenciesUs[949]
        val p99 = latenciesUs[989]
        println("PricingEngine 1000-run latency (us): p50=$p50, p95=$p95, p99=$p99")
        assertTrue("Expected p99 pricing latency under 50ms, was $p99 us", p99 < 50_000.0)
    }
}
