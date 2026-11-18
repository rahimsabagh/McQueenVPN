package com.v2ray.ang.mcqueen

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AetherIdentityProvisioner
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.handler.WebDavManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

class Phase3NetworkAndManifestTest {

    private class InMemoryManifestLocalStore(
        initialUrl: String? = null,
        initialToken: String? = null,
    ) : ManifestLocalStore {
        var storedUrl: String? = initialUrl
        var storedToken: String? = initialToken
        var storedManifestJson: String? = null
        var storedSummaryJson: String? = null
        var storedLastSyncAtMs: Long = 0L
        var storedValidatorState: ManifestValidatorState? = null
        val persistedServers = linkedMapOf<String, ProfileItem>()
        var persistCallCount: Int = 0
        var aetherSupported: Boolean = true

        override fun getConfiguredUrlRaw(): String? = storedUrl
        override fun setConfiguredUrlRaw(url: String) {
            storedUrl = url
        }

        override fun getAuthTokenRaw(): String? = storedToken
        override fun setAuthTokenRaw(token: String) {
            storedToken = token
        }

        override fun getManifestCacheJson(): String? = storedManifestJson
        override fun setManifestCacheJson(json: String) {
            storedManifestJson = json
        }

        override fun getSummaryCacheJson(): String? = storedSummaryJson
        override fun setSummaryCacheJson(json: String) {
            storedSummaryJson = json
        }

        override fun getLastSyncAtMs(): Long = storedLastSyncAtMs
        override fun setLastSyncAtMs(timestampMs: Long) {
            storedLastSyncAtMs = timestampMs
        }

        override fun getValidatorState(): ManifestValidatorState? = storedValidatorState
        override fun setValidatorState(state: ManifestValidatorState?) {
            storedValidatorState = state
        }

        override fun getSubscriptionServerList(): List<String> = persistedServers.keys.toList()

        override fun persistServers(servers: List<ValidatedServer>, guidProvider: (String, String) -> String) {
            persistCallCount++
            if (servers.isEmpty()) return
            persistedServers.clear()
            servers.forEach { server ->
                val guid = guidProvider(server.countryCode, server.id)
                persistedServers[guid] = server.profile
            }
        }

        override fun isAetherBinarySupported(context: Context): Boolean = aetherSupported

        override fun isOutboundBuildable(profile: ProfileItem): Boolean = true

        override fun log(priority: Int, message: String, throwable: Throwable?) = Unit
    }

    private fun testContext(): Context = mock()

    private fun sampleManifestJson(
        country: String = "Germany",
        countryCode: String = "DE",
        id: String = "de-01",
        updatedAt: String = "2026-11-17T10:00:00Z",
    ): String = """
        {
          "version": 1,
          "updated_at": "$updatedAt",
          "servers": [
            {
              "id": "$id",
              "country": "$country",
              "country_code": "$countryCode",
              "config": "vless://11111111-1111-1111-1111-111111111111@203.0.113.10:443?security=tls#$id",
              "enabled": true
            }
          ]
        }
    """.trimIndent()

    private fun newRepository(
        store: InMemoryManifestLocalStore,
        clockMs: () -> Long = { 1_700_000_000_000L },
        retryBaseDelayMs: Long = 10L,
        httpClient: OkHttpClient = McQueenServerRepository.defaultHttpClient(),
    ): McQueenServerRepository {
        return McQueenServerRepository(
            httpClient = httpClient,
            identityProvisioner = AetherIdentityProvisioner(),
            localStore = store,
            clockMs = clockMs,
            retryBaseDelayMs = retryBaseDelayMs,
        )
    }

