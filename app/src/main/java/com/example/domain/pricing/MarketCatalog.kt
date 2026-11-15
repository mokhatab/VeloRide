package com.example.domain.pricing

import com.example.domain.model.PricingPolicy
import com.example.domain.model.VehicleCategory
import com.google.firebase.Timestamp

data class MarketInfo(
    val countryCode: String,
    val countryName: String,
    val city: String,
    val currencyCode: String,
    val currencySymbol: String,
    val currencyDecimals: Int,
    val centerLat: Double,
    val centerLng: Double,
    val defaultDestinationLat: Double,
    val defaultDestinationLng: Double,
    val defaultDestinationName: String,
    val defaultPickupName: String
)

object MarketCatalog {
    val supportedMarkets: List<MarketInfo> = listOf(
        MarketInfo(
            countryCode = "EG",
            countryName = "Egypt",
            city = "Cairo",
            currencyCode = "EGP",
            currencySymbol = "EGP",
            currencyDecimals = 2,
            centerLat = 30.0444,
            centerLng = 31.2357,
            defaultPickupName = "Tahrir Square, Cairo",
            defaultDestinationLat = 30.1128,
            defaultDestinationLng = 31.3999,
            defaultDestinationName = "Cairo International Airport"
        ),
        MarketInfo(
            countryCode = "EG",
            countryName = "Egypt",
            city = "Alexandria",
            currencyCode = "EGP",
            currencySymbol = "EGP",
            currencyDecimals = 2,
            centerLat = 31.2001,
            centerLng = 29.9187,
            defaultPickupName = "Bibliotheca Alexandrina",
            defaultDestinationLat = 31.1839,
            defaultDestinationLng = 29.9489,
            defaultDestinationName = "Sidi Gaber Station"
        ),
        MarketInfo(
            countryCode = "SA",
            countryName = "Saudi Arabia",
            city = "Riyadh",
            currencyCode = "SAR",
            currencySymbol = "SAR",
            currencyDecimals = 2,
            centerLat = 24.7136,
            centerLng = 46.6753,
            defaultPickupName = "Kingdom Centre, Riyadh",
            defaultDestinationLat = 24.9576,
            defaultDestinationLng = 46.6988,
            defaultDestinationName = "King Khalid Airport"
        ),
        MarketInfo(
            countryCode = "AE",
            countryName = "UAE",
            city = "Dubai",
            currencyCode = "AED",
            currencySymbol = "AED",
            currencyDecimals = 2,
            centerLat = 25.1972,
            centerLng = 55.2744,
            defaultPickupName = "Downtown Dubai",
            defaultDestinationLat = 25.2532,
            defaultDestinationLng = 55.3657,
            defaultDestinationName = "Dubai International (DXB)"
        ),
        MarketInfo(
            countryCode = "GB",
            countryName = "United Kingdom",
            city = "London",
            currencyCode = "GBP",
            currencySymbol = "£",
            currencyDecimals = 2,
            centerLat = 51.5074,
            centerLng = -0.1278,
            defaultPickupName = "Trafalgar Square, London",
            defaultDestinationLat = 51.4700,
            defaultDestinationLng = -0.4543,
            defaultDestinationName = "Heathrow Airport"
        ),
        MarketInfo(
            countryCode = "US",
            countryName = "United States",
            city = "New York",
            currencyCode = "USD",
            currencySymbol = "$",
            currencyDecimals = 2,
            centerLat = 40.7580,
            centerLng = -73.9855,
            defaultPickupName = "Midtown Manhattan, NY",
            defaultDestinationLat = 40.6413,
            defaultDestinationLng = -73.7781,
            defaultDestinationName = "JFK Airport"
        ),
        MarketInfo(
            countryCode = "DE",
            countryName = "Germany",
            city = "Berlin",
            currencyCode = "EUR",
            currencySymbol = "€",
            currencyDecimals = 2,
            centerLat = 52.5200,
            centerLng = 13.4050,
            defaultPickupName = "Alexanderplatz, Berlin",
            defaultDestinationLat = 52.3667,
            defaultDestinationLng = 13.5033,
            defaultDestinationName = "Berlin Brandenburg Airport"
        )
    )

    fun detectMarketFromCoordinates(lat: Double, lng: Double): MarketInfo {
        return supportedMarkets.minByOrNull { market ->
            val dLat = lat - market.centerLat
            val dLng = lng - market.centerLng
            dLat * dLat + dLng * dLng
        } ?: supportedMarkets.first()
    }

    fun marketByCountryAndCity(countryCode: String, city: String?): MarketInfo {
        if (!city.isNullOrBlank()) {
            supportedMarkets.firstOrNull {
                it.countryCode.equals(countryCode, true) && it.city.equals(city, true)
            }?.let { return it }
        }
        return supportedMarkets.firstOrNull { it.countryCode.equals(countryCode, true) }
            ?: supportedMarkets.first()
    }

