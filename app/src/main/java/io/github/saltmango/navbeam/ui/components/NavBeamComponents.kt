package io.github.saltmango.navbeam.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.saltmango.navbeam.R
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.theme.*
import java.util.Currency
import java.util.Locale

// ---- Card ----
@Composable
fun NavBeamCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    padding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = NavBeamTheme.colors
    val shape = NavBeamTheme.shapes.card
    val border = if (glow) {
        Modifier.border(1.dp, colors.primaryTint2, shape)
    } else {
        Modifier.border(1.dp, colors.line, shape)
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surf1)
            .then(border)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

// ---- Section Card ----
@Composable
fun NavBeamSection(
    title: String,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = NavBeamTheme.colors
    NavBeamCard(modifier = modifier.fillMaxWidth(), padding = 0.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surf2)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            NavBeamEyebrow(title)
        }
        NavBeamDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content,
        )
    }
}

// ---- Buttons ----
enum class BtnVariant { Primary, Secondary, Ghost, Danger }
enum class BtnSize { Lg, Md, Sm }

@Composable
fun NavBeamButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    variant: BtnVariant = BtnVariant.Primary,
    size: BtnSize = BtnSize.Md,
    enabled: Boolean = true,
) {
    val colors = NavBeamTheme.colors
    val shape = NavBeamTheme.shapes.button
    val (bgColor, fgColor, borderColor, hasShadow) = when (variant) {
        BtnVariant.Primary   -> listOf(colors.primary, colors.onPrimary, Color.Transparent, true)
        BtnVariant.Secondary -> listOf(colors.surf2, colors.textHi, colors.line2, false)
        BtnVariant.Ghost     -> listOf(Color.Transparent, colors.textMid, colors.line2, false)
        BtnVariant.Danger    -> listOf(Color.Transparent, colors.alert, colors.alert.copy(alpha = 0.4f), false)
    }
    val height = when (size) {
        BtnSize.Lg -> 58.dp
        BtnSize.Md -> 50.dp
        BtnSize.Sm -> 40.dp
    }
    val fontSize = when (size) {
        BtnSize.Lg -> 16.sp
        BtnSize.Md -> 15.sp
        BtnSize.Sm -> 13.5.sp
    }
    val iconSize = if (size == BtnSize.Sm) 17.dp else 20.dp

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(height)
            .clip(shape)
            .then(if (hasShadow == true && enabled) Modifier.shadow(8.dp, shape, ambientColor = colors.primaryGlow, spotColor = colors.primaryGlow) else Modifier)
            .background((bgColor as Color).let { if (enabled) it else it.copy(alpha = 0.4f) })
            .then(
                if (borderColor != Color.Transparent) Modifier.border(1.dp, borderColor as Color, shape)
                else Modifier
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fgColor as Color, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = label,
            color = (fgColor as Color).let { if (enabled) it else it.copy(alpha = 0.5f) },
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NavBeamIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    active: Boolean = false,
    tint: Color? = null,
    contentDescription: String? = null,
) {
    val colors = NavBeamTheme.colors
    val shape = NavBeamTheme.shapes.iconButton
    val bg = if (active) colors.primaryTint else colors.surf2
    val bd = if (active) colors.primaryTint2 else colors.line2
    val iconTint = tint ?: if (active) colors.primary else colors.textMid

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(bg)
            .border(1.dp, bd, shape)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = iconTint, modifier = Modifier.size(20.dp))
    }
}

// ---- Chip & Badge ----
enum class ChipTone { Primary, Success, Warn, Alert, Off, Neutral }

