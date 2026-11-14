package com.v2ray.ang.mcqueen

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Centralized remote manifest location for McQueen VPN.
 *
 * Security & private-repository architecture:
 * - No GitHub Personal Access Tokens or credentials are ever bundled in the APK.
 * - For public repositories, point [DEFAULT_SERVERS_URL] to raw.githubusercontent.com over HTTPS.
 * - If your server directory lives in a private GitHub repository, host a lightweight tokenless
 *   HTTPS proxy (e.g., Cloudflare Worker / GitHub App / signed CDN endpoint) that reads the
 *   private repo using server-side credentials and serves `servers.json` over HTTPS, then configure
 *   its HTTPS URL here or via the in-app Server Directory Source setting.
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
    const val MANIFEST_REFRESH_INTERVAL_HOURS = 6L
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
            parsed.scheme == "http" && (parsed.host == "127.0.0.1" || parsed.host == "localhost") -> true
            else -> false
        }
    }
}
