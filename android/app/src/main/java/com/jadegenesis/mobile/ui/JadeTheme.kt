package com.jadegenesis.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object JadeColors {
    val Bg = Color(0xFF07100D)
    val NavBg = Color(0xFF0A1512)
    val Surface = Color(0xFF101917)
    val SurfaceHover = Color(0xFF141F1C)
    val SurfaceKey = Color(0xFF16211F)
    val SurfaceSunken = Color(0xFF0B1412)
    val SheetBg = Color(0xFF0D1715)
    val Jade = Color(0xFF5EE9B5)
    val JadeInk = Color(0xFF06231B)
    val JadeDim = Color(0xFF2FA37C)
    val JadeSoft = Color(0xFFB6E8D6)
    val Ink = Color(0xFFE6F1ED)
    val InkBody = Color(0xFFD5E4DF)
    val InkMuted = Color(0xFFC6D8D2)
    val Muted = Color(0xFF9DB0AA)
    val Muted2 = Color(0xFF93A8A2)
    val Muted3 = Color(0xFF7E958E)
    val Warn = Color(0xFFF2B45C)
    val Error = Color(0xFFFF7A6B)
    val Info = Color(0xFF6BB8F2)
    val Violet = Color(0xFFA78BF5)
    val Teal = Color(0xFF4ED8C4)
    val Knowledge = Color(0xFF8CF5CF)
    val Border = Jade.copy(alpha = 0.11f)
    val BorderStrong = Jade.copy(alpha = 0.16f)
    val Divider = Jade.copy(alpha = 0.07f)
    val RailLine = Jade.copy(alpha = 0.18f)
    val TrackBg = Jade.copy(alpha = 0.09f)
    val GhostBg = Jade.copy(alpha = 0.05f)
    val GhostHover = Jade.copy(alpha = 0.12f)
    val PillActive = Jade.copy(alpha = 0.16f)
}

// The design handoff references Space Grotesk + IBM Plex Mono, but the archive
// intentionally carries no font binaries. Keep stable Android fallbacks here;
// exact downloadable fonts can be introduced later without changing the UI API.
val JadeSans: FontFamily = FontFamily.SansSerif
val JadeMono: FontFamily = FontFamily.Monospace

private val JadeColorScheme = darkColorScheme(
    primary = JadeColors.Jade,
    onPrimary = JadeColors.JadeInk,
    primaryContainer = JadeColors.Jade.copy(alpha = 0.14f),
    onPrimaryContainer = JadeColors.JadeSoft,
    secondary = JadeColors.Info,
    onSecondary = JadeColors.Bg,
    tertiary = JadeColors.Violet,
    background = JadeColors.Bg,
    onBackground = JadeColors.Ink,
    surface = JadeColors.Surface,
    onSurface = JadeColors.Ink,
    surfaceVariant = JadeColors.Surface,
    onSurfaceVariant = JadeColors.Muted,
    error = JadeColors.Error,
    onError = JadeColors.Bg,
    outline = JadeColors.Border,
    outlineVariant = JadeColors.Divider
)

private val JadeTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = JadeSans,
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = JadeSans,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 25.sp
    ),
    titleLarge = TextStyle(
        fontFamily = JadeSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 21.sp
    ),
    titleMedium = TextStyle(
        fontFamily = JadeSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontFamily = JadeSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = JadeSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = JadeSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = JadeSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = JadeSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = JadeMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp
    ),
    labelSmall = TextStyle(
        fontFamily = JadeMono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun JadeGenesisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JadeColorScheme,
        typography = JadeTypography,
        content = content
    )
}
