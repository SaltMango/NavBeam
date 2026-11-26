package io.github.saltmango.navbeam.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.saltmango.navbeam.dash.map.PrefetchProgress
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.components.*
import io.github.saltmango.navbeam.ui.theme.*
import io.github.saltmango.navbeam.viewmodel.RouteViewModel
import io.github.saltmango.navbeam.viewmodel.DashViewModel
import io.github.saltmango.navbeam.data.SettingsPrefs
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.ui.platform.LocalDensity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(
    onBack: () -> Unit,
    onSentToDash: (String) -> Unit,
    routeViewModel: RouteViewModel = viewModel(),
    dashViewModel: DashViewModel = viewModel(),
) {
    val colors = NavBeamTheme.colors
    val routeState by routeViewModel.state.collectAsState()
    val dest       = routeState.destination
    val destName   = dest?.name ?: "Set Destination"
    val destSub    = dest?.let { "%.5f, %.5f".format(it.lat, it.lng) } ?: ""

    val savedList by routeViewModel.saved.collectAsState()
    val searchQuery by routeViewModel.searchQuery.collectAsState()
    val predictions by routeViewModel.predictions.collectAsState()
    val recents by routeViewModel.recents.collectAsState()
    val searchError by routeViewModel.searchError.collectAsState()

    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val voiceManager = remember { io.github.saltmango.navbeam.dash.nav.VoiceManager.get(ctx) }
    val voiceMode by voiceManager.mode.collectAsState()
    val voice = when (voiceMode) {
        io.github.saltmango.navbeam.dash.nav.VoiceMode.OFF   -> "Off"
        io.github.saltmango.navbeam.dash.nav.VoiceMode.TONE  -> "Tone"
        io.github.saltmango.navbeam.dash.nav.VoiceMode.FULL  -> "Voice"
    }
    var sent by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showMissingProfileDialog by remember { mutableStateOf(false) }
    var showLoadProfileModal by remember { mutableStateOf(false) }
    val activeProfile by io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.collectAsState()
    var editing by remember { mutableStateOf<io.github.saltmango.navbeam.data.SavedLocation?>(null) }

    val dashUi by dashViewModel.ui.collectAsState()
    val prefetchProgress by dashViewModel.prefetchProgress.collectAsState(initial = PrefetchProgress())
    val savedRoutes by routeViewModel.savedRoutes.collectAsState()
    var showQuickSettings by remember { mutableStateOf(false) }
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Expanded,
            skipHiddenState = true
        )
    )

    LaunchedEffect(dest) {
        scaffoldState.bottomSheetState.expand()
    }
    
    var keepAwake by remember { mutableStateOf(SettingsPrefs.getKeepAwake(ctx)) }
    var hasMapsKey by remember { mutableStateOf(io.github.saltmango.navbeam.data.MapsKeyManager.getKey(ctx).isNotBlank()) }

    var routeColorName by remember { mutableStateOf(SettingsPrefs.getRouteColor(ctx)) }
    var customRouteColorHex by remember { mutableStateOf(SettingsPrefs.getCustomRouteColorHex(ctx)) }
    var routeLineWidth by remember { mutableStateOf(SettingsPrefs.getRouteLineWidth(ctx)) }
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
            colors.routeFallback
        }
    }

    LaunchedEffect(Unit) {
        keepAwake = SettingsPrefs.getKeepAwake(ctx)
        hasMapsKey = io.github.saltmango.navbeam.data.MapsKeyManager.getKey(ctx).isNotBlank()
    }

    val navPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }
    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    var pendingStartNav by remember { mutableStateOf(false) }

    val navPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.any { it }
        if (granted && pendingStartNav) {
            pendingStartNav = false
            sent = true
        }
    }

    // When sent is set to true, ensure we initiate connection or projection if not already streaming
    LaunchedEffect(sent) {
        if (sent) {
            dashViewModel.setDestination(
                name = destName,
                lat  = routeState.destination?.lat,
                lng  = routeState.destination?.lng,
                stops = routeState.stops.mapNotNull { it.location }
            )
            if (dashUi.stage == io.github.saltmango.navbeam.viewmodel.ConnStage.CONNECTED) {
                dashViewModel.startProjection()
            } else if (dashUi.stage != io.github.saltmango.navbeam.viewmodel.ConnStage.STREAMING) {
                dashViewModel.connect(autoStartProj = true)
            }
        }
    }

    // Observe connection stage to navigate to DashScreen when streaming is active
    LaunchedEffect(sent, dashUi.stage) {
        if (dashUi.stage == io.github.saltmango.navbeam.viewmodel.ConnStage.STREAMING && sent) {
            onSentToDash(destName)
            sent = false
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission()) {
            pendingStartNav = false
            navPermLauncher.launch(navPermissions)
        }
    }

    androidx.activity.compose.BackHandler(enabled = sent) {
        dashViewModel.disconnect()
        sent = false
    }

    Box(Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            modifier = Modifier.fillMaxSize(),
            scaffoldState = scaffoldState,
            sheetPeekHeight = 100.dp,
            sheetContainerColor = colors.bg1,
            sheetTonalElevation = 0.dp,
            sheetShadowElevation = 0.dp,
            sheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            sheetDragHandle = { BottomSheetDefaults.DragHandle(color = colors.line3) },
            sheetContent = {
                // Fixed Bottom Sheet Header (when a destination is selected)
                if (dest != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max)
                            .padding(horizontal = 18.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(colors.primary)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                            Text(
                                text = destName,
                                color = colors.textHi,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GeistFamily,
                                letterSpacing = (-0.3).sp,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee()
                            )
                            if (destSub.isNotBlank()) {
                                Text(
                                    text = destSub,
                                    color = colors.textLo,
                                    fontSize = 12.5.sp,
                                    fontFamily = GeistMonoFamily,
                                    modifier = Modifier.padding(top = 1.dp),
                                    maxLines = 1
                                )
                            }
                        }
                        NavBeamIconButton(
                            icon = NavBeamIcons.Gear,
                            onClick = { showQuickSettings = true },
                            size = 48.dp,
                            tint = colors.textHi
                        )
                        Spacer(Modifier.width(8.dp))
                        NavBeamIconButton(
                            icon = NavBeamIcons.X,
                            onClick = { routeViewModel.clear() },
                            size = 48.dp,
                            tint = colors.alert
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(elevation = 1.5.dp, clip = false)
                    ) {
                        NavBeamDivider()
                    }
                }

                // Scrollable Content Area
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 8.dp)
                ) {
                    Spacer(Modifier.height(12.dp))

                    if (dest == null) {
                        // INITIAL STATE: Prompt & Saved Destinations
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "Where to?",
                                color = colors.textHi,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GeistFamily,
                                letterSpacing = (-0.4).sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Search for a destination above, choose a saved place below, or tap anywhere on the map to drop a pin.",
                                color = colors.textMid,
                                fontSize = 13.5.sp,
                                fontFamily = GeistFamily
                            )
                        }

                        if (recents.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            NavBeamEyebrow("Recent Searches", Modifier.padding(bottom = 8.dp, start = 4.dp))
                            NavBeamCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                                recents.forEachIndexed { i, loc ->
                                    if (i > 0) NavBeamDivider(Modifier.padding(horizontal = 4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { routeViewModel.selectRecent(loc) }
                                            .padding(horizontal = 10.dp, vertical = 12.dp),
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(NavBeamTheme.shapes.iconBadge)
                                                .background(colors.surf2),
                                        ) {
                                            Icon(
                                                imageVector = NavBeamIcons.History,
                                                contentDescription = null,
                                                tint = colors.textMid,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = loc.name,
                                                color = colors.textHi,
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = GeistFamily,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = "%.5f, %.5f".format(loc.lat, loc.lng),
                                                color = colors.textLo,
                                                fontSize = 12.sp,
                                                fontFamily = GeistMonoFamily,
                                                modifier = Modifier.padding(top = 2.dp),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (savedRoutes.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            NavBeamEyebrow("Saved Offline Routes", Modifier.padding(bottom = 8.dp, start = 4.dp))
                            NavBeamCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                                savedRoutes.forEachIndexed { i, item ->
                                    if (i > 0) NavBeamDivider(Modifier.padding(horizontal = 4.dp))
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(NavBeamTheme.shapes.iconBadge)
                                                    .background(colors.successTint),
                                            ) {
                                                Icon(
                                                    imageVector = NavBeamIcons.Route,
                                                    contentDescription = null,
                                                    tint = colors.success,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(14.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    color = colors.textHi,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = GeistFamily,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = "%.0f km • 100%% Offline Ready".format(item.distanceMeters / 1000.0),
                                                    color = colors.textLo,
                                                    fontSize = 12.sp,
                                                    fontFamily = GeistMonoFamily,
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }
                                            Icon(
                                                imageVector = NavBeamIcons.Trash,
                                                contentDescription = "delete route",
                                                tint = colors.alert,
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clickable { routeViewModel.deleteSavedRoute(item) }
                                            )
                                        }

                                        Spacer(Modifier.height(10.dp))

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            NavBeamButton(
                                                label = "${item.fromName} → ${item.toName}",
                                                onClick = { routeViewModel.loadSavedRoute(item, isReturn = false, dashViewModel = dashViewModel) },
                                                variant = BtnVariant.Primary,
                                                size = BtnSize.Sm,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (item.returnRouteJson.isNotBlank()) {
                                                NavBeamButton(
                                                    label = "Return (${item.toName} → ${item.fromName})",
                                                    onClick = { routeViewModel.loadSavedRoute(item, isReturn = true, dashViewModel = dashViewModel) },
                                                    variant = BtnVariant.Secondary,
                                                    size = BtnSize.Sm,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (savedList.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            NavBeamEyebrow("Saved Destinations", Modifier.padding(bottom = 8.dp, start = 4.dp))
                            NavBeamCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                                savedList.forEachIndexed { i, loc ->
                                    if (i > 0) NavBeamDivider(Modifier.padding(horizontal = 4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { routeViewModel.selectSaved(loc) }
                                            .padding(horizontal = 10.dp, vertical = 12.dp),
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(NavBeamTheme.shapes.iconBadge)
                                                .background(colors.primaryTint),
                                        ) {
                                            Icon(
                                                imageVector = NavBeamIcons.LocationPin,
                                                contentDescription = null,
                                                tint = colors.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = loc.name,
                                                color = colors.textHi,
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = GeistFamily,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = if (loc.note.isNotBlank()) loc.note else "%.4f, %.4f".format(loc.lat, loc.lng),
                                                color = colors.textLo,
                                                fontSize = 12.sp,
                                                fontFamily = GeistMonoFamily,
                                                modifier = Modifier.padding(top = 2.dp),
                                                maxLines = 1,
                                            )
                                        }
                                        Icon(
                                            imageVector = NavBeamIcons.Edit,
                                            contentDescription = "edit",
                                            tint = colors.textLo,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clickable { editing = loc },
                                        )
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.height(18.dp))
                            NavBeamEmptyState(
                                icon = NavBeamIcons.Pin,
                                title = "No saved destinations yet",
                                subtitle = "Bookmarked locations will appear here.",
                                padding = 16.dp,
                            )
                        }
                    } else {
                        // ACTIVE STATE: Destination Selected & Routing details
                        val routing = routeState.routing
                        val durationColor = when {
                            routing                                  -> colors.textHi
                            routeState.trafficDelaySeconds > 600     -> colors.alert
                            routeState.trafficDelaySeconds > 180     -> colors.warn
                            else                                     -> colors.success
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max)
                        ) {
                            NavBeamMetricCard(
                                value = if (routing) "\u2026" else (routeState.distanceText ?: "\u2014"),
                                label = "Distance",
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            NavBeamMetricCard(
                                value = if (routing) "\u2026" else (routeState.durationText ?: "\u2014"),
                                label = "Duration",
                                valueColor = durationColor,
                                emphasize = true,
                                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                            )
                            NavBeamMetricCard(
                                value = if (routing) "\u2026" else (routeState.etaText ?: "\u2014"),
                                label = "Arrive",
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }

                        if (prefetchProgress.total > 0) {
                            Spacer(Modifier.height(14.dp))
                            val progressColor = if (prefetchProgress.isDownloading) colors.primary else colors.success
                            NavBeamCard(
                                modifier = Modifier.fillMaxWidth(),
                                padding = 12.dp
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (prefetchProgress.isDownloading) NavBeamIcons.Download else NavBeamIcons.Check,
                                                contentDescription = null,
                                                tint = progressColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = if (prefetchProgress.isDownloading) "Downloading offline map" else "Offline map ready for ride",
                                                color = colors.textHi,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = GeistFamily
                                            )
                                        }
                                        Text(
                                            text = "${prefetchProgress.percentage}%",
                                            color = progressColor,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = GeistMonoFamily
                                        )
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    NavBeamProgressBar(
                                        progress = prefetchProgress.percentage / 100f,
                                        color = progressColor,
                                        height = 5.dp,
                                    )

                                    Spacer(Modifier.height(4.dp))

                                    Text(
                                        text = "${prefetchProgress.completed} of ${prefetchProgress.total} map tiles cached",
                                        color = colors.textLo,
                                        fontSize = 11.5.sp,
                                        fontFamily = GeistMonoFamily
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        NavBeamButton(
                            label = "Save Route Offline (Both Directions)",
                            onClick = {
                                routeViewModel.saveActiveRouteOffline(dashViewModel = dashViewModel)
                                android.widget.Toast.makeText(ctx, "Route & Return directions saved for offline ride!", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            icon = NavBeamIcons.Save,
                            variant = BtnVariant.Secondary,
                            size = BtnSize.Lg,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(22.dp))

                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            NavBeamEyebrow("Voice guidance")
                            Icon(
                                imageVector = if (voice == "Off") NavBeamIcons.SpeakerOff else NavBeamIcons.Speaker,
                                contentDescription = null,
                                tint = if (voice == "Off") colors.textLo else colors.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        NavBeamSegmentedControl(
                            options = listOf("Off", "Tone only", "Voice"),
                            selected = voice,
                            onSelect = {
                                voiceManager.setMode(when (it) {
                                    "Off"   -> io.github.saltmango.navbeam.dash.nav.VoiceMode.OFF
                                    "Voice" -> io.github.saltmango.navbeam.dash.nav.VoiceMode.FULL
                                    else    -> io.github.saltmango.navbeam.dash.nav.VoiceMode.TONE
                                })
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        )
                    }
                }

                // Fixed/Sticky Bottom Action Bar
                if (dest != null) {
                    val savedItem = savedList.find { it.lat == dest.lat && it.lng == dest.lng }
                    val isSaved = savedItem != null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NavBeamButton(
                            label = when {
                                sent               -> "Starting..."
                                routeState.routing -> "Routing..."
                                else               -> "Start Navigation"
                            },
                            onClick = {
                                if (!activeProfile.isConfigured) {
                                    showMissingProfileDialog = true
                                } else if (hasLocationPermission()) {
                                    val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                                    val gpsEnabled = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                                    val networkEnabled = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
                                    if (!gpsEnabled && !networkEnabled) {
                                        android.widget.Toast.makeText(
                                            ctx,
                                            "Please enable Location Services/GPS in settings.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        if (dashUi.stage == io.github.saltmango.navbeam.viewmodel.ConnStage.STREAMING) {
                                            onSentToDash(destName)
                                        } else {
                                            sent = true
                                        }
                                    }
                                } else {
                                    pendingStartNav = true
                                    navPermLauncher.launch(navPermissions)
                                }
                            },
                            icon = if (sent) NavBeamIcons.Check else NavBeamIcons.Navi,
                            variant = if (sent) BtnVariant.Secondary else BtnVariant.Primary,
                            size = BtnSize.Lg,
                            enabled = !sent,
                            modifier = Modifier.weight(1f),
                        )
                        NavBeamIconButton(
                            icon = if (isSaved) NavBeamIcons.Trash else NavBeamIcons.Save,
                            onClick = {
                                if (isSaved) {
                                    savedItem?.let { routeViewModel.deleteSaved(it) }
                                } else {
                                    showSave = true
                                }
                            },
                            size = 58.dp,
                            tint = if (isSaved) colors.alert else null,
                        )
                    }
                } else {
                    Spacer(Modifier.height(16.dp))
                }
            },
        ) { _ ->
            // Map preview (full-screen background)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.surf1),
            ) {
                val isRedirecting = (dashUi.stage == io.github.saltmango.navbeam.viewmodel.ConnStage.STREAMING && routeState.destination != null)
                if (hasMapsKey && !sent && !isRedirecting) {
                    NavBeamMap(
                        riderLat = null,
                        riderLng = null,
                        dest = dest?.let { it.lat to it.lng },
                        stops = routeState.stops.mapNotNull { it.location },
                        routePoints = routeState.route?.geometry.orEmpty(),
                        hasLocationPermission = hasLocationPermission(),
                        fitRoute = true,
                        routeColor = currentRouteColor,
                        routeWidth = routeLineWidth,
                        onMapClick = { latLng ->
                            routeViewModel.selectLocationFromMap(latLng.latitude, latLng.longitude)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.surf1),
                        contentAlignment = Alignment.Center
                    ) {
                        if (sent || isRedirecting) {
                            androidx.compose.material3.CircularProgressIndicator(color = colors.primary)
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = NavBeamIcons.LocationPin,
                                    contentDescription = null,
                                    tint = colors.alert,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "Google Maps API Key Required",
                                    color = colors.textHi,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = GeistFamily
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "To search routes and view the map, please add your API Key in the Menu settings.",
                                    color = colors.textLo,
                                    fontSize = 12.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    fontFamily = GeistFamily
                                )
                            }
                        }
                    }
                }

                // Top bar overlay
                val stops = routeState.stops
                val activeStopIndex by routeViewModel.activeStopIndex.collectAsState()

                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    if (stops.isEmpty()) {
                        // Inline Search Bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(CircleShape)
                                .background(colors.bg1)
                                .border(1.dp, colors.line3, CircleShape)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Search, null, tint = colors.textLo, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))

                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search for a place...",
                                            color = colors.textLo,
                                            fontSize = 14.sp,
                                            fontFamily = GeistFamily
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { routeViewModel.setSearchQuery(it) },
                                        textStyle = TextStyle(
                                            color = colors.textHi,
                                            fontSize = 14.sp,
                                            fontFamily = GeistFamily
                                        ),
                                        cursorBrush = SolidColor(colors.primary),
                                        singleLine = true,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            imeAction = ImeAction.Search
                                        ),
                                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                            onSearch = {
                                                focusManager.clearFocus()
                                                keyboardController?.hide()
                                            }
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (searchQuery.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        imageVector = NavBeamIcons.X,
                                        contentDescription = "Clear search",
                                        tint = colors.textLo,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable {
                                                routeViewModel.clearSearch()
                                                focusManager.clearFocus()
                                                keyboardController?.hide()
                                            }
                                    )
                                }
                            }
                        }
                    } else {
                        // Multi-stop layout card
                        var draggedIndex by remember { mutableStateOf<Int?>(null) }
                        var dragOffset by remember { mutableStateOf(0f) }

                        NavBeamCard(
                            modifier = Modifier.fillMaxWidth(),
                            padding = 8.dp
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                stops.forEachIndexed { index, stop ->
                                    val isDragging = draggedIndex == index
                                    val itemOffset = if (isDragging) dragOffset else 0f

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(40.dp)
                                            .offset(y = with(LocalDensity.current) { itemOffset.toDp() })
                                            .background(if (isDragging) colors.surf2 else Color.Transparent)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(if (index == 0) colors.primary else colors.alert),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = ('A' + index).toString(),
                                                color = colors.onPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = GeistMonoFamily
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(1.dp, if (activeStopIndex == index) colors.primary else colors.line3, RoundedCornerShape(8.dp))
                                                .background(colors.surf1)
                                                .clickable {
                                                    routeViewModel.setActiveStopIndex(index)
                                                    routeViewModel.setSearchQuery(stop.location?.name ?: "")
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (activeStopIndex == index) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        if (searchQuery.isEmpty()) {
                                                            Text(
                                                                text = "Type location...",
                                                                color = colors.textLo,
                                                                fontSize = 13.5.sp,
                                                                fontFamily = GeistFamily
                                                            )
                                                        }
                                                        BasicTextField(
                                                            value = searchQuery,
                                                            onValueChange = { routeViewModel.setSearchQuery(it) },
                                                            textStyle = TextStyle(
                                                                color = colors.textHi,
                                                                fontSize = 13.5.sp,
                                                                fontFamily = GeistFamily
                                                            ),
                                                            cursorBrush = SolidColor(colors.primary),
                                                            singleLine = true,
                                                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                                                imeAction = ImeAction.Search
                                                            ),
                                                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                                                onSearch = {
                                                                    focusManager.clearFocus()
                                                                    keyboardController?.hide()
                                                                    routeViewModel.setActiveStopIndex(null)
                                                                }
                                                            ),
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                    }
                                                    if (searchQuery.isNotEmpty()) {
                                                        Icon(
                                                            imageVector = NavBeamIcons.X,
                                                            contentDescription = "Clear search",
                                                            tint = colors.textLo,
                                                            modifier = Modifier
                                                                .size(16.dp)
                                                                .clickable {
                                                                    routeViewModel.clearSearch()
                                                                }
                                                        )
                                                    }
                                                }
                                            } else {
                                                Text(
                                                    text = stop.location?.name ?: "Choose stop...",
                                                    color = if (stop.location != null) colors.textHi else colors.textLo,
                                                    fontSize = 13.5.sp,
                                                    fontFamily = GeistFamily,
                                                    maxLines = 1,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }

                                        Spacer(Modifier.width(8.dp))

                                        Icon(
                                            imageVector = NavBeamIcons.X,
                                            contentDescription = "Delete stop",
                                            tint = colors.textLo,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clickable {
                                                    routeViewModel.removeStop(index)
                                                }
                                        )
                                        Spacer(Modifier.width(8.dp))

                                        if (stops.size > 1) {
                                            val currentIndex = rememberUpdatedState(index)
                                            Icon(
                                                imageVector = Icons.Outlined.Menu,
                                                contentDescription = "Drag to reorder",
                                                tint = colors.textLo,
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .pointerInput(stop.id) {
                                                        detectDragGestures(
                                                            onDragStart = {
                                                                draggedIndex = currentIndex.value
                                                                dragOffset = 0f
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                dragOffset += dragAmount.y

                                                                val idx = currentIndex.value
                                                                val threshold = 58.dp.toPx()
                                                                val targetIndex = when {
                                                                    dragOffset > threshold -> idx + 1
                                                                    dragOffset < -threshold -> idx - 1
                                                                    else -> null
                                                                }
                                                                if (targetIndex != null && targetIndex in stops.indices) {
                                                                    routeViewModel.moveStop(idx, targetIndex)
                                                                    draggedIndex = targetIndex
                                                                    dragOffset = 0f
                                                                }
                                                            },
                                                            onDragEnd = {
                                                                draggedIndex = null
                                                                dragOffset = 0f
                                                                routeViewModel.finalizeStopOrder()
                                                            },
                                                            onDragCancel = {
                                                                draggedIndex = null
                                                                dragOffset = 0f
                                                                routeViewModel.finalizeStopOrder()
                                                            }
                                                        )
                                                    }
                                            )
                                        }
                                    }
                                }

                                if (stops.size < 5) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { routeViewModel.addStop() }
                                            .padding(vertical = 4.dp, horizontal = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = NavBeamIcons.Plus,
                                            contentDescription = "Add stop",
                                            tint = colors.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Add stop",
                                            color = colors.primary,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = GeistFamily
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Floating suggestions dropdown
                    if (predictions.isNotEmpty() || searchError != null) {
                        Spacer(Modifier.height(8.dp))
                        NavBeamCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp),
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
                                        if (index > 0) {
                                            NavBeamDivider(Modifier.padding(horizontal = 8.dp))
                                        }
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    routeViewModel.selectPrediction(prediction)
                                                    focusManager.clearFocus()
                                                    keyboardController?.hide()
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
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    if (showSave) SaveLocationDialog(
        defaultName = destName,
        onSave = { name, note -> routeViewModel.saveCurrentDestination(name, note); showSave = false },
        onDismiss = { showSave = false },
    )
    editing?.let { loc ->
        EditLocationDialog(
            loc = loc,
            onSave = { name, note -> routeViewModel.renameSaved(loc, name, note); editing = null },
            onDelete = { routeViewModel.deleteSaved(loc); editing = null },
            onDismiss = { editing = null },
        )
    }
    if (showQuickSettings) {
        ModalBottomSheet(
            onDismissRequest = { showQuickSettings = false },
            containerColor = colors.surf1,
            dragHandle = { BottomSheetDefaults.DragHandle(color = colors.line) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Quick Ride Settings",
                    color = colors.textHi,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Navigation Mode Setting
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    ) {
                        NavBeamEyebrow("Navigation mode")
                        Icon(
                            imageVector = if (dashUi.navMode == "Map") NavBeamIcons.Route else NavBeamIcons.Navi,
                            contentDescription = null,
                            tint = if (dashUi.navMode == "Map") colors.primary else colors.textLo,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    NavBeamSegmentedControl(
                        options = listOf("Map", "Arrow"),
                        selected = dashUi.navMode,
                        onSelect = { mode ->
                            dashViewModel.setNavMode(mode)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }



                // Conditionally show Map-only settings
                if (dashUi.navMode == "Map") {
                    // 3. Map Rotation (Heading-up Map)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NavBeamEyebrow("Map Rotation")
                        NavBeamSegmentedControl(
                            options = listOf("North-Up", "Heading-Up"),
                            selected = if (dashUi.headingUp) "Heading-Up" else "North-Up",
                            onSelect = { dashViewModel.toggleHeadingUp() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 4. Stream Quality
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NavBeamEyebrow("Stream Quality")
                        NavBeamSegmentedControl(
                            options = listOf("Max", "Balanced", "Battery Saver", "Custom"),
                            selected = dashUi.streamQuality,
                            onSelect = { dashViewModel.setStreamQuality(it) },
                            modifier = Modifier.fillMaxWidth()
                        )
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
                        NavBeamEyebrow("Route Line Color")

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
                                                SettingsPrefs.setRouteColor(ctx, name)
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
                            NavBeamEyebrow("Route Line Thickness")
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
                                SettingsPrefs.setRouteLineWidth(ctx, width)
                            },
                            valueRange = 4f..20f,
                            steps = 15,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = colors.primary,
                                activeTrackColor = colors.primary,
                                inactiveTrackColor = colors.line3,
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 5. Keep Dash Awake Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val newVal = !keepAwake
                            keepAwake = newVal
                            SettingsPrefs.setKeepAwake(ctx, newVal)
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keep dash awake",
                            color = colors.textHi,
                            fontSize = 14.sp,
                            fontFamily = GeistFamily
                        )
                        Text(
                            text = "Prevent dash display sleep while connected",
                            color = colors.textLo,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    NavBeamToggle(on = keepAwake, onChange = {
                        keepAwake = it
                        SettingsPrefs.setKeepAwake(ctx, it)
                    })
                }
            }
        }
    } // close Column

    // Connection Overlay Dialog
    if (sent && dashUi.stage != io.github.saltmango.navbeam.viewmodel.ConnStage.STREAMING) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            NavBeamCard(
                glow = dashUi.stage != io.github.saltmango.navbeam.viewmodel.ConnStage.ERROR,
                padding = 20.dp,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .wrapContentHeight()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when (dashUi.stage) {
                            io.github.saltmango.navbeam.viewmodel.ConnStage.WIFI -> "Connecting to Dash..."
                            io.github.saltmango.navbeam.viewmodel.ConnStage.AUTH -> "Establishing Secure Link..."
                            io.github.saltmango.navbeam.viewmodel.ConnStage.ERROR -> "Connection Failed"
                            io.github.saltmango.navbeam.viewmodel.ConnStage.CONNECTED -> "Dash Connected"
                            else -> "Connecting..."
                        },
                        color = colors.textHi,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily
                    )

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = when (dashUi.stage) {
                            io.github.saltmango.navbeam.viewmodel.ConnStage.WIFI -> if (dashUi.ssid.isNotBlank()) "Joining Wi-Fi SSID ${dashUi.ssid}..." else "Searching for Display Wi-Fi..."
                            io.github.saltmango.navbeam.viewmodel.ConnStage.AUTH -> "Securing projection session..."
                            io.github.saltmango.navbeam.viewmodel.ConnStage.CONNECTED -> "Ready to stream navigation. Starting projection..."
                            io.github.saltmango.navbeam.viewmodel.ConnStage.ERROR -> dashUi.errorMessage ?: "Check display power and proximity."
                            else -> "Please wait..."
                        },
                        color = if (dashUi.stage == io.github.saltmango.navbeam.viewmodel.ConnStage.ERROR) colors.alert else colors.textMid,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontFamily = GeistFamily
                    )

                    Spacer(Modifier.height(20.dp))

                    if (dashUi.stage == io.github.saltmango.navbeam.viewmodel.ConnStage.ERROR) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(colors.alertTint)
                        ) {
                            Icon(
                                imageVector = NavBeamIcons.X,
                                contentDescription = "Error",
                                tint = colors.alert,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        NavBeamProgressBar(color = colors.primary, height = 4.dp)
                    }

                        Spacer(Modifier.height(24.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (dashUi.stage == io.github.saltmango.navbeam.viewmodel.ConnStage.ERROR || dashUi.needsWifiOn) {
                                NavBeamButton(
                                    label = "Cancel",
                                    onClick = {
                                        dashViewModel.disconnect()
                                        sent = false
                                    },
                                    variant = BtnVariant.Ghost,
                                    size = BtnSize.Sm,
                                    modifier = Modifier.weight(1f)
                                )
                                NavBeamButton(
                                    label = if (!activeProfile.isConfigured) "Load Profile" else "Connect",
                                    onClick = {
                                        if (!activeProfile.isConfigured) {
                                            dashViewModel.disconnect()
                                            sent = false
                                            showLoadProfileModal = true
                                        } else {
                                            val wm = ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                                            if (!wm.isWifiEnabled) {
                                                dashViewModel.connect(autoStartProj = true)
                                                runCatching {
                                                    ctx.startActivity(android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI))
                                                }
                                            } else {
                                                dashViewModel.connect(autoStartProj = true)
                                            }
                                        }
                                    },
                                    variant = BtnVariant.Primary,
                                    size = BtnSize.Sm,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                NavBeamButton(
                                    label = "Cancel",
                                    onClick = {
                                        dashViewModel.disconnect()
                                        sent = false
                                    },
                                    variant = BtnVariant.Ghost,
                                    size = BtnSize.Sm,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
        if (showColorPickerDialog) {
            ColorPickerDialog(
                initialColorHex = customRouteColorHex,
                onSave = { newColor ->
                    customRouteColorHex = newColor
                    SettingsPrefs.setCustomRouteColorHex(ctx, newColor)
                    routeColorName = "Custom"
                    SettingsPrefs.setRouteColor(ctx, "Custom")
                    showColorPickerDialog = false
                },
                onDismiss = {
                    showColorPickerDialog = false
                }
            )
        }
        if (showMissingProfileDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showMissingProfileDialog = false },
                containerColor = colors.surf1,
                title = {
                    Text(
                        text = "Display Profile Required",
                        color = colors.textHi,
                        fontFamily = GeistFamily,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                text = {
                    Text(
                        text = "Upload or paste your motorcycle display's .json profile before starting dash navigation.",
                        color = colors.textMid,
                        fontSize = 13.5.sp,
                        fontFamily = GeistFamily,
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showMissingProfileDialog = false
                            showLoadProfileModal = true
                        }
                    ) {
                        Text("Load Profile", color = colors.primary, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showMissingProfileDialog = false }
                    ) {
                        Text("Cancel", color = colors.textMid)
                    }
                },
            )
        }
        LoadProfileModal(
            visible = showLoadProfileModal,
            onDismiss = { showLoadProfileModal = false },
            dashViewModel = dashViewModel,
        )
    } // close Box
}

@Composable
private fun SaveLocationDialog(defaultName: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    val colors = NavBeamTheme.colors
    var name by remember { mutableStateOf(defaultName) }
    var note by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) { Text("Save", color = if (name.isNotBlank()) colors.primary else colors.textLo) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textMid) } },
        title = { Text("Save destination", color = colors.textHi) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        containerColor = colors.surf1,
    )
}

@Composable
private fun EditLocationDialog(loc: io.github.saltmango.navbeam.data.SavedLocation, onSave: (String, String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val colors = NavBeamTheme.colors
    var name by remember { mutableStateOf(loc.name) }
    var note by remember { mutableStateOf(loc.note) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) { Text("Save", color = if (name.isNotBlank()) colors.primary else colors.textLo) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDelete) { Text("Delete", color = colors.alert) } },
        title = { Text("Edit destination", color = colors.textHi) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        containerColor = colors.surf1,
    )
}
