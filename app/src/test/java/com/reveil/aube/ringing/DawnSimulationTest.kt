package com.reveil.aube.ringing

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the dawn ramp curves that drive light, sound and text contrast during ringing. */
class DawnSimulationTest {

    @Test
    fun `dawnEase is clamped and monotonic at the endpoints`() {
        assertEquals(0f, dawnEase(0f), 1e-6f)
        assertEquals(1f, dawnEase(1f), 1e-6f)
        // Out-of-range input is coerced, never extrapolated.
        assertEquals(0f, dawnEase(-1f), 1e-6f)
        assertEquals(1f, dawnEase(2f), 1e-6f)
    }

    @Test
    fun `dawnScreenBrightness stays within its floor and ceiling`() {
        assertEquals(0.02f, dawnScreenBrightness(0f), 1e-6f)
        assertEquals(1f, dawnScreenBrightness(1f), 1e-6f)
        val mid = dawnScreenBrightness(0.5f)
        assertTrue(mid in 0.02f..1f)
    }

    @Test
    fun `dawnVolume never drops to full silence and reaches full volume at the end`() {
        assertTrue(dawnVolume(0f) >= 0.06f)
        assertEquals(1f, dawnVolume(1f), 1e-6f)
    }

    @Test
    fun `dawnBackgroundColor progresses from night to daylight`() {
        val start = dawnBackgroundColor(0f)
        val end = dawnBackgroundColor(1f)
        // Night is near-black, daylight is near-white: luminance should climb across the ramp.
        assertTrue(start.luminance() < end.luminance())
    }

    @Test
    fun `dawnTextIsDark flips once the background is bright enough`() {
        assertFalse(dawnTextIsDark(0f))
        assertTrue(dawnTextIsDark(1f))
    }
}