    // 1. Repeated requests reuse the shared ConnectionPool and Dispatcher
    @Test
    fun `1_repeated_requests_and_app_clients_reuse_shared_connection_pool_and_dispatcher`() {
        val base = HttpUtil.baseClient()
        val httpUtilClient1 = HttpUtil.buildOkHttpClient(5_000, 0, null, null, followRedirects = true)
        val httpUtilClient2 = HttpUtil.buildOkHttpClient(15_000, 0, null, null, followRedirects = false)
        val repoClient = McQueenServerRepository.defaultHttpClient()
        val updateClient = AppUpdateChecker.defaultHttpClient()

        WebDavManager.init(
            WebDavConfig(
                baseUrl = "https://dav.example.invalid",
                username = "user",
                password = "secret",
                timeoutSeconds = 20L,
            )
        )
        val webDavField = WebDavManager::class.java.getDeclaredField("client").apply { isAccessible = true }
        val webDavClient = webDavField.get(WebDavManager) as OkHttpClient

        listOf(httpUtilClient1, httpUtilClient2, repoClient, updateClient, webDavClient).forEach { client ->
            assertSame(base.connectionPool, client.connectionPool)
            assertSame(base.dispatcher, client.dispatcher)
        }

        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(code = 200, body = "first"))
            server.enqueue(MockResponse(code = 200, body = "second"))