@Composable
fun NavBeamChip(
    label: String,
    tone: ChipTone = ChipTone.Neutral,
    dot: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val colors = NavBeamTheme.colors
    val shape = NavBeamTheme.shapes.chip
    val (bg, fg, bd) = when (tone) {
        ChipTone.Primary -> Triple(colors.primaryTint, colors.primary, colors.primaryTint2)
        ChipTone.Success -> Triple(colors.successTint, colors.success, colors.success.copy(alpha = 0.35f))
        ChipTone.Warn    -> Triple(colors.warnTint, colors.warn, colors.warn.copy(alpha = 0.3f))
        ChipTone.Alert   -> Triple(colors.alertTint, colors.alert, colors.alert.copy(alpha = 0.32f))
        ChipTone.Off     -> Triple(colors.surf1, colors.textLo, colors.line)
        ChipTone.Neutral -> Triple(colors.surf2, colors.textMid, colors.line2)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .height(30.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, bd, shape)
            .padding(horizontal = 12.dp),
    ) {
        if (dot) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        }
        Text(
            text = label,
            color = fg,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistFamily,
            maxLines = 1,
        )
    }
}

@Composable
fun NavBeamBadge(
    label: String,
    tone: ChipTone = ChipTone.Primary,
    modifier: Modifier = Modifier,
) {
    val colors = NavBeamTheme.colors
    val (bg, fg) = when (tone) {
        ChipTone.Primary -> colors.primaryTint to colors.primary
        ChipTone.Success -> colors.successTint to colors.success
        ChipTone.Warn    -> colors.warnTint to colors.warn
        ChipTone.Alert   -> colors.alertTint to colors.alert
        ChipTone.Off     -> colors.surf2 to colors.textLo
        ChipTone.Neutral -> colors.surf2 to colors.textMid
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = fg,
            style = NavBeamTheme.typography.badge,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp,
        )
    }
}

// ---- Toggle ----
@Composable
fun NavBeamToggle(
    on: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    val colors = NavBeamTheme.colors
    val track = if (on) colors.primary else colors.surf3
    val bd    = if (on) colors.primary else colors.line3
    val knob  = if (on) colors.onPrimary else colors.textMid
    val knobOffset = if (on) 22.dp else 2.5.dp

    Box(
        modifier = modifier
            .width(50.dp)
            .height(30.dp)
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
                .background(knob),
        )
    }
}

// ---- Segmented control ----
@Composable
fun NavBeamSegmentedControl(
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
                    .background(if (active) colors.primaryTint else Color.Transparent)
                    .clickable { onSelect(opt) },
            ) {
                Text(
                    text = opt,
                    color = if (active) colors.primary else colors.textLo,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---- Divider ----
@Composable
fun NavBeamDivider(modifier: Modifier = Modifier) {
    val colors = NavBeamTheme.colors
    Box(modifier.fillMaxWidth().height(1.dp).background(colors.line))
}

// ---- Eyebrow label ----
@Composable
fun NavBeamEyebrow(text: String, modifier: Modifier = Modifier) {
    val colors = NavBeamTheme.colors
    Text(
        text = text,
        color = colors.textLo,
        style = NavBeamTheme.typography.eyebrow,
        modifier = modifier,
    )
}

// ---- Progress Bar ----
@Composable
fun NavBeamProgressBar(
    progress: Float? = null,
    modifier: Modifier = Modifier,
    color: Color = NavBeamTheme.colors.primary,
    height: Dp = 4.dp,
) {
    val colors = NavBeamTheme.colors
    val corner = height / 2
    if (progress != null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(corner))
                .background(colors.line),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(corner))
                    .background(color),
            )
        }
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "indeterminate-progress")
        val progressShift by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "progress-shift",
        )
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(corner))
                .background(colors.line),
        ) {
            val width = maxWidth
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(width * 0.38f)
                    .offset(x = width * 0.62f * progressShift)
                    .clip(RoundedCornerShape(corner))
                    .background(color),
            )
        }
    }
}

