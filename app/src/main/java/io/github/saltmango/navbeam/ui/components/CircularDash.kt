package io.github.saltmango.navbeam.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.saltmango.navbeam.ui.theme.*
import kotlin.math.*

/**
 * Circular Dash viewport — the signature element.
 * Mirrors the round TFT display.
 *
 * @param size      Diameter of the circle
 * @param pan       Map pan offset (±46 units)
 * @param zoom      Map zoom scale (0.7–2.4)
 * @param distance  Next turn distance string
 * @param street    Current street name
 * @param live      Show live ring pulse (connected state)
 * @param compact   Hide distance/street labels (for small embeds)
 */
@Composable
fun CircularDash(
    size: Dp,
    pan: Offset = Offset.Zero,
    zoom: Float = 1f,
    distance: String = "400 m",
    street: String = "Sample Street",
    live: Boolean = true,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = NavBeamTheme.colors
    // Live ring pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "live-ring")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 2.0f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "ring-scale",
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "ring-alpha",
    )

    val activeProfile by io.github.saltmango.navbeam.dash.profile.DashProfileManager.activeProfile.collectAsState()
    val isCircle = activeProfile.display.shape == io.github.saltmango.navbeam.dash.profile.DisplayShape.CIRCLE
    val bezelShape = if (isCircle) CircleShape else RoundedCornerShape(16.dp)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            // Outer bezel rings adapt to theme (machined dark steel in dark mode, silver-slate in light mode)
            .border(2.dp, colors.dashBezelOuter, bezelShape)
            .padding(2.dp)
            .border(6.dp, colors.dashBezelInner, bezelShape)
            .padding(6.dp)
            .clip(bezelShape)
            .background(
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to colors.dashBgCenter,
                        0.7f to colors.dashBgMid,
                        1f to colors.dashBgEdge,
                    ),
                ),
            ),
    ) {
        // Map layer with pan/zoom
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = pan.x
                    translationY = pan.y
                    scaleX = zoom
                    scaleY = zoom
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
        ) {
            drawMapContents(colors)
        }

        // Tick ring overlay
        Canvas(Modifier.fillMaxSize()) {
            drawTickRing(colors)
        }

        // Live ring pulse (behind the rider marker)
        if (live) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = colors.dashAccent.copy(alpha = ringAlpha * 0.5f),
                    radius = this.size.minDimension * 0.12f * ringScale,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // Rider marker (centre, fixed) — 26dp canvas
        Canvas(Modifier.size(26.dp)) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val r9 = 9.dp.toPx()
            drawCircle(color = colors.dashBgMid, radius = r9, center = Offset(cx, cy))
            drawCircle(color = colors.dashAccent, radius = r9, center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
            val path = Path().apply {
                moveTo(cx, 2.dp.toPx())
                lineTo(this@Canvas.size.width - 2.dp.toPx(), this@Canvas.size.height - 2.dp.toPx())
                lineTo(cx, this@Canvas.size.height - 5.dp.toPx())
                lineTo(2.dp.toPx(), this@Canvas.size.height - 2.dp.toPx())
                close()
            }
            drawPath(path, colors.dashAccent)
        }

        // N compass
        Text(
            text = "N",
            color = NavBeamAmber,
            fontFamily = GeistMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.05f).sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = (size.value * 0.085f).dp),
        )

        // Non-compact overlays: next-turn banner + street label
        if (!compact) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = (size.value * 0.17f).dp)
                    .clip(CircleShape)
                    .background(colors.dashBannerBg)
                    .border(1.dp, colors.dashAccent.copy(alpha = 0.35f), CircleShape)
                    .padding(horizontal = 13.dp, vertical = 5.dp),
            ) {
                Text(
                    text = distance,
                    color = colors.dashBannerText,
                    fontFamily = GeistMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.062f).sp,
                )
            }

            Text(
                text = street,
                color = colors.dashBannerText,
                fontFamily = GeistFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.052f).sp,
                letterSpacing = (-0.1).sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = (size.value * 0.13f).dp)
                    .widthIn(max = size * 0.74f),
            )
        }

        // Glass highlight
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.White.copy(alpha = 0.07f),
                        0.42f to Color.Transparent,
                    ),
                    center = Offset(size.toPx() * 0.3f, size.toPx() * 0.08f),
                    radius = size.toPx() * 0.6f,
                ),
            )
        }
    }
}

