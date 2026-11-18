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
import com.v2ray.ang.core.ReloadOutcome
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.TrafficStatsSnapshot
import com.v2ray.ang.handler.SpeedNotificationController
import com.v2ray.ang.handler.VpnTrafficTracker
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DaemonStartGate
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.ui.main.McQueenRuntimeBridge
import com.v2ray.ang.ui.main.McQueenViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class Phase5ReliabilityAndLifecycleTest {

    private class FakeRuntimeBridge : McQueenRuntimeBridge {
        var selectServerGuid: String? = "de-1"
        var persistedCountryCode: String? = "DE"
        var persistedCountryName: String? = "Germany"
        var cachedManifest: ManifestParseResult? = ManifestParseResult(
            version = 1,
            updatedAt = "2026-11-17T10:00:00Z",
            servers = emptyList(),
            countries = listOf(
                CountryUiModel("DE", "Germany", "🇩🇪", 2, bestLatencyMs = 42L),
                CountryUiModel("NL", "Netherlands", "🇳🇱", 2, bestLatencyMs = 55L),
                CountryUiModel("US", "United States", "🇺🇸", 1, bestLatencyMs = 120L),
            ),
            skippedCount = 0,
        )
        var candidatesByCountry: Map<String, List<String>> = mapOf(
            "DE" to listOf("de-1", "de-2"),
            "NL" to listOf("nl-1", "nl-2"),
            "US" to listOf("us-1"),
        )
        var identityDeferredByCountry: MutableMap<String, CompletableDeferred<CountryIdentityStatus>> = mutableMapOf()
        var pendingProvisioningCountries: MutableSet<String> = mutableSetOf()
        var syncDeferred: CompletableDeferred<ManifestSyncOutcome>? = null
        var releaseDeferred: CompletableDeferred<AppUpdateCheckResult>? = null

        val sentServiceMessages = mutableListOf<Triple<Int, String, String?>>()
        val startedGuids = mutableListOf<String>()
        var stopServiceCalls = 0
        var receiverRegisteredCount = 0
        var receiverUnregisteredCount = 0

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
            syncDeferred?.await() ?: ManifestSyncOutcome.Success(
                cachedManifest ?: ManifestParseResult(1, null, emptyList(), emptyList(), 0),
                fromCache = true,
            )

        override suspend fun measureCountryLatencies(countries: List<CountryUiModel>): List<CountryUiModel> = countries

        override suspend fun hasPendingIdentityProvisioning(countryCode: String): Boolean =
            countryCode in pendingProvisioningCountries

        override suspend fun ensureIdentitiesForCountry(countryCode: String): CountryIdentityStatus {
            val deferred = identityDeferredByCountry[countryCode]
            return deferred?.await() ?: CountryIdentityStatus.Ready(newlyProvisioned = false)
        }

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

        override suspend fun checkLatestRelease(): AppUpdateCheckResult =
            releaseDeferred?.await() ?: AppUpdateCheckResult.Idle

        override fun string(resId: Int, vararg args: Any): String = when (resId) {
            R.string.app_name -> "McQueen VPN"
            R.string.mcqueen_verifying_tunnel -> "Verifying tunnel"
            R.string.mcqueen_switching_country -> "Switching to ${args.firstOrNull() ?: ""}"
            R.string.mcqueen_testing_country -> "Testing ${args.firstOrNull() ?: ""}"
            R.string.mcqueen_preparing_secure_connection -> "Preparing"
            R.string.mcqueen_failover_notice -> "Trying next in ${args.firstOrNull() ?: ""}"
            R.string.mcqueen_all_candidates_failed -> "All failed in ${args.firstOrNull() ?: ""}"
            R.string.mcqueen_connection_timeout -> "Timed out"
            R.string.mcqueen_tunnel_check_failed -> "Tunnel check failed"
            R.string.mcqueen_vpn_permission_denied -> "Permission denied"
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

    // Scenario 1: Concurrent connection requests
    @Test
    fun `1 concurrent connect then switch cancels earlier operation and starts only latest candidate`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val deProvisioning = CompletableDeferred<CountryIdentityStatus>()
        val bridge = FakeRuntimeBridge().apply {
            pendingProvisioningCountries += "DE"
            identityDeferredByCountry["DE"] = deProvisioning
        }
        val vm = McQueenViewModel(
            application = mock<Application>(),
            runtimeBridge = bridge,
            ioDispatcher = dispatcher,
            initializeOnCreate = true,
        )
        runCurrent()

        // Start DE connect (suspends at identity provisioning)
        vm.toggleConnection()
        runCurrent()
        assertEquals(VpnConnectionState.PREPARING, vm.uiState.value.connectionState)

        // Rapidly switch to NL before DE finishes provisioning
        vm.selectCountry(CountryUiModel("NL", "Netherlands", "🇳🇱", 2))
        runCurrent()
        assertEquals(VpnConnectionState.RECONNECTING, vm.uiState.value.connectionState)
        assertEquals(listOf("nl-1"), bridge.startedGuids)

        // Old DE provisioning resolves late -> must remain ignored
        deProvisioning.complete(CountryIdentityStatus.Ready(newlyProvisioned = true))
        runCurrent()
        assertEquals(listOf("nl-1"), bridge.startedGuids)
        assertEquals("NL", vm.uiState.value.selectedCountry?.countryCode)

        vm.onCleared()
    }

    // Scenario 2: Repeated disconnect requests & disconnect-then-immediate-reconnect
    @Test
    fun `2 repeated disconnect is idempotent and immediate reconnect awaits trailing stop ack without flashing DISCONNECTED`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val bridge = FakeRuntimeBridge()
        val vm = McQueenViewModel(
            application = mock(),
            runtimeBridge = bridge,
            wallClockMs = { 10_000L },
            elapsedClockMs = { 1_000L },
            ioDispatcher = dispatcher,
            initializeOnCreate = true,
        )
        runCurrent()

        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_RUNNING, sessionStartMs = 9_000L))
        runCurrent()
        assertEquals(VpnConnectionState.CONNECTED, vm.uiState.value.connectionState)

        // Repeated disconnect calls
        vm.disconnectVpn()
        vm.disconnectVpn()
        runCurrent()
        assertEquals(VpnConnectionState.DISCONNECTED, vm.uiState.value.connectionState)
        assertEquals(2, bridge.stopServiceCalls)

        // Reconnect immediately before :daemon's async MSG_STATE_STOP_SUCCESS arrives
        vm.toggleConnection()
        runCurrent()
        assertEquals(VpnConnectionState.PREPARING, vm.uiState.value.connectionState)
        assertTrue(bridge.startedGuids.isEmpty())

        // Delayed STOP_SUCCESS from previous disconnect wakes reconnect cleanly without flashing DISCONNECTED
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_STOP_SUCCESS))
        runCurrent()
        assertEquals(VpnConnectionState.CONNECTING, vm.uiState.value.connectionState)
        assertEquals(listOf("de-1"), bridge.startedGuids)

        vm.onCleared()
    }

    // Scenario 3: Disconnect during connection startup (and VPN permission denial cleanup)
    @Test
    fun `3 disconnect during startup and permission denial cancel jobs and block late permission launch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val bridge = FakeRuntimeBridge()
        val vm = McQueenViewModel(
            application = mock(),
            runtimeBridge = bridge,
            ioDispatcher = dispatcher,
            initializeOnCreate = true,
        )
        runCurrent()

        var permissionPrompts = 0
        vm.bindStartVpnCallback { permissionPrompts++ }

        vm.toggleConnection()
        runCurrent()
        assertEquals(1, permissionPrompts)
        assertEquals(VpnConnectionState.CONNECTING, vm.uiState.value.connectionState)

        // User taps Disconnect while VPN permission prompt is visible
        vm.disconnectVpn()
        runCurrent()
        assertEquals(VpnConnectionState.DISCONNECTED, vm.uiState.value.connectionState)

        // Late permission acceptance or delayed daemon callback must be ignored
        vm.launchServiceAfterPermission()
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_START_SUCCESS, sessionStartMs = 5_000L))
        runCurrent()
        assertTrue(bridge.startedGuids.isEmpty())
        assertEquals(VpnConnectionState.DISCONNECTED, vm.uiState.value.connectionState)

        // Now test onVpnPermissionDenied cleanup on fresh attempt
        vm.toggleConnection()
        runCurrent()
        assertEquals(VpnConnectionState.CONNECTING, vm.uiState.value.connectionState)
        vm.onVpnPermissionDenied()
        runCurrent()
        assertEquals(VpnConnectionState.DISCONNECTED, vm.uiState.value.connectionState)
        assertFalse(vm.isPendingStopSignalActive)

        vm.onCleared()
    }

    // Scenario 4: Stale callback after a new connection
    @Test
    fun `4 stale failure, running, stop, and verification callbacks after a new switch are ignored`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val bridge = FakeRuntimeBridge()
        val vm = McQueenViewModel(
            application = mock(),
            runtimeBridge = bridge,
            wallClockMs = { 20_000L },
            ioDispatcher = dispatcher,
            initializeOnCreate = true,
        )
        runCurrent()

        // Connect DE and enter tunnel verification
        vm.toggleConnection()
        runCurrent()
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_START_SUCCESS, sessionStartMs = 20_000L))
        advanceTimeBy(400L)
        runCurrent()
        val oldVerifyReqId = bridge.sentServiceMessages
            .last { it.first == AppConfig.MSG_MEASURE_DELAY }
            .third!!

        // Switch to NL before DE verify response arrives
        vm.selectCountry(CountryUiModel("NL", "Netherlands", "🇳🇱", 2))
        runCurrent()
        assertEquals(VpnConnectionState.RECONNECTING, vm.uiState.value.connectionState)
        assertTrue(vm.isPendingStopSignalActive)

        // Stale MSG_STATE_RUNNING or MSG_STATE_START_FAILURE while waiting for stop MUST NOT abort NL switch
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_RUNNING, sessionStartMs = 20_000L))
        runCurrent()
        assertEquals(VpnConnectionState.RECONNECTING, vm.uiState.value.connectionState)
        assertTrue(vm.isPendingStopSignalActive)

        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_START_FAILURE, content = "old failed"))
        runCurrent()
        // Note: START_FAILURE woke pendingStopSignal so switch proceeds to NL without exhausting into FAILED
        assertEquals(VpnConnectionState.RECONNECTING, vm.uiState.value.connectionState)
        assertEquals(listOf("de-1", "nl-1"), bridge.startedGuids)

        // Late old verification failure from DE must NOT fail NL
        vm.handleServiceMessage(
            messageIntent(
                AppConfig.MSG_MEASURE_DELAY_RESULT,
                content = "timeout",
                requestId = oldVerifyReqId,
                delayMs = -1L,
            )
        )
        runCurrent()
        assertEquals(VpnConnectionState.RECONNECTING, vm.uiState.value.connectionState)

        // NL succeeds and verifies
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_START_SUCCESS, sessionStartMs = 25_000L))
        advanceTimeBy(400L)
        runCurrent()
        val newVerifyReqId = bridge.sentServiceMessages
            .last { it.first == AppConfig.MSG_MEASURE_DELAY }
            .third!!
        vm.handleServiceMessage(
            messageIntent(
                AppConfig.MSG_MEASURE_DELAY_RESULT,
                content = "45ms",
                requestId = newVerifyReqId,
                delayMs = 45L,
            )
        )
        runCurrent()
        assertEquals(VpnConnectionState.CONNECTED, vm.uiState.value.connectionState)

        vm.onCleared()
    }

    // Scenario 5: Failed connection cleanup (exhaustion & timeout)
    @Test
    fun `5 failed candidate exhaustion and timeout clean up guards and enter FAILED cleanly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val bridge = FakeRuntimeBridge().apply {
            candidatesByCountry = mapOf("US" to listOf("us-1"))
            persistedCountryCode = "US"
            persistedCountryName = "United States"
        }
        val vm = McQueenViewModel(
            application = mock(),
            runtimeBridge = bridge,
            ioDispatcher = dispatcher,
            initializeOnCreate = true,
        )
        runCurrent()

        vm.toggleConnection()
        runCurrent()
        assertEquals(listOf("us-1"), bridge.startedGuids)
        assertEquals(VpnConnectionState.CONNECTING, vm.uiState.value.connectionState)

        // Timeout fires at 18_000ms -> stops daemon and transitions to FAILED
        advanceTimeBy(18_100L)
        runCurrent()
        assertEquals(VpnConnectionState.FAILED, vm.uiState.value.connectionState)
        assertTrue(bridge.sentServiceMessages.any { it.first == AppConfig.MSG_STATE_STOP })
        assertFalse(vm.isDurationTickerActive)

        // Trailing STOP_SUCCESS preserves FAILED
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_STOP_SUCCESS))
        runCurrent()
        assertEquals(VpnConnectionState.FAILED, vm.uiState.value.connectionState)

        // Retrying from FAILED starts a clean connection
        vm.toggleConnection()
        runCurrent()
        assertEquals(VpnConnectionState.CONNECTING, vm.uiState.value.connectionState)
        assertEquals(listOf("us-1", "us-1"), bridge.startedGuids)

        vm.onCleared()
    }

    // Scenario 6 & 7: Failed server handover & Successful handover without false DISCONNECTED state
    @Test
    fun `6 and 7 ReloadOutcome and NetworkMonitor handle failed and successful handovers without false DISCONNECTED`() = runTest {
        // ReloadOutcome failure when Xray fails to come back stops service so tunnel isn't orphaned
        assertEquals(
            ReloadOutcome.STOP_SERVICE,
            ReloadOutcome.of(coreRunning = false, stoppedMeanwhile = false)
        )
        // Interrupted reload releases orphan cores
        assertEquals(
            ReloadOutcome.RELEASE_CORES,
            ReloadOutcome.of(coreRunning = true, stoppedMeanwhile = true)
        )
        // During active reload, isServiceRunning remains true even when Xray briefly restarts
        assertTrue(ReloadOutcome.serviceRuns(coreRunning = false, reloading = true, stoppedMeanwhile = false))
        assertFalse(ReloadOutcome.serviceRuns(coreRunning = false, reloading = true, stoppedMeanwhile = true))

        // Verify NetworkMonitor cancels pending debounced handover when unregistered
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connectivity: ConnectivityManager = mock()
        val request: NetworkRequest = mock()
        val netA: Network = mock()
        val netB: Network = mock()
        val caps: NetworkCapabilities = mock()
        var handoverCount = 0

        val monitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = {},
            claimHandover = { true },
            onHandover = { handoverCount++ },
            describe = { "test" },
            buildRequest = { request },
            handoverDispatcher = dispatcher,
            handoverDebounceMs = 200L,
            logInfo = {},
        )
        val captor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        monitor.register()
        verify(connectivity).requestNetwork(any(), captor.capture())
        val cb = captor.firstValue

        cb.onAvailable(netA)
        cb.onCapabilitiesChanged(netA, caps)
        cb.onAvailable(netB)
        cb.onCapabilitiesChanged(netB, caps)
        // Stop before debounce elapses -> handover must NOT execute
        monitor.unregister()
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(0, handoverCount)
    }

    // Scenario 8: Process recreation & state recovery
    @Test
    fun `8 process recreation recovers CONNECTED and CONNECTING Aether warmup states from daemon`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        var wallNowMs = 500_000L
        val bridge = FakeRuntimeBridge().apply {
            selectServerGuid = "de-2"
            persistedCountryCode = "DE"
            persistedCountryName = "Germany"
        }

        // Case A: Recreated while daemon is mid-warmup (MSG_STATE_CONNECTING)
        val vmA = McQueenViewModel(
            application = mock(),
            runtimeBridge = bridge,
            wallClockMs = { wallNowMs },
            ioDispatcher = dispatcher,
            initializeOnCreate = false,
        )
        vmA.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_CONNECTING, content = "Establishing encrypted transport..."))
        runCurrent()
        assertEquals(VpnConnectionState.CONNECTING, vmA.uiState.value.connectionState)
        assertTrue(vmA.isServiceRunningState)
        // Failure during recovered CONNECTING properly transitions to FAILED
        vmA.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_START_FAILURE, content = "warmup aborted"))
        runCurrent()
        assertEquals(VpnConnectionState.FAILED, vmA.uiState.value.connectionState)
        vmA.onCleared()

        // Case B: Recreated while daemon is already CONNECTED (MSG_STATE_RUNNING with sessionStartMs)
        val vmB = McQueenViewModel(
            application = mock(),
            runtimeBridge = bridge,
            wallClockMs = { wallNowMs },
            ioDispatcher = dispatcher,
            initializeOnCreate = false,
        )
        vmB.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_RUNNING, sessionStartMs = wallNowMs - 30_000L))
        runCurrent()
        assertEquals(VpnConnectionState.CONNECTED, vmB.uiState.value.connectionState)
        assertEquals(30L, vmB.uiState.value.sessionDurationSeconds)
        vmB.onCleared()
    }

    // Scenario 9: Duplicate service startup prevention
    @Test
    fun `9 DaemonStartGate prevents duplicate service startup and suppresses stale teardown`() {
        val gate = DaemonStartGate()
        gate.resetOnCreate()

        val gen1 = gate.beginStart(startId = 1)
        val gen2 = gate.beginStart(startId = 2)
        assertFalse(gate.shouldContinueStart(gen1))
        assertTrue(gate.shouldContinueStart(gen2))
        assertFalse(gate.shouldStopSelf(gen1))
        assertTrue(gate.shouldStopSelf(gen2))

        gate.markDestroyed()
        assertEquals(-1L, gate.beginStart(startId = 3))
        assertFalse(gate.shouldContinueStart(gen2))
    }

    // Scenario 10: Coroutine cancellation & resource cleanup
    @Test
    fun `10 onCleared cancels active jobs unregisters receiver and completes pending stop state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val syncDeferred = CompletableDeferred<ManifestSyncOutcome>()
        val releaseDeferred = CompletableDeferred<AppUpdateCheckResult>()
        val bridge = FakeRuntimeBridge().apply {
            this.syncDeferred = syncDeferred
            this.releaseDeferred = releaseDeferred
        }
        val vm = McQueenViewModel(
            application = mock(),
            runtimeBridge = bridge,
            ioDispatcher = dispatcher,
            initializeOnCreate = true,
        )
        runCurrent()

        vm.syncManifestFromGitHub(forceRefresh = true)
        vm.checkForAppUpdate()
        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_RUNNING, sessionStartMs = 1_000L))
        runCurrent()
        vm.selectCountry(CountryUiModel("NL", "Netherlands", "🇳🇱", 2))
        runCurrent()
        assertTrue(vm.isPendingStopSignalActive)
        assertEquals(1, bridge.receiverRegisteredCount)

        vm.onCleared()
        assertFalse(vm.isPendingStopSignalActive)
        assertFalse(vm.isDurationTickerActive)
        assertEquals(1, bridge.receiverUnregisteredCount)
    }

    // Scenario 11: Screen-off polling behavior preserved
    @Test
    fun `11 screen-off pauses polling while keeping counters intact across handover and screen-on resume`() = runTest(StandardTestDispatcher()) {
        var nowElapsed = 1_000L
        var serviceRunning = true
        val pendingDeltas = mutableListOf<OutboundTrafficStat>()
        var queries = 0
        val emitted = mutableListOf<TrafficStatsSnapshot>()
        val notifications = mutableListOf<String?>()

        val tracker = VpnTrafficTracker(
            source = {
                queries++
                pendingDeltas.toList().also { pendingDeltas.clear() }
            },
            monotonicClockMs = { nowElapsed },
        )
        val controller = SpeedNotificationController(
            scope = backgroundScope,
            trafficTracker = tracker,
            isServiceRunning = { serviceRunning },
            sessionStartMs = { if (serviceRunning) 10_000L else 0L },
            isSpeedNotificationEnabled = { true },
            onEmitTrafficStats = { emitted += it },
            onUpdateNotification = { text, _, _ -> notifications += text },
            pollIntervalMs = 1_000L,
        )

        controller.onSessionStart(10_000L, isInteractive = true)
        controller.startSpeedNotification("Germany")
        runCurrent()
        val queriesBeforeOff = queries

        controller.onScreenOff()
        nowElapsed += 15_000L
        pendingDeltas += OutboundTrafficStat(AppConfig.TAG_PROXY, "downlink", 8_192L)
        controller.onCoreReloaded()
        val queriesAfterReload = queries
        assertEquals(queriesBeforeOff + 1, queriesAfterReload)
        advanceTimeBy(15_000L)
        runCurrent()

        assertEquals(queriesAfterReload, queries)

        controller.onScreenOn("Germany")
        runCurrent()
        assertEquals(8_192L, emitted.last().sessionDownlinkBytes)
        assertTrue(notifications.last()!!.contains("↓ 8.0 KB"))

        serviceRunning = false
        controller.stopSessionStats()
        assertNull(notifications.last())
    }

    // Scenario 12: Session duration & traffic accounting preserved
    @Test
    fun `12 session duration and traffic accounting survive background-foreground transitions`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        var wallNowMs = 100_000L
        var elapsedNowMs = 1_000L
        val bridge = FakeRuntimeBridge()
        val vm = McQueenViewModel(
            application = mock(),
            runtimeBridge = bridge,
            wallClockMs = { wallNowMs },
            elapsedClockMs = { elapsedNowMs },
            ioDispatcher = dispatcher,
            initializeOnCreate = false,
        )

        vm.handleServiceMessage(messageIntent(AppConfig.MSG_STATE_RUNNING, sessionStartMs = wallNowMs))
        runCurrent()

        wallNowMs += 5_000L
        elapsedNowMs += 5_000L
        vm.handleServiceMessage(
            messageIntent(
                AppConfig.MSG_TRAFFIC_STATS,
                trafficSnapshot = TrafficStatsSnapshot(
                    uplinkBps = 256L,
                    downlinkBps = 2048L,
                    sessionStartMs = 100_000L,
                    sessionUplinkBytes = 1_500L,
                    sessionDownlinkBytes = 12_000L,
                )
            )
        )
        runCurrent()
        assertEquals(5L, vm.uiState.value.sessionDurationSeconds)
        assertEquals(12_000L, vm.uiState.value.sessionDownloadedBytes)
        assertEquals(1_500L, vm.uiState.value.sessionUploadedBytes)

        vm.onHostStopped()
        wallNowMs += 20_000L
        elapsedNowMs += 20_000L
        vm.onHostStarted()
        assertEquals(25L, vm.uiState.value.sessionDurationSeconds)

        vm.onCleared()
    }
}
