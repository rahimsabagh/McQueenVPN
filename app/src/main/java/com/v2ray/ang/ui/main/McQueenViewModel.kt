package com.v2ray.ang.ui.main

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.BypassIranRoutingHelper
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.TunnelProbePolicy
import com.v2ray.ang.dto.TrafficStatsSnapshot
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
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
import com.v2ray.ang.mcqueen.ManifestSyncOutcome
import com.v2ray.ang.mcqueen.McQueenManifestUpdater
import com.v2ray.ang.mcqueen.McQueenServerRepository
import com.v2ray.ang.mcqueen.VpnConnectionState
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class McQueenViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = McQueenServerRepository()
    private val appUpdateChecker = AppUpdateChecker()
    private val sessionCoordinator = ConnectionSessionCoordinator()

    private val _uiState = MutableStateFlow(
        McQueenUiState(
            manifestSourceUrl = repository.getConfiguredUrl(),
            hasAuthToken = repository.hasConfiguredAuthToken(),
            isBatteryOptimizationExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(application),
            themeMode = ThemeManager.themeMode,
            isBypassIranEnabled = SettingsManager.isBypassIranEnabled(),
        )
    )
    val uiState: StateFlow<McQueenUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private val _openExternalUrlEvents = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val openExternalUrlEvents: SharedFlow<String> = _openExternalUrlEvents.asSharedFlow()

    private var startVpnCallback: (() -> Unit)? = null
    private var connectJob: Job? = null
    private var failoverJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var verifyTunnelJob: Job? = null
    private var durationTickerJob: Job? = null
    private var latencyRefreshJob: Job? = null
    private var manifestSyncJob: Job? = null
    private var updateCheckJob: Job? = null
    private var sessionStartTimeMs: Long = 0L
    private var pendingVerifyRequestId: String? = null
    private var isReceiverRegistered = false
    private val isServiceRunning = AtomicBoolean(false)

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    isServiceRunning.set(true)
                    onCoreServiceStarted(isAlreadyRunning = true)
                }

                AppConfig.MSG_STATE_CONNECTING -> {
                    isServiceRunning.set(true)
                    val detail = intent.getStringExtra("content").orEmpty()
                    if (!sessionCoordinator.userRequestedDisconnect) {
                        _uiState.update {
                            it.copy(
                                connectionState = if (sessionCoordinator.isSwitchingCountry) {
                                    VpnConnectionState.RECONNECTING
                                } else {
                                    VpnConnectionState.CONNECTING
                                },
                                statusDetail = detail.ifBlank { it.statusDetail }
                            )
                        }
                    }
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    isServiceRunning.set(false)
                    val nextState = sessionCoordinator.onServiceStopped(
                        preserveFailure = _uiState.value.connectionState == VpnConnectionState.FAILED
                    )
                    if (nextState != VpnConnectionState.RECONNECTING) {
                        stopDurationTicker()
                        cancelConnectionGuards()
                        _uiState.update { state ->
                            state.copy(
                                connectionState = nextState,
                                statusDetail = if (nextState == VpnConnectionState.FAILED) state.statusDetail else "",
                                downloadBps = 0L,
                                uploadBps = 0L,
                                sessionDownloadedBytes = 0L,
                                sessionUploadedBytes = 0L,
                                sessionDurationSeconds = 0L,
                            )
                        }
                    }
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    isServiceRunning.set(true)
                    onCoreServiceStarted(isAlreadyRunning = false)
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    isServiceRunning.set(false)
                    val reason = intent.getStringExtra("content").orEmpty()
                    handleConnectionCandidateFailure(reason)
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    isServiceRunning.set(false)
                    val nextState = sessionCoordinator.onServiceStopped(
                        preserveFailure = _uiState.value.connectionState == VpnConnectionState.FAILED
                    )
                    if (nextState != VpnConnectionState.RECONNECTING) {
                        stopDurationTicker()
                        cancelConnectionGuards()
                        _uiState.update { state ->
                            state.copy(
                                connectionState = nextState,
                                statusDetail = if (nextState == VpnConnectionState.FAILED) state.statusDetail else "",
                                downloadBps = 0L,
                                uploadBps = 0L,
                                sessionDownloadedBytes = 0L,
                                sessionUploadedBytes = 0L,
                                sessionDurationSeconds = 0L,
                            )
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
    }

    init {
        registerServiceReceiver()
        observeSettingsReload()
        evaluateInitialBatteryOptimization()
        initializeGeoAssetsAndRefreshBypassStatus()
        McQueenManifestUpdater.sync(application, forceReschedule = false)
        loadInitialLocationsAndSync()
    }

    fun bindStartVpnCallback(callback: () -> Unit) {
        startVpnCallback = callback
    }

    override fun onCleared() {
        unregisterServiceReceiver()
        stopDurationTicker()
        cancelConnectionGuards()
        super.onCleared()
    }

    fun onHostResumed() {
        ThemeManager.refresh()
        refreshBatteryOptimizationStatus()
        refreshServiceStatus()
        refreshBypassIranStatus()
    }

    fun evaluateInitialBatteryOptimization() {
        val app = getApplication<Application>()
        val isExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(app)
        _uiState.update {
            it.copy(
                isBatteryOptimizationExempt = isExempt,
                showBatteryOptimizationDialog = false,
            )
        }
    }

    fun refreshBatteryOptimizationStatus() {
        val app = getApplication<Application>()
        val isExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(app)
        _uiState.update {
            it.copy(
                isBatteryOptimizationExempt = isExempt,
                showBatteryOptimizationDialog = if (isExempt) false else it.showBatteryOptimizationDialog,
            )
        }
    }

    fun dismissBatteryOptimizationDialog() {
        BatteryOptimizationHelper.markInitialPromptShown()
        _uiState.update { it.copy(showBatteryOptimizationDialog = false) }
    }

    fun onConfirmBatteryOptimizationDialog() {
        BatteryOptimizationHelper.markInitialPromptShown()
        _uiState.update { it.copy(showBatteryOptimizationDialog = false) }
    }

    fun setThemeMode(mode: String) {
        ThemeManager.setThemeMode(mode)
        _uiState.update { it.copy(themeMode = ThemeManager.themeMode) }
    }

    fun setBypassIranEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        val previous = SettingsManager.isBypassIranEnabled()
        SettingsManager.setBypassIranEnabled(enabled)
        _uiState.update { it.copy(isBypassIranEnabled = enabled) }
        refreshBypassIranStatus()

        if (previous == enabled) return

        val state = _uiState.value.connectionState
        val isActiveSession = state == VpnConnectionState.CONNECTED ||
            state == VpnConnectionState.PREPARING ||
            state == VpnConnectionState.CONNECTING ||
            state == VpnConnectionState.RECONNECTING
        val selected = _uiState.value.selectedCountry
        if (isActiveSession && selected != null) {
            _snackbarEvents.tryEmit(app.getString(R.string.mcqueen_bypass_iran_reconnecting))
            switchCountryAndReconnect(selected)
        } else {
            val msgRes = if (enabled) {
                R.string.mcqueen_bypass_iran_enabled_next
            } else {
                R.string.mcqueen_bypass_iran_disabled_next
            }
            _snackbarEvents.tryEmit(app.getString(msgRes))
        }
    }

    fun refreshBypassIranStatus() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val countryCode = _uiState.value.selectedCountry?.countryCode
            val status = withContext(Dispatchers.IO) {
                val selectedGuid = sessionCoordinator.currentGuid()
                    ?: MmkvManager.getSelectServer()
                    ?: countryCode?.let { code ->
                        runCatching { repository.selectCandidatesForCountry(app, code).firstOrNull()?.guid }.getOrNull()
                    }
                BypassIranRoutingHelper.checkCompatibility(app, selectedGuid)
            }
            _uiState.update {
                it.copy(
                    isBypassIranEnabled = SettingsManager.isBypassIranEnabled(),
                    bypassIranAvailability = status,
                )
            }
        }
    }

    private fun initializeGeoAssetsAndRefreshBypassStatus() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val status = withContext(Dispatchers.IO) {
                SettingsManager.initAssets(app, app.assets)
                BypassIranRoutingHelper.checkCompatibility(app, MmkvManager.getSelectServer())
            }
            _uiState.update {
                it.copy(
                    isBypassIranEnabled = SettingsManager.isBypassIranEnabled(),
                    bypassIranAvailability = status,
                )
            }
        }
    }

    fun checkForAppUpdate() {
        if (updateCheckJob?.isActive == true) return
        val app = getApplication<Application>()
        _uiState.update { it.copy(appUpdateState = AppUpdateCheckResult.Checking) }

        updateCheckJob = viewModelScope.launch {
            val result = appUpdateChecker.checkLatestRelease()
            _uiState.update { it.copy(appUpdateState = result) }

            when (result) {
                is AppUpdateCheckResult.UpdateAvailable -> Unit
                is AppUpdateCheckResult.UpToDate -> {
                    _snackbarEvents.tryEmit(
                        app.getString(R.string.mcqueen_update_up_to_date, result.installedVersion)
                    )
                }

                AppUpdateCheckResult.NoInternet -> {
                    _snackbarEvents.tryEmit(app.getString(R.string.mcqueen_update_no_internet))
                }

                is AppUpdateCheckResult.RateLimited -> {
                    _snackbarEvents.tryEmit(app.getString(R.string.mcqueen_update_rate_limited))
                }

                is AppUpdateCheckResult.InvalidResponse -> {
                    _snackbarEvents.tryEmit(app.getString(R.string.mcqueen_update_invalid_response))
                }

                is AppUpdateCheckResult.MissingApkAsset -> {
                    _snackbarEvents.tryEmit(
                        app.getString(R.string.mcqueen_update_missing_apk, result.latestVersion)
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
                getApplication<Application>().getString(R.string.mcqueen_update_invalid_response)
            )
            return
        }
        _openExternalUrlEvents.tryEmit(current.apkDownloadUrl)
        _uiState.update { it.copy(appUpdateState = AppUpdateCheckResult.Idle) }
    }

    fun refreshServiceStatus() {
        MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    private fun observeSettingsReload() {
        _uiState.update {
            it.copy(
                manifestSourceUrl = repository.getConfiguredUrl(),
                hasAuthToken = repository.hasConfiguredAuthToken(),
                themeMode = ThemeManager.themeMode,
            )
        }
    }

    private fun loadInitialLocationsAndSync() {
        viewModelScope.launch {
            reloadCachedCountriesInternal()
            syncManifestFromGitHub(silentSuccess = true, forceRefresh = false)
        }
    }

    private fun reloadCachedCountries() {
        viewModelScope.launch {
            reloadCachedCountriesInternal()
        }
    }

    private suspend fun reloadCachedCountriesInternal(): Boolean {
        val app = getApplication<Application>()
        val cached = repository.loadCachedManifest(app) ?: return false
        if (cached.countries.isEmpty()) return false
        applyCountriesList(cached.countries, cached.updatedAt.orEmpty())
        return true
    }

    private fun applyCountriesList(countries: List<CountryUiModel>, updatedAt: String) {
        val savedCode = MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY_CODE, null)
        val savedName = MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY, null)
        val resolvedSelection = McQueenUiHelper.resolveSavedCountry(
            countries = countries,
            currentSelectedCode = _uiState.value.selectedCountry?.countryCode,
            savedCode = savedCode,
            savedName = savedName,
        )

        if (resolvedSelection != null) {
            persistSelectedCountry(resolvedSelection)
        } else if (!savedCode.isNullOrBlank() || !savedName.isNullOrBlank()) {
            clearPersistedCountry()
        }

        _uiState.update {
            it.copy(
                countries = countries,
                selectedCountry = resolvedSelection,
                manifestErrorMessage = null,
                lastSyncUpdatedAt = updatedAt.ifBlank { it.lastSyncUpdatedAt },
                manifestSourceUrl = repository.getConfiguredUrl(),
                hasAuthToken = repository.hasConfiguredAuthToken(),
            )
        }
        if (resolvedSelection != null) {
            refreshBypassIranStatus()
        }
    }

    fun selectCountry(country: CountryUiModel) {
        val currentState = _uiState.value.connectionState
        val previousCountryCode = _uiState.value.selectedCountry?.countryCode

        persistSelectedCountry(country)

        val shouldSwitch = sessionCoordinator.shouldTriggerCountrySwitch(
            currentState = currentState,
            currentCountryCode = previousCountryCode,
            newCountryCode = country.countryCode,
        )

        if (!shouldSwitch) {
            _uiState.update {
                it.copy(
                    selectedCountry = country,
                    connectionState = if (it.connectionState == VpnConnectionState.FAILED) {
                        VpnConnectionState.DISCONNECTED
                    } else {
                        it.connectionState
                    },
                    statusDetail = if (it.connectionState == VpnConnectionState.FAILED) "" else it.statusDetail,
                )
            }
            return
        }

        switchCountryAndReconnect(country)
        refreshBypassIranStatus()
    }

    private fun persistSelectedCountry(country: CountryUiModel) {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY_CODE, country.countryCode)
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY, country.countryName)
    }

    private fun clearPersistedCountry() {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY_CODE, "")
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY, "")
    }

    fun syncManifestFromGitHub(silentSuccess: Boolean = false, forceRefresh: Boolean = !silentSuccess) {
        val existingJob = manifestSyncJob
        if (existingJob?.isActive == true) {
            if (!forceRefresh) return
            existingJob.cancel()
        }

        manifestSyncJob = viewModelScope.launch {
            val app = getApplication<Application>()
            _uiState.update { it.copy(isSyncingManifest = true, manifestErrorMessage = null) }
            try {
                when (val outcome = repository.syncFromGitHub(app)) {
                    is ManifestSyncOutcome.Success -> {
                        val countries = outcome.manifest.countries
                        applyCountriesList(countries, outcome.manifest.updatedAt.orEmpty())
                        if (!silentSuccess) {
                            _snackbarEvents.tryEmit(
                                app.getString(R.string.mcqueen_sync_success, countries.size)
                            )
                        }
                        refreshCountryLatenciesInBackground(countries)
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
                                app.getString(R.string.mcqueen_sync_error, outcome.reason)
                            )
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(isSyncingManifest = false) }
            }
        }
    }

    private fun refreshCountryLatenciesInBackground(countries: List<CountryUiModel>) {
        latencyRefreshJob?.cancel()
        latencyRefreshJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val measured = runCatching {
                repository.measureCountryLatencies(app, countries)
            }.getOrNull() ?: return@launch

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

            _uiState.update {
                it.copy(
                    countries = mergedCountries,
                    selectedCountry = updatedSelected,
                )
            }
        }
    }

    fun updateManifestSourceAndSync(url: String, authToken: String? = null, clearToken: Boolean = false) {
        val saved = repository.saveCustomManifestConfig(
            url = url,
            authTokenInput = authToken,
            clearAuthToken = clearToken,
        )
        if (!saved) {
            _snackbarEvents.tryEmit(getApplication<Application>().getString(R.string.mcqueen_settings_manifest_invalid))
            return
        }
        _uiState.update {
            it.copy(
                manifestSourceUrl = repository.getConfiguredUrl(),
                hasAuthToken = repository.hasConfiguredAuthToken(),
            )
        }
        McQueenManifestUpdater.sync(getApplication(), forceReschedule = true)
        syncManifestFromGitHub(silentSuccess = false, forceRefresh = true)
    }

    fun resetManifestSourceAndSync() {
        repository.resetCustomUrl()
        _uiState.update {
            it.copy(
                manifestSourceUrl = repository.getConfiguredUrl(),
                hasAuthToken = repository.hasConfiguredAuthToken(),
            )
        }
        McQueenManifestUpdater.sync(getApplication(), forceReschedule = true)
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
        connectJob?.cancel()
        failoverJob?.cancel()
        cancelConnectionGuards()
        stopDurationTicker()

        val switchingStatus = getApplication<Application>().getString(
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
            awaitActiveServiceStopped(operationId)
            if (!sessionCoordinator.isCurrentOperation(operationId)) return@launch

            startCountryCandidates(
                country = country,
                operationId = operationId,
                isCountrySwitch = true,
            )
        }
    }

    private suspend fun awaitActiveServiceStopped(operationId: Long) {
        val wasRunning = isServiceRunning.get() || CoreServiceManager.isServiceRunning()
        if (wasRunning) {
            MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_STATE_STOP, "")
        }

        val deadlineElapsedMs = SystemClock.elapsedRealtime() + SERVICE_STOP_AWAIT_TIMEOUT_MS
        while (sessionCoordinator.isCurrentOperation(operationId) &&
            (isServiceRunning.get() || CoreServiceManager.isServiceRunning()) &&
            SystemClock.elapsedRealtime() < deadlineElapsedMs
        ) {
            delay(SERVICE_STOP_POLL_INTERVAL_MS)
        }
        isServiceRunning.set(false)
    }

    private fun connectSelectedCountry() {
        val country = _uiState.value.selectedCountry
            ?: _uiState.value.countries.firstOrNull()?.also { firstCountry ->
                persistSelectedCountry(firstCountry)
                _uiState.update { it.copy(selectedCountry = firstCountry) }
            }
        if (country == null) {
            _snackbarEvents.tryEmit(getApplication<Application>().getString(R.string.mcqueen_select_country_first))
            syncManifestFromGitHub(silentSuccess = true, forceRefresh = false)
            return
        }

        connectJob?.cancel()
        failoverJob?.cancel()
        cancelConnectionGuards()

        sessionCoordinator.startPreparing(country.countryCode)
        val operationId = sessionCoordinator.activeOperationId
        connectJob = viewModelScope.launch {
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
        val app = getApplication<Application>()

        val needsProvisioning = repository.hasPendingIdentityProvisioning(app, country.countryCode)
        if (!sessionCoordinator.isCurrentOperation(operationId)) return

        if (needsProvisioning) {
            _uiState.update {
                it.copy(
                    connectionState = if (isCountrySwitch) VpnConnectionState.RECONNECTING else VpnConnectionState.PREPARING,
                    statusDetail = if (isCountrySwitch) {
                        app.getString(R.string.mcqueen_switching_country, country.countryName)
                    } else {
                        app.getString(R.string.mcqueen_preparing_secure_connection)
                    },
                    downloadBps = 0L,
                    uploadBps = 0L,
                )
            }
        }

        when (val identityStatus = repository.ensureIdentitiesForCountry(app, country.countryCode)) {
            is CountryIdentityStatus.Ready -> {
                LogUtil.i(AppConfig.TAG, "Identity status ready for ${country.countryCode}")
            }

            is CountryIdentityStatus.Failed -> {
                if (!sessionCoordinator.isCurrentOperation(operationId)) return
                val errorText = app.getString(identityStatus.kind.userMessageRes)
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
                    app.getString(R.string.mcqueen_switching_country, country.countryName)
                } else {
                    app.getString(R.string.mcqueen_testing_country, country.countryName)
                },
                downloadBps = 0L,
                uploadBps = 0L,
            )
        }

        val rankedCandidates = repository.selectCandidatesForCountry(app, country.countryCode)
        if (!sessionCoordinator.isCurrentOperation(operationId)) return

        val orderedGuids = rankedCandidates.map { it.guid }
        val firstGuid = if (isCountrySwitch) {
            sessionCoordinator.attachSwitchCandidates(operationId, orderedGuids)
        } else {
            sessionCoordinator.startAttempt(orderedGuids, country.countryCode)
        }

        if (firstGuid == null) {
            val failedText = app.getString(R.string.mcqueen_all_candidates_failed, country.countryName)
            _uiState.update {
                it.copy(
                    connectionState = VpnConnectionState.FAILED,
                    statusDetail = failedText,
                )
            }
            _snackbarEvents.tryEmit(failedText)
            return
        }

        MmkvManager.setSelectServer(firstGuid)
        startConnectionTimeoutGuard(country.countryName, sessionCoordinator.activeOperationId)
        startVpnCallback?.invoke() ?: LauncherManager.startService(app, firstGuid)
    }

    private fun onCoreServiceStarted(isAlreadyRunning: Boolean) {
        if (sessionCoordinator.userRequestedDisconnect) return
        val operationId = sessionCoordinator.activeOperationId

        val activeGuid = MmkvManager.getSelectServer()
        if (activeGuid != null && sessionCoordinator.currentGuid() == null && isAlreadyRunning) {
            val country = _uiState.value.selectedCountry
            sessionCoordinator.startAttempt(listOf(activeGuid), country?.countryCode)
            sessionCoordinator.onStartSuccess()
            startDurationTicker()
            _uiState.update {
                it.copy(
                    connectionState = VpnConnectionState.CONNECTED,
                    statusDetail = "",
                )
            }
            return
        }

        if (isAlreadyRunning && _uiState.value.connectionState == VpnConnectionState.CONNECTED) {
            return
        }

        _uiState.update {
            it.copy(
                connectionState = if (sessionCoordinator.isSwitchingCountry) {
                    VpnConnectionState.RECONNECTING
                } else {
                    VpnConnectionState.CONNECTING
                },
                statusDetail = getApplication<Application>().getString(R.string.mcqueen_verifying_tunnel),
            )
        }

        verifyTunnelJob?.cancel()
        val requestId = "verify_${UUID.randomUUID()}"
        pendingVerifyRequestId = requestId
        verifyTunnelJob = viewModelScope.launch {
            delay(350L)
            if (!sessionCoordinator.isCurrentOperation(operationId)) return@launch
            MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "", requestId)
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

        val firstLine = content.lineSequence().firstOrNull().orEmpty()
        val isSuccess = TunnelProbePolicy.isVerificationSuccess(delayMs, content)

        pendingVerifyRequestId = null
        if (isSuccess) {
            cancelConnectionGuards()
            sessionCoordinator.onStartSuccess()
            startDurationTicker()
            _uiState.update {
                it.copy(
                    connectionState = VpnConnectionState.CONNECTED,
                    statusDetail = "",
                )
            }
        } else {
            LogUtil.w(AppConfig.TAG, "Tunnel verification failed ($firstLine, delayMs=$delayMs); rotating candidate")
            MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_STATE_STOP, "")
            handleConnectionCandidateFailure(
                getApplication<Application>().getString(R.string.mcqueen_tunnel_check_failed)
            )
        }
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
        }
        _uiState.update {
            it.copy(
                downloadBps = downBps,
                uploadBps = upBps,
                sessionDownloadedBytes = sessionDownBytes,
                sessionUploadedBytes = sessionUpBytes,
            )
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
                LogUtil.w(AppConfig.TAG, "Connection attempt timed out in $countryName; rotating candidate")
                MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_STATE_STOP, "")
                handleConnectionCandidateFailure(
                    getApplication<Application>().getString(R.string.mcqueen_connection_timeout)
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
    }

    private fun handleConnectionCandidateFailure(reason: String = "") {
        if (sessionCoordinator.userRequestedDisconnect) return
        cancelConnectionGuards()
        stopDurationTicker()

        val app = getApplication<Application>()
        val countryName = _uiState.value.selectedCountry?.countryName
            ?: app.getString(R.string.app_name)

        val decision = sessionCoordinator.onStartFailure(reason)
        val operationId = sessionCoordinator.activeOperationId

        when (decision) {
            is FailoverDecision.RetryNext -> {
                _uiState.update {
                    it.copy(
                        connectionState = VpnConnectionState.RECONNECTING,
                        statusDetail = app.getString(R.string.mcqueen_failover_notice, countryName),
                        downloadBps = 0L,
                        uploadBps = 0L,
                    )
                }
                failoverJob?.cancel()
                failoverJob = viewModelScope.launch {
                    awaitActiveServiceStopped(operationId)
                    if (!sessionCoordinator.isCurrentOperation(operationId)) return@launch
                    MmkvManager.setSelectServer(decision.guid)
                    startConnectionTimeoutGuard(countryName, operationId)
                    startVpnCallback?.invoke() ?: LauncherManager.startService(app, decision.guid)
                }
            }

            is FailoverDecision.Exhausted -> {
                val displayDetail = decision.reason.takeIf { it.isNotBlank() }
                    ?: app.getString(R.string.mcqueen_all_candidates_failed, countryName)
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
                _snackbarEvents.tryEmit(app.getString(R.string.mcqueen_status_hint_failed))
            }
        }
    }

    fun disconnectVpn() {
        sessionCoordinator.onUserDisconnect()
        connectJob?.cancel()
        failoverJob?.cancel()
        cancelConnectionGuards()
        stopDurationTicker()
        LauncherManager.stopService(getApplication())
        isServiceRunning.set(false)
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
    }

    fun onVpnPermissionDenied() {
        sessionCoordinator.onUserDisconnect()
        cancelConnectionGuards()
        _uiState.update {
            it.copy(
                connectionState = VpnConnectionState.DISCONNECTED,
                statusDetail = "",
                downloadBps = 0L,
                uploadBps = 0L,
                sessionDownloadedBytes = 0L,
                sessionUploadedBytes = 0L,
            )
        }
        _snackbarEvents.tryEmit(getApplication<Application>().getString(R.string.mcqueen_vpn_permission_denied))
    }

    fun launchServiceAfterPermission() {
        val guid = sessionCoordinator.currentGuid() ?: MmkvManager.getSelectServer()
        if (guid == null) {
            connectSelectedCountry()
            return
        }
        LauncherManager.startService(getApplication(), guid)
    }

    private fun startDurationTicker() {
        if (durationTickerJob?.isActive == true) return
        val daemonSessionStart = CoreServiceManager.getSessionStartTimeMs()
        sessionStartTimeMs = if (daemonSessionStart > 0L) daemonSessionStart else System.currentTimeMillis()
        durationTickerJob = viewModelScope.launch {
            while (isActive) {
                val latestDaemonStart = CoreServiceManager.getSessionStartTimeMs()
                if (latestDaemonStart > 0L) {
                    sessionStartTimeMs = latestDaemonStart
                }
                val elapsed = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000L).coerceAtLeast(0L)
                _uiState.update { it.copy(sessionDurationSeconds = elapsed) }
                delay(1000L)
            }
        }
    }

    private fun stopDurationTicker() {
        durationTickerJob?.cancel()
        durationTickerJob = null
        sessionStartTimeMs = 0L
    }

    private fun registerServiceReceiver() {
        if (isReceiverRegistered) return
        ContextCompat.registerReceiver(
            getApplication(),
            mMsgReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags()
        )
        isReceiverRegistered = true
    }

    private fun unregisterServiceReceiver() {
        if (!isReceiverRegistered) return
        try {
            getApplication<Application>().unregisterReceiver(mMsgReceiver)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to unregister McQueen service receiver", e)
        }
        isReceiverRegistered = false
    }

    companion object {
        private const val CANDIDATE_CONNECTION_TIMEOUT_MS = 18_000L
        private const val SERVICE_STOP_AWAIT_TIMEOUT_MS = 3_500L
        private const val SERVICE_STOP_POLL_INTERVAL_MS = 40L
    }
}
