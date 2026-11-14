package com.v2ray.ang.ui.main

import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.R
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.mcqueen.CountryAvailability
import com.v2ray.ang.mcqueen.CountryUiModel
import com.v2ray.ang.mcqueen.VpnConnectionState
import com.v2ray.ang.ui.compose.DisplayFontFamily
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.ui.compose.McQueenSemanticColors
import com.v2ray.ang.ui.compose.MonoFontFamily
import com.v2ray.ang.ui.compose.ThemeManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McQueenScreen(
    uiState: McQueenUiState,
    onConnectClick: () -> Unit,
    onSelectCountry: (CountryUiModel) -> Unit,
    onRefreshCountries: () -> Unit,
    onSetThemeMode: (String) -> Unit,
    onSetAutoBoot: (Boolean) -> Unit,
    onSetBypassLan: (Boolean) -> Unit,
    onSaveManifestUrl: (String, String, Boolean) -> Boolean,
    onResetManifestUrl: () -> Unit,
) {
    var showPreferences by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    val currentThemeMode by ThemeManager.themeMode.collectAsState()

    val isDark = LocalDarkTheme.current
    val stateAccentColor = resolveStateAccentColor(uiState.connectionState, isDark)
    val animatedAccent by animateColorAsState(
        targetValue = stateAccentColor,
        animationSpec = tween(durationMillis = 420),
        label = "accentColor",
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isWideScreen = maxWidth >= 680.dp

            // Subtle ambient radial glow anchored behind the connection stage
            AmbientStatusBackdrop(
                accentColor = animatedAccent,
                connectionState = uiState.connectionState,
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                McQueenTopBar(
                    isSyncing = uiState.isSyncing,
                    onRefreshClick = onRefreshCountries,
                    onPreferencesClick = { showPreferences = true },
                )

                if (isWideScreen) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.48f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            ConnectionHeroSection(
                                uiState = uiState,
                                accentColor = animatedAccent,
                                onConnectClick = onConnectClick,
                            )
                            TelemetryStripCard(
                                connectionState = uiState.connectionState,
                                downloadBps = uiState.downloadBps,
                                uploadBps = uiState.uploadBps,
                                sessionDurationText = uiState.sessionDurationText,
                                accentColor = animatedAccent,
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(0.52f)
                                .fillMaxHeight(),
                        ) {
                            CountrySectionHeader(
                                countryCount = uiState.countries.size,
                                isSyncing = uiState.isSyncing,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            CountryGridContent(
                                uiState = uiState,
                                columns = 2,
                                accentColor = animatedAccent,
                                onSelectCountry = onSelectCountry,
                                onRefreshCountries = onRefreshCountries,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 600.dp)
                            .align(Alignment.CenterHorizontally),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item(key = "hero") {
                            ConnectionHeroSection(
                                uiState = uiState,
                                accentColor = animatedAccent,
                                onConnectClick = onConnectClick,
                            )
                        }

                        item(key = "telemetry") {
                            TelemetryStripCard(
                                connectionState = uiState.connectionState,
                                downloadBps = uiState.downloadBps,
                                uploadBps = uiState.uploadBps,
                                sessionDurationText = uiState.sessionDurationText,
                                accentColor = animatedAccent,
                            )
                        }

                        item(key = "countries_header") {
                            CountrySectionHeader(
                                countryCount = uiState.countries.size,
                                isSyncing = uiState.isSyncing,
                            )
                        }

                        if (uiState.isSyncing && uiState.countries.isEmpty()) {
                            item(key = "countries_loading") {
                                CountriesLoadingCard()
                            }
                        } else if (uiState.countries.isEmpty()) {
                            item(key = "countries_empty") {
                                CountriesEmptyCard(
                                    errorMessage = uiState.syncError,
                                    onRetry = onRefreshCountries,
                                )
                            }
                        } else {
                            items(
                                items = uiState.countries,
                                key = { it.countryCode },
                            ) { country ->
                                val isSelected = uiState.selectedCountry?.countryCode == country.countryCode
                                CountryListCard(
                                    country = country,
                                    isSelected = isSelected,
                                    accentColor = animatedAccent,
                                    onClick = { onSelectCountry(country) },
                                )
                            }
                        }

                        item(key = "bottom_spacer") {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }

    if (showPreferences) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showPreferences = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            PreferencesSheetContent(
                themeMode = currentThemeMode,
                autoBootEnabled = uiState.autoBootEnabled,
                bypassLanEnabled = uiState.bypassLanEnabled,
                manifestUrl = uiState.manifestUrl,
                manifestHasAuthToken = uiState.manifestHasAuthToken,
                manifestUpdatedAt = uiState.manifestUpdatedAt,
                onSetThemeMode = onSetThemeMode,
                onSetAutoBoot = onSetAutoBoot,
                onSetBypassLan = onSetBypassLan,
                onSaveManifestUrl = { url, token, clearToken ->
                    if (onSaveManifestUrl(url, token, clearToken)) {
                        showPreferences = false
                    }
                },
                onResetManifestUrl = {
                    onResetManifestUrl()
                    showPreferences = false
                },
                onOpenLicenses = {
                    showPreferences = false
                    showLicensesDialog = true
                },
            )
        }
    }

    if (showLicensesDialog) {
        LicensesDialog(onDismiss = { showLicensesDialog = false })
    }
}

@Composable
private fun resolveStateAccentColor(state: VpnConnectionState, isDark: Boolean): Color {
    return when (state) {
        VpnConnectionState.DISCONNECTED -> if (isDark) McQueenSemanticColors.DisconnectedDark else McQueenSemanticColors.DisconnectedLight
        VpnConnectionState.PREPARING,
        VpnConnectionState.CONNECTING -> McQueenSemanticColors.Connecting
        VpnConnectionState.CONNECTED -> McQueenSemanticColors.Connected
        VpnConnectionState.RECONNECTING -> McQueenSemanticColors.Reconnecting
        VpnConnectionState.FAILED -> McQueenSemanticColors.Failed
    }
}

@Composable
private fun AmbientStatusBackdrop(
    accentColor: Color,
    connectionState: VpnConnectionState,
) {
    val alpha = when (connectionState) {
        VpnConnectionState.CONNECTED -> 0.16f
        VpnConnectionState.PREPARING,
        VpnConnectionState.CONNECTING,
        VpnConnectionState.RECONNECTING -> 0.14f
        VpnConnectionState.FAILED -> 0.12f
        VpnConnectionState.DISCONNECTED -> 0.06f
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = alpha),
                    Color.Transparent,
                ),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.24f),
                radius = size.minDimension * 0.85f,
            ),
        )
    }
}