            val url = server.url("/ping").toString()
            val first = HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = 5_000))
            val second = HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = 5_000))

            assertEquals("first", first)
            assertEquals("second", second)

            val req1 = server.takeRequest()
            val req2 = server.takeRequest()
            assertFalse("close".equals(req1.headers["Connection"], ignoreCase = true))
            assertFalse("close".equals(req2.headers["Connection"], ignoreCase = true))
            assertEquals(0, req1.connectionIndex)
            assertEquals(0, req2.connectionIndex)
            assertTrue(base.connectionPool.connectionCount() >= 1)
        } finally {
            server.close()
        }
    }

    // 2. Different per-request timeout values remain respected
    @Test
    fun `2_different_timeout_values_remain_respected_per_request`() {
        val fastClient = HttpUtil.buildOkHttpClient(250, 0, null, null, followRedirects = true)
        val slowClient = HttpUtil.buildOkHttpClient(12_000, 0, null, null, followRedirects = true)

        assertEquals(250, fastClient.connectTimeoutMillis)
        assertEquals(250, fastClient.readTimeoutMillis)
        assertEquals(250, fastClient.writeTimeoutMillis)

        assertEquals(12_000, slowClient.connectTimeoutMillis)
        assertEquals(12_000, slowClient.readTimeoutMillis)
        assertEquals(12_000, slowClient.writeTimeoutMillis)

        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .headersDelay(350, TimeUnit.MILLISECONDS)
                    .body("delayed-response")
                    .build()
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .headersDelay(250, TimeUnit.MILLISECONDS)
                    .body("completed-response")
                    .build()
            )

            val url = server.url("/slow").toString()
            val timedOutResult = HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = 100))
            assertNull(timedOutResult)

            val okResult = HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = 2_000))
            assertEquals("completed-response", okResult)
        } finally {
            server.close()
        }
    }

    // 3. Proxy configurations and auth do not leak across requests
    @Test
    fun `3_proxy_configurations_and_auth_do_not_leak_across_requests`() {
        val proxiedClient = HttpUtil.buildOkHttpClient(
            timeout = 5_000,
            httpPort = 18_080,
            proxyUsername = "proxyUser",
            proxyPassword = "proxyPassword",
            followRedirects = true,
        )
        val directClient = HttpUtil.buildOkHttpClient(
            timeout = 5_000,
            httpPort = 0,
            proxyUsername = null,
            proxyPassword = null,
            followRedirects = true,
        )

        val proxy = proxiedClient.proxy
        assertNotNull(proxy)
        assertEquals(Proxy.Type.HTTP, proxy?.type())
        assertEquals(InetSocketAddress(AppConfig.LOOPBACK, 18_080), proxy?.address())
        assertTrue(proxiedClient.proxyAuthenticator !== Authenticator.NONE)

        assertNull(directClient.proxy)
        assertSame(Authenticator.NONE, directClient.proxyAuthenticator)
        assertNull(HttpUtil.baseClient().proxy)
        assertSame(Authenticator.NONE, HttpUtil.baseClient().proxyAuthenticator)

        val proxyServer = MockWebServer()
        val directServer = MockWebServer()
        proxyServer.start()
        directServer.start()
        try {
            proxyServer.enqueue(MockResponse(code = 200, body = "via-proxy"))
            directServer.enqueue(MockResponse(code = 200, body = "direct"))

            val proxiedBody = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = "http://remote.invalid/sub",
                    timeout = 3_000,
                    httpPort = proxyServer.port,
                    proxyUsername = "alice",
                    proxyPassword = "secret",
                )
            )
            assertEquals("via-proxy", proxiedBody)
            val proxyReq = proxyServer.takeRequest()
            assertTrue(proxyReq.headers["Proxy-Authorization"]?.startsWith("Basic ") == true)

            val directBody = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = directServer.url("/direct").toString(),
                    timeout = 3_000,
                    httpPort = 0,
                )
            )
            assertEquals("direct", directBody)
            val directReq = directServer.takeRequest()
            assertNull(directReq.headers["Proxy-Authorization"])
        } finally {
            proxyServer.close()
            directServer.close()
        }
    }

    // 4. HTTP 200 stores ETag and Last-Modified cleanly
    @Test
    fun `4_http_200_stores_etag_and_last_modified_cleanly`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/servers.json").toString()
            val store = InMemoryManifestLocalStore(initialUrl = url)
            val repo = newRepository(store, clockMs = { 123_456L })
            val json = sampleManifestJson()

            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("ETag", "\"etag-v1\"")
                    .addHeader("Last-Modified", "Tue, 17 Nov 2026 10:00:00 GMT")
                    .body(json)
                    .build()
            )

            val outcome = repo.syncFromGitHub(testContext())
            assertTrue(outcome is ManifestSyncOutcome.Success)
            val success = outcome as ManifestSyncOutcome.Success
            assertFalse(success.fromCache)
            assertEquals(1, success.manifest.servers.size)
            assertEquals(123_456L, store.storedLastSyncAtMs)
            assertEquals(json, store.storedManifestJson)

            val expectedScope = McQueenServerRepository.manifestValidatorScopeHash(url, null)
            assertEquals(
                ManifestValidatorState(
                    scopeHash = expectedScope,
                    etag = "\"etag-v1\"",
                    lastModified = "Tue, 17 Nov 2026 10:00:00 GMT",
                ),
                store.storedValidatorState,
            )
        } finally {
            server.close()
        }
    }

    // 5. Subsequent sync sends If-None-Match and If-Modified-Since without Cache-Control: no-cache
    @Test
    fun `5_subsequent_sync_sends_if_none_match_and_if_modified_since`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/servers.json").toString()
            val store = InMemoryManifestLocalStore(initialUrl = url)
            val repo = newRepository(store)

            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("ETag", "\"v1\"")
                    .addHeader("Last-Modified", "Tue, 17 Nov 2026 09:00:00 GMT")
                    .body(sampleManifestJson())
                    .build()
            )
            server.enqueue(MockResponse.Builder().code(304).build())

            repo.syncFromGitHub(testContext())
            val firstReq = server.takeRequest()
            assertNull(firstReq.headers["If-None-Match"])
            assertNull(firstReq.headers["If-Modified-Since"])
            assertNull(firstReq.headers["Cache-Control"])

            repo.syncFromGitHub(testContext())
            val secondReq = server.takeRequest()
            assertEquals("\"v1\"", secondReq.headers["If-None-Match"])
            assertEquals("Tue, 17 Nov 2026 09:00:00 GMT", secondReq.headers["If-Modified-Since"])
            assertNull(secondReq.headers["Cache-Control"])
        } finally {
            server.close()
        }
    }

    // 6. HTTP 304 reuses existing cached manifest and updates sync timestamp and validators
    @Test
    fun `6_http_304_reuses_cached_manifest_and_updates_sync_timestamp`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/servers.json").toString()
            val store = InMemoryManifestLocalStore(initialUrl = url)
            val clock = AtomicLong(1_000L)
            val repo = newRepository(store, clockMs = { clock.get() })

            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("ETag", "\"v1\"")
                    .addHeader("Last-Modified", "Tue, 17 Nov 2026 09:00:00 GMT")
                    .body(sampleManifestJson(country = "Germany", countryCode = "DE", id = "de-01"))
                    .build()
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(304)
                    .addHeader("ETag", "\"v1-refreshed\"")
                    .build()
            )

            val initial = repo.syncFromGitHub(testContext()) as ManifestSyncOutcome.Success
            assertEquals(1_000L, store.storedLastSyncAtMs)

            clock.set(99_000L)
            val second = repo.syncFromGitHub(testContext())
            assertTrue(second is ManifestSyncOutcome.Success)
            val cached = second as ManifestSyncOutcome.Success
            assertTrue(cached.fromCache)
            assertEquals(initial.manifest.countries, cached.manifest.countries)
            assertEquals(99_000L, store.storedLastSyncAtMs)
            assertEquals("\"v1-refreshed\"", store.storedValidatorState?.etag)
            assertEquals("Tue, 17 Nov 2026 09:00:00 GMT", store.storedValidatorState?.lastModified)
        } finally {
            server.close()
        }
    }

    // 7. HTTP 304 does not trigger unnecessary server writes or change broadcasts
    @Test
    fun `7_http_304_skips_persist_servers_and_suppresses_broadcast`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/servers.json").toString()
            val store = InMemoryManifestLocalStore(initialUrl = url)
            val repo = newRepository(store)

            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("ETag", "\"v1\"")
                    .body(sampleManifestJson())
                    .build()
            )
            server.enqueue(MockResponse.Builder().code(304).build())

            val first = repo.syncFromGitHub(testContext()) as ManifestSyncOutcome.Success
            assertTrue(McQueenManifestUpdater.shouldBroadcastManifestChange(first))
            assertEquals(1, store.persistCallCount)

            val second = repo.syncFromGitHub(testContext()) as ManifestSyncOutcome.Success
            assertTrue(second.fromCache)
            assertFalse(McQueenManifestUpdater.shouldBroadcastManifestChange(second))
            assertEquals(1, store.persistCallCount)
        } finally {
            server.close()
        }
    }

    // 8. HTTP 304 without valid local manifest cache triggers unconditional retry once
    @Test
    fun `8_http_304_without_valid_local_cache_performs_single_unconditional_retry`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/servers.json").toString()
            val scopeHash = McQueenServerRepository.manifestValidatorScopeHash(url, null)
            val store = InMemoryManifestLocalStore(initialUrl = url).apply {
                // Validator exists, but manifest cache JSON and server list are empty
                storedValidatorState = ManifestValidatorState(
                    scopeHash = scopeHash,
                    etag = "\"stale-etag\"",
                    lastModified = "Mon, 16 Nov 2026 00:00:00 GMT",
                )
            }
            val repo = newRepository(store)

            // Case A: 304 -> unconditional retry -> 200 recovery
            server.enqueue(MockResponse.Builder().code(304).build())
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("ETag", "\"fresh-etag\"")
                    .body(sampleManifestJson())
                    .build()
            )

            val recovered = repo.syncFromGitHub(testContext())
            assertTrue(recovered is ManifestSyncOutcome.Success)
            val success = recovered as ManifestSyncOutcome.Success
            assertFalse(success.fromCache)
            assertEquals(2, server.requestCount)

            val condReq = server.takeRequest()
            assertEquals("\"stale-etag\"", condReq.headers["If-None-Match"])
            val retryReq = server.takeRequest()
            assertNull(retryReq.headers["If-None-Match"])
            assertNull(retryReq.headers["If-Modified-Since"])
            assertEquals("\"fresh-etag\"", store.storedValidatorState?.etag)

            // Case B: clear local cache again and return 304 twice -> must fail after one retry (no loop)
            store.storedManifestJson = null
            store.storedSummaryJson = null
            store.persistedServers.clear()
            server.enqueue(MockResponse.Builder().code(304).build())
            server.enqueue(MockResponse.Builder().code(304).build())

            val failed = repo.syncFromGitHub(testContext())
            assertTrue(failed is ManifestSyncOutcome.Failure)
            assertEquals(4, server.requestCount)
        } finally {
            server.close()
        }
    }

    // 9. Server returning no ETag or Last-Modified still works normally
    @Test
    fun `9_server_without_etag_or_last_modified_works_cleanly`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/servers.json").toString()
            val store = InMemoryManifestLocalStore(initialUrl = url)
            val repo = newRepository(store)

            server.enqueue(MockResponse(code = 200, body = sampleManifestJson(id = "de-01")))
            server.enqueue(MockResponse(code = 200, body = sampleManifestJson(country = "Netherlands", countryCode = "NL", id = "nl-01")))

            val first = repo.syncFromGitHub(testContext()) as ManifestSyncOutcome.Success
            assertFalse(first.fromCache)
            assertNull(store.storedValidatorState)

            val second = repo.syncFromGitHub(testContext()) as ManifestSyncOutcome.Success
            assertFalse(second.fromCache)
            assertEquals(listOf("NL"), second.manifest.countries.map { it.countryCode })

            val req1 = server.takeRequest()
            val req2 = server.takeRequest()
            assertNull(req1.headers["If-None-Match"])
            assertNull(req1.headers["If-Modified-Since"])
            assertNull(req2.headers["If-None-Match"])
            assertNull(req2.headers["If-Modified-Since"])
        } finally {
            server.close()
        }
    }

    // 10. Changed manifest content returns HTTP 200 and updates servers and validators cleanly
    @Test
    fun `10_changed_manifest_content_returns_200_and_updates_servers_and_validators`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/servers.json").toString()
            val store = InMemoryManifestLocalStore(initialUrl = url)
            val repo = newRepository(store)

            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("ETag", "\"v1\"")
                    .body(sampleManifestJson(country = "Germany", countryCode = "DE", id = "de-01"))
                    .build()
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("ETag", "\"v2\"")
                    .addHeader("Last-Modified", "Wed, 18 Nov 2026 12:00:00 GMT")
                    .body(sampleManifestJson(country = "Netherlands", countryCode = "NL", id = "nl-01"))
                    .build()
            )

            val first = repo.syncFromGitHub(testContext()) as ManifestSyncOutcome.Success
            assertEquals(listOf("DE"), first.manifest.countries.map { it.countryCode })

            val second = repo.syncFromGitHub(testContext()) as ManifestSyncOutcome.Success
            assertFalse(second.fromCache)
            assertTrue(McQueenManifestUpdater.shouldBroadcastManifestChange(second))
            assertEquals(2, store.persistCallCount)
            assertEquals(listOf("NL"), second.manifest.countries.map { it.countryCode })
            assertEquals(1, store.persistedServers.size)
            assertEquals("\"v2\"", store.storedValidatorState?.etag)
            assertEquals("Wed, 18 Nov 2026 12:00:00 GMT", store.storedValidatorState?.lastModified)
        } finally {
            server.close()
        }
    }

    // 11. Changed URL or auth token invalidates/scopes old validators and never stores plaintext tokens
    @Test
    fun `11_changed_url_or_auth_token_invalidates_validators_and_never_stores_plaintext_token`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val urlA = server.url("/a/servers.json").toString()
            val urlB = server.url("/b/servers.json").toString()
            val secretToken = "ghp_super_secret_token_value_123"
            val store = InMemoryManifestLocalStore(initialUrl = urlA, initialToken = secretToken)
            val repo = newRepository(store)

            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("ETag", "\"etag-token-a\"")
                    .body(sampleManifestJson())
                    .build()
            )

            repo.syncFromGitHub(testContext())
            val req1 = server.takeRequest()
            assertEquals("Bearer $secretToken", req1.headers["Authorization"])
            assertNotNull(store.storedValidatorState)

            // Serialized validator must never contain plaintext auth token
            val serializedValidator = JsonUtil.toJson(store.storedValidatorState!!)
            assertFalse(serializedValidator.contains(secretToken))

            // Changing auth token override isolates scopeHash so old validator is not sent
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("ETag", "\"etag-token-b\"")
                    .body(sampleManifestJson())
                    .build()
            )
            repo.syncFromGitHub(testContext(), authTokenOverride = "other_token_456")
            val req2 = server.takeRequest()
            assertEquals("Bearer other_token_456", req2.headers["Authorization"])
            assertNull(req2.headers["If-None-Match"])

            // Explicit config save / reset invalidates stored validator & sync timestamp
            assertTrue(repo.saveCustomManifestConfig(url = urlB))
            assertNull(store.storedValidatorState)
            assertEquals(0L, store.storedLastSyncAtMs)

            server.enqueue(MockResponse(code = 200, body = sampleManifestJson()))
            repo.syncFromGitHub(testContext())
            val req3 = server.takeRequest()
            assertNull(req3.headers["If-None-Match"])

            repo.resetCustomUrl()
            assertNull(store.storedValidatorState)
            assertEquals(0L, store.storedLastSyncAtMs)
        } finally {
            server.close()
        }
    }

    // 12. Redirects and HTTPS security restrictions remain intact
    @Test
    fun `12_redirects_and_https_security_restrictions_remain_intact`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            // HttpUtil manual redirect without Connection: close delivers custom header + response headers
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", "/final")
                    .build()
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("X-Custom-Out", "ok")
                    .body("redirect-ok")
                    .build()
            )
            var capturedHeader: String? = null
            val body = HttpUtil.getUrlContentWithUserAgent(
                request = UrlContentRequest(
                    url = server.url("/start").toString(),
                    timeout = 5_000,
                    requestHeaders = """{"X-Test-Header":"phase3"}""",
                ),
                onResponseHeaders = { headers ->
                    capturedHeader = headers["X-Custom-Out"]
                },
            )
            assertEquals("redirect-ok", body)
            assertEquals("ok", capturedHeader)

            val redirectReq1 = server.takeRequest()
            val redirectReq2 = server.takeRequest()
            assertEquals("phase3", redirectReq1.headers["X-Test-Header"])
            assertEquals("phase3", redirectReq2.headers["X-Test-Header"])
            assertFalse("close".equals(redirectReq1.headers["Connection"], ignoreCase = true))
            assertFalse("close".equals(redirectReq2.headers["Connection"], ignoreCase = true))

            // McQueenServerRepository still blocks 302 downgrade to non-HTTPS remote URL without retrying
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", "http://insecure.example.invalid/servers.json")
                    .build()
            )
            val store = InMemoryManifestLocalStore(initialUrl = server.url("/manifest.json").toString())
            val repo = newRepository(store)
            val blockedOutcome = repo.syncFromGitHub(testContext())
            assertTrue(blockedOutcome is ManifestSyncOutcome.Failure)
            assertEquals(3, server.requestCount)

            // Non-HTTPS remote URL with token fails immediately with no network request
            var refusedCleartext = false
            try {
                repo.fetchManifestConditional(
                    url = "http://remote.example.com/servers.json",
                    authToken = "secret",
                )
            } catch (_: IOException) {
                refusedCleartext = true
            }
            assertTrue(refusedCleartext)
            assertEquals(3, server.requestCount)
        } finally {
            server.close()
        }
    }

    // 13. Coroutine cancellation interrupts retries cleanly
    @Test
    fun `13_coroutine_cancellation_interrupts_retry_delay_cleanly`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/servers.json").toString()
            val store = InMemoryManifestLocalStore(initialUrl = url)
            val repo = newRepository(store, retryBaseDelayMs = 10_000L)

            server.enqueue(MockResponse(code = 503, body = "Service Unavailable"))
            server.enqueue(MockResponse(code = 200, body = sampleManifestJson()))

            val elapsedMs = measureTimeMillis {
                val job = async(Dispatchers.IO) {
                    repo.fetchManifestConditional(url = url, maxAttempts = 3)
                }
                // Wait briefly for attempt #1 (503) to reach suspend retry delay()
                while (server.requestCount < 1) {
                    delay(10L)
                }
                delay(40L)
                job.cancel()
                try {
                    job.await()
                } catch (_: CancellationException) {
                    // Expected
                }
                assertTrue(job.isCancelled)
            }

            assertEquals(1, server.requestCount)
            assertTrue(elapsedMs < 3_000L, "Expected immediate coroutine cancellation without sleeping 10s (actual=${elapsedMs}ms)")
        } finally {
            server.close()
        }
    }

    // 14. Failed network requests do not corrupt existing valid cache
    @Test
    fun `14_failed_network_requests_preserve_existing_valid_cache`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/servers.json").toString()
            val store = InMemoryManifestLocalStore(initialUrl = url)
            val repo = newRepository(store)
            val initialJson = sampleManifestJson(country = "Germany", countryCode = "DE", id = "de-01")

            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("ETag", "\"v1\"")
                    .body(initialJson)
                    .build()
            )
            assertTrue(repo.syncFromGitHub(testContext()) is ManifestSyncOutcome.Success)
            val savedSummary = store.storedSummaryJson
            val savedValidator = store.storedValidatorState
            val savedGuids = store.persistedServers.keys.toList()

            // Simulate 5xx outages and malformed payload
            server.enqueue(MockResponse(code = 500, body = "err1"))
            server.enqueue(MockResponse(code = 502, body = "err2"))
            server.enqueue(MockResponse(code = 503, body = "err3"))
            val outageFailure = repo.syncFromGitHub(testContext())
            assertTrue(outageFailure is ManifestSyncOutcome.Failure)

            server.enqueue(MockResponse(code = 200, body = "{corrupt-json"))
            val corruptFailure = repo.syncFromGitHub(testContext())
            assertTrue(corruptFailure is ManifestSyncOutcome.Failure)

            // Cached manifest, summary, validators, and servers must remain intact
            assertEquals(initialJson, store.storedManifestJson)
            assertEquals(savedSummary, store.storedSummaryJson)
            assertEquals(savedValidator, store.storedValidatorState)
            assertEquals(savedGuids, store.persistedServers.keys.toList())
        } finally {
            server.close()
        }
    }

    // 15. Periodic manifest refresh remains functional without breaking existing scheduling/retry behavior
    @Test
    fun `15_periodic_manifest_refresh_policy_and_transient_429_retry_vs_404_contract_intact`() = runBlocking {
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, McQueenManifestUpdater.periodicWorkPolicy(forceReschedule = false))
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, McQueenManifestUpdater.periodicWorkPolicy(forceReschedule = true))

        val dummyManifest = RemoteServerManifestParser.parse(sampleManifestJson())
        assertTrue(McQueenManifestUpdater.shouldBroadcastManifestChange(ManifestSyncOutcome.Success(dummyManifest, fromCache = false)))
        assertFalse(McQueenManifestUpdater.shouldBroadcastManifestChange(ManifestSyncOutcome.Success(dummyManifest, fromCache = true)))

        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/servers.json").toString()
            val store = InMemoryManifestLocalStore(initialUrl = url)
            val repo = newRepository(store, retryBaseDelayMs = 5L)

            // Retryable 429 / 408 recover cleanly
            server.enqueue(MockResponse(code = 429, body = "rate limited"))
            server.enqueue(MockResponse(code = 408, body = "timeout"))
            server.enqueue(MockResponse(code = 200, body = sampleManifestJson()))

            val result = repo.fetchManifestConditional(url = url, maxAttempts = 3)
            assertTrue(result is McQueenServerRepository.ManifestFetchResult.Modified)
            assertEquals(3, server.requestCount)

            // Permanent 404 still fails immediately without retrying
            server.enqueue(MockResponse(code = 404, body = "Not Found"))
            var failedImmediately = false
            try {
                repo.fetchManifestConditional(url = url, maxAttempts = 3)
            } catch (_: IOException) {
                failedImmediately = true
            }
            assertTrue(failedImmediately)
            assertEquals(4, server.requestCount)
        } finally {
            server.close()
        }
    }
}
