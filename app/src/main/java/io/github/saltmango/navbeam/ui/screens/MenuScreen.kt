package io.github.saltmango.navbeam.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.components.*
import io.github.saltmango.navbeam.ui.theme.*
import io.github.saltmango.navbeam.data.DiagReporter
import io.github.saltmango.navbeam.data.RideDiagnostics
import io.github.saltmango.navbeam.data.SettingsPrefs
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.viewmodel.AppViewModel
import io.github.saltmango.navbeam.viewmodel.ConnectionState
import io.github.saltmango.navbeam.viewmodel.DashViewModel
import io.github.saltmango.navbeam.viewmodel.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MenuScreen(
    appViewModel: AppViewModel,
    dashViewModel: DashViewModel,
    conn: ConnectionState,
    onNavigate: (String) -> Unit
) {
    val colors = NavBeamTheme.colors
    val dashUi by dashViewModel.ui.collectAsState()
    val themeMode by appViewModel.themeMode.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var autoConnect by remember { mutableStateOf(SettingsPrefs.getAutoConnect(ctx)) }
    var screenOff   by remember { mutableStateOf(SettingsPrefs.getScreenOff(ctx)) }
    var keepAwake   by remember { mutableStateOf(SettingsPrefs.getKeepAwake(ctx)) }
    var units       by remember { mutableStateOf(SettingsPrefs.getUnits(ctx)) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showNavModeDialog by remember { mutableStateOf(false) }
    var showCustomConfigDialog by remember { mutableStateOf(false) }
    var bikeName by remember { mutableStateOf(SettingsPrefs.getBikeName(ctx).ifBlank { "My Bike" }) }
    var showBikeNameDialog by remember { mutableStateOf(false) }

    val activeProfile by DashProfileManager.activeProfile.collectAsState()
    
    var lastClickTime by remember { mutableStateOf(0L) }
    var clickCount by remember { mutableStateOf(0) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyValue by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(SettingsPrefs.getMapsApiKey(ctx))) }

    // Keep settings synchronized when Menu tab becomes active
    LaunchedEffect(Unit) {
        autoConnect = SettingsPrefs.getAutoConnect(ctx)
        screenOff = SettingsPrefs.getScreenOff(ctx)
        keepAwake = SettingsPrefs.getKeepAwake(ctx)
        units = SettingsPrefs.getUnits(ctx)
    }

    // Real voice setting, shared with RouteScreen via the VoiceManager singleton.
    val voiceManager = remember { io.github.saltmango.navbeam.dash.nav.VoiceManager.get(ctx) }
    val voiceMode by voiceManager.mode.collectAsState()
    val voice = when (voiceMode) {
        io.github.saltmango.navbeam.dash.nav.VoiceMode.OFF   -> "Off"
        io.github.saltmango.navbeam.dash.nav.VoiceMode.TONE  -> "Tone"
        io.github.saltmango.navbeam.dash.nav.VoiceMode.FULL  -> "Voice"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        // User account card at the very top
        navBeamCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            padding = 16.dp,
            onClick = { showBikeNameDialog = true }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PrimaryTint),
                ) {
                    Text(
                        text = bikeName.firstOrNull()?.uppercase() ?: "H",
                        color = Primary,
                        fontFamily = GeistMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = bikeName,
                        color = colors.textHi,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily
                    )
                    Text(
                        text = "Rider Profile",
                        color = colors.textMid,
                        fontSize = 12.5.sp,
                        fontFamily = GeistFamily,
                    )
                }
            }
        }



        // Main Menu navigation items
        navBeamCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            navBeamRow(
                title = "Rides",
                sub = "Past recorded trips and stats",
                icon = NavBeamIcons.History,
                onClick = { onNavigate("rides") }
            )
        }

        // Settings items migrated to Menu screen
        SettingsSection("Display Connection", padding = 6.dp) {
            SettingRow(
                icon = NavBeamIcons.Sync,
                title = "Auto-connect on start",
                sub = "Link when the bike is near",
                control = { navBeamToggle(autoConnect) { autoConnect = it; SettingsPrefs.setAutoConnect(ctx, it) } }
            )
            navBeamDivider(Modifier.padding(horizontal = 6.dp))
            val hasForgetOption = dashUi.ssid.isNotBlank()
            val showQuality = dashUi.navMode == "Map"
            SettingRow(
                icon = NavBeamIcons.Navi,
                title = "Navigation mode",
                sub = when (dashUi.navMode) {
                    "Arrow" -> "Arrow · directions only"
                    else -> "Map · full video cast"
                },
                control = { Icon(NavBeamIcons.ChevronRight, null, tint = colors.textLo, modifier = Modifier.size(18.dp)) },
                last = !showQuality && !hasForgetOption,
                onClick = { showNavModeDialog = true }
            )
            if (showQuality) {
                navBeamDivider(Modifier.padding(horizontal = 6.dp))
                SettingRow(
                    icon = NavBeamIcons.Zap,
                    title = "Stream quality",
                    sub = when (dashUi.streamQuality) {
                        "Max" -> "Max · maximum performance"
                        "Battery Saver" -> "Battery Saver · saves power"
                        "Custom" -> "Custom · tap to configure"
                        else -> "Balanced · default"
                    },
                    control = { Icon(NavBeamIcons.ChevronRight, null, tint = colors.textLo, modifier = Modifier.size(18.dp)) },
                    onClick = { showQualityDialog = true }
                )
            }
            navBeamDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                icon = NavBeamIcons.Wrench,
                title = "Hardware profile",
                sub = if (activeProfile.isConfigured) {
                    "${activeProfile.name} · ${activeProfile.display.width}×${activeProfile.display.height}"
                } else {
                    "No profile uploaded · Tap to set up"
                },
                control = { Icon(NavBeamIcons.ChevronRight, null, tint = colors.textLo, modifier = Modifier.size(18.dp)) },
                last = !hasForgetOption,
                onClick = { onNavigate("profile_setup") }
            )
            if (hasForgetOption) {
                navBeamDivider(Modifier.padding(horizontal = 6.dp))
                SettingRow(
                    icon = NavBeamIcons.Lock,
                    title = "Forget Paired Dash",
                    sub = "Allows connecting to a different dash",
                    control = {
                         Text("Forget", color = Alert, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    },
                    last = true,
                    onClick = { dashViewModel.forgetDash() }
                )
            }
        }

        SettingsSection("Map Preferences", padding = 6.dp) {
            SettingRow(
                icon = NavBeamIcons.Navi,
                title = "Heading-up Map",
                sub = "Rotates map to travel direction",
                control = {
                    navBeamToggle(on = dashUi.headingUp, onChange = { dashViewModel.toggleHeadingUp() })
                }
            )
            navBeamDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                icon = NavBeamIcons.Cross,
                title = "Map Adjust Mode",
                sub = "Allows joystick to pan the preview map",
                control = {
                    navBeamToggle(on = dashUi.adjustMode, onChange = { dashViewModel.setAdjustMode(it) })
                }
            )
            navBeamDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                icon = NavBeamIcons.Dash,
                title = "Open Map Preview",
                sub = "Live preview & dashboard cockpit",
                control = {
                    Icon(NavBeamIcons.ChevronRight, null, tint = colors.textLo, modifier = Modifier.size(18.dp))
                },
                last = true,
                onClick = { onNavigate("dash") }
            )
        }

        SettingsSection("Ride Optimization", padding = 6.dp) {
            SettingRow(NavBeamIcons.Power, "Turn phone screen off", "Map keeps streaming to the dash",
                control = { navBeamToggle(screenOff) { screenOff = it; SettingsPrefs.setScreenOff(ctx, it) } })
            navBeamDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(NavBeamIcons.Dash, "Keep dash awake", "Prevent display sleep",
                control = { navBeamToggle(keepAwake) { keepAwake = it; SettingsPrefs.setKeepAwake(ctx, it) } }, last = true)
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        fun answerGranted() = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ANSWER_PHONE_CALLS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        var mediaGranted by remember { mutableStateOf(io.github.saltmango.navbeam.media.MediaInfoProvider.isAccessGranted(ctx)) }
        var callAnswerGranted by remember { mutableStateOf(answerGranted()) }
        DisposableEffect(lifecycleOwner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) {
                    mediaGranted = io.github.saltmango.navbeam.media.MediaInfoProvider.isAccessGranted(ctx)
                    callAnswerGranted = answerGranted()
                }
            }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }
        val callPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            callAnswerGranted = it
        }
        SettingsSection("Alerts & Media", padding = 6.dp) {
            SettingRow(
                NavBeamIcons.Zap,
                "Now playing & calls on dash",
                if (mediaGranted) "Enabled · song info + caller shown while riding"
                else "Tap to allow notification access",
                control = {
                    navBeamChip(
                        if (mediaGranted) "On" else "Enable",
                        if (mediaGranted) ChipTone.Primary else ChipTone.Off, dot = true,
                    )
                },
                onClick = if (mediaGranted) null else {
                    { runCatching { ctx.startActivity(io.github.saltmango.navbeam.media.MediaInfoProvider.accessSettingsIntent()) } }
                },
            )
            navBeamDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                NavBeamIcons.Bt,
                "Answer calls from joystick",
                if (callAnswerGranted) "Enabled · UP answers, DOWN rejects"
                else "Tap to allow answering/rejecting calls",
                control = {
                    navBeamChip(
                        if (callAnswerGranted) "On" else "Enable",
                        if (callAnswerGranted) ChipTone.Primary else ChipTone.Off, dot = true,
                    )
                },
                last = true,
                onClick = if (callAnswerGranted) null else {
                    { callPermLauncher.launch(android.Manifest.permission.ANSWER_PHONE_CALLS) }
                },
            )
        }


        SettingsSection("Audio Guidance", padding = 14.dp) {
            navBeamSegmented(listOf("Off", "Tone", "Voice"), voice, {
                voiceManager.setMode(when (it) {
                    "Off"   -> io.github.saltmango.navbeam.dash.nav.VoiceMode.OFF
                    "Voice" -> io.github.saltmango.navbeam.dash.nav.VoiceMode.FULL
                    else    -> io.github.saltmango.navbeam.dash.nav.VoiceMode.TONE
                })
            }, Modifier.fillMaxWidth())
        }

        SettingsSection("Theme", padding = 14.dp) {
            navBeamSegmented(
                options = listOf("Dark mode", "Light mode", "Auto"),
                selected = when (themeMode) {
                    ThemeMode.On -> "Dark mode"
                    ThemeMode.Off -> "Light mode"
                    ThemeMode.Auto -> "Auto"
                },
                onSelect = {
                    val mode = when (it) {
                        "Dark mode" -> ThemeMode.On
                        "Light mode" -> ThemeMode.Off
                        else -> ThemeMode.Auto
                    }
                    appViewModel.setThemeMode(mode)
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = when (themeMode) {
                    ThemeMode.Off -> "Light mode — white based"
                    ThemeMode.On  -> "Dark mode — OLED black"
                    ThemeMode.Auto -> "System — follows device theme"
                },
                color = colors.textLo, fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp)
            )
        }

        SettingsSection("Distance Units", padding = 14.dp) {
            navBeamSegmented(listOf("Kilometres", "Miles"), units, { units = it; SettingsPrefs.setUnits(ctx, it) }, Modifier.fillMaxWidth())
        }

        var diagSummary by remember { mutableStateOf(RideDiagnostics.DiagSummary(0, 0, 0, 0L)) }
        var exportingDiag by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            diagSummary = withContext(Dispatchers.IO) { RideDiagnostics.summary(ctx) }
        }
        SettingsSection("Diagnostics & Bug Reports", padding = 6.dp) {
            SettingRow(
                icon = NavBeamIcons.Flag,
                title = "Report Issue on GitHub",
                sub = "Pre-fills crash trace & ride performance (zero personal data)",
                control = { Icon(NavBeamIcons.ChevronRight, null, tint = colors.textLo, modifier = Modifier.size(18.dp)) },
                onClick = if (exportingDiag) null else {
                    {
                        exportingDiag = true
                        scope.launch {
                            val (uri, savedPath) = withContext(Dispatchers.IO) {
                                val zip = RideDiagnostics.exportZip(ctx)
                                val path = zip?.let { DiagReporter.saveZipToDownloads(ctx, it) }
                                DiagReporter.buildGitHubIssueUri(ctx, savedZipDisplayPath = path) to path
                            }
                            exportingDiag = false
                            if (!savedPath.isNullOrBlank()) {
                                Toast.makeText(ctx, "Saved zip to $savedPath", Toast.LENGTH_SHORT).show()
                            }
                            if (!DiagReporter.openGitHubIssue(ctx, uri)) {
                                Toast.makeText(ctx, "No browser app available", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            )
            navBeamDivider(Modifier.padding(horizontal = 6.dp))
            val sizeLabel = remember(diagSummary.totalBytes) {
                when {
                    diagSummary.totalBytes < 1024L -> "${diagSummary.totalBytes} B"
                    diagSummary.totalBytes < (1024L * 1024L) -> "${diagSummary.totalBytes / 1024L} KB"
                    else -> "%.1f MB".format(diagSummary.totalBytes / (1024.0 * 1024.0))
                }
            }
            val diagSub = if (diagSummary.fileCount == 0) {
                "Stored on device only · no logs yet"
            } else {
                "${diagSummary.fileCount} files (${diagSummary.crashCount} crash, ${diagSummary.rideCount} ride) · $sizeLabel"
            }
            SettingRow(
                icon = NavBeamIcons.Share,
                title = if (exportingDiag) "Preparing diagnostics…" else "Share Diagnostics (.zip)",
                sub = diagSub,
                control = { Icon(NavBeamIcons.ChevronRight, null, tint = colors.textLo, modifier = Modifier.size(18.dp)) },
                onClick = if (exportingDiag) null else {
                    {
                        exportingDiag = true
                        scope.launch {
                            val zip = withContext(Dispatchers.IO) { RideDiagnostics.exportZip(ctx) }
                            exportingDiag = false
                            if (zip == null) {
                                Toast.makeText(ctx, "Failed to bundle diagnostics", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            runCatching {
                                ctx.startActivity(DiagReporter.shareZipIntent(ctx, zip))
                            }.onFailure {
                                Toast.makeText(ctx, "No app available to share diagnostics", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            )
            navBeamDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                icon = NavBeamIcons.Trash,
                title = "Clear Local Logs",
                sub = "Delete local crash, exit, and ride logs",
                control = {
                    Text(
                        "Clear",
                        color = if (diagSummary.fileCount > 0) Alert else colors.textLo,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily,
                    )
                },
                last = true,
                onClick = if (diagSummary.fileCount == 0) null else {
                    {
                        scope.launch {
                            withContext(Dispatchers.IO) { RideDiagnostics.clearLogs(ctx) }
                            diagSummary = withContext(Dispatchers.IO) { RideDiagnostics.summary(ctx) }
                            Toast.makeText(ctx, "Diagnostic logs cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }

        Spacer(Modifier.height(22.dp))

        val appVersion = remember { io.github.saltmango.navbeam.data.UpdateChecker.currentVersionName(ctx) }
        Text(
            "NAVBEAM v$appVersion",
            color = colors.textDis, fontSize = 11.sp, fontFamily = GeistMonoFamily,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp)
                .clickable {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime < 500) {
                        clickCount++
                    } else {
                        clickCount = 1
                    }
                    lastClickTime = now
                    if (clickCount >= 10) {
                        clickCount = 0
                        showApiKeyDialog = true
                    }
                },
        )
    }

    if (showQualityDialog) {
        StreamQualityDialog(
            currentQuality = dashUi.streamQuality,
            onSelect = { q ->
                dashViewModel.setStreamQuality(q)
                showQualityDialog = false
                if (q == "Custom") {
                    showCustomConfigDialog = true
                }
            },
            onDismiss = { showQualityDialog = false }
        )
    }

    if (showCustomConfigDialog) {
        CustomConfigDialog(
            ctx = ctx,
            dashViewModel = dashViewModel,
            onDismiss = { showCustomConfigDialog = false }
        )
    }

    if (showNavModeDialog) {
        NavModeDialog(
            currentMode = dashUi.navMode,
            onSelect = { mode ->
                dashViewModel.setNavMode(mode)
                showNavModeDialog = false
            },
            onDismiss = { showNavModeDialog = false }
        )
    }





    if (showBikeNameDialog) {
        var bikeNameValue by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(SettingsPrefs.getBikeName(ctx).ifBlank { "My Bike" })) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBikeNameDialog = false },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val trimmed = bikeNameValue.text.trim().ifBlank { "My Bike" }
                        SettingsPrefs.setBikeName(ctx, trimmed)
                        bikeName = trimmed
                        showBikeNameDialog = false
                    }
                ) { Text("Save", color = Primary) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showBikeNameDialog = false }) {
                    Text("Cancel", color = colors.textMid)
                }
            },
            title = { Text("Bike Name", color = colors.textHi) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = bikeNameValue,
                    onValueChange = { bikeNameValue = it },
                    label = { Text("Name") },
                    placeholder = { Text("My Bike") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            containerColor = colors.surf1,
        )
    }

    if (showApiKeyDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val trimmed = apiKeyValue.text.trim()
                        SettingsPrefs.setMapsApiKey(ctx, trimmed)
                        io.github.saltmango.navbeam.data.MapsKeyManager.applyKey(ctx)
                        showApiKeyDialog = false
                    }
                ) {
                    Text("Save", color = Primary)
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
private fun SettingsSection(
    title: String,
    padding: Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = NavBeamTheme.colors
    Spacer(Modifier.height(18.dp))
    navBeamCard(modifier = Modifier.fillMaxWidth(), padding = 0.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surf2)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Eyebrow(title)
        }
        navBeamDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            content = content
        )
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    sub: String? = null,
    control: (@Composable () -> Unit)? = null,
    last: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = NavBeamTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 6.dp, vertical = 13.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(colors.surf2).border(1.dp, colors.line, RoundedCornerShape(11.dp)),
        ) {
            Icon(icon, contentDescription = null, tint = colors.textMid, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            if (sub != null) Text(sub, color = colors.textLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        control?.invoke()
    }
}

@Composable
private fun DashWifiDialog(
    currentSsid: String,
    currentPassword: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NavBeamTheme.colors
    var ssid by remember { mutableStateOf(currentSsid) }
    var password by remember { mutableStateOf(currentPassword) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = password.length >= 8,
                onClick = { onSave(ssid.trim(), password) }
            ) {
                Text("Save", color = if (password.length >= 8) Primary else colors.textLo)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid)
            }
        },
        title = { Text("Dash Wi‑Fi settings", color = colors.textHi) },
        text = {
            Column {
                Text(
                    "Leave SSID blank to auto-discover any nearby dash hotspot matching the prefix. The password must be at least 8 characters.",
                    color = colors.textLo, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("SSID (Optional)") },
                    placeholder = { Text("e.g. DISPLAY_AP_01") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        containerColor = colors.surf1,
    )
}

@Composable
private fun StreamQualityDialog(
    currentQuality: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NavBeamTheme.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid)
            }
        },
        title = { Text("Stream quality", color = colors.textHi) },
        text = {
            Column {
                Text(
                    "Adjust video streaming quality. Higher quality looks smoother but uses more battery.",
                    color = colors.textLo, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp)
                )
                listOf(
                    "Max" to "Max performance and frame rate (8 fps / 4 fps)",
                    "Balanced" to "Balanced performance and battery (4 fps / 2 fps)",
                    "Battery Saver" to "Conserves battery, lower frame rate (2 fps / 1 fps)",
                    "Custom" to "Configure custom frame rates and bitrates"
                ).forEach { (quality, desc) ->
                    val isSelected = quality == currentQuality
                    val bg = if (isSelected) Primary.copy(alpha = 0.08f) else colors.surf2.copy(alpha = 0.4f)
                    val borderStroke = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Primary) else null

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(bg)
                            .let {
                                if (borderStroke != null) it.border(borderStroke, RoundedCornerShape(10.dp)) else it
                            }
                            .clickable { onSelect(quality) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = quality,
                                color = if (isSelected) Primary else colors.textHi,
                                fontSize = 14.5.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                fontFamily = GeistFamily
                            )
                            Text(
                                text = desc,
                                color = colors.textLo,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        containerColor = colors.surf1,
    )
}

@Composable
private fun NavModeDialog(
    currentMode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NavBeamTheme.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid)
            }
        },
        title = { Text("Navigation mode", color = colors.textHi) },
        text = {
            Column {
                Text(
                    "Select how guidance is displayed on the dash. Arrow mode saves phone battery.",
                    color = colors.textLo, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp)
                )
                listOf(
                    "Map" to "Streams full map video to dash screen",
                    "Arrow" to "Turn-by-turn direction arrows only"
                ).forEach { (mode, desc) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(mode) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode,
                                color = if (mode == currentMode) Primary else colors.textHi,
                                fontSize = 14.5.sp,
                                fontWeight = if (mode == currentMode) FontWeight.SemiBold else FontWeight.Normal,
                                fontFamily = GeistFamily
                            )
                            Text(
                                text = desc,
                                color = colors.textLo,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (mode == currentMode) {
                            Icon(
                                imageVector = NavBeamIcons.Check,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        containerColor = colors.surf1,
    )
}

private fun formatTimeAgo(timeMs: Long): String {
    if (timeMs <= 0L) return "never"
    val diff = System.currentTimeMillis() - timeMs
    if (diff < 0) return "just now"
    val seconds = diff / 1000
    if (seconds < 60) return "just now"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m ago"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h ago"
    val days = hours / 24
    return "${days}d ago"
}

@Composable
private fun CustomConfigDialog(
    ctx: android.content.Context,
    dashViewModel: DashViewModel,
    onDismiss: () -> Unit
) {
    val colors = NavBeamTheme.colors
    var fpsHighway by remember { mutableStateOf(dashViewModel.getCustomFps("HIGHWAY")) }
    var bitHighway by remember { mutableStateOf(dashViewModel.getCustomBitrate("HIGHWAY")) }
    var fpsCity by remember { mutableStateOf(dashViewModel.getCustomFps("CITY")) }
    var bitCity by remember { mutableStateOf(dashViewModel.getCustomBitrate("CITY")) }
    var fpsSlow by remember { mutableStateOf(dashViewModel.getCustomFps("SLOW")) }
    var bitSlow by remember { mutableStateOf(dashViewModel.getCustomBitrate("SLOW")) }
    var fpsStopped by remember { mutableStateOf(dashViewModel.getCustomFps("STOPPED")) }
    var bitStopped by remember { mutableStateOf(dashViewModel.getCustomBitrate("STOPPED")) }
    var fpsCornering by remember { mutableStateOf(dashViewModel.getCustomFps("CORNERING")) }
    var bitCornering by remember { mutableStateOf(dashViewModel.getCustomBitrate("CORNERING")) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    dashViewModel.setCustomFps("HIGHWAY", fpsHighway)
                    dashViewModel.setCustomBitrate("HIGHWAY", bitHighway)
                    dashViewModel.setCustomFps("CITY", fpsCity)
                    dashViewModel.setCustomBitrate("CITY", bitCity)
                    dashViewModel.setCustomFps("SLOW", fpsSlow)
                    dashViewModel.setCustomBitrate("SLOW", bitSlow)
                    dashViewModel.setCustomFps("STOPPED", fpsStopped)
                    dashViewModel.setCustomBitrate("STOPPED", bitStopped)
                    dashViewModel.setCustomFps("CORNERING", fpsCornering)
                    dashViewModel.setCustomBitrate("CORNERING", bitCornering)
                    onDismiss()
                }
            ) {
                Text("Save", color = Primary)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid)
            }
        },
        title = { Text("Custom Quality Config", color = colors.textHi) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Configure the desired frame rate (1-8 fps) and H.264 bitrate (100-1200 Kbps) for each speed profile.",
                    color = colors.textLo, fontSize = 12.sp
                )

                CustomProfileSliders(
                    title = "Highway Speed",
                    fps = fpsHighway,
                    bitrate = bitHighway,
                    maxBitrate = 1200,
                    onFpsChange = { fpsHighway = it },
                    onBitrateChange = { bitHighway = it }
                )

                CustomProfileSliders(
                    title = "City Cruise",
                    fps = fpsCity,
                    bitrate = bitCity,
                    maxBitrate = 1200,
                    onFpsChange = { fpsCity = it },
                    onBitrateChange = { bitCity = it }
                )

                CustomProfileSliders(
                    title = "Slow / Crawling",
                    fps = fpsSlow,
                    bitrate = bitSlow,
                    maxBitrate = 1200,
                    onFpsChange = { fpsSlow = it },
                    onBitrateChange = { bitSlow = it }
                )

                CustomProfileSliders(
                    title = "Stopped / Standstill",
                    fps = fpsStopped,
                    bitrate = bitStopped,
                    maxBitrate = 1200,
                    onFpsChange = { fpsStopped = it },
                    onBitrateChange = { bitStopped = it }
                )

                CustomProfileSliders(
                    title = "Cornering / Turns",
                    fps = fpsCornering,
                    bitrate = bitCornering,
                    maxBitrate = 1200,
                    onFpsChange = { fpsCornering = it },
                    onBitrateChange = { bitCornering = it }
                )
            }
        },
        containerColor = colors.surf1,
    )
}

@Composable
private fun CustomProfileSliders(
    title: String,
    fps: Int,
    bitrate: Int,
    maxBitrate: Int,
    onFpsChange: (Int) -> Unit,
    onBitrateChange: (Int) -> Unit
) {
    val colors = NavBeamTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, color = colors.textHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Frame Rate: $fps fps", color = colors.textMid, fontSize = 11.5.sp, fontFamily = GeistFamily)
        }
        androidx.compose.material3.Slider(
            value = fps.toFloat(),
            onValueChange = { onFpsChange(Math.round(it).toInt()) },
            valueRange = 1f..8f,
            steps = 6,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Bitrate: $bitrate Kbps", color = colors.textMid, fontSize = 11.5.sp, fontFamily = GeistFamily)
        }
        androidx.compose.material3.Slider(
            value = bitrate.toFloat(),
            onValueChange = { onBitrateChange(Math.round(it).toInt()) },
            valueRange = 100f..maxBitrate.toFloat(),
            steps = ((maxBitrate - 100) / 100) - 1,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary)
        )
    }
}


