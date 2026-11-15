package com.example.domain.mapping

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.example.domain.pricing.MarketCatalog
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
) {
    fun isValid(): Boolean = latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        !(latitude == 0.0 && longitude == 0.0)
}

data class ReverseGeocodeResult(
    val address: String,
    val countryCode: String,
    val countryName: String,
    val city: String,
    val currencyCode: String,
    val currencySymbol: String,
    val currencyDecimals: Int
)

data class PlaceSearchResult(
    val label: String,
    val address: String,
    val point: GeoPoint
)

data class RouteResult(
    val distanceKm: Double,
    val durationMinutes: Long,
    val polyline: List<GeoPoint>,
    val isRoadRouted: Boolean,
    val providerName: String
)

interface MappingProvider {
    suspend fun reverseGeocode(point: GeoPoint): ReverseGeocodeResult
    suspend fun searchPlaces(query: String, near: GeoPoint): List<PlaceSearchResult>
    suspend fun calculateRoute(origin: GeoPoint, destination: GeoPoint): RouteResult
    fun haversineKm(a: GeoPoint, b: GeoPoint): Double
}

data class OsrmRouteResponse(
    val code: String? = null,
    val routes: List<OsrmRoute>? = null
)

data class OsrmRoute(
    val distance: Double = 0.0,
    val duration: Double = 0.0,
    val geometry: OsrmGeometry? = null
)

data class OsrmGeometry(
    val coordinates: List<List<Double>> = emptyList()
)

data class NominatimPlace(
    @Json(name = "display_name") val displayName: String = "",
    val lat: String = "0",
    val lon: String = "0"
)

interface OsrmApi {
    @GET("route/v1/driving/{coords}")
    suspend fun getRoute(
        @Path("coords") coords: String,
        @Query("overview") overview: String = "simplified",
        @Query("geometries") geometries: String = "geojson"
    ): OsrmRouteResponse
}

interface NominatimApi {
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 5,
        @Header("User-Agent") userAgent: String = "VeloRideAndroid/1.0"
    ): List<NominatimPlace>
}