// ---- Stat / Metric Tile ----
@Composable
fun NavBeamStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = NavBeamTheme.colors.textHi,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    compact: Boolean = false,
) {
    val colors = NavBeamTheme.colors
    Column(
        horizontalAlignment = horizontalAlignment,
        modifier = modifier,
    ) {
        Text(
            text = value,
            color = valueColor,
            fontSize = if (compact) 14.sp else 20.sp,
            fontWeight = if (compact) FontWeight.SemiBold else FontWeight.Bold,
            fontFamily = GeistMonoFamily,
        )
        Text(
            text = label,
            color = colors.textLo,
            fontSize = if (compact) 11.sp else 11.5.sp,
            fontFamily = GeistFamily,
        )
    }
}

@Composable
fun NavBeamMetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = NavBeamTheme.colors.textHi,
    emphasize: Boolean = false,
) {
    val colors = NavBeamTheme.colors
    Column(
        modifier = modifier
            .clip(NavBeamTheme.shapes.tile)
            .background(colors.surf1)
            .border(1.dp, colors.line, NavBeamTheme.shapes.tile)
            .padding(13.dp),
    ) {
        Text(
            text = value,
            color = valueColor,
            fontSize = if (emphasize) 18.sp else 16.sp,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        NavBeamEyebrow(label, Modifier.padding(top = 4.dp))
    }
}

// ---- Quick Setting Tile ----
@Composable
fun NavBeamQuickTile(
    icon: ImageVector,
    label: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NavBeamTheme.colors
    NavBeamCard(
        modifier = modifier,
        padding = 14.dp,
        onClick = onClick,
    ) {
        Column {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) colors.primaryTint else colors.surf2),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (active) colors.primary else colors.textLo,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                color = colors.textLo,
                fontSize = 11.sp,
                fontFamily = GeistFamily,
                letterSpacing = 0.2.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                color = colors.textHi,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- Search Bar ----
@Composable
fun NavBeamSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    placeholder: String = "Search…",
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    val colors = NavBeamTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(NavBeamTheme.shapes.button)
            .background(colors.surf1)
            .border(1.dp, colors.line2, NavBeamTheme.shapes.button)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(NavBeamIcons.Search, contentDescription = null, tint = colors.textMid, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = colors.textLo,
                        style = NavBeamTheme.typography.body,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = NavBeamTheme.typography.body.copy(color = colors.textHi),
                    cursorBrush = SolidColor(colors.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = NavBeamIcons.X,
                    contentDescription = "Clear",
                    tint = colors.textLo,
                    modifier = Modifier
                        .size(17.dp)
                        .clickable(onClick = onClear),
                )
            }
        }
    }
}

