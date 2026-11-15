package com.v2ray.ang.mcqueen

import androidx.compose.ui.graphics.Color
import com.v2ray.ang.ui.compose.McQueenMockupAmber
import com.v2ray.ang.ui.compose.McQueenMockupAmberLight
import com.v2ray.ang.ui.compose.McQueenConnectedPlateDarkBottom
import com.v2ray.ang.ui.compose.McQueenConnectedPlateDarkTop
import com.v2ray.ang.ui.compose.McQueenConnectedPlateLightBottom
import com.v2ray.ang.ui.compose.McQueenConnectedPlateLightTop
import com.v2ray.ang.ui.compose.McQueenConnectingLabelDark
import com.v2ray.ang.ui.compose.McQueenConnectingPlateBottom
import com.v2ray.ang.ui.compose.McQueenConnectingPlateTop
import com.v2ray.ang.ui.compose.McQueenDisconnectedPlateDarkBottom
import com.v2ray.ang.ui.compose.McQueenDisconnectedPlateDarkTop
import com.v2ray.ang.ui.compose.McQueenDisconnectedPlateLightBottom
import com.v2ray.ang.ui.compose.McQueenDisconnectedPlateLightTop
import com.v2ray.ang.ui.compose.McQueenDisconnectedTextDark
import com.v2ray.ang.ui.compose.McQueenDisconnectedTextLight
import com.v2ray.ang.ui.main.ConnectButtonPalette
import com.v2ray.ang.ui.main.ConnectButtonState
import com.v2ray.ang.ui.main.contrastRatio
import com.v2ray.ang.ui.main.resolveConnectButtonPalette
import com.v2ray.ang.ui.main.resolveConnectButtonState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.max

class ConnectButtonStateTest {

    @Test
    fun `resolveConnectButtonState maps every VpnConnectionState to three-state ConnectButton machine`() {
        assertEquals(ConnectButtonState.Disconnected, resolveConnectButtonState(VpnConnectionState.DISCONNECTED))
        assertEquals(ConnectButtonState.Disconnected, resolveConnectButtonState(VpnConnectionState.FAILED))
        assertEquals(ConnectButtonState.Connecting, resolveConnectButtonState(VpnConnectionState.PREPARING))
        assertEquals(ConnectButtonState.Connecting, resolveConnectButtonState(VpnConnectionState.CONNECTING))
        assertEquals(ConnectButtonState.Connecting, resolveConnectButtonState(VpnConnectionState.RECONNECTING))
        assertEquals(ConnectButtonState.Connected, resolveConnectButtonState(VpnConnectionState.CONNECTED))
    }

    @Test
    fun `disconnected state uses neutral gray surface and high-contrast foreground`() {
        listOf(true, false).forEach { isDark ->
            val palette = resolveConnectButtonPalette(
                state = ConnectButtonState.Disconnected,
                isDark = isDark,
                needsLocation = false,
                isFailed = false,
            )
            assertEquals(
                if (isDark) McQueenDisconnectedPlateDarkTop else McQueenDisconnectedPlateLightTop,
                palette.plateTop,
            )
            assertEquals(
                if (isDark) McQueenDisconnectedPlateDarkBottom else McQueenDisconnectedPlateLightBottom,
                palette.plateBottom,
            )
            val expectedFg = if (isDark) McQueenDisconnectedTextDark else McQueenDisconnectedTextLight
            assertEquals(expectedFg, palette.iconTint)
            assertEquals(expectedFg, palette.labelColor)
            assertHighContrast(palette)
            assertNoBlueBrandColors(palette)
        }
    }

    @Test
    fun `connecting state uses yellow surface with high-contrast carbon foreground`() {
        listOf(true, false).forEach { isDark ->
            val palette = resolveConnectButtonPalette(
                state = ConnectButtonState.Connecting,
                isDark = isDark,
            )
            assertEquals(McQueenConnectingPlateTop, palette.plateTop)
            assertEquals(McQueenConnectingPlateBottom, palette.plateBottom)
            assertEquals(McQueenConnectingLabelDark, palette.iconTint)
            assertEquals(McQueenConnectingLabelDark, palette.labelColor)
            assertEquals(if (isDark) McQueenMockupAmber else McQueenMockupAmberLight, palette.accentColor)
            assertHighContrast(palette)
            assertNoBlueBrandColors(palette)
        }
    }

    @Test
    fun `connected state uses vibrant red surface and white foreground`() {
        listOf(true, false).forEach { isDark ->
            val palette = resolveConnectButtonPalette(
                state = ConnectButtonState.Connected,
                isDark = isDark,
            )
            assertEquals(
                if (isDark) McQueenConnectedPlateDarkTop else McQueenConnectedPlateLightTop,
                palette.plateTop,
            )
            assertEquals(
                if (isDark) McQueenConnectedPlateDarkBottom else McQueenConnectedPlateLightBottom,
                palette.plateBottom,
            )
            assertEquals(Color.White, palette.iconTint)
            assertEquals(Color.White, palette.labelColor)
            assertHighContrast(palette)
            assertNoBlueBrandColors(palette)
        }
    }

    @Test
    fun `every state and theme variant meets high-contrast threshold and contains zero blue branding`() {
        val scenarios = listOf(
            Triple(ConnectButtonState.Disconnected, false, false),
            Triple(ConnectButtonState.Disconnected, true, false),
            Triple(ConnectButtonState.Disconnected, false, true),
            Triple(ConnectButtonState.Connecting, false, false),
            Triple(ConnectButtonState.Connected, false, false),
        )

        listOf(true, false).forEach { isDark ->
            scenarios.forEach { (state, needsLocation, isFailed) ->
                val palette = resolveConnectButtonPalette(
                    state = state,
                    isDark = isDark,
                    needsLocation = needsLocation,
                    isFailed = isFailed,
                )
                assertHighContrast(palette)
                assertNoBlueBrandColors(palette)
            }
        }
    }

    private fun assertHighContrast(palette: ConnectButtonPalette) {
        val labelTopRatio = contrastRatio(palette.labelColor, palette.plateTop)
        val labelBottomRatio = contrastRatio(palette.labelColor, palette.plateBottom)
        val iconTopRatio = contrastRatio(palette.iconTint, palette.plateTop)
        val iconBottomRatio = contrastRatio(palette.iconTint, palette.plateBottom)

        assertTrue(labelTopRatio >= 4.5, "Label vs plateTop contrast too low: $labelTopRatio")
        assertTrue(labelBottomRatio >= 4.5, "Label vs plateBottom contrast too low: $labelBottomRatio")
        assertTrue(iconTopRatio >= 4.5, "Icon vs plateTop contrast too low: $iconTopRatio")
        assertTrue(iconBottomRatio >= 4.5, "Icon vs plateBottom contrast too low: $iconBottomRatio")
    }

    private fun assertNoBlueBrandColors(palette: ConnectButtonPalette) {
        val allColors = listOf(
            palette.chassisBackground,
            palette.chassisRim,
            palette.plateTop,
            palette.plateBottom,
            palette.plateBorder,
            palette.iconTint,
            palette.labelColor,
            palette.accentColor,
            palette.statusLedColor,
        )
        allColors.forEach { color ->
            val blueDominance = color.blue - max(color.red, color.green)
            assertFalse(
                blueDominance > 0.05f,
                "Unexpected blue color detected: red=${color.red}, green=${color.green}, blue=${color.blue}"
            )
        }
    }
}
