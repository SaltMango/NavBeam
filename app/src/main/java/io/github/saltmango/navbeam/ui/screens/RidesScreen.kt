package io.github.saltmango.navbeam.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.saltmango.navbeam.data.Ride
import io.github.saltmango.navbeam.dash.nav.PolylineCodec
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

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        ScreenHeader(
            eyebrow = "Telemetry",
            title = "Ride history",
            hint = "Every connected ride is saved here — distance, duration, and the route you took.",
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 24.dp)
        ) {
            if (rides.isEmpty()) {
                EmptyRides()
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
    val totalKm = rides.sumOf { it.distanceKm }
    val totalSec = rides.sumOf { it.durationSec }
    navBeamCard(modifier = Modifier.fillMaxWidth(), padding = 18.dp) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("${rides.size}", "rides")
            Stat("%.0f".format(totalKm), "km total")
            Stat(fmtDuration(totalSec), "time")
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val colors = NavBeamTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Primary, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = GeistFamily)
        Text(label, color = colors.textLo, fontSize = 12.sp, fontFamily = GeistFamily)
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
    navBeamCard(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        padding = 16.dp
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // Track sketch
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(colors.surf2),
                ) {
                    if (track.size >= 2) TrackSketch(track, Modifier.fillMaxSize().padding(8.dp))
                    else Icon(NavBeamIcons.Route, contentDescription = null, tint = colors.textLo, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(fmtDate(ride.startMs), color = colors.textHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text(
                        "${fmtTime(ride.startMs)} – ${fmtTime(ride.endMs)}",
                        color = colors.textLo, fontSize = 12.sp, fontFamily = GeistFamily,
                    )
                }
                Text("%.1f km".format(ride.distanceKm), color = Primary, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = GeistFamily)
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat(fmtDuration(ride.durationSec), "duration")
                MiniStat("%.0f km/h".format(ride.avgSpeedKmh), "avg")
                MiniStat("%.0f km/h".format(ride.maxSpeedKmh), "max")
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(colors.surf2)
                        .clickable { onDelete() },
                ) {
                    Icon(NavBeamIcons.X, contentDescription = "Delete ride", tint = colors.textLo, modifier = Modifier.size(16.dp))
                }
            }

            // ─── Route & Battery Info Summary (Always visible if present) ───
            if (!ride.destinationName.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(NavBeamIcons.LocationPin, contentDescription = null, tint = Primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${ride.startLocationName ?: "Start"} → ${ride.destinationName}",
                        color = colors.textHi, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = GeistFamily
                    )
                }
                if (!ride.stops.isNullOrBlank()) {
                    Text(
                        text = "Stops: ${ride.stops}",
                        color = colors.textLo, fontSize = 11.sp, fontFamily = GeistFamily,
                        modifier = Modifier.padding(start = 20.dp, top = 2.dp)
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
                            color = colors.textHi, fontSize = 12.sp, fontFamily = GeistFamily
                        )
                    }
                    if (ride.startBatteryTemp > 0 || ride.endBatteryTemp > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(NavBeamIcons.Thermo, contentDescription = null, tint = colors.textLo, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${"%.1f".format(ride.startBatteryTemp)}°C → ${"%.1f".format(ride.endBatteryTemp)}°C",
                                color = colors.textHi, fontSize = 12.sp, fontFamily = GeistFamily
                            )
                        }
                    }
                }
            }

            // Expand hint arrow if not expanded and we have technical stats to show
            if (!expanded && (ride.avgFps > 0 || ride.recalcCount > 0 || ride.reconnectCount > 0 || ride.startBattery >= 0)) {
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(
                        NavBeamIcons.ChevronDown,
                        contentDescription = "Show details",
                        tint = colors.textLo,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // ─── Expanded Tech Diagnostics Details ───
            if (expanded) {
                Spacer(Modifier.height(14.dp))
                navBeamDivider()
                Spacer(Modifier.height(10.dp))
                
                Text(
                    text = "TECHNICAL METRICS", 
                    color = colors.textLo, 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold, 
                    fontFamily = GeistFamily
                )
                Spacer(Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Streaming section
                    if (ride.avgFps > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(modifier = Modifier.weight(1f)) {
                                TechItem("Stream Rate", "${"%.1f".format(ride.avgFps)} fps")
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                TechItem("Bitrate (Avg)", "${"%.0f".format(ride.avgBitrateKbps)} Kbps")
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(modifier = Modifier.weight(1f)) {
                                TechItem("Video Data", "%.1f MB".format(ride.totalBytesSent / (1024.0 * 1024.0)))
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                TechItem("Encoder / Mode", "${ride.encoderMode} (${ride.streamQuality})")
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(modifier = Modifier.weight(1f)) {
                                TechItem("Encode Latency", "${"%.1f".format(ride.avgEncodeTimeMs)} ms")
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                TechItem("Network Latency", "${"%.1f".format(ride.avgNetworkLatencyMs)} ms")
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(modifier = Modifier.weight(1f)) {
                                TechItem("Projection Mode", ride.navMode)
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                TechItem("Frame Drops", "${ride.skippedFrames} frames")
                            }
                        }
                    }

                    // GPS & Link Quality
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            TechItem("GPS Accuracy", "${"%.1f".format(ride.gpsAccuracyAvg)} m")
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            TechItem("GPS Fixes", "${ride.gpsFixCount} updates")
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            TechItem("GPS Interval", "${"%.1f".format(ride.gpsIntervalAvgMs / 1000.0)} s")
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            TechItem("GPS Signal Drops", "${ride.gpsDropsCount}")
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            TechItem("Reroutes (Recalc)", "${ride.recalcCount}")
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            TechItem("Link Reconnects", "${ride.reconnectCount}")
                        }
                    }

                    // System & Hardware
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            TechItem("Device Model", ride.deviceModel.ifBlank { "Unknown" })
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            TechItem("Android Version", if (ride.androidVersion.isNotBlank()) "Android ${ride.androidVersion}" else "Unknown")
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            TechItem("Battery Used", "${ride.batteryUsed}% (${"%.2f".format(ride.batteryUsedPerMin)}%/min)")
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            TechItem("Thermal State", if (ride.thermalThrottled) "Throttled (Hot)" else "Normal")
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(
                        NavBeamIcons.ChevronUp,
                        contentDescription = "Collapse details",
                        tint = colors.textLo,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TechItem(label: String, value: String) {
    val colors = NavBeamTheme.colors
    Column {
        Text(label, color = colors.textLo, fontSize = 10.sp, fontFamily = GeistFamily)
        Text(value, color = colors.textHi, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    val colors = NavBeamTheme.colors
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, color = colors.textHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
        Text(label, color = colors.textLo, fontSize = 11.sp, fontFamily = GeistFamily)
    }
}

@Composable
private fun TrackSketch(points: List<io.github.saltmango.navbeam.dash.nav.GeoPoint>, modifier: Modifier) {
    val colors = NavBeamTheme.colors
    Canvas(modifier) {
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }; val maxLng = points.maxOf { it.lng }
        val spanLat = (maxLat - minLat).coerceAtLeast(1e-6)
        val spanLng = (maxLng - minLng).coerceAtLeast(1e-6)
        val span = maxOf(spanLat, spanLng)   // keep aspect square-ish
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = ((p.lng - minLng) / span * size.width).toFloat()
            val y = (size.height - (p.lat - minLat) / span * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Primary, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // start (green-ish) + end (primary) dots
        val first = points.first(); val last = points.last()
        drawCircle(colors.textHi, 2.5f, Offset(((first.lng - minLng) / span * size.width).toFloat(),
            (size.height - (first.lat - minLat) / span * size.height).toFloat()))
        drawCircle(Primary, 2.5f, Offset(((last.lng - minLng) / span * size.width).toFloat(),
            (size.height - (last.lat - minLat) / span * size.height).toFloat()))
    }
}

@Composable
private fun EmptyRides() {
    val colors = NavBeamTheme.colors
    navBeamCard(modifier = Modifier.fillMaxWidth(), padding = 28.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(56.dp).clip(CircleShape).background(colors.surf2),
            ) {
                Icon(NavBeamIcons.Route, contentDescription = null, tint = colors.textLo, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("No rides recorded yet", color = colors.textHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            Spacer(Modifier.height(6.dp))
            Text(
                "Connect to your dash to start a ride — it's saved automatically when you disconnect.",
                color = colors.textLo, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

private fun fmtDate(ms: Long) = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(ms))
private fun fmtTime(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
private fun fmtDuration(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
