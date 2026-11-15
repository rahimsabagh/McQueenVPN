package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File

/**
 * Coordinates the "Bypass Iran" feature across routing rules, DNS domain segmentation,
 * geodata archive verification (geoip:ir + geosite:category-ir), and engine compatibility.
 */
object BypassIranRoutingHelper {

    data class GeoDataStatus(
        val hasGeoipDat: Boolean,
        val hasGeositeDat: Boolean,
        val hasGeoipIr: Boolean,
        val hasGeositeCategoryIr: Boolean,
        val ipv4CidrCount: Int,
        val ipv6CidrCount: Int,
        val irDomainCount: Int,
    ) {
        val isReady: Boolean get() = hasGeoipIr && hasGeositeCategoryIr
    }

    sealed class AvailabilityStatus {
        data class Supported(
            val geoStatus: GeoDataStatus,
            val engineLabel: String,
        ) : AvailabilityStatus()

        data class MissingGeoData(
            val geoStatus: GeoDataStatus,
        ) : AvailabilityStatus()

        data class UnsupportedProtocol(
            val reasonKey: UnsupportedReason,
            val protocolName: String,
        ) : AvailabilityStatus()
    }

    enum class UnsupportedReason {
        CUSTOM_PROFILE_NO_XRAY_ROUTING,
        NON_XRAY_DIRECT_ENGINE,
    }

    internal fun buildBypassRules(
        enabled: Boolean,
        geoAssets: GeoAssetSanitizer,
    ): List<V2rayConfig.RoutingBean.RulesBean> {
        if (!enabled) return emptyList()

        val rules = mutableListOf<V2rayConfig.RoutingBean.RulesBean>()

        // 1. Bypass Iranian domains via geosite:category-ir before general proxy rules
        val domainRule = V2rayConfig.RoutingBean.RulesBean(
            type = "field",
            domain = arrayListOf(AppConfig.GEOSITE_CATEGORY_IR),
            outboundTag = AppConfig.TAG_DIRECT,
        )
        geoAssets.sanitizeRoutingRule(domainRule)?.let { rules.add(it) }

        // 2. Bypass Iranian IPv4 & IPv6 CIDRs via geoip:ir before general proxy rules
        val ipRule = V2rayConfig.RoutingBean.RulesBean(
            type = "field",
            ip = arrayListOf(AppConfig.GEOIP_IR),
            outboundTag = AppConfig.TAG_DIRECT,
        )
        geoAssets.sanitizeRoutingRule(ipRule)?.let { rules.add(it) }

        if (rules.size < 2) {
            runCatching {
                LogUtil.w(
                    AppConfig.TAG,
                    "BypassIranRoutingHelper: incomplete geodata (hasGeoipIr=${geoAssets.canUseGeoipIr()}, hasGeositeCategoryIr=${geoAssets.canUseGeositeCategoryIr()})"
                )
            }
        }
        return rules
    }

    internal fun buildDnsRoutingDomainRule(
        enabled: Boolean,
        geoAssets: GeoAssetSanitizer,
    ): CoreConfigContext.RoutingDomainRule? {
        if (!enabled || !geoAssets.canUseGeositeCategoryIr()) {
            return null
        }
        return CoreConfigContext.RoutingDomainRule(
            domain = listOf(AppConfig.GEOSITE_CATEGORY_IR),
            outboundTag = AppConfig.TAG_DIRECT,
        )
    }

    fun inspectGeoData(assetDir: File): GeoDataStatus {
        val sanitizer = GeoAssetSanitizer.forDirectory(assetDir)
        return GeoDataStatus(
            hasGeoipDat = sanitizer.hasGeoipDat,
            hasGeositeDat = sanitizer.hasGeositeDat,
            hasGeoipIr = sanitizer.canUseGeoipIr(),
            hasGeositeCategoryIr = sanitizer.canUseGeositeCategoryIr(),
            ipv4CidrCount = sanitizer.geoipIrIpv4Count,
            ipv6CidrCount = sanitizer.geoipIrIpv6Count,
            irDomainCount = sanitizer.geositeCategoryIrDomainCount,
        )
    }

    fun checkCompatibility(
        context: Context,
        selectedGuid: String? = MmkvManager.getSelectServer(),
    ): AvailabilityStatus {
        val assetDir = File(Utils.userAssetPath(context))
        val geoStatus = inspectGeoData(assetDir)
        if (!geoStatus.isReady) {
            return AvailabilityStatus.MissingGeoData(geoStatus)
        }

        val guid = selectedGuid ?: return AvailabilityStatus.Supported(
            geoStatus = geoStatus,
            engineLabel = "Xray-core",
        )
        val profile = MmkvManager.decodeServerConfig(guid)
            ?: return AvailabilityStatus.Supported(geoStatus = geoStatus, engineLabel = "Xray-core")

        val rawConfig = if (profile.configType == EConfigType.CUSTOM) {
            MmkvManager.decodeServerRaw(guid)
        } else {
            null
        }
        return evaluateProfileCompatibility(profile, rawConfig, geoStatus)
    }

    internal fun evaluateProfileCompatibility(
        profile: ProfileItem,
        rawCustomJson: String?,
        geoStatus: GeoDataStatus,
    ): AvailabilityStatus {
        if (!geoStatus.isReady) {
            return AvailabilityStatus.MissingGeoData(geoStatus)
        }

        return when (profile.configType) {
            EConfigType.CUSTOM -> {
                AvailabilityStatus.UnsupportedProtocol(
                    reasonKey = UnsupportedReason.CUSTOM_PROFILE_NO_XRAY_ROUTING,
                    protocolName = profile.configType.name,
                )
            }

            // Aether and all Xray-routed profiles (WireGuard, VLESS, VMess, Trojan, SS, Hysteria2, MASQUE, PolicyGroup, ProxyChain)
            // run Xray-core as the front-end TUN/SOCKS router where domain: geosite:category-ir and ip: geoip:ir execute
            // cleanly without injecting routing syntax into Psiphon, Aether CLI, or WireGuard peer blocks.
            EConfigType.AETHER -> AvailabilityStatus.Supported(geoStatus, "Xray + Aether")
            else -> AvailabilityStatus.Supported(geoStatus, "Xray-core")
        }
    }

    fun isStandaloneNonXrayEngine(engineId: String): Boolean {
        val normalized = engineId.trim().lowercase()
        return normalized == "psiphon-standalone" || normalized == "wireguard-standalone"
    }

    fun shouldApplyBypassIran(
        isEnabled: Boolean = SettingsManager.isBypassIranEnabled(),
        profileType: EConfigType?,
    ): Boolean {
        if (!isEnabled) return false
        return profileType != EConfigType.CUSTOM
    }
}
