package com.v2ray.ang.mcqueen

import androidx.appcompat.app.AppCompatDelegate
import com.v2ray.ang.helper.BatteryOptimizationHelper
import com.v2ray.ang.ui.compose.ThemeManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatteryOptimizationAndThemeTest {

    @Test
    fun `battery optimization prompt only appears on first launch when not exempt`() {
        // Already exempt -> never show prompt
        assertFalse(
            BatteryOptimizationHelper.shouldShowFirstLaunchPrompt(
                isExempt = true,
                promptAlreadyShown = false
            )
        )
        assertFalse(
            BatteryOptimizationHelper.shouldShowFirstLaunchPrompt(
                isExempt = true,
                promptAlreadyShown = true
            )
        )

        // Not exempt and prompt not yet shown -> show prompt once
        assertTrue(
            BatteryOptimizationHelper.shouldShowFirstLaunchPrompt(
                isExempt = false,
                promptAlreadyShown = false
            )
        )

        // Not exempt but user already dismissed/handled first-launch prompt -> do not spam
        assertFalse(
            BatteryOptimizationHelper.shouldShowFirstLaunchPrompt(
                isExempt = false,
                promptAlreadyShown = true
            )
        )
    }

    @Test
    fun `theme manager normalizes light dark and system modes and resolves night mode flags`() {
        assertEquals(ThemeManager.MODE_LIGHT, ThemeManager.normalizeMode("1"))
        assertEquals(ThemeManager.MODE_LIGHT, ThemeManager.normalizeMode("light"))
        assertEquals(ThemeManager.MODE_DARK, ThemeManager.normalizeMode("2"))
        assertEquals(ThemeManager.MODE_DARK, ThemeManager.normalizeMode("dark"))
        assertEquals(ThemeManager.MODE_SYSTEM, ThemeManager.normalizeMode("0"))
        assertEquals(ThemeManager.MODE_SYSTEM, ThemeManager.normalizeMode(null))
        assertEquals(ThemeManager.MODE_SYSTEM, ThemeManager.normalizeMode("unexpected"))

        // Light mode forces light regardless of system dark state
        assertFalse(ThemeManager.resolveDarkTheme(ThemeManager.MODE_LIGHT, systemInDarkTheme = true))
        assertFalse(ThemeManager.resolveDarkTheme(ThemeManager.MODE_LIGHT, systemInDarkTheme = false))
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_NO,
            ThemeManager.resolveNightModeFlag(ThemeManager.MODE_LIGHT)
        )

        // Dark mode forces dark regardless of system dark state
        assertTrue(ThemeManager.resolveDarkTheme(ThemeManager.MODE_DARK, systemInDarkTheme = false))
        assertTrue(ThemeManager.resolveDarkTheme(ThemeManager.MODE_DARK, systemInDarkTheme = true))
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_YES,
            ThemeManager.resolveNightModeFlag(ThemeManager.MODE_DARK)
        )

        // System mode follows systemInDarkTheme
        assertTrue(ThemeManager.resolveDarkTheme(ThemeManager.MODE_SYSTEM, systemInDarkTheme = true))
        assertFalse(ThemeManager.resolveDarkTheme(ThemeManager.MODE_SYSTEM, systemInDarkTheme = false))
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            ThemeManager.resolveNightModeFlag(ThemeManager.MODE_SYSTEM)
        )
    }
}
