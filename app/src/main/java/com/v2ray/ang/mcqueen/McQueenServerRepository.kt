package com.v2ray.ang.mcqueen

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.AetherDelayTester
import com.v2ray.ang.core.AetherIdentityProvisioner
import com.v2ray.ang.core.AetherProvisionResult
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.core.ProvisionFailureKind
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class ManifestSyncOutcome {
    data class Success(
        val manifest: ManifestParseResult,
        val fromCache: Boolean = false,
    ) : ManifestSyncOutcome()

    data class Failure(val reason: String) : ManifestSyncOutcome()
}

enum class TunnelEngine {
    XRAY,
    AETHER,
}

sealed class CountryIdentityStatus {
    data class Ready(val newlyProvisioned: Boolean) : CountryIdentityStatus()
    data class Failed(
        val kind: ProvisionFailureKind,
        val diagnosticDetail: String? = null,
    ) : CountryIdentityStatus()
}

class McQueenServerRepository(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val identityProvisioner: AetherIdentityProvisioner = AetherIdentityProvisioner(),
) {

    fun getConfiguredUrl(): String {
        val stored = MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_SERVERS_URL)
        val resolved = McQueenServerConfig.resolveActiveUrl(stored)
        if (!stored.isNullOrBlank() && stored != resolved) {
            MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SERVERS_URL, resolved)
        }
        return resolved
    }

    fun getConfiguredAuthToken(): String? =
        MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_MANIFEST_TOKEN)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun hasConfiguredAuthToken(): Boolean = getConfiguredAuthToken() != null

    fun saveCustomUrl(url: String): Boolean = saveCustomManifestConfig(url = url)

    fun saveCustomManifestConfig(
        url: String,
        authTokenInput: String? = null,
        clearAuthToken: Boolean = false,
    ): Boolean {
        val trimmed = url.trim()
        if (!McQueenServerConfig.isValidManifestUrl(trimmed)) {
            return false
        }
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SERVERS_URL, trimmed)
        when {
            clearAuthToken -> MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_MANIFEST_TOKEN, "")
            !authTokenInput.isNullOrBlank() -> MmkvManager.encodeSettings(
                AppConfig.PREF_MCQUEEN_MANIFEST_TOKEN,
                authTokenInput.trim(),
            )
        }
        return true
    }

    fun resetCustomUrl() {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SERVERS_URL, McQueenServerConfig.DEFAULT_SERVERS_URL)
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_MANIFEST_TOKEN, "")
    }

    suspend fun loadCachedManifest(context: Context): ManifestParseResult? = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            loadCachedOrBundledManifestLocked(context)
        }
    }

    private fun loadCachedOrBundledManifestLocked(context: Context): ManifestParseResult? {
        if (!AetherCoreManager.isSupported(context)) {
            LogUtil.w(
                AppConfig.TAG,
                "McQueenServerRepository: Aether binary not executable yet in nativeLibraryDir; retaining aether:// entries in server directory"
            )
        }

        val cachedJson = MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_MANIFEST_CACHE)
        if (!cachedJson.isNullOrBlank()) {
            val parsed = parseManifestForDirectory(cachedJson)
            if (parsed != null && parsed.servers.isNotEmpty()) {
                val existingGuids = MmkvManager.decodeServerList(AppConfig.MCQUEEN_SUBSCRIPTION_ID)
                if (existingGuids.size < parsed.servers.size) {
                    persistServersToMmkv(parsed.servers)
                }
                return parsed
            }
        }

        val bundledJson = loadBundledManifestJson(context) ?: return null
        val bundledParsed = parseManifestForDirectory(bundledJson) ?: return null
        if (bundledParsed.servers.isEmpty()) return null

        runCatching {
            persistServersToMmkv(bundledParsed.servers)
            MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_MANIFEST_CACHE, bundledJson)
        }.onFailure { e ->
            LogUtil.w(AppConfig.TAG, "McQueenServerRepository: failed to seed bundled servers.json into MMKV", e)
        }
        return bundledParsed
    }

    private fun parseManifestForDirectory(json: String): ManifestParseResult? {
        return runCatching {
            RemoteServerManifestParser.parse(
                json = json,
                isAetherSupported = true,
                subscriptionId = AppConfig.MCQUEEN_SUBSCRIPTION_ID,
                verifyOutbound = ::isOutboundBuildable,
            )
        }.onFailure { e ->
            LogUtil.w(AppConfig.TAG, "McQueenServerRepository: failed to parse manifest JSON", e)
        }.getOrNull()
    }

    internal fun loadBundledManifestJson(context: Context? = null): String? {
        if (context != null) {
            val fromAsset = runCatching {
                context.assets.open(BUNDLED_MANIFEST_ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()
            if (!fromAsset.isNullOrBlank()) {
                return fromAsset
            }
        }
        val fileCandidates = listOf(
            File(BUNDLED_MANIFEST_ASSET_NAME),
            File("../$BUNDLED_MANIFEST_ASSET_NAME"),
            File("app/src/main/assets/$BUNDLED_MANIFEST_ASSET_NAME"),
        )
        return fileCandidates.firstOrNull { it.isFile }?.let { file ->
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
    }

    suspend fun syncFromGitHub(
        context: Context,
        urlOverride: String? = null,
        authTokenOverride: String? = null,
    ): ManifestSyncOutcome = withContext(Dispatchers.IO) {
        val targetUrl = urlOverride?.trim()?.takeIf { it.isNotEmpty() } ?: getConfiguredUrl()
        if (!McQueenServerConfig.isValidManifestUrl(targetUrl)) {
            return@withContext ManifestSyncOutcome.Failure("Invalid HTTPS server directory URL")
        }
        val effectiveToken = authTokenOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: getConfiguredAuthToken()

        val responseJson = try {
            fetchManifestJson(targetUrl, effectiveToken)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "McQueenServerRepository: fetch failed for url=$targetUrl", e)
            manifestMutex.withLock {
                loadCachedOrBundledManifestLocked(context)
            }
            return@withContext ManifestSyncOutcome.Failure(
                e.message?.takeIf { it.isNotBlank() } ?: "Network error"
            )
        }

        val parsed = try {
            RemoteServerManifestParser.parse(
                json = responseJson,
                isAetherSupported = true,
                subscriptionId = AppConfig.MCQUEEN_SUBSCRIPTION_ID,
                verifyOutbound = ::isOutboundBuildable,
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "McQueenServerRepository: validation failed", e)
            manifestMutex.withLock {
                loadCachedOrBundledManifestLocked(context)
            }
            return@withContext ManifestSyncOutcome.Failure(
                e.message?.takeIf { it.isNotBlank() } ?: "Invalid manifest format"
            )
        }

        if (parsed.servers.isEmpty()) {
            manifestMutex.withLock {
                loadCachedOrBundledManifestLocked(context)
            }
            return@withContext ManifestSyncOutcome.Failure("No enabled usable servers found in manifest")
        }

        try {
            manifestMutex.withLock {
                persistServersToMmkv(parsed.servers)
                MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_MANIFEST_CACHE, responseJson)
                if (urlOverride != null) {
                    MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SERVERS_URL, targetUrl)
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "McQueenServerRepository: storage failed", e)
            return@withContext ManifestSyncOutcome.Failure("Unable to save server list locally")
        }

        ManifestSyncOutcome.Success(manifest = parsed, fromCache = false)
    }

    suspend fun measureCountryLatencies(
        context: Context,
        countries: List<CountryUiModel>,
    ): List<CountryUiModel> = withContext(Dispatchers.IO) {
        val byCountry = loadStoredProfilesByCountry()
        val profileMap = byCountry.values.flatten().toMap()
        val countrySemaphore = Semaphore(McQueenServerConfig.MAX_CONCURRENT_PROBES)

        coroutineScope {
            countries.map { country ->
                async {
                    countrySemaphore.withPermit {
                        val candidates = byCountry[country.countryCode]
                            .orEmpty()
                            .take(McQueenServerConfig.COUNTRY_PROBE_LIMIT)
                        if (candidates.isEmpty()) return@withPermit country

                        val ranked = CountryServerSelector.rankCandidates(
                            candidates = candidates.map { (guid, profile) ->
                                toSelectableCandidate(guid, country.countryCode, profile)
                            },
                            timeoutMs = McQueenServerConfig.PROBE_TIMEOUT_MS.toLong(),
                            maxConcurrency = McQueenServerConfig.MAX_CONCURRENT_PROBES,
                        ) { candidate ->
                            val profile = profileMap[candidate.guid]
                            if (profile != null && engineFor(profile) == TunnelEngine.XRAY) {
                                probeRealOutboundDelay(context, candidate.guid, profile)
                            } else {
                                // Avoid spawning multiple full Aether/Tor/Psiphon sessions during passive list scan;
                                // real tunnel verification runs at connect time for Aether candidates.
                                probeTcpReachability(candidate)
                            }
                        }

                        val bestReal = ranked.firstOrNull { candidate ->
                            val profile = profileMap[candidate.guid]
                            candidate.latencyMs > 0L && profile != null && engineFor(profile) == TunnelEngine.XRAY
                        }?.latencyMs
                        val bestAny = ranked.firstOrNull { it.latencyMs > 0L }?.latencyMs
                        country.copy(
                            bestLatencyMs = bestReal ?: bestAny,
                            availability = if (bestReal != null && bestReal <= 220L) {
                                CountryAvailability.OPTIMAL
                            } else {
                                CountryAvailability.READY
                            },
                        )
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun hasPendingIdentityProvisioning(
        context: Context,
        countryCode: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedCode = countryCode.trim().uppercase(Locale.ROOT)
        val candidates = loadStoredProfilesByCountry(context)[normalizedCode].orEmpty()
        candidates.any { (_, profile) ->
            engineFor(profile) == TunnelEngine.AETHER &&
                !identityProvisioner.hasValidIdentity(context, profile)
        }
    }

    suspend fun ensureIdentitiesForCountry(
        context: Context,
        countryCode: String,
    ): CountryIdentityStatus = withContext(Dispatchers.IO) {
        val normalizedCode = countryCode.trim().uppercase(Locale.ROOT)
        val candidates = loadStoredProfilesByCountry(context)[normalizedCode].orEmpty()
        if (candidates.isEmpty()) {
            return@withContext CountryIdentityStatus.Ready(newlyProvisioned = false)
        }

        val hasNonAetherCandidate = candidates.any { (_, profile) -> engineFor(profile) != TunnelEngine.AETHER }
        val aetherCandidates = candidates.filter { (_, profile) -> engineFor(profile) == TunnelEngine.AETHER }
        if (aetherCandidates.isEmpty()) {
            return@withContext CountryIdentityStatus.Ready(newlyProvisioned = false)
        }

        val distinctByFiles = linkedMapOf<List<String>, ProfileItem>()
        for ((_, profile) in aetherCandidates) {
            val required = identityProvisioner.requiredFiles(profile).sorted()
            distinctByFiles.putIfAbsent(required, profile)
        }

        var readyGroupCount = 0
        var newlyProvisioned = false
        var firstFailure: AetherProvisionResult.Failed? = null

        for ((_, profile) in distinctByFiles) {
            when (val result = identityProvisioner.ensureIdentity(context, profile)) {
                is AetherProvisionResult.NotRequired,
                is AetherProvisionResult.AlreadyReady -> readyGroupCount++
                is AetherProvisionResult.Provisioned -> {
                    readyGroupCount++
                    newlyProvisioned = true
                }
                is AetherProvisionResult.Failed -> {
                    if (firstFailure == null) {
                        firstFailure = result
                    }
                }
            }
        }

        if (readyGroupCount > 0 || hasNonAetherCandidate) {
            CountryIdentityStatus.Ready(newlyProvisioned = newlyProvisioned)
        } else {
            val failure = firstFailure ?: AetherProvisionResult.Failed(ProvisionFailureKind.IDENTITY_MISSING)
            CountryIdentityStatus.Failed(
                kind = failure.kind,
                diagnosticDetail = failure.diagnosticDetail,
            )
        }
    }

    suspend fun selectCandidatesForCountry(
        context: Context,
        countryCode: String,
    ): List<CandidateLatency> = withContext(Dispatchers.IO) {
        val normalizedCode = countryCode.trim().uppercase(Locale.ROOT)
        val allCandidates = loadStoredProfilesByCountry(context)[normalizedCode].orEmpty()
        val candidates = allCandidates.filter { (_, profile) ->
            engineFor(profile) != TunnelEngine.AETHER || identityProvisioner.hasValidIdentity(context, profile)
        }
        if (candidates.isEmpty()) return@withContext emptyList()
        val profileMap = candidates.toMap()

        CountryServerSelector.rankCandidates(
            candidates = candidates.map { (guid, profile) ->
                toSelectableCandidate(guid, normalizedCode, profile)
            },
            timeoutMs = McQueenServerConfig.PROBE_TIMEOUT_MS.toLong(),
            maxConcurrency = McQueenServerConfig.MAX_CONCURRENT_PROBES,
        ) { candidate ->
            val profile = profileMap[candidate.guid]
            if (profile == null) {
                -1L
            } else {
                probeCandidateForConnection(context, candidate.guid, profile, candidate)
            }
        }
    }

    private suspend fun probeCandidateForConnection(
        context: Context,
        guid: String,
        profile: ProfileItem,
        candidate: SelectableCandidate,
    ): Long {
        return when (engineFor(profile)) {
            TunnelEngine.AETHER -> {
                val realDelay = runCatching {
                    AetherDelayTester.measure(
                        context = context,
                        guid = guid,
                        profile = profile,
                        url = SettingsManager.getDelayTestUrl(),
                    )
                }.getOrDefault(-1L)
                if (realDelay > 0L) realDelay else probeTcpReachability(candidate)
            }
            TunnelEngine.XRAY -> probeRealOutboundDelay(context, guid, profile)
        }
    }

    private suspend fun probeRealOutboundDelay(
        context: Context,
        guid: String,
        profile: ProfileItem,
    ): Long = coroutineScope {
        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return@coroutineScope -1L
        }
        val batch = UUID.randomUUID().toString()
        val job = async(Dispatchers.IO) {
            try {
                CoreNativeManager.initCoreEnv(context)
                CoreNativeManager.measureOutboundDelay(
                    config = configResult.content,
                    testUrl = SettingsManager.getDelayTestUrl(),
                    batch = batch,
                )
            } catch (t: Throwable) {
                LogUtil.w(AppConfig.TAG, "McQueenServerRepository: real-ping probe failed for ${profile.configType}", t)
                -1L
            }
        }
        try {
            job.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            kotlin.concurrent.thread(name = "McQueenProbeCancel") {
                CoreNativeManager.cancelOutboundDelays(batch)
            }
            throw e
        }
    }

    private fun isOutboundBuildable(profile: ProfileItem): Boolean {
        return try {
            CoreOutboundBuilder.convert(profile) != null
        } catch (t: Throwable) {
            LogUtil.w(AppConfig.TAG, "McQueenServerRepository: outbound build check failed for ${profile.configType}", t)
            false
        }
    }

    internal fun fetchManifestJson(
        url: String,
        authToken: String? = null,
        maxAttempts: Int = DEFAULT_FETCH_MAX_ATTEMPTS,
    ): String {
        val sanitizedToken = authToken?.trim()?.takeIf { it.isNotEmpty() }
        if (sanitizedToken != null && !McQueenServerConfig.isAuthEligibleUrl(url)) {
            throw IOException("Refused sending credentials to non-HTTPS URL")
        }

        val totalAttempts = maxAttempts.coerceIn(1, 5)
        var lastException: IOException? = null

        for (attempt in 1..totalAttempts) {
            try {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")

                if (sanitizedToken != null) {
                    requestBuilder.header("Authorization", "Bearer $sanitizedToken")
                }

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!McQueenServerConfig.isValidManifestUrl(response.request.url.toString())) {
                        throw NonRetryableManifestException("Refused non-HTTPS manifest response URL")
                    }
                    val code = response.code
                    if (!response.isSuccessful) {
                        val msg = "HTTP $code while fetching servers.json"
                        if (code in 400..499 && code != 408 && code != 429) {
                            throw NonRetryableManifestException(msg)
                        }
                        throw IOException(msg)
                    }
                    val body = response.body.string()
                    if (body.isBlank()) {
                        throw IOException("Empty response body from $url")
                    }
                    return body
                }
            } catch (e: NonRetryableManifestException) {
                throw IOException(e.message, e)
            } catch (e: IOException) {
                lastException = e
                if (attempt < totalAttempts) {
                    try {
                        Thread.sleep(RETRY_BASE_DELAY_MS * attempt)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }

        throw lastException ?: IOException("Failed to fetch servers.json after $totalAttempts attempts")
    }

    private class NonRetryableManifestException(message: String) : IOException(message)

    internal fun deterministicServerGuid(countryCode: String, serverId: String): String {
        val key = "mcqueen:${countryCode.trim().uppercase(Locale.ROOT)}|${serverId.trim()}"
        return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8))
            .toString()
            .replace("-", "")
    }

    private fun persistServersToMmkv(servers: List<ValidatedServer>) {
        if (servers.isEmpty()) return
        val profiles = linkedMapOf<String, ProfileItem>()
        servers.forEach { server ->
            val guid = deterministicServerGuid(server.countryCode, server.id)
            profiles[guid] = server.profile
        }
        MmkvManager.saveServerProfiles(
            profiles = profiles,
            rawConfigs = emptyMap(),
            subscriptionId = AppConfig.MCQUEEN_SUBSCRIPTION_ID,
            append = false,
        )
    }

    private suspend fun loadStoredProfilesByCountry(
        context: Context? = null,
    ): Map<String, List<Pair<String, ProfileItem>>> {
        var guids = MmkvManager.decodeServerList(AppConfig.MCQUEEN_SUBSCRIPTION_ID)
        if (guids.isEmpty() && context != null) {
            manifestMutex.withLock {
                if (MmkvManager.decodeServerList(AppConfig.MCQUEEN_SUBSCRIPTION_ID).isEmpty()) {
                    loadCachedOrBundledManifestLocked(context)
                }
                guids = MmkvManager.decodeServerList(AppConfig.MCQUEEN_SUBSCRIPTION_ID)
            }
        }
        val grouped = linkedMapOf<String, MutableList<Pair<String, ProfileItem>>>()
        for (guid in guids) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            val code = profile.description
                ?.substringBefore('|')
                ?.trim()
                ?.uppercase(Locale.ROOT)
                ?.takeIf { it.length == 2 }
                ?: continue
            grouped.getOrPut(code) { mutableListOf() }.add(guid to profile)
        }
        return grouped
    }

    private fun toSelectableCandidate(
        guid: String,
        countryCode: String,
        profile: ProfileItem,
    ): SelectableCandidate {
        val (host, port) = if (profile.configType == EConfigType.AETHER) {
            val probeHost = AetherDelayTester.probeHost(profile)
            probeHost to if (probeHost != null) 443 else null
        } else {
            profile.server to profile.serverPort?.toIntOrNull()
        }
        return SelectableCandidate(
            guid = guid,
            countryCode = countryCode,
            pingHost = host,
            pingPort = port,
        )
    }

    private fun probeTcpReachability(candidate: SelectableCandidate): Long {
        val host = candidate.pingHost?.trim()?.takeIf { it.isNotEmpty() } ?: return -1L
        val port = candidate.pingPort?.takeIf { it in 1..65535 } ?: return -1L
        return SpeedtestManager.socketConnectTime(
            url = host,
            port = port,
            timeoutMs = McQueenServerConfig.PROBE_TIMEOUT_MS,
        )
    }

    companion object {
        private const val BUNDLED_MANIFEST_ASSET_NAME = "servers.json"
        private const val DEFAULT_FETCH_MAX_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 250L
        private val manifestMutex = Mutex()

        fun engineFor(profile: ProfileItem): TunnelEngine = engineFor(profile.configType)

        fun engineFor(configType: EConfigType): TunnelEngine =
            if (configType == EConfigType.AETHER) TunnelEngine.AETHER else TunnelEngine.XRAY

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(McQueenServerConfig.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(McQueenServerConfig.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followSslRedirects(false)
                .addNetworkInterceptor { chain ->
                    val target = chain.request().url.toString()
                    if (!McQueenServerConfig.isValidManifestUrl(target)) {
                        throw IOException("Blocked insecure redirect to non-HTTPS URL")
                    }
                    chain.proceed(chain.request())
                }
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "McQueenVPN/${BuildConfig.VERSION_NAME}")
                        .build()
                    chain.proceed(request)
                }
                .build()
        }
    }
}
