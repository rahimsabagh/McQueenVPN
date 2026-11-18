package com.v2ray.ang.mcqueen

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.V2rayConfig.OutboundBean.StreamSettingsBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.service.DialerEndpointPolicy
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class Phase6SecurityHardeningTest {

    @Test
    fun `manifest parser rejects insecure unpinned TLS entries and allows verified or pinned entries`() {
        val manifestJson = """
            {
              "version": 1,
              "updated_at": "2026-11-17T00:00:00Z",
              "servers": [
                {
                  "id": "de-insecure-vless",
                  "country": "Germany",
                  "country_code": "DE",
                  "config": "vless://11111111-1111-1111-1111-111111111111@203.0.113.10:443?security=tls&allowInsecure=1#de-insecure",
                  "enabled": true
                },
                {
                  "id": "fr-insecure-hy2",
                  "country": "France",
                  "country_code": "FR",
                  "config": "hysteria2://pass@203.0.113.11:443/?insecure=1#fr-insecure",
                  "enabled": true
                },
                {
                  "id": "nl-verified-vless",
                  "country": "Netherlands",
                  "country_code": "NL",
                  "config": "vless://22222222-2222-2222-2222-222222222222@203.0.113.12:443?security=tls#nl-verified",
                  "enabled": true
                }
              ]
            }
        """.trimIndent()

        val result = RemoteServerManifestParser.parse(manifestJson)
        assertEquals(1, result.servers.size)
        assertEquals(2, result.skippedCount)
        assertEquals("nl-verified-vless", result.servers.single().id)

        val unpinnedInsecure = ProfileItem(
            configType = EConfigType.VMESS,
            server = "203.0.113.15",
            serverPort = "443",
            password = "11111111-1111-1111-1111-111111111111",
            security = AppConfig.TLS,
            insecure = true,
            pinnedCA256 = null,
        )
        assertFalse(RemoteServerManifestParser.isProfileUsable(unpinnedInsecure))

        val pinnedProfile = unpinnedInsecure.copy(
            pinnedCA256 = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        )
        assertTrue(RemoteServerManifestParser.isProfileUsable(pinnedProfile))
    }

    @Test
    fun `outbound builder never emits allowInsecure true for TLS or REALITY`() {
        val tlsStream = StreamSettingsBean()
        val insecureTlsProfile = ProfileItem(
            configType = EConfigType.VLESS,
            server = "203.0.113.20",
            serverPort = "443",
            security = AppConfig.TLS,
            sni = "vpn.example.com",
            insecure = true,
            pinnedCA256 = null,
        )
        CoreOutboundBuilder.populateTlsSettings(tlsStream, insecureTlsProfile, null)
        assertNotNull(tlsStream.tlsSettings)
        assertFalse(tlsStream.tlsSettings!!.allowInsecure)

        val realityStream = StreamSettingsBean()
        val insecureRealityProfile = insecureTlsProfile.copy(
            security = AppConfig.REALITY,
            publicKey = "pubkey123",
        )
        CoreOutboundBuilder.populateTlsSettings(realityStream, insecureRealityProfile, null)
        assertNotNull(realityStream.realitySettings)
        assertFalse(realityStream.realitySettings!!.allowInsecure)
    }

    @Test
    fun `loopback or private http check rejects substring spoofing attacks`() {
        assertTrue(Utils.isLoopbackOrPrivateHttpUrl("http://127.0.0.1:8080/sub"))
        assertTrue(Utils.isLoopbackOrPrivateHttpUrl("http://localhost:3000/sub"))
        assertTrue(Utils.isLoopbackOrPrivateHttpUrl("http://[::1]:8080/sub"))
        assertTrue(Utils.isLoopbackOrPrivateHttpUrl("http://192.168.1.10/sub"))
        assertTrue(Utils.isLoopbackOrPrivateHttpUrl("http://10.0.0.2:8080/sub"))

        assertFalse(Utils.isLoopbackOrPrivateHttpUrl("http://attacker.com/127.0.0.1"))
        assertFalse(Utils.isLoopbackOrPrivateHttpUrl("http://127.0.0.1.attacker.com/sub"))
        assertFalse(Utils.isLoopbackOrPrivateHttpUrl("http://attacker.com/?redirect=127.0.0.1"))
        assertFalse(Utils.isLoopbackOrPrivateHttpUrl("http://user:127.0.0.1@attacker.com/sub"))
        assertFalse(Utils.isLoopbackOrPrivateHttpUrl("http://8.8.8.8/sub"))
    }

    @Test
    fun `HttpUtil strips sensitive headers on cross-origin redirect and blocks unsafe redirects`() {
        val originServer = MockWebServer()
        val targetServer = MockWebServer()
        originServer.start()
        targetServer.start()
        try {
            val client = HttpUtil.buildOkHttpClient(5_000, 0, null, null, followRedirects = true)
            assertFalse(client.followSslRedirects)

            // Cross-origin redirect (different port -> different origin)
            originServer.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", targetServer.url("/dest").toString())
                    .build()
            )
            targetServer.enqueue(MockResponse(code = 200, body = "cross-origin-ok"))

            val body = HttpUtil.getUrlContentWithUserAgent(
                UrlContentRequest(
                    url = originServer.url("/source").toString(),
                    timeout = 5_000,
                    requestHeaders = """{"Authorization":"Bearer top-secret","Cookie":"sid=secret","X-Api-Key":"secret-key"}""",
                )
            )
            assertEquals("cross-origin-ok", body)

            val firstHop = originServer.takeRequest()
            assertEquals("Bearer top-secret", firstHop.headers["Authorization"])
            assertEquals("sid=secret", firstHop.headers["Cookie"])
            assertEquals("secret-key", firstHop.headers["X-Api-Key"])

            val secondHop = targetServer.takeRequest()
            assertNull(secondHop.headers["Authorization"])
            assertNull(secondHop.headers["Cookie"])
            assertNull(secondHop.headers["X-Api-Key"])

            // Non-HTTP(S) redirect target must be rejected
            originServer.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", "file:///etc/passwd")
                    .build()
            )
            assertThrows(IOException::class.java) {
                HttpUtil.getUrlContentWithUserAgent(
                    UrlContentRequest(
                        url = originServer.url("/bad-redirect").toString(),
                        timeout = 5_000,
                    )
                )
            }

            // HTTPS -> HTTP downgrade redirect must be blocked
            val downgradeError = assertThrows(IOException::class.java) {
                HttpUtil.resolveAndValidateRedirect(
                    currentUrl = "https://vpn.example.com/sub",
                    location = "http://vpn.example.com/sub"
                )
            }
            assertTrue(downgradeError.message?.contains("HTTPS downgrade") == true)

            // Same-origin relative redirect preserves origin; cross-scheme upgrade drops origin
            val (_, sameOrigin) = HttpUtil.resolveAndValidateRedirect(
                currentUrl = "https://vpn.example.com/start",
                location = "/next"
            )
            assertTrue(sameOrigin)
            val (_, upgradedOrigin) = HttpUtil.resolveAndValidateRedirect(
                currentUrl = "http://vpn.example.com/start",
                location = "https://vpn.example.com/next"
            )
            assertFalse(upgradedOrigin)
        } finally {
            originServer.close()
            targetServer.close()
        }
    }

    @Test
    fun `HttpUtil redacts credentials paths queries and fragments in logged URLs`() {
        val redacted = HttpUtil.redactUrlForLogging("https://user:pass@vpn.example.com:8443/sub/secret-token?auth=abc#frag")
        assertEquals("https://vpn.example.com:8443/<redacted>", redacted)
        assertFalse(redacted.contains("user"))
        assertFalse(redacted.contains("pass"))
        assertFalse(redacted.contains("secret-token"))
        assertFalse(redacted.contains("auth=abc"))
        assertFalse(redacted.contains("frag"))

        val ipv6Redacted = HttpUtil.redactUrlForLogging("https://admin:pw@[::1]:9443/private?k=1")
        assertEquals("https://[::1]:9443/<redacted>", ipv6Redacted)
        assertFalse(ipv6Redacted.contains("admin"))
        assertFalse(ipv6Redacted.contains("pw"))
        assertFalse(ipv6Redacted.contains("k=1"))

        assertEquals("<empty>", HttpUtil.redactUrlForLogging("   "))
        assertEquals("<redacted-url>", HttpUtil.redactUrlForLogging("not a url with spaces"))
    }

    @Test
    fun `DialerEndpointPolicy enforces loopback endpoints and locks WebView navigation`() {
        assertEquals("http://127.0.0.1:10809/", DialerEndpointPolicy.parseLoopbackDialerUrl("127.0.0.1:10809"))
        assertEquals("http://localhost:10809/", DialerEndpointPolicy.parseLoopbackDialerUrl("localhost:10809"))
        assertEquals("http://[::1]:10809/", DialerEndpointPolicy.parseLoopbackDialerUrl("[::1]:10809"))

        assertNull(DialerEndpointPolicy.parseLoopbackDialerUrl("evil.com:10809"))
        assertNull(DialerEndpointPolicy.parseLoopbackDialerUrl("0.0.0.0:10809"))
        assertNull(DialerEndpointPolicy.parseLoopbackDialerUrl("127.0.0.1"))
        assertNull(DialerEndpointPolicy.parseLoopbackDialerUrl("127.0.0.1:0"))
        assertNull(DialerEndpointPolicy.parseLoopbackDialerUrl("127.0.0.1:70000"))
        assertNull(DialerEndpointPolicy.parseLoopbackDialerUrl("127.0.0.1:10809@evil.com"))
        assertNull(DialerEndpointPolicy.parseLoopbackDialerUrl("127.0.0.1:10809/path"))
        assertNull(DialerEndpointPolicy.parseLoopbackDialerUrl("http://127.0.0.1:10809"))

        val baseUrl = "http://127.0.0.1:10809/"
        assertTrue(DialerEndpointPolicy.isAllowedTopLevelNavigation(baseUrl, "http://127.0.0.1:10809/index.html"))
        assertFalse(DialerEndpointPolicy.isAllowedTopLevelNavigation(baseUrl, "http://127.0.0.1:9999/"))
        assertFalse(DialerEndpointPolicy.isAllowedTopLevelNavigation(baseUrl, "https://127.0.0.1:10809/"))
        assertFalse(DialerEndpointPolicy.isAllowedTopLevelNavigation(baseUrl, "http://evil.com/"))
        assertFalse(DialerEndpointPolicy.isAllowedTopLevelNavigation(baseUrl, "file:///etc/passwd"))

        assertTrue(DialerEndpointPolicy.isBlockedSubresourceScheme("file"))
        assertTrue(DialerEndpointPolicy.isBlockedSubresourceScheme("content"))
        assertTrue(DialerEndpointPolicy.isBlockedSubresourceScheme("javascript"))
        assertTrue(DialerEndpointPolicy.isBlockedSubresourceScheme(null))
        assertFalse(DialerEndpointPolicy.isBlockedSubresourceScheme("http"))
        assertFalse(DialerEndpointPolicy.isBlockedSubresourceScheme("https"))
        assertFalse(DialerEndpointPolicy.isBlockedSubresourceScheme("ws"))
        assertFalse(DialerEndpointPolicy.isBlockedSubresourceScheme("wss"))
    }
}
