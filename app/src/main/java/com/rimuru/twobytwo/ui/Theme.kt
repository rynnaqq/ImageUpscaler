package com.rimuru.twobytwo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette derived from the Rimuru artwork: ice-blue primary, amber-gold accent
private val IceBlue = Color(0xFF5BA8D0)
private val IceBlueDeep = Color(0xFF2E6E96)
private val IceBlueContainer = Color(0xFFD6ECF7)
private val Gold = Color(0xFFD9A521)
private val GoldContainer = Color(0xFFFFE9BE)
private val NightSurface = Color(0xFF10181E)
private val NightSurfaceVar = Color(0xFF1A242C)

private val LightColors = lightColorScheme(
    primary = IceBlueDeep,
    onPrimary = Color.White,
    primaryContainer = IceBlueContainer,
    onPrimaryContainer = Color(0xFF0B2B3D),
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = GoldContainer,
    onSecondaryContainer = Color(0xFF3D2E00),
    surface = Color(0xFFFBFDFE),
    surfaceVariant = Color(0xFFE7EEF2),
    onSurface = Color(0xFF17212A),
    onSurfaceVariant = Color(0xFF465863),
    outline = Color(0xFF718792),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD2EF),
    onPrimary = Color(0xFF00344B),
    primaryContainer = IceBlueDeep,
    onPrimaryContainer = IceBlueContainer,
    secondary = Color(0xFFF2C35C),
    onSecondary = Color(0xFF3D2E00),
    secondaryContainer = Color(0xFF584300),
    onSecondaryContainer = GoldContainer,
    surface = NightSurface,
    surfaceVariant = NightSurfaceVar,
    onSurface = Color(0xFFE1E9ED),
    onSurfaceVariant = Color(0xFFA9BEC9),
    outline = Color(0xFF748994),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

/** App theme — fixed Rimuru palette (PRD UX-1 dynamic color is a later polish item). */
@Composable
fun RimuruTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