class OsrmMappingProvider(
    private val context: Context? = null
) : MappingProvider {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    }

    private val osrmApi: OsrmApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://router.project-osrm.org/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OsrmApi::class.java)
    }

    private val nominatimApi: NominatimApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://nominatim.openstreetmap.org/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(NominatimApi::class.java)
    }

    override suspend fun reverseGeocode(point: GeoPoint): ReverseGeocodeResult = withContext(Dispatchers.IO) {
        val marketFallback = MarketCatalog.detectMarketFromCoordinates(point.latitude, point.longitude)
        if (context != null && Geocoder.isPresent()) {
            try {
                val geocoder = Geocoder(context, Locale.ENGLISH)
                @Suppress("DEPRECATION")
                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocation(point.latitude, point.longitude, 1)
                } else {
                    geocoder.getFromLocation(point.latitude, point.longitude, 1)
                }
                val addr = addresses?.firstOrNull()
                if (addr != null) {
                    val countryCode = addr.countryCode?.uppercase() ?: marketFallback.countryCode
                    val city = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: marketFallback.city
                    val market = MarketCatalog.marketByCountryAndCity(countryCode, city)
                    val formatted = listOfNotNull(addr.thoroughfare, addr.subLocality, city, addr.countryName)
                        .distinct()
                        .joinToString(", ")
                        .ifBlank { "${market.city}, ${market.countryName}" }
                    return@withContext ReverseGeocodeResult(
                        address = formatted,
                        countryCode = market.countryCode,
                        countryName = addr.countryName ?: market.countryName,
                        city = city,
                        currencyCode = market.currencyCode,
                        currencySymbol = market.currencySymbol,
                        currencyDecimals = market.currencyDecimals
                    )
                }
            } catch (_: Exception) {
                // Fall back to coordinate market detection cleanly
            }
        }
        ReverseGeocodeResult(
            address = "${marketFallback.defaultPickupName} (${String.format(Locale.US, "%.4f, %.4f", point.latitude, point.longitude)})",
            countryCode = marketFallback.countryCode,
            countryName = marketFallback.countryName,
            city = marketFallback.city,
            currencyCode = marketFallback.currencyCode,
            currencySymbol = marketFallback.currencySymbol,
            currencyDecimals = marketFallback.currencyDecimals
        )
    }

    override suspend fun searchPlaces(query: String, near: GeoPoint): List<PlaceSearchResult> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            try {
                val results = nominatimApi.search(query.trim())
                if (results.isNotEmpty()) {
                    return@withContext results.mapNotNull { place ->
                        val lat = place.lat.toDoubleOrNull() ?: return@mapNotNull null
                        val lon = place.lon.toDoubleOrNull() ?: return@mapNotNull null
                        val p = GeoPoint(lat, lon)
                        if (!p.isValid()) return@mapNotNull null
                        PlaceSearchResult(
                            label = place.displayName.substringBefore(",").trim().ifBlank { query },
                            address = place.displayName,
                            point = p
                        )
                    }
                }
            } catch (_: Exception) {
                // Graceful fallback to local market landmarks
            }
            val market = MarketCatalog.detectMarketFromCoordinates(near.latitude, near.longitude)
            listOf(
                PlaceSearchResult(
                    label = market.defaultDestinationName,
                    address = "${market.defaultDestinationName}, ${market.city}",
                    point = GeoPoint(market.defaultDestinationLat, market.defaultDestinationLng)
                ),
                PlaceSearchResult(
                    label = market.defaultPickupName,
                    address = "${market.defaultPickupName}, ${market.city}",
                    point = GeoPoint(market.centerLat, market.centerLng)
                )
            )
        }

    override suspend fun calculateRoute(origin: GeoPoint, destination: GeoPoint): RouteResult =
        withContext(Dispatchers.IO) {
            require(origin.isValid() && destination.isValid()) {
                "Invalid coordinates provided for route calculation."
            }
            try {
                val coords = "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}"
                val response = osrmApi.getRoute(coords)
                val route = response.routes?.firstOrNull()
                if (response.code == "Ok" && route != null && route.distance > 0.0) {
                    val points = route.geometry?.coordinates?.mapNotNull { pair ->
                        if (pair.size >= 2) GeoPoint(pair[1], pair[0]) else null
                    }.orEmpty().ifEmpty { interpolateRoute(origin, destination) }
                    return@withContext RouteResult(
                        distanceKm = (route.distance / 1000.0).coerceAtLeast(0.5),
                        durationMinutes = (route.duration / 60.0).toLong().coerceAtLeast(2L),
                        polyline = points,
                        isRoadRouted = true,
                        providerName = "OSRM Driving Route"
                    )
                }
            } catch (_: Exception) {
                // Graceful road-factor fallback when routing endpoint is unreachable
            }
            val straightKm = haversineKm(origin, destination)
            val estimatedRoadKm = (straightKm * 1.28).coerceAtLeast(0.8)
            val estimatedMins = ((estimatedRoadKm / 34.0) * 60.0).toLong().coerceAtLeast(3L)
            RouteResult(
                distanceKm = estimatedRoadKm,
                durationMinutes = estimatedMins,
                polyline = interpolateRoute(origin, destination),
                isRoadRouted = false,
                providerName = "Fallback Road-Factor Estimate"
            )
        }

    override fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val h = sinLat * sinLat +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sinLon * sinLon
        val c = 2.0 * atan2(sqrt(h), sqrt(1.0 - h))
        return earthRadiusKm * c
    }

    private fun interpolateRoute(a: GeoPoint, b: GeoPoint): List<GeoPoint> {
        val midLat = (a.latitude + b.latitude) / 2.0 + 0.004
        val midLng = (a.longitude + b.longitude) / 2.0 - 0.004
        return listOf(a, GeoPoint(midLat, midLng), b)
    }
}
