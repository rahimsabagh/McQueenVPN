package com.v2ray.ang.mcqueen

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.BypassIranRoutingHelper
import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.core.GeoAssetSanitizer
import com.v2ray.ang.core.GeoDatInspector
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BypassIranRoutingTest {

    private fun resolveAssetsDir(): File {
        val candidates = listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
        )
        return candidates.firstOrNull { File(it, AppConfig.GEOIP_DAT).isFile }
            ?: error("Could not locate app/src/main/assets containing geoip.dat and geosite.dat")
    }

    @Test
    fun `bundled geoip and geosite archives contain IR IPv4-IPv6 CIDRs and category-ir domains`() {
        val assetsDir = resolveAssetsDir()
        val geoipStats = GeoDatInspector.inspectGeoIp(File(assetsDir, AppConfig.GEOIP_DAT), "IR")
        val geositeStats = GeoDatInspector.inspectGeoSite(File(assetsDir, AppConfig.GEOSITE_DAT), "CATEGORY-IR")

        assertTrue(geoipStats.fileExists)
        assertTrue(geoipStats.validArchive)
        assertTrue(geoipStats.hasEntry, "geoip.dat must contain IR entry")
        assertTrue(geoipStats.ipv4CidrCount > 0, "geoip:ir must contain IPv4 CIDRs")
        assertTrue(geoipStats.ipv6CidrCount > 0, "geoip:ir must contain IPv6 CIDRs")

        assertTrue(geositeStats.fileExists)
        assertTrue(geositeStats.validArchive)
        assertTrue(geositeStats.hasEntry, "geosite.dat must contain CATEGORY-IR entry")
        assertTrue(geositeStats.domainCount > 0, "geosite:category-ir must contain domains")

        val status = BypassIranRoutingHelper.inspectGeoData(assetsDir)
        assertTrue(status.isReady)
        assertTrue(status.hasGeoipIr)
        assertTrue(status.hasGeositeCategoryIr)
    }

    @Test
    fun `detects missing or invalid geoip and geosite entries and suppresses broken rules`(@TempDir tempDir: File) {
        // 1. Missing .dat files completely
        val emptySanitizer = GeoAssetSanitizer.forDirectory(tempDir)
        assertFalse(emptySanitizer.canUseGeoipIr())
        assertFalse(emptySanitizer.canUseGeositeCategoryIr())
        assertTrue(BypassIranRoutingHelper.buildBypassRules(enabled = true, geoAssets = emptySanitizer).isEmpty())
        assertNull(BypassIranRoutingHelper.buildDnsRoutingDomainRule(enabled = true, geoAssets = emptySanitizer))

        // 2. Corrupted / stub .dat files lacking IR & CATEGORY-IR entries
        File(tempDir, AppConfig.GEOIP_DAT).writeText("invalid-binary-stub")
        File(tempDir, AppConfig.GEOSITE_DAT).writeText("invalid-binary-stub")
        val invalidSanitizer = GeoAssetSanitizer.forDirectory(tempDir)
        assertFalse(invalidSanitizer.canUseGeoipIr())
        assertFalse(invalidSanitizer.canUseGeositeCategoryIr())
        assertNull(invalidSanitizer.sanitizeRuleIps(listOf(AppConfig.GEOIP_IR)))
        assertNull(invalidSanitizer.sanitizeRuleDomains(listOf(AppConfig.GEOSITE_CATEGORY_IR)))
        assertTrue(BypassIranRoutingHelper.buildBypassRules(enabled = true, geoAssets = invalidSanitizer).isEmpty())

        val status = BypassIranRoutingHelper.inspectGeoData(tempDir)
        assertFalse(status.isReady)
    }

    @Test
    fun `generates exact field routing rules for geosite category-ir and geoip ir when enabled and none when disabled`() {
        val assetsDir = resolveAssetsDir()
        val sanitizer = GeoAssetSanitizer.forDirectory(assetsDir)

        // Default OFF produces zero rules
        val disabledRules = BypassIranRoutingHelper.buildBypassRules(enabled = false, geoAssets = sanitizer)
        assertTrue(disabledRules.isEmpty())
        assertNull(BypassIranRoutingHelper.buildDnsRoutingDomainRule(enabled = false, geoAssets = sanitizer))

        // Enabled produces the two required rules matching direct outbound
        val enabledRules = BypassIranRoutingHelper.buildBypassRules(enabled = true, geoAssets = sanitizer)
        assertEquals(2, enabledRules.size)

        val domainRule = enabledRules[0]
        assertEquals("field", domainRule.type)
        assertEquals(listOf(AppConfig.GEOSITE_CATEGORY_IR), domainRule.domain)
        assertEquals(AppConfig.TAG_DIRECT, domainRule.outboundTag)
        assertNull(domainRule.ip)

        val ipRule = enabledRules[1]
        assertEquals("field", ipRule.type)
        assertEquals(listOf(AppConfig.GEOIP_IR), ipRule.ip)
        assertEquals(AppConfig.TAG_DIRECT, ipRule.outboundTag)
        assertNull(ipRule.domain)

        // Verify rule evaluation order ahead of general catch-all proxy rule
        val allRules = mutableListOf<V2rayConfig.RoutingBean.RulesBean>()
        allRules.addAll(enabledRules)
        allRules.add(V2rayConfig.RoutingBean.RulesBean(port = "0-65535", outboundTag = AppConfig.TAG_PROXY))
        val firstProxyIndex = allRules.indexOfFirst { it.outboundTag == AppConfig.TAG_PROXY }
        assertEquals(0, allRules.indexOf(domainRule))
        assertEquals(1, allRules.indexOf(ipRule))
        assertTrue(firstProxyIndex > allRules.indexOf(ipRule))

        // DNS segmentation rule includes geosite:category-ir -> direct
        val dnsRule = BypassIranRoutingHelper.buildDnsRoutingDomainRule(enabled = true, geoAssets = sanitizer)
        assertNotNull(dnsRule)
        assertEquals(listOf(AppConfig.GEOSITE_CATEGORY_IR), dnsRule!!.domain)
        assertEquals(AppConfig.TAG_DIRECT, dnsRule.outboundTag)
    }

    @Test
    fun `protocol compatibility distinguishes xray-routed engines from standalone or custom engines without mutating wireguard outbound`() {
        val assetsDir = resolveAssetsDir()
        val geoStatus = BypassIranRoutingHelper.inspectGeoData(assetsDir)

        val aetherProfile = ProfileItem.create(EConfigType.AETHER).apply {
            remarks = "Germany"
        }
        val aetherCompat = BypassIranRoutingHelper.evaluateProfileCompatibility(aetherProfile, null, geoStatus)
        assertTrue(aetherCompat is BypassIranRoutingHelper.AvailabilityStatus.Supported)

        val customProfile = ProfileItem.create(EConfigType.CUSTOM).apply {
            remarks = "Custom Raw"
        }
        val customCompat = BypassIranRoutingHelper.evaluateProfileCompatibility(customProfile, "{}", geoStatus)
        assertTrue(customCompat is BypassIranRoutingHelper.AvailabilityStatus.UnsupportedProtocol)

        assertTrue(BypassIranRoutingHelper.isStandaloneNonXrayEngine("psiphon-standalone"))
        assertTrue(BypassIranRoutingHelper.isStandaloneNonXrayEngine("wireguard-standalone"))
        assertFalse(BypassIranRoutingHelper.shouldApplyBypassIran(isEnabled = true, profileType = EConfigType.CUSTOM))
        assertTrue(BypassIranRoutingHelper.shouldApplyBypassIran(isEnabled = true, profileType = EConfigType.AETHER))

        // WireGuard runs behind Xray-core front-end routing without mutating WireGuard outbound settings
        val wgProfile = ProfileItem.create(EConfigType.WIREGUARD).apply {
            remarks = "WG Test"
            server = "198.51.100.10"
            serverPort = "51820"
            secretKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
            publicKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
            localAddress = AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        }
        val wgCompat = BypassIranRoutingHelper.evaluateProfileCompatibility(wgProfile, null, geoStatus)
        assertTrue(wgCompat is BypassIranRoutingHelper.AvailabilityStatus.Supported)
        assertEquals("Xray-core", (wgCompat as BypassIranRoutingHelper.AvailabilityStatus.Supported).engineLabel)
        assertTrue(BypassIranRoutingHelper.shouldApplyBypassIran(isEnabled = true, profileType = EConfigType.WIREGUARD))
    }

    @Test
    fun `bypass iran defaults to enabled when unset while preserving explicit false and emits rules`() {
        assertTrue(AppConfig.DEFAULT_MCQUEEN_BYPASS_IRAN)
        assertTrue(com.v2ray.ang.handler.SettingsManager.resolveBypassIranSetting(hasExplicitValue = false, storedValue = false))
        assertTrue(com.v2ray.ang.handler.SettingsManager.resolveBypassIranSetting(hasExplicitValue = false, storedValue = true))
        assertFalse(com.v2ray.ang.handler.SettingsManager.resolveBypassIranSetting(hasExplicitValue = true, storedValue = false))
        assertTrue(com.v2ray.ang.handler.SettingsManager.resolveBypassIranSetting(hasExplicitValue = true, storedValue = true))

        val assetsDir = resolveAssetsDir()
        val sanitizer = GeoAssetSanitizer.forDirectory(assetsDir)
        val defaultRules = BypassIranRoutingHelper.buildBypassRules(
            enabled = AppConfig.DEFAULT_MCQUEEN_BYPASS_IRAN,
            geoAssets = sanitizer,
        )
        assertEquals(2, defaultRules.size)
        assertNotNull(
            BypassIranRoutingHelper.buildDnsRoutingDomainRule(
                enabled = AppConfig.DEFAULT_MCQUEEN_BYPASS_IRAN,
                geoAssets = sanitizer,
            )
        )
    }
}
