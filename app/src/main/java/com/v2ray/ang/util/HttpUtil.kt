package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.UrlContentRequest
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MalformedURLException
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

object HttpUtil {

    private val sharedConnectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)
    private val sharedDispatcher = Dispatcher()
    private val sharedBaseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .build()
    }

    internal fun baseClient(): OkHttpClient = sharedBaseClient

    /**
     * Converts the domain part of a URL string to its IDN (Punycode, ASCII Compatible Encoding) format.
     *
     * For example, a URL like "https://例子.中国/path" will be converted to "https://xn--fsqu00a.xn--fiqs8s/path".
     *
     * @param str The URL string to convert (can contain non-ASCII characters in the domain).
     * @return The URL string with the domain part converted to ASCII-compatible (Punycode) format.
     */
    fun toIdnUrl(str: String): String {
        val url = URL(str)
        val host = url.host
        val asciiHost = IDN.toASCII(url.host, IDN.ALLOW_UNASSIGNED)
        if (host != asciiHost) {
            return str.replace(host, asciiHost)
        } else {
            return str
        }
    }

    /**
     * Converts a Unicode domain name to its IDN (Punycode, ASCII Compatible Encoding) format.
     * If the input is an IP address or already an ASCII domain, returns the original string.
     *
     * @param domain The domain string to convert (can include non-ASCII internationalized characters).
     * @return The domain in ASCII-compatible (Punycode) format, or the original string if input is an IP or already ASCII.
     */
    fun toIdnDomain(domain: String): String {
        // Return as is if it's a pure IP address (IPv4 or IPv6)
        if (Utils.isPureIpAddress(domain)) {
            return domain
        }

        // Return as is if already ASCII (English domain or already punycode)
        if (domain.all { it.code < 128 }) {
            return domain
        }

        // Otherwise, convert to ASCII using IDN
        return IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED)
    }

    /**
     * Resolves a hostname to an IP address, returns original input if it's already an IP
     *
     * @param host The hostname or IP address to resolve
     * @param ipv6Preferred Whether to prefer IPv6 addresses, defaults to false
     * @return The resolved IP address or the original input (if it's already an IP or resolution fails)
     */
    fun resolveHostToIP(host: String, ipv6Preferred: Boolean = false): List<String>? {
        try {
            // If it's already an IP address, return it as a list
            if (Utils.isPureIpAddress(host)) {
                return null
            }

            // Get all IP addresses
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty()) {
                return null
            }

            // Sort addresses based on preference
            val sortedAddresses = if (ipv6Preferred) {
                addresses.sortedWith(compareByDescending { it is Inet6Address })
            } else {
                addresses.sortedWith(compareBy { it is Inet6Address })
            }

            val ipList = sortedAddresses.mapNotNull { it.hostAddress }

            LogUtil.i(AppConfig.TAG, "Resolved IPs for $host: ${ipList.joinToString()}")

            return ipList
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve host to IP", e)
            return null
        }
    }


    /**
     * Retrieves the content of a URL as a string.
     *
     * @param url The URL to fetch content from.
     * @param timeout The timeout value in milliseconds.
     * @param httpPort The HTTP port to use.
     * @return The content of the URL as a string.
     */
    fun getUrlContent(request: UrlContentRequest): String? {
        val url = request.url ?: return null
        val client = buildOkHttpClient(request.timeout, request.httpPort, request.proxyUsername, request.proxyPassword, followRedirects = true)
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        if (request.httpPort != 0 && !request.proxyUsername.isNullOrBlank() && !request.proxyPassword.isNullOrBlank()) {
            requestBuilder.header("Proxy-Authorization", Credentials.basic(request.proxyUsername, request.proxyPassword))
        }
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    logWarn("Failed to get URL content, code=${response.code}")
                    return null
                }
                return response.body?.string()
            }
        } catch (e: Exception) {
            logError("Failed to get URL content", e)
        }
        return null
    }

    /**
     * Retrieves the content of a URL as a string with a custom User-Agent header.
     *
     * @param url The URL to fetch content from.
     * @param timeout The timeout value in milliseconds.
     * @param httpPort The HTTP port to use.
     * @param onResponseHeaders Receives the headers of the successful response that produced the returned content.
     * @return The content of the URL as a string.
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    fun getUrlContentWithUserAgent(
        request: UrlContentRequest,
        onResponseHeaders: ((Headers) -> Unit)? = null
    ): String {
        var currentUrl = request.url
        var redirects = 0
        val maxRedirects = 3
        val client = if (currentUrl != null) {
            buildOkHttpClient(request.timeout, request.httpPort, request.proxyUsername, request.proxyPassword, followRedirects = false)
        } else {
            null
        }
        val finalUserAgent = if (request.userAgent.isNullOrBlank()) {
            "McQueenVPN/${BuildConfig.VERSION_NAME}"
        } else {
            request.userAgent
        }
        val activeHeaders = JsonUtil.parseHeadersToMap(request.requestHeaders).toMutableMap()

        while (redirects++ < maxRedirects) {
            val requestUrl = currentUrl ?: continue
            val activeClient = requireNotNull(client)
            val requestBuilder = Request.Builder()
                .url(requestUrl)
                .get()
                .header("User-agent", finalUserAgent)

            applyEmbeddedBasicAuthHeader(requestUrl, requestBuilder)

            for ((key, value) in activeHeaders) {
                logDebug("Adding custom header: $key")
                try {
                    requestBuilder.header(key, value)
                } catch (_: IllegalArgumentException) {
                }
            }

            if (request.httpPort != 0 && !request.proxyUsername.isNullOrBlank() && !request.proxyPassword.isNullOrBlank()) {
                requestBuilder.header("Proxy-Authorization", Credentials.basic(request.proxyUsername, request.proxyPassword))
            }

            activeClient.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.isRedirect -> {
                        val location = response.header("Location")
                        if (location.isNullOrEmpty()) {
                            throw IOException("Redirect location not found")
                        }
                        val (nextUrl, sameOrigin) = resolveAndValidateRedirect(requestUrl, location)
                        if (!sameOrigin) {
                            activeHeaders.clear()
                        }
                        currentUrl = nextUrl
                        continue
                    }

                    response.isSuccessful -> {
                        onResponseHeaders?.invoke(response.headers)
                        return response.body?.string() ?: ""
                    }

                    else -> {
                        throw IOException("Request failed with status code ${response.code}")
                    }
                }
            }
        }
        throw IOException("Too many redirects")
    }

    @Throws(IOException::class)
    internal fun resolveAndValidateRedirect(currentUrl: String, location: String): Pair<String, Boolean> {
        val nextUrl = resolveLocation(currentUrl, location)
        if (nextUrl.isNullOrEmpty()) {
            throw IOException("Failed to resolve redirect location")
        }
        val currentScheme = runCatching { URI(currentUrl).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        val nextScheme = runCatching { URI(nextUrl).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (nextScheme != "http" && nextScheme != "https") {
            throw IOException("Blocked non-HTTP(S) redirect scheme")
        }
        if (currentScheme == "https" && nextScheme != "https") {
            throw IOException("Blocked HTTPS downgrade redirect")
        }
        return nextUrl to isSameOrigin(currentUrl, nextUrl)
    }

    internal fun redactUrlForLogging(rawUrl: String?): String {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return "<empty>"
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host
            if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
                return "<redacted-url>"
            }
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            val pathSuffix = if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") "/<redacted>" else ""
            "$scheme://$host$portPart$pathSuffix"
        } catch (_: Exception) {
            "<redacted-url>"
        }
    }

    private fun isSameOrigin(firstUrl: String, secondUrl: String): Boolean {
        return try {
            val a = URI(firstUrl)
            val b = URI(secondUrl)
            val schemeA = a.scheme?.lowercase(Locale.ROOT) ?: return false
            val schemeB = b.scheme?.lowercase(Locale.ROOT) ?: return false
            val hostA = a.host?.lowercase(Locale.ROOT) ?: return false
            val hostB = b.host?.lowercase(Locale.ROOT) ?: return false
            schemeA == schemeB && hostA == hostB && effectivePort(a, schemeA) == effectivePort(b, schemeB)
        } catch (_: Exception) {
            false
        }
    }

    private fun effectivePort(uri: URI, normalizedScheme: String): Int {
        if (uri.port != -1) return uri.port
        return when (normalizedScheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    private fun applyEmbeddedBasicAuthHeader(rawUrl: String, requestBuilder: Request.Builder) {
        val parsed = runCatching { URL(rawUrl) }.getOrNull() ?: return
        parsed.userInfo?.let { userInfo ->
            val colon = userInfo.indexOf(':')
            val user = runCatching {
                Utils.decodeURIComponent(if (colon >= 0) userInfo.substring(0, colon) else userInfo)
            }.getOrDefault(if (colon >= 0) userInfo.substring(0, colon) else userInfo)
            val pass = runCatching {
                Utils.decodeURIComponent(if (colon >= 0) userInfo.substring(colon + 1) else "")
            }.getOrDefault(if (colon >= 0) userInfo.substring(colon + 1) else "")
            requestBuilder.header("Authorization", Credentials.basic(user, pass))
        }
    }

    internal fun buildOkHttpClient(
        timeout: Int,
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?,
        followRedirects: Boolean
    ): OkHttpClient {
        val builder = sharedBaseClient.newBuilder()
            .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(false)

        if (httpPort != 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, httpPort)))
            if (!proxyUsername.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request.newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
                            .build()
                    }
                }
            }
        }

        return builder.build()
    }

    private fun resolveLocation(baseUrl: String, raw: String): String? {
        return try {
            val locUri = URI(raw)
            val baseUri = URI(baseUrl)
            val resolved = if (locUri.isAbsolute) locUri else baseUri.resolve(locUri)
            resolved.toURL().toString()
        } catch (_: Exception) {
            try {
                URL(raw).toString()
            } catch (_: MalformedURLException) {
                try {
                    URL(URL(baseUrl), raw).toString()
                } catch (_: MalformedURLException) {
                    null
                }
            }
        }
    }

    fun downloadToFile(
        request: UrlContentRequest,
        targetFile: File
    ): Boolean {
        val url = request.url ?: return false
        val client = buildOkHttpClient(request.timeout, request.httpPort, request.proxyUsername, request.proxyPassword, followRedirects = true)
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        if (request.httpPort != 0 && !request.proxyUsername.isNullOrBlank() && !request.proxyPassword.isNullOrBlank()) {
            requestBuilder.header("Proxy-Authorization", Credentials.basic(request.proxyUsername, request.proxyPassword))
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    logWarn("Failed to download file, code=${response.code}, url=${redactUrlForLogging(url)}")
                    return false
                }
                val body = response.body ?: return false
                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }
        } catch (e: Exception) {
            logError("Failed to download file: ${redactUrlForLogging(url)}", e)
            false
        }
    }

    private fun logDebug(message: String) {
        runCatching { LogUtil.d(AppConfig.TAG, message) }
    }

    private fun logWarn(message: String) {
        runCatching { LogUtil.w(AppConfig.TAG, message) }
    }

    private fun logError(message: String, throwable: Throwable) {
        runCatching { LogUtil.e(AppConfig.TAG, message, throwable) }
    }
}
