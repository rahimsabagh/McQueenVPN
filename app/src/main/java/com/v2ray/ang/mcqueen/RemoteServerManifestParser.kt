package com.v2ray.ang.mcqueen

import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AetherDelayTester
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.TlsSettingsCheck
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.MasqueFmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.SocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import java.util.Locale

object RemoteServerManifestParser {

    private val uriParsers: List<Pair<String, (String) -> ProfileItem?>> by lazy {
        listOf(
            EConfigType.VLESS.protocolScheme to VlessFmt::parse,
            EConfigType.TROJAN.protocolScheme to TrojanFmt::parse,
            EConfigType.VMESS.protocolScheme to VmessFmt::parse,
            EConfigType.SHADOWSOCKS.protocolScheme to ShadowsocksFmt::parse,
            EConfigType.SOCKS.protocolScheme to SocksFmt::parse,
            AppConfig.SOCKS4 to SocksFmt::parse,
            AppConfig.SOCKS5 to SocksFmt::parse,
            EConfigType.WIREGUARD.protocolScheme to WireguardFmt::parse,
            EConfigType.HYSTERIA2.protocolScheme to Hysteria2Fmt::parse,
            AppConfig.HY2 to Hysteria2Fmt::parse,
            EConfigType.MASQUE.protocolScheme to MasqueFmt::parse,
            EConfigType.AETHER.protocolScheme to AetherFmt::parse,
        )
    }