@Composable
private fun McQueenTopBar(
    isSyncing: Boolean,
    onRefreshClick: () -> Unit,
    onPreferencesClick: () -> Unit,
) {
    val rotationTransition = rememberInfiniteTransition(label = "syncSpin")
    val spinAngle by rotationTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinAngle",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(start = 20.dp, end = 12.dp, top = 44.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_mcqueen_mark),
                contentDescription = stringResource(R.string.mcqueen_acc_emblem),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp)),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.mcqueen_brand_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(
            onClick = onRefreshClick,
            enabled = !isSyncing,
            modifier = Modifier
                .size(48.dp)
                .testTag("refresh_countries_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.mcqueen_acc_refresh),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = if (isSyncing) Modifier.rotate(spinAngle) else Modifier,
            )
        }

        IconButton(
            onClick = onPreferencesClick,
            modifier = Modifier
                .size(48.dp)
                .testTag("preferences_button"),
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = stringResource(R.string.mcqueen_acc_settings),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ConnectionHeroSection(
    uiState: McQueenUiState,
    accentColor: Color,
    onConnectClick: () -> Unit,
) {
    val statusLabel = when (uiState.connectionState) {
        VpnConnectionState.DISCONNECTED -> stringResource(R.string.mcqueen_status_disconnected)
        VpnConnectionState.PREPARING -> stringResource(R.string.mcqueen_status_preparing)
        VpnConnectionState.CONNECTING -> stringResource(R.string.mcqueen_status_connecting)
        VpnConnectionState.CONNECTED -> stringResource(R.string.mcqueen_status_connected)
        VpnConnectionState.RECONNECTING -> stringResource(R.string.mcqueen_status_reconnecting)
        VpnConnectionState.FAILED -> stringResource(R.string.mcqueen_status_failed)
    }

    val statusHint = uiState.statusDetail?.takeIf { it.isNotBlank() } ?: when (uiState.connectionState) {
        VpnConnectionState.DISCONNECTED -> stringResource(R.string.mcqueen_status_hint_disconnected)
        VpnConnectionState.PREPARING -> stringResource(R.string.mcqueen_status_hint_preparing)
        VpnConnectionState.CONNECTING -> stringResource(R.string.mcqueen_status_hint_connecting)
        VpnConnectionState.CONNECTED -> stringResource(R.string.mcqueen_status_hint_connected)
        VpnConnectionState.RECONNECTING -> stringResource(R.string.mcqueen_status_hint_reconnecting)
        VpnConnectionState.FAILED -> stringResource(R.string.mcqueen_status_hint_failed)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connection_hero_card"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
        ),
        border = BorderStroke(
            width = 1.dp,
            color = accentColor.copy(
                alpha = if (uiState.connectionState == VpnConnectionState.DISCONNECTED) 0.18f else 0.45f
            ),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusBadgePill(
                state = uiState.connectionState,
                label = statusLabel,
                accentColor = accentColor,
            )

            Spacer(modifier = Modifier.height(22.dp))

            PowerConnectOrbButton(
                connectionState = uiState.connectionState,
                accentColor = accentColor,
                hasCountries = uiState.countries.isNotEmpty(),
                onClick = onConnectClick,
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = statusHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            Spacer(modifier = Modifier.height(18.dp))

            SelectedCountryChip(selectedCountry = uiState.selectedCountry)
        }
    }
}

@Composable
private fun StatusBadgePill(
    state: VpnConnectionState,
    label: String,
    accentColor: Color,
) {
    val icon: ImageVector = when (state) {
        VpnConnectionState.CONNECTED -> Icons.Rounded.Shield
        VpnConnectionState.PREPARING,
        VpnConnectionState.CONNECTING,
        VpnConnectionState.RECONNECTING -> Icons.Rounded.Sync
        VpnConnectionState.FAILED -> Icons.Rounded.ErrorOutline
        VpnConnectionState.DISCONNECTED -> Icons.Rounded.PowerSettingsNew
    }

    Surface(
        modifier = Modifier.testTag("connection_status_badge"),
        shape = CircleShape,
        color = accentColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = accentColor,
            )
        }
    }
}

