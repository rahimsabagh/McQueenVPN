package com.v2ray.ang.mcqueen

import com.v2ray.ang.AppConfig
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class AppUpdateCheckerTest {

    @Test
    fun `semantic version parser and comparator handle v prefix and prerelease tags properly`() {
        val beta1Lower = SemanticVersion.parse("1.0.0-beta.1")!!
        val beta1Upper = SemanticVersion.parse("V1.0.0-beta.1")!!
        val beta2 = SemanticVersion.parse("v1.0.0-beta.2")!!
        val beta10 = SemanticVersion.parse("v1.0.0-beta.10")!!
        val rc1 = SemanticVersion.parse("v1.0.0-rc.1")!!
        val stable = SemanticVersion.parse("v1.0.0")!!
        val patch = SemanticVersion.parse("1.0.1")!!

        assertEquals(0, beta1Lower.compareTo(beta1Upper))
        assertFalse(SemanticVersion.isNewer("1.0.0-beta.1", "V1.0.0-beta.1")!!)
        assertTrue(SemanticVersion.isNewer("1.0.0-beta.1", "V1.0.0-beta.2")!!)
        assertTrue(beta2 < beta10)
        assertTrue(beta10 < rc1)
        assertTrue(rc1 < stable)
        assertTrue(stable < patch)
        assertNull(SemanticVersion.parse("invalid-tag"))
    }

    @Test
    fun `official github latest release endpoint and current build version match v1_0_0_beta_4 release`() {
        assertEquals(
            "https://api.github.com/repos/rahimsabagh/McQueenVPN/releases/latest",
            AppConfig.APP_LATEST_RELEASE_API_URL,
        )
        assertEquals("1.0.0-beta.4", com.v2ray.ang.BuildConfig.VERSION_NAME)
        assertNotNull(
            SemanticVersion.parse(com.v2ray.ang.BuildConfig.VERSION_NAME),
            "Expected BuildConfig.VERSION_NAME '${com.v2ray.ang.BuildConfig.VERSION_NAME}' to be a valid semantic version",
        )
        assertEquals(764, com.v2ray.ang.BuildConfig.VERSION_CODE)
        assertTrue(SemanticVersion.isNewer("1.0.0-beta.2", com.v2ray.ang.BuildConfig.VERSION_NAME)!!)
    }

    @Test
    fun `apk selector picks matching ABI or single universal release apk over HTTPS github domains`() {
        val multiAbiAssets = listOf(
            GitHubReleaseAsset(
                name = "McQueenVPN-v1.0.0-beta.4-armeabi-v7a-release.apk",
                downloadUrl = "https://github.com/rahimsabagh/McQueenVPN/releases/download/v1.0.0-beta.4/McQueenVPN-v1.0.0-beta.4-armeabi-v7a-release.apk",
            ),
            GitHubReleaseAsset(
                name = "McQueenVPN-v1.0.0-beta.4-arm64-v8a-debug.apk",
                downloadUrl = "https://github.com/rahimsabagh/McQueenVPN/releases/download/v1.0.0-beta.4/McQueenVPN-v1.0.0-beta.4-arm64-v8a-debug.apk",
            ),
            GitHubReleaseAsset(
                name = "McQueenVPN-v1.0.0-beta.4-arm64-v8a-release.apk",
                downloadUrl = "https://github.com/rahimsabagh/McQueenVPN/releases/download/v1.0.0-beta.4/McQueenVPN-v1.0.0-beta.4-arm64-v8a-release.apk",
            ),
            GitHubReleaseAsset(
                name = "McQueenVPN-v1.0.0-beta.4-universal-release.apk",
                downloadUrl = "https://github.com/rahimsabagh/McQueenVPN/releases/download/v1.0.0-beta.4/McQueenVPN-v1.0.0-beta.4-universal-release.apk",
            ),
            GitHubReleaseAsset(
                name = "SHA256SUMS.txt",
                downloadUrl = "https://github.com/rahimsabagh/McQueenVPN/releases/download/v1.0.0-beta.4/SHA256SUMS.txt",
            ),
        )
        val pickedArm64 = GitHubReleaseParser.selectBestApkAsset(multiAbiAssets, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("McQueenVPN-v1.0.0-beta.4-arm64-v8a-release.apk", pickedArm64?.name)

        val pickedArm32 = GitHubReleaseParser.selectBestApkAsset(multiAbiAssets, listOf("armeabi-v7a"))
        assertEquals("McQueenVPN-v1.0.0-beta.4-armeabi-v7a-release.apk", pickedArm32?.name)

        val pickedUniversalFallback = GitHubReleaseParser.selectBestApkAsset(multiAbiAssets, listOf("x86_64"))
        assertEquals("McQueenVPN-v1.0.0-beta.4-universal-release.apk", pickedUniversalFallback?.name)

        // Single generic release APK (matches live rahimsabagh/McQueenVPN release asset naming)
        val singleReleaseAssets = listOf(
            GitHubReleaseAsset(
                name = "app-release.apk",
                downloadUrl = "https://github.com/rahimsabagh/McQueenVPN/releases/download/V1.0.0/app-release.apk",
            ),
        )
        val pickedGeneric = GitHubReleaseParser.selectBestApkAsset(singleReleaseAssets, listOf("arm64-v8a"))
        assertEquals("app-release.apk", pickedGeneric?.name)

        // Rejects non-HTTPS or untrusted domains
        val untrustedAssets = listOf(
            GitHubReleaseAsset(
                name = "app-release.apk",
                downloadUrl = "http://evil.example.com/app-release.apk",
            ),
        )
        assertNull(GitHubReleaseParser.selectBestApkAsset(untrustedAssets, listOf("arm64-v8a")))
        assertFalse(GitHubReleaseParser.isOfficialGitHubAssetUrl("https://evil.example.com/app.apk"))
        assertTrue(GitHubReleaseParser.isOfficialGitHubAssetUrl("https://objects.githubusercontent.com/github-production-release-asset/app.apk"))
    }

    @Test
    fun `checkLatestRelease returns UpdateAvailable when GitHub publishes a newer release with valid APK`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse(
                    code = 200,
                    body = """
                        {
                          "tag_name": "v1.0.0",
                          "name": "McQueen VPN 1.0.0",
                          "body": "### What's New\n- Bypass Iran IP and Domain routing\n- Stability fixes",
                          "published_at": "2026-11-15T12:00:00Z",
                          "html_url": "https://github.com/rahimsabagh/McQueenVPN/releases/tag/v1.0.0",
                          "draft": false,
                          "prerelease": false,
                          "assets": [
                            {
                              "name": "McQueenVPN-arm64-v8a.apk",
                              "browser_download_url": "https://github.com/rahimsabagh/McQueenVPN/releases/download/v1.0.0/McQueenVPN-arm64-v8a.apk",
                              "content_type": "application/vnd.android.package-archive",
                              "size": 120000000
                            }
                          ]
                        }
                    """.trimIndent(),
                )
            )
            val checker = AppUpdateChecker(endpointUrl = server.url("/releases/latest").toString())
            val result = checker.checkLatestRelease(
                installedVersion = "1.0.0-beta.1",
                supportedAbis = listOf("arm64-v8a"),
            )
            assertTrue(result is AppUpdateCheckResult.UpdateAvailable)
            val available = result as AppUpdateCheckResult.UpdateAvailable
            assertEquals("v1.0.0", available.latestVersion)
            assertEquals("1.0.0-beta.1", available.installedVersion)
            assertEquals("McQueenVPN-arm64-v8a.apk", available.apkAssetName)
            assertTrue(available.releaseNotes.contains("Bypass Iran"))
            assertTrue(available.publishedAtFormatted.isNotBlank())
        } finally {
            server.close()
        }
    }

    @Test
    fun `checkLatestRelease returns UpToDate, RateLimited, InvalidResponse, MissingApkAsset, and NoInternet`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            // 1. UpToDate (identical version with V prefix)
            server.enqueue(
                MockResponse(
                    code = 200,
                    body = """
                        {
                          "tag_name": "V1.0.0-beta.1",
                          "published_at": "2026-11-14T14:57:05Z",
                          "assets": [
                            {"name": "app-release.apk", "browser_download_url": "https://github.com/rahimsabagh/McQueenVPN/releases/download/V1.0.0-beta.1/app-release.apk"}
                          ]
                        }
                    """.trimIndent(),
                )
            )
            val checker = AppUpdateChecker(endpointUrl = server.url("/latest").toString())
            val upToDate = checker.checkLatestRelease(installedVersion = "1.0.0-beta.1")
            assertTrue(upToDate is AppUpdateCheckResult.UpToDate)

            // 2. Rate limited (HTTP 403 X-RateLimit-Remaining: 0)
            server.enqueue(
                MockResponse.Builder()
                    .code(403)
                    .addHeader("X-RateLimit-Remaining", "0")
                    .addHeader("X-RateLimit-Reset", "1790000000")
                    .body("""{"message":"API rate limit exceeded"}""")
                    .build()
            )
            val rateLimited = checker.checkLatestRelease(installedVersion = "1.0.0-beta.1")
            assertTrue(rateLimited is AppUpdateCheckResult.RateLimited)
            assertEquals(1790000000L, (rateLimited as AppUpdateCheckResult.RateLimited).resetEpochSeconds)

            // 3. Invalid JSON response
            server.enqueue(MockResponse(code = 200, body = "{not-valid-json"))
            val invalid = checker.checkLatestRelease(installedVersion = "1.0.0-beta.1")
            assertTrue(invalid is AppUpdateCheckResult.InvalidResponse)

            // 4. Newer release missing APK asset
            server.enqueue(
                MockResponse(
                    code = 200,
                    body = """
                        {
                          "tag_name": "v2.0.0",
                          "body": "Notes",
                          "assets": [
                            {"name": "source.tar.gz", "browser_download_url": "https://github.com/rahimsabagh/McQueenVPN/releases/download/v2.0.0/source.tar.gz"}
                          ]
                        }
                    """.trimIndent(),
                )
            )
            val missingAsset = checker.checkLatestRelease(installedVersion = "1.0.0-beta.1")
            assertTrue(missingAsset is AppUpdateCheckResult.MissingApkAsset)
            assertEquals("v2.0.0", (missingAsset as AppUpdateCheckResult.MissingApkAsset).latestVersion)
        } finally {
            server.close()
        }

        // 5. Unreachable endpoint -> NoInternet
        val unusedPort = ServerSocket(0).use { it.localPort }
        val fastClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        val offlineChecker = AppUpdateChecker(
            httpClient = fastClient,
            endpointUrl = "http://127.0.0.1:$unusedPort/releases/latest",
        )
        val offlineResult = offlineChecker.checkLatestRelease(installedVersion = "1.0.0-beta.1")
        assertEquals(AppUpdateCheckResult.NoInternet, offlineResult)
    }

    @Test
    fun `apk metadata verifier rejects package mismatch, version rollback, and certificate mismatch`() {
        val signer = setOf("a1b2c3d4")
        val ok = AppUpdateApkVerifier.verifyPackageMetadata(
            expectedPackageName = "com.aistudio.mcqueenvpn.rmqvpn",
            installedVersionCode = 761L,
            installedSignerSha256 = signer,
            archivePackageName = "com.aistudio.mcqueenvpn.rmqvpn",
            archiveVersionCode = 762L,
            archiveSignerSha256 = signer,
        )
        assertTrue(ok.valid)

        val wrongPkg = AppUpdateApkVerifier.verifyPackageMetadata(
            expectedPackageName = "com.aistudio.mcqueenvpn.rmqvpn",
            installedVersionCode = 761L,
            installedSignerSha256 = signer,
            archivePackageName = "com.evil.clone",
            archiveVersionCode = 762L,
            archiveSignerSha256 = signer,
        )
        assertFalse(wrongPkg.valid)

        val rollback = AppUpdateApkVerifier.verifyPackageMetadata(
            expectedPackageName = "com.aistudio.mcqueenvpn.rmqvpn",
            installedVersionCode = 761L,
            installedSignerSha256 = signer,
            archivePackageName = "com.aistudio.mcqueenvpn.rmqvpn",
            archiveVersionCode = 761L,
            archiveSignerSha256 = signer,
        )
        assertFalse(rollback.valid)

        val wrongSig = AppUpdateApkVerifier.verifyPackageMetadata(
            expectedPackageName = "com.aistudio.mcqueenvpn.rmqvpn",
            installedVersionCode = 761L,
            installedSignerSha256 = signer,
            archivePackageName = "com.aistudio.mcqueenvpn.rmqvpn",
            archiveVersionCode = 762L,
            archiveSignerSha256 = setOf("deadbeef"),
        )
        assertFalse(wrongSig.valid)
    }
}
