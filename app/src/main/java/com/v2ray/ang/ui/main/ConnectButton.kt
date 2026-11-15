package com.v2ray.ang.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.mcqueen.VpnConnectionState
import com.v2ray.ang.ui.compose.AppTheme
import com.v2ray.ang.ui.compose.McQueenChassisDark
import com.v2ray.ang.ui.compose.McQueenChassisLight
import com.v2ray.ang.ui.compose.McQueenChassisRimDark
import com.v2ray.ang.ui.compose.McQueenChassisRimLight
import com.v2ray.ang.ui.compose.McQueenConnectedPlateDarkBottom
import com.v2ray.ang.ui.compose.McQueenConnectedPlateDarkTop
import com.v2ray.ang.ui.compose.McQueenConnectedPlateLightBottom
import com.v2ray.ang.ui.compose.McQueenConnectedPlateLightText
import com.v2ray.ang.ui.compose.McQueenConnectedPlateLightTop
import com.v2ray.ang.ui.compose.McQueenConnectingBorderDark
import com.v2ray.ang.ui.compose.McQueenConnectingBorderLight
import com.v2ray.ang.ui.compose.McQueenConnectingLabelDark
import com.v2ray.ang.ui.compose.McQueenConnectingPlateBottom
import com.v2ray.ang.ui.compose.McQueenConnectingPlateTop
import com.v2ray.ang.ui.compose.McQueenDisconnectedBorderDark
import com.v2ray.ang.ui.compose.McQueenDisconnectedBorderLight
import com.v2ray.ang.ui.compose.McQueenDisconnectedPlateDarkBottom
import com.v2ray.ang.ui.compose.McQueenDisconnectedPlateDarkTop
import com.v2ray.ang.ui.compose.McQueenDisconnectedPlateLightBottom
import com.v2ray.ang.ui.compose.McQueenDisconnectedPlateLightTop
import com.v2ray.ang.ui.compose.McQueenDisconnectedTextDark
import com.v2ray.ang.ui.compose.McQueenDisconnectedTextLight
import com.v2ray.ang.ui.compose.McQueenMockupAmber
import com.v2ray.ang.ui.compose.McQueenMockupAmberLight
import com.v2ray.ang.ui.compose.McQueenMockupRed
import com.v2ray.ang.ui.compose.McQueenRacingRedBright
import com.v2ray.ang.ui.compose.McQueenRacingRedDeep
import com.v2ray.ang.ui.compose.McQueenRacingRedHighlight
import kotlin.math.max
import kotlin.math.min

/**
 * Three-state connection control model:
 * - Disconnected -> Gray
 * - Connecting   -> Yellow / Amber
 * - Connected    -> Red
 */
enum class ConnectButtonState {
    Disconnected,
    Connecting,
    Connected,
}

@Immutable
data class ConnectButtonPalette(
    val chassisBackground: Color,
    val chassisRim: Color,
    val plateTop: Color,
    val plateBottom: Color,
    val plateBorder: Color,
    val iconTint: Color,
    val labelColor: Color,
    val accentColor: Color,
    val statusLedColor: Color,
)

/**
 * Maps domain [VpnConnectionState] to the three-state [ConnectButtonState] machine.
 */
fun resolveConnectButtonState(connectionState: VpnConnectionState): ConnectButtonState {
    return when (connectionState) {
        VpnConnectionState.CONNECTED -> ConnectButtonState.Connected
        VpnConnectionState.PREPARING,
        VpnConnectionState.CONNECTING,
        VpnConnectionState.RECONNECTING -> ConnectButtonState.Connecting
        VpnConnectionState.DISCONNECTED,
        VpnConnectionState.FAILED -> ConnectButtonState.Disconnected
    }
}

/**
 * Resolves the exact 3-color ConnectButton palette matching the MCQUEEN VPN mockup:
 * - Disconnected: Neutral Carbon Gray
 * - Connecting:   Vibrant Amber Yellow
 * - Connected:    McQueen Crimson Red
 * All variants guarantee >= 4.5:1 WCAG contrast and zero legacy blue tones.
 */