    fun countryCodeToFlag(code: String): String {
        val normalized = code.trim().uppercase(Locale.ROOT)
        if (normalized.length != 2 || normalized.any { it !in 'A'..'Z' }) {
            return "🌐"
        }
        val first = Character.codePointAt(normalized, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(normalized, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    /**
     * Validates and parses remote `servers.json`.
     *
     * @throws IllegalArgumentException when the top-level JSON structure is invalid or missing `servers`.
     */
    fun parse(
        json: String,
        isAetherSupported: Boolean = true,
        subscriptionId: String = AppConfig.MCQUEEN_SUBSCRIPTION_ID,
        verifyOutbound: (ProfileItem) -> Boolean = { true },
    ): ManifestParseResult {
        if (json.isBlank()) {
            throw IllegalArgumentException("Empty server manifest")
        }

        val rootElement = runCatching { JsonParser.parseString(json) }
            .getOrElse { throw IllegalArgumentException("Malformed server manifest JSON", it) }

        if (!rootElement.isJsonObject) {
            throw IllegalArgumentException("Server manifest root must be a JSON object")
        }
        val rootObj = rootElement.asJsonObject
        val serversElement = rootObj.get("servers")
        if (serversElement == null || !serversElement.isJsonArray) {
            throw IllegalArgumentException("Server manifest must contain a 'servers' array")
        }

        val dto = JsonUtil.fromJsonSafe(json, RemoteServerManifestDto::class.java)
            ?: throw IllegalArgumentException("Unable to decode server manifest")
        val rawEntries = dto.servers.orEmpty()

        val validatedServers = mutableListOf<ValidatedServer>()
        var skippedCount = 0

        for (entry in rawEntries) {
            val validated = validateAndParseEntry(
                entry = entry,
                isAetherSupported = isAetherSupported,
                subscriptionId = subscriptionId,
                verifyOutbound = verifyOutbound,
            )
            if (validated != null) {
                validatedServers.add(validated)
            } else {
                skippedCount++
            }
        }

        val countries = validatedServers
            .groupBy { it.countryCode }
            .map { (code, serversForCountry) ->
                val displayCountry = serversForCountry.first().countryName
                CountryUiModel(
                    countryCode = code,
                    countryName = displayCountry,
                    flagEmoji = countryCodeToFlag(code),
                    availableServerCount = serversForCountry.size,
                    bestLatencyMs = null,
                    availability = CountryAvailability.READY,
                )
            }
            .sortedBy { it.countryName.lowercase(Locale.ROOT) }

        return ManifestParseResult(
            version = dto.version,
            updatedAt = dto.updatedAt?.trim()?.takeIf { it.isNotEmpty() },
            servers = validatedServers,
            countries = countries,
            skippedCount = skippedCount,
        )
    }

    internal fun validateAndParseEntry(
        entry: RemoteServerEntryDto?,
        isAetherSupported: Boolean = true,
        subscriptionId: String = AppConfig.MCQUEEN_SUBSCRIPTION_ID,
        verifyOutbound: (ProfileItem) -> Boolean = { true },
    ): ValidatedServer? {
        if (entry == null) return null
        if (entry.enabled != true) return null

        val serverId = entry.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val countryCode = entry.countryCode?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }
            ?: return null

        val countryName = entry.country?.trim()?.takeIf { it.isNotEmpty() }
            ?: Locale.Builder().setRegion(countryCode).build().getDisplayCountry(Locale.ENGLISH)
                .takeIf { it.isNotBlank() }
            ?: countryCode

        val rawConfig = entry.config?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val profile = parseSingleConfigUri(rawConfig) ?: return null

        if (profile.configType == EConfigType.AETHER && !isAetherSupported) {
            return null
        }

        TlsSettingsCheck.fixImportedAlpn(profile)

        if (!isProfileUsable(profile) || !verifyOutbound(profile)) {
            return null
        }

        profile.subscriptionId = subscriptionId
        profile.remarks = "$countryName ($serverId)"
        profile.description = "$countryCode|$serverId"

        val (pingHost, pingPort) = extractPingEndpoint(profile)

        return ValidatedServer(
            id = serverId,
            countryCode = countryCode,
            countryName = countryName,
            profile = profile,
            pingHost = pingHost,
            pingPort = pingPort,
        )
    }

    internal fun parseSingleConfigUri(rawUri: String): ProfileItem? {
        val trimmed = rawUri.trim()
        val matched = uriParsers.firstOrNull { (scheme, _) ->
            trimmed.startsWith(scheme, ignoreCase = true)
        } ?: return null

        return runCatching { matched.second.invoke(trimmed) }.getOrNull()
    }

    internal fun isProfileUsable(profile: ProfileItem): Boolean {
        if (!LauncherManager.hasUsableServer(profile)) {
            return false
        }
        val host = profile.server?.trim().orEmpty()
        val port = profile.serverPort?.trim()?.toIntOrNull()

        return when (profile.configType) {
            EConfigType.AETHER -> {
                runCatching { com.v2ray.ang.core.AetherCore.of(profile) }.isSuccess
            }
            EConfigType.VLESS,
            EConfigType.VMESS,
            EConfigType.TROJAN -> {
                val hostValid = host.isNotEmpty() &&
                    (Utils.isPureIpAddress(host) || Utils.isValidUrl(host))
                val credValid = !profile.password.isNullOrBlank()
                val realityValid = !profile.security.equals(AppConfig.REALITY, ignoreCase = true) ||
                    !profile.publicKey.isNullOrBlank()
                hostValid && port != null && port in 1..65535 && credValid && realityValid
            }
            EConfigType.SHADOWSOCKS -> {
                val hostValid = host.isNotEmpty() &&
                    (Utils.isPureIpAddress(host) || Utils.isValidUrl(host))
                hostValid &&
                    port != null &&
                    port in 1..65535 &&
                    !profile.method.isNullOrBlank() &&
                    !profile.password.isNullOrBlank()
            }
            EConfigType.HYSTERIA2 -> {
                val hostValid = host.isNotEmpty() &&
                    (Utils.isPureIpAddress(host) || Utils.isValidUrl(host))
                hostValid && port != null && port in 1..65535 && !profile.password.isNullOrBlank()
            }
            EConfigType.SOCKS -> {
                val hostValid = host.isNotEmpty() &&
                    (Utils.isPureIpAddress(host) || Utils.isValidUrl(host))
                hostValid && port != null && port in 1..65535
            }
            EConfigType.WIREGUARD -> {
                val hostValid = host.isNotEmpty() &&
                    (Utils.isPureIpAddress(host) || Utils.isValidUrl(host))
                val keysValid = !profile.secretKey.isNullOrBlank() && !profile.publicKey.isNullOrBlank()
                val reservedValid = profile.reserved.isNullOrBlank() ||
                    profile.reserved.orEmpty().split(",").filter { it.isNotBlank() }.all { it.trim().toIntOrNull() != null }
                hostValid && port != null && port in 1..65535 && keysValid && reservedValid
            }
            EConfigType.MASQUE -> {
                val hostValid = host.isNotEmpty() &&
                    (Utils.isPureIpAddress(host) || Utils.isValidUrl(host))
                val headersValid = profile.headers.isNullOrBlank() || runCatching {
                    val obj = JsonParser.parseString(profile.headers.orEmpty()).asJsonObject
                    obj.entrySet().all { it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString }
                }.getOrDefault(false)
                hostValid && port != null && port in 1..65535 && headersValid
            }
            else -> false
        }
    }

    private fun extractPingEndpoint(profile: ProfileItem): Pair<String?, Int?> {
        return if (profile.configType == EConfigType.AETHER) {
            val host = AetherDelayTester.probeHost(profile)
            host to if (host != null) 443 else null
        } else {
            val host = profile.server?.trim()?.takeIf { it.isNotEmpty() }
            val port = profile.serverPort?.toIntOrNull()?.takeIf { it in 1..65535 }
            host to port
        }
    }
}
