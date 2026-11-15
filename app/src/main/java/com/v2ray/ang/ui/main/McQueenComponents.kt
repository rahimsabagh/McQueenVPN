package com.v2ray.ang.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.mcqueen.AppUpdateCheckResult
import com.v2ray.ang.mcqueen.CountryAvailability
import com.v2ray.ang.mcqueen.CountryUiModel
import com.v2ray.ang.mcqueen.VpnConnectionState
import com.v2ray.ang.ui.compose.McQueenButtonBorderDark
import com.v2ray.ang.ui.compose.McQueenButtonSurfaceDark
import com.v2ray.ang.ui.compose.McQueenCardConnectedDark
import com.v2ray.ang.ui.compose.McQueenCardConnectingDark
import com.v2ray.ang.ui.compose.McQueenCardSurfaceDark
import com.v2ray.ang.ui.compose.McQueenChampionshipGold
import com.v2ray.ang.ui.compose.McQueenChampionshipLightGold
import com.v2ray.ang.ui.compose.McQueenConnectedRedDark
import com.v2ray.ang.ui.compose.McQueenConnectedRedLight
import com.v2ray.ang.ui.compose.McQueenControlBorderDark
import com.v2ray.ang.ui.compose.McQueenControlSurfaceDark
import com.v2ray.ang.ui.compose.McQueenCrimsonReadable
import com.v2ray.ang.ui.compose.McQueenDisplayFont
import com.v2ray.ang.ui.compose.McQueenErrorCoral
import com.v2ray.ang.ui.compose.McQueenErrorLight
import com.v2ray.ang.ui.compose.McQueenHairlineBorder
import com.v2ray.ang.ui.compose.McQueenInactiveTimerDark
import com.v2ray.ang.ui.compose.McQueenMockupAmber
import com.v2ray.ang.ui.compose.McQueenMockupAmberLight
import com.v2ray.ang.ui.compose.McQueenMockupAmberText
import com.v2ray.ang.ui.compose.McQueenMockupEmerald
import com.v2ray.ang.ui.compose.McQueenMockupEmeraldLight
import com.v2ray.ang.ui.compose.McQueenMockupRed
import com.v2ray.ang.ui.compose.McQueenMockupRedIcon
import com.v2ray.ang.ui.compose.McQueenMockupRedSoft
import com.v2ray.ang.ui.compose.McQueenMonoFont
import com.v2ray.ang.ui.compose.McQueenMutedInkDark
import com.v2ray.ang.ui.compose.McQueenOverlineInkDark
import com.v2ray.ang.ui.compose.McQueenPillBorderDark
import com.v2ray.ang.ui.compose.McQueenPrimaryInkDark
import com.v2ray.ang.ui.compose.McQueenRacingRed
import com.v2ray.ang.ui.compose.McQueenSecondaryInkDark
import com.v2ray.ang.ui.compose.McQueenTitaniumWhite
import com.v2ray.ang.ui.compose.ThemeManager
import java.util.Locale

/**
 * Signature MCQUEEN two-tone "M" + crimson swoosh emblem inside the 44x44 (14dp radius) header tile,
 * matching the edited HTML design (<svg width="30" height="18" viewBox="225 340 575 340">).
 */
