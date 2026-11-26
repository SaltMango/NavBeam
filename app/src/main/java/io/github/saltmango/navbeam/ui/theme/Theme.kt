package io.github.saltmango.navbeam.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.saltmango.navbeam.viewmodel.ThemeMode

@Immutable
data class NavBeamColors(
    val isDark: Boolean,
    val bg0: Color,
    val bg1: Color,
    val surf1: Color,
    val surf2: Color,
    val surf3: Color,
    val line: Color,
    val line2: Color,
    val line3: Color,
    val textHi: Color,
    val textMid: Color,
    val textLo: Color,
    val textDis: Color,
    val primary: Color,
    val primaryBright: Color,
    val primaryDeep: Color,
    val primaryCrimson: Color,
    val primaryWarm: Color,
    val primaryTint: Color,
    val primaryTint2: Color,
    val primaryGlow: Color,
    val onPrimary: Color,
    val success: Color,
    val successTint: Color,
    val warn: Color,
    val warnTint: Color,
    val alert: Color,
    val alertTint: Color,
    val offline: Color,
    val scrim: Color,
    val shadow: Color,
    val heroTop: Color,
    val heroBottom: Color,
    val routeFallback: Color,
    val dashBezelOuter: Color,
    val dashBezelInner: Color,
    val dashBgCenter: Color,
    val dashBgMid: Color,
    val dashBgEdge: Color,
    val dashTerrain: Color,
    val dashRoad: Color,
    val dashBannerBg: Color,
    val dashBannerText: Color,
    val dashRouteDash: Color,
)

@Immutable
data class NavBeamShapes(
    val card: Shape = RoundedCornerShape(18.dp),
    val button: Shape = RoundedCornerShape(14.dp),
    val input: Shape = RoundedCornerShape(12.dp),
    val chip: Shape = CircleShape,
    val iconButton: Shape = RoundedCornerShape(14.dp),
    val tile: Shape = RoundedCornerShape(14.dp),
    val iconBadge: Shape = RoundedCornerShape(11.dp),
    val pill: Shape = CircleShape,
)

@Immutable
data class NavBeamSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 22.dp,
    val xxl: Dp = 32.dp,
)

private val DarkNavBeamColors = NavBeamColors(
    isDark = true,
    bg0 = Bg0Dark,
    bg1 = Bg1Dark,
    surf1 = Surf1Dark,
    surf2 = Surf2Dark,
    surf3 = Surf3Dark,
    line = LineDark,
    line2 = Line2Dark,
    line3 = Line3Dark,
    textHi = TextHiDark,
    textMid = TextMidDark,
    textLo = TextLoDark,
    textDis = TextDisDark,
    primary = Primary,
    primaryBright = PrimaryBright,
    primaryDeep = PrimaryDeep,
    primaryCrimson = PrimaryCrimson,
    primaryWarm = PrimaryWarm,
    primaryTint = PrimaryTint,
    primaryTint2 = PrimaryTint2,
    primaryGlow = PrimaryGlow,
    onPrimary = OnPrimary,
    success = SuccessDark,
    successTint = SuccessTintDark,
    warn = Warn,
    warnTint = WarnTintDark,
    alert = Alert,
    alertTint = AlertTintDark,
    offline = Offline,
    scrim = ScrimDark,
    shadow = ShadowDark,
    heroTop = HeroTopDark,
    heroBottom = Bg1Dark,
    routeFallback = FallbackRoute,
    dashBezelOuter = DashBezelOuterDark,
    dashBezelInner = DashBezelInnerDark,
    dashBgCenter = DashBgCenter,
    dashBgMid = DashBgMid,
    dashBgEdge = DashBgEdge,
    dashTerrain = DashTerrain,
    dashRoad = DashRoad,
    dashBannerBg = DashBannerBg,
    dashBannerText = DashBannerText,
    dashRouteDash = DashRouteDash,
)

private val LightNavBeamColors = NavBeamColors(
    isDark = false,
    bg0 = Bg0Light,
    bg1 = Bg1Light,
    surf1 = Surf1Light,
    surf2 = Surf2Light,
    surf3 = Surf3Light,
    line = LineLight,
    line2 = Line2Light,
    line3 = Line3Light,
    textHi = TextHiLight,
    textMid = TextMidLight,
    textLo = TextLoLight,
    textDis = TextDisLight,
    primary = Primary,
    primaryBright = PrimaryBright,
    primaryDeep = PrimaryDeep,
    primaryCrimson = PrimaryCrimson,
    primaryWarm = PrimaryWarm,
    primaryTint = PrimaryTint,
    primaryTint2 = PrimaryTint2,
    primaryGlow = PrimaryGlow,
    onPrimary = OnPrimary,
    success = SuccessLight,
    successTint = SuccessTintLight,
    warn = WarnLight,
    warnTint = WarnTintLight,
    alert = AlertLight,
    alertTint = AlertTintLight,
    offline = Offline,
    scrim = ScrimLight,
    shadow = ShadowLight,
    heroTop = HeroTopLight,
    heroBottom = HeroBottomLight,
    routeFallback = FallbackRoute,
    dashBezelOuter = DashBezelOuterLight,
    dashBezelInner = DashBezelInnerLight,
    dashBgCenter = DashBgCenter,
    dashBgMid = DashBgMid,
    dashBgEdge = DashBgEdge,
    dashTerrain = DashTerrain,
    dashRoad = DashRoad,
    dashBannerBg = DashBannerBg,
    dashBannerText = DashBannerText,
    dashRouteDash = DashRouteDash,
)

val LocalNavBeamColors = staticCompositionLocalOf { DarkNavBeamColors }
val LocalNavBeamTypography = staticCompositionLocalOf { NavBeamTypography() }
val LocalNavBeamShapes = staticCompositionLocalOf { NavBeamShapes() }
val LocalNavBeamSpacing = staticCompositionLocalOf { NavBeamSpacing() }

object NavBeamTheme {
    val colors: NavBeamColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNavBeamColors.current

    val typography: NavBeamTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalNavBeamTypography.current

    val shapes: NavBeamShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalNavBeamShapes.current

    val spacing: NavBeamSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalNavBeamSpacing.current
}

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryTint,
    onPrimaryContainer = Primary,
    secondary = TextMidDark,
    onSecondary = Bg1Dark,
    background = Bg1Dark,
    onBackground = TextHiDark,
    surface = Surf1Dark,
    onSurface = TextHiDark,
    surfaceVariant = Surf2Dark,
    onSurfaceVariant = TextMidDark,
    outline = Line2Dark,
    error = Alert,
    onError = TextHiDark,
    scrim = ScrimDark,
)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryTint,
    onPrimaryContainer = Primary,
    secondary = TextMidLight,
    onSecondary = Bg1Light,
    background = Bg1Light,
    onBackground = TextHiLight,
    surface = Surf1Light,
    onSurface = TextHiLight,
    surfaceVariant = Surf2Light,
    onSurfaceVariant = TextMidLight,
    outline = Line2Light,
    error = AlertLight,
    onError = Color.White,
    scrim = ScrimLight,
)

@Composable
fun NavBeamTheme(
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val navBeamColors = if (darkTheme) DarkNavBeamColors else LightNavBeamColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.Transparent.toArgb()
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalNavBeamColors provides navBeamColors,
        LocalNavBeamTypography provides NavBeamTypography(),
        LocalNavBeamShapes provides NavBeamShapes(),
        LocalNavBeamSpacing provides NavBeamSpacing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
