package io.github.saltmango.navbeam.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.saltmango.navbeam.R
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.theme.*
import io.github.saltmango.navbeam.viewmodel.ThemeMode

@Composable
fun LoginScreen(
    onContinue: () -> Unit,
    themeMode: ThemeMode = ThemeMode.System,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    val colors = NavBeamTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colorStops = arrayOf(0f to colors.heroTop, 0.65f to colors.heroBottom),
                    center = Offset(0.5f, 0f),
                    radius = Float.MAX_VALUE,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.75f))

            // Brand & Hero
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_navbeam_brand),
                    contentDescription = "NavBeam Brand Icon",
                    modifier = Modifier.size(124.dp),
                )

                Spacer(Modifier.height(22.dp))

                Text(
                    text = "NavBeam",
                    color = colors.textHi,
                    style = NavBeamTheme.typography.brand,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Keep your screen off and your phone in your pocket.",
                    color = colors.textMid,
                    fontSize = 13.5.sp,
                    fontFamily = GeistFamily,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.weight(0.85f))

            // Compact Theme Selector centered slightly above midpoint between top text and bottom button
            CompactThemeSelector(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
            )

            Spacer(Modifier.weight(1.15f))

            // Rider Onboarding CTA
            RiderGetStartedButton(
                onClick = onContinue,
                modifier = Modifier.padding(bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun CompactThemeSelector(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NavBeamTheme.colors
    val options = listOf(
        ThemeMode.Light to "Light",
        ThemeMode.Dark to "Dark",
        ThemeMode.System to "System",
    )
    Row(
        modifier = modifier
            .width(210.dp)
            .clip(CircleShape)
            .background(colors.surf1)
            .border(1.dp, colors.line, CircleShape)
            .padding(2.5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (mode, label) ->
            val active = mode == themeMode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            if (colors.isDark) colors.primaryTint2 else colors.primaryTint
                        } else {
                            Color.Transparent
                        },
                    )
                    .then(
                        if (active) {
                            Modifier.border(
                                width = 1.dp,
                                color = colors.primary.copy(alpha = if (colors.isDark) 0.4f else 0.25f),
                                shape = CircleShape,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onThemeModeChange(mode) },
            ) {
                Text(
                    text = label,
                    color = if (active) {
                        if (colors.isDark) colors.textHi else colors.primary
                    } else {
                        colors.textLo
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Rider-focused ignition / starter button.
 * Styled after motorcycle cockpit controls and digital dash start triggers:
 * - Rich Mountain Dusk cockpit gradient with crisp specular top rim
 * - Motorcycle dial badge with two-wheeler icon
 * - Monospace cockpit typography
 * - Dynamic throttle speed chevrons & diagonal hash marks
 * - Mechanical spring press compression & tactile haptic feedback
 */
@Composable
fun RiderGetStartedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NavBeamTheme.colors
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "rider-button-scale",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "throttle-chevrons")
    val chevronOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "chevron-offset",
    )

    val gradientStops = if (colors.isDark) {
        listOf(
            Color(0xFF1A476E),
            Color(0xFF235B8C),
            Color(0xFF2C70AA),
        )
    } else {
        listOf(
            colors.primaryDeep,
            colors.primary,
            colors.primaryBright,
        )
    }
    val contentColor = Color.White

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(gradientStops))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        contentColor.copy(alpha = if (colors.isDark) 0.3f else 0.4f),
                        contentColor.copy(alpha = 0.08f),
                        colors.shadow,
                    ),
                ),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))) {
            val strokeWidth = 1.5.dp.toPx()
            val streakColor = contentColor.copy(alpha = 0.1f)
            val width = size.width
            val height = size.height
            for (i in 0..3) {
                val startX = width - (i * 12.dp.toPx()) - 36.dp.toPx()
                drawLine(
                    color = streakColor,
                    start = Offset(startX, 0f),
                    end = Offset(startX - 16.dp.toPx(), height),
                    strokeWidth = strokeWidth,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.18f))
                    .border(1.dp, contentColor.copy(alpha = 0.24f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NavBeamIcons.Motor,
                    contentDescription = "Motorcycle Icon",
                    tint = contentColor,
                    modifier = Modifier.size(23.dp),
                )
            }

            Text(
                text = "GET STARTED",
                color = contentColor,
                fontFamily = GeistMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.5.sp,
                letterSpacing = 1.2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .width(40.dp)
                    .offset(x = chevronOffset.dp),
            ) {
                Icon(
                    imageVector = NavBeamIcons.ChevronRight,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.45f),
                    modifier = Modifier.size(19.dp),
                )
                Icon(
                    imageVector = NavBeamIcons.ChevronRight,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.95f),
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenDarkPreview() {
    NavBeamTheme(mode = ThemeMode.Dark) {
        LoginScreen(onContinue = {}, themeMode = ThemeMode.Dark)
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenLightPreview() {
    NavBeamTheme(mode = ThemeMode.Light) {
        LoginScreen(onContinue = {}, themeMode = ThemeMode.Light)
    }
}
