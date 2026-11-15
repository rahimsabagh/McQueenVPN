package com.v2ray.ang.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/**
 * Helper for checking Android battery optimization exemption status, managing the
 * one-time first-launch explanation prompt, and launching the official system settings flow.
 */
object BatteryOptimizationHelper {

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.M)
    fun isBatteryOptimizationSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    /**
     * Checks whether the application is currently exempt from Doze / App Standby battery
     * optimizations according to Android's [PowerManager].
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (!isBatteryOptimizationSupported()) {
            return true
        }
        return runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        }.onFailure { e ->
            LogUtil.w(AppConfig.TAG, "Failed to query battery optimization exemption status", e)
        }.getOrDefault(false)
    }

    /**
     * Pure decision function for whether the first-launch battery explanation dialog should be shown.
     *
     * - If the app is already exempt, never show the dialog.
     * - If the prompt has already been shown once on a prior launch, do not repeatedly nag the user.
     */
    fun shouldShowFirstLaunchPrompt(isExempt: Boolean, promptAlreadyShown: Boolean): Boolean {
        return !isExempt && !promptAlreadyShown
    }

    fun hasShownInitialPrompt(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_MCQUEEN_BATTERY_PROMPT_SHOWN, false)
    }

    fun markInitialPromptShown() {
        MmkvManager.encodeSettings(AppConfig.PREF_MCQUEEN_BATTERY_PROMPT_SHOWN, true)
    }

    /**
     * Builds the safest available system [Intent] for the user to grant unrestricted battery usage.
     *
     * Note: Play Store policy restricts [Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS].
     * When that permission is granted on the distribution, uses [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS];
     * otherwise falls back to [Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS] and
     * [Settings.ACTION_APPLICATION_DETAILS_SETTINGS].
     */
    @SuppressLint("BatteryLife")
    fun buildBatteryOptimizationIntent(context: Context): Intent {
        val packageManager = context.packageManager
        val packageUri = Uri.parse("package:${context.packageName}")

        if (isBatteryOptimizationSupported()) {
            val hasDirectRequestPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasDirectRequestPermission) {
                val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = packageUri
                }
                if (directIntent.resolveActivity(packageManager) != null) {
                    return directIntent
                }
            }

            val listSettingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (listSettingsIntent.resolveActivity(packageManager) != null) {
                return listSettingsIntent
            }
        }

        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = packageUri
        }
    }

    /**
     * Launches the Android battery optimization settings screen safely.
     * Returns true if an activity was launched.
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        return runCatching {
            val intent = buildBatteryOptimizationIntent(context).apply {
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            true
        }.onFailure { e ->
            LogUtil.w(AppConfig.TAG, "Failed to open battery optimization settings, trying app details fallback", e)
            runCatching {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    if (context !is android.app.Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(fallback)
                return true
            }
        }.getOrDefault(false)
    }
}
