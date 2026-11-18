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
import com.v2ray.ang.core.BypassIranRoutingHelper
import com.v2ray.ang.core.ProvisionFailureKind
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

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

internal data class ManifestValidatorState(
    val scopeHash: String,
    val etag: String? = null,
    val lastModified: String? = null,
)

internal interface ManifestLocalStore {
    fun getConfiguredUrlRaw(): String?
    fun setConfiguredUrlRaw(url: String)
    fun getAuthTokenRaw(): String?
    fun setAuthTokenRaw(token: String)
    fun getManifestCacheJson(): String?
    fun setManifestCacheJson(json: String)
    fun getSummaryCacheJson(): String?
    fun setSummaryCacheJson(json: String)
    fun getLastSyncAtMs(): Long
    fun setLastSyncAtMs(timestampMs: Long)
    fun getValidatorState(): ManifestValidatorState?
    fun setValidatorState(state: ManifestValidatorState?)
    fun getSubscriptionServerList(): List<String>
    fun persistServers(servers: List<ValidatedServer>, guidProvider: (String, String) -> String)
    fun isAetherBinarySupported(context: Context): Boolean
    fun isOutboundBuildable(profile: ProfileItem): Boolean
    fun log(priority: Int, message: String, throwable: Throwable? = null)
}

internal object MmkvManifestLocalStore : ManifestLocalStore {
    override fun getConfiguredUrlRaw(): String? =
        MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_SERVERS_URL)

    override fun setConfiguredUrlRaw(url: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SERVERS_URL, url)
    }

    override fun getAuthTokenRaw(): String? =
        MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_MANIFEST_TOKEN)

    override fun setAuthTokenRaw(token: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_MANIFEST_TOKEN, token)
    }

    override fun getManifestCacheJson(): String? =
        MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_MANIFEST_CACHE)

    override fun setManifestCacheJson(json: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_MANIFEST_CACHE, json)
    }

    override fun getSummaryCacheJson(): String? =
        MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_MANIFEST_SUMMARY_CACHE)

    override fun setSummaryCacheJson(json: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_MANIFEST_SUMMARY_CACHE, json)
    }

    override fun getLastSyncAtMs(): Long =
        MmkvManager.decodeSettingsLong(AppConfig.PREF_MCQUEEN_MANIFEST_LAST_SYNC_MS, 0L)

    override fun setLastSyncAtMs(timestampMs: Long) {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_MANIFEST_LAST_SYNC_MS, timestampMs)
    }

    override fun getValidatorState(): ManifestValidatorState? {
        val raw = MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_MANIFEST_VALIDATOR)
            ?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            JsonUtil.fromJsonSafe(raw, ManifestValidatorState::class.java)
        }.getOrNull()?.takeIf { it.scopeHash.isNotBlank() }
    }

    override fun setValidatorState(state: ManifestValidatorState?) {
        val encoded = if (state == null) "" else runCatching { JsonUtil.toJson(state) }.getOrDefault("")
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_MANIFEST_VALIDATOR, encoded)
    }

    override fun getSubscriptionServerList(): List<String> =
        MmkvManager.decodeServerList(AppConfig.MCQUEEN_SUBSCRIPTION_ID)

    override fun persistServers(servers: List<ValidatedServer>, guidProvider: (String, String) -> String) {
        if (servers.isEmpty()) return
        val profiles = linkedMapOf<String, ProfileItem>()
        servers.forEach { server ->
            val guid = guidProvider(server.countryCode, server.id)
            profiles[guid] = server.profile
        }
        MmkvManager.saveServerProfiles(
            profiles = profiles,
            rawConfigs = emptyMap(),
            subscriptionId = AppConfig.MCQUEEN_SUBSCRIPTION_ID,
            append = false,
        )
    }

    override fun isAetherBinarySupported(context: Context): Boolean = AetherCoreManager.isSupported(context)

    override fun isOutboundBuildable(profile: ProfileItem): Boolean {
        return try {
            CoreOutboundBuilder.convert(profile) != null
        } catch (t: Throwable) {
            log(android.util.Log.WARN, "McQueenServerRepository: outbound build check failed for ${profile.configType}", t)
            false
        }
    }

    override fun log(priority: Int, message: String, throwable: Throwable?) {
        runCatching {
            when {
                priority >= android.util.Log.ERROR && throwable != null -> LogUtil.e(AppConfig.TAG, message, throwable)
                priority >= android.util.Log.ERROR -> LogUtil.e(AppConfig.TAG, message)
                priority >= android.util.Log.WARN && throwable != null -> LogUtil.w(AppConfig.TAG, message, throwable)
                priority >= android.util.Log.WARN -> LogUtil.w(AppConfig.TAG, message)
                throwable != null -> LogUtil.i(AppConfig.TAG, message, throwable)
                else -> LogUtil.i(AppConfig.TAG, message)
            }
        }
    }
}

