package com.v2ray.ang.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.HelperBaseComponentActivity

class MainActivity : HelperBaseComponentActivity() {

    private val viewModel: McQueenViewModel by viewModels()

    private val requestVpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.onVpnPermissionGranted {
                LauncherManager.startService(this)
            }
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        viewModel.startListenBroadcast()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopListenBroadcast()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
            }
        }
    }

    private fun prepareVpnIntent() =
        if (SettingsManager.isVpnMode()) VpnService.prepare(this) else null

    @Composable
    override fun ScreenContent() {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        McQueenScreen(
            uiState = uiState,
            onConnectClick = {
                viewModel.onConnectActionClicked(
                    needsVpnPermission = { prepareVpnIntent() != null },
                    requestVpnPermission = {
                        val intent = prepareVpnIntent()
                        if (intent != null) {
                            requestVpnPermission.launch(intent)
                        } else {
                            viewModel.onVpnPermissionGranted {
                                LauncherManager.startService(this)
                            }
                        }
                    },
                    startVpnService = {
                        LauncherManager.startService(this)
                    },
                )
            },
            onSelectCountry = viewModel::selectCountry,
            onRefreshCountries = { viewModel.syncCountries(showToast = true) },
            onSetThemeMode = viewModel::setThemeMode,
            onSetAutoBoot = viewModel::setAutoBootEnabled,
            onSetBypassLan = viewModel::setBypassLanEnabled,
            onSaveManifestUrl = viewModel::saveManifestUrlAndSync,
            onResetManifestUrl = viewModel::resetManifestUrlAndSync,
        )
    }
}
