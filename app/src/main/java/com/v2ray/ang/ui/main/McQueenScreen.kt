package com.v2ray.ang.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.mcqueen.AppUpdateCheckResult
import com.v2ray.ang.mcqueen.CountryUiModel
import com.v2ray.ang.mcqueen.VpnConnectionState
import com.v2ray.ang.ui.compose.AppSnackbarHost
import com.v2ray.ang.ui.compose.McQueenButtonSurfaceDark
import com.v2ray.ang.ui.compose.McQueenCardConnectedDark
import com.v2ray.ang.ui.compose.McQueenCardConnectingDark
import com.v2ray.ang.ui.compose.McQueenCardSurfaceDark
import com.v2ray.ang.ui.compose.McQueenChampionshipGold
import com.v2ray.ang.ui.compose.McQueenChampionshipLightGold
import com.v2ray.ang.ui.compose.McQueenConnectedRedDark
import com.v2ray.ang.ui.compose.McQueenConnectedRedLight
import com.v2ray.ang.ui.compose.McQueenCrimsonReadable
import com.v2ray.ang.ui.compose.McQueenHairlineBorder
import com.v2ray.ang.ui.compose.McQueenMockupAmber
import com.v2ray.ang.ui.compose.McQueenMockupRed
import com.v2ray.ang.ui.compose.McQueenRacingRed
import com.v2ray.ang.ui.compose.McQueenTitaniumWhite
import kotlin.math.max

enum class McQueenNavTab {
    HOME,
    LOCATIONS,
    SETTINGS
}

