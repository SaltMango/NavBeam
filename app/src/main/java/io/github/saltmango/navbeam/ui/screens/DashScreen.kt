package io.github.saltmango.navbeam.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.core.*
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.components.*
import io.github.saltmango.navbeam.ui.theme.*
import io.github.saltmango.navbeam.viewmodel.ConnStage
import io.github.saltmango.navbeam.viewmodel.DashViewModel
import io.github.saltmango.navbeam.data.SettingsPrefs
import io.github.saltmango.navbeam.dash.nav.VoiceManager
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.BottomSheetDefaults
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashScreen(
    vm: DashViewModel = viewModel(),
    onStopNavigation: (() -> Unit)? = null
) {
    val ui by vm.ui.collectAsState()
    val previewFrame by vm.previewFrame.collectAsState()
    val context = LocalContext.current
    val colors = NavBeamTheme.colors
    val infiniteTransition = rememberInfiniteTransition(label = "dash-effects")
    val activeProfile by io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.collectAsState()
    val isCircle = activeProfile.display.shape == io.github.saltmango.navbeam.dash.profile.DisplayShape.CIRCLE
    val bezelShape = if (isCircle) CircleShape else RoundedCornerShape(24.dp)
    val haloShape = if (isCircle) CircleShape else RoundedCornerShape(28.dp)

    var showQuickSettings by remember { mutableStateOf(false) }
    var keepAwake by remember { mutableStateOf(SettingsPrefs.getKeepAwake(context)) }
    var hasMapsKey by remember { mutableStateOf(io.github.saltmango.navbeam.data.MapsKeyManager.getKey(context).isNotBlank()) }
    val voiceManager = remember { VoiceManager.get(context) }

    var routeColorName by remember { mutableStateOf(SettingsPrefs.getRouteColor(context)) }
    var customRouteColorHex by remember { mutableStateOf(SettingsPrefs.getCustomRouteColorHex(context)) }
    var routeLineWidth by remember { mutableStateOf(SettingsPrefs.getRouteLineWidth(context)) }
    var showColorPickerDialog by remember { mutableStateOf(false) }

    val currentRouteColor = remember(routeColorName, customRouteColorHex) {
        val hex = when (routeColorName) {
            "Laser Purple" -> "#BF00FF"
            "Acid Pink" -> "#FF007F"
            "Fuchsia" -> "#FF00FF"
            "Black" -> "#000000"
            "Default Blue" -> "#4285F4"
            else -> customRouteColorHex
        }
        try {
            val cleanHex = hex.trim().removePrefix("#")
            val longVal = cleanHex.toLong(16)
            val fullHex = if (cleanHex.length <= 6) 0xFF000000 or longVal else longVal
            Color(fullHex)
        } catch (e: Exception) {
            Color(0xFFA040FF)
        }
    }

    LaunchedEffect(Unit) {
        keepAwake = SettingsPrefs.getKeepAwake(context)
        hasMapsKey = io.github.saltmango.navbeam.data.MapsKeyManager.getKey(context).isNotBlank()
    }

    val essentialPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }
    val requestedPermissions = remember {
        buildList {
            addAll(essentialPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    fun hasEssentialPermissions() = essentialPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var batteryOk by remember {
        mutableStateOf(io.github.saltmango.navbeam.util.DeviceReadiness.isIgnoringBatteryOptimizations(context))
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOk = io.github.saltmango.navbeam.util.DeviceReadiness.isIgnoringBatteryOptimizations(context)
        vm.connect()
    }

    fun connectWithBackgroundCheck() {
        if (io.github.saltmango.navbeam.util.DeviceReadiness.shouldPromptBatteryExemption(context)) {
            io.github.saltmango.navbeam.util.DeviceReadiness.markBatteryExemptionAsked(context)
            val launched = runCatching {
                batteryLauncher.launch(io.github.saltmango.navbeam.util.DeviceReadiness.batteryExemptionIntent(context))
            }.isSuccess
            if (!launched) vm.connect()
        } else {
            vm.connect()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val essentialOk = essentialPermissions.all { results[it] == true }
        if (essentialOk) connectWithBackgroundCheck()
    }

    var pan by remember { mutableStateOf(Offset.Zero) }
    val adjustMode = ui.adjustMode
    var joystickVelocity by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(ui.lastButton) {
        val b = ui.lastButton ?: return@LaunchedEffect
        when {
            b.startsWith("→") -> pan = Offset((pan.x - 12f).coerceIn(-46f, 46f), pan.y)
            b.startsWith("←") -> pan = Offset((pan.x + 12f).coerceIn(-46f, 46f), pan.y)
            b.startsWith("↓") -> pan = Offset(pan.x, (pan.y - 12f).coerceIn(-46f, 46f))
            b.startsWith("↑") -> pan = Offset(pan.x, (pan.y + 12f).coerceIn(-46f, 46f))
            b.startsWith("●") -> pan = Offset.Zero
        }
    }

    LaunchedEffect(joystickVelocity, adjustMode) {
        while (adjustMode && (joystickVelocity.x != 0f || joystickVelocity.y != 0f)) {
            pan = Offset(
                (pan.x - joystickVelocity.x * 2.4f).coerceIn(-46f, 46f),
                (pan.y - joystickVelocity.y * 2.4f).coerceIn(-46f, 46f),
            )
            vm.panBy(joystickVelocity.x * 4f, joystickVelocity.y * 4f)
            delay(16)
        }
    }

    val streaming = ui.stage == ConnStage.STREAMING
    val uiState = rememberUpdatedState(ui)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                batteryOk = io.github.saltmango.navbeam.util.DeviceReadiness.isIgnoringBatteryOptimizations(context)
                if (uiState.value.needsWifiOn &&
                    (context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
                        as android.net.wifi.WifiManager).isWifiEnabled &&
                    hasEssentialPermissions()
                ) {
                    vm.connect()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Dash view",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (streaming && ui.gpsStatus != io.github.saltmango.navbeam.viewmodel.GpsStatus.GOOD) {
                            navBeamChip(
                                if (ui.gpsStatus == io.github.saltmango.navbeam.viewmodel.GpsStatus.LOST) "GPS lost" else "GPS weak",
                                if (ui.gpsStatus == io.github.saltmango.navbeam.viewmodel.GpsStatus.LOST) ChipTone.Alert else ChipTone.Warn,
                            )
                        }
                        if (streaming && ui.thermal != "OK") {
                            navBeamChip(ui.thermal, if (ui.thermal == "Warm") ChipTone.Warn else ChipTone.Alert)
                        }
                        when (ui.stage) {
                            ConnStage.STREAMING -> navBeamChip("LIVE", ChipTone.Primary, dot = true)
                            ConnStage.WIFI, ConnStage.AUTH -> navBeamChip("Connecting…", ChipTone.Neutral)
                            ConnStage.OFFLINE -> navBeamChip("Offline", ChipTone.Off)
                            else -> {}
                        }
                        navBeamIconBtn(icon = NavBeamIcons.Gear, onClick = { showQuickSettings = true }, size = 32.dp)
                    }
                },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 18.dp)
            ) {
                // Connection Overlay (Push content down if visible)
                if (!streaming) {
                    navBeamCard(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                        glow = ui.stage == ConnStage.WIFI || ui.stage == ConnStage.AUTH
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    when {
                                        ui.stage == ConnStage.STREAMING -> "Streaming Live Map"
                                        ui.stage == ConnStage.CONNECTED -> "Motorcycle Connected"
                                        ui.stage == ConnStage.WIFI || ui.stage == ConnStage.AUTH -> if (ui.ssid.isNotBlank()) "Joining ${ui.ssid}…" else "Finding dash…"
                                        ui.stage == ConnStage.ERROR -> "Connection Failed"
                                        else -> "Connect to Display"
                                    },
                                    color = colors.textHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily
                                )
                                if (ui.stage == ConnStage.ERROR && !ui.errorMessage.isNullOrBlank()) {
                                    Text(
                                        text = ui.errorMessage!!,
                                        color = Alert,
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                            if (ui.stage == ConnStage.OFFLINE || ui.stage == ConnStage.ERROR) {
                                navBeamBtn(
                                    label = "Connect",
                                    onClick = {
                                        val wm = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                                        if (!wm.isWifiEnabled) {
                                            vm.connect()
                                            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)) }
                                        } else if (hasEssentialPermissions()) {
                                            connectWithBackgroundCheck()
                                        } else {
                                            permissionLauncher.launch(requestedPermissions)
                                        }
                                    },
                                    icon = NavBeamIcons.Wifi,
                                    variant = BtnVariant.Primary,
                                    size = BtnSize.Sm
                                )
                            } else if (ui.stage == ConnStage.CONNECTED) {
                                navBeamBtn("Start", onClick = { vm.startProjection() }, icon = NavBeamIcons.Navi, variant = BtnVariant.Primary, size = BtnSize.Sm)
                            } else {
                                navBeamBtn("Cancel", onClick = { vm.disconnect() }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
                            }
                        }
                    }
                }

                // FLEXIBLE CONTENT AREA (Auto-margin logic)
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Maneuver Pill (Top Auto Margin)
                    if (ui.maneuver != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(CircleShape).background(colors.surf1.copy(alpha = 0.92f)).border(1.dp, colors.line, CircleShape).padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Icon(NavBeamIcons.Navi, null, tint = if (ui.offRoute) Warn else Primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(if (ui.offRoute) "Rerouting…" else (ui.maneuver ?: ""), color = colors.textHi, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    } else {
                        // Empty spacer if no maneuver, to maintain vertical balance
                        Spacer(Modifier.height(36.dp))
                    }

                    Spacer(Modifier.weight(1f)) // AUTO MARGIN (Top)

                    // Main Viewport (Always Centered)
                    Box(contentAlignment = Alignment.Center) {
                        val haloAlpha by infiniteTransition.animateFloat(0.15f, 0.35f, animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Reverse), label = "vh")
                        Box(Modifier.size(250.dp).clip(haloShape).background(Brush.radialGradient(listOf(if (streaming) PrimaryGlow.copy(alpha = haloAlpha) else colors.line3.copy(alpha = 0.1f), Color.Transparent))))
                        Box(
                            modifier = Modifier.size(236.dp).clip(bezelShape).background(colors.bg0)
                                .border(5.dp, if (colors.bg0 == Bg0Dark) Brush.linearGradient(listOf(Color(0xFF2E3336), Color(0xFF0F1112))) else Brush.linearGradient(listOf(Color(0xFFE2E8F0), Color(0xFF94A3B8))), bezelShape)
                                .border(1.dp, colors.line2, bezelShape)
                                .border(1.dp, if (streaming) Primary.copy(alpha = 0.8f) else Color.Transparent, bezelShape),
                        ) {
                            if (ui.navMode == "Arrow") {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = NavBeamIcons.Navi,
                                            contentDescription = null,
                                            tint = Primary,
                                            modifier = Modifier.size(56.dp)
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        ui.nextTurnDistanceM?.let { dist ->
                                            val formatted = if (dist < 1000) "${dist.toInt()} m" else "%.1f km".format(dist / 1000.0)
                                            Text(
                                                text = formatted,
                                                color = colors.textHi,
                                                fontSize = 28.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = GeistMonoFamily
                                            )
                                            Spacer(Modifier.height(6.dp))
                                        }
                                        Text(
                                            text = ui.maneuver ?: "Follow route",
                                            color = colors.textHi,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            maxLines = 2
                                        )
                                    }
                                }
                            } else if (streaming && previewFrame != null) {
                                androidx.compose.foundation.Image(bitmap = previewFrame!!.asImageBitmap(), contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else if (streaming) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Preparing view…", color = colors.textLo, fontSize = 12.sp, fontFamily = GeistMonoFamily) }
                            } else if (hasMapsKey) {
                                NavBeamMap(
                                    riderLat = ui.riderLat,
                                    riderLng = ui.riderLng,
                                    dest = ui.destLatLng,
                                    routePoints = ui.routePoints,
                                    hasLocationPermission = hasEssentialPermissions(),
                                    routeColor = currentRouteColor,
                                    routeWidth = routeLineWidth,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f)) // AUTO MARGIN (Bottom)

                    // Telemetry HUD (Anchored Bottom of Zone)
                    if (ui.remainingKm != null || ui.etaMinutes != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ui.remainingKm?.let { km ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clip(CircleShape).background(colors.surf1.copy(alpha = 0.92f)).border(1.dp, colors.line, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(NavBeamIcons.Navi, null, tint = Primary, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (km >= 10) "%.0f".format(km) else "%.1f".format(km), color = colors.textHi, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                                    Text(" km", color = colors.textLo, fontSize = 10.sp, fontFamily = GeistMonoFamily)
                                }
                            }
                            if (ui.etaMinutes != null || ui.etaArrival != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clip(CircleShape).background(colors.surf1.copy(alpha = 0.92f)).border(1.dp, colors.line, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(NavBeamIcons.Clock, null, tint = Primary, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    ui.etaMinutes?.let { min ->
                                        Text(min.toString(), color = colors.textHi, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                                        Text(" min", color = colors.textLo, fontSize = 10.sp, fontFamily = GeistMonoFamily)
                                    }
                                    if (ui.etaMinutes != null && ui.etaArrival != null) Text(" • ", color = colors.line, fontSize = 12.sp)
                                    ui.etaArrival?.let { arrival ->
                                        Text(arrival, color = colors.textHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                                    }
                                }
                            }
                        }
                    }
                }

                // COMPACT COCKPIT CONTROLS (Always Sticky at Bottom)
                if (ui.navMode == "Map") {
                    navBeamCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), padding = 12.dp) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp).clip(CircleShape).background(colors.bg0).border(1.dp, colors.line2, CircleShape)) {
                                Joystick(size = 90.dp, onMove = { v -> joystickVelocity = if (adjustMode) v else Offset.Zero })
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    navBeamIconBtn(icon = NavBeamIcons.Minus, onClick = { vm.zoomOut() }, size = 42.dp)
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("z${ui.mapZoom}", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                                        Eyebrow("ZOOM")
                                    }
                                    navBeamIconBtn(icon = NavBeamIcons.Plus, onClick = { vm.zoomIn() }, size = 42.dp)
                                }
                                navBeamBtn(label = "Recenter", onClick = { vm.recenter(); pan = Offset.Zero }, icon = NavBeamIcons.Recenter, variant = BtnVariant.Secondary, size = BtnSize.Sm, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

            }

            // Fixed Stop Button
            if (streaming) {
                Box(modifier = Modifier.fillMaxWidth().background(colors.bg0).border(1.dp, colors.line, androidx.compose.ui.graphics.RectangleShape).padding(16.dp)) {
                    navBeamBtn("Stop Navigation", onClick = { vm.stopProjection(); vm.exitNavigation(); onStopNavigation?.invoke() }, icon = NavBeamIcons.Power, variant = BtnVariant.Danger, size = BtnSize.Lg, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (showQuickSettings) {
        ModalBottomSheet(onDismissRequest = { showQuickSettings = false }, containerColor = colors.surf1, dragHandle = { BottomSheetDefaults.DragHandle(color = colors.line) }) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Quick Ride Settings", color = colors.textHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                if (ui.navMode == "Map") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow("Map Rotation")
                        navBeamSegmented(options = listOf("North-Up", "Heading-Up"), selected = if (ui.headingUp) "Heading-Up" else "North-Up", onSelect = { vm.toggleHeadingUp() }, modifier = Modifier.fillMaxWidth())
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow("Stream Quality")
                        navBeamSegmented(options = listOf("Max", "Balanced", "Battery Saver", "Custom"), selected = ui.streamQuality, onSelect = { vm.setStreamQuality(it) }, modifier = Modifier.fillMaxWidth())
                    }

                    // Route Line Color Settings
                    val colorOptions = listOf(
                        "Laser Purple" to "#BF00FF",
                        "Acid Pink" to "#FF007F",
                        "Fuchsia" to "#FF00FF",
                        "Black" to "#000000",
                        "Default Blue" to "#4285F4",
                        "Custom" to "#808080"
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow("Route Line Color")

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            colorOptions.forEach { (name, hex) ->
                                val isSelected = routeColorName == name
                                val badgeColor = remember(name, hex, customRouteColorHex) {
                                    try {
                                        val finalHex = if (name == "Custom") customRouteColorHex else hex
                                        val cleanHex = finalHex.trim().removePrefix("#")
                                        val longVal = cleanHex.toLong(16)
                                        val fullHex = if (cleanHex.length <= 6) 0xFF000000 or longVal else longVal
                                        Color(fullHex)
                                    } catch (e: Exception) {
                                        Color.Gray
                                    }
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            if (name == "Custom") {
                                                showColorPickerDialog = true
                                            } else {
                                                routeColorName = name
                                                SettingsPrefs.setRouteColor(context, name)
                                            }
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(badgeColor)
                                            .border(
                                                width = if (isSelected) 2.5.dp else 1.dp,
                                                color = if (isSelected) colors.textHi else colors.line3,
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (name == "Custom") {
                                            Icon(
                                                imageVector = NavBeamIcons.Edit,
                                                contentDescription = null,
                                                tint = colors.textHi,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = name.split(" ").last(), // "Purple", "Pink", "Fuchsia", "Black", "Blue", "Custom"
                                        color = if (isSelected) colors.textHi else colors.textLo,
                                        fontSize = 11.sp,
                                        fontFamily = GeistFamily,
                                        modifier = Modifier.padding(top = 4.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    // Route Line Width Settings
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Eyebrow("Route Line Thickness")
                            Text(
                                text = "${routeLineWidth.toInt()} dp",
                                color = colors.textHi,
                                fontSize = 13.sp,
                                fontFamily = GeistMonoFamily
                            )
                        }
                        androidx.compose.material3.Slider(
                            value = routeLineWidth,
                            onValueChange = { width ->
                                routeLineWidth = width
                                SettingsPrefs.setRouteLineWidth(context, width)
                            },
                            valueRange = 4f..20f,
                            steps = 15,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = Primary,
                                activeTrackColor = Primary,
                                inactiveTrackColor = colors.line3,
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { keepAwake = !keepAwake; SettingsPrefs.setKeepAwake(context, keepAwake) }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Keep dash awake", color = colors.textHi, fontSize = 14.sp, fontFamily = GeistFamily)
                        Text("Prevent dash display sleep while connected", color = colors.textLo, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    navBeamToggle(on = keepAwake, onChange = { keepAwake = it; SettingsPrefs.setKeepAwake(context, it) })
                }
            }
        }
        if (showColorPickerDialog) {
            ColorPickerDialog(
                initialColorHex = customRouteColorHex,
                onSave = { newColor ->
                    customRouteColorHex = newColor
                    SettingsPrefs.setCustomRouteColorHex(context, newColor)
                    routeColorName = "Custom"
                    SettingsPrefs.setRouteColor(context, "Custom")
                    showColorPickerDialog = false
                },
                onDismiss = {
                    showColorPickerDialog = false
                }
            )
        }
    }
}
