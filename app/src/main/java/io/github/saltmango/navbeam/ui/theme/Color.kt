package io.github.saltmango.navbeam.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// NavBeam Color System — OKLCH Perceptual Harmony & WCAG 2.1 AA/AAA Contrast
// Primary Brand:   Mountain Dusk (OKLCH H ≈ 245°)
// Secondary Brand: NavBeam Amber (OKLCH H ≈ 60°–66°, ~180° perceptual complement)
// ============================================================================

// ---- Mountain Dusk (Primary Brand Ramp, OKLCH H ≈ 245°) ----
val MountainDuskLight       = Color(0xFF1E3548) // L=0.319, C=0.045, H=244.8° (Light Primary; 12.67:1 on White)
val MountainDuskMid         = Color(0xFF162837) // L=0.269, C=0.037, H=244.9°
val MountainDuskDark        = Color(0xFF0E1B26) // L=0.216, C=0.028, H=245.2° (Dark Surface & Dark onPrimary)
val PrimaryBrightLight      = Color(0xFF2B4B66) // L=0.400, C=0.056, H=244.8° (9.13:1 on White)
val PrimaryContainerLight   = Color(0xFFD3E3F0) // L=0.908, C=0.025, H=242.4°
val OnPrimaryContainerLight = Color(0xFF0E1B26) // 13.31:1 on PrimaryContainerLight

// Luminous Dark-Mode Primary Tonal Ladder (OKLCH H = 244°, AA/AAA on #0E1B26 & #1A2D3E)
val PrimaryDark             = Color(0xFF75B5E9) // L=0.750, C=0.100, H=244.0° (7.93:1 on Surf1Dark, 6.41:1 on Surf2Dark)
val PrimaryBrightDark       = Color(0xFF9ACCEF) // L=0.830, C=0.082, H=244.0° (10.19:1 on #0E1B26)
val PrimaryDeepDark         = Color(0xFF5698CE) // L=0.660, C=0.105, H=244.0° (5.63:1 on #0E1B26)
val PrimaryContainerDark    = MountainDuskLight // #1E3548
val OnPrimaryContainerDark  = PrimaryContainerLight // #D3E3F0 (9.66:1 on #1E3548)

val PrimaryTintDark         = Color(0x2675B5E9) // 15% PrimaryDark
val PrimaryTint2Dark        = Color(0x4075B5E9) // 25% PrimaryDark
val PrimaryGlowDark         = Color(0x5975B5E9) // 35% PrimaryDark

val PrimaryTintLight        = Color(0x1C1E3548) // 11% MountainDuskLight
val PrimaryTint2Light       = Color(0x301E3548) // 19% MountainDuskLight
val PrimaryGlowLight        = Color(0x471E3548) // 28% MountainDuskLight

// ---- NavBeam Amber (Secondary / Accent, OKLCH H ≈ 60°–66°) ----
val NavBeamAmber             = Color(0xFFFFA63D) // L=0.797, C=0.155, H=66.0° (8.95:1 on Surf1Dark)
val NavBeamAmberDark         = Color(0xFFE08219) // L=0.694, C=0.155, H=60.7°
val OnSecondary              = Color(0xFF1A1100) // Warm obsidian (9.58:1 on NavBeamAmber)
val SecondaryContainerDark   = Color(0xFF4A2C00)
val OnSecondaryContainerDark = Color(0xFFFFDCA8) // 9.72:1 on SecondaryContainerDark

val SecondaryLight           = Color(0xFF9A5100) // L=0.510, C=0.125, H=59.0° (5.91:1 on White, 5.05:1 on Surf2Light)
val OnSecondaryLight         = Color(0xFFFFFFFF)
val SecondaryContainerLight  = Color(0xFFFFE4C2)
val OnSecondaryContainerLight = Color(0xFF2A1600) // 14.12:1 on SecondaryContainerLight

val SecondaryTintDark        = Color(0x26FFA63D)
val SecondaryTint2Dark       = Color(0x40FFA63D)
val SecondaryTintLight       = Color(0x1C9A5100)
val SecondaryTint2Light      = Color(0x309A5100)

// ---- Surfaces & Neutrals (Dark Theme — Mountain Dusk Slate Ladder) ----
val Bg0Dark                 = Color(0xFF060C12) // L=0.151
val Bg1Dark                 = Color(0xFF081018) // BackgroundDark (L=0.169)
val BackgroundDark          = Bg1Dark
val Surf1Dark               = Color(0xFF11202E) // SurfaceDark (L=0.237, 1.16:1 over Bg1Dark)
val SurfaceDark             = Surf1Dark
val Surf2Dark               = Color(0xFF1A2D3E) // SurfaceVariantDark (L=0.289, 1.17:1 over Surf1Dark)
val SurfaceVariantDark      = Surf2Dark
val Surf3Dark               = Color(0xFF233A4F) // L=0.339 (1.20:1 over Surf2Dark)
val OutlineDark             = Color(0xFF2E475D) // L=0.388

