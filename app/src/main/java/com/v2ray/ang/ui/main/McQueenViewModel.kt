package com.v2ray.ang.ui.main

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.TrafficStatsSnapshot
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.mcqueen.ConnectionSessionCoordinator
import com.v2ray.ang.mcqueen.CountryUiModel
import com.v2ray.ang.mcqueen.FailoverDecision
import com.v2ray.ang.mcqueen.ManifestSyncOutcome
import com.v2ray.ang.mcqueen.McQueenManifestUpdater
import com.v2ray.ang.mcqueen.McQueenServerConfig
import com.v2ray.ang.mcqueen.McQueenServerRepository
import com.v2ray.ang.mcqueen.VpnConnectionState
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class McQueenUiState(
    val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val statusDetail: String? = null,
    val countries: List<CountryUiModel> = emptyList(),
    val selectedCountry: CountryUiModel? = null,
    val downloadBps: Long = 0L,
    val uploadBps: Long = 0L,
    val sessionStartMs: Long = 0L,
    val sessionDurationText: String = "00:00:00",
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    val manifestUpdatedAt: String? = null,
    val manifestUrl: String = "",
    val autoBootEnabled: Boolean = false,
    val bypassLanEnabled: Boolean = true,
)

class McQueenViewModel(application: Application) : BaseViewModel(application) {

    private val repository = McQueenServerRepository()
    private val coordinator = ConnectionSessionCoordinator()

