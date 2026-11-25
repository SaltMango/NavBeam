package io.github.saltmango.navbeam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.theme.*
import androidx.compose.ui.res.painterResource
import io.github.saltmango.navbeam.R
import java.util.Currency
import java.util.Locale

// ---- Shape constants ----
val CardShape    = RoundedCornerShape(18.dp)
val BtnShape     = RoundedCornerShape(14.dp)
val InputShape   = RoundedCornerShape(12.dp)
val ChipShape    = CircleShape
val IconBtnShape = RoundedCornerShape(14.dp)

// ---- Card ----
@Composable
fun navBeamCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    padding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = NavBeamTheme.colors
    val border = if (glow)
        Modifier.border(1.dp, PrimaryTint2, CardShape)
    else
        Modifier.border(1.dp, colors.line, CardShape)

    Column(
        modifier = modifier
            .clip(CardShape)
            .background(colors.surf1)
            .then(border)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

// ---- Buttons ----
enum class BtnVariant { Primary, Secondary, Ghost, Danger }
enum class BtnSize { Lg, Md, Sm }

@Composable
fun navBeamBtn(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    variant: BtnVariant = BtnVariant.Primary,
    size: BtnSize = BtnSize.Md,
    enabled: Boolean = true,
) {
    val colors = NavBeamTheme.colors
    val (bgColor, fgColor, borderColor, hasShadow) = when (variant) {
        BtnVariant.Primary   -> listOf(Primary, OnPrimary, Color.Transparent, true)
        BtnVariant.Secondary -> listOf(colors.surf2, colors.textHi, colors.line2, false)
        BtnVariant.Ghost     -> listOf(Color.Transparent, colors.textMid, colors.line2, false)
        BtnVariant.Danger    -> listOf(Color.Transparent, Alert, Alert.copy(alpha = 0.4f), false)
    }
    val height = when (size) { BtnSize.Lg -> 58.dp; BtnSize.Md -> 50.dp; BtnSize.Sm -> 40.dp }
    val fontSize = when (size) { BtnSize.Lg -> 16.sp; BtnSize.Md -> 15.sp; BtnSize.Sm -> 13.5.sp }
    val iconSize = when (size) { BtnSize.Sm -> 17.dp; else -> 20.dp }

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(height)
            .clip(BtnShape)
            .background(bgColor as Color)
            .border(1.dp, borderColor as Color, BtnShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fgColor as Color,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = label,
            color = fgColor as Color,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistFamily,
            letterSpacing = (-0.15).sp,
            maxLines = 1,
        )
    }
}

// ---- Icon button (square rounded) ----
@Composable
fun navBeamIconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    active: Boolean = false,
    tint: Color? = null,
) {
    val colors = NavBeamTheme.colors
    val bg = if (active) PrimaryTint else colors.surf2
    val bd = if (active) PrimaryTint2 else colors.line2
    val iconTint = tint ?: if (active) Primary else colors.textMid

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(IconBtnShape)
            .background(bg)
            .border(1.dp, bd, IconBtnShape)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
    }
}

// ---- Chip ----
enum class ChipTone { Primary, Warn, Alert, Off, Neutral }

@Composable
fun navBeamChip(
    label: String,
    tone: ChipTone = ChipTone.Neutral,
    dot: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val colors = NavBeamTheme.colors
    val (bg, fg, bd) = when (tone) {
        ChipTone.Primary -> Triple(PrimaryTint, Primary, PrimaryTint2)
        ChipTone.Warn    -> Triple(Warn.copy(alpha = 0.13f), Warn, Warn.copy(alpha = 0.3f))
        ChipTone.Alert   -> Triple(Alert.copy(alpha = 0.13f), Alert, Alert.copy(alpha = 0.32f))
        ChipTone.Off     -> Triple(colors.surf1, colors.textLo, colors.line)
        ChipTone.Neutral -> Triple(colors.surf2, colors.textMid, colors.line2)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .height(30.dp)
            .clip(ChipShape)
            .background(bg)
            .border(1.dp, bd, ChipShape)
            .padding(horizontal = 12.dp),
    ) {
        if (dot) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        }
        Text(
            label, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = GeistFamily, maxLines = 1,
        )
    }
}

// ---- Toggle ----
@Composable
fun navBeamToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = NavBeamTheme.colors
    val track = if (on) Primary else colors.surf3
    val bd    = if (on) Primary else colors.line3
    val knob  = if (on) OnPrimary else colors.textMid
    val knobOffset = if (on) 22.dp else 2.5.dp

    Box(
        modifier = Modifier
            .width(50.dp).height(30.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, bd, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChange(!on) },
    ) {
        Box(
            Modifier
                .padding(start = knobOffset, top = 2.5.dp)
                .size(23.dp)
                .clip(CircleShape)
                .background(knob)
        )
    }
}

