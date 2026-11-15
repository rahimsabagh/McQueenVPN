package com.v2ray.ang.ui.compose

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager

// =========================================================================
// MCQUEEN — Effortless Performance Design Tokens
// Inspired by precision automotive cockpits & anodized graphite hardware
// =========================================================================

// Core Brand & Surface Tokens (Preserved Spec Constants)
val McQueenGraphiteBlack = Color(0xFF111416)    // #111416 Spec Graphite Black
val McQueenDeepSurface = Color(0xFF191E22)      // #191E22 Spec Deep Surface
val McQueenElevatedSurface = Color(0xFF20272D)  // #20272D Spec Elevated Surface
val McQueenRacingRed = Color(0xFF941E29)        // #941E29 Spec Restrained Brand Accent
val McQueenTitaniumWhite = Color(0xFFD9DADD)    // #D9DADD Spec Titanium White
val McQueenSecondaryText = Color(0xFF929BA3)    // #929BA3 Spec Secondary Text

// Obsidian Mockup Surfaces & Borders (MCQUEEN VPN Redesign)
val McQueenObsidianBlack = Color(0xFF09090B)
val McQueenDialWellDark = Color(0xFF0B0B0E)
val McQueenCardSurfaceDark = Color(0xFF101014)
val McQueenCardConnectingDark = Color(0xFF15120B)
val McQueenCardConnectedDark = Color(0xFF160B0D)
val McQueenControlSurfaceDark = Color(0xFF131317)
val McQueenButtonSurfaceDark = Color(0xFF1B1B21)
val McQueenHairlineBorder = Color(0xFF23232A)
val McQueenControlBorderDark = Color(0xFF25252C)
val McQueenPillBorderDark = Color(0xFF2A2A31)
val McQueenSubtleDivider = Color(0xFF25252C)
val McQueenButtonBorderDark = Color(0xFF2F2F38)

// Text Hierarchy Tokens (Dark Mode)
val McQueenPrimaryInkDark = Color(0xFFF4F4F5)
val McQueenSecondaryInkDark = Color(0xFFD4D4D8)
val McQueenMutedInkDark = Color(0xFFA1A1AA)
val McQueenOverlineInkDark = Color(0xFF9CA3AF)
val McQueenInactiveTimerDark = Color(0xFF8B8B95)

val McQueenCrimsonReadable = Color(0xFFFF6A75)
val McQueenCrimsonLight = Color(0xFF941E29)

// Light Mode Companion Tokens
val McQueenLightBackground = Color(0xFFF4F4F6)
val McQueenLightSurface = Color(0xFFFFFFFF)
val McQueenLightElevatedSurface = Color(0xFFE9EAEE)
val McQueenLightMainText = Color(0xFF111416)
val McQueenLightSecondaryText = Color(0xFF525866)
val McQueenLightBorder = Color(0xFFD5D7DD)

// Semantic Status Accents — Disconnected (Gray) · Connecting (Yellow/Amber) · Connected (Red)
val McQueenMockupAmber = Color(0xFFFBBF24)
val McQueenMockupAmberText = Color(0xFFFCD34D)
val McQueenMockupAmberLight = Color(0xFF9E6B00)
val McQueenMockupRed = Color(0xFFEF3340)
val McQueenMockupRedSoft = Color(0xFFFF8A93)
val McQueenMockupRedIcon = Color(0xFFFF6A75)
val McQueenMockupEmerald = Color(0xFF34D399)
val McQueenMockupEmeraldLight = Color(0xFF059669)

val McQueenConnectedTeal = McQueenMockupEmerald
val McQueenConnectedLightTeal = McQueenMockupEmeraldLight
val McQueenConnectedRedDark = McQueenMockupRed
val McQueenConnectedRedLight = Color(0xFFC41622)
val McQueenChampionshipGold = McQueenMockupAmber
val McQueenChampionshipLightGold = McQueenMockupAmberLight
val McQueenWarningAmber = McQueenMockupAmber
val McQueenWarningLightAmber = McQueenMockupAmberLight
val McQueenErrorCoral = Color(0xFFFF6A75)
val McQueenErrorLight = Color(0xFFA6121C)

// Hardware & Dial Tokens (Disconnected Gray · Connecting Yellow · Connected Red)
val McQueenChassisDark = McQueenDialWellDark
val McQueenChassisLight = Color(0xFFE7E9EC)
val McQueenChassisRimDark = Color(0xFF24242B)
val McQueenChassisRimLight = Color(0xFFCFD3D7)

