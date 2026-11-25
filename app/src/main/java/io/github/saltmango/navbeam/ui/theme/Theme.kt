package io.github.saltmango.navbeam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import androidx.core.view.WindowCompat
import io.github.saltmango.navbeam.viewmodel.ThemeMode

data class NavBeamColors(
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
)

val LocalNavBeamColors = staticCompositionLocalOf {
    NavBeamColors(
        bg0 = Bg0Dark, bg1 = Bg1Dark, surf1 = Surf1Dark, surf2 = Surf2Dark, surf3 = Surf3Dark,
        line = LineDark, line2 = Line2Dark, line3 = Line3Dark,
        textHi = TextHiDark, textMid = TextMidDark, textLo = TextLoDark, textDis = TextDisDark
    )
}

object NavBeamTheme {
    val colors: NavBeamColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNavBeamColors.current
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
    scrim = Bg0Dark,
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
    error = Alert,
    onError = Color.White,
    scrim = Bg0Light,
)

@Composable
fun NavBeamTheme(
    mode: ThemeMode = ThemeMode.Auto,
    content: @Composable () -> Unit
) {
    val darkTheme = when (mode) {
        ThemeMode.On -> true
        ThemeMode.Off -> false
        ThemeMode.Auto -> isSystemInDarkTheme()
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val navBeamColors = if (darkTheme) {
        NavBeamColors(
            bg0 = Bg0Dark, bg1 = Bg1Dark, surf1 = Surf1Dark, surf2 = Surf2Dark, surf3 = Surf3Dark,
            line = LineDark, line2 = Line2Dark, line3 = Line3Dark,
            textHi = TextHiDark, textMid = TextMidDark, textLo = TextLoDark, textDis = TextDisDark
        )
    } else {
        NavBeamColors(
            bg0 = Bg0Light, bg1 = Bg1Light, surf1 = Surf1Light, surf2 = Surf2Light, surf3 = Surf3Light,
            line = LineLight, line2 = Line2Light, line3 = Line3Light,
            textHi = TextHiLight, textMid = TextMidLight, textLo = TextLoLight, textDis = TextDisLight
        )
    }

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

    CompositionLocalProvider(LocalNavBeamColors provides navBeamColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
