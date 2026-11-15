package com.v2ray.ang.ui.main

import com.v2ray.ang.mcqueen.CountryUiModel
import com.v2ray.ang.mcqueen.VpnConnectionState
import java.util.Locale

object McQueenUiHelper {

    /**
     * Restores the previously selected country when available, otherwise always defaults
     * to the first available location so one-tap connect works immediately.
     */
    fun resolveSavedCountry(
        countries: List<CountryUiModel>,
        currentSelectedCode: String?,
        savedCode: String?,
        savedName: String?,
    ): CountryUiModel? {
        if (countries.isEmpty()) return null
        val targetCode = currentSelectedCode?.trim()?.takeIf { it.isNotEmpty() }
            ?: savedCode?.trim()?.takeIf { it.isNotEmpty() }
        if (targetCode != null) {
            countries.firstOrNull { it.countryCode.equals(targetCode, ignoreCase = true) }?.let {
                return it
            }
        }
        val targetName = savedName?.trim()?.takeIf { it.isNotEmpty() }
        if (targetName != null) {
            countries.firstOrNull { it.countryName.equals(targetName, ignoreCase = true) }?.let {
                return it
            }
        }
        return countries.first()
    }

    /**
     * Filters locations by country name or ISO code for quick lookup.
     */
    fun filterCountries(countries: List<CountryUiModel>, query: String): List<CountryUiModel> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return countries
        val lower = trimmed.lowercase(Locale.ROOT)
        return countries.filter {
            it.countryName.lowercase(Locale.ROOT).contains(lower) ||
                it.countryCode.lowercase(Locale.ROOT).contains(lower)
        }
    }

    /**
     * Disconnecting is a direct single-tap action on the main Power Dial
     * without requiring a secondary confirmation modal.
     */
    fun requiresDisconnectConfirmation(state: VpnConnectionState): Boolean {
        return false
    }
}
