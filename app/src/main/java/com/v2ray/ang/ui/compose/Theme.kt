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

// McQueen brand typography
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
    displaySmall = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = BodyFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = BodyFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = BodyFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = BodyFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

private val McQueenShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

// Premium graphite + electric jade palette
private val McQueenDarkColors = darkColorScheme(
    primary = Color(0xFFB7F36B),
    onPrimary = Color(0xFF17220A),
    primaryContainer = Color(0xFF293A18),
    onPrimaryContainer = Color(0xFFD9FFAB),
    secondary = Color(0xFF70DDB5),
    onSecondary = Color(0xFF06291D),
    secondaryContainer = Color(0xFF123D2E),
    onSecondaryContainer = Color(0xFFB9F4DC),
    tertiary = Color(0xFFB9C7FF),
    onTertiary = Color(0xFF18244A),
    tertiaryContainer = Color(0xFF29365F),
    onTertiaryContainer = Color(0xFFDCE2FF),
    error = Color(0xFFFF7777),
    onError = Color(0xFF3B0808),
    errorContainer = Color(0xFF511B1B),
    onErrorContainer = Color(0xFFFFDADA),
    background = Color(0xFF090B0A),
    onBackground = Color(0xFFF2F5F0),
    surface = Color(0xFF101411),
    onSurface = Color(0xFFF2F5F0),
    surfaceVariant = Color(0xFF1A211C),
    onSurfaceVariant = Color(0xFFA4AEA5),
    outline = Color(0xFF3A463C),
    outlineVariant = Color(0xFF252E27),
    inverseSurface = Color(0xFFE9EFE8),
    inverseOnSurface = Color(0xFF151A16),
    inversePrimary = Color(0xFF526D31),
    surfaceContainerLowest = Color(0xFF070907),
    surfaceContainerLow = Color(0xFF111612),
    surfaceContainer = Color(0xFF171D18),
    surfaceContainerHigh = Color(0xFF202820),
    surfaceContainerHighest = Color(0xFF293329),
)

private val McQueenLightColors = lightColorScheme(
    primary = Color(0xFF42651E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9F2B8),
    onPrimaryContainer = Color(0xFF17270A),
    secondary = Color(0xFF176B4C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC5F1DC),
    onSecondaryContainer = Color(0xFF002114),
    tertiary = Color(0xFF485B96),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE2FF),
    onTertiaryContainer = Color(0xFF101B45),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F7F2),
    onBackground = Color(0xFF171C17),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171C17),
    surfaceVariant = Color(0xFFE8EDE5),
    onSurfaceVariant = Color(0xFF4D584D),
    outline = Color(0xFF788477),
    outlineVariant = Color(0xFFDCE3D9),
    inverseSurface = Color(0xFF293329),
    inverseOnSurface = Color(0xFFF2F5F0),
    inversePrimary = Color(0xFFB7F36B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4EC),
    surfaceContainer = Color(0xFFEAEFE5),
    surfaceContainerHigh = Color(0xFFE1E8DC),
    surfaceContainerHighest = Color(0xFFD8E0D3),
)

object McQueenSemanticColors {
    val Connected = Color(0xFFB7F36B)
    val Connecting = Color(0xFF70DDB5)
    val Reconnecting = Color(0xFFFFC66D)
    val Failed = Color(0xFFFF7777)
    val DisconnectedDark = Color(0xFF87948A)
    val DisconnectedLight = Color(0xFF68756A)
    val CrimsonAccent = Color(0xFFB7F36B)
}

val toastNormalBgLight = Color(0xE61E293B)
val toastNormalBgDark = Color(0xE6263248)
val toastSuccessBg = Color(0xE6059669)
val toastErrorBg = Color(0xE6DC2626)
val toastInfoBg = Color(0xE60284C7)
val toastTextColor = Color.White

object ThemeManager {
    private const val DEFAULT_THEME_MODE = "2"
    private const val DEFAULT_DYNAMIC_COLOR = false
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
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
        _themeMode.value = MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
        _dynamicColorEnabled.value = MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)
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
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
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
