package com.reveil.aube.ringing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

private val NightColor = Color(0xFF14161F)
private val DawnColor = Color(0xFFE08A4F)
private val DaylightColor = Color(0xFFFFF6E8)

/**
 * Smoothstep easing so the ramp starts slow and accelerates toward the end — closer to the
 * sigmoidal curve used in the lab dawn-simulation studies than a flat linear fade.
 */
fun dawnEase(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return p * p * (3 - 2 * p)
}

/** Night -> amber dawn -> warm daylight, matching the color progression from the research. */
fun dawnBackgroundColor(progress: Float): Color {
    val eased = dawnEase(progress)
    return if (eased < 0.6f) {
        lerp(NightColor, DawnColor, eased / 0.6f)
    } else {
        lerp(DawnColor, DaylightColor, (eased - 0.6f) / 0.4f)
    }
}

/** Screen brightness the hosting window should track for this progress. */
fun dawnScreenBrightness(progress: Float): Float {
    val eased = dawnEase(progress)
    return (0.02f + eased * 0.98f).coerceIn(0.02f, 1f)
}

/**
 * Whether foreground text/buttons should switch to dark ink to stay readable. Driven by the
 * actual rendered background luminance rather than a fixed [progress] cutoff — the color ramp
 * is eased (non-linear), so a fixed threshold on raw progress used to leave white text
 * sitting on an already-bright amber background for a big chunk of the ramp.
 */
fun dawnTextIsDark(progress: Float): Boolean = dawnBackgroundColor(progress).luminance() > 0.22f

/**
 * Alarm volume for this progress — the same eased curve as the light, so sound and light
 * build together across the earliest→latest window instead of light ramping silently and
 * sound then blasting in all at once the moment the deadline hits.
 */
fun dawnVolume(progress: Float): Float {
    val eased = dawnEase(progress)
    return (MIN_DAWN_VOLUME + eased * (1f - MIN_DAWN_VOLUME)).coerceIn(MIN_DAWN_VOLUME, 1f)
}

private const val MIN_DAWN_VOLUME = 0.06f

@Composable
fun DawnBackground(progress: Float, modifier: Modifier = Modifier) {
    val color = dawnBackgroundColor(progress)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(color, lerp(color, Color.Black, 0.18f))
                )
            )
    )
}
