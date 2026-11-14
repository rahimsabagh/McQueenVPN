package com.v2ray.ang.mcqueen

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.AetherDelayTester
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.CoreOutboundBuilder
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

class McQueenServerRepository(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    fun getConfiguredUrl(): String {
        val stored = MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_SERVERS_URL)
        val resolved = McQueenServerConfig.resolveActiveUrl(stored)
        if (!stored.isNullOrBlank() && stored != resolved) {
            MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SERVERS_URL, resolved)
        }
        return resolved
    }

    fun saveCustomUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!McQueenServerConfig.isValidManifestUrl(trimmed)) {
            return false
        }
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SERVERS_URL, trimmed)
        return true
    }

    fun resetCustomUrl() {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SERVERS_URL, McQueenServerConfig.DEFAULT_SERVERS_URL)
    }

    suspend fun loadCachedManifest(context: Context): ManifestParseResult? = withContext(Dispatchers.IO) {
        val cachedJson = MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_MANIFEST_CACHE)
        if (cachedJson.isNullOrBlank()) return@withContext null

        val parsed = runCatching {
            RemoteServerManifestParser.parse(
                json = cachedJson,
                isAetherSupported = AetherCoreManager.isSupported(context),
                subscriptionId = AppConfig.MCQUEEN_SUBSCRIPTION_ID,
                verifyOutbound = ::isOutboundBuildable,
            )
        }.getOrNull() ?: return@withContext null

        if (parsed.servers.isEmpty()) return@withContext null

        val existingGuids = MmkvManager.decodeServerList(AppConfig.MCQUEEN_SUBSCRIPTION_ID)
        if (existingGuids.isEmpty()) {
            persistServersToMmkv(parsed.servers)
        }
        parsed
    }

    suspend fun syncFromGitHub(
        context: Context,
        urlOverride: String? = null,
    ): ManifestSyncOutcome = withContext(Dispatchers.IO) {
        val targetUrl = urlOverride?.trim()?.takeIf { it.isNotEmpty() } ?: getConfiguredUrl()
        if (!McQueenServerConfig.isValidManifestUrl(targetUrl)) {
            return@withContext ManifestSyncOutcome.Failure("Invalid HTTPS server directory URL")
        }

        val responseJson = try {
            fetchManifestJson(targetUrl)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "McQueenServerRepository: fetch failed", e)
            return@withContext ManifestSyncOutcome.Failure(
                e.message?.takeIf { it.isNotBlank() } ?: "Network error"
            )
        }

        val parsed = try {
            RemoteServerManifestParser.parse(
                json = responseJson,
                isAetherSupported = AetherCoreManager.isSupported(context),
                subscriptionId = AppConfig.MCQUEEN_SUBSCRIPTION_ID,
                verifyOutbound = ::isOutboundBuildable,
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "McQueenServerRepository: validation failed", e)
            return@withContext ManifestSyncOutcome.Failure(
                e.message?.takeIf { it.isNotBlank() } ?: "Invalid manifest format"
            )
        }

        if (parsed.servers.isEmpty()) {
            return@withContext ManifestSyncOutcome.Failure("No enabled usable servers found in manifest")
        }

        try {
            persistServersToMmkv(parsed.servers)
            MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_MANIFEST_CACHE, responseJson)
            if (urlOverride != null) {
                MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SERVERS_URL, targetUrl)
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
                            if (profile != null && profile.configType != EConfigType.AETHER) {
                                probeRealOutboundDelay(context, candidate.guid, profile)
                            } else {
                                // Avoid spawning multiple full Aether/Tor/Psiphon sessions during passive list scan;
                                // real tunnel verification runs at connect time for Aether candidates.
                                probeTcpReachability(candidate)
                            }
                        }

                        val bestReal = ranked.firstOrNull { candidate ->
                            val profile = profileMap[candidate.guid]
                            candidate.latencyMs > 0L && profile?.configType != EConfigType.AETHER
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

    suspend fun selectCandidatesForCountry(
        context: Context,
        countryCode: String,
    ): List<CandidateLatency> = withContext(Dispatchers.IO) {
        val normalizedCode = countryCode.trim().uppercase(Locale.ROOT)
        val candidates = loadStoredProfilesByCountry()[normalizedCode].orEmpty()
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
        if (profile.configType == EConfigType.AETHER) {
            val realDelay = runCatching {
                AetherDelayTester.measure(
                    context = context,
                    guid = guid,
                    profile = profile,
                    url = SettingsManager.getDelayTestUrl(),
                )
            }.getOrDefault(-1L)
            return if (realDelay > 0L) realDelay else probeTcpReachability(candidate)
        }
        return probeRealOutboundDelay(context, guid, profile)
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

    private fun fetchManifestJson(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!McQueenServerConfig.isValidManifestUrl(response.request.url.toString())) {
                throw IOException("Refused non-HTTPS manifest response URL")
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} while fetching servers.json")
            }
            return response.body.string()
        }
    }

    private fun persistServersToMmkv(servers: List<ValidatedServer>) {
        val profiles = linkedMapOf<String, ProfileItem>()
        servers.forEach { server ->
            val guid = Utils.getUuid()
            profiles[guid] = server.profile
        }
        MmkvManager.saveServerProfiles(
            profiles = profiles,
            rawConfigs = emptyMap(),
            subscriptionId = AppConfig.MCQUEEN_SUBSCRIPTION_ID,
            append = false,
        )
    }

    private fun loadStoredProfilesByCountry(): Map<String, List<Pair<String, ProfileItem>>> {
        val guids = MmkvManager.decodeServerList(AppConfig.MCQUEEN_SUBSCRIPTION_ID)
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