fun resolveConnectButtonPalette(
    state: ConnectButtonState,
    isDark: Boolean,
    needsLocation: Boolean = false,
    isFailed: Boolean = false,
): ConnectButtonPalette {
    val chassisBackground = if (isDark) McQueenChassisDark else McQueenChassisLight

    return when (state) {
        ConnectButtonState.Disconnected -> ConnectButtonPalette(
            chassisBackground = chassisBackground,
            chassisRim = if (isDark) McQueenChassisRimDark else McQueenChassisRimLight,
            plateTop = if (isDark) McQueenDisconnectedPlateDarkTop else McQueenDisconnectedPlateLightTop,
            plateBottom = if (isDark) McQueenDisconnectedPlateDarkBottom else McQueenDisconnectedPlateLightBottom,
            plateBorder = if (isDark) McQueenDisconnectedBorderDark else McQueenDisconnectedBorderLight,
            iconTint = if (isDark) McQueenDisconnectedTextDark else McQueenDisconnectedTextLight,
            labelColor = if (isDark) McQueenDisconnectedTextDark else McQueenDisconnectedTextLight,
            accentColor = if (isDark) McQueenChassisRimDark else McQueenDisconnectedBorderLight,
            statusLedColor = if (isFailed) {
                if (isDark) McQueenRacingRedHighlight else McQueenRacingRedDeep
            } else {
                if (isDark) McQueenDisconnectedBorderDark else McQueenDisconnectedBorderLight
            },
        )

        ConnectButtonState.Connecting -> ConnectButtonPalette(
            chassisBackground = chassisBackground,
            chassisRim = if (isDark) McQueenMockupAmber else McQueenMockupAmberLight,
            plateTop = McQueenConnectingPlateTop,
            plateBottom = McQueenConnectingPlateBottom,
            plateBorder = if (isDark) McQueenConnectingBorderDark else McQueenConnectingBorderLight,
            iconTint = McQueenConnectingLabelDark,
            labelColor = McQueenConnectingLabelDark,
            accentColor = if (isDark) McQueenMockupAmber else McQueenMockupAmberLight,
            statusLedColor = if (isDark) McQueenMockupAmber else McQueenMockupAmberLight,
        )

        ConnectButtonState.Connected -> ConnectButtonPalette(
            chassisBackground = chassisBackground,
            chassisRim = if (isDark) McQueenMockupRed else McQueenRacingRedBright,
            plateTop = if (isDark) McQueenConnectedPlateDarkTop else McQueenConnectedPlateLightTop,
            plateBottom = if (isDark) McQueenConnectedPlateDarkBottom else McQueenConnectedPlateLightBottom,
            plateBorder = if (isDark) McQueenRacingRedHighlight else McQueenRacingRedDeep,
            iconTint = Color.White,
            labelColor = McQueenConnectedPlateLightText,
            accentColor = if (isDark) McQueenMockupRed else McQueenRacingRedBright,
            statusLedColor = if (isDark) McQueenMockupRed else McQueenRacingRedBright,
        )
    }
}

/**
 * Computes WCAG 2.1 contrast ratio between two opaque colors.
 */
