package com.v2ray.ang.mcqueen

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import com.v2ray.ang.util.JsonUtil
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class McQueenServerConfigAndMockFetchTest {

    @Test
    fun `validates https urls, 30-minute schedule constants, my-releases branch, and unique work policies`() {
        assertEquals("my-releases", McQueenServerConfig.DEFAULT_GITHUB_BRANCH)
        assertEquals(
            "https://raw.githubusercontent.com/rahimsabagh/McQueenVPN/my-releases/servers.json",
            McQueenServerConfig.DEFAULT_SERVERS_URL,
        )
        assertEquals(30L, McQueenServerConfig.MANIFEST_REFRESH_INTERVAL_MINUTES)
        assertTrue(
            TimeUnit.MINUTES.toMillis(McQueenServerConfig.MANIFEST_REFRESH_INTERVAL_MINUTES) >=
                PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
        )
        assertEquals(
            ExistingPeriodicWorkPolicy.KEEP,
            McQueenManifestUpdater.periodicWorkPolicy(forceReschedule = false),
        )
        assertEquals(
            ExistingPeriodicWorkPolicy.UPDATE,
            McQueenManifestUpdater.periodicWorkPolicy(forceReschedule = true),
        )
        assertEquals(
            McQueenServerConfig.DEFAULT_SERVERS_URL,
            McQueenServerConfig.resolveActiveUrl(McQueenServerConfig.LEGACY_DEFAULT_SERVERS_URL),
        )
        assertEquals(
            McQueenServerConfig.DEFAULT_SERVERS_URL,
            McQueenServerConfig.resolveActiveUrl("http://example.com/servers.json"),
        )
        assertTrue(McQueenServerConfig.isValidManifestUrl(McQueenServerConfig.DEFAULT_SERVERS_URL))
        assertTrue(McQueenServerConfig.isValidManifestUrl("https://example.com/vpn/servers.json"))
        assertTrue(McQueenServerConfig.isValidManifestUrl("http://127.0.0.1:8080/servers.json"))
        assertFalse(McQueenServerConfig.isValidManifestUrl("http://example.com/servers.json"))
        assertFalse(McQueenServerConfig.isValidManifestUrl("not-a-url"))
    }

    @Test
    fun `repository servers_json matches schema, keeps credentialed entries disabled, and contains no real credentials`() {
        val candidates = listOf(File("../servers.json"), File("servers.json"))
        val manifestFile = candidates.firstOrNull { it.isFile }
            ?: error("Expected /servers.json to exist in repository root")

        val json = manifestFile.readText()
        val dto = JsonUtil.fromJsonSafe(json, RemoteServerManifestDto::class.java)
            ?: error("Failed to decode /servers.json")

        assertTrue((dto.version ?: 0) >= 1)
        val entries = dto.servers.orEmpty()
        assertTrue(entries.isNotEmpty())

        val credentialedSamples = entries.filterNot { it.config.orEmpty().startsWith("aether://") }
        assertTrue(
            credentialedSamples.all { it.enabled == false },
            "Credentialed sample entries in /servers.json must stay disabled by default",
        )
        assertTrue(
            credentialedSamples.all { entry ->
                val cfg = entry.config.orEmpty()
                cfg.contains(".invalid") || cfg.contains("203.0.113.") || cfg.contains("198.51.100.") || cfg.contains("192.0.2.")
            },
            "Credentialed sample entries in /servers.json must only use documentation/RFC5737 placeholder endpoints",
        )

        val enabledEntries = entries.filter { it.enabled == true }
        assertTrue(
            enabledEntries.all { it.config.orEmpty().startsWith("aether://") },
            "Only keyless aether:// profiles may be enabled in public /servers.json",
        )

        val parsed = RemoteServerManifestParser.parse(json, isAetherSupported = true)
        assertEquals(enabledEntries.size, parsed.servers.size)
        assertEquals(entries.size - enabledEntries.size, parsed.skippedCount)
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

    @Test
    fun `fetchManifestJson attaches Bearer token only when supplied and blocks insecure remote http`() {
        val server = MockWebServer()
        server.start()
        try {
            val payload = """{"version":1,"updated_at":"2026-11-14T00:00:00Z","servers":[]}"""
            server.enqueue(MockResponse(code = 200, body = payload))
            server.enqueue(MockResponse(code = 200, body = payload))

            val repository = McQueenServerRepository(McQueenServerRepository.defaultHttpClient())
            val url = server.url("/private/servers.json").toString()

            val publicBody = repository.fetchManifestJson(url, authToken = null)
            assertEquals(payload, publicBody)
            val publicRecorded = server.takeRequest()
            assertNull(publicRecorded.headers["Authorization"])

            val privateBody = repository.fetchManifestJson(url, authToken = "  runtime_test_token_123  ")
            assertEquals(payload, privateBody)
            val authRecorded = server.takeRequest()
            assertEquals("Bearer runtime_test_token_123", authRecorded.headers["Authorization"])

            var rejectedRemoteHttp = false
            try {
                repository.fetchManifestJson("http://example.com/servers.json", authToken = "runtime_test_token_123")
            } catch (_: IOException) {
                rejectedRemoteHttp = true
            }
            assertTrue(rejectedRemoteHttp, "Credentials must never be sent to remote cleartext HTTP URLs")
        } finally {
            server.close()
        }
    }

    @Test
    fun `defaultHttpClient blocks redirect to non-https remote url`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", "http://insecure.example.invalid/servers.json")
                    .build()
            )
            val client = McQueenServerRepository.defaultHttpClient()
            val url = server.url("/servers.json").toString()
            var blocked = false
            try {
                client.newCall(Request.Builder().url(url).build()).execute().close()
            } catch (_: IOException) {
                blocked = true
            }
            assertTrue(blocked, "Expected redirect to remote HTTP URL to be blocked")
        } finally {
            server.close()
        }
    }

    @Test
    fun `fetchManifestJson recovers from transient 5xx failure via bounded retry and does not retry 4xx`() {
        val server = MockWebServer()
        server.start()
        try {
            val validPayload = """{"version":4,"updated_at":"2026-11-14T15:26:03.347Z","servers":[]}"""
            server.enqueue(MockResponse(code = 502, body = "Bad Gateway"))
            server.enqueue(MockResponse(code = 503, body = "Service Unavailable"))
            server.enqueue(MockResponse(code = 200, body = validPayload))

            val repository = McQueenServerRepository(McQueenServerRepository.defaultHttpClient())
            val url = server.url("/servers.json").toString()

            val body = repository.fetchManifestJson(url, authToken = null, maxAttempts = 3)
            assertEquals(validPayload, body)
            assertEquals(3, server.requestCount)

            server.enqueue(MockResponse(code = 404, body = "Not Found"))
            var failedImmediatelyOn404 = false
            try {
                repository.fetchManifestJson(url, authToken = null, maxAttempts = 3)
            } catch (_: IOException) {
                failedImmediatelyOn404 = true
            }
            assertTrue(failedImmediatelyOn404, "Expected HTTP 404 to fail immediately without retry")
            assertEquals(4, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `bundled servers_json fallback loads all 87 enabled aether servers across 29 countries and produces deterministic guids`() {
        val repository = McQueenServerRepository(McQueenServerRepository.defaultHttpClient())
        val bundledJson = repository.loadBundledManifestJson(context = null)
            ?: error("Expected bundled servers.json fallback to be readable")

        val parsed = RemoteServerManifestParser.parse(bundledJson)
        assertEquals(87, parsed.servers.size)
        assertEquals(29, parsed.countries.size)
        assertEquals(0, parsed.skippedCount)

        val topCodes = parsed.countries.take(2).map { it.countryCode }
        assertEquals(listOf("IR", "UN"), topCodes)
        assertTrue(parsed.countries.all { it.availableServerCount == 3 })

        val guid1 = repository.deterministicServerGuid("DE", "de-wg")
        val guid2 = repository.deterministicServerGuid("de", "de-wg")
        val guidDifferent = repository.deterministicServerGuid("DE", "de-masque")
        assertEquals(guid1, guid2, "Deterministic GUID must be stable across refreshes for the same country and server ID")
        assertFalse(guid1 == guidDifferent, "Distinct server IDs must produce distinct GUIDs")
    }
}

