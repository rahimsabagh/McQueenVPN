package com.v2ray.ang.ui.main

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.BypassIranRoutingHelper
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.TunnelProbePolicy
import com.v2ray.ang.dto.TrafficStatsSnapshot
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.BatteryOptimizationHelper
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.mcqueen.AppUpdateCheckResult
import com.v2ray.ang.mcqueen.AppUpdateChecker
import com.v2ray.ang.mcqueen.ConnectionSessionCoordinator
import com.v2ray.ang.mcqueen.CountryIdentityStatus
import com.v2ray.ang.mcqueen.CountryUiModel
import com.v2ray.ang.mcqueen.FailoverDecision
import com.v2ray.ang.mcqueen.GitHubReleaseParser
import com.v2ray.ang.mcqueen.ManifestParseResult
import com.v2ray.ang.mcqueen.ManifestSyncOutcome
import com.v2ray.ang.mcqueen.McQueenManifestUpdater
import com.v2ray.ang.mcqueen.McQueenServerConfig
import com.v2ray.ang.mcqueen.McQueenServerRepository
import com.v2ray.ang.mcqueen.VpnConnectionState
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class McQueenUiState(
    val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val statusDetail: String = "",
    val selectedCountry: CountryUiModel? = null,
    val countries: List<CountryUiModel> = emptyList(),
    val isSyncingManifest: Boolean = false,
    val manifestErrorMessage: String? = null,
    val lastSyncUpdatedAt: String = "",
    val manifestSourceUrl: String = "",
    val hasAuthToken: Boolean = false,
    val downloadBps: Long = 0L,
    val uploadBps: Long = 0L,
    val sessionDownloadedBytes: Long = 0L,
    val sessionUploadedBytes: Long = 0L,
    val sessionDurationSeconds: Long = 0L,
    val isBatteryOptimizationExempt: Boolean = true,
    val showBatteryOptimizationDialog: Boolean = false,
    val themeMode: String = ThemeManager.MODE_SYSTEM,
    val isBypassIranEnabled: Boolean = AppConfig.DEFAULT_MCQUEEN_BYPASS_IRAN,
    val bypassIranAvailability: BypassIranRoutingHelper.AvailabilityStatus? = null,
    val appUpdateState: AppUpdateCheckResult = AppUpdateCheckResult.Idle,
)

internal interface McQueenRuntimeBridge {
    fun registerReceiver(receiver: BroadcastReceiver)
    fun unregisterReceiver(receiver: BroadcastReceiver)
    fun sendMsg2Service(what: Int, content: String, requestId: String? = null)
    fun startService(guid: String)
    fun stopService()
    fun getSelectServer(): String?
    fun setSelectServer(guid: String)
    fun getSelectedCountryCode(): String?
    fun getSelectedCountryName(): String?
    fun saveSelectedCountry(country: CountryUiModel)
    fun clearSelectedCountry()
    fun getConfiguredManifestUrl(): String
    fun hasConfiguredAuthToken(): Boolean
    fun saveCustomManifestConfig(url: String, authTokenInput: String?, clearAuthToken: Boolean): Boolean
    fun resetCustomManifestUrl()
    suspend fun loadCachedManifest(): ManifestParseResult?
    suspend fun isManifestCacheFresh(): Boolean
    suspend fun syncManifestFromGitHub(): ManifestSyncOutcome
    suspend fun measureCountryLatencies(countries: List<CountryUiModel>): List<CountryUiModel>
    suspend fun hasPendingIdentityProvisioning(countryCode: String): Boolean
    suspend fun ensureIdentitiesForCountry(countryCode: String): CountryIdentityStatus
    suspend fun selectCandidateGuidsForCountry(countryCode: String): List<String>
    suspend fun checkBypassIranAvailability(countryCode: String?, activeGuid: String?): BypassIranRoutingHelper.AvailabilityStatus
    fun isBypassIranEnabled(): Boolean
    fun setBypassIranEnabled(enabled: Boolean)
    fun getThemeMode(): String
    fun setThemeMode(mode: String)
    fun refreshThemeMode()
    fun isBatteryOptimizationExempt(): Boolean
    fun markBatteryPromptShown()
    fun schedulePeriodicManifestSync(forceReschedule: Boolean)
    suspend fun checkLatestRelease(): AppUpdateCheckResult
    fun string(@StringRes resId: Int, vararg args: Any): String
    fun logInfo(message: String)
    fun logWarn(message: String, throwable: Throwable? = null)
}

private class DefaultMcQueenRuntimeBridge(
    private val app: Application,
    private val repository: McQueenServerRepository = McQueenServerRepository(),
    private val appUpdateChecker: AppUpdateChecker = AppUpdateChecker(),
) : McQueenRuntimeBridge {
    override fun registerReceiver(receiver: BroadcastReceiver) {
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags(),
        )
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver) {
        app.unregisterReceiver(receiver)
    }

    override fun sendMsg2Service(what: Int, content: String, requestId: String?) {
        MessageHelper.sendMsg2Service(app, what, content, requestId)
    }

    override fun startService(guid: String) {
        LauncherManager.startService(app, guid)
    }

    override fun stopService() {
        LauncherManager.stopService(app)
    }

    override fun getSelectServer(): String? = MmkvManager.getSelectServer()

    override fun setSelectServer(guid: String) {
        MmkvManager.setSelectServer(guid)
    }

    override fun getSelectedCountryCode(): String? =
        MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY_CODE, null)

    override fun getSelectedCountryName(): String? =
        MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY, null)

    override fun saveSelectedCountry(country: CountryUiModel) {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY_CODE, country.countryCode)
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY, country.countryName)
    }

    override fun clearSelectedCountry() {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY_CODE, "")
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY, "")
    }

    override fun getConfiguredManifestUrl(): String = repository.getConfiguredUrl()

    override fun hasConfiguredAuthToken(): Boolean = repository.hasConfiguredAuthToken()

    override fun saveCustomManifestConfig(
        url: String,
        authTokenInput: String?,
        clearAuthToken: Boolean,
    ): Boolean = repository.saveCustomManifestConfig(
        url = url,
        authTokenInput = authTokenInput,
        clearAuthToken = clearAuthToken,
    )

    override fun resetCustomManifestUrl() {
        repository.resetCustomUrl()
    }

    override suspend fun loadCachedManifest(): ManifestParseResult? = repository.loadCachedManifest(app)

    override suspend fun isManifestCacheFresh(): Boolean = repository.isManifestCacheFresh()

    override suspend fun syncManifestFromGitHub(): ManifestSyncOutcome = repository.syncFromGitHub(app)

    override suspend fun measureCountryLatencies(countries: List<CountryUiModel>): List<CountryUiModel> =
        repository.measureCountryLatencies(app, countries)

    override suspend fun hasPendingIdentityProvisioning(countryCode: String): Boolean =
        repository.hasPendingIdentityProvisioning(app, countryCode)

    override suspend fun ensureIdentitiesForCountry(countryCode: String): CountryIdentityStatus =
        repository.ensureIdentitiesForCountry(app, countryCode)

    override suspend fun selectCandidateGuidsForCountry(countryCode: String): List<String> =
        repository.selectCandidatesForCountry(app, countryCode).map { it.guid }

    override suspend fun checkBypassIranAvailability(
        countryCode: String?,
        activeGuid: String?,
    ): BypassIranRoutingHelper.AvailabilityStatus {
        SettingsManager.initAssets(app, app.assets)
        val selectedGuid = if (!countryCode.isNullOrBlank()) {
            repository.findCompatibilityGuidForCountry(
                countryCode = countryCode,
                activeGuid = activeGuid,
                fallbackSelectGuid = MmkvManager.getSelectServer(),
            )
        } else {
            activeGuid ?: MmkvManager.getSelectServer()
        }
        return BypassIranRoutingHelper.checkCompatibility(app, selectedGuid)
    }

    override fun isBypassIranEnabled(): Boolean = SettingsManager.isBypassIranEnabled()

    override fun setBypassIranEnabled(enabled: Boolean) {
        SettingsManager.setBypassIranEnabled(enabled)
    }

    override fun getThemeMode(): String = ThemeManager.themeMode

    override fun setThemeMode(mode: String) {
        ThemeManager.setThemeMode(mode)
    }

    override fun refreshThemeMode() {
        ThemeManager.refresh()
    }

    override fun isBatteryOptimizationExempt(): Boolean =
        BatteryOptimizationHelper.isIgnoringBatteryOptimizations(app)

    override fun markBatteryPromptShown() {
        BatteryOptimizationHelper.markInitialPromptShown()
    }

    override fun schedulePeriodicManifestSync(forceReschedule: Boolean) {
        McQueenManifestUpdater.sync(app, forceReschedule = forceReschedule)
    }

    override suspend fun checkLatestRelease(): AppUpdateCheckResult = appUpdateChecker.checkLatestRelease()

    override fun string(resId: Int, vararg args: Any): String =
        if (args.isEmpty()) app.getString(resId) else app.getString(resId, *args)

    override fun logInfo(message: String) {
        LogUtil.i(AppConfig.TAG, message)
    }

    override fun logWarn(message: String, throwable: Throwable?) {
        if (throwable != null) {
            LogUtil.w(AppConfig.TAG, message, throwable)
        } else {
            LogUtil.w(AppConfig.TAG, message)
        }
    }
}

