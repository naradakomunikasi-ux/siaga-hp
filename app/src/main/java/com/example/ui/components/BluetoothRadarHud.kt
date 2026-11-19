package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.MonitoredPhoneUiItem
import com.example.ui.PhonePresenceStatus
import com.example.ui.theme.SiagaThemeTokens
import com.example.ui.theme.TelemetryMonoStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class RadarVisualState {
    SCANNING,
    ALL_SAFE,
    MISSING_ALERT,
    STANDBY
}

@Composable
fun BluetoothRadarHud(
    visualState: RadarVisualState,
    phones: List<MonitoredPhoneUiItem>,
    detectedCount: Int,
    monitoredCount: Int,
    remainingSeconds: Int,
    modifier: Modifier = Modifier,
    size: Dp = 216.dp
) {
    val tokens = SiagaThemeTokens.colors
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceColor = MaterialTheme.colorScheme.surface

    val accentColor = when (visualState) {
        RadarVisualState.SCANNING -> tokens.radarAccent
        RadarVisualState.ALL_SAFE -> tokens.safe
        RadarVisualState.MISSING_ALERT -> tokens.alert
        RadarVisualState.STANDBY -> MaterialTheme.colorScheme.primary
    }

    val infiniteTransition = rememberInfiniteTransition(label = "radar_anim")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_sweep"
    )
    val pulseFraction by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radar_pulse"
    )

    val monitoredPhones = phones.filter { it.entity.isMonitored }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxRadius = (this.size.minDimension / 2f) - 10.dp.toPx()

            // Radial ambient glow behind radar
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = if (visualState == RadarVisualState.STANDBY) 0.14f else 0.24f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxRadius * pulseFraction
                ),
                radius = maxRadius * pulseFraction,
                center = center
            )

            // Concentric range rings
            listOf(0.38f, 0.68f, 1.0f).forEachIndexed { idx, factor ->
                val ringRadius = maxRadius * factor
                drawCircle(
                    color = if (idx == 2) accentColor.copy(alpha = 0.35f) else outlineColor.copy(alpha = 0.45f),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = if (idx == 2) 1.6.dp.toPx() else 1.dp.toPx())
                )
            }

            // Crosshair lines
            drawLine(
                color = outlineColor.copy(alpha = 0.28f),
                start = Offset(center.x - maxRadius, center.y),
                end = Offset(center.x + maxRadius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = outlineColor.copy(alpha = 0.28f),
                start = Offset(center.x, center.y - maxRadius),
                end = Offset(center.x, center.y + maxRadius),
                strokeWidth = 1.dp.toPx()
            )

            // Active rotating sweep sector during scan
            if (visualState == RadarVisualState.SCANNING) {
                rotate(degrees = sweepAngle, pivot = center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0.0f to Color.Transparent,
                            0.72f to Color.Transparent,
                            0.94f to accentColor.copy(alpha = 0.35f),
                            1.0f to accentColor.copy(alpha = 0.85f),
                            center = center
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = true,
                        topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                        size = androidx.compose.ui.geometry.Size(maxRadius * 2, maxRadius * 2)
                    )
                    drawLine(
                        color = accentColor,
                        start = center,
                        end = Offset(center.x + maxRadius, center.y),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // Plot monitored smartphones as blips based on real RSSI proximity
            monitoredPhones.forEachIndexed { index, phone ->
                val angleDeg = (index * (360f / monitoredPhones.size.coerceAtLeast(1))) - 55f
                val angleRad = angleDeg * (PI / 180.0)
                val normalizedRadius = when (phone.liveStatus) {
                    PhonePresenceStatus.DETECTED -> {
                        val rssi = phone.liveRssi ?: -62
                        // Map -40 dBm (very close -> 0.42) to -90 dBm (edge -> 0.82)
                        val clamped = rssi.coerceIn(-95, -40)
                        0.42f + ((clamped + 40).toFloat() / -55f) * 0.38f
                    }
                    PhonePresenceStatus.MISSING -> 0.94f
                    else -> 0.72f
                }
                val blipRadius = maxRadius * normalizedRadius
                val blipCenter = Offset(
                    x = center.x + (cos(angleRad) * blipRadius).toFloat(),
                    y = center.y + (sin(angleRad) * blipRadius).toFloat()
                )
                val blipColor = when (phone.liveStatus) {
                    PhonePresenceStatus.DETECTED -> tokens.safe
                    PhonePresenceStatus.MISSING -> tokens.alert
                    PhonePresenceStatus.SCANNING -> tokens.radarAccent
                    else -> outlineColor
                }

                drawCircle(
                    color = blipColor.copy(alpha = 0.28f),
                    radius = 10.dp.toPx(),
                    center = blipCenter
                )
                drawCircle(
                    color = blipColor,
                    radius = 5.dp.toPx(),
                    center = blipCenter
                )
                drawCircle(
                    color = surfaceColor,
                    radius = 2.dp.toPx(),
                    center = blipCenter
                )
            }

            // Inner hub circle
            drawCircle(
                color = surfaceColor,
                radius = maxRadius * 0.34f,
                center = center
            )
            drawCircle(
                color = accentColor.copy(alpha = 0.7f),
                radius = maxRadius * 0.34f,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // Center HUD telemetry readout
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = when (visualState) {
                    RadarVisualState.SCANNING -> Icons.Rounded.BluetoothSearching
                    RadarVisualState.ALL_SAFE -> Icons.Rounded.CheckCircle
                    RadarVisualState.MISSING_ALERT -> Icons.Rounded.WarningAmber
                    RadarVisualState.STANDBY -> Icons.Rounded.DirectionsCar
                },
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = when (visualState) {
                    RadarVisualState.SCANNING -> "${remainingSeconds}s"
                    else -> "$detectedCount/$monitoredCount"
                },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun SignalStrengthIndicator(
    rssi: Int?,
    modifier: Modifier = Modifier
) {
    val tokens = SiagaThemeTokens.colors
    val activeBars = when {
        rssi == null -> 0
        rssi >= -60 -> 4
        rssi >= -72 -> 3
        rssi >= -84 -> 2
        else -> 1
    }
    val barColor = when (activeBars) {
        4, 3 -> tokens.safe
        2 -> tokens.warning
        1 -> tokens.alert
        else -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
    ) {
        val heights = listOf(6.dp, 10.dp, 14.dp, 18.dp)
        heights.forEachIndexed { idx, barHeight ->
            val filled = idx < activeBars
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (filled) barColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    )
            )
        }
        if (rssi != null) {
            Text(
                text = " ${rssi}dBm",
                style = TelemetryMonoStyle,
                color = barColor
            )
        }
    }
}

fun formatRssiQuality(rssi: Int?): String = when {
    rssi == null -> "Belum ada sinyal"
    rssi >= -60 -> "Sangat dekat (Dalam kabin)"
    rssi >= -74 -> "Dekat (Dalam kabin)"
    rssi >= -86 -> "Jarak sedang (Tepi mobil)"
    else -> "Sinyal lemah (Kemungkinan jauh)"
}

fun formatRelativeTime(timestamp: Long?): String {
    if (timestamp == null) return "Belum pernah terdeteksi"
    val diffSec = ((System.currentTimeMillis() - timestamp) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 15 -> "Baru saja"
        diffSec < 60 -> "$diffSec detik lalu"
        diffSec < 3600 -> "${diffSec / 60} menit lalu"
        diffSec < 86400 -> "${diffSec / 3600} jam lalu"
        else -> SimpleDateFormat("dd MMM, HH:mm", Locale.Builder().setLanguage("id").setRegion("ID").build())
            .format(Date(timestamp))
    }
}

fun formatClockTimestamp(timestamp: Long): String {
    return SimpleDateFormat("dd MMM • HH:mm:ss", Locale.Builder().setLanguage("id").setRegion("ID").build())
        .format(Date(timestamp))
}