private fun DrawScope.drawMapContents(colors: NavBeamColors) {
    val w = size.width
    val h = size.height

    val terrainPath = Path().apply {
        moveTo(0f, h * 0.75f)
        cubicTo(w * 0.2f, h * 0.675f, w * 0.35f, h * 0.825f, w * 0.55f, h * 0.75f)
        cubicTo(w * 0.75f, h * 0.69f, w * 0.875f, h * 0.79f, w, h * 0.75f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(terrainPath, colors.dashTerrain)

    val roadPaint = Paint().apply {
        color = colors.dashRoad
        strokeWidth = 5f
        strokeCap = StrokeCap.Round
        style = PaintingStyle.Stroke
    }
    drawContext.canvas.drawPath(Path().apply {
        moveTo(w * 0.1f, h * 0.2f)
        cubicTo(w * 0.3f, h * 0.35f, w * 0.3f, h * 0.6f, w * 0.15f, h * 0.85f)
    }, roadPaint)
    drawContext.canvas.drawPath(Path().apply {
        moveTo(w * 0.9f, h * 0.15f)
        cubicTo(w * 0.75f, h * 0.35f, w * 0.825f, h * 0.6f, w * 0.75f, h * 0.9f)
    }, roadPaint)
    drawContext.canvas.drawPath(Path().apply {
        moveTo(w * 0.05f, h * 0.55f)
        lineTo(w * 0.4f, h * 0.5f)
        lineTo(w * 0.75f, h * 0.56f)
        lineTo(w, h * 0.5f)
    }, roadPaint)

    val routePaint = Paint().apply {
        color = colors.dashAccent
        strokeWidth = 7f
        strokeCap = StrokeCap.Round
        strokeJoin = StrokeJoin.Round
        style = PaintingStyle.Stroke
    }
    val routePath = Path().apply {
        moveTo(w * 0.5f, h * 0.875f)
        cubicTo(w * 0.5f, h * 0.75f, w * 0.35f, h * 0.7f, w * 0.36f, h * 0.55f)
        cubicTo(w * 0.37f, h * 0.41f, w * 0.55f, h * 0.39f, w * 0.56f, h * 0.26f)
        cubicTo(w * 0.565f, h * 0.17f, w * 0.5f, h * 0.13f, w * 0.5f, h * 0.06f)
    }
    drawContext.canvas.drawPath(routePath, routePaint)

    drawContext.canvas.drawPath(routePath, Paint().apply {
        color = colors.dashRouteDash
        strokeWidth = 1.6f
        strokeCap = StrokeCap.Round
        style = PaintingStyle.Stroke
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 7f))
    })
}

private fun DrawScope.drawTickRing(colors: NavBeamColors) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    for (i in 0 until 60) {
        val angle = (i.toFloat() / 60f) * 2f * PI.toFloat() - PI.toFloat() / 2f
        val major = i % 5 == 0
        val r1 = (if (major) 0.9f else 0.93f) * (size.width / 2f)
        val r2 = 0.96f * (size.width / 2f)
        drawLine(
            color = if (major) colors.dashAccent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.13f),
            start = Offset(cx + cos(angle) * r1, cy + sin(angle) * r1),
            end = Offset(cx + cos(angle) * r2, cy + sin(angle) * r2),
            strokeWidth = if (major) 1.4f else 0.8f,
            cap = StrokeCap.Butt,
        )
    }
}
