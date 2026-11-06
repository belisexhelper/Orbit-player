package com.orbitplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object OrbitColors {
    val Navy = Color(0xFF1F3B73)
    val NavyDeep = Color(0xFF0F1A30)
    val Panel = Color(0xFF16233F)
    val PanelHigh = Color(0xFF1E2E52)
    val Amber = Color(0xFFF2B33D)
    val Text = Color(0xFFEAF0FA)
    val TextDim = Color(0xFF9AA9C7)
    val Ok = Color(0xFF3FBF7F)
    val Bad = Color(0xFFFF7A6E)
}

/** Da li je aktivan TV interfejs (daljinski) — komponente prilagođavaju veličinu i fokus */
val LocalIsTv = staticCompositionLocalOf { false }

fun parseColor(hex: String, fallback: Color): Color = runCatching {
    val h = hex.trim().removePrefix("#")
    if (h.length == 6) Color(("FF$h").toLong(16)) else fallback
}.getOrDefault(fallback)

@Composable
fun OrbitTheme(light: Boolean, accentHex: String, isTv: Boolean, content: @Composable () -> Unit) {
    val accent = parseColor(accentHex, OrbitColors.Amber)
    val scheme: ColorScheme = if (light && !isTv) {
        lightColorScheme(
            primary = OrbitColors.Navy,
            onPrimary = Color.White,
            secondary = accent,
            background = Color(0xFFF4F6FA),
            surface = Color.White,
            surfaceVariant = Color(0xFFE6EBF4),
            onBackground = Color(0xFF1A2233),
            onSurface = Color(0xFF1A2233),
            onSurfaceVariant = Color(0xFF4D5870),
        )
    } else {
        darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF1B1300),
            secondary = accent,
            background = OrbitColors.NavyDeep,
            surface = OrbitColors.Panel,
            surfaceVariant = OrbitColors.PanelHigh,
            onBackground = OrbitColors.Text,
            onSurface = OrbitColors.Text,
            onSurfaceVariant = OrbitColors.TextDim,
            error = OrbitColors.Bad,
        )
    }
    val scale = if (isTv) 1.0f else 1.0f
    val typography = Typography(
        headlineMedium = TextStyle(fontSize = (if (isTv) 30 else 26).sp * scale, fontWeight = FontWeight.Bold),
        titleLarge = TextStyle(fontSize = (if (isTv) 24 else 20).sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = (if (isTv) 19 else 16).sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = (if (isTv) 18 else 16).sp),
        bodyMedium = TextStyle(fontSize = (if (isTv) 16 else 14).sp),
        labelLarge = TextStyle(fontSize = (if (isTv) 16 else 14).sp, fontWeight = FontWeight.SemiBold),
        bodySmall = TextStyle(fontSize = (if (isTv) 14 else 12).sp),
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
