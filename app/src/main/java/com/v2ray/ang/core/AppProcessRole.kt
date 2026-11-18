package com.v2ray.ang.core

import android.app.Application
import android.os.Build

enum class AppProcessRole {
    UI,
    DAEMON,
    TASKS,
    BG,
}

data class ProcessInitPlan(
    val role: AppProcessRole,
    val initializeMmkv: Boolean,
    val initializeAppLocale: Boolean,
    val refreshTheme: Boolean,
    val eagerSettingsInit: Boolean,
    val asyncUiSettingsInit: Boolean,
    val wireAetherListenPort: Boolean,
    val eagerWorkManagerInit: Boolean,
)

object AppProcessInitializer {

    fun currentProcessName(packageName: String): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }.getOrNull()
        } else {
            runCatching {
                java.io.File("/proc/self/cmdline")
                    .readText()
                    .trim { it <= ' ' || it == '\u0000' }
                    .ifBlank { null }
            }.getOrNull()
        } ?: packageName
    }

    fun resolveProcessRole(processName: String?, packageName: String): AppProcessRole {
        val normalized = processName?.trim().orEmpty()
        if (normalized.isEmpty() || normalized == packageName) {
            return AppProcessRole.UI
        }
        val suffix = normalized.removePrefix(packageName)
        return when {
            suffix == ":daemon" || normalized.endsWith(":daemon") -> AppProcessRole.DAEMON
            suffix == ":tasks" || normalized.endsWith(":tasks") -> AppProcessRole.TASKS
            suffix == ":bg" || normalized.endsWith(":bg") -> AppProcessRole.BG
            else -> AppProcessRole.UI
        }
    }

    fun planFor(role: AppProcessRole): ProcessInitPlan {
        return when (role) {
            AppProcessRole.UI -> ProcessInitPlan(
                role = AppProcessRole.UI,
                initializeMmkv = true,
                initializeAppLocale = true,
                refreshTheme = true,
                eagerSettingsInit = false,
                asyncUiSettingsInit = true,
                wireAetherListenPort = true,
                eagerWorkManagerInit = false,
            )

            AppProcessRole.DAEMON -> ProcessInitPlan(
                role = AppProcessRole.DAEMON,
                initializeMmkv = true,
                initializeAppLocale = false,
                refreshTheme = false,
                eagerSettingsInit = true,
                asyncUiSettingsInit = false,
                wireAetherListenPort = true,
                eagerWorkManagerInit = false,
            )

            AppProcessRole.TASKS -> ProcessInitPlan(
                role = AppProcessRole.TASKS,
                initializeMmkv = true,
                initializeAppLocale = false,
                refreshTheme = false,
                eagerSettingsInit = true,
                asyncUiSettingsInit = false,
                wireAetherListenPort = true,
                eagerWorkManagerInit = false,
            )

            AppProcessRole.BG -> ProcessInitPlan(
                role = AppProcessRole.BG,
                initializeMmkv = true,
                initializeAppLocale = false,
                refreshTheme = false,
                eagerSettingsInit = false,
                asyncUiSettingsInit = false,
                wireAetherListenPort = false,
                eagerWorkManagerInit = true,
            )
        }
    }
}