@Composable
fun McQueenBrandMark(
    modifier: Modifier = Modifier
) {
    val isDark = isDarkThemeActive()
    val containerBg = if (isDark) McQueenControlSurfaceDark else MaterialTheme.colorScheme.surface
    val containerBorder = if (isDark) McQueenControlBorderDark else MaterialTheme.colorScheme.outline
    val mFillColor = if (isDark) Color(0xFFD9DEDF) else MaterialTheme.colorScheme.onSurface
    val swooshFillColor = Color(0xFF8B1A1F)

    Surface(
        modifier = modifier.size(44.dp),
        shape = RoundedCornerShape(14.dp),
        color = containerBg,
        border = BorderStroke(1.dp, containerBorder)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier.size(width = 30.dp, height = 18.dp)
            ) {
                // viewBox="225 340 575 340"
                val ox = 225f
                val oy = 340f
                val sx = size.width / 575f
                val sy = size.height / 340f

                fun px(x: Float): Float = (x - ox) * sx
                fun py(y: Float): Float = (y - oy) * sy

                // <path d="M237 666L417 358L512 517L606 358L697 512Q660 518 630 528L607 489L570 552Q522 572 481 598L416 490L315 666Z" fill="#d9dedf"/>
                val mPath = Path().apply {
                    moveTo(px(237f), py(666f))
                    lineTo(px(417f), py(358f))
                    lineTo(px(512f), py(517f))
                    lineTo(px(606f), py(358f))
                    lineTo(px(697f), py(512f))
                    quadraticTo(px(660f), py(518f), px(630f), py(528f))
                    lineTo(px(607f), py(489f))
                    lineTo(px(570f), py(552f))
                    quadraticTo(px(522f), py(572f), px(481f), py(598f))
                    lineTo(px(416f), py(490f))
                    lineTo(px(315f), py(666f))
                    close()
                }
                drawPath(path = mPath, color = mFillColor)

                // <path d="M418 666C520 585 650 540 787 519C690 546 585 600 520 666Z" fill="#8b1a1f"/>
                val swooshPath = Path().apply {
                    moveTo(px(418f), py(666f))
                    cubicTo(
                        px(520f), py(585f),
                        px(650f), py(540f),
                        px(787f), py(519f)
                    )
                    cubicTo(
                        px(690f), py(546f),
                        px(585f), py(600f),
                        px(520f), py(666f)
                    )
                    close()
                }
                drawPath(path = swooshPath, color = swooshFillColor)
            }
        }
    }
}

/**
 * Clean minimal header bar matching the MCQUEEN mockup:
 * brand emblem, tracked wordmark + subtitle, Refresh & Overflow Menu icon tiles.
 */
@Composable
fun McQueenTopBar(
    connectionState: VpnConnectionState,
    isSyncing: Boolean,
    isCheckingUpdate: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onCheckForUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isDark = isDarkThemeActive()
    val tileBg = if (isDark) McQueenControlSurfaceDark else MaterialTheme.colorScheme.surface
    val tileBorder = if (isDark) McQueenControlBorderDark else MaterialTheme.colorScheme.outline
    val tileIconTint = if (isDark) McQueenSecondaryInkDark else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (isDark) McQueenOverlineInkDark else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            McQueenBrandMark(
                modifier = Modifier.clearAndSetSemantics {}
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.mcqueen_wordmark),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = McQueenDisplayFont,
                        fontSize = 20.sp,
                        letterSpacing = 2.4.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.mcqueen_effortless_subtitle),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = tileBg,
                border = BorderStroke(1.dp, tileBorder)
            ) {
                IconButton(
                    onClick = onRefresh,
                    enabled = !isSyncing,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("refresh_locations_button")
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = McQueenCrimsonReadable
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.mcqueen_acc_refresh),
                            tint = tileIconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Box {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = tileBg,
                    border = BorderStroke(1.dp, tileBorder)
                ) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("main_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.mcqueen_acc_menu),
                            tint = tileIconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.testTag("main_overflow_menu")
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.mcqueen_nav_settings),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = null,
                                tint = McQueenCrimsonReadable
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onOpenSettings()
                        },
                        modifier = Modifier.testTag("menu_open_settings")
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (isCheckingUpdate) {
                                    stringResource(R.string.mcqueen_update_checking)
                                } else {
                                    stringResource(R.string.mcqueen_menu_check_for_update)
                                },
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingIcon = {
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        enabled = !isCheckingUpdate,
                        onClick = {
                            menuExpanded = false
                            onCheckForUpdate()
                        },
                        modifier = Modifier.testTag("menu_check_for_update")
                    )
                }
            }
        }
    }
}

/**
 * Clean sub-screen top bar with 48dp Back button and title for Locations / Settings.
 */