class McQueenViewModel internal constructor(
    application: Application,
    private val runtimeBridge: McQueenRuntimeBridge,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val elapsedClockMs: () -> Long = SystemClock::elapsedRealtime,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initializeOnCreate: Boolean = true,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        runtimeBridge = DefaultMcQueenRuntimeBridge(application),
    )

    private val sessionCoordinator = ConnectionSessionCoordinator()

    private val _uiState = MutableStateFlow(
        McQueenUiState(
            manifestSourceUrl = runtimeBridge.getConfiguredManifestUrl(),
            hasAuthToken = runtimeBridge.hasConfiguredAuthToken(),
            isBatteryOptimizationExempt = true,
            themeMode = runtimeBridge.getThemeMode(),
            isBypassIranEnabled = runtimeBridge.isBypassIranEnabled(),
        )
    )
    val uiState: StateFlow<McQueenUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private val _openExternalUrlEvents = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val openExternalUrlEvents: SharedFlow<String> = _openExternalUrlEvents.asSharedFlow()

    private var startVpnCallback: (() -> Unit)? = null
    private var initialLoadJob: Job? = null
    private var reloadCountriesJob: Job? = null
    private var connectJob: Job? = null
    private var failoverJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var verifyTunnelJob: Job? = null
    private var durationTickerJob: Job? = null
    private var latencyRefreshJob: Job? = null
    private var manifestSyncJob: Job? = null
    private var updateCheckJob: Job? = null
    private var batteryCheckJob: Job? = null
    private var bypassStatusJob: Job? = null
    private var sessionStartTimeMs: Long = 0L
    private var lastTrafficSyncElapsedMs: Long = -1L
    private var pendingVerifyRequestId: String? = null
    private var pendingVerifyOperationId: Long = 0L
    private var isReceiverRegistered = false
    private var isHostVisible = true
    private var isLocationsTabActive = false
    private var hasMeasuredLatenciesForManifest = false
    private val periodicSyncScheduled = AtomicBoolean(false)
    private val isServiceRunning = AtomicBoolean(false)
    private val pendingStopAckFromDisconnect = AtomicBoolean(false)
    private val stopSignalLock = Any()
    private var pendingStopSignal: CompletableDeferred<Unit>? = null

    internal val isDurationTickerActive: Boolean
        get() = durationTickerJob?.isActive == true

    internal val trackedSessionStartTimeMs: Long
        get() = sessionStartTimeMs

    internal val isPendingStopSignalActive: Boolean
        get() = synchronized(stopSignalLock) { pendingStopSignal != null }

    internal val isServiceRunningState: Boolean
        get() = isServiceRunning.get()

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            handleServiceMessage(intent)
        }
    }

    internal fun handleServiceMessage(intent: Intent?) {
        when (intent?.getIntExtra("key", 0)) {
            AppConfig.MSG_STATE_RUNNING -> {
                if (isPendingStopSignalActive ||
                    sessionCoordinator.isAwaitingSwitchStop() ||
                    pendingStopAckFromDisconnect.get() ||
                    sessionCoordinator.userRequestedDisconnect ||
                    sessionCoordinator.currentState() == VpnConnectionState.PREPARING
                ) {
                    return
                }
                isServiceRunning.set(true)
                val daemonStartMs = intent.getLongExtra(MessageHelper.EXTRA_SESSION_START_MS, 0L)
                onCoreServiceStarted(isAlreadyRunning = true, daemonSessionStartMs = daemonStartMs)
            }

            AppConfig.MSG_STATE_CONNECTING -> {
                if (isPendingStopSignalActive ||
                    sessionCoordinator.isAwaitingSwitchStop() ||
                    pendingStopAckFromDisconnect.get() ||
                    sessionCoordinator.userRequestedDisconnect
                ) {
                    return
                }
                isServiceRunning.set(true)
                val detail = intent.getStringExtra("content").orEmpty()
                if (sessionCoordinator.currentState() == VpnConnectionState.DISCONNECTED) {
                    val countryCode = _uiState.value.selectedCountry?.countryCode
                        ?: runtimeBridge.getSelectedCountryCode()
                    sessionCoordinator.recoverConnecting(countryCode)
                }
                val targetState = if (sessionCoordinator.isSwitchingCountry) {
                    VpnConnectionState.RECONNECTING
                } else {
                    VpnConnectionState.CONNECTING
                }
                _uiState.update { state ->
                    val nextDetail = detail.ifBlank { state.statusDetail }
                    if (state.connectionState == targetState && state.statusDetail == nextDetail) {
                        state
                    } else {
                        state.copy(
                            connectionState = targetState,
                            statusDetail = nextDetail,
                        )
                    }
                }
            }

            AppConfig.MSG_STATE_NOT_RUNNING -> {
                pendingStopAckFromDisconnect.set(false)
                signalServiceStopped()
                val nextState = sessionCoordinator.onServiceNotRunningQuery(
                    preserveFailure = _uiState.value.connectionState == VpnConnectionState.FAILED
                )
                if (nextState == VpnConnectionState.DISCONNECTED || nextState == VpnConnectionState.FAILED) {
                    stopDurationTicker()
                    cancelConnectionGuards()
                    _uiState.update { state ->
                        val nextDetail = if (nextState == VpnConnectionState.FAILED) state.statusDetail else ""
                        if (state.connectionState == nextState &&
                            state.statusDetail == nextDetail &&
                            state.downloadBps == 0L &&
                            state.uploadBps == 0L &&
                            state.sessionDownloadedBytes == 0L &&
                            state.sessionUploadedBytes == 0L &&
                            state.sessionDurationSeconds == 0L
                        ) {
                            state
                        } else {
                            state.copy(
                                connectionState = nextState,
                                statusDetail = nextDetail,
                                downloadBps = 0L,
                                uploadBps = 0L,
                                sessionDownloadedBytes = 0L,
                                sessionUploadedBytes = 0L,
                                sessionDurationSeconds = 0L,
                            )
                        }
                    }
                }
            }

            AppConfig.MSG_STATE_START_SUCCESS -> {
                if (isPendingStopSignalActive ||
                    sessionCoordinator.isAwaitingSwitchStop() ||
                    pendingStopAckFromDisconnect.get() ||
                    sessionCoordinator.userRequestedDisconnect
                ) {
                    return
                }
                isServiceRunning.set(true)
                val daemonStartMs = intent.getLongExtra(MessageHelper.EXTRA_SESSION_START_MS, 0L)
                onCoreServiceStarted(isAlreadyRunning = false, daemonSessionStartMs = daemonStartMs)
            }

            AppConfig.MSG_STATE_START_FAILURE -> {
                pendingStopAckFromDisconnect.set(false)
                signalServiceStopped()
                if (!sessionCoordinator.canAcceptRuntimeFailure()) {
                    return
                }
                val reason = intent.getStringExtra("content").orEmpty()
                handleConnectionCandidateFailure(reason)
            }

            AppConfig.MSG_STATE_STOP_SUCCESS -> {
                pendingStopAckFromDisconnect.set(false)
                signalServiceStopped()
                val nextState = sessionCoordinator.onServiceStopped(
                    preserveFailure = _uiState.value.connectionState == VpnConnectionState.FAILED
                )
                if (nextState == VpnConnectionState.DISCONNECTED || nextState == VpnConnectionState.FAILED) {
                    stopDurationTicker()
                    cancelConnectionGuards()
                    _uiState.update { state ->
                        val nextDetail = if (nextState == VpnConnectionState.FAILED) state.statusDetail else ""
                        if (state.connectionState == nextState &&
                            state.statusDetail == nextDetail &&
                            state.downloadBps == 0L &&
                            state.uploadBps == 0L &&
                            state.sessionDownloadedBytes == 0L &&
                            state.sessionUploadedBytes == 0L &&
                            state.sessionDurationSeconds == 0L
                        ) {
                            state
                        } else {
                            state.copy(
                                connectionState = nextState,
                                statusDetail = nextDetail,
                                downloadBps = 0L,
                                uploadBps = 0L,
                                sessionDownloadedBytes = 0L,
                                sessionUploadedBytes = 0L,
                                sessionDurationSeconds = 0L,
                            )
                        }
                    }
                }
            }

            AppConfig.MSG_MEASURE_DELAY_RESULT -> {
                val reqId = intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID).orEmpty()
                val content = intent.getStringExtra("content").orEmpty()
                val delayMs = if (intent.hasExtra(MessageHelper.EXTRA_DELAY_MS)) {
                    intent.getLongExtra(MessageHelper.EXTRA_DELAY_MS, -1L)
                } else {
                    null
                }
                handleTunnelVerificationMessage(reqId, content, delayMs)
            }

            AppConfig.MSG_TRAFFIC_STATS -> {
                val snapshot = intent.serializable<TrafficStatsSnapshot>("content")
                handleTrafficStats(snapshot)
            }

            AppConfig.MSG_SERVERS_CHANGED -> {
                reloadCachedCountries()
            }
        }
    }

    init {
        if (initializeOnCreate) {
            registerServiceReceiver()
            observeSettingsReload()
            refreshBatteryOptimizationStatus()
            loadInitialLocationsAndSync()
        }
    }

    fun bindStartVpnCallback(callback: () -> Unit) {
        startVpnCallback = callback
    }

    public override fun onCleared() {
        pendingStopAckFromDisconnect.set(false)
        signalServiceStopped()
        unregisterServiceReceiver()
        stopDurationTicker()
        cancelConnectionGuards()
        initialLoadJob?.cancel()
        reloadCountriesJob?.cancel()
        connectJob?.cancel()
        failoverJob?.cancel()
        latencyRefreshJob?.cancel()
        manifestSyncJob?.cancel()
        batteryCheckJob?.cancel()
        bypassStatusJob?.cancel()
        updateCheckJob?.cancel()
        super.onCleared()
    }

    fun onHostStarted() {
        isHostVisible = true
        if (_uiState.value.connectionState == VpnConnectionState.CONNECTED) {
            if (sessionStartTimeMs > 0L) {
                val elapsed = computeSessionDurationSeconds(sessionStartTimeMs, wallClockMs())
                _uiState.update { state ->
                    if (state.sessionDurationSeconds == elapsed) state
                    else state.copy(sessionDurationSeconds = elapsed)
                }
            }
            ensureDurationTickerIfVisible()
        }
    }

    fun onHostStopped() {
        isHostVisible = false
        durationTickerJob?.cancel()
        durationTickerJob = null
    }

    fun onHostResumed() {
        isHostVisible = true
        runtimeBridge.refreshThemeMode()
        refreshBatteryOptimizationStatus()
        refreshServiceStatus()
        refreshBypassIranStatus()
        if (_uiState.value.connectionState == VpnConnectionState.CONNECTED) {
            ensureDurationTickerIfVisible()
        }
    }

    fun refreshBatteryOptimizationStatus() {
        if (batteryCheckJob?.isActive == true) return
        batteryCheckJob = viewModelScope.launch {
            val isExempt = withContext(ioDispatcher) {
                runtimeBridge.isBatteryOptimizationExempt()
            }
            _uiState.update { state ->
                val nextDialog = if (isExempt) false else state.showBatteryOptimizationDialog
                if (state.isBatteryOptimizationExempt == isExempt &&
                    state.showBatteryOptimizationDialog == nextDialog
                ) {
                    state
                } else {
                    state.copy(
                        isBatteryOptimizationExempt = isExempt,
                        showBatteryOptimizationDialog = nextDialog,
                    )
                }
            }
        }
    }

    fun dismissBatteryOptimizationDialog() {
        runtimeBridge.markBatteryPromptShown()
        _uiState.update { state ->
            if (!state.showBatteryOptimizationDialog) state else state.copy(showBatteryOptimizationDialog = false)
        }
    }

    fun onConfirmBatteryOptimizationDialog() {
        runtimeBridge.markBatteryPromptShown()
        _uiState.update { state ->
            if (!state.showBatteryOptimizationDialog) state else state.copy(showBatteryOptimizationDialog = false)
        }
    }

    fun setThemeMode(mode: String) {
        runtimeBridge.setThemeMode(mode)
        val resolvedMode = runtimeBridge.getThemeMode()
        _uiState.update { state ->
            if (state.themeMode == resolvedMode) state else state.copy(themeMode = resolvedMode)
        }
    }

    fun setBypassIranEnabled(enabled: Boolean) {
        val previous = runtimeBridge.isBypassIranEnabled()
        runtimeBridge.setBypassIranEnabled(enabled)
        _uiState.update { state ->
            if (state.isBypassIranEnabled == enabled) state else state.copy(isBypassIranEnabled = enabled)
        }
        refreshBypassIranStatus()

        if (previous == enabled) return

        val state = _uiState.value.connectionState
        val isActiveSession = state == VpnConnectionState.CONNECTED ||
            state == VpnConnectionState.PREPARING ||
            state == VpnConnectionState.CONNECTING ||
            state == VpnConnectionState.RECONNECTING
        val selected = _uiState.value.selectedCountry
        if (isActiveSession && selected != null) {
            _snackbarEvents.tryEmit(runtimeBridge.string(R.string.mcqueen_bypass_iran_reconnecting))
            switchCountryAndReconnect(selected)
        } else {
            val msgRes = if (enabled) {
                R.string.mcqueen_bypass_iran_enabled_next
            } else {
                R.string.mcqueen_bypass_iran_disabled_next
            }
            _snackbarEvents.tryEmit(runtimeBridge.string(msgRes))
        }
    }

    fun refreshBypassIranStatus() {
        bypassStatusJob?.cancel()
        bypassStatusJob = viewModelScope.launch {
            val countryCode = _uiState.value.selectedCountry?.countryCode
            val status = withContext(ioDispatcher) {
                runtimeBridge.checkBypassIranAvailability(
                    countryCode = countryCode,
                    activeGuid = sessionCoordinator.currentGuid(),
                )
            }
            val enabledNow = runtimeBridge.isBypassIranEnabled()
            _uiState.update { state ->
                if (state.isBypassIranEnabled == enabledNow && state.bypassIranAvailability == status) {
                    state
                } else {
                    state.copy(
                        isBypassIranEnabled = enabledNow,
                        bypassIranAvailability = status,
                    )
                }
            }
        }
    }

    fun checkForAppUpdate() {
        if (updateCheckJob?.isActive == true) return
        _uiState.update { it.copy(appUpdateState = AppUpdateCheckResult.Checking) }

        updateCheckJob = viewModelScope.launch {
            val result = runtimeBridge.checkLatestRelease()
            _uiState.update { it.copy(appUpdateState = result) }

            when (result) {
                is AppUpdateCheckResult.UpdateAvailable -> Unit
                is AppUpdateCheckResult.UpToDate -> {
                    _snackbarEvents.tryEmit(
                        runtimeBridge.string(R.string.mcqueen_update_up_to_date, result.installedVersion)
                    )
                }

                AppUpdateCheckResult.NoInternet -> {
                    _snackbarEvents.tryEmit(runtimeBridge.string(R.string.mcqueen_update_no_internet))
                }

                is AppUpdateCheckResult.RateLimited -> {
                    _snackbarEvents.tryEmit(runtimeBridge.string(R.string.mcqueen_update_rate_limited))
                }

                is AppUpdateCheckResult.InvalidResponse -> {
                    _snackbarEvents.tryEmit(runtimeBridge.string(R.string.mcqueen_update_invalid_response))
                }

                is AppUpdateCheckResult.MissingApkAsset -> {
                    _snackbarEvents.tryEmit(
                        runtimeBridge.string(R.string.mcqueen_update_missing_apk, result.latestVersion)
                    )
                }

                AppUpdateCheckResult.Idle,
                AppUpdateCheckResult.Checking -> Unit
            }
        }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(appUpdateState = AppUpdateCheckResult.Idle) }
    }

    fun openVerifiedUpdateUrl() {
        val current = _uiState.value.appUpdateState as? AppUpdateCheckResult.UpdateAvailable ?: return
        if (!GitHubReleaseParser.isOfficialGitHubAssetUrl(current.apkDownloadUrl)) {
            _snackbarEvents.tryEmit(
                runtimeBridge.string(R.string.mcqueen_update_invalid_response)
            )
            return
        }
        _openExternalUrlEvents.tryEmit(current.apkDownloadUrl)
        _uiState.update { it.copy(appUpdateState = AppUpdateCheckResult.Idle) }
    }

    fun refreshServiceStatus() {
        runtimeBridge.sendMsg2Service(AppConfig.MSG_REGISTER_CLIENT, "", null)
    }

    private fun observeSettingsReload() {
        _uiState.update {
            it.copy(
                manifestSourceUrl = runtimeBridge.getConfiguredManifestUrl(),
                hasAuthToken = runtimeBridge.hasConfiguredAuthToken(),
                themeMode = runtimeBridge.getThemeMode(),
            )
        }
    }

    private fun loadInitialLocationsAndSync() {
        initialLoadJob?.cancel()
        initialLoadJob = viewModelScope.launch {
            val hasUsableCache = reloadCachedCountriesInternal()

            // Refresh Bypass Iran compatibility once cached selection/profiles are available (metadata-only, zero probing).
            refreshBypassIranStatus()

            // Schedule periodic WorkManager background sync off the critical main-thread startup path.
            if (periodicSyncScheduled.compareAndSet(false, true)) {
                withContext(ioDispatcher) {
                    runtimeBridge.schedulePeriodicManifestSync(forceReschedule = false)
                }
            }

            val isCacheFresh = withContext(ioDispatcher) {
                runtimeBridge.isManifestCacheFresh()
            }
            if (McQueenServerConfig.shouldAutoSyncManifestOnStartup(
                    hasUsableManifest = hasUsableCache,
                    isCacheFresh = isCacheFresh,
                )
            ) {
                syncManifestFromGitHub(silentSuccess = true, forceRefresh = false)
            }
        }
    }

    private fun reloadCachedCountries() {
        reloadCountriesJob?.cancel()
        reloadCountriesJob = viewModelScope.launch {
            reloadCachedCountriesInternal()
        }
    }

    private suspend fun reloadCachedCountriesInternal(): Boolean {
        val cached = runtimeBridge.loadCachedManifest() ?: return false
        if (cached.countries.isEmpty()) return false
        applyCountriesList(cached.countries, cached.updatedAt.orEmpty(), refreshCompatibility = false)
        return true
    }

    private fun applyCountriesList(
        countries: List<CountryUiModel>,
        updatedAt: String,
        refreshCompatibility: Boolean = true,
    ) {
        val previousSelectedCode = _uiState.value.selectedCountry?.countryCode
        val savedCode = runtimeBridge.getSelectedCountryCode()
        val savedName = runtimeBridge.getSelectedCountryName()
        val resolvedSelection = McQueenUiHelper.resolveSavedCountry(
            countries = countries,
            currentSelectedCode = previousSelectedCode,
            savedCode = savedCode,
            savedName = savedName,
        )

        if (resolvedSelection != null) {
            runtimeBridge.saveSelectedCountry(resolvedSelection)
        } else if (!savedCode.isNullOrBlank() || !savedName.isNullOrBlank()) {
            runtimeBridge.clearSelectedCountry()
        }

        val manifestUrl = runtimeBridge.getConfiguredManifestUrl()
        val hasToken = runtimeBridge.hasConfiguredAuthToken()
        _uiState.update {
            it.copy(
                countries = countries,
                selectedCountry = resolvedSelection,
                manifestErrorMessage = null,
                lastSyncUpdatedAt = updatedAt.ifBlank { it.lastSyncUpdatedAt },
                manifestSourceUrl = manifestUrl,
                hasAuthToken = hasToken,
            )
        }
        if (refreshCompatibility &&
            resolvedSelection != null &&
            (resolvedSelection.countryCode != previousSelectedCode || _uiState.value.bypassIranAvailability == null)
        ) {
            refreshBypassIranStatus()
        }
    }

    fun selectCountry(country: CountryUiModel) {
        val currentState = _uiState.value.connectionState
        val previousCountryCode = _uiState.value.selectedCountry?.countryCode

        runtimeBridge.saveSelectedCountry(country)

        val shouldSwitch = sessionCoordinator.shouldTriggerCountrySwitch(
            currentState = currentState,
            currentCountryCode = previousCountryCode,
            newCountryCode = country.countryCode,
        )

        if (!shouldSwitch) {
            _uiState.update { state ->
                val nextConnState = if (state.connectionState == VpnConnectionState.FAILED) {
                    VpnConnectionState.DISCONNECTED
                } else {
                    state.connectionState
                }
                val nextDetail = if (state.connectionState == VpnConnectionState.FAILED) "" else state.statusDetail
                if (state.selectedCountry == country &&
                    state.connectionState == nextConnState &&
                    state.statusDetail == nextDetail
                ) {
                    state
                } else {
                    state.copy(
                        selectedCountry = country,
                        connectionState = nextConnState,
                        statusDetail = nextDetail,
                    )
                }
            }
            if (previousCountryCode != country.countryCode) {
                refreshBypassIranStatus()
            }
            return
        }

        switchCountryAndReconnect(country)
        refreshBypassIranStatus()
    }

    fun onLocationsTabOpened() {
        isLocationsTabActive = true
        val countries = _uiState.value.countries
        if (countries.isNotEmpty() && !hasMeasuredLatenciesForManifest && latencyRefreshJob?.isActive != true) {
            val state = _uiState.value.connectionState
            if (state == VpnConnectionState.DISCONNECTED || state == VpnConnectionState.FAILED) {
                refreshCountryLatenciesInBackground(countries)
            }
        }
    }

    fun onLocationsTabClosed() {
        isLocationsTabActive = false
        latencyRefreshJob?.cancel()
        latencyRefreshJob = null
    }

    fun syncManifestFromGitHub(silentSuccess: Boolean = false, forceRefresh: Boolean = !silentSuccess) {
        val existingJob = manifestSyncJob
        if (existingJob?.isActive == true) {
            if (!forceRefresh) return
            existingJob.cancel()
        }

        manifestSyncJob = viewModelScope.launch {
            _uiState.update { it.copy(isSyncingManifest = true, manifestErrorMessage = null) }
            try {
                when (val outcome = runtimeBridge.syncManifestFromGitHub()) {
                    is ManifestSyncOutcome.Success -> {
                        val countries = outcome.manifest.countries
                        if (outcome.fromCache && _uiState.value.countries.isNotEmpty()) {
                            val manifestUrl = runtimeBridge.getConfiguredManifestUrl()
                            val hasToken = runtimeBridge.hasConfiguredAuthToken()
                            _uiState.update {
                                it.copy(
                                    manifestErrorMessage = null,
                                    lastSyncUpdatedAt = outcome.manifest.updatedAt.orEmpty().ifBlank { it.lastSyncUpdatedAt },
                                    manifestSourceUrl = manifestUrl,
                                    hasAuthToken = hasToken,
                                )
                            }
                        } else {
                            hasMeasuredLatenciesForManifest = false
                            applyCountriesList(countries, outcome.manifest.updatedAt.orEmpty())
                            if (isLocationsTabActive) {
                                refreshCountryLatenciesInBackground(countries)
                            }
                        }
                        if (!silentSuccess) {
                            _snackbarEvents.tryEmit(
                                runtimeBridge.string(R.string.mcqueen_sync_success, countries.size)
                            )
                        }
                    }

                    is ManifestSyncOutcome.Failure -> {
                        if (_uiState.value.countries.isEmpty()) {
                            reloadCachedCountriesInternal()
                        }
                        if (_uiState.value.countries.isEmpty()) {
                            _uiState.update { it.copy(manifestErrorMessage = outcome.reason) }
                        }
                        if (!silentSuccess || _uiState.value.countries.isEmpty()) {
                            _snackbarEvents.tryEmit(
                                runtimeBridge.string(R.string.mcqueen_sync_error, outcome.reason)
                            )
                        }
                    }
                }
            } finally {
                _uiState.update { state ->
                    if (!state.isSyncingManifest) state else state.copy(isSyncingManifest = false)
                }
            }
        }
    }

    private fun refreshCountryLatenciesInBackground(countries: List<CountryUiModel>) {
        latencyRefreshJob?.cancel()
        latencyRefreshJob = viewModelScope.launch {
            val measured = runCatching {
                runtimeBridge.measureCountryLatencies(countries)
            }.getOrNull() ?: return@launch

            hasMeasuredLatenciesForManifest = true
            val measuredByCode = measured.associateBy { it.countryCode }
            val currentList = _uiState.value.countries.ifEmpty { measured }
            val mergedCountries = currentList.map { existing ->
                val updated = measuredByCode[existing.countryCode]
                if (updated != null) {
                    existing.copy(
                        bestLatencyMs = updated.bestLatencyMs,
                        availability = updated.availability,
                    )
                } else {
                    existing
                }
            }

            val selectedCode = _uiState.value.selectedCountry?.countryCode
            val updatedSelected = mergedCountries.firstOrNull { it.countryCode == selectedCode }
                ?: _uiState.value.selectedCountry

            _uiState.update { state ->
                if (state.countries == mergedCountries && state.selectedCountry == updatedSelected) {
                    state
                } else {
                    state.copy(
                        countries = mergedCountries,
                        selectedCountry = updatedSelected,
                    )
                }
            }
        }
    }

    fun updateManifestSourceAndSync(url: String, authToken: String? = null, clearToken: Boolean = false) {
        val saved = runtimeBridge.saveCustomManifestConfig(
            url = url,
            authTokenInput = authToken,
            clearAuthToken = clearToken,
        )
        if (!saved) {
            _snackbarEvents.tryEmit(runtimeBridge.string(R.string.mcqueen_settings_manifest_invalid))
            return
        }
        _uiState.update {
            it.copy(
                manifestSourceUrl = runtimeBridge.getConfiguredManifestUrl(),
                hasAuthToken = runtimeBridge.hasConfiguredAuthToken(),
            )
        }
        runtimeBridge.schedulePeriodicManifestSync(forceReschedule = true)
        syncManifestFromGitHub(silentSuccess = false, forceRefresh = true)
    }

    fun resetManifestSourceAndSync() {
        runtimeBridge.resetCustomManifestUrl()
        _uiState.update {
            it.copy(
                manifestSourceUrl = runtimeBridge.getConfiguredManifestUrl(),
                hasAuthToken = runtimeBridge.hasConfiguredAuthToken(),
            )
        }
        runtimeBridge.schedulePeriodicManifestSync(forceReschedule = true)
        syncManifestFromGitHub(silentSuccess = false, forceRefresh = true)
    }

    fun toggleConnection() {
        when (_uiState.value.connectionState) {
            VpnConnectionState.CONNECTED,
            VpnConnectionState.PREPARING,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.RECONNECTING -> disconnectVpn()

            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.FAILED -> connectSelectedCountry()
        }
    }

    private fun switchCountryAndReconnect(country: CountryUiModel) {
        val operationId = sessionCoordinator.beginCountrySwitch(country.countryCode)
        latencyRefreshJob?.cancel()
        latencyRefreshJob = null
        connectJob?.cancel()
        failoverJob?.cancel()
        cancelConnectionGuards()
        stopDurationTicker()

        val switchingStatus = runtimeBridge.string(
            R.string.mcqueen_switching_country,
            country.countryName
        )

        _uiState.update {
            it.copy(
                selectedCountry = country,
                connectionState = VpnConnectionState.RECONNECTING,
                statusDetail = switchingStatus,
                downloadBps = 0L,
                uploadBps = 0L,
                sessionDownloadedBytes = 0L,
                sessionUploadedBytes = 0L,
                sessionDurationSeconds = 0L,
            )
        }

        connectJob = viewModelScope.launch {
            awaitActiveServiceStopped(
                operationId = operationId,
                sendStopCommand = isServiceRunning.get(),
            )
            if (!sessionCoordinator.isCurrentOperation(operationId)) return@launch

            startCountryCandidates(
                country = country,
                operationId = operationId,
                isCountrySwitch = true,
            )
        }
    }

    private suspend fun awaitActiveServiceStopped(
        operationId: Long,
        sendStopCommand: Boolean = true,
    ) {
        if (!isServiceRunning.get() && !pendingStopAckFromDisconnect.get()) return
        val deferred = CompletableDeferred<Unit>()
        synchronized(stopSignalLock) {
            if ((!isServiceRunning.get() && !pendingStopAckFromDisconnect.get()) ||
                !sessionCoordinator.isCurrentOperation(operationId)
            ) {
                deferred.complete(Unit)
            } else {
                pendingStopSignal?.cancel()
                pendingStopSignal = deferred
            }
        }
        if (!deferred.isCompleted) {
            if (sendStopCommand) {
                runtimeBridge.sendMsg2Service(AppConfig.MSG_STATE_STOP, "", null)
            }
            withTimeoutOrNull(SERVICE_STOP_AWAIT_TIMEOUT_MS) {
                deferred.await()
            }
            synchronized(stopSignalLock) {
                if (pendingStopSignal === deferred) {
                    pendingStopSignal = null
                }
            }
        }
        isServiceRunning.set(false)
        pendingStopAckFromDisconnect.set(false)
    }

    private fun signalServiceStopped() {
        val deferred = synchronized(stopSignalLock) {
            isServiceRunning.set(false)
            pendingStopSignal.also { pendingStopSignal = null }
        }
        deferred?.complete(Unit)
    }

    private fun connectSelectedCountry() {
        val country = _uiState.value.selectedCountry
            ?: _uiState.value.countries.firstOrNull()?.also { firstCountry ->
                runtimeBridge.saveSelectedCountry(firstCountry)
                _uiState.update { it.copy(selectedCountry = firstCountry) }
            }
        if (country == null) {
            _snackbarEvents.tryEmit(runtimeBridge.string(R.string.mcqueen_select_country_first))
            syncManifestFromGitHub(silentSuccess = true, forceRefresh = false)
            return
        }

        latencyRefreshJob?.cancel()
        latencyRefreshJob = null
        connectJob?.cancel()
        failoverJob?.cancel()
        cancelConnectionGuards()

        val needsStopAwait = isServiceRunning.get() ||
            pendingStopAckFromDisconnect.get() ||
            sessionCoordinator.hasLaunchedOrActiveService()
        val shouldSendStopCommand = isServiceRunning.get()

        sessionCoordinator.startPreparing(country.countryCode)
        val operationId = sessionCoordinator.activeOperationId
        if (needsStopAwait) {
            _uiState.update { state ->
                val detail = runtimeBridge.string(R.string.mcqueen_preparing_secure_connection)
                if (state.connectionState == VpnConnectionState.PREPARING && state.statusDetail == detail) {
                    state
                } else {
                    state.copy(
                        connectionState = VpnConnectionState.PREPARING,
                        statusDetail = detail,
                        downloadBps = 0L,
                        uploadBps = 0L,
                    )
                }
            }
        }
        connectJob = viewModelScope.launch {
            if (needsStopAwait) {
                awaitActiveServiceStopped(
                    operationId = operationId,
                    sendStopCommand = shouldSendStopCommand,
                )
                if (!sessionCoordinator.isCurrentOperation(operationId)) return@launch
            }
            startCountryCandidates(
                country = country,
                operationId = operationId,
                isCountrySwitch = false,
            )
        }
    }

    private suspend fun startCountryCandidates(
        country: CountryUiModel,
        operationId: Long,
        isCountrySwitch: Boolean,
    ) {
        val needsProvisioning = runtimeBridge.hasPendingIdentityProvisioning(country.countryCode)
        if (!sessionCoordinator.isCurrentOperation(operationId)) return

        if (needsProvisioning) {
            _uiState.update {
                it.copy(
                    connectionState = if (isCountrySwitch) VpnConnectionState.RECONNECTING else VpnConnectionState.PREPARING,
                    statusDetail = if (isCountrySwitch) {
                        runtimeBridge.string(R.string.mcqueen_switching_country, country.countryName)
                    } else {
                        runtimeBridge.string(R.string.mcqueen_preparing_secure_connection)
                    },
                    downloadBps = 0L,
                    uploadBps = 0L,
                )
            }
        }

        when (val identityStatus = runtimeBridge.ensureIdentitiesForCountry(country.countryCode)) {
            is CountryIdentityStatus.Ready -> {
                runtimeBridge.logInfo("Identity status ready for ${country.countryCode}")
            }

            is CountryIdentityStatus.Failed -> {
                if (!sessionCoordinator.isCurrentOperation(operationId)) return
                val errorText = runtimeBridge.string(identityStatus.kind.userMessageRes)
                sessionCoordinator.onStartFailure(errorText)
                _uiState.update {
                    it.copy(
                        connectionState = VpnConnectionState.FAILED,
                        statusDetail = errorText,
                        downloadBps = 0L,
                        uploadBps = 0L,
                    )
                }
                _snackbarEvents.tryEmit(errorText)
                return
            }
        }

        if (!sessionCoordinator.isCurrentOperation(operationId)) return

        _uiState.update {
            it.copy(
                connectionState = if (isCountrySwitch) VpnConnectionState.RECONNECTING else VpnConnectionState.CONNECTING,
                statusDetail = if (isCountrySwitch) {
                    runtimeBridge.string(R.string.mcqueen_switching_country, country.countryName)
                } else {
                    runtimeBridge.string(R.string.mcqueen_testing_country, country.countryName)
                },
                downloadBps = 0L,
                uploadBps = 0L,
            )
        }

        val orderedGuids = runtimeBridge.selectCandidateGuidsForCountry(country.countryCode)
        if (!sessionCoordinator.isCurrentOperation(operationId)) return

        val firstGuid = if (isCountrySwitch) {
            sessionCoordinator.attachSwitchCandidates(operationId, orderedGuids)
        } else {
            sessionCoordinator.startAttempt(orderedGuids, country.countryCode)
        }

        if (firstGuid == null) {
            val failedText = runtimeBridge.string(R.string.mcqueen_all_candidates_failed, country.countryName)
            _uiState.update {
                it.copy(
                    connectionState = VpnConnectionState.FAILED,
                    statusDetail = failedText,
                )
            }
            _snackbarEvents.tryEmit(failedText)
            return
        }

        runtimeBridge.setSelectServer(firstGuid)
        startConnectionTimeoutGuard(country.countryName, sessionCoordinator.activeOperationId)
        val permissionCallback = startVpnCallback
        if (permissionCallback != null) {
            permissionCallback.invoke()
        } else {
            sessionCoordinator.markServiceLaunched(operationId)
            runtimeBridge.startService(firstGuid)
        }
    }

    private fun onCoreServiceStarted(isAlreadyRunning: Boolean, daemonSessionStartMs: Long = 0L) {
        if (sessionCoordinator.userRequestedDisconnect) return
        val operationId = sessionCoordinator.activeOperationId

        val activeGuid = runtimeBridge.getSelectServer()
        if (activeGuid != null && sessionCoordinator.currentGuid() == null && isAlreadyRunning) {
            val countryCode = _uiState.value.selectedCountry?.countryCode
                ?: runtimeBridge.getSelectedCountryCode()
            sessionCoordinator.startAttempt(listOf(activeGuid), countryCode)
            markSessionConnected(daemonSessionStartMs)
            return
        }

        if (isAlreadyRunning && _uiState.value.connectionState == VpnConnectionState.CONNECTED) {
            if (daemonSessionStartMs > 0L && sessionStartTimeMs != daemonSessionStartMs) {
                sessionStartTimeMs = daemonSessionStartMs
                val refreshedDuration = computeSessionDurationSeconds(sessionStartTimeMs, wallClockMs())
                _uiState.update { state ->
                    if (state.sessionDurationSeconds == refreshedDuration) state
                    else state.copy(sessionDurationSeconds = refreshedDuration)
                }
            }
            ensureDurationTickerIfVisible()
            return
        }

        val targetState = if (sessionCoordinator.isSwitchingCountry) {
            VpnConnectionState.RECONNECTING
        } else {
            VpnConnectionState.CONNECTING
        }
        val verifyingText = runtimeBridge.string(R.string.mcqueen_verifying_tunnel)
        _uiState.update { state ->
            if (state.connectionState == targetState && state.statusDetail == verifyingText) {
                state
            } else {
                state.copy(
                    connectionState = targetState,
                    statusDetail = verifyingText,
                )
            }
        }

        verifyTunnelJob?.cancel()
        val requestId = "verify_${UUID.randomUUID()}"
        pendingVerifyRequestId = requestId
        pendingVerifyOperationId = operationId
        verifyTunnelJob = viewModelScope.launch {
            delay(TUNNEL_VERIFY_DELAY_MS)
            if (!sessionCoordinator.isCurrentOperation(operationId)) return@launch
            runtimeBridge.sendMsg2Service(AppConfig.MSG_MEASURE_DELAY, "", requestId)
        }
    }

    private fun handleTunnelVerificationMessage(
        requestId: String,
        content: String,
        delayMs: Long? = null,
    ) {
        if (sessionCoordinator.userRequestedDisconnect) return
        val expectedId = pendingVerifyRequestId ?: return
        if (requestId.isNotEmpty() && requestId != expectedId) return
        if (!sessionCoordinator.isCurrentOperation(pendingVerifyOperationId)) {
            pendingVerifyRequestId = null
            pendingVerifyOperationId = 0L
            return
        }

        val firstLine = content.lineSequence().firstOrNull().orEmpty()
        val isSuccess = TunnelProbePolicy.isVerificationSuccess(delayMs, content)

        pendingVerifyRequestId = null
        pendingVerifyOperationId = 0L
        if (isSuccess) {
            cancelConnectionGuards()
            markSessionConnected(daemonSessionStartMs = sessionStartTimeMs)
            runtimeBridge.sendMsg2Service(AppConfig.MSG_REGISTER_CLIENT, "", null)
        } else {
            runtimeBridge.logWarn("Tunnel verification failed ($firstLine, delayMs=$delayMs); rotating candidate")
            runtimeBridge.sendMsg2Service(AppConfig.MSG_STATE_STOP, "", null)
            handleConnectionCandidateFailure(
                runtimeBridge.string(R.string.mcqueen_tunnel_check_failed)
            )
        }
    }

    private fun markSessionConnected(daemonSessionStartMs: Long) {
        sessionCoordinator.onStartSuccess()
        val resolvedStartMs = when {
            daemonSessionStartMs > 0L -> daemonSessionStartMs
            sessionStartTimeMs > 0L -> sessionStartTimeMs
            else -> wallClockMs()
        }
        sessionStartTimeMs = resolvedStartMs
        lastTrafficSyncElapsedMs = -1L
        val elapsedSeconds = computeSessionDurationSeconds(resolvedStartMs, wallClockMs())
        _uiState.update { state ->
            if (state.connectionState == VpnConnectionState.CONNECTED &&
                state.statusDetail.isEmpty() &&
                state.sessionDurationSeconds == elapsedSeconds
            ) {
                state
            } else {
                state.copy(
                    connectionState = VpnConnectionState.CONNECTED,
                    statusDetail = "",
                    sessionDurationSeconds = elapsedSeconds,
                )
            }
        }
        ensureDurationTickerIfVisible()
    }

    private fun handleTrafficStats(snapshot: TrafficStatsSnapshot?) {
        if (_uiState.value.connectionState != VpnConnectionState.CONNECTED) return
        val downBps = (snapshot?.downlinkBps ?: 0L).coerceAtLeast(0L)
        val upBps = (snapshot?.uplinkBps ?: 0L).coerceAtLeast(0L)
        val sessionDownBytes = (snapshot?.sessionDownlinkBytes ?: 0L).coerceAtLeast(0L)
        val sessionUpBytes = (snapshot?.sessionUplinkBytes ?: 0L).coerceAtLeast(0L)
        val startMs = snapshot?.sessionStartMs ?: 0L
        if (startMs > 0L && sessionStartTimeMs != startMs) {
            sessionStartTimeMs = startMs
        } else if (sessionStartTimeMs <= 0L) {
            sessionStartTimeMs = wallClockMs()
        }
        val durationSeconds = computeSessionDurationSeconds(sessionStartTimeMs, wallClockMs())
        lastTrafficSyncElapsedMs = elapsedClockMs()

        _uiState.update { state ->
            if (state.downloadBps == downBps &&
                state.uploadBps == upBps &&
                state.sessionDownloadedBytes == sessionDownBytes &&
                state.sessionUploadedBytes == sessionUpBytes &&
                state.sessionDurationSeconds == durationSeconds
            ) {
                state
            } else {
                state.copy(
                    downloadBps = downBps,
                    uploadBps = upBps,
                    sessionDownloadedBytes = sessionDownBytes,
                    sessionUploadedBytes = sessionUpBytes,
                    sessionDurationSeconds = durationSeconds,
                )
            }
        }
    }

    private fun startConnectionTimeoutGuard(countryName: String, expectedOperationId: Long) {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = viewModelScope.launch {
            delay(CANDIDATE_CONNECTION_TIMEOUT_MS)
            if (!sessionCoordinator.isCurrentOperation(expectedOperationId)) return@launch
            val state = _uiState.value.connectionState
            if (state == VpnConnectionState.PREPARING ||
                state == VpnConnectionState.CONNECTING ||
                state == VpnConnectionState.RECONNECTING
            ) {
                runtimeBridge.logWarn("Connection attempt timed out in $countryName; rotating candidate")
                runtimeBridge.sendMsg2Service(AppConfig.MSG_STATE_STOP, "", null)
                handleConnectionCandidateFailure(
                    runtimeBridge.string(R.string.mcqueen_connection_timeout)
                )
            }
        }
    }

    private fun cancelConnectionGuards() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        verifyTunnelJob?.cancel()
        verifyTunnelJob = null
        pendingVerifyRequestId = null
        pendingVerifyOperationId = 0L
    }

    private fun handleConnectionCandidateFailure(reason: String = "") {
        if (sessionCoordinator.userRequestedDisconnect) return
        cancelConnectionGuards()
        stopDurationTicker()

        val countryName = _uiState.value.selectedCountry?.countryName
            ?: runtimeBridge.string(R.string.app_name)

        val decision = sessionCoordinator.onStartFailure(reason)
        val operationId = sessionCoordinator.activeOperationId

        when (decision) {
            is FailoverDecision.RetryNext -> {
                _uiState.update {
                    it.copy(
                        connectionState = VpnConnectionState.RECONNECTING,
                        statusDetail = runtimeBridge.string(R.string.mcqueen_failover_notice, countryName),
                        downloadBps = 0L,
                        uploadBps = 0L,
                    )
                }
                failoverJob?.cancel()
                failoverJob = viewModelScope.launch {
                    awaitActiveServiceStopped(operationId)
                    if (!sessionCoordinator.isCurrentOperation(operationId)) return@launch
                    runtimeBridge.setSelectServer(decision.guid)
                    startConnectionTimeoutGuard(countryName, operationId)
                    val permissionCallback = startVpnCallback
                    if (permissionCallback != null) {
                        permissionCallback.invoke()
                    } else {
                        sessionCoordinator.markServiceLaunched(operationId)
                        runtimeBridge.startService(decision.guid)
                    }
                }
            }

            is FailoverDecision.Exhausted -> {
                val displayDetail = decision.reason.takeIf { it.isNotBlank() }
                    ?: runtimeBridge.string(R.string.mcqueen_all_candidates_failed, countryName)
                _uiState.update {
                    it.copy(
                        connectionState = VpnConnectionState.FAILED,
                        statusDetail = displayDetail,
                        downloadBps = 0L,
                        uploadBps = 0L,
                        sessionDownloadedBytes = 0L,
                        sessionUploadedBytes = 0L,
                        sessionDurationSeconds = 0L,
                    )
                }
                _snackbarEvents.tryEmit(runtimeBridge.string(R.string.mcqueen_status_hint_failed))
            }
        }
    }

    fun disconnectVpn() {
        val hadRunningService = isServiceRunning.get() ||
            pendingStopAckFromDisconnect.get() ||
            sessionCoordinator.hasLaunchedOrActiveService()
        sessionCoordinator.onUserDisconnect()
        connectJob?.cancel()
        connectJob = null
        failoverJob?.cancel()
        failoverJob = null
        cancelConnectionGuards()
        stopDurationTicker()
        if (hadRunningService) {
            pendingStopAckFromDisconnect.set(true)
        }
        runtimeBridge.stopService()
        signalServiceStopped()
        _uiState.update { state ->
            if (state.connectionState == VpnConnectionState.DISCONNECTED &&
                state.statusDetail.isEmpty() &&
                state.downloadBps == 0L &&
                state.uploadBps == 0L &&
                state.sessionDownloadedBytes == 0L &&
                state.sessionUploadedBytes == 0L &&
                state.sessionDurationSeconds == 0L
            ) {
                state
            } else {
                state.copy(
                    connectionState = VpnConnectionState.DISCONNECTED,
                    statusDetail = "",
                    downloadBps = 0L,
                    uploadBps = 0L,
                    sessionDownloadedBytes = 0L,
                    sessionUploadedBytes = 0L,
                    sessionDurationSeconds = 0L,
                )
            }
        }
    }

    fun onVpnPermissionDenied() {
        sessionCoordinator.onUserDisconnect()
        connectJob?.cancel()
        connectJob = null
        failoverJob?.cancel()
        failoverJob = null
        cancelConnectionGuards()
        stopDurationTicker()
        pendingStopAckFromDisconnect.set(false)
        signalServiceStopped()
        _uiState.update {
            it.copy(
                connectionState = VpnConnectionState.DISCONNECTED,
                statusDetail = "",
                downloadBps = 0L,
                uploadBps = 0L,
                sessionDownloadedBytes = 0L,
                sessionUploadedBytes = 0L,
                sessionDurationSeconds = 0L,
            )
        }
        _snackbarEvents.tryEmit(runtimeBridge.string(R.string.mcqueen_vpn_permission_denied))
    }

    fun launchServiceAfterPermission() {
        if (sessionCoordinator.userRequestedDisconnect) return
        val guid = sessionCoordinator.currentGuid() ?: runtimeBridge.getSelectServer()
        if (guid == null) {
            connectSelectedCountry()
            return
        }
        sessionCoordinator.markServiceLaunched()
        runtimeBridge.startService(guid)
    }

    private fun ensureDurationTickerIfVisible() {
        if (!isHostVisible || _uiState.value.connectionState != VpnConnectionState.CONNECTED) {
            return
        }
        if (durationTickerJob?.isActive == true) return
        if (sessionStartTimeMs <= 0L) {
            sessionStartTimeMs = wallClockMs()
        }
        durationTickerJob = viewModelScope.launch {
            while (isActive && isHostVisible && _uiState.value.connectionState == VpnConnectionState.CONNECTED) {
                delay(DURATION_TICK_INTERVAL_MS)
                if (!isActive || !isHostVisible || _uiState.value.connectionState != VpnConnectionState.CONNECTED) {
                    break
                }
                val lastTrafficSync = lastTrafficSyncElapsedMs
                if (lastTrafficSync >= 0L && (elapsedClockMs() - lastTrafficSync) < TRAFFIC_SYNC_GRACE_MS) {
                    // Traffic telemetry already emitted the authoritative speed + duration snapshot in this cycle.
                    continue
                }
                val elapsed = computeSessionDurationSeconds(sessionStartTimeMs, wallClockMs())
                _uiState.update { state ->
                    if (state.sessionDurationSeconds == elapsed) state
                    else state.copy(sessionDurationSeconds = elapsed)
                }
            }
        }
    }

    private fun stopDurationTicker() {
        durationTickerJob?.cancel()
        durationTickerJob = null
        sessionStartTimeMs = 0L
        lastTrafficSyncElapsedMs = -1L
    }

    private fun registerServiceReceiver() {
        if (isReceiverRegistered) return
        runtimeBridge.registerReceiver(mMsgReceiver)
        isReceiverRegistered = true
    }

    private fun unregisterServiceReceiver() {
        if (!isReceiverRegistered) return
        try {
            runtimeBridge.unregisterReceiver(mMsgReceiver)
        } catch (e: Exception) {
            runtimeBridge.logWarn("Failed to unregister McQueen service receiver", e)
        }
        isReceiverRegistered = false
    }

    companion object {
        private const val CANDIDATE_CONNECTION_TIMEOUT_MS = 18_000L
        internal const val SERVICE_STOP_AWAIT_TIMEOUT_MS = 3_500L
        private const val TUNNEL_VERIFY_DELAY_MS = 350L
        private const val DURATION_TICK_INTERVAL_MS = 1_000L
        internal const val TRAFFIC_SYNC_GRACE_MS = 1_150L

        internal fun computeSessionDurationSeconds(startMs: Long, nowMs: Long): Long {
            return if (startMs > 0L) ((nowMs - startMs) / 1000L).coerceAtLeast(0L) else 0L
        }
    }
}