    private val _uiState = MutableStateFlow(
        McQueenUiState(
            manifestUrl = repository.getConfiguredUrl(),
            autoBootEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_BOOTED, false),
            bypassLanEnabled = SettingsManager.routingRulesetsBypassLan(),
        )
    )
    val uiState: StateFlow<McQueenUiState> = _uiState.asStateFlow()

    private var startVpnCallback: (() -> Unit)? = null
    private var durationTickerJob: Job? = null
    private var failoverJob: Job? = null
    private var latencyJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var verifyTunnelJob: Job? = null
    private var pendingVerifyRequestId: String? = null
    private var userRequestedDisconnect: Boolean = false
    private var receiverRegistered = false

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != AppConfig.BROADCAST_ACTION_ACTIVITY) return

            when (intent.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> handleServiceRunning()
                AppConfig.MSG_STATE_NOT_RUNNING -> handleServiceStopped(preserveFailure = true)
                AppConfig.MSG_STATE_START_SUCCESS -> handleStartSuccess()
                AppConfig.MSG_STATE_CONNECTING -> handleConnecting(intent.getStringExtra("content"))
                AppConfig.MSG_STATE_START_FAILURE -> handleStartFailure(intent.getStringExtra("content"))
                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    val shouldPreserveFailure = !userRequestedDisconnect
                    userRequestedDisconnect = false
                    handleServiceStopped(preserveFailure = shouldPreserveFailure)
                }
                AppConfig.MSG_MEASURE_DELAY_RESULT -> {
                    val reqId = intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID)
                    val result = intent.serializable<ConnectionTestResult>("content")
                    handleMeasureDelayResult(reqId, result)
                }
                AppConfig.MSG_MEASURE_DELAY_CANCEL -> {
                    val reqId = intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID)
                    handleMeasureDelayCancelled(reqId)
                }
                AppConfig.MSG_TRAFFIC_STATS -> {
                    val snapshot = intent.serializable<TrafficStatsSnapshot>("content") ?: return
                    handleTrafficStats(snapshot)
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            val cached = repository.loadCachedManifest(app)
            if (cached != null) {
                applyCountries(cached.countries, cached.updatedAt)
                refreshCountryLatencies()
            }
            syncCountries(showToast = false)
        }
    }

    fun startListenBroadcast() {
        if (!receiverRegistered) {
            val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
            ContextCompat.registerReceiver(app, broadcastReceiver, filter, Utils.receiverFlags())
            receiverRegistered = true
        }
        MessageHelper.sendMsg2ServiceForResult(app, AppConfig.MSG_REGISTER_CLIENT, "") { handled ->
            if (!handled && coordinator.currentState() == VpnConnectionState.CONNECTED) {
                handleServiceStopped(preserveFailure = true)
            }
        }
    }

    fun stopListenBroadcast() {
        if (receiverRegistered) {
            try {
                app.unregisterReceiver(broadcastReceiver)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "McQueenViewModel: failed to unregister receiver", e)
            }
            receiverRegistered = false
        }
    }

    fun syncCountries(showToast: Boolean = true) {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncError = null) }
            when (val outcome = repository.syncFromGitHub(app)) {
                is ManifestSyncOutcome.Success -> {
                    applyCountries(outcome.manifest.countries, outcome.manifest.updatedAt)
                    _uiState.update { it.copy(isSyncing = false, syncError = null) }
                    if (showToast) {
                        toastSuccess(getString(R.string.mcqueen_sync_success, outcome.manifest.countries.size))
                    }
                    refreshCountryLatencies()
                }

                is ManifestSyncOutcome.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            syncError = outcome.reason,
                        )
                    }
                    if (showToast) {
                        toastError(getString(R.string.mcqueen_sync_error, outcome.reason))
                    }
                }
            }
        }
    }

    fun refreshCountryLatencies() {
        val currentCountries = _uiState.value.countries
        if (currentCountries.isEmpty()) return
        latencyJob?.cancel()
        latencyJob = viewModelScope.launch {
            val measured = repository.measureCountryLatencies(app, currentCountries)
            _uiState.update { state ->
                val selectedCode = state.selectedCountry?.countryCode
                val updatedSelected = selectedCode?.let { code ->
                    measured.firstOrNull { it.countryCode == code }
                } ?: state.selectedCountry
                state.copy(
                    countries = measured,
                    selectedCountry = updatedSelected,
                )
            }
        }
    }

    fun selectCountry(country: CountryUiModel) {
        persistSelectedCountry(country)
        _uiState.update { state ->
            val resetFailed = state.connectionState == VpnConnectionState.FAILED
            val nextConnectionState = if (resetFailed) coordinator.onUserDisconnect() else state.connectionState
            state.copy(
                selectedCountry = country,
                connectionState = nextConnectionState,
                statusDetail = if (resetFailed) null else state.statusDetail,
            )
        }
    }

    fun onConnectActionClicked(
        needsVpnPermission: () -> Boolean,
        requestVpnPermission: () -> Unit,
        startVpnService: () -> Unit,
    ) {
        startVpnCallback = startVpnService
        when (_uiState.value.connectionState) {
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.RECONNECTING -> {
                disconnect()
            }

            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.FAILED -> {
                val targetCountry = ensureSelectedCountry() ?: run {
                    toast(R.string.mcqueen_select_country_first)
                    return
                }
                if (needsVpnPermission()) {
                    requestVpnPermission()
                } else {
                    connectCountry(targetCountry, startVpnService)
                }
            }
        }
    }

    fun onVpnPermissionGranted(startVpnService: () -> Unit) {
        startVpnCallback = startVpnService
        val targetCountry = ensureSelectedCountry() ?: return
        connectCountry(targetCountry, startVpnService)
    }

    fun onVpnPermissionDenied() {
        cancelAttemptGuards()
        coordinator.onUserDisconnect()
        _uiState.update {
            it.copy(
                connectionState = VpnConnectionState.DISCONNECTED,
                statusDetail = getString(R.string.mcqueen_vpn_permission_denied),
            )
        }
        toastError(R.string.mcqueen_vpn_permission_denied)
    }

    fun disconnect() {
        userRequestedDisconnect = true
        cancelAttemptGuards()
        stopDurationTicker()
        coordinator.onUserDisconnect()
        LauncherManager.stopService(app)
        _uiState.update {
            it.copy(
                connectionState = VpnConnectionState.DISCONNECTED,
                statusDetail = null,
                downloadBps = 0L,
                uploadBps = 0L,
                sessionStartMs = 0L,
                sessionDurationText = "00:00:00",
            )
        }
    }

    fun setThemeMode(mode: String) {
        ThemeManager.setThemeMode(mode)
    }

    fun setAutoBootEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_IS_BOOTED, enabled)
        _uiState.update { it.copy(autoBootEnabled = enabled) }
    }

    fun setBypassLanEnabled(enabled: Boolean) {
        viewModelScope.launch {
            MmkvManager.encodeSettings(AppConfig.PREF_VPN_BYPASS_LAN, if (enabled) "1" else "2")
            _uiState.update { it.copy(bypassLanEnabled = SettingsManager.routingRulesetsBypassLan()) }
            if (_uiState.value.connectionState == VpnConnectionState.CONNECTED) {
                LauncherManager.restartService(app)
            }
        }
    }

    fun saveManifestUrlAndSync(rawUrl: String): Boolean {
        if (!repository.saveCustomUrl(rawUrl)) {
            toastError(R.string.mcqueen_settings_manifest_invalid)
            return false
        }
        _uiState.update { it.copy(manifestUrl = repository.getConfiguredUrl()) }
        McQueenManifestUpdater.sync(app, forceReschedule = true)
        syncCountries(showToast = true)
        return true
    }

    fun resetManifestUrlAndSync() {
        repository.resetCustomUrl()
        _uiState.update { it.copy(manifestUrl = repository.getConfiguredUrl()) }
        McQueenManifestUpdater.sync(app, forceReschedule = true)
        syncCountries(showToast = true)
    }

    private fun connectCountry(country: CountryUiModel, startVpnService: () -> Unit) {
        userRequestedDisconnect = false
        cancelAttemptGuards()
        persistSelectedCountry(country)

        _uiState.update {
            it.copy(
                connectionState = VpnConnectionState.CONNECTING,
                statusDetail = getString(R.string.mcqueen_testing_country, country.countryName),
                downloadBps = 0L,
                uploadBps = 0L,
            )
        }

        failoverJob = viewModelScope.launch {
            val ranked = repository.selectCandidatesForCountry(app, country.countryCode)
            val firstGuid = coordinator.startAttempt(ranked.map { it.guid })
            if (firstGuid == null) {
                _uiState.update {
                    it.copy(
                        connectionState = VpnConnectionState.FAILED,
                        statusDetail = getString(R.string.mcqueen_all_candidates_failed, country.countryName),
                    )
                }
                return@launch
            }
            MmkvManager.setSelectServer(firstGuid)
            startAttemptTimeout(firstGuid)
            startVpnService()
        }
    }

    private fun startAttemptTimeout(guid: String) {
        connectionTimeoutJob?.cancel()
        val isAether = MmkvManager.decodeServerConfig(guid)?.configType == EConfigType.AETHER
        val timeoutMs = if (isAether) {
            McQueenServerConfig.AETHER_CONNECT_ATTEMPT_TIMEOUT_MS
        } else {
            McQueenServerConfig.CONNECT_ATTEMPT_TIMEOUT_MS
        }
        connectionTimeoutJob = viewModelScope.launch {
            delay(timeoutMs)
            val currentState = coordinator.currentState()
            if ((currentState == VpnConnectionState.CONNECTING || currentState == VpnConnectionState.RECONNECTING) &&
                coordinator.currentGuid() == guid
            ) {
                LogUtil.w(AppConfig.TAG, "McQueenViewModel: attempt timeout after ${timeoutMs}ms for guid=$guid")
                LauncherManager.stopService(app)
                handleStartFailure(getString(R.string.mcqueen_connection_timeout))
            }
        }
    }

    private fun handleServiceRunning() {
        connectionTimeoutJob?.cancel()
        val nextState = coordinator.onServiceRunningQuery()
        _uiState.update {
            it.copy(
                connectionState = nextState,
                statusDetail = if (nextState == VpnConnectionState.CONNECTED) null else it.statusDetail,
            )
        }
    }

    private fun handleConnecting(detail: String?) {
        val nextState = coordinator.onConnectingBroadcast()
        _uiState.update {
            it.copy(
                connectionState = nextState,
                statusDetail = detail?.takeIf { text -> text.isNotBlank() } ?: it.statusDetail,
            )
        }
    }

    private fun handleStartSuccess() {
        failoverJob?.cancel()
        connectionTimeoutJob?.cancel()
        if (userRequestedDisconnect) return

        // Verify end-to-end encrypted traffic over the newly started core before promoting UI to CONNECTED.
        val requestId = UUID.randomUUID().toString()
        pendingVerifyRequestId = requestId
        _uiState.update {
            it.copy(
                statusDetail = getString(R.string.mcqueen_verifying_tunnel),
            )
        }

        verifyTunnelJob?.cancel()
        verifyTunnelJob = viewModelScope.launch {
            delay(McQueenServerConfig.TUNNEL_VERIFY_TIMEOUT_MS)
            if (pendingVerifyRequestId == requestId) {
                pendingVerifyRequestId = null
                LogUtil.w(AppConfig.TAG, "McQueenViewModel: post-connect tunnel verification timed out")
                LauncherManager.stopService(app)
                handleStartFailure(getString(R.string.mcqueen_tunnel_check_failed))
            }
        }

        MessageHelper.sendMsg2ServiceForResult(app, AppConfig.MSG_MEASURE_DELAY, requestId) { handled ->
            if (!handled && pendingVerifyRequestId == requestId) {
                pendingVerifyRequestId = null
                verifyTunnelJob?.cancel()
                handleStartFailure(getString(R.string.mcqueen_tunnel_check_failed))
            }
        }
    }

    private fun handleMeasureDelayResult(requestId: String?, result: ConnectionTestResult?) {
        val activeRequestId = pendingVerifyRequestId ?: return
        if (requestId != activeRequestId) return

        pendingVerifyRequestId = null
        verifyTunnelJob?.cancel()

        if (result != null && result.delayMillis >= 0L) {
            promoteToConnected()
        } else {
            val reason = result?.errorMessage?.takeIf { it.isNotBlank() }
                ?: getString(R.string.mcqueen_tunnel_check_failed)
            LogUtil.w(AppConfig.TAG, "McQueenViewModel: post-connect tunnel verification failed: $reason")
            LauncherManager.stopService(app)
            handleStartFailure(reason)
        }
    }

    private fun handleMeasureDelayCancelled(requestId: String?) {
        val activeRequestId = pendingVerifyRequestId ?: return
        if (requestId != activeRequestId) return

        pendingVerifyRequestId = null
        verifyTunnelJob?.cancel()
        if (!userRequestedDisconnect) {
            LauncherManager.stopService(app)
            handleStartFailure(getString(R.string.mcqueen_tunnel_check_failed))
        }
    }

    private fun promoteToConnected() {
        val nextState = coordinator.onStartSuccess()
        val now = System.currentTimeMillis()
        val effectiveStart = _uiState.value.sessionStartMs.takeIf { it > 0L } ?: now
        startDurationTicker(effectiveStart)
        _uiState.update {
            it.copy(
                connectionState = nextState,
                statusDetail = null,
                sessionStartMs = effectiveStart,
            )
        }
    }

    private fun handleStartFailure(reason: String?) {
        if (userRequestedDisconnect) return
        connectionTimeoutJob?.cancel()
        verifyTunnelJob?.cancel()
        pendingVerifyRequestId = null
        stopDurationTicker()

        when (val decision = coordinator.onStartFailure(reason)) {
            is FailoverDecision.RetryNext -> {
                val countryName = _uiState.value.selectedCountry?.countryName.orEmpty()
                _uiState.update {
                    it.copy(
                        connectionState = VpnConnectionState.RECONNECTING,
                        statusDetail = getString(R.string.mcqueen_failover_notice, countryName),
                        downloadBps = 0L,
                        uploadBps = 0L,
                        sessionStartMs = 0L,
                        sessionDurationText = "00:00:00",
                    )
                }
                failoverJob?.cancel()
                failoverJob = viewModelScope.launch {
                    delay(450L)
                    MmkvManager.setSelectServer(decision.guid)
                    startAttemptTimeout(decision.guid)
                    val callback = startVpnCallback
                    if (callback != null) {
                        callback()
                    } else {
                        LauncherManager.startService(app, decision.guid)
                    }
                }
            }

            is FailoverDecision.Exhausted -> {
                val countryName = _uiState.value.selectedCountry?.countryName
                val failureMessage = decision.reason
                    ?: countryName?.let { getString(R.string.mcqueen_all_candidates_failed, it) }
                    ?: getString(R.string.mcqueen_status_hint_failed)
                _uiState.update {
                    it.copy(
                        connectionState = VpnConnectionState.FAILED,
                        statusDetail = failureMessage,
                        downloadBps = 0L,
                        uploadBps = 0L,
                        sessionStartMs = 0L,
                        sessionDurationText = "00:00:00",
                    )
                }
            }
        }
    }

    private fun handleServiceStopped(preserveFailure: Boolean) {
        val nextState = coordinator.onServiceStopped(preserveFailure = preserveFailure)
        if (nextState == VpnConnectionState.RECONNECTING) {
            return
        }
        connectionTimeoutJob?.cancel()
        verifyTunnelJob?.cancel()
        pendingVerifyRequestId = null
        stopDurationTicker()
        _uiState.update {
            it.copy(
                connectionState = nextState,
                statusDetail = if (nextState == VpnConnectionState.FAILED) it.statusDetail else null,
                downloadBps = 0L,
                uploadBps = 0L,
                sessionStartMs = 0L,
                sessionDurationText = "00:00:00",
            )
        }
    }

    private fun handleTrafficStats(snapshot: TrafficStatsSnapshot) {
        if (snapshot.sessionStartMs > 0L) {
            startDurationTicker(snapshot.sessionStartMs)
        }
        _uiState.update { state ->
            val effectiveStart = if (snapshot.sessionStartMs > 0L) snapshot.sessionStartMs else state.sessionStartMs
            state.copy(
                downloadBps = snapshot.downlinkBps.coerceAtLeast(0L),
                uploadBps = snapshot.uplinkBps.coerceAtLeast(0L),
                sessionStartMs = effectiveStart,
                sessionDurationText = if (effectiveStart > 0L) {
                    ConnectionSessionCoordinator.formatDuration(System.currentTimeMillis() - effectiveStart)
                } else {
                    "00:00:00"
                },
            )
        }
    }

    private fun startDurationTicker(sessionStartMs: Long) {
        if (sessionStartMs <= 0L) return
        if (durationTickerJob?.isActive == true && _uiState.value.sessionStartMs == sessionStartMs) return
        durationTickerJob?.cancel()
        durationTickerJob = viewModelScope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - sessionStartMs
                _uiState.update {
                    it.copy(sessionDurationText = ConnectionSessionCoordinator.formatDuration(elapsed))
                }
                delay(1000L)
            }
        }
    }

    private fun stopDurationTicker() {
        durationTickerJob?.cancel()
        durationTickerJob = null
    }

    private fun cancelAttemptGuards() {
        failoverJob?.cancel()
        connectionTimeoutJob?.cancel()
        verifyTunnelJob?.cancel()
        pendingVerifyRequestId = null
    }

    private fun applyCountries(countries: List<CountryUiModel>, updatedAt: String?) {
        val savedCode = MmkvManager.decodeSettingsString(KEY_SELECTED_COUNTRY_CODE).orEmpty()
        val savedName = MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY).orEmpty()
        val resolvedSelected = countries.firstOrNull { it.countryCode == savedCode }
            ?: countries.firstOrNull { it.countryName.equals(savedName, ignoreCase = true) }
            ?: countries.firstOrNull()

        if (resolvedSelected != null) {
            persistSelectedCountry(resolvedSelected)
        }

        _uiState.update {
            it.copy(
                countries = countries,
                selectedCountry = resolvedSelected,
                manifestUpdatedAt = updatedAt,
            )
        }
    }

    private fun ensureSelectedCountry(): CountryUiModel? {
        val current = _uiState.value.selectedCountry
        if (current != null) return current
        val fallback = _uiState.value.countries.firstOrNull() ?: return null
        persistSelectedCountry(fallback)
        _uiState.update { it.copy(selectedCountry = fallback) }
        return fallback
    }

    private fun persistSelectedCountry(country: CountryUiModel) {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY, country.countryName)
        MmkvManager.encodeSettings(KEY_SELECTED_COUNTRY_CODE, country.countryCode)
    }

    override fun onCleared() {
        super.onCleared()
        stopDurationTicker()
        cancelAttemptGuards()
        latencyJob?.cancel()
        stopListenBroadcast()
    }

    companion object {
        private const val KEY_SELECTED_COUNTRY_CODE = "pref_mcqueen_selected_country_code"
    }
}
