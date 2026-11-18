package com.v2ray.ang.mcqueen

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.BypassIranRoutingHelper
import com.v2ray.ang.dto.TrafficStatsSnapshot
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.ui.main.CONNECTED_HALO_STATIC_ALPHA
import com.v2ray.ang.ui.main.ConnectButtonState
import com.v2ray.ang.ui.main.McQueenRuntimeBridge
import com.v2ray.ang.ui.main.McQueenUiState
import com.v2ray.ang.ui.main.McQueenViewModel
import com.v2ray.ang.ui.main.resolveConnectButtonState
import com.v2ray.ang.ui.main.shouldAnimateConnectRing
import com.v2ray.ang.ui.main.shouldAnimateConnectedHalo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class Phase4UiAndRuntimePerformanceTest {

    private class FakeRuntimeBridge : McQueenRuntimeBridge {
        var selectServerGuid: String? = "de-1"
        var persistedCountryCode: String? = "DE"
        var persistedCountryName: String? = "Germany"
        var cachedManifest: ManifestParseResult? = ManifestParseResult(
            version = 1,
            updatedAt = "2026-11-17T10:00:00Z",
            servers = emptyList(),
            countries = listOf(
                CountryUiModel("DE", "Germany", "🇩🇪", 1, bestLatencyMs = 42L),
                CountryUiModel("NL", "Netherlands", "🇳🇱", 1, bestLatencyMs = 55L),
                CountryUiModel("US", "United States", "🇺🇸", 1, bestLatencyMs = 120L),
            ),
            skippedCount = 0,
        )
        var candidatesByCountry: Map<String, List<String>> = mapOf(
            "DE" to listOf("de-1"),
            "NL" to listOf("nl-1"),
            "US" to listOf("us-1"),
        )
        val sentServiceMessages = mutableListOf<Triple<Int, String, String?>>()
        val startedGuids = mutableListOf<String>()
        var stopServiceCalls = 0
        var receiverRegisteredCount = 0
        var receiverUnregisteredCount = 0
        var pendingIdentityProvisioning = false

        override fun registerReceiver(receiver: BroadcastReceiver) {
            receiverRegisteredCount++
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            receiverUnregisteredCount++
        }

        override fun sendMsg2Service(what: Int, content: String, requestId: String?) {
            sentServiceMessages += Triple(what, content, requestId)
        }

        override fun startService(guid: String) {
            startedGuids += guid
        }

        override fun stopService() {
            stopServiceCalls++
        }

        override fun getSelectServer(): String? = selectServerGuid

        override fun setSelectServer(guid: String) {
            selectServerGuid = guid
        }

        override fun getSelectedCountryCode(): String? = persistedCountryCode

        override fun getSelectedCountryName(): String? = persistedCountryName

        override fun saveSelectedCountry(country: CountryUiModel) {
            persistedCountryCode = country.countryCode
            persistedCountryName = country.countryName
        }

        override fun clearSelectedCountry() {
            persistedCountryCode = null
            persistedCountryName = null
        }

        override fun getConfiguredManifestUrl(): String = "https://example.com/servers.json"

        override fun hasConfiguredAuthToken(): Boolean = false

        override fun saveCustomManifestConfig(
            url: String,
            authTokenInput: String?,
            clearAuthToken: Boolean,
        ): Boolean = true

        override fun resetCustomManifestUrl() = Unit

        override suspend fun loadCachedManifest(): ManifestParseResult? = cachedManifest

        override suspend fun isManifestCacheFresh(): Boolean = true

        override suspend fun syncManifestFromGitHub(): ManifestSyncOutcome =
            ManifestSyncOutcome.Success(
                cachedManifest ?: ManifestParseResult(
                    version = 1,
                    updatedAt = null,
                    servers = emptyList(),
                    countries = emptyList(),
                    skippedCount = 0,
                ),
                fromCache = true,
            )

        override suspend fun measureCountryLatencies(countries: List<CountryUiModel>): List<CountryUiModel> = countries

        override suspend fun hasPendingIdentityProvisioning(countryCode: String): Boolean = pendingIdentityProvisioning

        override suspend fun ensureIdentitiesForCountry(countryCode: String): CountryIdentityStatus =
            CountryIdentityStatus.Ready(newlyProvisioned = false)

        override suspend fun selectCandidateGuidsForCountry(countryCode: String): List<String> =
            candidatesByCountry[countryCode].orEmpty()

        override suspend fun checkBypassIranAvailability(
            countryCode: String?,
            activeGuid: String?,
        ): BypassIranRoutingHelper.AvailabilityStatus = BypassIranRoutingHelper.AvailabilityStatus.Supported(
            geoStatus = BypassIranRoutingHelper.GeoDataStatus(
                hasGeoipDat = true,
                hasGeositeDat = true,
                hasGeoipIr = true,
                hasGeositeCategoryIr = true,
                ipv4CidrCount = 10,
                ipv6CidrCount = 2,
                irDomainCount = 10,
            ),
            engineLabel = "Xray",
        )

        override fun isBypassIranEnabled(): Boolean = true

        override fun setBypassIranEnabled(enabled: Boolean) = Unit

        override fun getThemeMode(): String = ThemeManager.MODE_SYSTEM

        override fun setThemeMode(mode: String) = Unit

        override fun refreshThemeMode() = Unit

        override fun isBatteryOptimizationExempt(): Boolean = true

        override fun markBatteryPromptShown() = Unit

        override fun schedulePeriodicManifestSync(forceReschedule: Boolean) = Unit

        override suspend fun checkLatestRelease(): AppUpdateCheckResult = AppUpdateCheckResult.Idle

        override fun string(resId: Int, vararg args: Any): String = when (resId) {
            R.string.app_name -> "McQueen VPN"
            R.string.mcqueen_verifying_tunnel -> "Verifying tunnel"
            R.string.mcqueen_switching_country -> "Switching to ${args.firstOrNull() ?: ""}"
            R.string.mcqueen_testing_country -> "Testing ${args.firstOrNull() ?: ""}"
            R.string.mcqueen_preparing_secure_connection -> "Preparing"
            else -> "str_$resId"
        }

        override fun logInfo(message: String) = Unit

        override fun logWarn(message: String, throwable: Throwable?) = Unit
    }

    private fun messageIntent(
        key: Int,
        content: String = "",
        sessionStartMs: Long = 0L,
        trafficSnapshot: TrafficStatsSnapshot? = null,
        requestId: String = "",
        delayMs: Long? = null,
    ): Intent = mock {
        on { getIntExtra("key", 0) } doReturn key
        on { getStringExtra("content") } doReturn content
        on { getLongExtra(MessageHelper.EXTRA_SESSION_START_MS, 0L) } doReturn sessionStartMs
        on { getStringExtra(MessageHelper.EXTRA_REQUEST_ID) } doReturn requestId
        on { hasExtra(MessageHelper.EXTRA_DELAY_MS) } doReturn (delayMs != null)
        on { getLongExtra(MessageHelper.EXTRA_DELAY_MS, -1L) } doReturn (delayMs ?: -1L)
        @Suppress("DEPRECATION")
        on { getSerializableExtra("content") } doReturn trafficSnapshot
        on { getSerializableExtra("content", TrafficStatsSnapshot::class.java) } doReturn trafficSnapshot
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. State equality / non-duplicate UI state updates
    @Test
    fun `identical telemetry, connecting, and idle stop messages do not emit duplicate UiState updates`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        var wallNowMs = 1_000_000L
        var elapsedNowMs = 5_000L
        val bridge = FakeRuntimeBridge()
        val app: Application = mock()
        val vm = McQueenViewModel(
            application = app,
            runtimeBridge = bridge,
            wallClockMs = { wallNowMs },
            elapsedClockMs = { elapsedNowMs },
            ioDispatcher = dispatcher,
            initializeOnCreate = false,
        )

        val emissions = mutableListOf<McQueenUiState>()
        val collectJob: Job = backgroundScope.launch {
            vm.uiState.collect { emissions += it }
        }
        runCurrent()
        emissions.clear()

        // Duplicate MSG_STATE_NOT_RUNNING when already DISCONNECTED emits zero updates.
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_NOT_RUNNING))
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_NOT_RUNNING))
        runCurrent()
        assertEquals(0, emissions.size)

        // Duplicate MSG_STATE_CONNECTING with same detail emits only once.
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_CONNECTING, content = "Connecting"))
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_CONNECTING, content = "Connecting"))
        runCurrent()
        assertEquals(1, emissions.size)

        // Transition to CONNECTED via MSG_STATE_RUNNING.
        vm.handleServiceMessage(
            messageIntent(AppConfig.MSG_STATE_RUNNING, sessionStartMs = wallNowMs)
        )
        runCurrent()
        emissions.clear()

        // Duplicate identical traffic stats within the same second emit only once.
        val snap = TrafficStatsSnapshot(
            uplinkBps = 128L,
            downlinkBps = 1024L,
            sessionStartMs = wallNowMs,
            sessionUplinkBytes = 500L,
            sessionDownlinkBytes = 2_000L,
        )
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_TRAFFIC_STATS, trafficSnapshot = snap))
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_TRAFFIC_STATS, trafficSnapshot = snap))
        runCurrent()
        assertEquals(1, emissions.size)

        collectJob.cancel()
        vm.onCleared()
    }

    // 2 & 3. Absence of duplicate polling loops + accurate atomic duration & traffic totals
    @Test
    fun `traffic stats drive atomic speed, totals, and duration in one emission per second without overlapping ticker emit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        var wallNowMs = 2_000_000L
        var elapsedNowMs = 10_000L
        val bridge = FakeRuntimeBridge()
        val vm = McQueenViewModel(
            application = mock(),
            runtimeBridge = bridge,
            wallClockMs = { wallNowMs },
            elapsedClockMs = { elapsedNowMs },
            ioDispatcher = dispatcher,
            initializeOnCreate = false,
        )

        val emissions = mutableListOf<McQueenUiState>()
        val collectJob = backgroundScope.launch { vm.uiState.collect { emissions += it } }
        runCurrent()

        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_RUNNING, sessionStartMs = wallNowMs))
        runCurrent()
        assertEquals(VpnConnectionState.CONNECTED, vm.uiState.value.connectionState)
        assertTrue(vm.isDurationTickerActive)
        emissions.clear()

        // Simulate 3 seconds where daemon MSG_TRAFFIC_STATS arrives every 1,000ms.
        for (sec in 1L..3L) {
            wallNowMs += 1_000L
            elapsedNowMs += 1_000L
            advanceTimeBy(1_000L)
            vm.handleServiceMessage(
                messageIntent(
                    AppConfig.MSG_TRAFFIC_STATS,
                    trafficSnapshot = TrafficStatsSnapshot(
                        uplinkBps = sec * 100L,
                        downlinkBps = sec * 500L,
                        sessionStartMs = 2_000_000L,
                        sessionUplinkBytes = sec * 1_000L,
                        sessionDownlinkBytes = sec * 5_000L,
                    ),
                )
            )
            runCurrent()
        }

        // Expect strictly 1 emission per second (3 total, NOT 6 from dual duration + traffic loops).
        assertEquals(3, emissions.size)
        val latest = emissions.last()
        assertEquals(3L, latest.sessionDurationSeconds)
        assertEquals(1_500L, latest.downloadBps)
        assertEquals(300L, latest.uploadBps)
        assertEquals(15_000L, latest.sessionDownloadedBytes)
        assertEquals(3_000L, latest.sessionUploadedBytes)

        // If traffic broadcasts stop arriving (e.g. >1150ms silent), coordinated fallback ticker advances duration.
        wallNowMs += 2_000L
        elapsedNowMs += 2_000L
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(5L, vm.uiState.value.sessionDurationSeconds)

        collectJob.cancel()
        vm.onCleared()
    }

    // 4. ConnectButton animation gating logic
    @Test
    fun `ConnectButton gates infinite animations by state and host visibility`() {
        assertEquals(ConnectButtonState.Disconnected, resolveConnectButtonState(VpnConnectionState.DISCONNECTED))
        assertEquals(ConnectButtonState.Disconnected, resolveConnectButtonState(VpnConnectionState.FAILED))
        assertEquals(ConnectButtonState.Connecting, resolveConnectButtonState(VpnConnectionState.CONNECTING))
        assertEquals(ConnectButtonState.Connected, resolveConnectButtonState(VpnConnectionState.CONNECTED))

        // Disconnected never animates ring or halo regardless of visibility.
        assertFalse(shouldAnimateConnectRing(ConnectButtonState.Disconnected, isVisible = true))
        assertFalse(shouldAnimateConnectedHalo(ConnectButtonState.Disconnected, isVisible = true))

        // Connecting runs only the orbital ring animation when visible.
        assertTrue(shouldAnimateConnectRing(ConnectButtonState.Connecting, isVisible = true))
        assertFalse(shouldAnimateConnectRing(ConnectButtonState.Connecting, isVisible = false))
        assertFalse(shouldAnimateConnectedHalo(ConnectButtonState.Connecting, isVisible = true))

        // Connected runs only the halo pulse animation when visible, falling back to static alpha when hidden.
        assertTrue(shouldAnimateConnectedHalo(ConnectButtonState.Connected, isVisible = true))
        assertFalse(shouldAnimateConnectedHalo(ConnectButtonState.Connected, isVisible = false))
        assertFalse(shouldAnimateConnectRing(ConnectButtonState.Connected, isVisible = true))
        assertTrue(CONNECTED_HALO_STATIC_ALPHA in 0.2f..0.4f)
    }

    // 5. Cross-process connection state consistency & event-driven stop await
    @Test
    fun `UI recreation adopts daemon sessionStartMs and uses event-driven stop signal during country switch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val wallNowMs = 5_000_000L
        val daemonStartedAtMs = wallNowMs - 45_000L
        val bridge = FakeRuntimeBridge().apply {
            selectServerGuid = "de-1"
            persistedCountryCode = "DE"
            persistedCountryName = "Germany"
        }
        val vm = McQueenViewModel(
            application = mock(),
            runtimeBridge = bridge,
            wallClockMs = { wallNowMs },
            elapsedClockMs = { 20_000L },
            ioDispatcher = dispatcher,
            initializeOnCreate = false,
        )

        // Recreated UI receives MSG_STATE_RUNNING from :daemon with authoritative sessionStartMs before manifest loads.
        vm.handleServiceMessage(
            messageIntent(AppConfig.MSG_STATE_RUNNING, sessionStartMs = daemonStartedAtMs)
        )
        runCurrent()

        assertEquals(VpnConnectionState.CONNECTED, vm.uiState.value.connectionState)
        assertEquals(45L, vm.uiState.value.sessionDurationSeconds)
        assertEquals(daemonStartedAtMs, vm.trackedSessionStartTimeMs)
        assertTrue(vm.isServiceRunningState)

        // Triggering a country switch while service is running suspends on CompletableDeferred (no 40ms polling)
        // and resumes immediately when MSG_STATE_STOP_SUCCESS arrives.
        vm.selectCountry(CountryUiModel("NL", "Netherlands", "🇳🇱", 1))
        runCurrent()
        assertEquals(VpnConnectionState.RECONNECTING, vm.uiState.value.connectionState)
        assertTrue(vm.isPendingStopSignalActive)
        assertTrue(bridge.sentServiceMessages.any { it.first == AppConfig.MSG_STATE_STOP })
        assertTrue(bridge.startedGuids.isEmpty())

        // Daemon finishes stopping and broadcasts MSG_STATE_STOP_SUCCESS -> switch proceeds immediately without flashing DISCONNECTED.
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_STOP_SUCCESS))
        runCurrent()

        assertFalse(vm.isPendingStopSignalActive)
        assertEquals(VpnConnectionState.RECONNECTING, vm.uiState.value.connectionState)
        assertEquals(listOf("nl-1"), bridge.startedGuids)

        vm.onCleared()
    }

    // 6. Handover & query transitions without invalid DISCONNECTED UI states
    @Test
    fun `passive MSG_STATE_NOT_RUNNING does not reset PREPARING, CONNECTING, or RECONNECTING to DISCONNECTED`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val coordinator = ConnectionSessionCoordinator()
        assertEquals(VpnConnectionState.PREPARING, coordinator.startPreparing("DE"))
        assertEquals(VpnConnectionState.PREPARING, coordinator.onServiceNotRunningQuery())

        assertEquals("de-1", coordinator.startAttempt(listOf("de-1"), "DE"))
        assertEquals(VpnConnectionState.CONNECTING, coordinator.onServiceNotRunningQuery())

        coordinator.beginCountrySwitch("NL")
        assertEquals(VpnConnectionState.RECONNECTING, coordinator.onServiceNotRunningQuery())

        assertEquals(VpnConnectionState.CONNECTED, coordinator.onStartSuccess())
        assertEquals(VpnConnectionState.DISCONNECTED, coordinator.onServiceNotRunningQuery())

        // Verify full ViewModel preservation during active connect / permission return resume query.
        val bridge = FakeRuntimeBridge().apply {
            pendingIdentityProvisioning = false
        }
        val vm = McQueenViewModel(
            application = mock(),
            runtimeBridge = bridge,
            ioDispatcher = dispatcher,
            initializeOnCreate = true,
        )
        runCurrent()

        vm.bindStartVpnCallback { /* wait at permission / service launch */ }
        vm.toggleConnection()
        runCurrent()
        assertEquals(VpnConnectionState.CONNECTING, vm.uiState.value.connectionState)

        // Resuming from VPN permission dialog triggers MSG_REGISTER_CLIENT -> daemon replies MSG_STATE_NOT_RUNNING.
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_NOT_RUNNING))
        runCurrent()
        assertEquals(VpnConnectionState.CONNECTING, vm.uiState.value.connectionState)

        vm.onCleared()
    }

    // 7. NetworkMonitor duplicate-callback prevention & lifecycle cleanup
    @Test
    fun `NetworkMonitor deduplicates identical capability callbacks, protects active upstream on stale onLost, and blocks post-unregister callbacks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connectivity: ConnectivityManager = mock()
        val networkRequest: NetworkRequest = mock()
        val networkA: Network = mock()
        val networkB: Network = mock()
        val caps: NetworkCapabilities = mock()

        val dispatchedNetworks = mutableListOf<List<Network>?>()
        var claimedCount = 0
        var handoverCount = 0

        val monitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> dispatchedNetworks += networks?.toList() },
            claimHandover = {
                claimedCount++
                true
            },
            onHandover = { handoverCount++ },
            describe = { "test" },
            buildRequest = { networkRequest },
            handoverDispatcher = dispatcher,
            handoverDebounceMs = 100L,
            logInfo = {},
        )

        val callbackCaptor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        monitor.register()
        verify(connectivity).requestNetwork(any(), callbackCaptor.capture())
        val callback = callbackCaptor.firstValue

        // Initial availability + storm of capability updates on same Network A -> single dispatch.
        callback.onAvailable(networkA)
        callback.onCapabilitiesChanged(networkA, caps)
        callback.onCapabilitiesChanged(networkA, caps)
        callback.onAvailable(networkA)
        assertEquals(listOf(listOf(networkA)), dispatchedNetworks)
        assertEquals(0, handoverCount)

        // Make-before-break handover: Network B becomes available, then delayed onLost(A) fires.
        callback.onAvailable(networkB)
        callback.onCapabilitiesChanged(networkB, caps)
        callback.onLost(networkA) // stale onLost from replaced Network A must NOT clear Network B!
        assertEquals(listOf(listOf(networkA), listOf(networkB)), dispatchedNetworks)

        // Debounced handover completes once.
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(1, claimedCount)
        assertEquals(1, handoverCount)

        // Active upstream B lost -> dispatches null once; duplicate onLost(B) is deduplicated.
        callback.onLost(networkB)
        callback.onLost(networkB)
        assertEquals(listOf(listOf(networkA), listOf(networkB), null), dispatchedNetworks)

        // After unregister, late callbacks do nothing and schedule zero handovers.
        monitor.unregister()
        callback.onAvailable(networkA)
        callback.onCapabilitiesChanged(networkA, caps)
        callback.onLost(networkA)
        advanceTimeBy(200L)
        runCurrent()

        assertEquals(3, dispatchedNetworks.size)
        assertEquals(1, handoverCount)
    }

    // 8. Cancellation and lifecycle edge cases
    @Test
    fun `onHostStopped pauses duration ticker while preserving start time, onHostStarted resumes it, and onCleared cleans everything`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        var wallNowMs = 10_000_000L
        var elapsedNowMs = 50_000L
        val bridge = FakeRuntimeBridge()
        val vm = McQueenViewModel(
            application = mock(),
            runtimeBridge = bridge,
            wallClockMs = { wallNowMs },
            elapsedClockMs = { elapsedNowMs },
            ioDispatcher = dispatcher,
            initializeOnCreate = true,
        )
        runCurrent()
        assertEquals(1, bridge.receiverRegisteredCount)

        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_RUNNING, sessionStartMs = wallNowMs))
        runCurrent()
        assertTrue(vm.isDurationTickerActive)

        // App moves to background -> ticker pauses without clearing sessionStartTimeMs.
        vm.onHostStopped()
        assertFalse(vm.isDurationTickerActive)
        assertEquals(10_000_000L, vm.trackedSessionStartTimeMs)

        // Time advances while backgrounded; returning to foreground immediately updates duration and restarts ticker.
        wallNowMs += 12_000L
        elapsedNowMs += 12_000L
        vm.onHostStarted()
        assertEquals(12L, vm.uiState.value.sessionDurationSeconds)
        assertTrue(vm.isDurationTickerActive)

        // Disconnect clears telemetry cleanly.
        vm.disconnectVpn()
        runCurrent()
        assertFalse(vm.isDurationTickerActive)
        assertEquals(0L, vm.uiState.value.sessionDurationSeconds)
        assertEquals(VpnConnectionState.DISCONNECTED, vm.uiState.value.connectionState)

        // onCleared unregisters receiver and cancels remaining jobs.
        vm.onCleared()
        assertEquals(1, bridge.receiverUnregisteredCount)
        assertFalse(vm.isPendingStopSignalActive)
    }
}