class McQueenServerRepository internal constructor(
    private val httpClient: OkHttpClient,
    private val identityProvisioner: AetherIdentityProvisioner,
    private val localStore: ManifestLocalStore,
    private val clockMs: () -> Long,
    private val retryBaseDelayMs: Long,
) {
    constructor(
        httpClient: OkHttpClient = sharedDefaultHttpClient,
        identityProvisioner: AetherIdentityProvisioner = AetherIdentityProvisioner(),
    ) : this(
        httpClient = httpClient,
        identityProvisioner = identityProvisioner,
        localStore = MmkvManifestLocalStore,
        clockMs = System::currentTimeMillis,
        retryBaseDelayMs = RETRY_BASE_DELAY_MS,
    )

    fun getConfiguredUrl(): String {
        val stored = localStore.getConfiguredUrlRaw()
        val resolved = McQueenServerConfig.resolveActiveUrl(stored)
        if (!stored.isNullOrBlank() && stored != resolved) {
            localStore.setConfiguredUrlRaw(resolved)
        }
        return resolved
    }

    fun getConfiguredAuthToken(): String? =
        localStore.getAuthTokenRaw()
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
        localStore.setConfiguredUrlRaw(trimmed)
        when {
            clearAuthToken -> localStore.setAuthTokenRaw("")
            !authTokenInput.isNullOrBlank() -> localStore.setAuthTokenRaw(authTokenInput.trim())
        }
        localStore.setValidatorState(null)
        localStore.setLastSyncAtMs(0L)
        return true
    }

    fun resetCustomUrl() {
        localStore.setConfiguredUrlRaw(McQueenServerConfig.DEFAULT_SERVERS_URL)
        localStore.setAuthTokenRaw("")
        localStore.setValidatorState(null)
        localStore.setLastSyncAtMs(0L)
    }

    fun getLastManifestSyncAtMs(): Long = localStore.getLastSyncAtMs()

    fun isManifestCacheFresh(nowMs: Long = clockMs()): Boolean =
        McQueenServerConfig.isManifestCacheFresh(getLastManifestSyncAtMs(), nowMs)

    suspend fun loadCachedManifest(context: Context): ManifestParseResult? = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            loadCachedDirectorySummaryLocked() ?: loadCachedOrBundledManifestLocked(context)
        }
    }

    private fun loadCachedDirectorySummaryLocked(): ManifestParseResult? {
        val summaryJson = localStore.getSummaryCacheJson()
            ?.takeIf { it.isNotBlank() } ?: return null
        if (localStore.getSubscriptionServerList().isEmpty()) {
            return null
        }
        val summary = runCatching {
            JsonUtil.fromJsonSafe(summaryJson, CachedManifestSummary::class.java)
        }.getOrNull() ?: return null
        val countries = summary.countries.takeIf { it.isNotEmpty() } ?: return null
        return ManifestParseResult(
            version = summary.version,
            updatedAt = summary.updatedAt,
            servers = emptyList(),
            countries = countries,
            skippedCount = summary.skippedCount,
        )
    }

    private fun saveCachedDirectorySummary(parsed: ManifestParseResult) {
        if (parsed.countries.isEmpty()) return
        val summary = CachedManifestSummary(
            version = parsed.version,
            countries = parsed.countries,
            skippedCount = parsed.skippedCount,
            updatedAt = parsed.updatedAt,
        )
        runCatching {
            localStore.setSummaryCacheJson(JsonUtil.toJson(summary))
        }
    }

    private fun loadCachedOrBundledManifestLocked(context: Context): ManifestParseResult? {
        if (!localStore.isAetherBinarySupported(context)) {
            localStore.log(
                android.util.Log.WARN,
                "McQueenServerRepository: Aether binary not executable yet in nativeLibraryDir; retaining aether:// entries in server directory",
            )
        }

        val cachedJson = localStore.getManifestCacheJson()
        if (!cachedJson.isNullOrBlank()) {
            val parsed = parseManifestForDirectory(cachedJson)
            if (parsed != null && parsed.servers.isNotEmpty()) {
                val existingGuids = localStore.getSubscriptionServerList()
                if (existingGuids.size < parsed.servers.size) {
                    persistServersToMmkv(parsed.servers)
                }
                saveCachedDirectorySummary(parsed)
                return parsed
            }
        }

        val bundledJson = loadBundledManifestJson(context) ?: return null
        val bundledParsed = parseManifestForDirectory(bundledJson) ?: return null
        if (bundledParsed.servers.isEmpty()) return null

        runCatching {
            persistServersToMmkv(bundledParsed.servers)
            localStore.setManifestCacheJson(bundledJson)
            saveCachedDirectorySummary(bundledParsed)
            if (getLastManifestSyncAtMs() <= 0L) {
                localStore.setLastSyncAtMs(clockMs())
            }
        }.onFailure { e ->
            localStore.log(
                android.util.Log.WARN,
                "McQueenServerRepository: failed to seed bundled servers.json into MMKV",
                e,
            )
        }
        return bundledParsed
    }

    private fun resolveValidatedCachedManifestFor304Locked(): ManifestParseResult? {
        val cachedJson = localStore.getManifestCacheJson()?.takeIf { it.isNotBlank() } ?: return null
        if (localStore.getSubscriptionServerList().isEmpty()) {
            return null
        }
        loadCachedDirectorySummaryLocked()?.let { return it }

        val parsed = parseManifestForDirectory(cachedJson) ?: return null
        if (parsed.servers.isEmpty()) return null
        if (localStore.getSubscriptionServerList().size < parsed.servers.size) {
            return null
        }
        saveCachedDirectorySummary(parsed)
        return parsed
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
            localStore.log(
                android.util.Log.WARN,
                "McQueenServerRepository: failed to parse manifest JSON",
                e,
            )
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
        val scopeHash = manifestValidatorScopeHash(targetUrl, effectiveToken)
        val storedValidator = manifestMutex.withLock {
            localStore.getValidatorState()?.takeIf { it.scopeHash == scopeHash }
        }

        val initialFetch = try {
            fetchManifestConditional(
                url = targetUrl,
                authToken = effectiveToken,
                etag = storedValidator?.etag,
                lastModified = storedValidator?.lastModified,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            localStore.log(android.util.Log.ERROR, "McQueenServerRepository: fetch failed for url=${HttpUtil.redactUrlForLogging(targetUrl)}", e)
            manifestMutex.withLock {
                loadCachedOrBundledManifestLocked(context)
            }
            return@withContext ManifestSyncOutcome.Failure(
                e.message?.takeIf { it.isNotBlank() } ?: "Network error"
            )
        }

        val resolvedFetch = when (initialFetch) {
            is ManifestFetchResult.Modified -> initialFetch

            is ManifestFetchResult.NotModified -> {
                val cachedOutcome = manifestMutex.withLock {
                    val cachedManifest = resolveValidatedCachedManifestFor304Locked()
                    if (cachedManifest != null) {
                        val updatedEtag = initialFetch.etag ?: storedValidator?.etag
                        val updatedLastModified = initialFetch.lastModified ?: storedValidator?.lastModified
                        localStore.setLastSyncAtMs(clockMs())
                        if (!updatedEtag.isNullOrBlank() || !updatedLastModified.isNullOrBlank()) {
                            localStore.setValidatorState(
                                ManifestValidatorState(
                                    scopeHash = scopeHash,
                                    etag = updatedEtag,
                                    lastModified = updatedLastModified,
                                )
                            )
                        }
                        if (urlOverride != null) {
                            localStore.setConfiguredUrlRaw(targetUrl)
                        }
                        ManifestSyncOutcome.Success(manifest = cachedManifest, fromCache = true)
                    } else {
                        localStore.setValidatorState(null)
                        null
                    }
                }
                if (cachedOutcome != null) {
                    return@withContext cachedOutcome
                }

                // 304 received without a valid local manifest cache: perform one unconditional retry.
                try {
                    when (
                        val retryResult = fetchManifestConditional(
                            url = targetUrl,
                            authToken = effectiveToken,
                            etag = null,
                            lastModified = null,
                        )
                    ) {
                        is ManifestFetchResult.Modified -> retryResult
                        is ManifestFetchResult.NotModified -> {
                            return@withContext ManifestSyncOutcome.Failure(
                                "Server returned 304 Not Modified without a valid local manifest"
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    localStore.log(android.util.Log.ERROR, "McQueenServerRepository: unconditional retry failed for url=${HttpUtil.redactUrlForLogging(targetUrl)}", e)
                    manifestMutex.withLock {
                        loadCachedOrBundledManifestLocked(context)
                    }
                    return@withContext ManifestSyncOutcome.Failure(
                        e.message?.takeIf { it.isNotBlank() } ?: "Network error"
                    )
                }
            }
        }

        val parsed = try {
            RemoteServerManifestParser.parse(
                json = resolvedFetch.body,
                isAetherSupported = true,
                subscriptionId = AppConfig.MCQUEEN_SUBSCRIPTION_ID,
                verifyOutbound = ::isOutboundBuildable,
            )
        } catch (e: Exception) {
            localStore.log(android.util.Log.ERROR, "McQueenServerRepository: validation failed", e)
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
                localStore.setManifestCacheJson(resolvedFetch.body)
                localStore.setLastSyncAtMs(clockMs())
                saveCachedDirectorySummary(parsed)
                if (!resolvedFetch.etag.isNullOrBlank() || !resolvedFetch.lastModified.isNullOrBlank()) {
                    localStore.setValidatorState(
                        ManifestValidatorState(
                            scopeHash = scopeHash,
                            etag = resolvedFetch.etag,
                            lastModified = resolvedFetch.lastModified,
                        )
                    )
                } else {
                    localStore.setValidatorState(null)
                }
                if (urlOverride != null) {
                    localStore.setConfiguredUrlRaw(targetUrl)
                }
            }
        } catch (e: Exception) {
            localStore.log(android.util.Log.ERROR, "McQueenServerRepository: storage failed", e)
            return@withContext ManifestSyncOutcome.Failure("Unable to save server list locally")
        }

        ManifestSyncOutcome.Success(manifest = parsed, fromCache = false)
    }

    suspend fun findCompatibilityGuidForCountry(
        countryCode: String,
        activeGuid: String? = null,
        fallbackSelectGuid: String? = MmkvManager.getSelectServer(),
    ): String? = withContext(Dispatchers.IO) {
        val normalizedCode = countryCode.trim().uppercase(Locale.ROOT)
        val countryProfiles = loadStoredProfilesByCountry(context = null)[normalizedCode].orEmpty()
        BypassIranRoutingHelper.selectCompatibilityGuid(
            activeGuid = activeGuid,
            countryProfiles = countryProfiles,
            fallbackSelectGuid = fallbackSelectGuid,
        )
    }

    suspend fun measureCountryLatencies(
        context: Context,
        countries: List<CountryUiModel>,
    ): List<CountryUiModel> = withContext(Dispatchers.IO) {
        val byCountry = loadStoredProfilesByCountry()
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
                            // Passive UI directory scans use lightweight TCP reachability so browsing
                            // locations never initializes libv2ray/Go runtime or spawns tunnel processes
                            // inside the UI process.
                            probeTcpReachability(candidate)
                        }

                        val bestLatency = ranked.firstOrNull { it.latencyMs > 0L }?.latencyMs
                        country.copy(
                            bestLatencyMs = bestLatency,
                            availability = if (bestLatency != null && bestLatency <= 220L) {
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

    private fun isOutboundBuildable(profile: ProfileItem): Boolean = localStore.isOutboundBuildable(profile)

    internal sealed class ManifestFetchResult {
        data class Modified(
            val body: String,
            val etag: String?,
            val lastModified: String?,
        ) : ManifestFetchResult()

        data class NotModified(
            val etag: String?,
            val lastModified: String?,
        ) : ManifestFetchResult()
    }

    internal fun fetchManifestJson(
        url: String,
        authToken: String? = null,
        maxAttempts: Int = DEFAULT_FETCH_MAX_ATTEMPTS,
    ): String {
        return runBlocking {
            when (
                val result = fetchManifestConditional(
                    url = url,
                    authToken = authToken,
                    etag = null,
                    lastModified = null,
                    maxAttempts = maxAttempts,
                )
            ) {
                is ManifestFetchResult.Modified -> result.body
                is ManifestFetchResult.NotModified -> throw IOException("Unexpected HTTP 304 without conditional headers")
            }
        }
    }

    internal suspend fun fetchManifestConditional(
        url: String,
        authToken: String? = null,
        etag: String? = null,
        lastModified: String? = null,
        maxAttempts: Int = DEFAULT_FETCH_MAX_ATTEMPTS,
    ): ManifestFetchResult {
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

                if (sanitizedToken != null) {
                    requestBuilder.header("Authorization", "Bearer $sanitizedToken")
                }
                etag?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    requestBuilder.header("If-None-Match", it)
                }
                lastModified?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    requestBuilder.header("If-Modified-Since", it)
                }

                httpClient.newCall(requestBuilder.build()).awaitResponse().use { response ->
                    if (!McQueenServerConfig.isValidManifestUrl(response.request.url.toString())) {
                        throw NonRetryableManifestException("Refused non-HTTPS manifest response URL")
                    }
                    val code = response.code
                    val responseEtag = response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                    val responseLastModified = response.header("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }

                    if (code == 304) {
                        return ManifestFetchResult.NotModified(
                            etag = responseEtag,
                            lastModified = responseLastModified,
                        )
                    }
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
                    return ManifestFetchResult.Modified(
                        body = body,
                        etag = responseEtag,
                        lastModified = responseLastModified,
                    )
                }
            } catch (e: NonRetryableManifestException) {
                throw IOException(e.message, e)
            } catch (e: IOException) {
                lastException = e
                if (attempt < totalAttempts) {
                    val backoffMs = retryBaseDelayMs * attempt
                    if (backoffMs > 0L) {
                        delay(backoffMs)
                    }
                }
            }
        }

        throw lastException ?: IOException("Failed to fetch servers.json after $totalAttempts attempts")
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            cancel()
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) {
                    cont.resume(response) { _, value, _ ->
                        value.close()
                    }
                } else {
                    response.close()
                }
            }
        })
    }

    private class NonRetryableManifestException(message: String) : IOException(message)

    internal fun deterministicServerGuid(countryCode: String, serverId: String): String {
        val key = "mcqueen:${countryCode.trim().uppercase(Locale.ROOT)}|${serverId.trim()}"
        return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8))
            .toString()
            .replace("-", "")
    }

    private fun persistServersToMmkv(servers: List<ValidatedServer>) {
        localStore.persistServers(servers, ::deterministicServerGuid)
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
        private val sharedDefaultHttpClient: OkHttpClient by lazy { defaultHttpClient() }

        internal fun manifestValidatorScopeHash(url: String, authToken: String?): String {
            val normalizedUrl = url.trim()
            val normalizedToken = authToken?.trim().orEmpty()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$normalizedUrl\u0000$normalizedToken".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun engineFor(profile: ProfileItem): TunnelEngine = engineFor(profile.configType)

        fun engineFor(configType: EConfigType): TunnelEngine =
            if (configType == EConfigType.AETHER) TunnelEngine.AETHER else TunnelEngine.XRAY

        fun defaultHttpClient(): OkHttpClient {
            return HttpUtil.baseClient().newBuilder()
                .connectTimeout(McQueenServerConfig.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(McQueenServerConfig.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(McQueenServerConfig.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor { chain ->
                    var currentRequest = chain.request().newBuilder()
                        .header("User-Agent", "McQueenVPN/${BuildConfig.VERSION_NAME}")
                        .build()
                    var response = chain.proceed(currentRequest)
                    var redirectCount = 0
                    val maxRedirects = 5

                    while (response.code in listOf(301, 302, 303, 307, 308)) {
                        val location = response.header("Location")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: break
                        val resolvedUrl = currentRequest.url.resolve(location)
                        if (resolvedUrl == null || !McQueenServerConfig.isValidManifestUrl(resolvedUrl.toString())) {
                            response.close()
                            throw NonRetryableManifestException("Blocked insecure redirect to non-HTTPS URL")
                        }
                        if (++redirectCount > maxRedirects) {
                            response.close()
                            throw NonRetryableManifestException("Too many redirects while fetching servers.json")
                        }

                        val nextBuilder = currentRequest.newBuilder().url(resolvedUrl)
                        if (!resolvedUrl.host.equals(currentRequest.url.host, ignoreCase = true) ||
                            resolvedUrl.port != currentRequest.url.port
                        ) {
                            nextBuilder.removeHeader("Authorization")
                        }
                        response.close()
                        currentRequest = nextBuilder.build()
                        response = chain.proceed(currentRequest)
                    }
                    response
                }
                .build()
        }
    }
}

private data class CachedManifestSummary(
    val version: Int = 1,
    val countries: List<CountryUiModel> = emptyList(),
    val skippedCount: Int = 0,
    val updatedAt: String? = null,
)