// ---- Empty State ----
@Composable
fun NavBeamEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    padding: Dp = 24.dp,
) {
    val colors = NavBeamTheme.colors
    NavBeamCard(modifier = modifier.fillMaxWidth(), padding = padding) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(colors.surf2),
            ) {
                Icon(icon, contentDescription = null, tint = colors.textLo, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                color = colors.textHi,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistFamily,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = colors.textLo,
                style = NavBeamTheme.typography.bodySecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

// ---- List row ----
enum class IconTone { Neutral, Primary, Success, Warn, Alert }

@Composable
fun NavBeamRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTone: IconTone = IconTone.Neutral,
    sub: String? = null,
    right: String? = null,
    rightSub: String? = null,
    accentRight: Boolean = false,
    trailingIcon: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = NavBeamTheme.colors
    val (iconBg, iconFg) = when (iconTone) {
        IconTone.Primary -> colors.primaryTint to colors.primary
        IconTone.Success -> colors.successTint to colors.success
        IconTone.Alert   -> colors.alertTint to colors.alert
        IconTone.Warn    -> colors.warnTint to colors.warn
        IconTone.Neutral -> colors.surf2 to colors.textMid
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 13.dp, horizontal = 6.dp),
    ) {
        if (icon != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(NavBeamTheme.shapes.iconBadge)
                    .background(iconBg)
                    .border(1.dp, colors.line, NavBeamTheme.shapes.iconBadge),
            ) {
                Icon(icon, contentDescription = null, tint = iconFg, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textHi,
                style = NavBeamTheme.typography.cardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    text = sub,
                    color = colors.textLo,
                    fontSize = 12.5.sp,
                    fontFamily = GeistFamily,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (right != null || rightSub != null) {
            Column(horizontalAlignment = Alignment.End) {
                if (right != null) {
                    Text(
                        text = right,
                        color = if (accentRight) colors.primary else colors.textHi,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = GeistMonoFamily,
                    )
                }
                if (rightSub != null) {
                    Text(rightSub, color = colors.textLo, fontSize = 11.5.sp, fontFamily = GeistFamily)
                }
            }
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(8.dp))
            trailingContent()
        } else if (trailingIcon || (onClick != null && right == null)) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = colors.textLo,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---- Screen header ----
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavBeamScreenHeader(
    modifier: Modifier = Modifier,
    title: String? = null,
    eyebrow: String? = null,
    hint: String? = null,
    wordmark: Boolean = false,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = NavBeamTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                if (wordmark) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                            contentDescription = "NavBeam Logo",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(24.dp),
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
                        if (eyebrow != null) NavBeamEyebrow(eyebrow, Modifier.padding(bottom = 1.dp))
                        if (title != null) {
                            Text(
                                text = title,
                                color = colors.textHi,
                                style = NavBeamTheme.typography.screenTitle,
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                if (onBack != null) {
                    NavBeamIconButton(
                        icon = Icons.Outlined.ChevronLeft,
                        onClick = onBack,
                        modifier = Modifier.padding(end = 8.dp),
                        contentDescription = "Back",
                    )
                }
            },
            actions = {
                if (trailing != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        trailing()
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.surf1,
                titleContentColor = colors.textHi,
                navigationIconContentColor = colors.textHi,
                actionIconContentColor = colors.textHi,
            ),
        )
        if (hint != null) {
            Text(
                text = hint,
                color = colors.textLo,
                fontSize = 12.sp,
                fontFamily = GeistFamily,
                lineHeight = 15.sp,
                modifier = Modifier.padding(
                    start = if (onBack != null) 60.dp else 16.dp,
                    top = 2.dp,
                    end = 16.dp,
                    bottom = 6.dp,
                ),
            )
        }
        // Subtle downward drop shadow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(colors.shadow, Color.Transparent),
                    ),
                ),
        )
    }
}

fun getCurrencySymbol(): String = try {
    Currency.getInstance(Locale.getDefault()).symbol
} catch (_: Exception) {
    "₹"
}

