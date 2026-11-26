package io.github.saltmango.navbeam.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.saltmango.navbeam.dash.profile.DashProfile
import io.github.saltmango.navbeam.dash.profile.DashProfileJsonCodec
import io.github.saltmango.navbeam.dash.profile.DashProfileManager
import io.github.saltmango.navbeam.dash.profile.DisplayShape
import io.github.saltmango.navbeam.dash.profile.ProfileFileImporter
import io.github.saltmango.navbeam.data.AuthPrefs
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.theme.*
import io.github.saltmango.navbeam.util.AppDispatchers
import io.github.saltmango.navbeam.viewmodel.ConnStage
import io.github.saltmango.navbeam.viewmodel.DashViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingModalProfileImport(
    val profile: DashProfile,
    val sourceLabel: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoadProfileModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    dashViewModel: DashViewModel? = null,
    onActivated: (DashProfile) -> Unit = {},
) {
    val ctx = LocalContext.current
    val colors = NavBeamTheme.colors
    val scope = rememberCoroutineScope()

    var isImporting by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<PendingModalProfileImport?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pastedJson by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }

    val openDocumentMimeTypes = remember { arrayOf("*/*") }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImporting = true
        importError = null
        scope.launch {
            val outcome = withContext(AppDispatchers.networking) {
                ProfileFileImporter.importFromUri(ctx.contentResolver, uri)
            }
            isImporting = false
            outcome.onSuccess { imported ->
                importError = null
                pendingImport = PendingModalProfileImport(
                    profile = imported.profile,
                    sourceLabel = imported.sourceLabel,
                )
            }.onFailure { err ->
                importError = err.message ?: "Failed to parse profile JSON."
            }
        }
    }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = {
                importError = null
                onDismiss()
            },
            containerColor = colors.surf1,
            dragHandle = { BottomSheetDefaults.DragHandle(color = colors.line) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Load Display Profile",
                            color = colors.textHi,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GeistFamily,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Upload a .navbeam or .json config file, or paste the raw JSON for your display model.",
                            color = colors.textMid,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontFamily = GeistFamily,
                        )
                    }
                    if (isImporting) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(
                            color = colors.primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
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
                            .padding(12.dp),
                    ) {
                        Icon(
                            imageVector = NavBeamIcons.ErrorOutline,
                            contentDescription = null,
                            tint = colors.alert,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Invalid Profile",
                                color = colors.alert,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = GeistFamily,
                            )
                            Text(
                                text = importError.orEmpty(),
                                color = colors.textHi,
                                fontSize = 12.sp,
                                lineHeight = 16.5.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        IconButton(
                            onClick = { importError = null },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = NavBeamIcons.X,
                                contentDescription = "Dismiss error",
                                tint = colors.textMid,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    NavBeamButton(
                        label = if (isImporting) "Reading…" else "Upload Profile File",
                        onClick = { importLauncher.launch(openDocumentMimeTypes) },
                        icon = NavBeamIcons.UploadFile,
                        variant = BtnVariant.Primary,
                        size = BtnSize.Lg,
                        enabled = !isImporting,
                        modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

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
                    fontFamily = GeistFamily,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Paste your profile JSON below:",
                        color = colors.textLo,
                        fontSize = 12.sp,
                    )

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
                                fontSize = 11.5.sp,
                            )
                        },
                        textStyle = TextStyle(
                            color = colors.textHi,
                            fontFamily = GeistMonoFamily,
                            fontSize = 11.5.sp,
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
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (pasteError != null) {
                        Text(
                            text = pasteError.orEmpty(),
                            color = colors.alert,
                            fontSize = 11.5.sp,
                            lineHeight = 15.5.sp,
                        )
                    }
                }
            },
            confirmButton = {
                if (pastedJson.isBlank()) {
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
                        Text("Paste", color = colors.primary, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    TextButton(
                        onClick = {
                            val raw = pastedJson
                            scope.launch {
                                val result = withContext(AppDispatchers.networking) {
                                    DashProfileJsonCodec.fromJson(raw)
                                }
                                result.onSuccess { parsed ->
                                    pasteError = null
                                    showPasteDialog = false
                                    pastedJson = ""
                                    pendingImport = PendingModalProfileImport(
                                        profile = parsed,
                                        sourceLabel = "Pasted JSON",
                                    )
                                }.onFailure { err ->
                                    pasteError = err.message ?: "Invalid profile JSON."
                                }
                            }
                        }
                    ) {
                        Text("Load", color = colors.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text("Cancel", color = colors.textMid)
                }
            },
        )
    }

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
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Verify Profile",
                        color = colors.textHi,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily,
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
                        overflow = TextOverflow.Ellipsis,
                    )
                    NavBeamCard(modifier = Modifier.fillMaxWidth(), padding = 12.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = candidate.name,
                                color = colors.textHi,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GeistFamily,
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
                                fontFamily = GeistMonoFamily,
                            )
                            if (candidate.description.isNotBlank()) {
                                Text(
                                    text = candidate.description,
                                    color = colors.textMid,
                                    fontSize = 12.sp,
                                )
                            }
                            NavBeamDivider(Modifier.padding(vertical = 4.dp))
                            ModalVerifySpecLine(
                                label = "Display",
                                value = "${candidate.display.width}×${candidate.display.height} ($shapeLabel) · ${candidate.display.activeBitrateBps / 1000} kbps",
                            )
                            ModalVerifySpecLine(
                                label = "Wi-Fi",
                                value = "${candidate.network.defaultSsidPrefix}* → ${candidate.network.gatewayIp}",
                            )
                            ModalVerifySpecLine(
                                label = "UDP Ports",
                                value = "TX :${candidate.network.controlTxPort} · RX :${candidate.network.controlRxPort} · RTP :${candidate.network.videoRtpPort}",
                            )
                            ModalVerifySpecLine(
                                label = "Protocol",
                                value = "${candidate.protocol.magic} · ${candidate.protocol.authType}",
                            )
                            ModalVerifySpecLine(
                                label = "Controls",
                                value = "${candidate.maneuvers.size} turn icons · ${candidate.inputs.size} buttons",
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.primaryTint)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = NavBeamIcons.Check,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Profile checks passed — ready to use.",
                            color = colors.primary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 15.sp,
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
                            .onSuccess { activated ->
                                AuthPrefs.setOnboarded(ctx, true)
                                AuthPrefs.setProfileSkipped(ctx, false)
                                Toast.makeText(ctx, "Profile '${activated.name}' activated", Toast.LENGTH_SHORT).show()
                                pendingImport = null
                                importError = null
                                onDismiss()
                                onActivated(activated)
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
            },
        )
    }
}

@Composable
private fun ModalVerifySpecLine(label: String, value: String) {
    val colors = NavBeamTheme.colors
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = colors.textLo,
            fontSize = 11.5.sp,
            fontFamily = GeistFamily,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = colors.textHi,
            fontSize = 11.5.sp,
            fontFamily = GeistMonoFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
