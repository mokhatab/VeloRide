package com.example.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.StringsProvider
import com.example.core.maps.MapsKeyHelper
import com.example.domain.mapping.GeoPoint
import com.example.domain.mapping.RouteResult
import com.example.domain.model.DriverAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.max

enum class MapPinTarget { PICKUP, DESTINATION }

@Composable
fun VeloMapCanvas(
    language: AppLanguage,
    pickup: GeoPoint,
    destination: GeoPoint,
    route: RouteResult?,
    onlineDrivers: List<DriverAvailability>,
    assignedDriverLocation: DriverAvailability?,
    onSelectPickup: (GeoPoint) -> Unit,
    onSelectDestination: (GeoPoint) -> Unit,
    onGpsPermissionUpdate: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = remember(language) { StringsProvider.get(language) }
    var activeTarget by remember { mutableStateOf(MapPinTarget.DESTINATION) }
    var gpsStatusNote by remember { mutableStateOf<String?>(null) }
    val hasRealMapsKey = remember { MapsKeyHelper.isRealMapsKey() }

    val colorBg = MaterialTheme.colorScheme.surfaceVariant
    val colorGrid = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val colorRoad = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val colorPrimary = MaterialTheme.colorScheme.primary
    val colorSecondary = MaterialTheme.colorScheme.secondary
    val colorTertiary = MaterialTheme.colorScheme.tertiary
    val colorError = MaterialTheme.colorScheme.error
    val colorSurface = MaterialTheme.colorScheme.surface

    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun fetchCurrentGps() {
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                        onSelectPickup(GeoPoint(loc.latitude, loc.longitude))
                        gpsStatusNote = null
                    } else {
                        gpsStatusNote = if (language == AppLanguage.ARABIC) {
                            "استخدم الموقع الافتراضي أو انقر على الخريطة"
                        } else {
                            "GPS signal unavailable; tap map to adjust pin"
                        }
                    }
                }
                .addOnFailureListener {
                    gpsStatusNote = if (language == AppLanguage.ARABIC) {
                        "تعذر جلب GPS حالياً"
                    } else {
                        "Unable to acquire GPS fix"
                    }
                }
        } catch (_: Exception) {
            gpsStatusNote = "GPS unavailable"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        onGpsPermissionUpdate?.invoke(fineGranted)
        if (fineGranted) {
            fetchCurrentGps()
        } else {
            gpsStatusNote = if (language == AppLanguage.ARABIC) {
                "تم رفض إذن الموقع؛ يمكنك تحديد النقاط يدوياً على الخريطة"
            } else {
                "Location permission denied; tap map pins manually"
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Pin target & GPS controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = activeTarget == MapPinTarget.PICKUP,
                    onClick = { activeTarget = MapPinTarget.PICKUP },
                    label = { Text(strings.pickupLabel) },
                    leadingIcon = {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.testTag("map_pin_pickup_mode")
                )
                FilterChip(
                    selected = activeTarget == MapPinTarget.DESTINATION,
                    onClick = { activeTarget = MapPinTarget.DESTINATION },
                    label = { Text(strings.destinationLabel) },
                    leadingIcon = {
                        Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.testTag("map_pin_dest_mode")
                )
            }

            FilledTonalIconButton(
                onClick = {
                    val hasFine = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasFine) {
                        fetchCurrentGps()
                    } else {
                        val perms = buildList {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        permissionLauncher.launch(perms)
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("use_gps_button")
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = strings.useCurrentGps
                )
            }
        }

        if (hasRealMapsKey) {
            // Interactive Google Maps SDK for Android
            val pickupLatLng = LatLng(pickup.latitude, pickup.longitude)
            val destLatLng = LatLng(destination.latitude, destination.longitude)
            val cameraPositionState = rememberCameraPositionState {
                position = CameraPosition.fromLatLngZoom(pickupLatLng, 12.5f)
            }

            LaunchedEffect(pickup, destination, route) {
                runCatching {
                    val boundsBuilder = LatLngBounds.builder()
                        .include(pickupLatLng)
                        .include(destLatLng)
                    route?.polyline?.forEach { pt ->
                        boundsBuilder.include(LatLng(pt.latitude, pt.longitude))
                    }
                    assignedDriverLocation?.let { d ->
                        boundsBuilder.include(LatLng(d.latitude, d.longitude))
                    }
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 80)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
            ) {
                GoogleMap(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("google_map_view"),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = true,
                        myLocationButtonEnabled = false,
                        compassEnabled = true
                    ),
                    onMapClick = { latLng ->
                        val geo = GeoPoint(latLng.latitude, latLng.longitude)
                        if (activeTarget == MapPinTarget.PICKUP) {
                            onSelectPickup(geo)
                        } else {
                            onSelectDestination(geo)
                        }
                    }
                ) {
                    Marker(
                        state = MarkerState(position = pickupLatLng),
                        title = strings.pickupLabel,
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                    )
                    Marker(
                        state = MarkerState(position = destLatLng),
                        title = strings.destinationLabel,
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                    )
                    onlineDrivers.take(15).forEach { driver ->
                        Marker(
                            state = MarkerState(position = LatLng(driver.latitude, driver.longitude)),
                            title = driver.vehicleCategory,
                            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN)
                        )
                    }
                    assignedDriverLocation?.let { assigned ->
                        Marker(
                            state = MarkerState(position = LatLng(assigned.latitude, assigned.longitude)),
                            title = strings.driverMode,
                            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                        )
                    }
                    val polylineCoords = (route?.polyline?.takeIf { it.size >= 2 } ?: listOf(pickup, destination))
                        .map { LatLng(it.latitude, it.longitude) }
                    Polyline(
                        points = polylineCoords,
                        color = colorPrimary,
                        width = 10f,
                        pattern = if (route?.isRoadRouted == false) listOf(Dash(20f), Gap(12f)) else null
                    )
                }

                // Driver count pill
                DriverCountPill(
                    language = language,
                    count = onlineDrivers.size,
                    color = colorTertiary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                )
            }
        } else {
            // Safe vector fallback when MAPS_API_KEY is still the unconfigured placeholder
            val allPoints = remember(pickup, destination, route, onlineDrivers, assignedDriverLocation) {
                buildList {
                    add(pickup)
                    add(destination)
                    route?.polyline?.let { addAll(it) }
                    onlineDrivers.take(12).forEach { add(GeoPoint(it.latitude, it.longitude)) }
                    assignedDriverLocation?.let { add(GeoPoint(it.latitude, it.longitude)) }
                }
            }

            val minLat = (allPoints.minOfOrNull { it.latitude } ?: pickup.latitude) - 0.025
            val maxLat = (allPoints.maxOfOrNull { it.latitude } ?: pickup.latitude) + 0.025
            val minLng = (allPoints.minOfOrNull { it.longitude } ?: pickup.longitude) - 0.025
            val maxLng = (allPoints.maxOfOrNull { it.longitude } ?: pickup.longitude) + 0.025
            val latSpan = max(0.04, maxLat - minLat)
            val lngSpan = max(0.04, maxLng - minLng)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                    .background(colorBg)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("interactive_map_canvas")
                        .pointerInput(minLat, latSpan, minLng, lngSpan, activeTarget) {
                            detectTapGestures { offset ->
                                val tappedLng = minLng + (offset.x / size.width) * lngSpan
                                val tappedLat = maxLat - (offset.y / size.height) * latSpan
                                val tapped = GeoPoint(tappedLat, tappedLng)
                                if (activeTarget == MapPinTarget.PICKUP) {
                                    onSelectPickup(tapped)
                                } else {
                                    onSelectDestination(tapped)
                                }
                            }
                        }
                ) {
                    fun toOffset(p: GeoPoint): Offset {
                        val x = ((p.longitude - minLng) / lngSpan).toFloat().coerceIn(0.04f, 0.96f) * size.width
                        val y = ((maxLat - p.latitude) / latSpan).toFloat().coerceIn(0.06f, 0.94f) * size.height
                        return Offset(x, y)
                    }

                    val stepX = size.width / 8f
                    val stepY = size.height / 6f
                    for (i in 1..7) {
                        drawLine(colorGrid, Offset(i * stepX, 0f), Offset(i * stepX, size.height), strokeWidth = 1.5f)
                    }
                    for (j in 1..5) {
                        drawLine(colorGrid, Offset(0f, j * stepY), Offset(size.width, j * stepY), strokeWidth = 1.5f)
                    }
                    drawLine(colorRoad, Offset(0f, size.height * 0.72f), Offset(size.width, size.height * 0.25f), strokeWidth = 6f)
                    drawLine(colorRoad, Offset(size.width * 0.2f, 0f), Offset(size.width * 0.75f, size.height), strokeWidth = 4.5f)

                    val polyPoints = route?.polyline?.takeIf { it.size >= 2 } ?: listOf(pickup, destination)
                    val path = Path().apply {
                        val first = toOffset(polyPoints.first())
                        moveTo(first.x, first.y)
                        polyPoints.drop(1).forEach { pt ->
                            val off = toOffset(pt)
                            lineTo(off.x, off.y)
                        }
                    }
                    drawPath(
                        path = path,
                        color = colorPrimary.copy(alpha = 0.35f),
                        style = Stroke(width = 16f, cap = StrokeCap.Round)
                    )
                    drawPath(
                        path = path,
                        color = colorPrimary,
                        style = Stroke(
                            width = 7f,
                            cap = StrokeCap.Round,
                            pathEffect = if (route?.isRoadRouted == false) PathEffect.dashPathEffect(floatArrayOf(16f, 10f)) else null
                        )
                    )

                    onlineDrivers.forEach { d ->
                        val dOff = toOffset(GeoPoint(d.latitude, d.longitude))
                        drawCircle(color = colorSurface, radius = 13f, center = dOff)
                        drawCircle(color = colorTertiary, radius = 9f, center = dOff)
                    }

                    assignedDriverLocation?.let { assigned ->
                        val aOff = toOffset(GeoPoint(assigned.latitude, assigned.longitude))
                        drawCircle(color = colorSecondary.copy(alpha = 0.28f), radius = 26f, center = aOff)
                        drawCircle(color = colorSurface, radius = 16f, center = aOff)
                        drawCircle(color = colorSecondary, radius = 11f, center = aOff)
                    }

                    val pOff = toOffset(pickup)
                    drawCircle(color = colorPrimary.copy(alpha = 0.25f), radius = 24f, center = pOff)
                    drawCircle(color = colorSurface, radius = 14f, center = pOff)
                    drawCircle(color = colorPrimary, radius = 9f, center = pOff)

                    val dOff = toOffset(destination)
                    drawCircle(color = colorError.copy(alpha = 0.25f), radius = 24f, center = dOff)
                    drawCircle(color = colorSurface, radius = 14f, center = dOff)
                    drawCircle(color = colorError, radius = 9f, center = dOff)
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LegendDot(color = colorPrimary, label = strings.selectOnMap)
                        Text(
                            text = route?.providerName ?: "Route",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                DriverCountPill(
                    language = language,
                    count = onlineDrivers.size,
                    color = colorTertiary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                )
            }

            Text(
                text = strings.googleMapsSecretHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("maps_key_notice")
            )
        }

        gpsStatusNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun DriverCountPill(
    language: AppLanguage,
    count: Int,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        modifier = modifier
    ) {
        val countLabel = if (language == AppLanguage.ARABIC) {
            "$count سائق متصل"
        } else {
            "$count online"
        }
        Text(
            text = countLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}
