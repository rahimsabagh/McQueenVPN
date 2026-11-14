package com.v2ray.ang.mcqueen

import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteServerManifestParserTest {

    @Test
    fun `parses 36 servers across countries, skips disabled and malformed entries`() {
        val entries = mutableListOf<String>()
        val countries = listOf(
            "DE" to "Germany",
            "NL" to "Netherlands",
            "US" to "United States",
            "JP" to "Japan",
            "GB" to "United Kingdom",
            "FR" to "France",
        )
        var counter = 1
        for (round in 1..6) {
            for ((code, name) in countries) {
                val id = "${code.lowercase()}-0$round"
                val config = if (counter % 2 == 0) {
                    "vless://11111111-1111-1111-1111-111111111111@203.0.113.$counter:443?security=tls&type=ws#$id"
                } else {
                    "trojan://secretpass-$counter@198.51.100.$counter:443?security=tls#$id"
                }
                entries.add(
                    """
                    {
                      "id": "$id",
                      "country": "$name",
                      "country_code": "$code",
                      "config": "$config",
                      "enabled": true
                    }
                    """.trimIndent()
                )
                counter++
            }
        }
        // Disabled entry for an otherwise absent country -> CA must NOT appear
        entries.add(
            """
            {
              "id": "ca-01",
              "country": "Canada",
              "country_code": "CA",
              "config": "vless://11111111-1111-1111-1111-111111111111@192.0.2.10:443?security=tls#ca",
              "enabled": false
            }
            """.trimIndent()
        )
        // Malformed URI for AU -> AU must NOT appear
        entries.add(
            """
            {
              "id": "au-01",
              "country": "Australia",
              "country_code": "AU",
              "config": "not-a-valid-uri",
              "enabled": true
            }
            """.trimIndent()
        )

        val manifestJson = """
            {
              "version": 1,
              "updated_at": "2026-11-14T00:00:00Z",
              "servers": [${entries.joinToString(",")}]
            }
        """.trimIndent()

        val result = RemoteServerManifestParser.parse(
            json = manifestJson,
            isAetherSupported = true,
        )

        assertEquals(1, result.version)
        assertEquals("2026-11-14T00:00:00Z", result.updatedAt)
        assertEquals(36, result.servers.size)
        assertEquals(2, result.skippedCount)
        assertEquals(6, result.countries.size)
        assertFalse(result.countries.any { it.countryCode == "CA" || it.countryCode == "AU" })

        val germany = result.countries.first { it.countryCode == "DE" }
        assertEquals("Germany", germany.countryName)
        assertEquals("🇩🇪", germany.flagEmoji)
        assertEquals(6, germany.availableServerCount)
        // Ensure no raw config strings leak into UI model representation
        assertFalse(germany.toString().contains("vless://"))
        assertFalse(germany.toString().contains("trojan://"))
    }

    @Test
    fun `filters aether servers when device ABI does not support Aether`() {
        val manifestJson = """
            {
              "version": 1,
              "updated_at": "2026-11-14T00:00:00Z",
              "servers": [
                {
                  "id": "nl-aether",
                  "country": "Netherlands",
                  "country_code": "NL",
                  "config": "aether://?protocol=warp&scan=balanced#nl-aether",
                  "enabled": true
                },
                {
                  "id": "de-vless",
                  "country": "Germany",
                  "country_code": "DE",
                  "config": "vless://22222222-2222-2222-2222-222222222222@203.0.113.99:443?security=tls#de-vless",
                  "enabled": true
                }
              ]
            }
        """.trimIndent()

        val supported = RemoteServerManifestParser.parse(manifestJson, isAetherSupported = true)
        assertEquals(2, supported.servers.size)
        assertTrue(supported.servers.any { it.profile.configType == EConfigType.AETHER })

        val unsupported = RemoteServerManifestParser.parse(manifestJson, isAetherSupported = false)
        assertEquals(1, unsupported.servers.size)
        assertEquals("DE", unsupported.countries.single().countryCode)
    }

    @Test
    fun `throws IllegalArgumentException on malformed manifest JSON`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteServerManifestParser.parse("{ invalid json", isAetherSupported = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteServerManifestParser.parse("""{"version": 1}""", isAetherSupported = true)
        }
    }

    @Test
    fun `converts ISO country codes to flag emojis safely`() {
        assertEquals("🇩🇪", RemoteServerManifestParser.countryCodeToFlag("de"))
        assertEquals("🇳🇱", RemoteServerManifestParser.countryCodeToFlag("NL"))
        assertEquals("🇺🇸", RemoteServerManifestParser.countryCodeToFlag("US"))
        assertEquals("🌐", RemoteServerManifestParser.countryCodeToFlag("123"))
    }

    @Test
    fun `rejects incomplete protocol configs and honors outbound verification callback`() {
        val manifestJson = """
            {
              "version": 1,
              "updated_at": "2026-11-14T00:00:00Z",
              "servers": [
                {
                  "id": "wg-valid",
                  "country": "Germany",
                  "country_code": "DE",
                  "config": "wireguard://AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA%3D@203.0.113.10:51820?publickey=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB%3D&reserved=1,2,3#wg-ok",
                  "enabled": true
                },
                {
                  "id": "wg-bad-reserved",
                  "country": "Germany",
                  "country_code": "DE",
                  "config": "wireguard://AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA%3D@203.0.113.11:51820?publickey=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB%3D&reserved=abc#wg-bad",
                  "enabled": true
                },
                {
                  "id": "hy2-valid",
                  "country": "Netherlands",
                  "country_code": "NL",
                  "config": "hysteria2://secret@198.51.100.20:443?sni=example.invalid#hy2-ok",
                  "enabled": true
                },
                {
                  "id": "hy2-no-pass",
                  "country": "Netherlands",
                  "country_code": "NL",
                  "config": "hysteria2://198.51.100.21:443?sni=example.invalid#hy2-nopass",
                  "enabled": true
                },
                {
                  "id": "ss-no-pass",
                  "country": "France",
                  "country_code": "FR",
                  "config": "ss://YWVzLTI1Ni1nY206@192.0.2.40:8388#ss-nopass",
                  "enabled": true
                },
                {
                  "id": "vless-bad-port",
                  "country": "United States",
                  "country_code": "US",
                  "config": "vless://11111111-1111-1111-1111-111111111111@203.0.113.50:99999?security=tls#bad-port",
                  "enabled": true
                },
                {
                  "id": "socks-no-port",
                  "country": "Canada",
                  "country_code": "CA",
                  "config": "socks5://192.0.2.99#no-port",
                  "enabled": true
                }
              ]
            }
        """.trimIndent()

        val result = RemoteServerManifestParser.parse(
            json = manifestJson,
            isAetherSupported = true,
        )

        assertEquals(2, result.servers.size)
        assertEquals(5, result.skippedCount)
        assertEquals(listOf("wg-valid", "hy2-valid"), result.servers.map { it.id })

        val rejectedByOutbound = RemoteServerManifestParser.parse(
            json = manifestJson,
            isAetherSupported = true,
            verifyOutbound = { it.configType != EConfigType.WIREGUARD },
        )
        assertEquals(listOf("hy2-valid"), rejectedByOutbound.servers.map { it.id })
    }
}
