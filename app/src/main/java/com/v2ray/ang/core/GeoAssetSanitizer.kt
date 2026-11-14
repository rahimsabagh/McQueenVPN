package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import java.io.File

/**
 * Sanitizes Xray routing and DNS geoip/geosite/ext rules against the .dat files actually present
 * in the app assets directory. Prevents Xray-core startup failures when optional geodata archives
 * (such as geoip-only-cn-private.dat, geoip.dat, or geosite.dat) are not bundled or downloaded.
 */
internal class GeoAssetSanitizer(
    val hasGeoipOnlyCnPrivate: Boolean,
    val hasGeoipDat: Boolean,
    val hasGeositeDat: Boolean,
    private val hasCustomAsset: (String) -> Boolean = { false },
) {
    fun canUseGeoipCn(): Boolean = hasGeoipOnlyCnPrivate || hasGeoipDat

    fun sanitizeRuleIps(ips: List<String>?): List<String>? {
        if (ips == null) return null
        val sanitized = ArrayList<String>(ips.size)
        for (raw in ips) {
            val entry = raw.trim()
            if (entry.isEmpty()) continue
            when {
                entry == AppConfig.GEOIP_CN -> {
                    when {
                        hasGeoipOnlyCnPrivate -> sanitized.add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:cn")
                        hasGeoipDat -> sanitized.add(AppConfig.GEOIP_CN)
                    }
                }

                entry == AppConfig.GEOIP_PRIVATE -> {
                    when {
                        hasGeoipOnlyCnPrivate -> sanitized.add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:private")
                        hasGeoipDat -> sanitized.add(AppConfig.GEOIP_PRIVATE)
                        else -> sanitized.addAll(PRIVATE_IP_FALLBACK_CIDRS)
                    }
                }

                entry.startsWith("geoip:") -> {
                    if (hasGeoipDat) sanitized.add(entry)
                }

                entry.startsWith("ext:") -> {
                    if (isExtAssetAvailable(entry)) sanitized.add(entry)
                }

                else -> sanitized.add(entry)
            }
        }
        return sanitized.distinct().takeIf { it.isNotEmpty() }
    }

    fun sanitizeRuleDomains(domains: List<String>?): List<String>? {
        if (domains == null) return null
        val sanitized = ArrayList<String>(domains.size)
        for (raw in domains) {
            val entry = raw.trim()
            if (entry.isEmpty()) continue
            when {
                entry == AppConfig.GEOSITE_PRIVATE -> {
                    if (hasGeositeDat) {
                        sanitized.add(entry)
                    } else {
                        sanitized.addAll(PRIVATE_DOMAIN_FALLBACK)
                    }
                }

                entry.startsWith("geosite:") -> {
                    if (hasGeositeDat) sanitized.add(entry)
                }

                entry.startsWith("ext:") -> {
                    if (isExtAssetAvailable(entry)) sanitized.add(entry)
                }

                else -> sanitized.add(entry)
            }
        }
        return sanitized.distinct().takeIf { it.isNotEmpty() }
    }

    fun sanitizeRoutingRule(rule: V2rayConfig.RoutingBean.RulesBean): V2rayConfig.RoutingBean.RulesBean? {
        rule.ip = sanitizeRuleIps(rule.ip)
        rule.domain = sanitizeRuleDomains(rule.domain)
        return rule.takeIf(::hasEffectiveMatchers)
    }

    fun hasEffectiveMatchers(rule: V2rayConfig.RoutingBean.RulesBean): Boolean {
        return !rule.ip.isNullOrEmpty() ||
            !rule.domain.isNullOrEmpty() ||
            !rule.process.isNullOrEmpty() ||
            !rule.port.isNullOrBlank() ||
            !rule.sourcePort.isNullOrBlank() ||
            !rule.network.isNullOrBlank() ||
            !rule.source.isNullOrEmpty() ||
            !rule.user.isNullOrEmpty() ||
            !rule.inboundTag.isNullOrEmpty() ||
            !rule.protocol.isNullOrEmpty() ||
            !rule.attrs.isNullOrBlank()
    }

    private fun isExtAssetAvailable(extRule: String): Boolean {
        val afterPrefix = extRule.removePrefix("ext:")
        val separator = afterPrefix.indexOf(':')
        val fileName = (if (separator >= 0) afterPrefix.substring(0, separator) else afterPrefix).trim()
        if (fileName.isEmpty() || fileName.contains("..") || fileName.contains('/') || fileName.contains('\\')) {
            return false
        }
        return when (fileName) {
            AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT -> hasGeoipOnlyCnPrivate
            AppConfig.GEOIP_DAT -> hasGeoipDat
            AppConfig.GEOSITE_DAT -> hasGeositeDat
            else -> hasCustomAsset(fileName)
        }
    }

    companion object {
        internal val PRIVATE_IPV6_CIDRS = listOf(
            "::1/128",
            "fc00::/7",
            "fe80::/10",
        )

        internal val PRIVATE_IP_FALLBACK_CIDRS: List<String> =
            AppConfig.PRIVATE_IP_LIST + PRIVATE_IPV6_CIDRS

        internal val PRIVATE_DOMAIN_FALLBACK = listOf(
            "domain:local",
            "domain:localhost",
            "domain:lan",
            "domain:home.arpa",
            "domain:internal",
        )

        fun forDirectory(assetDir: File): GeoAssetSanitizer {
            return GeoAssetSanitizer(
                hasGeoipOnlyCnPrivate = File(assetDir, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT).isFile,
                hasGeoipDat = File(assetDir, AppConfig.GEOIP_DAT).isFile,
                hasGeositeDat = File(assetDir, AppConfig.GEOSITE_DAT).isFile,
                hasCustomAsset = { fileName -> File(assetDir, fileName).isFile },
            )
        }
    }
}
