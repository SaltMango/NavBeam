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
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val primaryTint: Color,
    val primaryTint2: Color,
    val primaryGlow: Color,
    val onPrimary: Color,
    val secondary: Color,
    val secondaryDark: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val secondaryTint: Color,
    val secondaryTint2: Color,
    val onSecondary: Color,
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
    val dashAccent: Color,
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
    primary = PrimaryDark,
    primaryBright = PrimaryBrightDark,
    primaryDeep = PrimaryDeepDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    primaryTint = PrimaryTintDark,
    primaryTint2 = PrimaryTint2Dark,
    primaryGlow = PrimaryGlowDark,
    onPrimary = MountainDuskDark,
    secondary = NavBeamAmber,
    secondaryDark = NavBeamAmberDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    secondaryTint = SecondaryTintDark,
    secondaryTint2 = SecondaryTint2Dark,
    onSecondary = OnSecondary,
    success = SuccessDark,
    successTint = SuccessTintDark,
    warn = WarnDark,
    warnTint = WarnTintDark,
    alert = AlertDark,
    alertTint = AlertTintDark,
    offline = OfflineDark,
    scrim = ScrimDark,
    shadow = ShadowDark,
    heroTop = HeroTopDark,
    heroBottom = HeroBottomDark,
    routeFallback = FallbackRoute,
    dashAccent = PrimaryDark,
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
    primary = MountainDuskLight,
    primaryBright = PrimaryBrightLight,
    primaryDeep = MountainDuskDark,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    primaryTint = PrimaryTintLight,
    primaryTint2 = PrimaryTint2Light,
    primaryGlow = PrimaryGlowLight,
    onPrimary = OnPrimary,
    secondary = SecondaryLight,
    secondaryDark = NavBeamAmberDark,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    secondaryTint = SecondaryTintLight,
    secondaryTint2 = SecondaryTint2Light,
    onSecondary = OnSecondaryLight,
    success = SuccessLight,
    successTint = SuccessTintLight,
    warn = WarnLight,
    warnTint = WarnTintLight,
    alert = AlertLight,
    alertTint = AlertTintLight,
    offline = OfflineLight,
    scrim = ScrimLight,
    shadow = ShadowLight,
    heroTop = HeroTopLight,
    heroBottom = HeroBottomLight,
    routeFallback = FallbackRoute,
    dashAccent = PrimaryDark,
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
    primary = PrimaryDark,
    onPrimary = MountainDuskDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = NavBeamAmber,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    background = BackgroundDark,
    onBackground = TextHiDark,
    surface = SurfaceDark,
    onSurface = TextHiDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextMidDark,
    outline = OutlineDark,
    outlineVariant = LineDark,
    error = AlertDark,
    onError = MountainDuskDark,
    scrim = ScrimDark,
)

private val LightColorScheme = lightColorScheme(
    primary = MountainDuskLight,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    background = BackgroundLight,
    onBackground = TextHiLight,
    surface = SurfaceLight,
    onSurface = TextHiLight,
    surfaceVariant = Surf2Light,
    onSurfaceVariant = TextMidLight,
    outline = Line2Light,
    outlineVariant = LineLight,
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
