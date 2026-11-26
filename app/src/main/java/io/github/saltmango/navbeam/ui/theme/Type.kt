package io.github.saltmango.navbeam.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import io.github.saltmango.navbeam.R

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

val GeistFamily = FontFamily(
    Font(googleFont = GoogleFont("Geist"), fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Geist"), fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Geist"), fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Geist"), fontProvider = provider, weight = FontWeight.Bold),
)

val GeistMonoFamily = FontFamily(
    Font(googleFont = GoogleFont("Geist Mono"), fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Geist Mono"), fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Geist Mono"), fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Geist Mono"), fontProvider = provider, weight = FontWeight.Bold),
)

@Immutable
data class NavBeamTypography(
    val brand: TextStyle = TextStyle(
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = 0.16.sp,
    ),
    val display: TextStyle = TextStyle(
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 38.sp,
    ),
    val screenTitle: TextStyle = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        letterSpacing = (-0.42).sp,
    ),
    val sectionTitle: TextStyle = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = (-0.3).sp,
    ),
    val cardTitle: TextStyle = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.5.sp,
        letterSpacing = (-0.15).sp,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    val bodySecondary: TextStyle = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
    ),
    val caption: TextStyle = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
    ),
    val eyebrow: TextStyle = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp,
    ),
    val badge: TextStyle = TextStyle(
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 0.8.sp,
    ),
    val metricValue: TextStyle = TextStyle(
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
    ),
    val mono: TextStyle = TextStyle(
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    ),
    val monoSmall: TextStyle = TextStyle(
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.16.sp,
    ),
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 38.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = (-0.48).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        letterSpacing = (-0.38).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        letterSpacing = (-0.42).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = (-0.16).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.16.sp,
    ),
)
