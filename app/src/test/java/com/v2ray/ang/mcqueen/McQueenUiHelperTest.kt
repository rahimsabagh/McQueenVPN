package com.v2ray.ang.mcqueen

import com.v2ray.ang.ui.compose.McQueenDeepSurface
import com.v2ray.ang.ui.compose.McQueenElevatedSurface
import com.v2ray.ang.ui.compose.McQueenGraphiteBlack
import com.v2ray.ang.ui.compose.McQueenRacingRed
import com.v2ray.ang.ui.compose.McQueenSecondaryText
import com.v2ray.ang.ui.compose.McQueenTitaniumWhite
import com.v2ray.ang.ui.main.McQueenUiHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McQueenUiHelperTest {

    @Test
    fun `resolveSavedCountry returns null on empty list and defaults to first available location when no selection is saved`() {
        val list = listOf(
            CountryUiModel("US", "United States", "🇺🇸", 3, bestLatencyMs = 80L, availability = CountryAvailability.OPTIMAL),
            CountryUiModel("DE", "Germany", "🇩🇪", 2, bestLatencyMs = 45L, availability = CountryAvailability.OPTIMAL),
        )

        assertNull(McQueenUiHelper.resolveSavedCountry(emptyList(), "DE", "DE", "Germany"))
        assertEquals("US", McQueenUiHelper.resolveSavedCountry(list, null, null, null)?.countryCode)
        assertEquals("US", McQueenUiHelper.resolveSavedCountry(list, "  ", "", "   ")?.countryCode)
        assertEquals("US", McQueenUiHelper.resolveSavedCountry(list, null, "FR", "France")?.countryCode)
    }

    @Test
    fun `resolveSavedCountry restores explicit selection by code or name case-insensitively`() {
        val list = listOf(
            CountryUiModel("US", "United States", "🇺🇸", 3),
            CountryUiModel("DE", "Germany", "🇩🇪", 2),
        )

        assertEquals(
            "DE",
            McQueenUiHelper.resolveSavedCountry(list, "de", null, null)?.countryCode
        )
        assertEquals(
            "US",
            McQueenUiHelper.resolveSavedCountry(list, null, "us", null)?.countryCode
        )
        assertEquals(
            "DE",
            McQueenUiHelper.resolveSavedCountry(list, null, null, "germany")?.countryCode
        )
    }

    @Test
    fun `filterCountries matches country name and code case-insensitively`() {
        val list = listOf(
            CountryUiModel("DE", "Germany", "🇩🇪", 2),
            CountryUiModel("US", "United States", "🇺🇸", 3),
            CountryUiModel("JP", "Japan", "🇯🇵", 1),
        )
        assertEquals(3, McQueenUiHelper.filterCountries(list, "  ").size)
        assertEquals(listOf("DE"), McQueenUiHelper.filterCountries(list, "germ").map { it.countryCode })
        assertEquals(listOf("JP"), McQueenUiHelper.filterCountries(list, "jp").map { it.countryCode })
        assertTrue(McQueenUiHelper.filterCountries(list, "nonexistent").isEmpty())
    }

    @Test
    fun `requiresDisconnectConfirmation is false for all states to allow instant one-tap disconnect`() {
        assertFalse(McQueenUiHelper.requiresDisconnectConfirmation(VpnConnectionState.CONNECTED))
        assertFalse(McQueenUiHelper.requiresDisconnectConfirmation(VpnConnectionState.DISCONNECTED))
        assertFalse(McQueenUiHelper.requiresDisconnectConfirmation(VpnConnectionState.PREPARING))
        assertFalse(McQueenUiHelper.requiresDisconnectConfirmation(VpnConnectionState.CONNECTING))
        assertFalse(McQueenUiHelper.requiresDisconnectConfirmation(VpnConnectionState.RECONNECTING))
        assertFalse(McQueenUiHelper.requiresDisconnectConfirmation(VpnConnectionState.FAILED))
    }

    @Test
    fun `design palette tokens match Effortless Performance specification`() {
        assertEquals(0xFF111416UL, McQueenGraphiteBlack.value shr 32)
        assertEquals(0xFF191E22UL, McQueenDeepSurface.value shr 32)
        assertEquals(0xFF20272DUL, McQueenElevatedSurface.value shr 32)
        assertEquals(0xFF941E29UL, McQueenRacingRed.value shr 32)
        assertEquals(0xFFD9DADDUL, McQueenTitaniumWhite.value shr 32)
        assertEquals(0xFF929BA3UL, McQueenSecondaryText.value shr 32)
    }
}
