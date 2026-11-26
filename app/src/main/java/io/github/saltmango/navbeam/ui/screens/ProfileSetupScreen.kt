package io.github.saltmango.navbeam.ui.screens

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.saltmango.navbeam.dash.profile.DashProfile
import io.github.saltmango.navbeam.dash.profile.DashProfileJsonCodec
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.dash.profile.DisplayShape
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.components.*
import io.github.saltmango.navbeam.ui.theme.*
import io.github.saltmango.navbeam.viewmodel.ConnStage
import io.github.saltmango.navbeam.viewmodel.DashViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val COMMUNITY_GITHUB_URL = "https://github.com/SaltMango/NavBeam"
private const val TEMPLATE_SCHEMA_URL = "https://github.com/SaltMango/NavBeam/blob/main/DashProfile.json"

private data class PendingProfileImport(
    val profile: DashProfile,
    val sourceLabel: String,
)

@Composable
fun ProfileSetupScreen(
    onComplete: () -> Unit,
    onBack: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    isOnboarding: Boolean = false,
    dashViewModel: DashViewModel? = null,
) {
    val ctx = LocalContext.current
    val colors = NavBeamTheme.colors
    val scope = rememberCoroutineScope()
    val activeProfile by DashProfileManager.activeProfile.collectAsState()
    val availableProfiles by DashProfileManager.availableProfiles.collectAsState()

    var pendingImport by remember { mutableStateOf<PendingProfileImport?>(null) }
    var profileChangePrompt by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    var showPasteDialog by remember { mutableStateOf(false) }
    var pastedJson by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }

    // Use */* so OEM file pickers never grey out .json files; strict UTF-8 + 64KB + schema
    // validation in DashProfileJsonCodec safely rejects non-JSON or malformed files.
    val openDocumentMimeTypes = remember { arrayOf("*/*") }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImporting = true
        importError = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val (displayName, reportedSize) = queryUriMetadata(ctx, uri)
                    if (reportedSize != null && reportedSize > DashProfileJsonCodec.MAX_PROFILE_BYTES) {
                        throw SecurityException(
                            "File is too large ($reportedSize bytes). Maximum allowed size is 64 KB."
                        )
                    }
                    val parsed = ctx.contentResolver.openInputStream(uri)?.use { stream ->
                        DashProfileJsonCodec.fromStream(stream).getOrThrow()
                    } ?: throw IllegalStateException("Unable to open the selected file.")
                    PendingProfileImport(
                        profile = parsed,
                        sourceLabel = displayName ?: "Selected .json file",
                    )
                }
            }
            isImporting = false
            outcome.onSuccess { imported ->
                importError = null
                pendingImport = imported
            }.onFailure { err ->
                importError = err.message ?: "Failed to parse profile JSON."
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null || !activeProfile.isConfigured) return@rememberLauncherForActivityResult
        val snapshot = activeProfile
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = DashProfileJsonCodec.toJson(snapshot)
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Unable to write destination file.")
                }
            }
            result.onSuccess {
                Toast.makeText(ctx, "Exported '${snapshot.name}'", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(ctx, "Export failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Android runtime permissions required for Wi-Fi discovery, GPS turn-by-turn, and FGS alerts.
    val requestedPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun hasAllPermissions(): Boolean = requestedPermissions.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    var allPermissionsGranted by remember { mutableStateOf(hasAllPermissions()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        allPermissionsGranted = hasAllPermissions()
        onComplete()
    }

    fun handleContinue() {
        if (!isOnboarding) {
            onBack?.invoke() ?: onComplete()
            return
        }
        if (hasAllPermissions()) {
            onComplete()
        } else {
            permissionLauncher.launch(requestedPermissions)
        }
    }

    fun openExternalUrl(url: String) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(ctx, "No browser app found to open link.", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg1)
    ) {
        NavBeamScreenHeader(
            eyebrow = if (isOnboarding) "Setup" else "Configuration",
            title = "Hardware Profile",
            hint = "Import your motorcycle display's .json profile.",
            onBack = onBack,
            trailing = {
                if (activeProfile.isConfigured) {
                    IconButton(
                        onClick = { exportLauncher.launch("${activeProfile.id}.json") },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = NavBeamIcons.Share,
                            contentDescription = "Export active profile",
                            tint = colors.textMid,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        )

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
            // 1. Hero Card: Active Profile Telemetry OR Friendly Empty Status + Plain Instructions
            if (activeProfile.isConfigured) {
                ActiveProfileHeroCard(profile = activeProfile)
            } else {
                EmptyProfileHeroCard()
                Text(
                    text = "To connect your phone to your bike's screen, upload or paste the .json config file for your display model.",
                    color = colors.textHi,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    fontFamily = GeistFamily,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }

            // 2. Import Actions (Upload .json file or Paste JSON + inline error banner)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp)
                ) {
                    NavBeamEyebrow(text = "Add Config File")
                    if (isImporting) {
                        CircularProgressIndicator(
                            color = colors.primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (importError != null) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.alertTint)
                            .border(1.dp, colors.alert.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Icon(
                            imageVector = NavBeamIcons.ErrorOutline,
                            contentDescription = null,
                            tint = colors.alert,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Invalid Profile",
                                color = colors.alert,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GeistFamily
                            )
                            Text(
                                text = importError ?: "",
                                color = colors.textHi,
                                fontSize = 12.sp,
                                lineHeight = 16.5.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        IconButton(
                            onClick = { importError = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = NavBeamIcons.X,
                                contentDescription = "Dismiss error",
                                tint = colors.textMid,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    NavBeamButton(
                        label = if (isImporting) "Reading…" else "Upload .json",
                        onClick = { importLauncher.launch(openDocumentMimeTypes) },
                        icon = NavBeamIcons.UploadFile,
                        variant = BtnVariant.Primary,
                        size = BtnSize.Lg,
                        enabled = !isImporting,
                        modifier = Modifier.weight(1f)
                    )
                    NavBeamButton(
                        label = "Paste JSON",
                        onClick = {
                            pasteError = null
                            showPasteDialog = true
                        },
                        icon = NavBeamIcons.Paste,
                        variant = BtnVariant.Secondary,
                        size = BtnSize.Lg,
                        enabled = !isImporting,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 3. Saved Profiles List
            if (availableProfiles.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    NavBeamEyebrow(
                        text = "Saved Profiles (${availableProfiles.size})",
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )

                    NavBeamCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                        Column {
                            availableProfiles.forEachIndexed { index, summary ->
                                val isSelected = summary.id == activeProfile.id
                                val shapeShort = when (summary.shape) {
                                    DisplayShape.CIRCLE -> "Circle"
                                    DisplayShape.RECTANGLE -> "Rect"
                                    null -> ""
                                }
                                val specsLine = buildList {
                                    if (summary.width > 0 && summary.height > 0) {
                                        add(
                                            if (shapeShort.isNotEmpty()) "${summary.width}×${summary.height} $shapeShort"
                                            else "${summary.width}×${summary.height}"
                                        )
                                    }
                                    if (summary.gatewayIp.isNotBlank()) add(summary.gatewayIp)
                                    if (summary.authType.isNotBlank()) add(summary.authType)
                                }.joinToString(" · ")

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) colors.primaryTint else Color.Transparent)
                                        .clickable {
                                            val stage = dashViewModel?.ui?.value?.stage
                                            val hasActiveConn = stage != null &&
                                                stage != ConnStage.OFFLINE &&
                                                stage != ConnStage.ERROR
                                            if (hasActiveConn && !isSelected) {
                                                profileChangePrompt = summary.id
                                            } else {
                                                DashProfileManager.selectProfile(summary.id)
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) colors.primary else Color.Transparent)
                                            .border(
                                                width = 1.5.dp,
                                                color = if (isSelected) colors.primary else colors.line3,
                                                shape = CircleShape
                                            )
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = NavBeamIcons.Check,
                                                contentDescription = "Active",
                                                tint = colors.onPrimary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = summary.name,
                                                color = if (isSelected) colors.primary else colors.textHi,
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = GeistFamily,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (summary.firmwareVersion.isNotBlank()) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "v${summary.firmwareVersion}",
                                                    color = colors.textMid,
                                                    fontSize = 10.5.sp,
                                                    fontFamily = GeistMonoFamily,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(5.dp))
                                                        .background(colors.surf2)
                                                        .border(1.dp, colors.line, RoundedCornerShape(5.dp))
                                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        if (specsLine.isNotBlank()) {
                                            Text(
                                                text = specsLine,
                                                color = colors.textMid,
                                                fontSize = 11.5.sp,
                                                fontFamily = GeistMonoFamily,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 3.dp)
                                            )
                                        }
                                        if (summary.description.isNotBlank()) {
                                            Text(
                                                text = summary.description,
                                                color = colors.textLo,
                                                fontSize = 11.5.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    IconButton(
                                        onClick = {
                                            val stage = dashViewModel?.ui?.value?.stage
                                            val hasActiveConn = stage != null &&
                                                stage != ConnStage.OFFLINE &&
                                                stage != ConnStage.ERROR
                                            if (isSelected && hasActiveConn) {
                                                dashViewModel.disconnect()
                                            }
                                            DashProfileManager.deleteCustomProfile(summary.id)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = NavBeamIcons.Trash,
                                            contentDescription = "Delete profile",
                                            tint = colors.alert,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }

                                if (index < availableProfiles.size - 1) {
                                    NavBeamDivider(Modifier.padding(horizontal = 8.dp))
                                }
                            }
                        }
                    }
                }
            }

                Spacer(Modifier.weight(1f))

                // 4. Resources & Schema Template Links
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 2.dp)) {
                        NavBeamEyebrow(text = "Don't Have a Config File?")
                        Text(
                            text = "Find one for your bike on GitHub or create your own from the template.",
                            color = colors.textMid,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = GeistFamily,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NavBeamButton(
                            label = "GitHub Repo",
                            onClick = { openExternalUrl(COMMUNITY_GITHUB_URL) },
                            icon = NavBeamIcons.OpenInNew,
                            variant = BtnVariant.Ghost,
                            size = BtnSize.Sm,
                            modifier = Modifier.weight(1f)
                        )
                        NavBeamButton(
                            label = "JSON Template",
                            onClick = { openExternalUrl(TEMPLATE_SCHEMA_URL) },
                            icon = NavBeamIcons.Code,
                            variant = BtnVariant.Ghost,
                            size = BtnSize.Sm,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Bottom Sticky CTA
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surf1)
                .navigationBarsPadding()
        ) {
            NavBeamDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val hasProfile = activeProfile.isConfigured
                if (!hasProfile && isOnboarding && onSkip != null) {
                    NavBeamButton(
                        label = "Skip for now",
                        onClick = onSkip,
                        variant = BtnVariant.Secondary,
                        size = BtnSize.Lg,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "You can add one later in Menu → Hardware Profile.",
                        color = colors.textMid,
                        fontSize = 13.sp,
                        fontFamily = GeistFamily,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                } else {
                    val ctaLabel = when {
                        !hasProfile -> "Upload a Profile to Continue"
                        !isOnboarding -> "Done"
                        allPermissionsGranted -> "Continue to NavBeam"
                        else -> "Continue & Grant Permissions"
                    }
                    NavBeamButton(
                        label = ctaLabel,
                        onClick = { handleContinue() },
                        enabled = hasProfile,
                        variant = if (hasProfile) BtnVariant.Primary else BtnVariant.Secondary,
                        size = BtnSize.Lg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (hasProfile) 1f else 0.55f)
                    )
                }
            }
        }
    }

    // Paste Raw JSON Dialog
    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            containerColor = colors.surf1,
            title = {
                Text(
                    text = "Paste Profile JSON",
                    color = colors.textHi,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Paste your profile JSON below:",
                            color = colors.textLo,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                                if (clipText.isNullOrBlank()) {
                                    pasteError = "Clipboard is empty."
                                } else if (clipText.toByteArray(Charsets.UTF_8).size > DashProfileJsonCodec.MAX_PROFILE_BYTES) {
                                    pasteError = "Clipboard text exceeds 64 KB limit."
                                } else {
                                    pastedJson = clipText
                                    pasteError = null
                                }
                            }
                        ) {
                            Text("From Clipboard", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    OutlinedTextField(
                        value = pastedJson,
                        onValueChange = {
                            pastedJson = it
                            if (pasteError != null) pasteError = null
                        },
                        placeholder = {
                            Text(
                                "{\n  \"id\": \"my_dash\",\n  ...\n}",
                                color = colors.textLo,
                                fontFamily = GeistMonoFamily,
                                fontSize = 11.5.sp
                            )
                        },
                        textStyle = TextStyle(
                            color = colors.textHi,
                            fontFamily = GeistMonoFamily,
                            fontSize = 11.5.sp
                        ),
                        minLines = 6,
                        maxLines = 10,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.line2,
                            focusedContainerColor = colors.surf2,
                            unfocusedContainerColor = colors.surf2,
                            cursorColor = colors.primary,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (pasteError != null) {
                        Text(
                            text = pasteError ?: "",
                            color = colors.alert,
                            fontSize = 11.5.sp,
                            lineHeight = 15.5.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val raw = pastedJson
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                DashProfileJsonCodec.fromJson(raw)
                            }
                            result.onSuccess { parsed ->
                                pasteError = null
                                showPasteDialog = false
                                pastedJson = ""
                                pendingImport = PendingProfileImport(
                                    profile = parsed,
                                    sourceLabel = "Pasted JSON",
                                )
                            }.onFailure { err ->
                                pasteError = err.message ?: "Invalid profile JSON."
                            }
                        }
                    }
                ) {
                    Text("Validate", color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text("Cancel", color = colors.textMid)
                }
            }
        )
    }

    // Import Verification Dialog
    if (pendingImport != null) {
        val candidate = pendingImport!!.profile
        val sourceLabel = pendingImport!!.sourceLabel
        val shapeLabel = when (candidate.display.shape) {
            DisplayShape.CIRCLE -> "Circle"
            DisplayShape.RECTANGLE -> "Rectangle"
            null -> "Unknown"
        }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            containerColor = colors.surf1,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = NavBeamIcons.Verified,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Verify Profile",
                        color = colors.textHi,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Source: $sourceLabel",
                        color = colors.textLo,
                        fontSize = 11.5.sp,
                        fontFamily = GeistMonoFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    NavBeamCard(modifier = Modifier.fillMaxWidth(), padding = 12.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = candidate.name,
                                color = colors.textHi,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GeistFamily
                            )
                            Text(
                                text = buildString {
                                    append("id: ${candidate.id}")
                                    if (candidate.firmwareVersion.isNotBlank()) {
                                        append("  ·  fw: ${candidate.firmwareVersion}")
                                    }
                                },
                                color = colors.textLo,
                                fontSize = 11.5.sp,
                                fontFamily = GeistMonoFamily
                            )
                            if (candidate.description.isNotBlank()) {
                                Text(
                                    text = candidate.description,
                                    color = colors.textMid,
                                    fontSize = 12.sp
                                )
                            }
                            NavBeamDivider(Modifier.padding(vertical = 4.dp))
                            VerifySpecLine(
                                label = "Display",
                                value = "${candidate.display.width}×${candidate.display.height} ($shapeLabel) · ${candidate.display.activeBitrateBps / 1000} kbps"
                            )
                            VerifySpecLine(
                                label = "Wi-Fi",
                                value = "${candidate.network.defaultSsidPrefix}* → ${candidate.network.gatewayIp}"
                            )
                            VerifySpecLine(
                                label = "UDP Ports",
                                value = "TX :${candidate.network.controlTxPort} · RX :${candidate.network.controlRxPort} · RTP :${candidate.network.videoRtpPort}"
                            )
                            VerifySpecLine(
                                label = "Protocol",
                                value = "${candidate.protocol.magic} · ${candidate.protocol.authType}"
                            )
                            VerifySpecLine(
                                label = "Controls",
                                value = "${candidate.maneuvers.size} turn icons · ${candidate.inputs.size} buttons"
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.primaryTint)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = NavBeamIcons.Check,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Profile checks passed — ready to use.",
                            color = colors.primary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 15.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val stage = dashViewModel?.ui?.value?.stage
                        val hasActiveConn = stage != null &&
                            stage != ConnStage.OFFLINE &&
                            stage != ConnStage.ERROR
                        if (hasActiveConn) {
                            dashViewModel.disconnect()
                        }
                        DashProfileManager.saveAndActivate(candidate)
                            .onSuccess {
                                Toast.makeText(ctx, "Profile '${it.name}' activated", Toast.LENGTH_SHORT).show()
                                pendingImport = null
                                importError = null
                            }
                            .onFailure { err ->
                                importError = err.message ?: "Failed to save profile."
                                pendingImport = null
                            }
                    }
                ) {
                    Text("Activate Profile", color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text("Cancel", color = colors.textMid)
                }
            }
        )
    }

    // Safety Prompt for active streams
    if (profileChangePrompt != null) {
        val targetId = profileChangePrompt!!
        AlertDialog(
            onDismissRequest = { profileChangePrompt = null },
            containerColor = colors.surf1,
            title = { Text("Restart Active Stream?", color = colors.textHi) },
            text = {
                Text(
                    "Switching hardware profiles requires disconnecting the current dash session so new video and network parameters can take effect.",
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
                    Text("Disconnect & Apply", color = colors.alert, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { profileChangePrompt = null }) {
                    Text("Cancel", color = colors.textMid)
                }
            }
        )
    }
}

@Composable
private fun EmptyProfileHeroCard() {
    val colors = NavBeamTheme.colors
    NavBeamCard(glow = true, modifier = Modifier.fillMaxWidth(), padding = 20.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.primaryTint)
                ) {
                    Icon(
                        imageVector = NavBeamIcons.Dash,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "No Bike Display Added",
                        color = colors.textHi,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily
                    )
                    Text(
                        text = "Add a config file to get started",
                        color = colors.textMid,
                        fontSize = 14.sp,
                        fontFamily = GeistFamily
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            NavBeamChip(label = "NOT SET", tone = ChipTone.Neutral, dot = true)
        }
    }
}

@Composable
private fun ActiveProfileHeroCard(profile: DashProfile) {
    val colors = NavBeamTheme.colors
    val shapeLabel = when (profile.display.shape) {
        DisplayShape.CIRCLE -> "Circle"
        DisplayShape.RECTANGLE -> "Rectangle"
        null -> "Default"
    }
    val authLabel = when (profile.protocol.authType) {
        "RSA_AES_256" -> "RSA + AES-256"
        "NONE" -> "Open (None)"
        else -> profile.protocol.authType
    }

    NavBeamCard(glow = true, modifier = Modifier.fillMaxWidth(), padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.primaryTint)
                ) {
                    Icon(
                        imageVector = NavBeamIcons.Dash,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        color = colors.textHi,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(profile.id)
                            if (profile.firmwareVersion.isNotBlank()) {
                                append(" · FW ${profile.firmwareVersion}")
                            }
                        },
                        color = colors.textLo,
                        fontSize = 11.5.sp,
                        fontFamily = GeistMonoFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                NavBeamChip(label = "ACTIVE", tone = ChipTone.Primary, dot = true)
            }

            if (profile.description.isNotBlank()) {
                Text(
                    text = profile.description,
                    color = colors.textMid,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp
                )
            }

            // 2x2 Hardware Spec Grid
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SpecTile(
                    label = "DISPLAY",
                    value = "${profile.display.width}×${profile.display.height}",
                    sub = "$shapeLabel · ${profile.display.activeBitrateBps / 1000} kbps",
                    modifier = Modifier.weight(1f)
                )
                SpecTile(
                    label = "NETWORK",
                    value = profile.network.gatewayIp,
                    sub = "SSID ${profile.network.defaultSsidPrefix}*",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SpecTile(
                    label = "PROTOCOL",
                    value = authLabel,
                    sub = "Magic ${profile.protocol.magic} · v${profile.version}",
                    modifier = Modifier.weight(1f)
                )
                SpecTile(
                    label = "CONTROLS",
                    value = "${profile.maneuvers.size} icons",
                    sub = "${profile.inputs.size} button actions",
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "UDP TX :${profile.network.controlTxPort}  ·  RX :${profile.network.controlRxPort}  ·  RTP :${profile.network.videoRtpPort}",
                color = colors.textLo,
                fontSize = 11.sp,
                fontFamily = GeistMonoFamily
            )
        }
    }
}

@Composable
private fun SpecTile(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier,
) {
    val colors = NavBeamTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surf2)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            color = colors.textLo,
            fontSize = 10.sp,
            fontFamily = GeistMonoFamily,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp
        )
        Text(
            text = value,
            color = colors.textHi,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        Text(
            text = sub,
            color = colors.textMid,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

@Composable
private fun VerifySpecLine(label: String, value: String) {
    val colors = NavBeamTheme.colors
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            color = colors.textLo,
            fontSize = 11.5.sp,
            fontFamily = GeistFamily
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = colors.textHi,
            fontSize = 11.5.sp,
            fontFamily = GeistMonoFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun queryUriMetadata(ctx: Context, uri: Uri): Pair<String?, Long?> {
    return runCatching {
        ctx.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null to null
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
            name to size
        } ?: (null to null)
    }.getOrDefault(null to null)
}
