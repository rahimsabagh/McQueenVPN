package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// McQueen VPN Brand Fonts
val DisplayFontFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold),
)

val BodyFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
)

val MonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.SemiBold),
)

private val McQueenTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

private val McQueenShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// Obsidian Stealth Dark Palette (Primary Experience)
private val McQueenDarkColors = darkColorScheme(
    primary = Color(0xFFFF4D4D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF421216),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF00D4FF),
    onSecondary = Color(0xFF00242E),
    secondaryContainer = Color(0xFF093645),
    onSecondaryContainer = Color(0xFFBDF2FF),
    tertiary = Color(0xFF00D68F),
    onTertiary = Color(0xFF00291A),
    tertiaryContainer = Color(0xFF073B28),
    onTertiaryContainer = Color(0xFF9BF6CE),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3F0005),
    errorContainer = Color(0xFF4F1317),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF090C12),
    onBackground = Color(0xFFF2F5FA),
    surface = Color(0xFF0E131D),
    onSurface = Color(0xFFF2F5FA),
    surfaceVariant = Color(0xFF1A2232),
    onSurfaceVariant = Color(0xFF9AA7BD),
    outline = Color(0xFF2D384D),
    outlineVariant = Color(0xFF1F2838),
    inverseSurface = Color(0xFFF2F5FA),
    inverseOnSurface = Color(0xFF0E131D),
    inversePrimary = Color(0xFFD92638),
    surfaceContainerLowest = Color(0xFF070A0F),
    surfaceContainerLow = Color(0xFF101622),
    surfaceContainer = Color(0xFF151C2B),
    surfaceContainerHigh = Color(0xFF1C2538),
    surfaceContainerHighest = Color(0xFF232E45),
)

// Crisp Daylight Palette (Light Mode Support)
private val McQueenLightColors = lightColorScheme(
    primary = Color(0xFFD92638),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE5E7),
    onPrimaryContainer = Color(0xFF4A060D),
    secondary = Color(0xFF007EA7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9F3FF),
    onSecondaryContainer = Color(0xFF002533),
    tertiary = Color(0xFF008F5D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCFFAEA),
    onTertiaryContainer = Color(0xFF002819),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE7ECF5),
    onSurfaceVariant = Color(0xFF52607A),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    inverseSurface = Color(0xFF151C2B),
    inverseOnSurface = Color(0xFFF8FAFC),
    inversePrimary = Color(0xFFFF7A85),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5FB),
    surfaceContainer = Color(0xFFEBF0F8),
    surfaceContainerHigh = Color(0xFFE2E8F3),
    surfaceContainerHighest = Color(0xFFD9E1EE),
)

// Semantic McQueen VPN State Colors
object McQueenSemanticColors {
    val Connected = Color(0xFF00D68F)
    val Connecting = Color(0xFF00C2FF)
    val Reconnecting = Color(0xFFFFB020)
    val Failed = Color(0xFFFF5252)
    val DisconnectedDark = Color(0xFF7C8AA5)
    val DisconnectedLight = Color(0xFF64748B)
    val CrimsonAccent = Color(0xFFFF3D4E)
}

// Toast Colors 90%
val toastNormalBgLight = Color(0xE61E293B)
val toastNormalBgDark = Color(0xE6263248)
val toastSuccessBg = Color(0xE6059669)
val toastErrorBg = Color(0xE6DC2626)
val toastInfoBg = Color(0xE60284C7)
val toastTextColor = Color.White

object ThemeManager {
    private const val DEFAULT_THEME_MODE = "2" // Dark mode primary
    private const val DEFAULT_DYNAMIC_COLOR = false

    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, DEFAULT_THEME_MODE)
            ?: DEFAULT_THEME_MODE
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, DEFAULT_THEME_MODE)
                ?: DEFAULT_THEME_MODE
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { true }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> McQueenDarkColors
        else -> McQueenLightColors
    }
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = McQueenTypography,
            shapes = McQueenShapes,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