// ---- Segmented control ----
@Composable
fun navBeamSegmented(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NavBeamTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surf1)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { opt ->
            val active = opt == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) PrimaryTint else Color.Transparent)
                    .clickable { onSelect(opt) },
            ) {
                Text(
                    opt, color = if (active) Primary else colors.textLo,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                )
            }
        }
    }
}

// ---- Divider ----
@Composable
fun navBeamDivider(modifier: Modifier = Modifier) {
    val colors = NavBeamTheme.colors
    Box(modifier.fillMaxWidth().height(1.dp).background(colors.line))
}

// ---- Eyebrow label ----
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    val colors = NavBeamTheme.colors
    Text(
        text = text,
        color = colors.textLo,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp,
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

// ---- List row ----
enum class IconTone { Neutral, Primary, Warn, Alert }

@Composable
fun navBeamRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTone: IconTone = IconTone.Neutral,
    sub: String? = null,
    right: String? = null,
    rightSub: String? = null,
    accentRight: Boolean = false,
    trailingIcon: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = NavBeamTheme.colors
    val (iconBg, iconFg) = when (iconTone) {
        IconTone.Primary -> PrimaryTint to Primary
        IconTone.Alert  -> Alert.copy(alpha = 0.13f) to Alert
        IconTone.Warn   -> Warn.copy(alpha = 0.13f) to Warn
        IconTone.Neutral -> colors.surf2 to colors.textMid
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp, horizontal = 4.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (icon != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBg)
                    .border(1.dp, colors.line, RoundedCornerShape(12.dp)),
            ) {
                Icon(icon, contentDescription = null, tint = iconFg, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, color = colors.textHi, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = GeistFamily, letterSpacing = (-0.15).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(sub, color = colors.textLo, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (right != null || rightSub != null) {
            Column(horizontalAlignment = Alignment.End) {
                if (right != null) {
                    Text(
                        right,
                        color = if (accentRight) Primary else colors.textHi,
                        fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
                        fontFamily = GeistMonoFamily,
                    )
                }
                if (rightSub != null) {
                    Text(rightSub, color = colors.textLo, fontSize = 11.5.sp)
                }
            }
        }
        if (trailingIcon || (onClick != null && right == null)) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null, tint = colors.textLo, modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---- Screen scaffold (scrollable body) ----
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenHeader(
    modifier: Modifier = Modifier,
    title: String? = null,
    eyebrow: String? = null,
    hint: String? = null,
    wordmark: Boolean = false,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = NavBeamTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        TopAppBar(
            title = {
                if (wordmark) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                            contentDescription = "NavBeam Logo",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "NavBeam",
                            color = colors.textHi,
                            fontFamily = GeistMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 21.sp,
                            letterSpacing = 0.14.sp,
                        )
                    }
                } else {
                    Column {
                        if (eyebrow != null) Eyebrow(eyebrow, Modifier.padding(bottom = 1.dp))
                        if (title != null) {
                            Text(
                                text = title,
                                color = colors.textHi,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GeistFamily,
                                letterSpacing = (-0.42).sp,
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                if (onBack != null) {
                    navBeamIconBtn(
                        icon = Icons.Outlined.ChevronLeft,
                        onClick = onBack,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            },
            actions = {
                if (trailing != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        trailing()
                    }
                }
            },
            modifier = Modifier,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.surf1,
                titleContentColor = colors.textHi,
                navigationIconContentColor = colors.textHi,
                actionIconContentColor = colors.textHi,
            )
        )
        if (hint != null) {
            Text(
                hint, color = colors.textLo, fontSize = 12.sp, fontFamily = GeistFamily,
                lineHeight = 15.sp, modifier = Modifier.padding(start = if (onBack != null) 60.dp else 16.dp, top = 2.dp, end = 16.dp, bottom = 6.dp),
            )
        }
        // Downward drop shadow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

fun getCurrencySymbol(): String = try {
    Currency.getInstance(Locale.getDefault()).symbol
} catch (e: Exception) {
    "₹"
}

@Composable
fun NumField(value: String, onChange: (String) -> Unit, label: String, decimal: Boolean, modifier: Modifier = Modifier, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    )
}


@Composable
fun ColorWheelPicker(
    selectedColorHex: String,
    onColorSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = listOf(
        Color(0xFFFF0000), // Red
        Color(0xFFFFFF00), // Yellow
        Color(0xFF00FF00), // Green
        Color(0xFF00FFFF), // Cyan
        Color(0xFF0000FF), // Blue
        Color(0xFFFF00FF), // Magenta
        Color(0xFFFF0000)  // Red
    )

    val sweepShader = remember {
        Brush.sweepGradient(colors)
    }

    var wheelSize by remember { mutableStateOf(IntSize.Zero) }

    // Parse current selected color to HSV to place the selection handle
    val hsv = remember(selectedColorHex) {
        val parsed = try {
            android.graphics.Color.parseColor(selectedColorHex)
        } catch (e: Exception) {
            android.graphics.Color.BLUE
        }
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV(parsed, arr)
        arr
    }

    val hue = hsv[0]
    val saturation = hsv[1]

    val currentOnColorSelected by rememberUpdatedState(onColorSelected)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val centerX = wheelSize.width / 2f
                        val centerY = wheelSize.height / 2f
                        val dx = offset.x - centerX
                        val dy = offset.y - centerY
                        val radius = minOf(centerX, centerY)
                        if (radius > 0) {
                            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            var angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            if (angle < 0) angle += 360f
                            val sat = (dist / radius).coerceIn(0f, 1f)
                            val colorInt = android.graphics.Color.HSVToColor(floatArrayOf(angle, sat, 1f))
                            val newHex = String.format("#%06X", 0xFFFFFF and colorInt)
                            currentOnColorSelected(newHex)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val position = change.position
                        val centerX = wheelSize.width / 2f
                        val centerY = wheelSize.height / 2f
                        val dx = position.x - centerX
                        val dy = position.y - centerY
                        val radius = minOf(centerX, centerY)
                        if (radius > 0) {
                            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            var angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            if (angle < 0) angle += 360f
                            val sat = (dist / radius).coerceIn(0f, 1f)
                            val colorInt = android.graphics.Color.HSVToColor(floatArrayOf(angle, sat, 1f))
                            val newHex = String.format("#%06X", 0xFFFFFF and colorInt)
                            currentOnColorSelected(newHex)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val centerX = wheelSize.width / 2f
                    val centerY = wheelSize.height / 2f
                    val dx = offset.x - centerX
                    val dy = offset.y - centerY
                    val radius = minOf(centerX, centerY)
                    if (radius > 0) {
                        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        var angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        if (angle < 0) angle += 360f
                        val sat = (dist / radius).coerceIn(0f, 1f)
                        val colorInt = android.graphics.Color.HSVToColor(floatArrayOf(angle, sat, 1f))
                        val newHex = String.format("#%06X", 0xFFFFFF and colorInt)
                        currentOnColorSelected(newHex)
                    }
                }
            }
            .onGloballyPositioned { coordinates ->
                wheelSize = coordinates.size
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val centerOffset = Offset(size.width / 2f, size.height / 2f)

            // 1. Draw the hue wheel using sweep gradient
            drawCircle(
                brush = sweepShader,
                radius = radius,
                center = centerOffset
            )

            // 2. Overlay radial gradient for saturation (white in center, fading outwards)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    center = centerOffset,
                    radius = radius
                ),
                radius = radius,
                center = centerOffset
            )

            // 3. Draw picker handle (indicator circle)
            val angleRad = Math.toRadians(hue.toDouble())
            val handleRadius = radius * saturation
            val handleX = centerOffset.x + (handleRadius * Math.cos(angleRad)).toFloat()
            val handleY = centerOffset.y + (handleRadius * Math.sin(angleRad)).toFloat()

            // Outer ring of indicator
            drawCircle(
                color = Color.Black,
                radius = 12.dp.toPx(),
                center = Offset(handleX, handleY),
                style = Stroke(width = 2.dp.toPx())
            )
            // Inner ring of indicator
            drawCircle(
                color = Color.White,
                radius = 10.dp.toPx(),
                center = Offset(handleX, handleY),
                style = Stroke(width = 2.dp.toPx())
            )
            // Solid fill matching the selected color
            drawCircle(
                color = Color(android.graphics.Color.parseColor(selectedColorHex)),
                radius = 8.dp.toPx(),
                center = Offset(handleX, handleY)
            )
        }
    }
}

@Composable
fun ColorPickerDialog(
    initialColorHex: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NavBeamTheme.colors
    var tempColor by remember { mutableStateOf(initialColorHex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Custom Route Color",
                color = colors.textHi,
                fontSize = 16.sp,
                fontFamily = GeistFamily,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Color Wheel
                ColorWheelPicker(
                    selectedColorHex = tempColor,
                    onColorSelected = { tempColor = it },
                    modifier = Modifier.size(160.dp)
                )

                // HEX input text field
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Eyebrow("HEX Code")
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, colors.line3, RoundedCornerShape(8.dp))
                            .background(colors.surf2)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = tempColor,
                            onValueChange = { tempColor = it },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = colors.textHi,
                                fontSize = 13.sp,
                                fontFamily = GeistMonoFamily
                            ),
                            singleLine = true,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Primary)
                        )
                    }

                    // Color Preview Box
                    val previewColor = remember(tempColor) {
                        try {
                            Color(android.graphics.Color.parseColor(tempColor))
                        } catch (e: Exception) {
                            Color.Gray
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(previewColor)
                            .border(1.5.dp, colors.line3, RoundedCornerShape(8.dp))
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(tempColor) }
            ) {
                Text("OK", color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel", color = colors.textMid)
            }
        },
        containerColor = colors.surf1
    )
}
