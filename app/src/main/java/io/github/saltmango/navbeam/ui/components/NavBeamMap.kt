package io.github.saltmango.navbeam.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import io.github.saltmango.navbeam.dash.nav.GeoPoint
import io.github.saltmango.navbeam.ui.theme.*

private const val FOLLOW_ZOOM = 15.5f
private const val NAV_ZOOM = 17.5f
private const val NAV_TILT = 45.0f

/**
 * Replaces the MapLibre map with Google Maps.
 */
@Composable
fun NavBeamMap(
    riderLat: Double?,
    riderLng: Double?,
    dest: Pair<Double, Double>?,
    stops: List<io.github.saltmango.navbeam.data.SharedLocation> = emptyList(),
    routePoints: List<GeoPoint>,
    hasLocationPermission: Boolean,
    modifier: Modifier = Modifier,
    fitRoute: Boolean = false,
    navMode: Boolean = false,
    riderBearing: Float = 0f,
    routeColor: androidx.compose.ui.graphics.Color = MapRoute,
    routeWidth: Float = 8f,
    onMapClick: ((LatLng) -> Unit)? = null,
) {
    val context = LocalContext.current
    val cameraPositionState = rememberCameraPositionState()

    val uiSettings = remember {
        MapUiSettings(
            compassEnabled = false,
            myLocationButtonEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
            zoomControlsEnabled = false,
            mapToolbarEnabled = false,
        )
    }

    val properties = remember(navMode, hasLocationPermission) {
        MapProperties(
            isMyLocationEnabled = hasLocationPermission && (
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ),
            isTrafficEnabled = true, // Bike navigation usually benefits from traffic info
        )
    }

    // Defensive icon loading: BitmapDescriptorFactory requires MapsInitializer to have run.
    // GoogleMap() usually handles this, but we need the descriptors for markers INSIDE the content.
    var riderIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }
    var destIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }

    LaunchedEffect(Unit) {
        MapsInitializer.initialize(context)
        riderIcon = BitmapDescriptorFactory.fromBitmap(chevronBitmap())
        destIcon = BitmapDescriptorFactory.fromBitmap(destPinBitmap())
    }

    val coroutineScope = rememberCoroutineScope()
    val colors = NavBeamTheme.colors

    Box(modifier = modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = uiSettings,
            properties = properties,
            onMapClick = { onMapClick?.invoke(it) }
        ) {
            // Route Polyline
            if (routePoints.size >= 2) {
                Polyline(
                    points = routePoints.map { LatLng(it.lat, it.lng) },
                    color = routeColor.copy(alpha = 0.65f),
                    width = routeWidth,
                    jointType = JointType.ROUND,
                    startCap = RoundCap(),
                    endCap = RoundCap()
                )
            }

            // Render stops as letter markers A, B, C, D...
            if (stops.isNotEmpty()) {
                stops.forEachIndexed { i, stopLoc ->
                    val letter = ('A' + i).toString()
                    val isFirst = i == 0
                    val icon = remember(letter, isFirst) {
                        BitmapDescriptorFactory.fromBitmap(letterPinBitmap(letter, isFirst))
                    }
                    val markerState = remember(stopLoc.lat, stopLoc.lng) {
                        MarkerState(position = LatLng(stopLoc.lat, stopLoc.lng))
                    }
                    Marker(
                        state = markerState,
                        icon = icon,
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
                    )
                }
            } else {
                // Backward-compatible fallback to single dest marker
                dest?.let { d ->
                    destIcon?.let { icon ->
                        val destState = remember(d.first, d.second) { MarkerState(position = LatLng(d.first, d.second)) }
                        Marker(
                            state = destState,
                            icon = icon,
                            anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
                        )
                    }
                }
            }

            // Rider Marker (Custom Chevron)
            if (navMode && riderLat != null && riderLng != null) {
                riderIcon?.let { icon ->
                    val riderState = remember(riderLat, riderLng) { MarkerState(position = LatLng(riderLat, riderLng)) }
                    Marker(
                        state = riderState,
                        icon = icon,
                        rotation = riderBearing,
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        flat = true
                    )
                }
            }
        }

        if (hasLocationPermission) {
            NavBeamIconButton(
                icon = io.github.saltmango.navbeam.ui.NavBeamIcons.Recenter,
                onClick = {
                    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                    val loc = runCatching {
                        lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    }.getOrNull()
                    if (loc != null) {
                        coroutineScope.launch {
                            cameraPositionState.animate(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.builder()
                                        .target(LatLng(loc.latitude, loc.longitude))
                                        .zoom(FOLLOW_ZOOM)
                                        .build()
                                )
                            )
                        }
                    }
                    val client = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                    try {
                        client.getCurrentLocation(
                            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                            null
                        ).addOnSuccessListener { freshLoc ->
                            if (freshLoc != null) {
                                coroutineScope.launch {
                                    cameraPositionState.animate(
                                        CameraUpdateFactory.newCameraPosition(
                                            CameraPosition.builder()
                                                .target(LatLng(freshLoc.latitude, freshLoc.longitude))
                                                .zoom(FOLLOW_ZOOM)
                                                .build()
                                        )
                                    )
                                }
                            }
                        }
                    } catch (_: SecurityException) {}
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 38.dp, end = 16.dp)
                    .background(colors.bg1.copy(alpha = 0.85f), NavBeamTheme.shapes.iconButton),
            )
        }
    }
 
    // Camera Control Logic
    LaunchedEffect(riderLat, riderLng, riderBearing, navMode, fitRoute, routePoints.size, hasLocationPermission) {
        when {
            fitRoute && routePoints.size >= 2 -> {
                val bounds = LatLngBounds.builder().apply {
                    routePoints.forEach { include(LatLng(it.lat, it.lng)) }
                }.build()
                cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 220))
            }
            riderLat != null && riderLng != null -> {
                val target = LatLng(riderLat, riderLng)
                val cameraPosition = if (navMode) {
                    CameraPosition.builder()
                        .target(target)
                        .zoom(NAV_ZOOM)
                        .tilt(NAV_TILT)
                        .bearing(riderBearing)
                        .build()
                } else {
                    CameraPosition.builder()
                        .target(target)
                        .zoom(FOLLOW_ZOOM)
                        .build()
                }
                cameraPositionState.animate(CameraUpdateFactory.newCameraPosition(cameraPosition))
            }
            dest != null -> {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(LatLng(dest.first, dest.second), 13.0f)
                )
            }
            hasLocationPermission -> {
                val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                val loc = runCatching {
                    lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                }.getOrNull()
                if (loc != null) {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), FOLLOW_ZOOM)
                    )
                }
                val client = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                try {
                    client.getCurrentLocation(
                        com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                        null
                    ).addOnSuccessListener { freshLoc ->
                        if (freshLoc != null) {
                            coroutineScope.launch {
                                cameraPositionState.animate(
                                    CameraUpdateFactory.newLatLngZoom(LatLng(freshLoc.latitude, freshLoc.longitude), FOLLOW_ZOOM)
                                )
                            }
                        }
                    }
                } catch (_: SecurityException) {}
            }
        }
    }
}

