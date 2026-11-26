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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.saltmango.navbeam.R
import io.github.saltmango.navbeam.ui.NavBeamIcons
import io.github.saltmango.navbeam.ui.components.NavBeamSegmentedControl
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
        ) {
            // Brand & Hero
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_navbeam_brand),
                    contentDescription = "NavBeam Brand Icon",
                    modifier = Modifier.size(140.dp),
                )

                Spacer(Modifier.height(30.dp))

                Text(
                    text = "NavBeam",
                    color = colors.textHi,
                    style = NavBeamTheme.typography.brand,
                )

                Spacer(Modifier.height(12.dp))

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

            // Rider Onboarding CTA & Theme Selector
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
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
                        onThemeModeChange(mode)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                RiderGetStartedButton(onClick = onContinue)
            }
        }
    }
}

/**
 * Rider-focused ignition / starter button.
 * Styled after motorcycle cockpit controls and digital dash start triggers:
 * - High-energy ignition gradient with crisp top highlight rim
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        colors.primaryDeep,
                        colors.primary,
                        colors.primaryBright,
                    ),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        colors.onPrimary.copy(alpha = 0.4f),
                        colors.onPrimary.copy(alpha = 0.1f),
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
            val streakColor = colors.onPrimary.copy(alpha = 0.1f)
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
                    .background(Color.Black.copy(alpha = 0.16f))
                    .border(1.dp, colors.onPrimary.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NavBeamIcons.Motor,
                    contentDescription = "Motorcycle Icon",
                    tint = colors.onPrimary,
                    modifier = Modifier.size(23.dp),
                )
            }

            Text(
                text = "GET STARTED",
                color = colors.onPrimary,
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
                    tint = colors.onPrimary.copy(alpha = 0.45f),
                    modifier = Modifier.size(19.dp),
                )
                Icon(
                    imageVector = NavBeamIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.onPrimary.copy(alpha = 0.95f),
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
