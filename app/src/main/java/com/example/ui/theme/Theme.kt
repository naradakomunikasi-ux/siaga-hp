package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class SiagaSemanticColors(
    val safe: Color,
    val safeContainer: Color,
    val onSafeContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val alert: Color,
    val alertContainer: Color,
    val onAlertContainer: Color,
    val radarAccent: Color,
    val cardElevated: Color
)

private val DarkSemanticColors = SiagaSemanticColors(
    safe = SignalEmeraldLight,
    safeContainer = EmeraldContainer,
    onSafeContainer = OnEmeraldContainer,
    warning = WarningAmber,
    warningContainer = WarningAmberContainer,
    onWarningContainer = OnAmberContainer,
    alert = AlertRoseLight,
    alertContainer = RoseContainer,
    onAlertContainer = OnRoseContainer,
    radarAccent = ElectricCyan,
    cardElevated = CockpitSurfaceElevated
)

private val LightSemanticColors = SiagaSemanticColors(
    safe = SignalEmerald,
    safeContainer = LightEmeraldContainer,
    onSafeContainer = LightOnEmeraldContainer,
    warning = WarningAmber,
    warningContainer = LightAmberContainer,
    onWarningContainer = LightOnAmberContainer,
    alert = AlertRose,
    alertContainer = LightRoseContainer,
    onAlertContainer = LightOnRoseContainer,
    radarAccent = ElectricCyanDark,
    cardElevated = PearlSurface
)

val LocalSiagaColors = staticCompositionLocalOf { DarkSemanticColors }

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = CockpitObsidian,
    primaryContainer = DeepCyanContainer,
    onPrimaryContainer = OnCyanContainer,
    secondary = SignalEmeraldLight,
    onSecondary = CockpitObsidian,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = OnEmeraldContainer,
    tertiary = WarningAmber,
    onTertiary = CockpitObsidian,
    tertiaryContainer = WarningAmberContainer,
    onTertiaryContainer = OnAmberContainer,
    error = AlertRoseLight,
    onError = CockpitObsidian,
    errorContainer = RoseContainer,
    onErrorContainer = OnRoseContainer,
    background = CockpitObsidian,
    onBackground = TextPrimaryDark,
    surface = CockpitSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = CockpitSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerHigh = CockpitSurfaceHighest,
    outline = CockpitOutline,
    outlineVariant = CockpitSurfaceHighest
)

private val LightColorScheme = lightColorScheme(
    primary = ElectricCyanDark,
    onPrimary = Color.White,
    primaryContainer = LightCyanContainer,
    onPrimaryContainer = LightOnCyanContainer,
    secondary = SignalEmerald,
    onSecondary = Color.White,
    secondaryContainer = LightEmeraldContainer,
    onSecondaryContainer = LightOnEmeraldContainer,
    tertiary = WarningAmber,
    onTertiary = Color.White,
    tertiaryContainer = LightAmberContainer,
    onTertiaryContainer = LightOnAmberContainer,
    error = AlertRose,
    onError = Color.White,
    errorContainer = LightRoseContainer,
    onErrorContainer = LightOnRoseContainer,
    background = PearlBackground,
    onBackground = TextPrimaryLight,
    surface = PearlSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = PearlSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerHigh = PearlSurfaceVariant,
    outline = PearlOutline,
    outlineVariant = PearlSurfaceVariant
)

val SiagaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun SiagaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalSiagaColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SiagaTypography,
            shapes = SiagaShapes,
            content = content
        )
    }
}

object SiagaThemeTokens {
    val colors: SiagaSemanticColors
        @Composable get() = LocalSiagaColors.current
}
