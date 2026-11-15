package com.v2ray.ang.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.BypassIranRoutingHelper
import com.v2ray.ang.enums.Language
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.mcqueen.AppUpdateCheckResult
import com.v2ray.ang.ui.compose.McQueenChampionshipGold
import com.v2ray.ang.ui.compose.McQueenChampionshipLightGold
import com.v2ray.ang.ui.compose.McQueenConnectedRedDark
import com.v2ray.ang.ui.compose.McQueenConnectedRedLight
import com.v2ray.ang.ui.compose.McQueenCrimsonReadable
import com.v2ray.ang.ui.compose.McQueenRacingRed
import com.v2ray.ang.ui.compose.McQueenTitaniumWhite
import com.v2ray.ang.ui.compose.ThemeManager

@Composable
fun McQueenSettingsContent(
    uiState: McQueenUiState,
    onSelectThemeMode: (String) -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    onRefreshLocations: () -> Unit,
    onSetBypassIranEnabled: (Boolean) -> Unit,
    onCheckForUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = isDarkThemeActive()
    var autoStartOnBoot by MmkvManager.rememberMmkvBool(AppConfig.PREF_IS_BOOTED, false)
    var bypassLanMode by MmkvManager.rememberMmkvString(
        AppConfig.PREF_VPN_BYPASS_LAN,
        AppConfig.DEFAULT_VPN_BYPASS_LAN
    )
    var currentLanguageCode by MmkvManager.rememberMmkvString(
        AppConfig.PREF_LANGUAGE,
        Language.AUTO.code
    )
    var showLicensesDialog by remember { mutableStateOf(false) }

    val normalizedThemeMode = ThemeManager.normalizeMode(uiState.themeMode)
    val connectedColor = if (isDark) McQueenConnectedRedDark else McQueenConnectedRedLight
    val warningColor = if (isDark) McQueenChampionshipGold else McQueenChampionshipLightGold

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Appearance (Light / Dark / System) & Language (System / English / Persian RTL)
        SettingsCard(
            icon = Icons.Rounded.SettingsBrightness,
            title = stringResource(R.string.mcqueen_appearance_title),
            subtitle = stringResource(R.string.mcqueen_theme_subtitle)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("theme_selector_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectorPill(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("theme_option_dark"),
                    icon = Icons.Rounded.DarkMode,
                    label = stringResource(R.string.mcqueen_theme_dark),
                    selected = normalizedThemeMode == ThemeManager.MODE_DARK,
                    onClick = { onSelectThemeMode(ThemeManager.MODE_DARK) }
                )
                SelectorPill(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("theme_option_light"),
                    icon = Icons.Rounded.LightMode,
                    label = stringResource(R.string.mcqueen_theme_light),
                    selected = normalizedThemeMode == ThemeManager.MODE_LIGHT,
                    onClick = { onSelectThemeMode(ThemeManager.MODE_LIGHT) }
                )
                SelectorPill(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("theme_option_system"),
                    icon = Icons.Rounded.SettingsBrightness,
                    label = stringResource(R.string.mcqueen_theme_system),
                    selected = normalizedThemeMode == ThemeManager.MODE_SYSTEM,
                    onClick = { onSelectThemeMode(ThemeManager.MODE_SYSTEM) }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = stringResource(R.string.mcqueen_language_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.mcqueen_language_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val activeLang = Language.fromCode(currentLanguageCode)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("language_selector_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectorPill(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("language_option_system"),
                    icon = null,
                    label = stringResource(R.string.mcqueen_language_system),
                    selected = activeLang == Language.AUTO,
                    onClick = {
                        currentLanguageCode = Language.AUTO.code
                        AppLocaleManager.setApplicationLanguage(Language.AUTO.code)
                    }
                )
                SelectorPill(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("language_option_en"),
                    icon = null,
                    label = stringResource(R.string.mcqueen_language_english),
                    selected = activeLang == Language.ENGLISH,
                    onClick = {
                        currentLanguageCode = Language.ENGLISH.code
                        AppLocaleManager.setApplicationLanguage(Language.ENGLISH.code)
                    }
                )
                SelectorPill(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("language_option_fa"),
                    icon = null,
                    label = stringResource(R.string.mcqueen_language_persian),
                    selected = activeLang == Language.PERSIAN,
                    onClick = {
                        currentLanguageCode = Language.PERSIAN.code
                        AppLocaleManager.setApplicationLanguage(Language.PERSIAN.code)
                    }
                )
            }
        }

        // 2. Tunnel Preferences Card (Bypass Iran, Auto Boot, Bypass LAN)
        SettingsCard(
            icon = Icons.Rounded.Tune,
            title = stringResource(R.string.mcqueen_settings_title),
            subtitle = null
        ) {
            BypassIranSwitchItem(
                checked = uiState.isBypassIranEnabled,
                availability = uiState.bypassIranAvailability,
                connectedColor = connectedColor,
                warningColor = warningColor,
                onCheckedChange = onSetBypassIranEnabled,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            SettingsSwitchItem(
                label = stringResource(R.string.mcqueen_settings_auto_boot),
                checked = autoStartOnBoot,
                onCheckedChange = { autoStartOnBoot = it },
                testTag = "auto_boot_switch"
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            SettingsSwitchItem(
                label = stringResource(R.string.mcqueen_settings_bypass_lan),
                checked = bypassLanMode == "1",
                onCheckedChange = { bypassLanMode = if (it) "1" else "2" },
                testTag = "bypass_lan_switch"
            )
        }

        // 3. Background Connection Protection
        val isExempt = uiState.isBatteryOptimizationExempt
        SettingsCard(
            icon = if (isExempt) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryAlert,
            title = stringResource(R.string.mcqueen_battery_settings_title),
            subtitle = if (isExempt) {
                stringResource(R.string.mcqueen_battery_status_unrestricted_short)
            } else {
                stringResource(R.string.mcqueen_battery_status_restricted_short)
            }
        ) {
            if (!isExempt) {
                OutlinedButton(
                    onClick = onOpenBatteryOptimizationSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .testTag("open_battery_settings_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, McQueenRacingRed.copy(alpha = 0.70f))
                ) {
                    Text(
                        text = stringResource(R.string.mcqueen_battery_unrestrict_action),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onOpenBatteryOptimizationSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .testTag("open_battery_settings_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = stringResource(R.string.mcqueen_battery_status_unrestricted_short),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 4. Locations Directory Refresh
        SettingsCard(
            icon = Icons.Rounded.CloudSync,
            title = stringResource(R.string.mcqueen_network_diagnostics_title),
            subtitle = if (uiState.lastSyncUpdatedAt.isNotBlank()) {
                stringResource(R.string.mcqueen_settings_last_updated, uiState.lastSyncUpdatedAt)
            } else {
                stringResource(R.string.mcqueen_settings_manifest_sync_note)
            }
        ) {
            OutlinedButton(
                onClick = onRefreshLocations,
                enabled = !uiState.isSyncingManifest,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .testTag("settings_sync_locations_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = stringResource(R.string.mcqueen_acc_refresh),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 5. About, Updates & Licenses
        SettingsCard(
            icon = Icons.Rounded.Info,
            title = stringResource(R.string.mcqueen_about_title),
            subtitle = null
        ) {
            InfoKeyValueRow(
                label = stringResource(R.string.mcqueen_version_label),
                value = BuildConfig.VERSION_NAME
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showLicensesDialog = true }
                    .padding(vertical = 8.dp)
                    .testTag("open_licenses_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = null,
                    tint = McQueenCrimsonReadable,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.mcqueen_settings_licenses),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            val isCheckingUpdate = uiState.appUpdateState is AppUpdateCheckResult.Checking
            OutlinedButton(
                onClick = onCheckForUpdate,
                enabled = !isCheckingUpdate,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .testTag("settings_check_for_update_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, McQueenRacingRed.copy(alpha = 0.70f))
            ) {
                if (isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = McQueenCrimsonReadable
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = McQueenCrimsonReadable,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = if (isCheckingUpdate) {
                        stringResource(R.string.mcqueen_update_checking)
                    } else {
                        stringResource(R.string.mcqueen_menu_check_for_update)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (showLicensesDialog) {
        val licenseText = remember {
            runCatching {
                context.assets.open("open_source_licenses.txt").bufferedReader().use { it.readText() }
            }.getOrDefault("Xray-core (MPL-2.0)\nAether-core (AGPL-3.0)\nhev-socks5-tunnel (MIT)")
        }

        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = stringResource(R.string.mcqueen_settings_licenses),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = licenseText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showLicensesDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_close),
                        color = McQueenCrimsonReadable
                    )
                }
            }
        )
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = McQueenCrimsonReadable,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SelectorPill(
    modifier: Modifier = Modifier,
    icon: ImageVector?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(13.dp))
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) {
            McQueenRacingRed.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) {
                McQueenRacingRed
            } else {
                MaterialTheme.colorScheme.outline
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) {
                        McQueenCrimsonReadable
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = McQueenTitaniumWhite,
                checkedTrackColor = McQueenRacingRed,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

@Composable
private fun BypassIranSwitchItem(
    checked: Boolean,
    availability: BypassIranRoutingHelper.AvailabilityStatus?,
    connectedColor: androidx.compose.ui.graphics.Color,
    warningColor: androidx.compose.ui.graphics.Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    val isSupported = availability !is BypassIranRoutingHelper.AvailabilityStatus.MissingGeoData &&
        availability !is BypassIranRoutingHelper.AvailabilityStatus.UnsupportedProtocol

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.mcqueen_settings_bypass_iran),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (checked && isSupported) {
                            connectedColor.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = if (checked) {
                                stringResource(R.string.mcqueen_bypass_iran_state_on)
                            } else {
                                stringResource(R.string.mcqueen_bypass_iran_state_off)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (checked && isSupported) {
                                connectedColor
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.mcqueen_settings_bypass_iran_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = isSupported,
                modifier = Modifier.testTag("bypass_iran_switch"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = McQueenTitaniumWhite,
                    checkedTrackColor = McQueenRacingRed,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
        }

        val statusNote = when (availability) {
            is BypassIranRoutingHelper.AvailabilityStatus.Supported -> {
                stringResource(
                    R.string.mcqueen_bypass_iran_status_ready,
                    availability.engineLabel,
                    availability.geoStatus.irDomainCount,
                    availability.geoStatus.ipv4CidrCount,
                    availability.geoStatus.ipv6CidrCount,
                )
            }
            is BypassIranRoutingHelper.AvailabilityStatus.MissingGeoData -> {
                stringResource(R.string.mcqueen_bypass_iran_status_missing_geo)
            }
            is BypassIranRoutingHelper.AvailabilityStatus.UnsupportedProtocol -> {
                stringResource(R.string.mcqueen_bypass_iran_status_unsupported, availability.protocolName)
            }
            null -> null
        }

        if (!statusNote.isNullOrBlank()) {
            Text(
                text = statusNote,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSupported) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    warningColor
                },
                modifier = Modifier.testTag("bypass_iran_status_note")
            )
        }
    }
}

@Composable
private fun InfoKeyValueRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}
