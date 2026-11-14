package com.v2ray.ang.mcqueen

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McQueenServerConfigAndMockFetchTest {

    @Test
    fun `validates https urls and loopback urls while rejecting insecure remote http`() {
        assertTrue(McQueenServerConfig.isValidManifestUrl(McQueenServerConfig.DEFAULT_SERVERS_URL))
        assertTrue(McQueenServerConfig.isValidManifestUrl("https://example.com/vpn/servers.json"))
        assertTrue(McQueenServerConfig.isValidManifestUrl("http://127.0.0.1:8080/servers.json"))
        assertFalse(McQueenServerConfig.isValidManifestUrl("http://example.com/servers.json"))
        assertFalse(McQueenServerConfig.isValidManifestUrl("not-a-url"))
    }

    @Test
    fun `fetches and parses servers json from MockWebServer`() {
        val server = MockWebServer()
        server.start()
        try {
            val payload = """
                {
                  "version": 1,
                  "updated_at": "2026-11-14T00:00:00Z",
                  "servers": [
                    {
                      "id": "de-01",
                      "country": "Germany",
                      "country_code": "DE",
                      "config": "vless://11111111-1111-1111-1111-111111111111@203.0.113.10:443?security=tls#de-01",
                      "enabled": true
                    },
                    {
                      "id": "nl-01",
                      "country": "Netherlands",
                      "country_code": "NL",
                      "config": "trojan://pass123@198.51.100.20:443?security=tls#nl-01",
                      "enabled": true
                    }
                  ]
                }
            """.trimIndent()
            server.enqueue(MockResponse(code = 200, body = payload))

            val url = server.url("/servers.json").toString()
            assertTrue(McQueenServerConfig.isValidManifestUrl(url))

            val client = OkHttpClient()
            val body = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                assertEquals(200, resp.code)
                resp.body.string()
            }

            val parsed = RemoteServerManifestParser.parse(body, isAetherSupported = true)
            assertEquals(2, parsed.countries.size)
            assertEquals(listOf("DE", "NL"), parsed.countries.map { it.countryCode })
        } finally {
            server.close()
        }
    }
}
