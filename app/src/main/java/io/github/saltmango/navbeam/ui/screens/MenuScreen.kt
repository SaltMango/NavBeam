package io.github.saltmango.navbeam.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.saltmango.navbeam.dash.nav.VoiceMode
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.data.DiagReporter
import io.github.saltmango.navbeam.data.MapsKeyManager
import io.github.saltmango.navbeam.data.RideDiagnostics
import io.github.saltmango.navbeam.data.SettingsPrefs
import io.github.saltmango.navbeam.data.UpdateChecker
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.components.*
import io.github.saltmango.navbeam.ui.theme.*
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
    onNavigate: (String) -> Unit,
) {
    val colors = NavBeamTheme.colors
    val dashUi by dashViewModel.ui.collectAsState()
    val themeMode by appViewModel.themeMode.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var autoConnect by remember { mutableStateOf(SettingsPrefs.getAutoConnect(ctx)) }
    var screenOff by remember { mutableStateOf(SettingsPrefs.getScreenOff(ctx)) }
    var keepAwake by remember { mutableStateOf(SettingsPrefs.getKeepAwake(ctx)) }
    var units by remember { mutableStateOf(SettingsPrefs.getUnits(ctx)) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showNavModeDialog by remember { mutableStateOf(false) }
    var showCustomConfigDialog by remember { mutableStateOf(false) }
    var bikeName by remember { mutableStateOf(SettingsPrefs.getBikeName(ctx).ifBlank { "My Bike" }) }
    var showBikeNameDialog by remember { mutableStateOf(false) }

    val activeProfile by DashProfileManager.activeProfile.collectAsState()

    var lastClickTime by remember { mutableStateOf(0L) }
    var clickCount by remember { mutableStateOf(0) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyValue by remember { mutableStateOf(TextFieldValue(SettingsPrefs.getMapsApiKey(ctx))) }

    LaunchedEffect(Unit) {
        autoConnect = SettingsPrefs.getAutoConnect(ctx)
        screenOff = SettingsPrefs.getScreenOff(ctx)
        keepAwake = SettingsPrefs.getKeepAwake(ctx)
        units = SettingsPrefs.getUnits(ctx)
    }

    val voiceManager = remember { io.github.saltmango.navbeam.dash.nav.VoiceManager.get(ctx) }
    val voiceMode by voiceManager.mode.collectAsState()
    val voice = when (voiceMode) {
        VoiceMode.OFF -> "Off"
        VoiceMode.TONE -> "Tone"
        VoiceMode.FULL -> "Voice"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Rider Profile card
        NavBeamCard(
            modifier = Modifier.fillMaxWidth(),
            padding = 16.dp,
            onClick = { showBikeNameDialog = true },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(NavBeamTheme.shapes.button)
                        .background(colors.primaryTint),
                ) {
                    Text(
                        text = bikeName.firstOrNull()?.uppercase() ?: "H",
                        color = colors.primary,
                        fontFamily = GeistMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = bikeName,
                        color = colors.textHi,
                        style = NavBeamTheme.typography.cardTitle,
                    )
                    Text(
                        text = "Rider Profile",
                        color = colors.textMid,
                        style = NavBeamTheme.typography.bodySecondary,
                    )
                }
            }
        }

        // Main Menu navigation items
        NavBeamCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            NavBeamRow(
                title = "Rides",
                sub = "Past recorded trips and stats",
                icon = NavBeamIcons.History,
                onClick = { onNavigate("rides") },
            )
        }

        // Display Connection
        NavBeamSection("Display Connection") {
            NavBeamRow(
                icon = NavBeamIcons.Sync,
                title = "Auto-connect on start",
                sub = "Link when the bike is near",
                trailingContent = {
                    NavBeamToggle(autoConnect) {
                        autoConnect = it
                        SettingsPrefs.setAutoConnect(ctx, it)
                    }
                },
            )
            NavBeamDivider(Modifier.padding(horizontal = 6.dp))
            val hasForgetOption = dashUi.ssid.isNotBlank()
            val showQuality = dashUi.navMode == "Map"
            NavBeamRow(
                icon = NavBeamIcons.Navi,
                title = "Navigation mode",
                sub = when (dashUi.navMode) {
                    "Arrow" -> "Arrow · directions only"
                    else -> "Map · full video cast"
                },
                onClick = { showNavModeDialog = true },
            )
            if (showQuality) {
                NavBeamDivider(Modifier.padding(horizontal = 6.dp))
                NavBeamRow(
                    icon = NavBeamIcons.Zap,
                    title = "Stream quality",
                    sub = when (dashUi.streamQuality) {
                        "Max" -> "Max · maximum performance"
                        "Battery Saver" -> "Battery Saver · saves power"
                        "Custom" -> "Custom · tap to configure"
                        else -> "Balanced · default"
                    },
                    onClick = { showQualityDialog = true },
                )
            }
            NavBeamDivider(Modifier.padding(horizontal = 6.dp))
            NavBeamRow(
                icon = NavBeamIcons.Wrench,
                title = "Hardware profile",
                sub = if (activeProfile.isConfigured) {
                    "${activeProfile.name} · ${activeProfile.display.width}×${activeProfile.display.height}"
                } else {
                    "No profile uploaded · Tap to set up"
                },
                onClick = { onNavigate("profile_setup") },
            )
            if (hasForgetOption) {
                NavBeamDivider(Modifier.padding(horizontal = 6.dp))
                NavBeamRow(
                    icon = NavBeamIcons.Lock,
                    iconTone = IconTone.Alert,
                    title = "Forget Paired Dash",
                    sub = "Allows connecting to a different dash",
                    trailingContent = {
                        Text(
                            text = "Forget",
                            color = colors.alert,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = GeistFamily,
                        )
                    },
                    onClick = { dashViewModel.forgetDash() },
                )
            }
        }

        NavBeamSection("Map Preferences") {
            NavBeamRow(
                icon = NavBeamIcons.Navi,
                title = "Heading-up Map",
                sub = "Rotates map to travel direction",
                trailingContent = {
                    NavBeamToggle(on = dashUi.headingUp, onChange = { dashViewModel.toggleHeadingUp() })
                },
            )
            NavBeamDivider(Modifier.padding(horizontal = 6.dp))
            NavBeamRow(
                icon = NavBeamIcons.Cross,
                title = "Map Adjust Mode",
                sub = "Allows joystick to pan the preview map",
                trailingContent = {
                    NavBeamToggle(on = dashUi.adjustMode, onChange = { dashViewModel.setAdjustMode(it) })
                },
            )
            NavBeamDivider(Modifier.padding(horizontal = 6.dp))
            NavBeamRow(
                icon = NavBeamIcons.Dash,
                title = "Open Map Preview",
                sub = "Live preview & dashboard cockpit",
                onClick = { onNavigate("dash") },
            )
        }

        NavBeamSection("Ride Optimization") {
            NavBeamRow(
                icon = NavBeamIcons.Power,
                title = "Turn phone screen off",
                sub = "Map keeps streaming to the dash",
                trailingContent = {
                    NavBeamToggle(screenOff) {
                        screenOff = it
                        SettingsPrefs.setScreenOff(ctx, it)
                    }
                },
            )
            NavBeamDivider(Modifier.padding(horizontal = 6.dp))
            NavBeamRow(
                icon = NavBeamIcons.Dash,
                title = "Keep dash awake",
                sub = "Prevent display sleep",
                trailingContent = {
                    NavBeamToggle(keepAwake) {
                        keepAwake = it
                        SettingsPrefs.setKeepAwake(ctx, it)
                    }
                },
            )
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        fun answerGranted() = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.ANSWER_PHONE_CALLS,
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
        NavBeamSection("Alerts & Media") {
            NavBeamRow(
                icon = NavBeamIcons.Zap,
                title = "Now playing & calls on dash",
                sub = if (mediaGranted) "Enabled · song info + caller shown while riding"
                else "Tap to allow notification access",
                trailingContent = {
                    NavBeamChip(
                        label = if (mediaGranted) "On" else "Enable",
                        tone = if (mediaGranted) ChipTone.Primary else ChipTone.Off,
                        dot = true,
                    )
                },
                onClick = if (mediaGranted) null else {
                    { runCatching { ctx.startActivity(io.github.saltmango.navbeam.media.MediaInfoProvider.accessSettingsIntent()) } }
                },
            )
            NavBeamDivider(Modifier.padding(horizontal = 6.dp))
            NavBeamRow(
                icon = NavBeamIcons.Bt,
                title = "Answer calls from joystick",
                sub = if (callAnswerGranted) "Enabled · UP answers, DOWN rejects"
                else "Tap to allow answering/rejecting calls",
                trailingContent = {
                    NavBeamChip(
                        label = if (callAnswerGranted) "On" else "Enable",
                        tone = if (callAnswerGranted) ChipTone.Primary else ChipTone.Off,
                        dot = true,
                    )
                },
                onClick = if (callAnswerGranted) null else {
                    { callPermLauncher.launch(android.Manifest.permission.ANSWER_PHONE_CALLS) }
                },
            )
        }

        NavBeamSection("Audio Guidance", contentPadding = 14.dp) {
            NavBeamSegmentedControl(
                options = listOf("Off", "Tone", "Voice"),
                selected = voice,
                onSelect = {
                    voiceManager.setMode(
                        when (it) {
                            "Off" -> VoiceMode.OFF
                            "Voice" -> VoiceMode.FULL
                            else -> VoiceMode.TONE
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        NavBeamSection("Theme", contentPadding = 14.dp) {
            NavBeamSegmentedControl(
                options = listOf("Light", "Dark", "System"),
                selected = when (themeMode) {
                    ThemeMode.Light -> "Light"
                    ThemeMode.Dark -> "Dark"
                    ThemeMode.System -> "System"
                },
                onSelect = {
                    val mode = when (it) {
                        "Light" -> ThemeMode.Light
                        "Dark" -> ThemeMode.Dark
                        else -> ThemeMode.System
                    }
                    appViewModel.setThemeMode(mode)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = when (themeMode) {
                    ThemeMode.Light -> "Light mode — bright high-contrast daytime interface"
                    ThemeMode.Dark -> "Dark mode — OLED pure black for night riding"
                    ThemeMode.System -> "System — automatically follows device theme"
                },
                color = colors.textLo,
                style = NavBeamTheme.typography.caption,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp),
            )
        }

        NavBeamSection("Distance Units", contentPadding = 14.dp) {
            NavBeamSegmentedControl(
                options = listOf("Kilometres", "Miles"),
                selected = units,
                onSelect = {
                    units = it
                    SettingsPrefs.setUnits(ctx, it)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        var diagSummary by remember { mutableStateOf(RideDiagnostics.DiagSummary(0, 0, 0, 0L)) }
        var exportingDiag by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            diagSummary = withContext(Dispatchers.IO) { RideDiagnostics.summary(ctx) }
        }
        NavBeamSection("Diagnostics & Bug Reports") {
            NavBeamRow(
                icon = NavBeamIcons.Flag,
                title = "Report Issue on GitHub",
                sub = "Pre-fills crash trace & ride performance (zero personal data)",
                trailingIcon = true,
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
                },
            )
            NavBeamDivider(Modifier.padding(horizontal = 6.dp))
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
            NavBeamRow(
                icon = NavBeamIcons.Share,
                title = if (exportingDiag) "Preparing diagnostics…" else "Share Diagnostics (.zip)",
                sub = diagSub,
                trailingIcon = true,
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
                },
            )
            NavBeamDivider(Modifier.padding(horizontal = 6.dp))
            NavBeamRow(
                icon = NavBeamIcons.Trash,
                iconTone = if (diagSummary.fileCount > 0) IconTone.Alert else IconTone.Neutral,
                title = "Clear Local Logs",
                sub = "Delete local crash, exit, and ride logs",
                trailingContent = {
                    Text(
                        text = "Clear",
                        color = if (diagSummary.fileCount > 0) colors.alert else colors.textLo,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily,
                    )
                },
                onClick = if (diagSummary.fileCount == 0) null else {
                    {
                        scope.launch {
                            withContext(Dispatchers.IO) { RideDiagnostics.clearLogs(ctx) }
                            diagSummary = withContext(Dispatchers.IO) { RideDiagnostics.summary(ctx) }
                            Toast.makeText(ctx, "Diagnostic logs cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }

        val appVersion = remember { UpdateChecker.currentVersionName(ctx) }
        Text(
            text = "NAVBEAM v$appVersion",
            color = colors.textDis,
            style = NavBeamTheme.typography.monoSmall,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 6.dp)
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
                if (q == "Custom") showCustomConfigDialog = true
            },
            onDismiss = { showQualityDialog = false },
        )
    }

    if (showCustomConfigDialog) {
        CustomConfigDialog(
            dashViewModel = dashViewModel,
            onDismiss = { showCustomConfigDialog = false },
        )
    }

    if (showNavModeDialog) {
        NavModeDialog(
            currentMode = dashUi.navMode,
            onSelect = { mode ->
                dashViewModel.setNavMode(mode)
                showNavModeDialog = false
            },
            onDismiss = { showNavModeDialog = false },
        )
    }

    if (showBikeNameDialog) {
        var bikeNameValue by remember { mutableStateOf(TextFieldValue(SettingsPrefs.getBikeName(ctx).ifBlank { "My Bike" })) }
        AlertDialog(
            onDismissRequest = { showBikeNameDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = bikeNameValue.text.trim().ifBlank { "My Bike" }
                        SettingsPrefs.setBikeName(ctx, trimmed)
                        bikeName = trimmed
                        showBikeNameDialog = false
                    },
                ) { Text("Save", color = colors.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showBikeNameDialog = false }) {
                    Text("Cancel", color = colors.textMid)
                }
            },
            title = { Text("Bike Name", color = colors.textHi) },
            text = {
                OutlinedTextField(
                    value = bikeNameValue,
                    onValueChange = { bikeNameValue = it },
                    label = { Text("Name") },
                    placeholder = { Text("My Bike") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            containerColor = colors.surf1,
        )
    }

    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = apiKeyValue.text.trim()
                        SettingsPrefs.setMapsApiKey(ctx, trimmed)
                        MapsKeyManager.applyKey(ctx)
                        showApiKeyDialog = false
                    },
                ) {
                    Text("Save", color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) {
                    Text("Cancel", color = colors.textMid)
                }
            },
            title = { Text("Google Maps API Key", color = colors.textHi) },
            text = {
                Column {
                    Text(
                        text = "Paste your Google Maps API Key to enable maps, routing, and navigation. Get one from the Google Cloud Console — enable Maps SDK for Android and restrict it to this app's package name.",
                        color = colors.textLo,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    OutlinedTextField(
                        value = apiKeyValue,
                        onValueChange = { apiKeyValue = it },
                        label = { Text("API Key") },
                        placeholder = { Text("AIzaSy...") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            containerColor = colors.surf1,
        )
    }
}

@Composable
private fun StreamQualityDialog(
    currentQuality: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = NavBeamTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid)
            }
        },
        title = { Text("Stream quality", color = colors.textHi) },
        text = {
            Column {
                Text(
                    text = "Adjust video streaming quality. Higher quality looks smoother but uses more battery.",
                    color = colors.textLo,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                listOf(
                    "Max" to "Max performance and frame rate (8 fps / 4 fps)",
                    "Balanced" to "Balanced performance and battery (4 fps / 2 fps)",
                    "Battery Saver" to "Conserves battery, lower frame rate (2 fps / 1 fps)",
                    "Custom" to "Configure custom frame rates and bitrates",
                ).forEach { (quality, desc) ->
                    val isSelected = quality == currentQuality
                    val bg = if (isSelected) colors.primaryTint else colors.surf2.copy(alpha = 0.4f)
                    val borderStroke = if (isSelected) BorderStroke(1.5.dp, colors.primary) else null

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
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = quality,
                                color = if (isSelected) colors.primary else colors.textHi,
                                fontSize = 14.5.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                fontFamily = GeistFamily,
                            )
                            Text(
                                text = desc,
                                color = colors.textLo,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(top = 2.dp),
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
    onDismiss: () -> Unit,
) {
    val colors = NavBeamTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid)
            }
        },
        title = { Text("Navigation mode", color = colors.textHi) },
        text = {
            Column {
                Text(
                    text = "Select how guidance is displayed on the dash. Arrow mode saves phone battery.",
                    color = colors.textLo,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                listOf(
                    "Map" to "Streams full map video to dash screen",
                    "Arrow" to "Turn-by-turn direction arrows only",
                ).forEach { (mode, desc) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(mode) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode,
                                color = if (mode == currentMode) colors.primary else colors.textHi,
                                fontSize = 14.5.sp,
                                fontWeight = if (mode == currentMode) FontWeight.SemiBold else FontWeight.Normal,
                                fontFamily = GeistFamily,
                            )
                            Text(
                                text = desc,
                                color = colors.textLo,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (mode == currentMode) {
                            Icon(
                                imageVector = NavBeamIcons.Check,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp),
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
private fun CustomConfigDialog(
    dashViewModel: DashViewModel,
    onDismiss: () -> Unit,
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

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
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
                },
            ) {
                Text("Save", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid)
            }
        },
        title = { Text("Custom Quality Config", color = colors.textHi) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Configure the desired frame rate (1-8 fps) and H.264 bitrate (100-1200 Kbps) for each speed profile.",
                    color = colors.textLo,
                    fontSize = 12.sp,
                )

                CustomProfileSliders("Highway Speed", fpsHighway, bitHighway, 1200, { fpsHighway = it }, { bitHighway = it })
                CustomProfileSliders("City Cruise", fpsCity, bitCity, 1200, { fpsCity = it }, { bitCity = it })
                CustomProfileSliders("Slow / Crawling", fpsSlow, bitSlow, 1200, { fpsSlow = it }, { bitSlow = it })
                CustomProfileSliders("Stopped / Standstill", fpsStopped, bitStopped, 1200, { fpsStopped = it }, { bitStopped = it })
                CustomProfileSliders("Cornering / Turns", fpsCornering, bitCornering, 1200, { fpsCornering = it }, { bitCornering = it })
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
    onBitrateChange: (Int) -> Unit,
) {
    val colors = NavBeamTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, color = colors.textHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
        Spacer(Modifier.height(8.dp))
        Text("Frame Rate: $fps fps", color = colors.textMid, fontSize = 11.5.sp, fontFamily = GeistFamily)
        Slider(
            value = fps.toFloat(),
            onValueChange = { onFpsChange(Math.round(it)) },
            valueRange = 1f..8f,
            steps = 6,
            colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
        )
        Text("Bitrate: $bitrate Kbps", color = colors.textMid, fontSize = 11.5.sp, fontFamily = GeistFamily)
        Slider(
            value = bitrate.toFloat(),
            onValueChange = { onBitrateChange(Math.round(it)) },
            valueRange = 100f..maxBitrate.toFloat(),
            steps = ((maxBitrate - 100) / 100) - 1,
            colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
        )
    }
}