@Composable
fun NumField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    decimal: Boolean,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
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
    modifier: Modifier = Modifier,
) {
    val wheelColors = listOf(
        Color(0xFFFF0000),
        Color(0xFFFFFF00),
        Color(0xFF00FF00),
        Color(0xFF00FFFF),
        Color(0xFF0000FF),
        Color(0xFFFF00FF),
        Color(0xFFFF0000),
    )

    val sweepShader = remember { Brush.sweepGradient(wheelColors) }
    var wheelSize by remember { mutableStateOf(IntSize.Zero) }

    val hsv = remember(selectedColorHex) {
        val parsed = try {
            android.graphics.Color.parseColor(selectedColorHex)
        } catch (_: Exception) {
            android.graphics.Color.BLUE
        }
        FloatArray(3).also { android.graphics.Color.colorToHSV(parsed, it) }
    }

    val hue = hsv[0]
    val saturation = hsv[1]
    val currentOnColorSelected by rememberUpdatedState(onColorSelected)

    fun updateColorAt(offset: Offset) {
        val centerX = wheelSize.width / 2f
        val centerY = wheelSize.height / 2f
        val dx = offset.x - centerX
        val dy = offset.y - centerY
        val radius = minOf(centerX, centerY)
        if (radius > 0) {
            val dist = kotlin.math.hypot(dx, dy)
            var angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (angle < 0) angle += 360f
            val sat = (dist / radius).coerceIn(0f, 1f)
            val colorInt = android.graphics.Color.HSVToColor(floatArrayOf(angle, sat, 1f))
            currentOnColorSelected(String.format("#%06X", 0xFFFFFF and colorInt))
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { updateColorAt(it) },
                    onDrag = { change, _ ->
                        change.consume()
                        updateColorAt(change.position)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { updateColorAt(it) }
            }
            .onGloballyPositioned { wheelSize = it.size },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val centerOffset = Offset(size.width / 2f, size.height / 2f)

            drawCircle(brush = sweepShader, radius = radius, center = centerOffset)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    center = centerOffset,
                    radius = radius,
                ),
                radius = radius,
                center = centerOffset,
            )

            val angleRad = Math.toRadians(hue.toDouble())
            val handleRadius = radius * saturation
            val handleX = centerOffset.x + (handleRadius * kotlin.math.cos(angleRad)).toFloat()
            val handleY = centerOffset.y + (handleRadius * kotlin.math.sin(angleRad)).toFloat()

            drawCircle(
                color = Color.Black,
                radius = 12.dp.toPx(),
                center = Offset(handleX, handleY),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = Color.White,
                radius = 10.dp.toPx(),
                center = Offset(handleX, handleY),
                style = Stroke(width = 2.dp.toPx()),
            )
            val selectedColor = try {
                Color(android.graphics.Color.parseColor(selectedColorHex))
            } catch (_: Exception) {
                Color.Blue
            }
            drawCircle(
                color = selectedColor,
                radius = 8.dp.toPx(),
                center = Offset(handleX, handleY),
            )
        }
    }
}

@Composable
fun ColorPickerDialog(
    initialColorHex: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = NavBeamTheme.colors
    var tempColor by remember { mutableStateOf(initialColorHex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Custom Route Color",
                color = colors.textHi,
                style = NavBeamTheme.typography.cardTitle,
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ColorWheelPicker(
                    selectedColorHex = tempColor,
                    onColorSelected = { tempColor = it },
                    modifier = Modifier.size(160.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    NavBeamEyebrow("HEX Code")
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, colors.line3, RoundedCornerShape(8.dp))
                            .background(colors.surf2)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        BasicTextField(
                            value = tempColor,
                            onValueChange = { tempColor = it },
                            textStyle = NavBeamTheme.typography.mono.copy(color = colors.textHi),
                            singleLine = true,
                            cursorBrush = SolidColor(colors.primary),
                        )
                    }

                    val previewColor = remember(tempColor) {
                        try {
                            Color(android.graphics.Color.parseColor(tempColor))
                        } catch (_: Exception) {
                            colors.offline
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(previewColor)
                            .border(1.5.dp, colors.line3, RoundedCornerShape(8.dp)),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(tempColor) }) {
                Text("OK", color = colors.primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid)
            }
        },
        containerColor = colors.surf1,
    )
}

@Preview(showBackground = true)
@Composable
private fun NavBeamComponentsLightPreview() {
    NavBeamTheme(mode = io.github.saltmango.navbeam.viewmodel.ThemeMode.Light) {
        Column(
            modifier = Modifier.background(NavBeamTheme.colors.bg1).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NavBeamSection("Preview Section") {
                NavBeamRow(
                    title = "Connected Dash",
                    sub = "240×240 Circular TFT",
                    icon = NavBeamIcons.Dash,
                    iconTone = IconTone.Primary,
                    trailingContent = { NavBeamToggle(on = true, onChange = {}) },
                )
            }
            NavBeamSegmentedControl(
                options = listOf("Light", "Dark", "System"),
                selected = "Light",
                onSelect = {},
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavBeamChip("LIVE", tone = ChipTone.Primary, dot = true)
                NavBeamChip("Ready", tone = ChipTone.Success, dot = true)
            }
            NavBeamButton("Continue", onClick = {}, modifier = Modifier.fillMaxWidth())
        }
    }
}
