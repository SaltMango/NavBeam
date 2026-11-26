package io.github.saltmango.navbeam.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.saltmango.navbeam.dash.nav.VoiceManager
import io.github.saltmango.navbeam.dash.nav.VoiceMode
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.data.SettingsPrefs
import io.github.saltmango.navbeam.data.MapsKeyManager
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.components.*
import io.github.saltmango.navbeam.ui.theme.*
import io.github.saltmango.navbeam.viewmodel.ConnectionState
import io.github.saltmango.navbeam.viewmodel.RidesViewModel
import io.github.saltmango.navbeam.viewmodel.RouteViewModel
import io.github.saltmango.navbeam.viewmodel.DashViewModel
import io.github.saltmango.navbeam.viewmodel.ConnStage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HOME_COMMUNITY_GITHUB_URL = "https://github.com/SaltMango/NavBeam"
private const val HOME_TEMPLATE_SCHEMA_URL = "https://github.com/SaltMango/NavBeam/blob/main/DashProfile.json"

@Composable
fun HomeScreen(
    conn: ConnectionState,
    onNavigate: (String) -> Unit,
    routeViewModel: RouteViewModel = viewModel(),
    ridesViewModel: RidesViewModel = viewModel(),
    dashViewModel: DashViewModel = viewModel(),
) {
    val ctx = LocalContext.current
    val colors = NavBeamTheme.colors
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current



    val dashUi by dashViewModel.ui.collectAsState()
    val activeProfile by DashProfileManager.activeProfile.collectAsState()

    // Permissions gate for connecting to the dash
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
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    var batteryOk by remember {
        mutableStateOf(io.github.saltmango.navbeam.util.DeviceReadiness.isIgnoringBatteryOptimizations(ctx))
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOk = io.github.saltmango.navbeam.util.DeviceReadiness.isIgnoringBatteryOptimizations(ctx)
        dashViewModel.connect()
    }

    val batteryWarningLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOk = io.github.saltmango.navbeam.util.DeviceReadiness.isIgnoringBatteryOptimizations(ctx)
    }



    fun connectWithBackgroundCheck() {
        if (io.github.saltmango.navbeam.util.DeviceReadiness.shouldPromptBatteryExemption(ctx)) {
            io.github.saltmango.navbeam.util.DeviceReadiness.markBatteryExemptionAsked(ctx)
            val launched = runCatching {
                batteryLauncher.launch(io.github.saltmango.navbeam.util.DeviceReadiness.batteryExemptionIntent(ctx))
            }.isSuccess
            if (!launched) dashViewModel.connect()
        } else {
            dashViewModel.connect()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val essentialOk = essentialPermissions.all { results[it] == true }
        if (essentialOk) connectWithBackgroundCheck()
    }

    val saved by routeViewModel.saved.collectAsState()
    val recents by routeViewModel.recents.collectAsState()
    val searchQuery by routeViewModel.searchQuery.collectAsState()
    val predictions by routeViewModel.predictions.collectAsState()
    val searchError by routeViewModel.searchError.collectAsState()
    val routeState by routeViewModel.state.collectAsState()
    val rides by ridesViewModel.rides.collectAsState()


    // 2. Dash status mapping (avoiding trademarks)
    val status = when {
        dashUi.stage == ConnStage.STREAMING -> Triple("Streaming Live Map", "Active on motorcycle display", colors.primary)
        dashUi.stage == ConnStage.CONNECTED -> Triple("Motorcycle Connected", "Link established", colors.primary)
        dashUi.stage == ConnStage.WIFI || dashUi.stage == ConnStage.AUTH -> Triple("Searching…", "Establishing link to motorcycle", colors.warn)
        dashUi.stage == ConnStage.ERROR -> Triple("Connection Error", dashUi.errorMessage ?: "Check display power & proximity", colors.alert)
        else -> Triple("Offline", "Bike not connected", colors.offline)
    }
    val (statusLabel, statusSub, statusDot) = status

    val isConnected = dashUi.stage == ConnStage.CONNECTED || dashUi.stage == ConnStage.STREAMING
    val isSearching = dashUi.stage == ConnStage.WIFI || dashUi.stage == ConnStage.AUTH
    val isStreaming = dashUi.stage == ConnStage.STREAMING

    // Pulse animation for connected dot
    val infiniteTransition = rememberInfiniteTransition(label = "dot-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        1f, 0.35f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    // 3. Pre-ride quick settings controllers
    val voiceManager = remember { VoiceManager.get(ctx) }
    val voiceMode by voiceManager.mode.collectAsState()
    var autoConnect by remember { mutableStateOf(SettingsPrefs.getAutoConnect(ctx)) }
    var screenOff   by remember { mutableStateOf(SettingsPrefs.getScreenOff(ctx)) }
    var keepAwake   by remember { mutableStateOf(SettingsPrefs.getKeepAwake(ctx)) }
    var hasMapsKey by remember { mutableStateOf(MapsKeyManager.getKey(ctx).isNotBlank()) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyValue by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(SettingsPrefs.getMapsApiKey(ctx))) }

    // Keep settings synchronized when Home tab becomes active
    LaunchedEffect(Unit) {
        autoConnect = SettingsPrefs.getAutoConnect(ctx)
        screenOff = SettingsPrefs.getScreenOff(ctx)
        keepAwake = SettingsPrefs.getKeepAwake(ctx)
        hasMapsKey = MapsKeyManager.getKey(ctx).isNotBlank()
        batteryOk = io.github.saltmango.navbeam.util.DeviceReadiness.isIgnoringBatteryOptimizations(ctx)
    }

    // Keep state updated on resume (e.g. returning from settings)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val uiState = rememberUpdatedState(dashUi)
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                batteryOk = io.github.saltmango.navbeam.util.DeviceReadiness.isIgnoringBatteryOptimizations(ctx)
                hasMapsKey = MapsKeyManager.getKey(ctx).isNotBlank()
                
                // If we were waiting for Wi-Fi and it's now on, resume connection
                val wm = ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                if (uiState.value.needsWifiOn && wm.isWifiEnabled && hasEssentialPermissions()) {
                    dashViewModel.connect()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {

        NavBeamScreenHeader(wordmark = true)

        if (!activeProfile.isConfigured) {
            NoProfileHomeContent(
                hasMapsKey = hasMapsKey,
                onSetupProfile = { onNavigate("profile_setup") },
                onOpenApiKeyDialog = { showApiKeyDialog = true },
                onOpenExternalUrl = { url ->
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }.onFailure {
                        Toast.makeText(ctx, "No browser app found to open link.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // Active Route HUD Card
            val activeDest = routeState.destination
            if (dashUi.stage == ConnStage.STREAMING && activeDest != null) {
                NavBeamCard(
                    glow = true,
                    padding = 16.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.primaryTint)
                            ) {
                                Icon(
                                    imageVector = NavBeamIcons.Navi,
                                    contentDescription = "Active Navigation",
                                    tint = colors.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "ACTIVE GUIDANCE",
                                    color = colors.primary,
                                    style = NavBeamTheme.typography.badge,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = activeDest.name.ifBlank { "Dropped Pin" },
                                    color = colors.textHi,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GeistFamily,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            NavBeamBadge(label = "LIVE", tone = ChipTone.Primary)
                        }

                        val metrics = listOfNotNull(
                            routeState.distanceText,
                            routeState.durationText,
                            routeState.etaText?.let { "ETA $it" }
                        ).joinToString("  ·  ")

                        if (metrics.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            NavBeamDivider()
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = metrics,
                                color = colors.textHi,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GeistFamily,
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            NavBeamButton(
                                label = "Stop Navigation",
                                onClick = {
                                    dashViewModel.stopProjection()
                                    dashViewModel.exitNavigation()
                                    routeViewModel.clear()
                                },
                                variant = BtnVariant.Danger,
                                size = BtnSize.Sm,
                                modifier = Modifier.weight(1f)
                            )
                            NavBeamButton(
                                label = "Resume View",
                                onClick = { onNavigate("dash") },
                                icon = NavBeamIcons.Navi,
                                variant = BtnVariant.Primary,
                                size = BtnSize.Sm,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // 0. Maps API Key Warning
            if (!hasMapsKey) {
                NavBeamCard(
                    glow = true,
                    padding = 14.dp,
                    onClick = { showApiKeyDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.alertTint)
                        ) {
                            Icon(
                                imageVector = NavBeamIcons.LocationPin,
                                contentDescription = "Maps Key Warning",
                                tint = colors.alert,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "MAPS API KEY REQUIRED",
                                color = colors.alert,
                                style = NavBeamTheme.typography.badge,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Google Maps API Key is missing. Tap to configure in Menu settings.",
                                color = colors.textHi,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GeistFamily,
                            )
                        }
                        Icon(
                            imageVector = NavBeamIcons.ChevronRight,
                            contentDescription = null,
                            tint = colors.textLo,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // Battery Optimization Warning
            if (!batteryOk) {
                NavBeamCard(
                    glow = true,
                    padding = 14.dp,
                    onClick = {
                        val launched = runCatching {
                            batteryWarningLauncher.launch(io.github.saltmango.navbeam.util.DeviceReadiness.batteryExemptionIntent(ctx))
                        }.isSuccess
                        if (!launched) {
                            runCatching {
                                ctx.startActivity(io.github.saltmango.navbeam.util.DeviceReadiness.appSettingsIntent(ctx))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.warnTint)
                        ) {
                            Icon(
                                imageVector = NavBeamIcons.Zap,
                                contentDescription = "Battery Optimization Warning",
                                tint = colors.warn,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "BATTERY OPTIMIZATION ACTIVE",
                                color = colors.warn,
                                style = NavBeamTheme.typography.badge,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "The OS may kill navigation streaming when screen is off. Tap to allow background running.",
                                color = colors.textHi,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GeistFamily,
                            )
                        }
                        Icon(
                            imageVector = NavBeamIcons.ChevronRight,
                            contentDescription = null,
                            tint = colors.textLo,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }


            // 2. Connection Card — full card with inline connect button
            NavBeamCard(
                glow = isConnected,
                padding = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {

                    // Top row: icon + status info
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(52.dp)
                        ) {
                            if (isConnected) {
                                Box(
                                    Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .background(colors.primary.copy(alpha = 0.12f * pulseAlpha))
                                )
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(colors.primary.copy(alpha = 0.2f))
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isConnected) colors.primary
                                        else colors.surf2
                                    )
                                    .border(
                                        1.dp,
                                        if (isConnected) Color.Transparent else colors.line2,
                                        RoundedCornerShape(12.dp)
                                    ),
                            ) {
                                Icon(
                                    imageVector = if (isConnected) NavBeamIcons.Motor else NavBeamIcons.Wifi,
                                    contentDescription = null,
                                    tint = if (isConnected) colors.onPrimary else colors.textLo,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = statusLabel,
                                    color = colors.textHi,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GeistFamily,
                                    letterSpacing = (-0.3).sp,
                                )
                                if (isStreaming) {
                                    NavBeamBadge(label = "LIVE", tone = ChipTone.Primary)
                                } else if (isSearching) {
                                    Box(
                                        Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(statusDot)
                                    )
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = statusSub,
                                color = colors.textLo,
                                fontSize = 12.5.sp,
                                fontFamily = GeistFamily
                            )
                        }
                    }

                    NavBeamDivider()

                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        if (dashUi.stage == ConnStage.OFFLINE || dashUi.stage == ConnStage.ERROR) {
                            NavBeamButton(
                                label = "Connect",
                                onClick = {
                                    val wm = ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                                    if (!wm.isWifiEnabled) {
                                        dashViewModel.connect()
                                        runCatching {
                                            ctx.startActivity(android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI))
                                        }
                                    } else if (hasEssentialPermissions()) {
                                        connectWithBackgroundCheck()
                                    } else {
                                        permissionLauncher.launch(requestedPermissions)
                                    }
                                },
                                icon = NavBeamIcons.Wifi,
                                variant = BtnVariant.Primary,
                                size = BtnSize.Sm,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            NavBeamButton(
                                label = when (dashUi.stage) {
                                    ConnStage.CONNECTED, ConnStage.STREAMING -> "Disconnect"
                                    else -> "Cancel"
                                },
                                onClick = { dashViewModel.disconnect() },
                                variant = when (dashUi.stage) {
                                    ConnStage.CONNECTED, ConnStage.STREAMING -> BtnVariant.Danger
                                    else -> BtnVariant.Ghost
                                },
                                size = BtnSize.Sm,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (dashUi.stage == ConnStage.WIFI || dashUi.stage == ConnStage.AUTH) {
                        NavBeamProgressBar(height = 3.dp)
                    }
                }
            }

            // 3. Search Bar
            NavBeamSearchBar(
                query = searchQuery,
                onQueryChange = { routeViewModel.setSearchQuery(it) },
                onClear = {
                    routeViewModel.clearSearch()
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
                placeholder = "Search for a destination…",
                onSearch = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
            )

            // Search predictions dropdown
            if (predictions.isNotEmpty() || searchError != null) {
                NavBeamCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    padding = 4.dp
                ) {
                    if (searchError != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp, horizontal = 12.dp)
                        ) {
                            Text(
                                text = searchError!!,
                                color = colors.alert,
                                fontSize = 13.5.sp,
                                fontFamily = GeistFamily
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            predictions.forEachIndexed { index, prediction ->
                                if (index > 0) NavBeamDivider(Modifier.padding(horizontal = 8.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            routeViewModel.selectPrediction(prediction)
                                            routeViewModel.clearSearch()
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                            onNavigate("route")
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = prediction.getPrimaryText(null).toString(),
                                        color = colors.textHi,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = GeistFamily
                                    )
                                    val secondary = prediction.getSecondaryText(null).toString()
                                    if (secondary.isNotEmpty()) {
                                        Text(
                                            text = secondary,
                                            color = colors.textLo,
                                            fontSize = 12.sp,
                                            fontFamily = GeistFamily,
                                            modifier = Modifier.padding(top = 2.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Quick Pins Carousel
            if (saved.isNotEmpty() || recents.isNotEmpty()) {
                Column {
                    NavBeamEyebrow("Quick Pins", Modifier.padding(bottom = 8.dp, start = 2.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(saved) { loc ->
                            QuickPinChip(
                                name = loc.name,
                                icon = NavBeamIcons.LocationPin,
                                onClick = {
                                    routeViewModel.selectSaved(loc)
                                    onNavigate("route")
                                }
                            )
                        }
                        items(recents) { loc ->
                            QuickPinChip(
                                name = loc.name.ifBlank { "Dropped Pin" },
                                icon = NavBeamIcons.History,
                                onClick = {
                                    routeViewModel.selectRecent(loc)
                                    onNavigate("route")
                                }
                            )
                        }
                    }
                }
            }

            // 5. Quick Settings — tappable toggle tiles in a 2x2 grid
            Column {
                NavBeamEyebrow("Ride Settings", Modifier.padding(bottom = 8.dp, start = 2.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        NavBeamQuickTile(
                            icon = if (voiceMode == VoiceMode.OFF) NavBeamIcons.SpeakerOff else NavBeamIcons.Speaker,
                            label = "Voice",
                            value = when (voiceMode) {
                                VoiceMode.OFF -> "Off"
                                VoiceMode.TONE -> "Tone"
                                VoiceMode.FULL -> "Voice"
                            },
                            active = voiceMode != VoiceMode.OFF,
                            onClick = {
                                voiceManager.setMode(
                                    when (voiceMode) {
                                        VoiceMode.OFF -> VoiceMode.TONE
                                        VoiceMode.TONE -> VoiceMode.FULL
                                        VoiceMode.FULL -> VoiceMode.OFF
                                    },
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )

                        NavBeamQuickTile(
                            icon = NavBeamIcons.Sync,
                            label = "Auto-Connect",
                            value = if (autoConnect) "On Near" else "Off",
                            active = autoConnect,
                            onClick = {
                                val nextVal = !autoConnect
                                autoConnect = nextVal
                                SettingsPrefs.setAutoConnect(ctx, nextVal)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        NavBeamQuickTile(
                            icon = NavBeamIcons.Power,
                            label = "Screen Off",
                            value = if (screenOff) "On" else "Off",
                            active = screenOff,
                            onClick = {
                                val nextVal = !screenOff
                                screenOff = nextVal
                                SettingsPrefs.setScreenOff(ctx, nextVal)
                            },
                            modifier = Modifier.weight(1f),
                        )

                        NavBeamQuickTile(
                            icon = NavBeamIcons.Clock,
                            label = "Keep Dash Awake",
                            value = if (keepAwake) "Always" else "Off",
                            active = keepAwake,
                            onClick = {
                                val nextVal = !keepAwake
                                keepAwake = nextVal
                                SettingsPrefs.setKeepAwake(ctx, nextVal)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 6. Last Ride Telemetry Card
            val lastRide = remember(rides) { rides.firstOrNull() }
            if (lastRide != null) {
                Column {
                    NavBeamEyebrow("Last Ride", Modifier.padding(bottom = 8.dp, start = 2.dp))
                    NavBeamCard(
                        modifier = Modifier.fillMaxWidth(),
                        padding = 16.dp,
                        onClick = { onNavigate("rides") }
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.primaryTint),
                                ) {
                                    Icon(
                                        imageVector = NavBeamIcons.Route,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
                                            .format(Date(lastRide.startMs)),
                                        color = colors.textHi,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = GeistFamily
                                    )
                                    Text(
                                        text = "View full ride details",
                                        color = colors.textLo,
                                        fontSize = 12.sp,
                                        fontFamily = GeistFamily
                                    )
                                }
                                Icon(
                                    imageVector = NavBeamIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = colors.textLo,
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            Spacer(Modifier.height(14.dp))
                            NavBeamDivider()
                            Spacer(Modifier.height(14.dp))

                            Row(modifier = Modifier.fillMaxWidth()) {
                                NavBeamStat(
                                    value = "%.1f".format(lastRide.distanceKm),
                                    label = "km",
                                    valueColor = colors.primary,
                                    horizontalAlignment = Alignment.Start,
                                    modifier = Modifier.weight(1f),
                                )
                                val sec = lastRide.durationSec
                                val h = sec / 3600
                                val m = (sec % 3600) / 60
                                NavBeamStat(
                                    value = if (h > 0) "${h}h ${m}m" else "${m}m",
                                    label = "duration",
                                    horizontalAlignment = Alignment.Start,
                                    modifier = Modifier.weight(1f),
                                )
                                NavBeamStat(
                                    value = "%.0f".format(lastRide.avgSpeedKmh),
                                    label = "km/h avg",
                                    horizontalAlignment = Alignment.Start,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

        }
        }
    }

    if (showApiKeyDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val trimmed = apiKeyValue.text.trim()
                        SettingsPrefs.setMapsApiKey(ctx, trimmed)
                        MapsKeyManager.applyKey(ctx)
                        hasMapsKey = trimmed.isNotBlank()
                        showApiKeyDialog = false
                    }
                ) {
                    Text("Save", color = colors.primary)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showApiKeyDialog = false }) {
                    Text("Cancel", color = colors.textMid)
                }
            },
            title = { Text("Google Maps API Key", color = colors.textHi) },
            text = {
                Column {
                    Text(
                        "Paste your Google Maps API Key to enable maps, routing, and navigation. Get one from the Google Cloud Console — enable Maps SDK for Android and restrict it to this app's package name.",
                        color = colors.textLo, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp)
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = apiKeyValue,
                        onValueChange = { apiKeyValue = it },
                        label = { Text("API Key") },
                        placeholder = { Text("AIzaSy...") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            containerColor = colors.surf1,
        )
    }
}

@Composable
private fun QuickPinChip(
    name: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val colors = NavBeamTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surf2)
            .border(1.dp, colors.line2, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            color = colors.textHi,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistFamily,
            maxLines = 1,
        )
    }
}

@Composable
private fun NoProfileHomeContent(
    hasMapsKey: Boolean,
    onSetupProfile: () -> Unit,
    onOpenApiKeyDialog: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NavBeamTheme.colors

    BoxWithConstraints(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 28.dp),
        ) {
            if (!hasMapsKey) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.alertTint)
                        .border(1.dp, colors.alert.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenApiKeyDialog)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = NavBeamIcons.LocationPin,
                        contentDescription = "Maps Key Warning",
                        tint = colors.alert,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Maps API key missing · Tap to add",
                        color = colors.textHi,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = GeistFamily,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = NavBeamIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.textLo,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Missing profile emblem
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(104.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(colors.alertTint)
                        .border(1.dp, colors.alert.copy(alpha = 0.25f), CircleShape),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(colors.surf1)
                        .border(1.5.dp, colors.alert.copy(alpha = 0.45f), CircleShape),
                ) {
                    Icon(
                        imageVector = NavBeamIcons.MissingFile,
                        contentDescription = "Missing Display Profile",
                        tint = colors.alert,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            NavBeamChip(label = "NO PROFILE LOADED", tone = ChipTone.Alert, dot = true)

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Load a Display Profile",
                color = colors.textHi,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistFamily,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "Import your motorcycle display's .json profile to enable Wi-Fi linking, off-screen video encoding, and turn-by-turn dash navigation.",
                color = colors.textMid,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                fontFamily = GeistFamily,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            Spacer(Modifier.weight(1.15f))

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                NavBeamButton(
                    label = "Load Display Profile",
                    onClick = onSetupProfile,
                    icon = NavBeamIcons.UploadFile,
                    variant = BtnVariant.Primary,
                    size = BtnSize.Lg,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    NavBeamButton(
                        label = "GitHub Repo",
                        onClick = { onOpenExternalUrl(HOME_COMMUNITY_GITHUB_URL) },
                        icon = NavBeamIcons.OpenInNew,
                        variant = BtnVariant.Ghost,
                        size = BtnSize.Sm,
                        modifier = Modifier.weight(1f),
                    )
                    NavBeamButton(
                        label = "JSON Template",
                        onClick = { onOpenExternalUrl(HOME_TEMPLATE_SCHEMA_URL) },
                        icon = NavBeamIcons.Code,
                        variant = BtnVariant.Ghost,
                        size = BtnSize.Sm,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

