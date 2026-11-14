package com.v2ray.ang.mcqueen

import com.google.gson.annotations.SerializedName
import com.v2ray.ang.dto.entities.ProfileItem

data class RemoteServerManifestDto(
    val version: Int = 1,
    @SerializedName("updated_at")
    val updatedAt: String? = null,
    val servers: List<RemoteServerEntryDto>? = null,
)

data class RemoteServerEntryDto(
    val id: String? = null,
    val country: String? = null,
    @SerializedName("country_code")
    val countryCode: String? = null,
    val config: String? = null,
    val enabled: Boolean? = null,
)

enum class CountryAvailability {
    READY,
    OPTIMAL,
}

/**
 * Consumer-facing country representation exposed to the Jetpack Compose UI.
 * Raw VPN URIs, keys, UUIDs, and internal server IDs are strictly omitted.
 */
data class CountryUiModel(
    val countryCode: String,
    val countryName: String,
    val flagEmoji: String,
    val availableServerCount: Int,
    val bestLatencyMs: Long? = null,
    val availability: CountryAvailability = CountryAvailability.READY,
)

data class ValidatedServer(
    val id: String,
    val countryCode: String,
    val countryName: String,
    val profile: ProfileItem,
    val pingHost: String?,
    val pingPort: Int?,
)

data class ManifestParseResult(
    val version: Int,
    val updatedAt: String?,
    val servers: List<ValidatedServer>,
    val countries: List<CountryUiModel>,
    val skippedCount: Int,
)

data class CandidateLatency(
    val guid: String,
    val latencyMs: Long,
)
