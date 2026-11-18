package com.v2ray.ang.mcqueen

import androidx.work.ExistingPeriodicWorkPolicy
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AppProcessInitializer
import com.v2ray.ang.core.AppProcessRole
import com.v2ray.ang.core.BypassIranRoutingHelper
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class Phase2StartupOptimizationTest {

    private fun resolveBundledAssetsDir(): File {
        val candidates = listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
        )
        return candidates.firstOrNull { File(it, AppConfig.GEOIP_DAT).isFile }
            ?: error("Could not locate app/src/main/assets containing geoip.dat and geosite.dat")
    }

    @Test
    fun `resolveProcessRole classifies UI, daemon, tasks, bg, and fallback process names accurately`() {
        val pkg = "com.aistudio.mcqueenvpn.rmqvpn"

        assertEquals(AppProcessRole.UI, AppProcessInitializer.resolveProcessRole(pkg, pkg))
        assertEquals(AppProcessRole.UI, AppProcessInitializer.resolveProcessRole(null, pkg))
        assertEquals(AppProcessRole.UI, AppProcessInitializer.resolveProcessRole("", pkg))
        assertEquals(AppProcessRole.UI, AppProcessInitializer.resolveProcessRole("$pkg:unknown", pkg))

        assertEquals(AppProcessRole.DAEMON, AppProcessInitializer.resolveProcessRole("$pkg:daemon", pkg))
        assertEquals(AppProcessRole.TASKS, AppProcessInitializer.resolveProcessRole("$pkg:tasks", pkg))
        assertEquals(AppProcessRole.BG, AppProcessInitializer.resolveProcessRole("$pkg:bg", pkg))
    }

    @Test
    fun `process init plans isolate UI work from background processes and remove eager WorkManager or blocking settings from UI`() {
        val uiPlan = AppProcessInitializer.planFor(AppProcessRole.UI)
        assertTrue(uiPlan.initializeMmkv)
        assertTrue(uiPlan.initializeAppLocale)
        assertTrue(uiPlan.refreshTheme)
        assertFalse(uiPlan.eagerSettingsInit)
        assertTrue(uiPlan.asyncUiSettingsInit)
        assertTrue(uiPlan.wireAetherListenPort)
        assertFalse(uiPlan.eagerWorkManagerInit)

        val daemonPlan = AppProcessInitializer.planFor(AppProcessRole.DAEMON)
        assertTrue(daemonPlan.initializeMmkv)
        assertFalse(daemonPlan.initializeAppLocale)
        assertFalse(daemonPlan.refreshTheme)
        assertTrue(daemonPlan.eagerSettingsInit)
        assertFalse(daemonPlan.asyncUiSettingsInit)
        assertTrue(daemonPlan.wireAetherListenPort)
        assertFalse(daemonPlan.eagerWorkManagerInit)

        val tasksPlan = AppProcessInitializer.planFor(AppProcessRole.TASKS)
        assertTrue(tasksPlan.initializeMmkv)
        assertFalse(tasksPlan.initializeAppLocale)
        assertFalse(tasksPlan.refreshTheme)
        assertTrue(tasksPlan.eagerSettingsInit)
        assertFalse(tasksPlan.asyncUiSettingsInit)
        assertTrue(tasksPlan.wireAetherListenPort)
        assertFalse(tasksPlan.eagerWorkManagerInit)

        val bgPlan = AppProcessInitializer.planFor(AppProcessRole.BG)
        assertTrue(bgPlan.initializeMmkv)
        assertFalse(bgPlan.initializeAppLocale)
        assertFalse(bgPlan.refreshTheme)
        assertFalse(bgPlan.eagerSettingsInit)
        assertFalse(bgPlan.asyncUiSettingsInit)
        assertFalse(bgPlan.wireAetherListenPort)
        assertTrue(bgPlan.eagerWorkManagerInit)
    }

    @Test
    fun `manifest cache freshness and startup auto-sync skip redundant HTTP fetch only when usable and fresh`() {
        val ttl = McQueenServerConfig.MANIFEST_CACHE_TTL_MS
        val nowMs = 1_700_000_000_000L

        assertFalse(McQueenServerConfig.isManifestCacheFresh(lastSyncedAtMs = 0L, nowMs = nowMs))
        assertFalse(McQueenServerConfig.isManifestCacheFresh(lastSyncedAtMs = -10L, nowMs = nowMs))
        assertTrue(McQueenServerConfig.isManifestCacheFresh(lastSyncedAtMs = nowMs, nowMs = nowMs))
        assertTrue(McQueenServerConfig.isManifestCacheFresh(lastSyncedAtMs = nowMs - ttl + 1L, nowMs = nowMs))
        assertFalse(McQueenServerConfig.isManifestCacheFresh(lastSyncedAtMs = nowMs - ttl, nowMs = nowMs))
        assertFalse(McQueenServerConfig.isManifestCacheFresh(lastSyncedAtMs = nowMs + 5_000L, nowMs = nowMs))

        // Usable + fresh cache skips startup sync
        assertFalse(
            McQueenServerConfig.shouldAutoSyncManifestOnStartup(
                hasUsableManifest = true,
                isCacheFresh = true,
            )
        )
        // Stale cache still triggers startup sync
        assertTrue(
            McQueenServerConfig.shouldAutoSyncManifestOnStartup(
                hasUsableManifest = true,
                isCacheFresh = false,
            )
        )
        // Missing/corrupt cache always triggers startup sync even if timestamp appears fresh
        assertTrue(
            McQueenServerConfig.shouldAutoSyncManifestOnStartup(
                hasUsableManifest = false,
                isCacheFresh = true,
            )
        )
        assertTrue(
            McQueenServerConfig.shouldAutoSyncManifestOnStartup(
                hasUsableManifest = false,
                isCacheFresh = false,
            )
        )

        // Periodic startup scheduling must retain KEEP while user URL changes use UPDATE
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, McQueenManifestUpdater.periodicWorkPolicy(false))
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, McQueenManifestUpdater.periodicWorkPolicy(true))
    }

    @Test
    fun `selectCompatibilityGuid resolves metadata compatibility without probing and handles missing or custom profiles`() {
        val aetherProfile = ProfileItem.create(EConfigType.AETHER).apply { remarks = "Germany 1" }
        val customProfile = ProfileItem.create(EConfigType.CUSTOM).apply { remarks = "Custom Raw" }
        val wireguardProfile = ProfileItem.create(EConfigType.WIREGUARD).apply { remarks = "Germany WG" }

        // Active tunnel GUID wins immediately
        assertEquals(
            "active-guid",
            BypassIranRoutingHelper.selectCompatibilityGuid(
                activeGuid = "active-guid",
                countryProfiles = listOf("de-1" to aetherProfile),
                fallbackSelectGuid = "fallback-guid",
            )
        )

        // Prefers first non-CUSTOM Xray-routed profile without invoking any probe
        assertEquals(
            "de-wg",
            BypassIranRoutingHelper.selectCompatibilityGuid(
                activeGuid = null,
                countryProfiles = listOf("de-custom" to customProfile, "de-wg" to wireguardProfile),
                fallbackSelectGuid = "fallback-guid",
            )
        )

        // Preserves CUSTOM-only country so UI accurately marks Bypass Iran unsupported
        assertEquals(
            "only-custom",
            BypassIranRoutingHelper.selectCompatibilityGuid(
                activeGuid = "  ",
                countryProfiles = listOf("only-custom" to customProfile),
                fallbackSelectGuid = "fallback-guid",
            )
        )

        // Falls back to selected server GUID when country profile map is empty, or null if none
        assertEquals(
            "fallback-guid",
            BypassIranRoutingHelper.selectCompatibilityGuid(
                activeGuid = null,
                countryProfiles = emptyList(),
                fallbackSelectGuid = "fallback-guid",
            )
        )
        assertNull(
            BypassIranRoutingHelper.selectCompatibilityGuid(
                activeGuid = null,
                countryProfiles = emptyList(),
                fallbackSelectGuid = null,
            )
        )
    }

    @Test
    fun `inspectGeoData caches by directory metadata and invalidates cleanly when geodata files are updated`(@TempDir tempDir: File) {
        // 1. Missing geodata must never falsely report Bypass Iran as Supported
        val missingStatus = BypassIranRoutingHelper.inspectGeoData(tempDir)
        assertFalse(missingStatus.isReady)
        val missingCompat = BypassIranRoutingHelper.evaluateProfileCompatibility(
            profile = ProfileItem.create(EConfigType.AETHER),
            rawCustomJson = null,
            geoStatus = missingStatus,
        )
        assertTrue(missingCompat is BypassIranRoutingHelper.AvailabilityStatus.MissingGeoData)

        // 2. Corrupt stub files still report MissingGeoData
        val geoipTarget = File(tempDir, AppConfig.GEOIP_DAT)
        val geositeTarget = File(tempDir, AppConfig.GEOSITE_DAT)
        geoipTarget.writeText("corrupt-stub")
        geositeTarget.writeText("corrupt-stub")
        val corruptStatus = BypassIranRoutingHelper.inspectGeoData(tempDir)
        assertFalse(corruptStatus.isReady)

        // 3. Copy real bundled geoip.dat & geosite.dat -> fingerprint invalidates cache and reports Supported
        val bundledAssets = resolveBundledAssetsDir()
        File(bundledAssets, AppConfig.GEOIP_DAT).copyTo(geoipTarget, overwrite = true)
        File(bundledAssets, AppConfig.GEOSITE_DAT).copyTo(geositeTarget, overwrite = true)
        // Ensure distinct mtime in case copy happens within same filesystem timestamp granularity
        geoipTarget.setLastModified(System.currentTimeMillis() + 2_000L)
        geositeTarget.setLastModified(System.currentTimeMillis() + 2_000L)

        val readyStatus = BypassIranRoutingHelper.inspectGeoData(tempDir)
        assertTrue(readyStatus.isReady)
        assertTrue(readyStatus.hasGeoipIr)
        assertTrue(readyStatus.hasGeositeCategoryIr)
        assertEquals(readyStatus, BypassIranRoutingHelper.inspectGeoData(tempDir))

        val aetherCompat = BypassIranRoutingHelper.evaluateProfileCompatibility(
            profile = ProfileItem.create(EConfigType.AETHER),
            rawCustomJson = null,
            geoStatus = readyStatus,
        )
        assertTrue(aetherCompat is BypassIranRoutingHelper.AvailabilityStatus.Supported)

        val customCompat = BypassIranRoutingHelper.evaluateProfileCompatibility(
            profile = ProfileItem.create(EConfigType.CUSTOM),
            rawCustomJson = "{}",
            geoStatus = readyStatus,
        )
        assertTrue(customCompat is BypassIranRoutingHelper.AvailabilityStatus.UnsupportedProtocol)
    }
}
