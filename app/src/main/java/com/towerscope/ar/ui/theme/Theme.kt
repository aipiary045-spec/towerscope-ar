package com.towerscope.ar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val HudNavy = Color(0xCC0B1C2C)
val HudNavySolid = Color(0xFF0B1C2C)
val AccentYellow = Color(0xFFFFD60A)
val AccentCyan = Color(0xFF00E5FF)
val TextPrimary = Color(0xFFFFFFFF)
val DangerRed = Color(0xFFFF5252)
val SuccessGreen = Color(0xFF69F0AE)

private val OutdoorColorScheme = darkColorScheme(
    primary = AccentYellow,
    onPrimary = HudNavySolid,
    secondary = AccentCyan,
    onSecondary = HudNavySolid,
    background = HudNavySolid,
    onBackground = TextPrimary,
    surface = HudNavy,
    onSurface = TextPrimary,
    error = DangerRed,
    onError = TextPrimary
)

@Composable
fun TowerScopeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OutdoorColorScheme,
        typography = MaterialTheme.typography.copy(
            headlineLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = TextPrimary
            ),
            titleLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = TextPrimary
            ),
            bodyLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = TextPrimary
            ),
            labelLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = AccentYellow
            )
        ),
        content = content
    )
}