@Composable
fun McQueenScreen(
    uiState: McQueenUiState,
    snackbarHostState: SnackbarHostState,
    onToggleConnect: () -> Unit,
    onSelectCountry: (CountryUiModel) -> Unit,
    onRefreshLocations: () -> Unit,
    onUpdateManifestSource: (String, String?, Boolean) -> Unit,
    onResetManifestSource: () -> Unit,
    onSelectThemeMode: (String) -> Unit,
    onSetBypassIranEnabled: (Boolean) -> Unit,
    onCheckForUpdate: () -> Unit,
    onConfirmUpdateNow: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    onDismissBatteryOptimizationDialog: () -> Unit
) {
    var currentTab by rememberSaveable { mutableStateOf(McQueenNavTab.HOME) }

    if (currentTab != McQueenNavTab.HOME) {
        BackHandler {
            currentTab = McQueenNavTab.HOME
        }
    }

    val availableUpdate = uiState.appUpdateState as? AppUpdateCheckResult.UpdateAvailable
    if (availableUpdate != null) {
        McQueenUpdateAvailableDialog(
            update = availableUpdate,
            onUpdateNow = onConfirmUpdateNow,
            onCancel = onDismissUpdateDialog
        )
    }

    val isDark = isDarkThemeActive()
    val ambientGlowTarget = when (uiState.connectionState) {
        VpnConnectionState.CONNECTED -> McQueenMockupRed.copy(alpha = if (isDark) 0.18f else 0.08f)
        VpnConnectionState.PREPARING,
        VpnConnectionState.CONNECTING,
        VpnConnectionState.RECONNECTING -> McQueenMockupAmber.copy(alpha = if (isDark) 0.13f else 0.08f)
        VpnConnectionState.DISCONNECTED,
        VpnConnectionState.FAILED -> Color.White.copy(alpha = if (isDark) 0.05f else 0.0f)
    }
    val ambientGlowColor by animateColorAsState(
        targetValue = ambientGlowTarget,
        animationSpec = tween(durationMillis = 360),
        label = "home_ambient_glow"
    )
    val canvasBackground = MaterialTheme.colorScheme.background

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = canvasBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { AppSnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(color = canvasBackground)
                    if (ambientGlowColor.alpha > 0f && size.width > 0f && size.height > 0f) {
                        val center = Offset(size.width * 0.5f, size.height * 0.36f)
                        val radius = max(size.width * 0.85f, size.height * 0.45f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(ambientGlowColor, Color.Transparent),
                                center = center,
                                radius = radius
                            ),
                            radius = radius,
                            center = center
                        )
                    }
                }
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            val handlePrimaryConnectTap: () -> Unit = {
                val state = uiState.connectionState
                val isIdleOrFailed = state == VpnConnectionState.DISCONNECTED ||
                    state == VpnConnectionState.FAILED
                if (uiState.selectedCountry == null && uiState.countries.isEmpty() && isIdleOrFailed) {
                    currentTab = McQueenNavTab.LOCATIONS
                    onToggleConnect()
                } else {
                    onToggleConnect()
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                when (currentTab) {
                    McQueenNavTab.HOME -> {
                        McQueenTopBar(
                            connectionState = uiState.connectionState,
                            isSyncing = uiState.isSyncingManifest,
                            isCheckingUpdate = uiState.appUpdateState is AppUpdateCheckResult.Checking,
                            onRefresh = onRefreshLocations,
                            onOpenSettings = { currentTab = McQueenNavTab.SETTINGS },
                            onCheckForUpdate = onCheckForUpdate
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        UnifiedHomeLayout(
                            uiState = uiState,
                            onToggleConnect = handlePrimaryConnectTap,
                            onOpenLocations = { currentTab = McQueenNavTab.LOCATIONS },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    McQueenNavTab.LOCATIONS -> {
                        McQueenSubScreenTopBar(
                            title = stringResource(R.string.mcqueen_section_countries),
                            onBack = { currentTab = McQueenNavTab.HOME },
                            isSyncing = uiState.isSyncingManifest,
                            onRefresh = onRefreshLocations
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        LocationsDirectoryView(
                            countries = uiState.countries,
                            selectedCountry = uiState.selectedCountry,
                            connectionState = uiState.connectionState,
                            isSyncing = uiState.isSyncingManifest,
                            manifestErrorMessage = uiState.manifestErrorMessage,
                            onSelectCountry = { country ->
                                onSelectCountry(country)
                                currentTab = McQueenNavTab.HOME
                            },
                            onRefresh = onRefreshLocations,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    McQueenNavTab.SETTINGS -> {
                        McQueenSubScreenTopBar(
                            title = stringResource(R.string.mcqueen_nav_settings),
                            onBack = { currentTab = McQueenNavTab.HOME }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        McQueenSettingsContent(
                            uiState = uiState,
                            onSelectThemeMode = onSelectThemeMode,
                            onOpenBatteryOptimizationSettings = onOpenBatteryOptimizationSettings,
                            onRefreshLocations = onRefreshLocations,
                            onSetBypassIranEnabled = onSetBypassIranEnabled,
                            onCheckForUpdate = onCheckForUpdate,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Clean, calm Home layout centered around the circular Gray / Yellow / Red Power Orb,
 * Location card, and Download/Upload strip — effortless from age 6 to 60.
 */
@Composable
private fun UnifiedHomeLayout(
    uiState: McQueenUiState,
    onToggleConnect: () -> Unit,
    onOpenLocations: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useBalancedFullHeight = maxHeight >= 540.dp
        val isCompactHero = maxHeight < 680.dp
        val dialRingSize = if (isCompactHero) 224.dp else 264.dp
        val dialButtonSize = if (isCompactHero) 160.dp else 188.dp

        if (useBalancedFullHeight) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                McQueenConnectControl(
                    connectionState = uiState.connectionState,
                    statusDetail = uiState.statusDetail,
                    selectedCountry = uiState.selectedCountry,
                    sessionDurationSeconds = uiState.sessionDurationSeconds,
                    isSyncingManifest = uiState.isSyncingManifest,
                    hasAnyCountries = uiState.countries.isNotEmpty(),
                    onToggleConnect = onToggleConnect,
                    ringSize = dialRingSize,
                    buttonSize = dialButtonSize,
                    modifier = Modifier.weight(1f)
                )

                McQueenLocationSelectorRow(
                    selectedCountry = uiState.selectedCountry,
                    connectionState = uiState.connectionState,
                    isSyncingManifest = uiState.isSyncingManifest,
                    onOpenLocations = onOpenLocations
                )

                McQueenTrafficTelemetrySection(
                    connectionState = uiState.connectionState,
                    downloadBps = uiState.downloadBps,
                    uploadBps = uiState.uploadBps,
                    sessionDownloadedBytes = uiState.sessionDownloadedBytes,
                    sessionUploadedBytes = uiState.sessionUploadedBytes
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                McQueenConnectControl(
                    connectionState = uiState.connectionState,
                    statusDetail = uiState.statusDetail,
                    selectedCountry = uiState.selectedCountry,
                    sessionDurationSeconds = uiState.sessionDurationSeconds,
                    isSyncingManifest = uiState.isSyncingManifest,
                    hasAnyCountries = uiState.countries.isNotEmpty(),
                    onToggleConnect = onToggleConnect,
                    ringSize = 216.dp,
                    buttonSize = 156.dp,
                )

                McQueenLocationSelectorRow(
                    selectedCountry = uiState.selectedCountry,
                    connectionState = uiState.connectionState,
                    isSyncingManifest = uiState.isSyncingManifest,
                    onOpenLocations = onOpenLocations
                )

                McQueenTrafficTelemetrySection(
                    connectionState = uiState.connectionState,
                    downloadBps = uiState.downloadBps,
                    uploadBps = uiState.uploadBps,
                    sessionDownloadedBytes = uiState.sessionDownloadedBytes,
                    sessionUploadedBytes = uiState.sessionUploadedBytes
                )
            }
        }
    }
}

/**
 * Redesigned Locations Directory:
 * - Zero auto-select / fastest card
 * - Instant search bar whenever locations exist
 * - Explicit Loading, Error (with Retry), Empty, and Search-Empty states
 * - Effortless 72dp+ country rows with clear flags, availability/latency, and selection state
 */
@Composable
private fun LocationsDirectoryView(
    countries: List<CountryUiModel>,
    selectedCountry: CountryUiModel?,
    connectionState: VpnConnectionState,
    isSyncing: Boolean,
    manifestErrorMessage: String?,
    onSelectCountry: (CountryUiModel) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredCountries = remember(countries, searchQuery) {
        McQueenUiHelper.filterCountries(countries, searchQuery)
    }

    Column(modifier = modifier) {
        LocationsSectionHeader(
            count = countries.size,
            connectionState = connectionState
        )

        if (countries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 54.dp)
                    .testTag("locations_search_input"),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.mcqueen_search_countries_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("locations_search_clear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.mcqueen_search_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = McQueenRacingRed,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        when {
            countries.isEmpty() && isSyncing -> {
                LocationsLoadingCard()
            }

            countries.isEmpty() && !manifestErrorMessage.isNullOrBlank() -> {
                LocationsErrorCard(
                    errorMessage = manifestErrorMessage,
                    onRetry = onRefresh
                )
            }

            countries.isEmpty() -> {
                EmptyLocationsCard(onRefresh = onRefresh)
            }

            filteredCountries.isEmpty() -> {
                LocationsSearchEmptyCard(
                    query = searchQuery,
                    onClearSearch = { searchQuery = "" }
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(
                        items = filteredCountries,
                        key = { it.countryCode }
                    ) { country ->
                        val isSelected = selectedCountry?.countryCode == country.countryCode
                        CountryLocationRow(
                            country = country,
                            isSelected = isSelected,
                            connectionState = connectionState,
                            onClick = { onSelectCountry(country) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationsSectionHeader(
    count: Int,
    connectionState: VpnConnectionState
) {
    val isActiveSession = connectionState == VpnConnectionState.CONNECTED ||
        connectionState == VpnConnectionState.CONNECTING ||
        connectionState == VpnConnectionState.RECONNECTING ||
        connectionState == VpnConnectionState.PREPARING

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (isActiveSession) {
                stringResource(R.string.mcqueen_switch_on_tap_hint)
            } else {
                stringResource(R.string.mcqueen_select_on_tap_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (count > 0) {
            Spacer(modifier = Modifier.size(10.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = stringResource(R.string.mcqueen_countries_available_count, count),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun CountryLocationRow(
    country: CountryUiModel,
    isSelected: Boolean,
    connectionState: VpnConnectionState,
    onClick: () -> Unit
) {
    val isConnectedToCountry = isSelected && connectionState == VpnConnectionState.CONNECTED
    val isSwitchingToCountry = isSelected && (
        connectionState == VpnConnectionState.RECONNECTING ||
            connectionState == VpnConnectionState.CONNECTING ||
            connectionState == VpnConnectionState.PREPARING
        )
    val isDark = isDarkThemeActive()
    val connectedColor = if (isDark) McQueenConnectedRedDark else McQueenConnectedRedLight
    val connectingColor = if (isDark) McQueenChampionshipGold else McQueenChampionshipLightGold

    val containerColor by animateColorAsState(
        targetValue = when {
            isConnectedToCountry -> if (isDark) McQueenCardConnectedDark else connectedColor.copy(alpha = 0.07f)
            isSwitchingToCountry -> if (isDark) McQueenCardConnectingDark else connectingColor.copy(alpha = 0.08f)
            isSelected -> if (isDark) McQueenButtonSurfaceDark else MaterialTheme.colorScheme.surfaceVariant
            else -> if (isDark) McQueenCardSurfaceDark else MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(180),
        label = "country_row_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isConnectedToCountry -> connectedColor.copy(alpha = 0.55f)
            isSwitchingToCountry -> connectingColor.copy(alpha = 0.55f)
            isSelected -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f)
            else -> if (isDark) McQueenHairlineBorder else MaterialTheme.colorScheme.outline
        },
        animationSpec = tween(180),
        label = "country_row_border"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 76.dp)
            .clip(RoundedCornerShape(20.dp))
            .semantics(mergeDescendants = true) {
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .testTag("country_item_${country.countryCode}"),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isDark) McQueenButtonSurfaceDark else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = country.flagEmoji,
                            fontSize = 24.sp
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = country.countryName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    if (isSwitchingToCountry) {
                        Text(
                            text = stringResource(R.string.mcqueen_status_reconnecting),
                            style = MaterialTheme.typography.bodySmall,
                            color = connectingColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        CountryAvailabilityBadgeRow(country = country)
                    }
                }
            }

            if (isSelected) {
                val badgeAccent = when {
                    isConnectedToCountry -> connectedColor
                    isSwitchingToCountry -> connectingColor
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.acc_selected_server),
                    tint = badgeAccent,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("country_selected_checkmark_${country.countryCode}")
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun LocationsLoadingCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("locations_loading_card"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                strokeWidth = 3.dp,
                color = McQueenCrimsonReadable
            )
            Text(
                text = stringResource(R.string.mcqueen_countries_syncing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LocationsErrorCard(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("locations_error_card"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.50f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.mcqueen_locations_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = McQueenRacingRed,
                    contentColor = McQueenTitaniumWhite
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 50.dp)
                    .testTag("locations_retry_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.mcqueen_action_try_again))
            }
        }
    }
}

@Composable
private fun EmptyLocationsCard(
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("locations_empty_card"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.mcqueen_countries_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.mcqueen_countries_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = McQueenRacingRed,
                    contentColor = McQueenTitaniumWhite
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 50.dp)
                    .testTag("empty_refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.mcqueen_acc_refresh))
            }
        }
    }
}

@Composable
private fun LocationsSearchEmptyCard(
    query: String,
    onClearSearch: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("locations_search_empty_card"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.mcqueen_search_empty),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.mcqueen_search_empty_detail, query.trim()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            OutlinedButton(
                onClick = onClearSearch,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("clear_search_button")
            ) {
                Text(
                    text = stringResource(R.string.mcqueen_search_clear),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