/** Google-style blue chevron-in-a-circle. */
private fun chevronBitmap(): Bitmap {
    val s = 84
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(s / 2f, s / 2f, s * 0.34f, p)
    p.color = android.graphics.Color.rgb(66, 133, 244)
    c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.WHITE
    val cx = s / 2f
    c.drawPath(Path().apply {
        moveTo(cx, s * 0.24f); lineTo(s * 0.72f, s * 0.70f); lineTo(cx, s * 0.58f); lineTo(s * 0.28f, s * 0.70f); close()
    }, p)
    return bmp
}

/** Simple red destination pin. */
private fun destPinBitmap(): Bitmap {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.rgb(234, 67, 53); c.drawCircle(s / 2f, s / 2f, s * 0.24f, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.09f, p)
    return bmp
}

/** Circular pin labeled with A, B, C, D... */
private fun letterPinBitmap(letter: String, isFirst: Boolean): Bitmap {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    // Outer white circle
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(s / 2f, s / 2f, s * 0.44f, paint)
    
    // Inner colored circle
    paint.color = if (isFirst) android.graphics.Color.rgb(66, 133, 244) else android.graphics.Color.rgb(234, 67, 53)
    canvas.drawCircle(s / 2f, s / 2f, s * 0.38f, paint)
    
    // Letter text in center
    paint.color = android.graphics.Color.WHITE
    paint.textSize = s * 0.40f
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    
    val xPos = s / 2f
    val yPos = (s / 2f - (paint.descent() + paint.ascent()) / 2f)
    canvas.drawText(letter, xPos, yPos, paint)
    
    return bmp
}
