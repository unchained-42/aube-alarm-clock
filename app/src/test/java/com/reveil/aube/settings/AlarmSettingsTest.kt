package com.reveil.aube.settings

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSettingsTest {

    @Test(expected = IllegalArgumentException::class)
    fun `WakeWindow rejects earliest after latest`() {
        WakeWindow(earliestMinute = 100, latestMinute = 50)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `WakeWindow rejects out-of-range minutes`() {
        WakeWindow(earliestMinute = -1, latestMinute = 50)
    }

    @Test
    fun `WakeWindow allows earliest equal to latest`() {
        val window = WakeWindow(earliestMinute = 100, latestMinute = 100)
        assertEquals(100, window.earliestMinute)
    }

    @Test
    fun `windowFor without a date uses weekday or weekend based on the flag`() {
        val weekday = WakeWindow(7 * 60, 7 * 60 + 30)
        val weekend = WakeWindow(9 * 60, 9 * 60 + 30)
        val settings = defaultSettings(weekdayWindow = weekday, weekendWindow = weekend, useSeparateWeekend = true)

        assertEquals(weekday, settings.windowFor(dayOfWeekIsWeekend = false))
        assertEquals(weekend, settings.windowFor(dayOfWeekIsWeekend = true))
    }

    @Test
    fun `windowFor falls back to the weekday window when weekend separation is off`() {
        val weekday = WakeWindow(7 * 60, 7 * 60 + 30)
        val weekend = WakeWindow(9 * 60, 9 * 60 + 30)
        val settings = defaultSettings(weekdayWindow = weekday, weekendWindow = weekend, useSeparateWeekend = false)

        assertEquals(weekday, settings.windowFor(dayOfWeekIsWeekend = true))
    }

    @Test
    fun `windowFor with a date prefers the one-time override on its exact date only`() {
        val recurring = WakeWindow(7 * 60, 7 * 60 + 30)
        val override = WakeWindow(6 * 60, 6 * 60)
        val overrideDate = LocalDate.of(2026, 8, 21)
        val settings = defaultSettings(
            weekdayWindow = recurring,
            oneTimeOverrideDate = overrideDate,
            oneTimeOverrideWindow = override
        )

        assertEquals(override, settings.windowFor(overrideDate, dayOfWeekIsWeekend = false))
        assertEquals(recurring, settings.windowFor(overrideDate.plusDays(1), dayOfWeekIsWeekend = false))
    }

    @Test
    fun `effectiveQrPayload falls back to the default when unset`() {
        val settings = defaultSettings(qrPayload = null)
        assertTrue(settings.usingDefaultQrCode)
        assertEquals(com.reveil.aube.qr.DEFAULT_QR_PAYLOAD, settings.effectiveQrPayload)
    }

    @Test
    fun `effectiveQrPayload uses the custom payload once set`() {
        val settings = defaultSettings(qrPayload = "custom-code")
        assertFalse(settings.usingDefaultQrCode)
        assertEquals("custom-code", settings.effectiveQrPayload)
    }

    private fun defaultSettings(
        weekdayWindow: WakeWindow = WakeWindow(7 * 60, 7 * 60 + 30),
        weekendWindow: WakeWindow = WakeWindow(8 * 60 + 30, 9 * 60 + 15),
        useSeparateWeekend: Boolean = true,
        qrPayload: String? = null,
        oneTimeOverrideDate: LocalDate? = null,
        oneTimeOverrideWindow: WakeWindow? = null
    ) = AlarmSettings(
        alarmEnabled = true,
        weekdayWindow = weekdayWindow,
        weekendWindow = weekendWindow,
        useSeparateWeekend = useSeparateWeekend,
        dawnDurationMinutes = 25,
        trackingLeadMinutes = 30,
        qrPayload = qrPayload,
        musicUri = null,
        vibrationEnabled = true,
        lastHandledDate = null,
        reminders = emptyList(),
        onboardingCompleted = true,
        oneTimeOverrideDate = oneTimeOverrideDate,
        oneTimeOverrideWindow = oneTimeOverrideWindow,
        targetSleepMinutes = 480,
        chargeReminderMinute = null,
    )
}