// ---- Surfaces & Neutrals (Light Theme — Daytime Mist Slate Ladder) ----
val Bg0Light                = Color(0xFFECF1F6)
val Bg1Light                = Color(0xFFF4F7FA) // BackgroundLight (L=0.975)
val BackgroundLight         = Bg1Light
val Surf1Light              = Color(0xFFFFFFFF) // SurfaceLight (L=1.000)
val SurfaceLight            = Surf1Light
val Surf2Light              = Color(0xFFE7EEF5) // L=0.945
val Surf3Light              = Color(0xFFD9E4EF) // L=0.910

// ---- Outlines & Hairlines ----
val LineDark                = Color(0xFF253B50) // 1.43:1 hairline on Surf1Dark
val Line2Dark               = OutlineDark       // #2E475D
val Line3Dark               = Color(0xFF42607A)

val LineLight               = Color(0xFFD5E0EC)
val Line2Light              = Color(0xFFBACAD9)
val Line3Light              = Color(0xFF93AAC0)

// ---- Text / Content ----
val OnPrimary               = Color(0xFFFFFFFF)
val OnSurface               = Color(0xFFF1F5F9)
val OnSurfaceVariant        = Color(0xFF94A3B8)

val TextHiDark              = OnSurface         // #F1F5F9 (15.10:1 on Surf1Dark)
val TextMidDark             = OnSurfaceVariant  // #94A3B8 (6.45:1 on Surf1Dark, 5.50:1 on Surf2Dark)
val TextLoDark              = Color(0xFF7E95AC) // 5.34:1 on Surf1Dark, 4.56:1 on Surf2Dark (AA)
val TextDisDark             = Color(0xFF4B6278)

val TextHiLight             = MountainDuskDark  // #0E1B26 (17.45:1 on White)
val TextMidLight            = Color(0xFF33495E) // 9.31:1 on White, 7.96:1 on Surf2Light
val TextLoLight             = Color(0xFF52687D) // 5.78:1 on White, 4.94:1 on Surf2Light (AA)
val TextDisLight            = Color(0xFF8CA0B3)

// ---- Semantic Status (OKLCH-harmonized, AA+ on all surfaces & chips) ----
val SuccessDark             = Color(0xFF34D399) // 8.60:1 on Surf1Dark, 6.32:1 on 15% chip
val SuccessLight            = Color(0xFF0F6B35) // 6.61:1 on White, 5.62:1 on 11% chip
val SuccessTintDark         = Color(0x2634D399)
val SuccessTintLight        = Color(0x1C0F6B35)

val WarnDark                = NavBeamAmber      // #FFA63D (8.48:1 on Surf1Dark, 6.42:1 on 15% chip)
val WarnLight               = Color(0xFF9A5100) // 5.91:1 on White, 5.06:1 on 11% chip
val WarnTintDark            = Color(0x26FFA63D)
val WarnTintLight           = Color(0x1C9A5100)

val AlertDark               = Color(0xFFFB8A8A) // L=0.756, C=0.137, H=20.8° (7.17:1 on Surf1Dark, 5.57:1 on Surf1 chip, 4.76:1 on Surf2 chip)
val AlertLight              = Color(0xFFB91C1C) // 6.47:1 on White, 5.37:1 on 11% chip
val AlertTintDark           = Color(0x26FB8A8A)
val AlertTintLight          = Color(0x1CB91C1C)

val OfflineDark             = Color(0xFF647B90)
val OfflineLight            = Color(0xFF6B8094)

// ---- Overlays & Shadows ----
val ScrimDark               = Color(0xB3060C12)
val ScrimLight              = Color(0x730E1B26)
val ShadowDark              = Color(0x4D04090E)
val ShadowLight             = Color(0x1A0E1B26)

// ---- Hero Backdrops ----
val HeroTopDark             = MountainDuskLight // #1E3548
val HeroBottomDark          = BackgroundDark    // #0A131C
val HeroTopLight            = SurfaceLight      // #FFFFFF
val HeroBottomLight         = Color(0xFFE7EFF7)

// ---- Map & Dash Viewport ----
val MapRoute                = Color(0xFF4285F4)
val FallbackRoute           = Color(0xFFA040FF)
val DashBezelOuterDark      = OutlineDark       // #2E475D
val DashBezelInnerDark      = BackgroundDark    // #0A131C
val DashBezelOuterLight     = PrimaryContainerLight // #D3E3F0
val DashBezelInnerLight     = Color(0xFF8FA7BD)
val DashBgCenter            = MountainDuskMid   // #162837
val DashBgMid               = MountainDuskDark  // #0E1B26
val DashBgEdge              = Color(0xFF070E14)
val DashTerrain             = Color(0xFF12212F)
val DashRoad                = Color(0xFF22384C)
val DashBannerBg            = Color(0xD90A131C)
val DashBannerText          = OnSurface         // #F1F5F9
val DashRouteDash           = Color(0x99FFE6C4)