@Composable
fun McQueenSubScreenTopBar(
    title: String,
    onBack: () -> Unit,
    isSyncing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = isDarkThemeActive()
    val tileBg = if (isDark) McQueenControlSurfaceDark else MaterialTheme.colorScheme.surface
    val tileBorder = if (isDark) McQueenControlBorderDark else MaterialTheme.colorScheme.outline
    val tileIconTint = if (isDark) McQueenSecondaryInkDark else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = tileBg,
                border = BorderStroke(1.dp, tileBorder)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("subscreen_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.acc_back),
                        tint = tileIconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = McQueenDisplayFont,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onRefresh != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = tileBg,
                border = BorderStroke(1.dp, tileBorder)
            ) {
                IconButton(
                    onClick = onRefresh,
                    enabled = !isSyncing,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("locations_refresh_button")
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = McQueenCrimsonReadable
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.mcqueen_acc_refresh),
                            tint = tileIconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hero Center Stack matching the MCQUEEN mockup:
 * Status Badge Pill -> 264dp Orbital Ring + 188dp Power Dial -> 40sp Mono Timer -> Centered Hint.
 */
@Composable
fun McQueenConnectControl(
    connectionState: VpnConnectionState,
    statusDetail: String,
    selectedCountry: CountryUiModel?,
    sessionDurationSeconds: Long,
    isSyncingManifest: Boolean,
    hasAnyCountries: Boolean,
    onToggleConnect: () -> Unit,
    modifier: Modifier = Modifier,
    ringSize: Dp = 264.dp,
    buttonSize: Dp = 188.dp,
) {
    val isDark = isDarkThemeActive()
    val buttonState = resolveConnectButtonState(connectionState)
    val isBusy = buttonState == ConnectButtonState.Connecting
    val isConnected = buttonState == ConnectButtonState.Connected
    val isFailed = connectionState == VpnConnectionState.FAILED
    val needsLocation = selectedCountry == null && !hasAnyCountries && !isConnected && !isBusy

    val semanticColor = statusSemanticColor(connectionState)

    val subtitleText = when {
        statusDetail.isNotBlank() -> statusDetail
        isConnected && selectedCountry != null ->
            stringResource(R.string.mcqueen_hero_subtitle_connected, selectedCountry.countryName)
        isConnected ->
            stringResource(R.string.mcqueen_disconnect_tap_hint)
        isSyncingManifest && selectedCountry == null ->
            stringResource(R.string.mcqueen_hero_subtitle_loading)
        needsLocation ->
            stringResource(R.string.mcqueen_status_hint_select_location)
        else -> when (connectionState) {
            VpnConnectionState.DISCONNECTED -> stringResource(R.string.mcqueen_connect_tap_hint)
            VpnConnectionState.PREPARING -> stringResource(R.string.mcqueen_status_hint_preparing)
            VpnConnectionState.CONNECTING -> if (selectedCountry != null) {
                stringResource(R.string.mcqueen_testing_country, selectedCountry.countryName)
            } else {
                stringResource(R.string.mcqueen_status_hint_connecting)
            }
            VpnConnectionState.CONNECTED -> stringResource(R.string.mcqueen_disconnect_tap_hint)
            VpnConnectionState.RECONNECTING -> stringResource(R.string.mcqueen_status_hint_reconnecting)
            VpnConnectionState.FAILED -> stringResource(R.string.mcqueen_status_hint_failed)
        }
    }

    val timerTextColor = when {
        isConnected || isBusy -> if (isDark) McQueenPrimaryInkDark else MaterialTheme.colorScheme.onBackground
        else -> if (isDark) McQueenInactiveTimerDark else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    }

    val hintColor = when {
        isFailed -> semanticColor
        isDark -> McQueenMutedInkDark
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        StatusBadgePill(
            connectionState = connectionState,
            semanticColor = semanticColor
        )

        Spacer(modifier = Modifier.height(18.dp))

        ConnectButton(
            state = buttonState,
            onClick = onToggleConnect,
            needsLocation = needsLocation,
            isFailed = isFailed,
            ringSize = ringSize,
            buttonSize = buttonSize,
        )

        Spacer(modifier = Modifier.height(18.dp))

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = if (isConnected) formatSessionDuration(sessionDurationSeconds) else "00:00:00",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = McQueenMonoFont,
                    fontSize = 40.sp,
                    lineHeight = 46.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.4.sp
                ),
                color = timerTextColor,
                maxLines = 1,
                modifier = Modifier.testTag("session_telemetry_card")
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .widthIn(max = 280.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                color = hintColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Location Selector Card matching the HTML mockup:
 * - State-tinted container & border (Gray / Amber / Red)
 * - 48dp Rounded(14dp) icon tile
 * - "Location" overline, 17sp title, emerald-check availability row
 * - "Change" rounded pill button
 */
@Composable
fun McQueenLocationSelectorRow(
    selectedCountry: CountryUiModel?,
    connectionState: VpnConnectionState,
    isSyncingManifest: Boolean,
    onOpenLocations: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isDarkThemeActive()
    val buttonState = resolveConnectButtonState(connectionState)
    val needsLocation = selectedCountry == null

    val cardBg: Color
    val cardBorder: Color
    val iconTileBg: Color
    val iconTileFg: Color
    when (buttonState) {
        ConnectButtonState.Disconnected -> {
            cardBg = if (isDark) McQueenCardSurfaceDark else MaterialTheme.colorScheme.surface
            cardBorder = if (isDark) McQueenHairlineBorder else MaterialTheme.colorScheme.outline
            iconTileBg = if (isDark) McQueenButtonSurfaceDark else MaterialTheme.colorScheme.surfaceVariant
            iconTileFg = if (isDark) McQueenMutedInkDark else MaterialTheme.colorScheme.onSurfaceVariant
        }
        ConnectButtonState.Connecting -> {
            val amber = if (isDark) McQueenMockupAmber else McQueenMockupAmberLight
            cardBg = if (isDark) McQueenCardConnectingDark else amber.copy(alpha = 0.06f)
            cardBorder = amber.copy(alpha = 0.45f)
            iconTileBg = amber.copy(alpha = 0.14f)
            iconTileFg = amber
        }
        ConnectButtonState.Connected -> {
            val red = if (isDark) McQueenMockupRed else McQueenRacingRed
            cardBg = if (isDark) McQueenCardConnectedDark else red.copy(alpha = 0.05f)
            cardBorder = red.copy(alpha = 0.50f)
            iconTileBg = red.copy(alpha = 0.16f)
            iconTileFg = if (isDark) McQueenMockupRedIcon else red
        }
    }

    val overlineColor = if (isDark) McQueenOverlineInkDark else MaterialTheme.colorScheme.onSurfaceVariant
    val changeBtnBg = if (isDark) McQueenButtonSurfaceDark else MaterialTheme.colorScheme.surfaceVariant
    val changeBtnBorder = if (isDark) McQueenButtonBorderDark else MaterialTheme.colorScheme.outline
    val changeBtnFg = if (isDark) McQueenPrimaryInkDark else MaterialTheme.colorScheme.onSurface

    val cardDescription = if (selectedCountry != null) {
        stringResource(R.string.mcqueen_acc_selected_country, selectedCountry.countryName)
    } else {
        stringResource(R.string.mcqueen_label_no_country)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = cardDescription
            }
            .clickable(onClick = onOpenLocations)
            .testTag("active_location_card"),
        shape = RoundedCornerShape(20.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = iconTileBg
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (selectedCountry != null) {
                        Text(
                            text = selectedCountry.flagEmoji,
                            fontSize = 24.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Public,
                            contentDescription = null,
                            tint = iconTileFg,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = stringResource(R.string.mcqueen_label_location_short),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp,
                        letterSpacing = 0.5.sp
                    ),
                    color = overlineColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = selectedCountry?.countryName
                        ?: if (isSyncingManifest) {
                            stringResource(R.string.mcqueen_hero_subtitle_loading)
                        } else {
                            stringResource(R.string.mcqueen_label_no_country)
                        },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (selectedCountry != null) {
                    CountryAvailabilityBadgeRow(
                        country = selectedCountry,
                        isConnecting = buttonState == ConnectButtonState.Connecting
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenLocations)
                    .testTag("change_location_button"),
                shape = RoundedCornerShape(14.dp),
                color = changeBtnBg,
                border = BorderStroke(1.dp, changeBtnBorder)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(
                            if (needsLocation) R.string.mcqueen_action_select else R.string.mcqueen_action_change
                        ),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = changeBtnFg,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadgePill(
    connectionState: VpnConnectionState,
    semanticColor: Color
) {
    val isDark = isDarkThemeActive()
    val buttonState = resolveConnectButtonState(connectionState)
    val isFailed = connectionState == VpnConnectionState.FAILED
    val label = statusTitleText(connectionState)

    val pillBg: Color
    val pillBorder: Color
    val pillFg: Color
    when {
        isFailed -> {
            val err = if (isDark) McQueenErrorCoral else McQueenErrorLight
            pillBg = err.copy(alpha = 0.12f)
            pillBorder = err.copy(alpha = 0.50f)
            pillFg = if (isDark) McQueenMockupRedSoft else McQueenErrorLight
        }
        buttonState == ConnectButtonState.Disconnected -> {
            pillBg = if (isDark) McQueenControlSurfaceDark else MaterialTheme.colorScheme.surfaceVariant
            pillBorder = if (isDark) McQueenPillBorderDark else MaterialTheme.colorScheme.outline
            pillFg = if (isDark) McQueenSecondaryInkDark else MaterialTheme.colorScheme.onSurface
        }
        buttonState == ConnectButtonState.Connecting -> {
            val amber = if (isDark) McQueenMockupAmber else McQueenMockupAmberLight
            pillBg = amber.copy(alpha = 0.10f)
            pillBorder = amber.copy(alpha = 0.45f)
            pillFg = if (isDark) McQueenMockupAmberText else McQueenMockupAmberLight
        }
        else -> {
            val red = if (isDark) McQueenMockupRed else McQueenConnectedRedLight
            pillBg = red.copy(alpha = 0.12f)
            pillBorder = red.copy(alpha = 0.50f)
            pillFg = if (isDark) McQueenMockupRedSoft else McQueenConnectedRedLight
        }
    }

    Surface(
        modifier = Modifier.height(34.dp),
        shape = RoundedCornerShape(50),
        color = pillBg,
        border = BorderStroke(1.dp, pillBorder)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                }
                .testTag("connection_status_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isFailed) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = pillFg,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Canvas(modifier = Modifier.size(16.dp)) {
                    val s = size.minDimension / 24f
                    val stroke = Stroke(
                        width = 2.2f * s,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                    when (buttonState) {
                        ConnectButtonState.Connecting -> {
                            // <path d="M12 3a9 9 0 1 0 9 9"/> -> 270deg arc from 0deg (21, 12) clockwise to 270deg (12, 3)
                            val r = 9f * s
                            drawArc(
                                color = pillFg,
                                startAngle = 0f,
                                sweepAngle = 270f,
                                useCenter = false,
                                topLeft = androidx.compose.ui.geometry.Offset(12f * s - r, 12f * s - r),
                                size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                                style = stroke
                            )
                        }

                        ConnectButtonState.Disconnected,
                        ConnectButtonState.Connected -> {
                            // <path d="M12 3l8 3v6c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V6z"/>
                            val shieldPath = Path().apply {
                                moveTo(12f * s, 3f * s)
                                lineTo(20f * s, 6f * s)
                                lineTo(20f * s, 12f * s)
                                cubicTo(20f * s, 16.5f * s, 16.8f * s, 19.8f * s, 12f * s, 21f * s)
                                cubicTo(7.2f * s, 19.8f * s, 4f * s, 16.5f * s, 4f * s, 12f * s)
                                lineTo(4f * s, 6f * s)
                                close()
                            }
                            drawPath(path = shieldPath, color = pillFg, style = stroke)

                            if (buttonState == ConnectButtonState.Connected) {
                                // <path d="M8.5 12l2.5 2.5 4.5-5"/>
                                val checkPath = Path().apply {
                                    moveTo(8.5f * s, 12f * s)
                                    lineTo(11f * s, 14.5f * s)
                                    lineTo(15.5f * s, 9.5f * s)
                                }
                                drawPath(path = checkPath, color = pillFg, style = stroke)
                            }
                        }
                    }
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = pillFg,
                maxLines = 1
            )
        }
    }
}

@Composable
fun CountryAvailabilityBadgeRow(
    country: CountryUiModel,
    modifier: Modifier = Modifier,
    isConnecting: Boolean = false
) {
    val isDark = isDarkThemeActive()
    val bestDelay = country.bestLatencyMs
    val isMeasured = bestDelay != null && bestDelay > 0L
    val emeraldColor = if (isDark) McQueenMockupEmerald else McQueenMockupEmeraldLight
    val textColor = if (isDark) McQueenMutedInkDark else MaterialTheme.colorScheme.onSurfaceVariant

    val statusText = when {
        isConnecting -> stringResource(R.string.mcqueen_country_testing_servers)
        isMeasured && bestDelay!! <= 220L -> stringResource(R.string.mcqueen_country_optimal_ms, bestDelay)
        isMeasured -> stringResource(R.string.mcqueen_country_fast_ms, bestDelay!!)
        else -> stringResource(R.string.mcqueen_country_available)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = emeraldColor,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * HOME TELEMETRY STRIP matching the mockup 2-column grid:
 * Download / Upload cards in #101014 with #23232A border, state-tinted directional icon,
 * 20sp JetBrains Mono speed, and 12sp JetBrains Mono total.
 */
@Composable
fun McQueenTrafficTelemetrySection(
    connectionState: VpnConnectionState,
    downloadBps: Long,
    uploadBps: Long,
    sessionDownloadedBytes: Long,
    sessionUploadedBytes: Long,
    modifier: Modifier = Modifier
) {
    val isDark = isDarkThemeActive()
    val buttonState = resolveConnectButtonState(connectionState)
    val isConnected = buttonState == ConnectButtonState.Connected

    val statIconTint = when (buttonState) {
        ConnectButtonState.Disconnected -> if (isDark) McQueenMutedInkDark else MaterialTheme.colorScheme.onSurfaceVariant
        ConnectButtonState.Connecting -> if (isDark) McQueenMockupAmber else McQueenChampionshipLightGold
        ConnectButtonState.Connected -> if (isDark) McQueenMockupRedIcon else McQueenConnectedRedLight
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SpeedStatCard(
            modifier = Modifier
                .weight(1f)
                .testTag("download_speed_card"),
            icon = Icons.Rounded.ArrowDownward,
            label = stringResource(R.string.mcqueen_label_download),
            speedValue = if (isConnected) downloadBps.coerceAtLeast(0L).toSpeedString() else "0 B/s",
            totalValue = stringResource(
                R.string.mcqueen_total_label,
                if (isConnected) sessionDownloadedBytes.coerceAtLeast(0L).toTrafficString() else "0.00 B"
            ),
            iconTint = statIconTint,
        )
        SpeedStatCard(
            modifier = Modifier
                .weight(1f)
                .testTag("upload_speed_card"),
            icon = Icons.Rounded.ArrowUpward,
            label = stringResource(R.string.mcqueen_label_upload),
            speedValue = if (isConnected) uploadBps.coerceAtLeast(0L).toSpeedString() else "0 B/s",
            totalValue = stringResource(
                R.string.mcqueen_total_label,
                if (isConnected) sessionUploadedBytes.coerceAtLeast(0L).toTrafficString() else "0.00 B"
            ),
            iconTint = statIconTint,
        )
    }
}

@Composable
private fun SpeedStatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    speedValue: String,
    totalValue: String,
    iconTint: Color,
) {
    val isDark = isDarkThemeActive()
    val cardBg = if (isDark) McQueenCardSurfaceDark else MaterialTheme.colorScheme.surface
    val cardBorder = if (isDark) McQueenHairlineBorder else MaterialTheme.colorScheme.outline
    val labelColor = if (isDark) McQueenMutedInkDark else MaterialTheme.colorScheme.onSurfaceVariant
    val totalColor = if (isDark) McQueenOverlineInkDark else MaterialTheme.colorScheme.onSurfaceVariant
    val speedColor = if (isDark) McQueenPrimaryInkDark else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(20.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = speedValue,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = McQueenMonoFont,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = speedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = totalValue,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = McQueenMonoFont,
                    fontSize = 12.sp
                ),
                color = totalColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun McQueenDisconnectConfirmDialog(
    onConfirmDisconnect: () -> Unit,
    onKeepConnected: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeepConnected,
        modifier = Modifier.testTag("disconnect_confirm_dialog"),
        containerColor = MaterialTheme.colorScheme.surface,
        iconContentColor = McQueenCrimsonReadable,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        icon = {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = McQueenRacingRed.copy(alpha = 0.18f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        tint = McQueenCrimsonReadable,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        },
        title = {
            Text(
                text = stringResource(R.string.mcqueen_disconnect_confirm_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Text(
                text = stringResource(R.string.mcqueen_disconnect_confirm_body),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmDisconnect,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("disconnect_confirm_yes_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = McQueenRacingRed,
                    contentColor = McQueenTitaniumWhite
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.mcqueen_disconnect_confirm_yes))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onKeepConnected,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("disconnect_confirm_no_button")
            ) {
                Text(
                    text = stringResource(R.string.mcqueen_disconnect_confirm_no),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )
}

@Composable
fun McQueenBatteryOptimizationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        iconContentColor = MaterialTheme.colorScheme.onSurface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        icon = {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.BatteryChargingFull,
                        contentDescription = null,
                        tint = McQueenCrimsonReadable,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        },
        title = {
            Text(
                text = stringResource(R.string.mcqueen_battery_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Text(
                text = stringResource(R.string.mcqueen_battery_dialog_body),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = McQueenRacingRed,
                    contentColor = McQueenTitaniumWhite
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("battery_dialog_confirm_button")
            ) {
                Text(stringResource(R.string.mcqueen_battery_dialog_allow))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("battery_dialog_dismiss_button")
            ) {
                Text(
                    text = stringResource(R.string.mcqueen_battery_dialog_later),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
fun McQueenUpdateAvailableDialog(
    update: AppUpdateCheckResult.UpdateAvailable,
    onUpdateNow: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("app_update_available_dialog"),
        containerColor = MaterialTheme.colorScheme.surface,
        iconContentColor = McQueenCrimsonReadable,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        icon = {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = McQueenCrimsonReadable,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        },
        title = {
            Text(
                text = stringResource(R.string.mcqueen_update_available_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.mcqueen_update_version_label, update.latestVersion),
                    style = MaterialTheme.typography.titleSmall,
                    color = McQueenCrimsonReadable,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.mcqueen_update_installed_label, update.installedVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (update.publishedAtFormatted.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.mcqueen_update_published_label, update.publishedAtFormatted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.mcqueen_update_release_notes_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = update.releaseNotes.ifBlank { stringResource(R.string.mcqueen_update_no_notes) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdateNow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = McQueenRacingRed,
                    contentColor = McQueenTitaniumWhite
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("update_now_button")
            ) {
                Text(stringResource(R.string.mcqueen_update_now))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("update_cancel_button")
            ) {
                Text(
                    text = stringResource(R.string.mcqueen_update_cancel),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
fun isDarkThemeActive(): Boolean {
    return ThemeManager.resolveDarkTheme(ThemeManager.themeMode, isSystemInDarkTheme())
}

@Composable
fun statusSemanticColor(connectionState: VpnConnectionState): Color {
    val isDark = isDarkThemeActive()
    return when (connectionState) {
        VpnConnectionState.CONNECTED -> if (isDark) McQueenConnectedRedDark else McQueenConnectedRedLight
        VpnConnectionState.PREPARING,
        VpnConnectionState.CONNECTING,
        VpnConnectionState.RECONNECTING -> if (isDark) McQueenChampionshipGold else McQueenChampionshipLightGold
        VpnConnectionState.FAILED -> if (isDark) McQueenErrorCoral else McQueenErrorLight
        VpnConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
fun statusTitleText(connectionState: VpnConnectionState): String {
    return when (connectionState) {
        VpnConnectionState.DISCONNECTED -> stringResource(R.string.mcqueen_status_disconnected)
        VpnConnectionState.PREPARING -> stringResource(R.string.mcqueen_status_preparing)
        VpnConnectionState.CONNECTING -> stringResource(R.string.mcqueen_status_connecting)
        VpnConnectionState.CONNECTED -> stringResource(R.string.mcqueen_status_connected)
        VpnConnectionState.RECONNECTING -> stringResource(R.string.mcqueen_status_reconnecting)
        VpnConnectionState.FAILED -> stringResource(R.string.mcqueen_status_failed)
    }
}

fun formatSessionDuration(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3600L
    val minutes = (safeSeconds % 3600L) / 60L
    val seconds = safeSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
