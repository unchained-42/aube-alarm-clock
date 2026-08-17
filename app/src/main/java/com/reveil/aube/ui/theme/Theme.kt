package com.reveil.aube.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AubeColorScheme = darkColorScheme(
    primary = Dawn,
    onPrimary = Night,
    secondary = DawnDim,
    background = Night,
    surface = NightSurface,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = NightSurfaceAlt,
    onSurfaceVariant = TextSecondary,
    outline = Divider
)

// Calmer scale than Material's defaults: lighter weights, more line-height, and a
// dedicated "hero" size for the one number on the home screen that actually matters.
private val AubeTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp)
)

/** Extra styles outside Material's fixed slots — used only where they're actually needed. */
object AubeType {
    val heroTime = TextStyle(fontWeight = FontWeight.Light, fontSize = 68.sp, lineHeight = 72.sp, letterSpacing = (-1).sp)
    val eyebrow = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp)
}

@Composable
fun AubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AubeColorScheme,
        typography = AubeTypography,
        content = content
    )
}
