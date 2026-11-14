package com.v2ray.ang.mcqueen

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Centralized remote manifest location for McQueen VPN.
 *
 * Security & private-repository architecture:
 * - No GitHub Personal Access Tokens or credentials are ever bundled in the APK or committed to
 *   the public repository.
 * - For public repositories, [DEFAULT_SERVERS_URL] points to the `my-releases` branch over HTTPS.
 * - For private server directories, configure an authenticated HTTPS endpoint (or HTTPS proxy such
 *   as a Cloudflare Worker / GitHub App endpoint) and supply an optional Bearer token at runtime
 *   via Preferences; tokens are stored only in local device storage and transmitted solely over HTTPS.
 *
 * Background sync scheduling:
 * - Periodic sync is scheduled every [MANIFEST_REFRESH_INTERVAL_MINUTES] (30 minutes) via a unique
 *   WorkManager task, alongside startup sync. Android Doze, App Standby, and device battery
 *   optimizations are respected, so periodic execution is best-effort and not exact.
 */
object McQueenServerConfig {
    const val DEFAULT_GITHUB_OWNER = "rahimsabagh"
    const val DEFAULT_GITHUB_REPO = "McQueenVPN"
    const val DEFAULT_GITHUB_BRANCH = "my-releases"
    const val DEFAULT_SERVERS_JSON_PATH = "servers.json"

    const val DEFAULT_SERVERS_URL =
        "https://raw.githubusercontent.com/$DEFAULT_GITHUB_OWNER/$DEFAULT_GITHUB_REPO/$DEFAULT_GITHUB_BRANCH/$DEFAULT_SERVERS_JSON_PATH"

    internal const val LEGACY_DEFAULT_SERVERS_URL =
        "https://raw.githubusercontent.com/$DEFAULT_GITHUB_OWNER/$DEFAULT_GITHUB_REPO/main/$DEFAULT_SERVERS_JSON_PATH"

    const val REQUEST_TIMEOUT_SECONDS = 15L
    const val PROBE_TIMEOUT_MS = 3500
    const val COUNTRY_PROBE_LIMIT = 4
    const val MAX_CONCURRENT_PROBES = 4
    const val CONNECT_ATTEMPT_TIMEOUT_MS = 20_000L
    const val AETHER_CONNECT_ATTEMPT_TIMEOUT_MS = 65_000L
    const val TUNNEL_VERIFY_TIMEOUT_MS = 15_000L
    const val MANIFEST_REFRESH_INTERVAL_MINUTES = 30L
    const val MANIFEST_REFRESH_TASK_NAME = "mcqueen_manifest_updater"

    fun resolveActiveUrl(storedUrl: String?): String {
        val candidate = storedUrl?.trim().orEmpty()
        if (candidate.equals(LEGACY_DEFAULT_SERVERS_URL, ignoreCase = true)) {
            return DEFAULT_SERVERS_URL
        }
        return if (candidate.isNotEmpty() && isValidManifestUrl(candidate)) {
            candidate
        } else {
            DEFAULT_SERVERS_URL
        }
    }

    fun isValidManifestUrl(url: String): Boolean {
        val trimmed = url.trim()
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        return when {
            parsed.isHttps -> true
            isLoopbackHttp(parsed) -> true
            else -> false
        }
    }

    internal fun isAuthEligibleUrl(url: String): Boolean = isValidManifestUrl(url)

    private fun isLoopbackHttp(url: okhttp3.HttpUrl): Boolean =
        url.scheme == "http" && (url.host == "127.0.0.1" || url.host == "localhost")
}
