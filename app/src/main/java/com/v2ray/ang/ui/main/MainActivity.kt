package com.v2ray.ang.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.BatteryOptimizationHelper
import com.v2ray.ang.ui.base.BaseComponentActivity

class MainActivity : BaseComponentActivity() {
    private val mcQueenViewModel: McQueenViewModel by viewModels()

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                mcQueenViewModel.launchServiceAfterPermission()
            } else {
                mcQueenViewModel.onVpnPermissionDenied()
            }
        }

    private val requestBatteryOptimizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            mcQueenViewModel.refreshBatteryOptimizationStatus()
        }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Foreground VPN notification works when granted; no blocking action required if declined
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        mcQueenViewModel.bindStartVpnCallback {
            startVpnWithPermissionCheck()
        }
        super.onCreate(savedInstanceState)
        requestPostNotificationsIfNeeded()
    }

    @Composable
    override fun ScreenContent() {
        val uiState by mcQueenViewModel.uiState.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(mcQueenViewModel.snackbarEvents) {
            mcQueenViewModel.snackbarEvents.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }

        LaunchedEffect(mcQueenViewModel.openExternalUrlEvents) {
            mcQueenViewModel.openExternalUrlEvents.collect { verifiedUrl ->
                val opened = runCatching {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(verifiedUrl)
                    ).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }.isSuccess
                if (!opened) {
                    snackbarHostState.showSnackbar(getString(com.v2ray.ang.R.string.mcqueen_update_open_failed))
                }
            }
        }

        McQueenScreen(
            uiState = uiState,
            snackbarHostState = snackbarHostState,
            onToggleConnect = { mcQueenViewModel.toggleConnection() },
            onSelectCountry = { country -> mcQueenViewModel.selectCountry(country) },
            onRefreshLocations = { mcQueenViewModel.syncManifestFromGitHub(silentSuccess = false) },
            onUpdateManifestSource = { url, token, clearToken ->
                mcQueenViewModel.updateManifestSourceAndSync(url, token, clearToken)
            },
            onResetManifestSource = { mcQueenViewModel.resetManifestSourceAndSync() },
            onSelectThemeMode = { mode -> mcQueenViewModel.setThemeMode(mode) },
            onSetBypassIranEnabled = { enabled -> mcQueenViewModel.setBypassIranEnabled(enabled) },
            onCheckForUpdate = { mcQueenViewModel.checkForAppUpdate() },
            onConfirmUpdateNow = { mcQueenViewModel.openVerifiedUpdateUrl() },
            onDismissUpdateDialog = { mcQueenViewModel.dismissUpdateDialog() },
            onOpenBatteryOptimizationSettings = {
                mcQueenViewModel.onConfirmBatteryOptimizationDialog()
                launchBatteryOptimizationFlow()
            },
            onDismissBatteryOptimizationDialog = {
                mcQueenViewModel.dismissBatteryOptimizationDialog()
            },
            onLocationsTabOpened = {
                mcQueenViewModel.onLocationsTabOpened()
            },
            onLocationsTabClosed = {
                mcQueenViewModel.onLocationsTabClosed()
            },
        )
    }

    override fun onStart() {
        super.onStart()
        mcQueenViewModel.onHostStarted()
    }

    override fun onResume() {
        super.onResume()
        mcQueenViewModel.onHostResumed()
    }

    override fun onStop() {
        mcQueenViewModel.onHostStopped()
        super.onStop()
    }

    private fun startVpnWithPermissionCheck() {
        if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: AppConfig.VPN) == AppConfig.VPN) {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent == null) {
                mcQueenViewModel.launchServiceAfterPermission()
            } else {
                requestVpnPermission.launch(vpnIntent)
            }
        } else {
            mcQueenViewModel.launchServiceAfterPermission()
        }
    }

    private fun launchBatteryOptimizationFlow() {
        runCatching {
            val intent = BatteryOptimizationHelper.buildBatteryOptimizationIntent(this)
            requestBatteryOptimizationLauncher.launch(intent)
        }.onFailure {
            BatteryOptimizationHelper.openBatteryOptimizationSettings(this)
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
