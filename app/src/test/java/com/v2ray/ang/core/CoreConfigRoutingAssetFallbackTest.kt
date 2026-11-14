package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CoreConfigRoutingAssetFallbackTest {

    @Test
    fun `expands geoip private and geosite private and strips missing geo assets when dat files are absent`(@TempDir tempDir: File) {
        val sanitizer = GeoAssetSanitizer.forDirectory(tempDir)
        assertFalse(sanitizer.hasGeoipOnlyCnPrivate)
        assertFalse(sanitizer.hasGeoipDat)
        assertFalse(sanitizer.hasGeositeDat)

        val bypassLanRule = V2rayConfig.RoutingBean.RulesBean(
            ip = listOf(AppConfig.GEOIP_PRIVATE),
            domain = listOf(AppConfig.GEOSITE_PRIVATE),
            outboundTag = AppConfig.TAG_DIRECT,
        )
        val sanitizedBypass = sanitizer.sanitizeRoutingRule(bypassLanRule)
        assertNotNull(sanitizedBypass)
        assertTrue(sanitizedBypass!!.ip!!.containsAll(AppConfig.PRIVATE_IP_LIST))
        assertTrue(sanitizedBypass.ip!!.none { it.startsWith("geoip:") || it.startsWith("ext:") })
        assertTrue(sanitizedBypass.domain!!.contains("domain:local"))
        assertTrue(sanitizedBypass.domain!!.none { it.startsWith("geosite:") || it.startsWith("ext:") })

        val cnRuleOnly = V2rayConfig.RoutingBean.RulesBean(
            ip = listOf(AppConfig.GEOIP_CN),
            domain = listOf(AppConfig.GEOSITE_CN),
            outboundTag = AppConfig.TAG_DIRECT,
        )
        // Rule with ONLY missing geoip:cn / geosite:cn matchers must be dropped so it never turns into a catch-all direct rule
        assertNull(sanitizer.sanitizeRoutingRule(cnRuleOnly))

        val mixedRule = V2rayConfig.RoutingBean.RulesBean(
            ip = listOf(AppConfig.GEOIP_CN, "1.1.1.1/32"),
            domain = listOf(AppConfig.GEOSITE_CN, "domain:example.com"),
            outboundTag = AppConfig.TAG_PROXY,
        )
        val sanitizedMixed = sanitizer.sanitizeRoutingRule(mixedRule)
        assertNotNull(sanitizedMixed)
        assertEquals(listOf("1.1.1.1/32"), sanitizedMixed!!.ip)
        assertEquals(listOf("domain:example.com"), sanitizedMixed.domain)
    }

    @Test
    fun `rewrites geoip cn and geoip private to ext only when geoip-only-cn-private dat exists`(@TempDir tempDir: File) {
        File(tempDir, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT).writeText("stub")
        File(tempDir, AppConfig.GEOSITE_DAT).writeText("stub")

        val sanitizer = GeoAssetSanitizer.forDirectory(tempDir)
        assertTrue(sanitizer.hasGeoipOnlyCnPrivate)
        assertTrue(sanitizer.hasGeositeDat)
        assertTrue(sanitizer.canUseGeoipCn())

        val rule = V2rayConfig.RoutingBean.RulesBean(
            ip = listOf(AppConfig.GEOIP_PRIVATE, AppConfig.GEOIP_CN),
            domain = listOf(AppConfig.GEOSITE_PRIVATE, AppConfig.GEOSITE_CN),
            outboundTag = AppConfig.TAG_DIRECT,
        )
        val sanitized = sanitizer.sanitizeRoutingRule(rule)
        assertNotNull(sanitized)
        assertEquals(
            listOf(
                "ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:private",
                "ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:cn",
            ),
            sanitized!!.ip,
        )
        assertEquals(
            listOf(AppConfig.GEOSITE_PRIVATE, AppConfig.GEOSITE_CN),
            sanitized.domain,
        )
    }

    @Test
    fun `falls back to geoip private and geoip cn when full geoip dat exists without cn-private dat`(@TempDir tempDir: File) {
        File(tempDir, AppConfig.GEOIP_DAT).writeText("stub")
        val sanitizer = GeoAssetSanitizer.forDirectory(tempDir)

        val ips = sanitizer.sanitizeRuleIps(listOf(AppConfig.GEOIP_PRIVATE, AppConfig.GEOIP_CN, "ext:missing.dat:tag"))
        assertEquals(listOf(AppConfig.GEOIP_PRIVATE, AppConfig.GEOIP_CN), ips)
    }

    @Test
    fun `keeps rule that still has port or protocol matchers even when geoip and domain lists sanitize empty`(@TempDir tempDir: File) {
        val sanitizer = GeoAssetSanitizer.forDirectory(tempDir)
        val quicBlock = V2rayConfig.RoutingBean.RulesBean(
            ip = listOf(AppConfig.GEOIP_CN),
            port = "443",
            network = "udp",
            outboundTag = AppConfig.TAG_BLOCKED,
        )
        val sanitized = sanitizer.sanitizeRoutingRule(quicBlock)
        assertNotNull(sanitized)
        assertNull(sanitized!!.ip)
        assertEquals("443", sanitized.port)
        assertEquals("udp", sanitized.network)
    }
}
