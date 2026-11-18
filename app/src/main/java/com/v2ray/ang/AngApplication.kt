package com.v2ray.ang

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.AppProcessInitializer
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AngApplication : Application(), Configuration.Provider {
    companion object {
        lateinit var application: AngApplication
    }

    private val appInitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setDefaultProcessName("${ANG_PACKAGE}:bg")
            .build()

    /**
     * Initializes the application according to the active process role.
     */
    override fun onCreate() {
        super.onCreate()

        val processName = AppProcessInitializer.currentProcessName(packageName)
        val role = AppProcessInitializer.resolveProcessRole(processName, packageName)
        val plan = AppProcessInitializer.planFor(role)

        if (plan.initializeMmkv) {
            MmkvManager.initialize(this)
        }

        if (plan.initializeAppLocale) {
            AppLocaleManager.initialize(this)
        }

        if (plan.eagerWorkManagerInit && !WorkManager.isInitialized()) {
            WorkManager.initialize(this, workManagerConfiguration)
        }

        if (plan.eagerSettingsInit) {
            SettingsManager.initApp(this)
        } else if (plan.asyncUiSettingsInit) {
            appInitScope.launch {
                SettingsManager.initApp(this@AngApplication)
            }
        }

        if (plan.wireAetherListenPort) {
            // PattNG: the Aether cores of this process listen on the port of the settings.
            AetherCoreManager.listenPortSource = SettingsManager::getAetherListenPort
        }

        if (plan.refreshTheme) {
            // Initialize theme state from MMKV
            ThemeManager.refresh()
        }
    }
}
