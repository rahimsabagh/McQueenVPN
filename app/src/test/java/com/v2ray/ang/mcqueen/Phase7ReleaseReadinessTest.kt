package com.v2ray.ang.mcqueen

import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.TrafficStatsSnapshot
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class Phase7ReleaseReadinessTest {

    private fun resolveProjectFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        val fromModule = File("..", relativePath)
        if (fromModule.exists()) return fromModule
        return File(System.getProperty("user.dir"), relativePath)
    }

    @Test
    fun `bundled servers manifest parses all 87 servers across 29 countries without Phase 6 security rejections`() {
        val assetFile = resolveProjectFile("app/src/main/assets/servers.json")
            .takeIf { it.exists() }
            ?: resolveProjectFile("src/main/assets/servers.json")
        assertTrue(assetFile.exists(), "Bundled servers.json must exist at ${assetFile.absolutePath}")

        val rawJson = assetFile.readText(Charsets.UTF_8)
        val parsed = RemoteServerManifestParser.parse(rawJson)

        assertEquals(0, parsed.skippedCount, "No bundled server entry should be rejected by manifest parser")
        assertEquals(87, parsed.servers.size, "All 87 bundled servers must be valid and enabled")
        assertEquals(29, parsed.countries.size, "All 29 countries in bundled manifest must be available")
        assertTrue(
            parsed.servers.all { RemoteServerManifestParser.isProfileUsable(it.profile) },
            "Every bundled server profile must satisfy Phase 6 security usability checks"
        )
        assertTrue(
            parsed.countries.all { it.availability == CountryAvailability.READY && it.availableServerCount > 0 },
            "Every bundled country must have at least one available server"
        )
    }

    @Test
    fun `Gson and Java Serializable round-trips preserve critical VPN and manifest models`() {
        val profile = ProfileItem(
            configType = EConfigType.VLESS,
            remarks = "DE-Frankfurt-1",
            server = "vpn.example.com",
            serverPort = "443",
            password = "11111111-1111-1111-1111-111111111111",
            security = "tls",
            sni = "vpn.example.com",
            insecure = false,
        )
        val profileJson = JsonUtil.toJson(profile)
        val decodedProfile = JsonUtil.fromJsonSafe(profileJson, ProfileItem::class.java)
        assertNotNull(decodedProfile)
        assertEquals(profile.configType, decodedProfile!!.configType)
        assertEquals(profile.remarks, decodedProfile.remarks)
        assertEquals(profile.server, decodedProfile.server)
        assertEquals(profile.serverPort, decodedProfile.serverPort)
        assertFalse(decodedProfile.insecure ?: true)

        val validatorState = ManifestValidatorState(
            scopeHash = "abc123def456",
            etag = "\"v1-etag\"",
            lastModified = "Wed, 18 Nov 2026 00:00:00 GMT"
        )
        val validatorJson = JsonUtil.toJson(validatorState)
        val decodedValidator = JsonUtil.fromJsonSafe(validatorJson, ManifestValidatorState::class.java)
        assertEquals(validatorState, decodedValidator)

        val releaseJson = """{"tag_name":"v1.0.0","body":"Release notes","prerelease":false,"published_at":"2026-11-18T00:00:00Z","assets":[{"name":"McQueenVPN.apk","browser_download_url":"https://example.com/McQueenVPN.apk"}]}"""
        val decodedRelease = JsonUtil.fromJsonSafe(releaseJson, GitHubRelease::class.java)
        assertNotNull(decodedRelease)
        assertEquals("v1.0.0", decodedRelease!!.tagName)
        assertEquals(1, decodedRelease.assets.size)

        val outbound = V2rayConfig.OutboundBean(protocol = "vless", tag = "proxy")
        val outboundJson = JsonUtil.toJson(outbound)
        val decodedOutbound = JsonUtil.fromJsonSafe(outboundJson, V2rayConfig.OutboundBean::class.java)
        assertNotNull(decodedOutbound)
        assertEquals("vless", decodedOutbound!!.protocol)
        assertEquals("proxy", decodedOutbound.tag)

        // Multi-process IPC Serializable round-trip for TrafficStatsSnapshot
        val snapshot = TrafficStatsSnapshot(
            uplinkBps = 12_345L,
            downlinkBps = 67_890L,
            sessionStartMs = 10_000L,
            sessionUplinkBytes = 100_000L,
            sessionDownlinkBytes = 500_000L
        )
        val bytes = ByteArrayOutputStream().use { bos ->
            ObjectOutputStream(bos).use { oos -> oos.writeObject(snapshot) }
            bos.toByteArray()
        }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { ois ->
            ois.readObject() as TrafficStatsSnapshot
        }
        assertEquals(snapshot, restored)
        assertEquals(600_000L, restored.sessionTotalBytes)
    }

    @Test
    fun `proguard rules retain critical JNI Gson Serializable and WorkManager entrypoints`() {
        val proguardFile = resolveProjectFile("app/proguard-rules.pro")
            .takeIf { it.exists() }
            ?: resolveProjectFile("proguard-rules.pro")
        assertTrue(proguardFile.exists(), "proguard-rules.pro must exist at ${proguardFile.absolutePath}")

        val rules = proguardFile.readText(Charsets.UTF_8)
        val requiredFragments = listOf(
            "-keep class go.**",
            "-keep class libv2ray.**",
            "-keep class com.v2ray.ang.service.TProxyService",
            "native <methods>;",
            "-keep class com.v2ray.ang.dto.**",
            "-keep class com.v2ray.ang.enums.**",
            "-keep class com.v2ray.ang.mcqueen.RemoteServerManifestDto",
            "-keep class com.v2ray.ang.mcqueen.RemoteServerEntryDto",
            "-keep class com.v2ray.ang.mcqueen.ManifestValidatorState",
            "-keep class com.v2ray.ang.mcqueen.CachedManifestSummary",
            "implements java.io.Serializable",
            "-keep class com.tencent.mmkv.**"
        )
        for (fragment in requiredFragments) {
            assertTrue(
                rules.contains(fragment),
                "proguard-rules.pro is missing required keep rule fragment: $fragment"
            )
        }
    }
}