@Composable
private fun PowerConnectOrbButton(
    connectionState: VpnConnectionState,
    accentColor: Color,
    hasCountries: Boolean,
    onClick: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbTransition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweepAngle",
    )
    val coreScale by animateFloatAsState(
        targetValue = if (connectionState == VpnConnectionState.CONNECTED) 1.03f else 1.0f,
        animationSpec = tween(300),
        label = "coreScale",
    )

    val actionLabel = when (connectionState) {
        VpnConnectionState.CONNECTED -> stringResource(R.string.mcqueen_action_disconnect)
        VpnConnectionState.PREPARING,
        VpnConnectionState.CONNECTING,
        VpnConnectionState.RECONNECTING -> stringResource(R.string.mcqueen_action_cancel)
        VpnConnectionState.FAILED -> stringResource(R.string.mcqueen_action_retry)
        VpnConnectionState.DISCONNECTED -> stringResource(R.string.mcqueen_action_connect)
    }

    val isBusy = connectionState == VpnConnectionState.PREPARING ||
        connectionState == VpnConnectionState.CONNECTING ||
        connectionState == VpnConnectionState.RECONNECTING
    val isConnected = connectionState == VpnConnectionState.CONNECTED

    val buttonContainerColor = when (connectionState) {
        VpnConnectionState.CONNECTED -> McQueenSemanticColors.Connected
        VpnConnectionState.PREPARING,
        VpnConnectionState.CONNECTING,
        VpnConnectionState.RECONNECTING -> MaterialTheme.colorScheme.surfaceContainerHigh
        VpnConnectionState.FAILED -> McQueenSemanticColors.Failed
        VpnConnectionState.DISCONNECTED -> McQueenSemanticColors.CrimsonAccent
    }

    val contentTint = when {
        isBusy -> accentColor
        else -> Color.White
    }

    Box(
        modifier = Modifier.size(188.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Outer ambient pulse halo when connected or transitional
        if (isConnected || isBusy) {
            Box(
                modifier = Modifier
                    .size(174.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.11f)),
            )
        }

        // Animated progress arc during Connecting / Reconnecting
        Canvas(modifier = Modifier.size(176.dp)) {
            drawCircle(
                color = accentColor.copy(alpha = 0.2f),
                style = Stroke(width = 3.dp.toPx()),
            )
            if (isBusy) {
                drawArc(
                    color = accentColor,
                    startAngle = sweepAngle,
                    sweepAngle = 110f,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        Surface(
            modifier = Modifier
                .size(148.dp)
                .scale(coreScale)
                .clip(CircleShape)
                .testTag("connect_toggle_button")
                .clickable(
                    enabled = hasCountries || isConnected || isBusy,
                    role = Role.Button,
                    onClick = onClick,
                ),
            shape = CircleShape,
            color = buttonContainerColor,
            shadowElevation = if (isConnected) 16.dp else 8.dp,
            border = if (isBusy) BorderStroke(1.5.dp, accentColor.copy(alpha = 0.5f)) else null,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = actionLabel,
                    tint = contentTint,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = contentTint,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SelectedCountryChip(
    selectedCountry: CountryUiModel?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("selected_country_chip"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedCountry != null) {
                Text(
                    text = selectedCountry.flagEmoji,
                    fontSize = 24.sp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mcqueen_label_selected_country),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = selectedCountry.countryName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                selectedCountry.bestLatencyMs?.takeIf { it > 0L }?.let { ms ->
                    LatencyBadge(latencyMs = ms)
                }
            } else {
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.mcqueen_label_no_country),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TelemetryStripCard(
    connectionState: VpnConnectionState,
    downloadBps: Long,
    uploadBps: Long,
    sessionDurationText: String,
    accentColor: Color,
) {
    val isLive = connectionState == VpnConnectionState.CONNECTED
    val downloadLabel = if (isLive) downloadBps.toSpeedString() else "—"
    val uploadLabel = if (isLive) uploadBps.toSpeedString() else "—"
    val durationLabel = if (isLive) sessionDurationText else "00:00:00"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("telemetry_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TelemetryStatCell(
                icon = Icons.Rounded.ArrowDownward,
                iconTint = if (isLive) McQueenSemanticColors.Connected else MaterialTheme.colorScheme.onSurfaceVariant,
                label = stringResource(R.string.mcqueen_label_download),
                value = downloadLabel,
                modifier = Modifier
                    .weight(1f)
                    .testTag("download_speed_stat"),
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(34.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            TelemetryStatCell(
                icon = Icons.Rounded.ArrowUpward,
                iconTint = if (isLive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = stringResource(R.string.mcqueen_label_upload),
                value = uploadLabel,
                modifier = Modifier
                    .weight(1f)
                    .testTag("upload_speed_stat"),
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(34.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            TelemetryStatCell(
                icon = Icons.Rounded.Timer,
                iconTint = if (isLive) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                label = stringResource(R.string.mcqueen_label_duration),
                value = durationLabel,
                modifier = Modifier
                    .weight(1f)
                    .testTag("session_duration_stat"),
            )
        }
    }
}

@Composable
private fun TelemetryStatCell(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CountrySectionHeader(
    countryCount: Int,
    isSyncing: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.mcqueen_section_countries),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (countryCount > 0) {
                Text(
                    text = stringResource(R.string.mcqueen_countries_available_count, countryCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = isSyncing && countryCount > 0) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(CircleShape),
            )
        }
    }
}

@Composable
private fun CountryGridContent(
    uiState: McQueenUiState,
    columns: Int,
    accentColor: Color,
    onSelectCountry: (CountryUiModel) -> Unit,
    onRefreshCountries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.isSyncing && uiState.countries.isEmpty()) {
        CountriesLoadingCard()
        return
    }
    if (uiState.countries.isEmpty()) {
        CountriesEmptyCard(
            errorMessage = uiState.syncError,
            onRetry = onRefreshCountries,
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(
            items = uiState.countries,
            key = { it.countryCode },
        ) { country ->
            val isSelected = uiState.selectedCountry?.countryCode == country.countryCode
            CountryListCard(
                country = country,
                isSelected = isSelected,
                accentColor = accentColor,
                onClick = { onSelectCountry(country) },
            )
        }
    }
}

@Composable
private fun CountryListCard(
    country: CountryUiModel,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) {
        accentColor.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .testTag("country_card_${country.countryCode}")
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = country.flagEmoji,
                        fontSize = 22.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = country.countryName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val isOptimal = country.availability == CountryAvailability.OPTIMAL
                    val dotColor = if (isOptimal) McQueenSemanticColors.Connected else MaterialTheme.colorScheme.secondary
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Text(
                        text = if (isOptimal) {
                            stringResource(R.string.mcqueen_country_fast)
                        } else {
                            stringResource(R.string.mcqueen_country_ready)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            country.bestLatencyMs?.takeIf { it > 0L }?.let { ms ->
                LatencyBadge(latencyMs = ms)
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = country.countryName,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun LatencyBadge(latencyMs: Long) {
    val color = when {
        latencyMs <= 160L -> McQueenSemanticColors.Connected
        latencyMs <= 350L -> McQueenSemanticColors.Reconnecting
        else -> McQueenSemanticColors.Failed
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text = stringResource(R.string.mcqueen_country_latency, latencyMs),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Medium,
            ),
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CountriesLoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Text(
                text = stringResource(R.string.mcqueen_countries_syncing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CountriesEmptyCard(
    errorMessage: String?,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("countries_empty_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.mcqueen_countries_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = errorMessage?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.mcqueen_countries_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag("retry_sync_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.mcqueen_action_retry))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferencesSheetContent(
    themeMode: String,
    autoBootEnabled: Boolean,
    bypassLanEnabled: Boolean,
    manifestUrl: String,
    manifestHasAuthToken: Boolean,
    manifestUpdatedAt: String?,
    onSetThemeMode: (String) -> Unit,
    onSetAutoBoot: (Boolean) -> Unit,
    onSetBypassLan: (Boolean) -> Unit,
    onSaveManifestUrl: (String, String, Boolean) -> Unit,
    onResetManifestUrl: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    var draftUrl by remember(manifestUrl) { mutableStateOf(manifestUrl) }
    var draftToken by remember(manifestHasAuthToken) { mutableStateOf("") }
    var clearTokenRequested by remember(manifestHasAuthToken) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = stringResource(R.string.mcqueen_settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.mcqueen_settings_appearance),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val options = listOf(
                "2" to stringResource(R.string.mcqueen_settings_theme_dark),
                "1" to stringResource(R.string.mcqueen_settings_theme_light),
                "0" to stringResource(R.string.mcqueen_settings_theme_system),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (modeValue, label) ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        onClick = { onSetThemeMode(modeValue) },
                        selected = themeMode == modeValue,
                    ) {
                        Text(text = label, maxLines = 1)
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        PreferenceSwitchRow(
            title = stringResource(R.string.mcqueen_settings_auto_boot),
            checked = autoBootEnabled,
            onCheckedChange = onSetAutoBoot,
            testTag = "pref_auto_boot_switch",
        )

        PreferenceSwitchRow(
            title = stringResource(R.string.mcqueen_settings_bypass_lan),
            checked = bypassLanEnabled,
            onCheckedChange = onSetBypassLan,
            testTag = "pref_bypass_lan_switch",
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.mcqueen_settings_manifest_source),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draftUrl,
                onValueChange = { draftUrl = it },
                label = { Text(stringResource(R.string.mcqueen_settings_manifest_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manifest_url_input"),
            )
            OutlinedTextField(
                value = draftToken,
                onValueChange = {
                    draftToken = it
                    if (it.isNotEmpty()) clearTokenRequested = false
                },
                label = {
                    val labelRes = if (manifestHasAuthToken && !clearTokenRequested) {
                        R.string.mcqueen_settings_manifest_token_saved
                    } else {
                        R.string.mcqueen_settings_manifest_token_hint
                    }
                    Text(stringResource(labelRes))
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manifest_token_input"),
            )
            if ((manifestHasAuthToken && !clearTokenRequested) || draftToken.isNotEmpty()) {
                TextButton(
                    onClick = {
                        draftToken = ""
                        clearTokenRequested = true
                    },
                    modifier = Modifier.testTag("manifest_token_clear_button"),
                ) {
                    Text(stringResource(R.string.mcqueen_settings_manifest_token_clear))
                }
            }
            Text(
                text = stringResource(R.string.mcqueen_settings_manifest_sync_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!manifestUpdatedAt.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.mcqueen_settings_last_updated, manifestUpdatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onResetManifestUrl,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manifest_reset_button"),
                ) {
                    Text(stringResource(R.string.mcqueen_settings_manifest_reset))
                }
                Button(
                    onClick = { onSaveManifestUrl(draftUrl, draftToken, clearTokenRequested) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manifest_save_button"),
                ) {
                    Text(stringResource(R.string.mcqueen_settings_manifest_save))
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        TextButton(
            onClick = onOpenLicenses,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open_licenses_button"),
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.mcqueen_settings_licenses))
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.mcqueen_settings_licenses),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.mcqueen_action_cancel),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            loadUrl("file:///android_asset/open_source_licenses.html")
                        }
                    },
                )
            }
        }
    }
}