fun contrastRatio(foreground: Color, background: Color): Double {
    val l1 = foreground.luminance().toDouble()
    val l2 = background.luminance().toDouble()
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Circular Power Dial integrated directly onto the canvas (264dp outer track ring + 188dp core button):
 * - Disconnected: Carbon Gray (power icon)
 * - Connecting:   Vibrant Amber Yellow (1.8s mcq-spin + mcq-dash outer arc & circular Cancel X icon)
 * - Connected:    McQueen Crimson Red (solid energized ring & shield-check icon)
 */
@Composable
fun ConnectButton(
    state: ConnectButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    needsLocation: Boolean = false,
    isFailed: Boolean = false,
    enabled: Boolean = true,
    ringSize: Dp = 264.dp,
    buttonSize: Dp = 188.dp,
    testTag: String = "connect_toggle_button",
) {
    val isDark = isDarkThemeActive()
    val palette = remember(state, isDark, needsLocation, isFailed) {
        resolveConnectButtonPalette(
            state = state,
            isDark = isDark,
            needsLocation = needsLocation,
            isFailed = isFailed,
        )
    }

    val labelText = when (state) {
        ConnectButtonState.Disconnected -> when {
            needsLocation -> stringResource(R.string.mcqueen_action_choose_location)
            isFailed -> stringResource(R.string.mcqueen_action_retry)
            else -> stringResource(R.string.mcqueen_action_connect)
        }
        ConnectButtonState.Connecting -> stringResource(R.string.mcqueen_action_cancel)
        ConnectButtonState.Connected -> stringResource(R.string.mcqueen_action_disconnect)
    }

    val accessibilityLabel = when (state) {
        ConnectButtonState.Disconnected -> when {
            needsLocation -> stringResource(R.string.mcqueen_action_choose_location)
            isFailed -> stringResource(R.string.mcqueen_acc_retry_button)
            else -> stringResource(R.string.mcqueen_acc_connect_button)
        }
        ConnectButtonState.Connecting -> stringResource(R.string.mcqueen_acc_cancel_button)
        ConnectButtonState.Connected -> stringResource(R.string.mcqueen_acc_disconnect_button)
    }

    val stateDescText = when (state) {
        ConnectButtonState.Disconnected -> stringResource(
            if (isFailed) R.string.mcqueen_status_failed else R.string.mcqueen_status_disconnected
        )
        ConnectButtonState.Connecting -> stringResource(R.string.mcqueen_status_connecting)
        ConnectButtonState.Connected -> stringResource(R.string.mcqueen_status_connected)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "connect_orb_press_scale",
    )

    val targetTrackColor = when (state) {
        ConnectButtonState.Disconnected -> palette.chassisRim
        ConnectButtonState.Connecting -> palette.accentColor.copy(alpha = 0.16f)
        ConnectButtonState.Connected -> palette.chassisRim
    }
    val trackColor by animateColorAsState(
        targetValue = targetTrackColor,
        animationSpec = tween(durationMillis = 500),
        label = "connect_orb_track_color",
    )

    val plateTop by animateColorAsState(
        targetValue = palette.plateTop,
        animationSpec = tween(durationMillis = 240),
        label = "connect_orb_plate_top",
    )
    val plateBottom by animateColorAsState(
        targetValue = palette.plateBottom,
        animationSpec = tween(durationMillis = 240),
        label = "connect_orb_plate_bottom",
    )
    val plateBorder by animateColorAsState(
        targetValue = palette.plateBorder,
        animationSpec = tween(durationMillis = 240),
        label = "connect_orb_plate_border",
    )
    val iconTint by animateColorAsState(
        targetValue = palette.iconTint,
        animationSpec = tween(durationMillis = 200),
        label = "connect_orb_icon_tint",
    )
    val labelColor by animateColorAsState(
        targetValue = palette.labelColor,
        animationSpec = tween(durationMillis = 200),
        label = "connect_orb_label_color",
    )

    // Matches .mcq-spin (1.8s linear infinite) and .mcq-dash (1.8s ease-in-out infinite)
    val spinAngle: Float
    val dashPhase: Float
    if (state == ConnectButtonState.Connecting) {
        val transition = rememberInfiniteTransition(label = "connect_orb_mcq_ring")
        val animatedSpin by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "mcq_spin_angle",
        )
        val animatedDash by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "mcq_dash_phase",
        )
        spinAngle = animatedSpin
        dashPhase = animatedDash
    } else {
        spinAngle = 0f
        dashPhase = 0f
    }

    val connectedHaloAlpha = if (state == ConnectButtonState.Connected) {
        val transition = rememberInfiniteTransition(label = "connect_orb_halo")
        val alpha by transition.animateFloat(
            initialValue = 0.24f,
            targetValue = 0.36f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "connect_orb_halo_alpha",
        )
        alpha
    } else {
        0f
    }

    val iconSizeDp = (buttonSize * (54f / 188f)).coerceIn(38.dp, 54.dp)

    Box(
        modifier = modifier
            .size(ringSize)
            .scale(pressScale)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .drawBehind {
                // Soft outer ambient ring glow matching the mockup's box-shadow
                val glowColor = when (state) {
                    ConnectButtonState.Connected -> palette.accentColor.copy(alpha = connectedHaloAlpha)
                    ConnectButtonState.Connecting -> palette.accentColor.copy(alpha = 0.18f)
                    ConnectButtonState.Disconnected -> Color.Transparent
                }
                if (glowColor.alpha > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glowColor, glowColor.copy(alpha = 0f)),
                            center = center,
                            radius = size.minDimension * 0.66f,
                        ),
                        radius = size.minDimension * 0.66f,
                        center = center,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Outer 5dp architectural track ring + inner dark well (5dp inset) + mcq-dash SVG circle
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ringThickness = 5.dp.toPx()
            val outerRadius = size.minDimension / 2f
            val strokeRadius = outerRadius - ringThickness / 2f

            // Inner dark well behind the orb (matches #0b0b0e inset: 5px)
            drawCircle(
                color = palette.chassisBackground,
                radius = outerRadius - ringThickness,
                center = center,
            )

            // Outer 5dp track ring with 0.5s ease transition
            drawCircle(
                color = trackColor,
                radius = strokeRadius,
                style = Stroke(width = ringThickness),
            )

            if (state == ConnectButtonState.Connecting) {
                // Exact replica of:
                // @keyframes mcqdash {
                //   0%   { stroke-dasharray: 24 790;  stroke-dashoffset: 0 }
                //   50%  { stroke-dasharray: 400 414; stroke-dashoffset: -130 }
                //   100% { stroke-dasharray: 24 790;  stroke-dashoffset: -814 }
                // }
                val totalCircumference = 814f
                val dashLen: Float
                val dashOffset: Float
                if (dashPhase <= 0.5f) {
                    val u = EaseInOut.transform(dashPhase * 2f)
                    dashLen = 24f + (400f - 24f) * u
                    dashOffset = 130f * u
                } else {
                    val u = EaseInOut.transform((dashPhase - 0.5f) * 2f)
                    dashLen = 400f + (24f - 400f) * u
                    dashOffset = 130f + (814f - 130f) * u
                }

                val arcStartAngle = (spinAngle + (dashOffset / totalCircumference) * 360f) % 360f
                val arcSweepAngle = (dashLen / totalCircumference) * 360f
                val arcTopLeft = Offset(center.x - strokeRadius, center.y - strokeRadius)
                val arcSize = Size(strokeRadius * 2f, strokeRadius * 2f)

                drawArc(
                    color = palette.accentColor,
                    startAngle = arcStartAngle,
                    sweepAngle = arcSweepAngle,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = ringThickness, cap = StrokeCap.Round),
                )
            }
        }

        // Core circular 188dp Power Button with subtle state shadow glow
        Box(
            modifier = Modifier
                .size(buttonSize)
                .drawBehind {
                    val btnGlowAlpha = when (state) {
                        ConnectButtonState.Disconnected -> 0f
                        ConnectButtonState.Connecting -> 0.28f
                        ConnectButtonState.Connected -> 0.38f
                    }
                    if (btnGlowAlpha > 0f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    palette.accentColor.copy(alpha = btnGlowAlpha),
                                    Color.Transparent,
                                ),
                                center = Offset(size.width / 2f, size.height * 0.56f),
                                radius = size.minDimension * 0.68f,
                            ),
                            radius = size.minDimension * 0.68f,
                            center = Offset(size.width / 2f, size.height * 0.56f),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                onClick = onClick,
                enabled = enabled,
                interactionSource = interactionSource,
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, plateBorder),
                modifier = Modifier
                    .fillMaxSize()
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .semantics {
                        role = Role.Button
                        contentDescription = accessibilityLabel
                        stateDescription = stateDescText
                    }
                    .testTag(testTag),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(plateTop, plateBottom)
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        ConnectButtonHeroGlyph(
                            state = state,
                            needsLocation = needsLocation,
                            isFailed = isFailed,
                            tint = iconTint,
                            modifier = Modifier.size(iconSizeDp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = labelText,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.7.sp,
                            ),
                            color = labelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draws the exact 54x54 (viewBox 0 0 24 24) stroked vector glyphs from the edited HTML mockup:
 * - Disconnected: Power icon (M12 3v9, M6.4 6.6a8 8 0 1 0 11.2 0, stroke-width 2.1)
 * - Connecting:   Cancel circle + X icon (circle cx=12 cy=12 r=9.5 opacity=0.45, X cross, stroke-width 1.7)
 * - Connected:    Shield + Check icon (M12 3l8 3v6..., M8.5 12l2.5 2.5 4.5-5, stroke-width 2.1)
 */
@Composable
private fun ConnectButtonHeroGlyph(
    state: ConnectButtonState,
    needsLocation: Boolean,
    isFailed: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    if (state == ConnectButtonState.Disconnected && (needsLocation || isFailed)) {
        Icon(
            imageVector = if (needsLocation) Icons.Rounded.Public else Icons.Rounded.Refresh,
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
        return
    }

    val shieldPath = remember { Path() }
    val checkPath = remember { Path() }

    Canvas(modifier = modifier) {
        val s = size.minDimension / 24f
        when (state) {
            ConnectButtonState.Disconnected -> {
                val stroke = Stroke(
                    width = 2.1f * s,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                // <path d="M12 3v9"/>
                drawLine(
                    color = tint,
                    start = Offset(12f * s, 3f * s),
                    end = Offset(12f * s, 12f * s),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
                // <path d="M6.4 6.6a8 8 0 1 0 11.2 0"/>
                // Center (12, 12), radius 8; (6.4, 6.6) is ~226deg, (17.6, 6.6) is ~314deg -> sweep 272deg from 314deg
                val r = 8f * s
                drawArc(
                    color = tint,
                    startAngle = 314f,
                    sweepAngle = 272f,
                    useCenter = false,
                    topLeft = Offset(12f * s - r, 12f * s - r),
                    size = Size(r * 2f, r * 2f),
                    style = stroke,
                )
            }

            ConnectButtonState.Connecting -> {
                val strokeWidth = 1.7f * s
                // <circle cx="12" cy="12" r="9.5" stroke-opacity="0.45"/>
                drawCircle(
                    color = tint.copy(alpha = 0.45f),
                    radius = 9.5f * s,
                    center = Offset(12f * s, 12f * s),
                    style = Stroke(width = strokeWidth),
                )
                // <path d="M8.7 8.7l6.6 6.6"/>
                drawLine(
                    color = tint,
                    start = Offset(8.7f * s, 8.7f * s),
                    end = Offset(15.3f * s, 15.3f * s),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                // <path d="M15.3 8.7l-6.6 6.6"/>
                drawLine(
                    color = tint,
                    start = Offset(15.3f * s, 8.7f * s),
                    end = Offset(8.7f * s, 15.3f * s),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            ConnectButtonState.Connected -> {
                val stroke = Stroke(
                    width = 2.1f * s,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                // <path d="M12 3l8 3v6c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V6z"/>
                shieldPath.rewind()
                shieldPath.apply {
                    moveTo(12f * s, 3f * s)
                    lineTo(20f * s, 6f * s)
                    lineTo(20f * s, 12f * s)
                    cubicTo(
                        20f * s, 16.5f * s,
                        16.8f * s, 19.8f * s,
                        12f * s, 21f * s,
                    )
                    cubicTo(
                        7.2f * s, 19.8f * s,
                        4f * s, 16.5f * s,
                        4f * s, 12f * s,
                    )
                    lineTo(4f * s, 6f * s)
                    close()
                }
                drawPath(
                    path = shieldPath,
                    color = tint,
                    style = stroke,
                )
                // <path d="M8.5 12l2.5 2.5 4.5-5"/>
                checkPath.rewind()
                checkPath.apply {
                    moveTo(8.5f * s, 12f * s)
                    lineTo(11f * s, 14.5f * s)
                    lineTo(15.5f * s, 9.5f * s)
                }
                drawPath(
                    path = checkPath,
                    color = tint,
                    style = stroke,
                )
            }
        }
    }
}

@Preview(name = "Disconnected Gray Dark", showBackground = true)
@Composable
private fun PreviewConnectOrbGrayDark() {
    AppTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(24.dp)) {
            ConnectButton(
                state = ConnectButtonState.Disconnected,
                onClick = {},
            )
        }
    }
}

@Preview(name = "Connecting Yellow Dark", showBackground = true)
@Composable
private fun PreviewConnectOrbYellowDark() {
    AppTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(24.dp)) {
            ConnectButton(
                state = ConnectButtonState.Connecting,
                onClick = {},
            )
        }
    }
}

@Preview(name = "Connected Red Dark", showBackground = true)
@Composable
private fun PreviewConnectOrbRedDark() {
    AppTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(24.dp)) {
            ConnectButton(
                state = ConnectButtonState.Connected,
                onClick = {},
            )
        }
    }
}

@Preview(name = "Disconnected Gray Light", showBackground = true)
@Composable
private fun PreviewConnectOrbGrayLight() {
    AppTheme(darkTheme = false) {
        Box(modifier = Modifier.padding(24.dp)) {
            ConnectButton(
                state = ConnectButtonState.Disconnected,
                onClick = {},
            )
        }
    }
}
