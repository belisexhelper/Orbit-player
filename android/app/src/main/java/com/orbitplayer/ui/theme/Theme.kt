package com.orbitplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** Design tokens ("Orbit Slate": dark but readable, contrast ≥ 4.5:1 everywhere) */
@Immutable
data class OrbitPalette(
    val background: Color,
    val surface: Color,
    val surfaceFocus: Color,
    val elevated: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentAlt: Color,
    val live: Color,
    val success: Color,
    val divider: Color,
    val scrim: Color,
    val tileStart: Color,
    val tileEnd: Color,
    val isLight: Boolean,
)

val SlatePalette = OrbitPalette(
    background = Color(0xFF161B26),
    surface = Color(0xFF1E2533),
    surfaceFocus = Color(0xFF2A3344),
    elevated = Color(0xFF34405A),
    text = Color(0xFFF2F5FA),
    textMuted = Color(0xFFB8C1D1),
    accent = Color(0xFF3DA9FC),
    onAccent = Color(0xFF06223A),
    accentAlt = Color(0xFFFFB547),
    live = Color(0xFFFF5A5F),
    success = Color(0xFF3DDC97),
    divider = Color(0xFF2E3748),
    scrim = Color(0xB3000000),
    tileStart = Color(0xFF1B4FA3),
    tileEnd = Color(0xFF2B7BE0),
    isLight = false,
)

val OledPalette = SlatePalette.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF111418),
    surfaceFocus = Color(0xFF232A36),
    elevated = Color(0xFF2A3344),
    divider = Color(0xFF1E232C),
)

val LightPalette = OrbitPalette(
    background = Color(0xFFF4F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceFocus = Color(0xFFE3EAF5),
    elevated = Color(0xFFFFFFFF),
    text = Color(0xFF121722),
    textMuted = Color(0xFF4E5A6E),
    accent = Color(0xFF1668C4),
    onAccent = Color(0xFFFFFFFF),
    accentAlt = Color(0xFFB86E00),
    live = Color(0xFFD7263D),
    success = Color(0xFF1F8A5B),
    divider = Color(0xFFDCE2EC),
    scrim = Color(0x99000000),
    tileStart = Color(0xFF1B4FA3),
    tileEnd = Color(0xFF2B7BE0),
    isLight = true,
)

val LocalPalette = staticCompositionLocalOf { SlatePalette }

/** TV interface (remote control) or phone/tablet (touch) */
val LocalIsTv = staticCompositionLocalOf { false }

object Orbit {
    val colors: OrbitPalette @Composable get() = LocalPalette.current
}

fun paletteFor(theme: String, isTv: Boolean): OrbitPalette = when {
    theme == "oled" -> OledPalette
    theme == "light" && !isTv -> LightPalette
    else -> SlatePalette
}

@Composable
fun OrbitTheme(theme: String = "slate", isTv: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val p = paletteFor(theme, isTv)
    val scheme = if (p.isLight) {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.accentAlt,
            background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surfaceFocus, onSurfaceVariant = p.textMuted, error = p.live, outline = p.divider,
            surfaceContainer = p.surface, surfaceContainerHigh = p.elevated, surfaceContainerHighest = p.elevated,
        )
    } else {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.accentAlt,
            background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surfaceFocus, onSurfaceVariant = p.textMuted, error = p.live, outline = p.divider,
            surfaceContainer = p.surface, surfaceContainerHigh = p.elevated, surfaceContainerHighest = p.elevated,
            surfaceContainerLow = p.surface,
        )
    }
    val type = if (isTv) {
        Typography(
            headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
            headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp),
            bodyMedium = TextStyle(fontSize = 15.sp),
            bodySmall = TextStyle(fontSize = 13.sp),
            labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
    } else {
        Typography(
            headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
            headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp),
            bodyMedium = TextStyle(fontSize = 14.sp),
            bodySmall = TextStyle(fontSize = 12.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        )
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalPalette provides p,
        LocalIsTv provides isTv,
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
    ) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