// Disconnected (Neutral Carbon Gray)
val McQueenDisconnectedPlateDarkTop = Color(0xFF24242B)
val McQueenDisconnectedPlateDarkBottom = Color(0xFF17171C)
val McQueenDisconnectedBorderDark = Color(0xFF33333C)
val McQueenDisconnectedTextDark = Color(0xFFF4F4F5)
val McQueenDisconnectedPlateLightTop = Color(0xFFE4E6EA)
val McQueenDisconnectedPlateLightBottom = Color(0xFFD0D4DA)
val McQueenDisconnectedBorderLight = Color(0xFFA8AEB5)
val McQueenDisconnectedTextLight = Color(0xFF16181A)

// Connecting (Vibrant Amber/Yellow with High-Contrast Carbon Text)
val McQueenConnectingPlateTop = Color(0xFFFCC934)
val McQueenConnectingPlateBottom = Color(0xFFE8A410)
val McQueenConnectingBorderDark = Color(0xFFFCD34D)
val McQueenConnectingBorderLight = Color(0xFFB87F00)
val McQueenConnectingLabelDark = Color(0xFF1C1300)

// Connected (Vibrant McQueen Racing Red with Crisp White Text)
val McQueenRacingRedBright = Color(0xFFEF3340)
val McQueenRacingRedDeep = Color(0xFFB8121D)
val McQueenRacingRedHighlight = Color(0xFFFF6A75)
val McQueenConnectedPlateDarkTop = Color(0xFFE32A37)
val McQueenConnectedPlateDarkBottom = Color(0xFFB8121D)
val McQueenConnectedPlateLightTop = Color(0xFFD61F2B)
val McQueenConnectedPlateLightBottom = Color(0xFFA2121B)
val McQueenConnectedPlateLightText = Color(0xFFFFFFFF)

// Compatibility aliases for existing references
val McQueenConnectingPlateDarkTop = McQueenConnectingPlateTop
val McQueenConnectingPlateDarkBottom = McQueenConnectingPlateBottom
val McQueenConnectingPlateLightTop = McQueenConnectingPlateTop
val McQueenConnectingPlateLightBottom = McQueenConnectingPlateBottom
val McQueenConnectingFlameLight = McQueenChampionshipLightGold
val McQueenFailedPlateDarkTop = McQueenDisconnectedPlateDarkTop
val McQueenFailedPlateDarkBottom = McQueenDisconnectedPlateDarkBottom
val McQueenFailedPlateLightTop = McQueenDisconnectedPlateLightTop
val McQueenFailedPlateLightBottom = McQueenDisconnectedPlateLightBottom
val McQueenFailedLightText = McQueenDisconnectedTextLight

// Bundled Local Typography (from app/src/main/res/font/)
val McQueenDisplayFont = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold),
)

val McQueenBodyFont = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold),
)

val McQueenMonoFont = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono, FontWeight.Bold),
)

private val McQueenDarkColorScheme = darkColorScheme(
    primary = McQueenMockupRed,
    onPrimary = Color.White,
    primaryContainer = McQueenButtonSurfaceDark,
    onPrimaryContainer = McQueenPrimaryInkDark,
    secondary = McQueenSecondaryInkDark,
    onSecondary = McQueenObsidianBlack,
    secondaryContainer = McQueenButtonSurfaceDark,
    onSecondaryContainer = McQueenPrimaryInkDark,
    tertiary = McQueenMockupEmerald,
    onTertiary = McQueenObsidianBlack,
    tertiaryContainer = Color(0xFF132D24),
    onTertiaryContainer = Color(0xFFCFF5E7),
    background = McQueenObsidianBlack,
    onBackground = McQueenPrimaryInkDark,
    surface = McQueenCardSurfaceDark,
    onSurface = McQueenPrimaryInkDark,
    surfaceVariant = McQueenButtonSurfaceDark,
    onSurfaceVariant = McQueenMutedInkDark,
    surfaceContainerLowest = McQueenObsidianBlack,
    surfaceContainerLow = McQueenCardSurfaceDark,
    surfaceContainer = McQueenControlSurfaceDark,
    surfaceContainerHigh = McQueenButtonSurfaceDark,
    surfaceContainerHighest = Color(0xFF24242B),
    outline = McQueenHairlineBorder,
    outlineVariant = McQueenControlBorderDark,
    error = McQueenErrorCoral,
    onError = Color(0xFF28080C),
    errorContainer = Color(0xFF3E161B),
    onErrorContainer = Color(0xFFFFDADC),
)

