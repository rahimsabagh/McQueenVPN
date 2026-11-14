package com.v2ray.ang.mcqueen

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AetherDependency
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileEntry
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McQueenEngineRoutingTest {

    @Test
    fun `routes Xray protocols to XRAY and Aether profiles to AETHER`() {
        val vless = RemoteServerManifestParser.parseSingleConfigUri(
            "vless://11111111-1111-1111-1111-111111111111@203.0.113.10:443?security=tls#de-vless"
        )!!
        val vmess = RemoteServerManifestParser.parseSingleConfigUri(
            "vmess://11111111-1111-1111-1111-111111111111@203.0.113.11:443?type=ws&security=tls#us-vmess"
        )!!
        val trojan = RemoteServerManifestParser.parseSingleConfigUri(
            "trojan://secret@198.51.100.20:443?security=tls#nl-trojan"
        )!!
        val ss = RemoteServerManifestParser.parseSingleConfigUri(
            "ss://aes-256-gcm:password123@192.0.2.30:8388#fr-ss"
        )!!
        val socks = RemoteServerManifestParser.parseSingleConfigUri(
            "socks5://user:pass@192.0.2.31:1080#ca-socks"
        )!!
        val wireguard = RemoteServerManifestParser.parseSingleConfigUri(
            "wireguard://AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA%3D@203.0.113.40:51820?publickey=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB%3D#de-wg"
        )!!
        val hysteria2 = RemoteServerManifestParser.parseSingleConfigUri(
            "hysteria2://secret@198.51.100.50:443?sni=example.invalid#nl-hy2"
        )!!
        val masque = RemoteServerManifestParser.parseSingleConfigUri(
            "masque://203.0.113.60:443?sni=example.invalid#us-masque"
        )!!
        val aether = RemoteServerManifestParser.parseSingleConfigUri(
            "aether://?protocol=warp&scan=balanced#nl-aether"
        )!!

        val xrayProfiles = listOf(vless, vmess, trojan, ss, socks, wireguard, hysteria2, masque)
        for (profile in xrayProfiles) {
            assertTrue(
                RemoteServerManifestParser.isProfileUsable(profile),
                "Expected ${profile.configType} profile to pass validation",
            )
            assertEquals(
                TunnelEngine.XRAY,
                McQueenServerRepository.engineFor(profile),
                "Expected ${profile.configType} to route to XRAY",
            )
            val dependency = AetherDependency.of(listOf(resolvedOutboundFor(profile)))
            assertEquals(
                AetherDependency.None,
                dependency,
                "Expected ${profile.configType} to produce no Aether dependency",
            )
        }

        assertEquals(EConfigType.AETHER, aether.configType)
        assertEquals(TunnelEngine.AETHER, McQueenServerRepository.engineFor(aether))
        val aetherDependency = AetherDependency.of(listOf(resolvedOutboundFor(aether)))
        assertTrue(
            aetherDependency is AetherDependency.Single,
            "Expected AETHER profile to resolve to AetherDependency.Single",
        )
    }

    private fun resolvedOutboundFor(profile: ProfileItem): CoreConfigContext.ResolvedOutbound {
        val entry = ProfileEntry(guid = "guid-${profile.configType.name}", profile = profile)
        return CoreConfigContext.ResolvedOutbound(
            tag = AppConfig.TAG_PROXY,
            entry = entry,
            resolvedEntries = listOf(entry),
            resolvedType = CoreResolvedType.NORMAL,
        )
    }
}