    /**
     * Generates default administrator-configurable commercial pricing policies.
     * Egypt initial market uses requested commercial starting ranges:
     * Base fare EGP 8.50-20.00 (default 12.00 for Economy), Included first 1 km,
     * Additional km EGP 4.00-10.00 (default 6.50 for Economy), plus a separate
     * local taxi-meter reference profile.
     */
    private data class MarketTariffPreset(
        val baseEcon: Long,
        val perKmEcon: Long,
        val waitPerMin: Long,
        val minFare: Long,
        val bookingFee: Long,
        val taxPct: Double,
        val airportFee: Long
    )

    fun defaultPoliciesForAllMarkets(now: Timestamp = Timestamp.now()): List<PricingPolicy> {
        val result = mutableListOf<PricingPolicy>()
        for (market in supportedMarkets) {
            val preset = when (market.countryCode) {
                "EG" -> MarketTariffPreset(
                    if (market.city == "Cairo") 1200L else 1050L, // EGP 12.00 / 10.50 (within 8.50-20.00)
                    if (market.city == "Cairo") 650L else 550L,   // EGP 6.50 / 5.50 per km (within 4.00-10.00)
                    100L,  // EGP 1.00 / min waiting
                    2500L, // EGP 25.00 min fare
                    300L,  // EGP 3.00 booking fee
                    14.0,  // 14% VAT
                    2000L  // EGP 20.00 airport surcharge
                )
                "SA" -> MarketTariffPreset(900L, 280L, 60L, 1800L, 200L, 15.0, 1500L)
                "AE" -> MarketTariffPreset(1200L, 250L, 70L, 2000L, 250L, 5.0, 2000L)
                "GB" -> MarketTariffPreset(350L, 180L, 45L, 800L, 150L, 20.0, 500L)
                "US" -> MarketTariffPreset(400L, 165L, 50L, 900L, 250L, 8.875, 550L)
                "DE" -> MarketTariffPreset(420L, 195L, 45L, 850L, 150L, 19.0, 450L)
                else -> MarketTariffPreset(1200L, 650L, 100L, 2500L, 300L, 14.0, 2000L)
            }

            val baseL = preset.baseEcon
            val kmL = preset.perKmEcon
            val waitL = preset.waitPerMin
            val minL = preset.minFare
            val bookL = preset.bookingFee
            val taxD = preset.taxPct
            val airL = preset.airportFee

            VehicleCategory.entries.forEach { cat ->
                val factor = when (cat) {
                    VehicleCategory.ECONOMY -> 1.0
                    VehicleCategory.COMFORT -> 1.35
                    VehicleCategory.XL -> 1.65
                    VehicleCategory.VIP -> 2.2
                }
                val id = "${market.countryCode.lowercase()}_${market.city.lowercase()}_${cat.name.lowercase()}"
                result += PricingPolicy(
                    id = id,
                    countryCode = market.countryCode,
                    countryName = market.countryName,
                    city = market.city,
                    currencyCode = market.currencyCode,
                    currencySymbol = market.currencySymbol,
                    currencyDecimals = market.currencyDecimals.toLong(),
                    serviceCategory = cat.name,
                    isTaxiReference = false,
                    baseFareMinor = (baseL * factor).toLong().coerceAtLeast(100L),
                    includedDistanceKm = 1.0,
                    pricePerKmMinor = (kmL * factor).toLong().coerceAtLeast(50L),
                    waitingChargePerMinMinor = (waitL * factor).toLong(),
                    minimumFareMinor = (minL * factor).toLong(),
                    bookingFeeMinor = bookL,
                    taxPercentage = taxD,
                    airportSurchargeMinor = airL,
                    nightSurchargeMinor = (baseL * 0.25).toLong(),
                    trafficSurchargeMinor = 0L,
                    surgeMultiplier = 1.0,
                    effectiveStartMs = 0L,
                    effectiveEndMs = 4102444800000L,
                    pricingVersion = 1L,
                    isActive = true,
                    updatedByAdminId = "system",
                    createdAt = now,
                    updatedAt = now
                )
            }

            // Separate reference profile for local taxi-meter tariffs (non-authoritative for VeloRide)
            result += PricingPolicy(
                id = "${market.countryCode.lowercase()}_${market.city.lowercase()}_taxi_ref",
                countryCode = market.countryCode,
                countryName = market.countryName,
                city = market.city,
                currencyCode = market.currencyCode,
                currencySymbol = market.currencySymbol,
                currencyDecimals = market.currencyDecimals.toLong(),
                serviceCategory = VehicleCategory.ECONOMY.name,
                isTaxiReference = true,
                baseFareMinor = (baseL * 0.90).toLong(),
                includedDistanceKm = 1.0,
                pricePerKmMinor = (kmL * 0.75).toLong(),
                waitingChargePerMinMinor = (waitL * 0.80).toLong(),
                minimumFareMinor = (minL * 0.70).toLong(),
                bookingFeeMinor = 0L,
                taxPercentage = 0.0,
                airportSurchargeMinor = airL,
                nightSurchargeMinor = 0L,
                trafficSurchargeMinor = 0L,
                surgeMultiplier = 1.0,
                effectiveStartMs = 0L,
                effectiveEndMs = 4102444800000L,
                pricingVersion = 1L,
                isActive = true,
                updatedByAdminId = "system_taxi_ref",
                createdAt = now,
                updatedAt = now
            )
        }
        return result
    }
}
