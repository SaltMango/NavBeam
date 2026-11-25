package io.github.saltmango.navbeam.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.saltmango.navbeam.dash.profile.DashProfile
import io.github.saltmango.navbeam.dash.profile.DashProfileJsonCodec
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.components.*
import io.github.saltmango.navbeam.ui.theme.*
import io.github.saltmango.navbeam.viewmodel.ConnStage
import io.github.saltmango.navbeam.viewmodel.DashViewModel

private const val COMMUNITY_GITHUB_URL = "https://github.com/SaltMango/NavBeam"

@Composable
fun ProfileSetupScreen(
    onComplete: () -> Unit,
    onBack: (() -> Unit)? = null,
    isOnboarding: Boolean = false,
    dashViewModel: DashViewModel? = null,
) {
    val ctx = LocalContext.current
    val colors = NavBeamTheme.colors
    val activeProfile by DashProfileManager.activeProfile.collectAsState()
    val availableProfiles by DashProfileManager.availableProfiles.collectAsState()

    var pendingImportProfile by remember { mutableStateOf<DashProfile?>(null) }
    var profileChangePrompt by remember { mutableStateOf<String?>(null) }

    val openDocumentMimeTypes = remember {
        arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
    }

    // SAF File Pickers for Import & Export
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    DashProfileJsonCodec.fromStream(stream).getOrThrow()
                } ?: throw IllegalStateException("Unable to open selected file.")
            }.onSuccess { parsed ->
                pendingImportProfile = parsed
            }.onFailure { err ->
                Toast.makeText(ctx, "Invalid profile JSON: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val json = DashProfileJsonCodec.toJson(activeProfile)
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Unable to write file.")
            }.onSuccess {
                Toast.makeText(ctx, "Profile exported successfully", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(ctx, "Export failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Android Permissions for Onboarding Flow
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        onComplete()
    }

    fun handleContinue() {
        if (!isOnboarding) {
            onBack?.invoke() ?: onComplete()
            return
        }
        val allGranted = requestedPermissions.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            onComplete()
        } else {
            permissionLauncher.launch(requestedPermissions)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            eyebrow = if (isOnboarding) "Step 1 of 2" else "Configuration",
            title = "Hardware profile",
            hint = if (isOnboarding)
                "Set up your motorcycle dash screen before connecting."
            else
                "Manage screen resolution, network parameters, and control bindings.",
            onBack = onBack,
            trailing = {
                Row {
                    if (activeProfile.isConfigured) {
                        IconButton(
                            onClick = { exportLauncher.launch("${activeProfile.id}.json") },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(NavBeamIcons.Share, contentDescription = "Export profile", tint = colors.textMid, modifier = Modifier.size(19.dp))
                        }
                    }
                    IconButton(
                        onClick = { importLauncher.launch(openDocumentMimeTypes) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(NavBeamIcons.Download, contentDescription = "Import profile", tint = Primary, modifier = Modifier.size(19.dp))
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Simple explanation card
            navBeamCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(PrimaryTint)
                    ) {
                        Icon(NavBeamIcons.Dash, contentDescription = null, tint = Primary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "What is a Dash Profile?",
                            color = colors.textHi,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = GeistFamily
                        )
                        Text(
                            text = "NavBeam streams your live navigation map and turn indicators to your bike's round display. Every motorcycle screen model has different dimensions, Wi-Fi addresses, and button controls.\n\nA profile is a simple JSON file that tells NavBeam how to communicate with your specific dash.",
                            color = colors.textLo,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            // 2. Community Profiles card
            navBeamCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surf2)
                            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                    ) {
                        Icon(NavBeamIcons.Motor, contentDescription = null, tint = colors.textMid, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Get from Community",
                            color = colors.textHi,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = GeistFamily
                        )
                        Text(
                            text = "Have a different motorcycle dash or custom screen? Riders share tested JSON profiles for various motorcycle models in the NavBeam community.",
                            color = colors.textLo,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                        )
                        navBeamBtn(
                            label = "Browse Community Profiles",
                            onClick = {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(COMMUNITY_GITHUB_URL))
                                    ctx.startActivity(intent)
                                }
                            },
                            icon = NavBeamIcons.ChevronRight,
                            variant = BtnVariant.Secondary,
                            size = BtnSize.Sm,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 3. Upload Custom JSON Action Button
            navBeamCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
                Column {
                    Text(
                        text = "Upload Custom Profile",
                        color = colors.textHi,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily
                    )
                    Text(
                        text = "Got a .json profile file from the community or created one yourself? Tap below to select and import it.",
                        color = colors.textLo,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    navBeamBtn(
                        label = "Select .json File to Upload",
                        onClick = { importLauncher.launch(openDocumentMimeTypes) },
                        icon = NavBeamIcons.Plus,
                        variant = BtnVariant.Primary,
                        size = BtnSize.Md,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 4. Profiles list
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "UPLOADED PROFILES",
                    color = colors.textLo,
                    fontSize = 11.5.sp,
                    fontFamily = GeistMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )

                if (availableProfiles.isEmpty()) {
                    navBeamCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = NavBeamIcons.Download,
                                contentDescription = null,
                                tint = colors.textLo,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "No profile uploaded yet",
                                color = colors.textHi,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GeistFamily
                            )
                            Text(
                                text = "Download a .json profile from our community and tap upload above.",
                                color = colors.textLo,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                } else {
                    navBeamCard(modifier = Modifier.fillMaxWidth(), padding = 4.dp) {
                        Column {
                            availableProfiles.forEachIndexed { index, summary ->
                                val isSelected = summary.id == activeProfile.id
                                val bg = if (isSelected) Primary.copy(alpha = 0.08f) else Color.Transparent

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(bg)
                                        .clickable {
                                            val stage = dashViewModel?.ui?.value?.stage
                                            val hasActiveConn = stage != null && stage != ConnStage.OFFLINE && stage != ConnStage.ERROR
                                            if (hasActiveConn && !isSelected) {
                                                profileChangePrompt = summary.id
                                            } else {
                                                DashProfileManager.selectProfile(summary.id)
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = summary.name,
                                                color = if (isSelected) Primary else colors.textHi,
                                                fontSize = 14.5.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                fontFamily = GeistFamily
                                            )
                                            if (summary.firmwareVersion.isNotBlank()) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "FW: ${summary.firmwareVersion}",
                                                    color = Primary,
                                                    fontSize = 10.5.sp,
                                                    modifier = Modifier
                                                        .background(colors.surf2, RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        if (summary.description.isNotBlank()) {
                                            Text(
                                                text = summary.description,
                                                color = colors.textLo,
                                                fontSize = 11.5.sp,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }

                                    if (isSelected) {
                                        Icon(
                                            imageVector = NavBeamIcons.Check,
                                            contentDescription = "Active profile",
                                            tint = Primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            val stage = dashViewModel?.ui?.value?.stage
                                            val hasActiveConn = stage != null && stage != ConnStage.OFFLINE && stage != ConnStage.ERROR
                                            if (isSelected && hasActiveConn) {
                                                dashViewModel.disconnect()
                                            }
                                            DashProfileManager.deleteCustomProfile(summary.id)
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = NavBeamIcons.Trash,
                                            contentDescription = "Delete profile",
                                            tint = Alert,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }

                                if (index < availableProfiles.size - 1) {
                                    navBeamDivider(Modifier.padding(horizontal = 8.dp))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        // Bottom CTA
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surf1)
                .padding(16.dp)
        ) {
            val hasProfile = activeProfile.id.isNotBlank()
            navBeamBtn(
                label = if (!hasProfile) "Upload a Profile to Continue" else if (isOnboarding) "Continue to Permissions" else "Done",
                onClick = { handleContinue() },
                enabled = hasProfile,
                variant = if (hasProfile) BtnVariant.Primary else BtnVariant.Secondary,
                size = BtnSize.Lg,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // Import Preview Verification Dialog
    if (pendingImportProfile != null) {
        val candidate = pendingImportProfile!!
        AlertDialog(
            onDismissRequest = { pendingImportProfile = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val stage = dashViewModel?.ui?.value?.stage
                        val hasActiveConn = stage != null && stage != ConnStage.OFFLINE && stage != ConnStage.ERROR
                        if (hasActiveConn) {
                            dashViewModel.disconnect()
                        }
                        val result = DashProfileManager.saveAndActivate(candidate)
                        result.onSuccess {
                            Toast.makeText(ctx, "Profile '${it.name}' imported & activated", Toast.LENGTH_SHORT).show()
                            pendingImportProfile = null
                        }.onFailure { err ->
                            Toast.makeText(ctx, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text("Import & Activate", color = Primary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportProfile = null }) {
                    Text("Cancel", color = colors.textMid)
                }
            },
            title = { Text("Verify Profile JSON", color = colors.textHi) },
            text = {
                Column {
                    Text(
                        "Review parameters before activating this hardware profile:",
                        color = colors.textLo,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    navBeamCard(modifier = Modifier.fillMaxWidth(), padding = 10.dp) {
                        Column {
                            Text(candidate.name, color = colors.textHi, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, fontFamily = GeistFamily)
                            Text("ID: ${candidate.id}", color = colors.textLo, fontSize = 11.5.sp)
                            if (candidate.description.isNotBlank()) {
                                Text(candidate.description, color = colors.textMid, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            navBeamDivider()
                            Spacer(Modifier.height(8.dp))
                            Text("Display: ${candidate.display.width}×${candidate.display.height} (${candidate.display.shape})", color = colors.textHi, fontSize = 12.5.sp)
                            if (candidate.firmwareVersion.isNotBlank()) {
                                Text("Firmware: ${candidate.firmwareVersion}", color = colors.textHi, fontSize = 12.5.sp)
                            }
                            Text("Gateway IP: ${candidate.network.gatewayIp}", color = colors.textHi, fontSize = 12.5.sp)
                            Text("Ports: TX :${candidate.network.controlTxPort} · RX :${candidate.network.controlRxPort} · RTP :${candidate.network.videoRtpPort}", color = colors.textLo, fontSize = 11.5.sp)
                            Text("Maneuver Glyphs: ${candidate.maneuvers.size} supported", color = colors.textLo, fontSize = 11.5.sp)
                            Text("Security Check: Passed (Safe bounds & RFC 1918 numeric IP)", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            },
            containerColor = colors.surf1
        )
    }

    // Safety Prompt for active streams
    if (profileChangePrompt != null) {
        val targetId = profileChangePrompt!!
        AlertDialog(
            onDismissRequest = { profileChangePrompt = null },
            title = { Text("Restart Active Stream?", color = colors.textHi) },
            text = {
                Text(
                    "Switching hardware profiles requires restarting the active dash session so new video and network parameters can take effect.",
                    color = colors.textLo,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dashViewModel?.disconnect()
                        DashProfileManager.selectProfile(targetId)
                        profileChangePrompt = null
                    }
                ) {
                    Text("Disconnect & Apply", color = Alert)
                }
            },
            dismissButton = {
                TextButton(onClick = { profileChangePrompt = null }) {
                    Text("Cancel", color = colors.textMid)
                }
            },
            containerColor = colors.surf1
        )
    }
}
