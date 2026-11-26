package io.github.saltmango.navbeam.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.saltmango.navbeam.dash.nav.GeoPoint
import io.github.saltmango.navbeam.dash.nav.PolylineCodec
import io.github.saltmango.navbeam.data.Ride
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.components.*
import io.github.saltmango.navbeam.ui.theme.*
import io.github.saltmango.navbeam.viewmodel.RidesViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RidesScreen(
    onBack: () -> Unit,
    ridesViewModel: RidesViewModel = viewModel(),
) {
    val rides by ridesViewModel.rides.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        NavBeamScreenHeader(
            eyebrow = "Telemetry",
            title = "Ride history",
            hint = "Every connected ride is saved here — distance, duration, and the route you took.",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            if (rides.isEmpty()) {
                NavBeamEmptyState(
                    icon = NavBeamIcons.Route,
                    title = "No rides recorded yet",
                    subtitle = "Connect to your dash to start a ride — it's saved automatically when you disconnect.",
                    padding = 28.dp,
                )
            } else {
                RideTotals(rides)
                Spacer(Modifier.height(14.dp))
                rides.forEach { ride ->
                    RideCard(ride, onDelete = { ridesViewModel.deleteRide(ride) })
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun RideTotals(rides: List<Ride>) {
    val colors = NavBeamTheme.colors
    val totalKm = rides.sumOf { it.distanceKm }
    val totalSec = rides.sumOf { it.durationSec }
    NavBeamCard(modifier = Modifier.fillMaxWidth(), padding = 18.dp) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NavBeamStat("${rides.size}", "rides", valueColor = colors.primary)
            NavBeamStat("%.0f".format(totalKm), "km total", valueColor = colors.primary)
            NavBeamStat(fmtDuration(totalSec), "time", valueColor = colors.primary)
        }
    }
}

@Composable
private fun RideCard(ride: Ride, onDelete: () -> Unit) {
    val colors = NavBeamTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val track = remember(ride.trackPolyline) {
        if (ride.trackPolyline.isBlank()) emptyList()
        else PolylineCodec.decode(ride.trackPolyline)
    }
    NavBeamCard(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        padding = 16.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(colors.surf2),
                ) {
                    if (track.size >= 2) TrackSketch(track, Modifier.fillMaxSize().padding(8.dp))
                    else Icon(NavBeamIcons.Route, contentDescription = null, tint = colors.textLo, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = fmtDate(ride.startMs),
                        color = colors.textHi,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily,
                    )
                    Text(
                        text = "${fmtTime(ride.startMs)} – ${fmtTime(ride.endMs)}",
                        color = colors.textLo,
                        fontSize = 12.sp,
                        fontFamily = GeistFamily,
                    )
                }
                Text(
                    text = "%.1f km".format(ride.distanceKm),
                    color = colors.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                NavBeamStat(fmtDuration(ride.durationSec), "duration", horizontalAlignment = Alignment.Start, compact = true)
                NavBeamStat("%.0f km/h".format(ride.avgSpeedKmh), "avg", horizontalAlignment = Alignment.Start, compact = true)
                NavBeamStat("%.0f km/h".format(ride.maxSpeedKmh), "max", horizontalAlignment = Alignment.Start, compact = true)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(colors.surf2)
                        .clickable { onDelete() },
                ) {
                    Icon(NavBeamIcons.X, contentDescription = "Delete ride", tint = colors.textLo, modifier = Modifier.size(16.dp))
                }
            }

            if (!ride.destinationName.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(NavBeamIcons.LocationPin, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${ride.startLocationName ?: "Start"} → ${ride.destinationName}",
                        color = colors.textHi,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = GeistFamily,
                    )
                }
                if (!ride.stops.isNullOrBlank()) {
                    Text(
                        text = "Stops: ${ride.stops}",
                        color = colors.textLo,
                        fontSize = 11.sp,
                        fontFamily = GeistFamily,
                        modifier = Modifier.padding(start = 20.dp, top = 2.dp),
                    )
                }
            }

            if (ride.startBattery >= 0) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(NavBeamIcons.Zap, contentDescription = null, tint = colors.textLo, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${ride.batteryUsed}% used (${ride.startBattery}% → ${ride.endBattery}%)",
                            color = colors.textHi,
                            fontSize = 12.sp,
                            fontFamily = GeistFamily,
                        )
                    }
                    if (ride.startBatteryTemp > 0 || ride.endBatteryTemp > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(NavBeamIcons.Thermo, contentDescription = null, tint = colors.textLo, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${"%.1f".format(ride.startBatteryTemp)}°C → ${"%.1f".format(ride.endBatteryTemp)}°C",
                                color = colors.textHi,
                                fontSize = 12.sp,
                                fontFamily = GeistFamily,
                            )
                        }
                    }
                }
            }

            if (!expanded && (ride.avgFps > 0 || ride.recalcCount > 0 || ride.reconnectCount > 0 || ride.startBattery >= 0)) {
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(NavBeamIcons.ChevronDown, contentDescription = "Show details", tint = colors.textLo, modifier = Modifier.size(16.dp))
                }
            }

            if (expanded) {
                Spacer(Modifier.height(14.dp))
                NavBeamDivider()
                Spacer(Modifier.height(10.dp))

                Text(
                    text = "TECHNICAL METRICS",
                    color = colors.textLo,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistFamily,
                )
                Spacer(Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ride.avgFps > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TechItem("Stream Rate", "${"%.1f".format(ride.avgFps)} fps", Modifier.weight(1f))
                            TechItem("Bitrate (Avg)", "${"%.0f".format(ride.avgBitrateKbps)} Kbps", Modifier.weight(1f))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TechItem("Video Data", "%.1f MB".format(ride.totalBytesSent / (1024.0 * 1024.0)), Modifier.weight(1f))
                            TechItem("Encoder / Mode", "${ride.encoderMode} (${ride.streamQuality})", Modifier.weight(1f))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TechItem("Encode Latency", "${"%.1f".format(ride.avgEncodeTimeMs)} ms", Modifier.weight(1f))
                            TechItem("Network Latency", "${"%.1f".format(ride.avgNetworkLatencyMs)} ms", Modifier.weight(1f))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TechItem("Projection Mode", ride.navMode, Modifier.weight(1f))
                            TechItem("Frame Drops", "${ride.skippedFrames} frames", Modifier.weight(1f))
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TechItem("GPS Accuracy", "${"%.1f".format(ride.gpsAccuracyAvg)} m", Modifier.weight(1f))
                        TechItem("GPS Fixes", "${ride.gpsFixCount} updates", Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TechItem("GPS Interval", "${"%.1f".format(ride.gpsIntervalAvgMs / 1000.0)} s", Modifier.weight(1f))
                        TechItem("GPS Signal Drops", "${ride.gpsDropsCount}", Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TechItem("Reroutes (Recalc)", "${ride.recalcCount}", Modifier.weight(1f))
                        TechItem("Link Reconnects", "${ride.reconnectCount}", Modifier.weight(1f))
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TechItem("Device Model", ride.deviceModel.ifBlank { "Unknown" }, Modifier.weight(1f))
                        TechItem(
                            "Android Version",
                            if (ride.androidVersion.isNotBlank()) "Android ${ride.androidVersion}" else "Unknown",
                            Modifier.weight(1f),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TechItem("Battery Used", "${ride.batteryUsed}% (${"%.2f".format(ride.batteryUsedPerMin)}%/min)", Modifier.weight(1f))
                        TechItem("Thermal State", if (ride.thermalThrottled) "Throttled (Hot)" else "Normal", Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(NavBeamIcons.ChevronUp, contentDescription = "Collapse details", tint = colors.textLo, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun TechItem(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = NavBeamTheme.colors
    Column(modifier = modifier) {
        Text(label, color = colors.textLo, fontSize = 10.sp, fontFamily = GeistFamily)
        Text(value, color = colors.textHi, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
    }
}

@Composable
private fun TrackSketch(points: List<GeoPoint>, modifier: Modifier) {
    val colors = NavBeamTheme.colors
    Canvas(modifier) {
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }
        val spanLat = (maxLat - minLat).coerceAtLeast(1e-6)
        val spanLng = (maxLng - minLng).coerceAtLeast(1e-6)
        val span = maxOf(spanLat, spanLng)
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = ((p.lng - minLng) / span * size.width).toFloat()
            val y = (size.height - (p.lat - minLat) / span * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, colors.primary, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val first = points.first()
        val last = points.last()
        drawCircle(
            colors.textHi,
            2.5f,
            Offset(
                ((first.lng - minLng) / span * size.width).toFloat(),
                (size.height - (first.lat - minLat) / span * size.height).toFloat(),
            ),
        )
        drawCircle(
            colors.primary,
            2.5f,
            Offset(
                ((last.lng - minLng) / span * size.width).toFloat(),
                (size.height - (last.lat - minLat) / span * size.height).toFloat(),
            ),
        )
    }
}

private fun fmtDate(ms: Long) = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(ms))
private fun fmtTime(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
private fun fmtDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