private val McQueenLightColorScheme = lightColorScheme(
    primary = McQueenRacingRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF9E5E7),
    onPrimaryContainer = Color(0xFF4A0D13),
    secondary = McQueenLightMainText,
    onSecondary = Color.White,
    secondaryContainer = McQueenLightElevatedSurface,
    onSecondaryContainer = McQueenLightMainText,
    tertiary = McQueenMockupEmeraldLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6F3E8),
    onTertiaryContainer = Color(0xFF0B3D2C),
    background = McQueenLightBackground,
    onBackground = McQueenLightMainText,
    surface = McQueenLightSurface,
    onSurface = McQueenLightMainText,
    surfaceVariant = McQueenLightElevatedSurface,
    onSurfaceVariant = McQueenLightSecondaryText,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = McQueenLightBackground,
    surfaceContainer = McQueenLightSurface,
    surfaceContainerHigh = McQueenLightElevatedSurface,
    surfaceContainerHighest = Color(0xFFD9DFE5),
    outline = McQueenLightBorder,
    outlineVariant = Color(0xFFE3E7EC),
    error = McQueenErrorLight,
    onError = Color.White,
    errorContainer = Color(0xFFFCE6E8),
    onErrorContainer = Color(0xFF5A0F17),
)

// Accessible Typography Scale (14–16sp+ for key information, respects system font scaling)
private val McQueenTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = McQueenDisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = McQueenDisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.15).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = McQueenDisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = McQueenBodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = McQueenBodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = McQueenBodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = McQueenBodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = McQueenBodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = McQueenDisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = McQueenBodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.25.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = McQueenBodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
)

private val McQueenShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

object ThemeManager {
    const val MODE_SYSTEM = "0"
    const val MODE_LIGHT = "1"
    const val MODE_DARK = "2"

    private val _themeModeState = mutableStateOf(readPersistedMode())
    val themeMode: String
        get() = _themeModeState.value

    fun normalizeMode(rawMode: String?): String {
        return when (rawMode?.trim()) {
            MODE_LIGHT, "light", "no" -> MODE_LIGHT
            MODE_DARK, "dark", "yes" -> MODE_DARK
            else -> MODE_SYSTEM
        }
    }

    fun resolveDarkTheme(mode: String, systemInDarkTheme: Boolean): Boolean {
        return when (normalizeMode(mode)) {
            MODE_LIGHT -> false
            MODE_DARK -> true
            else -> systemInDarkTheme
        }
    }

    fun resolveNightModeFlag(mode: String): Int {
        return when (normalizeMode(mode)) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    fun refresh() {
        val current = readPersistedMode()
        _themeModeState.value = current
        applyAppCompatNightMode(current)
    }

    fun setThemeMode(mode: String) {
        val normalized = normalizeMode(mode)
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, normalized)
        _themeModeState.value = normalized
        SettingsChangeManager.notifySettingChanged(AppConfig.PREF_UI_MODE_NIGHT)
        applyAppCompatNightMode(normalized)
    }

    private fun readPersistedMode(): String {
        val stored = runCatching {
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, AppConfig.DEFAULT_UI_MODE_NIGHT)
        }.getOrDefault(AppConfig.DEFAULT_UI_MODE_NIGHT)
        return normalizeMode(stored)
    }

    private fun applyAppCompatNightMode(mode: String) {
        runCatching {
            val targetNightMode = resolveNightModeFlag(mode)
            if (AppCompatDelegate.getDefaultNightMode() != targetNightMode) {
                AppCompatDelegate.setDefaultNightMode(targetNightMode)
            }
        }
    }
}

@Composable
fun AppTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = darkTheme ?: ThemeManager.resolveDarkTheme(ThemeManager.themeMode, systemDark)
    val colorScheme = if (isDark) McQueenDarkColorScheme else McQueenLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.decorView.setBackgroundColor(colorScheme.background.toArgb())
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = McQueenTypography,
        shapes = McQueenShapes,
        content = content
    )
}
