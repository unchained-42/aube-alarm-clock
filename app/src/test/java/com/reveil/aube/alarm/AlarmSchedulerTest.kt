package com.reveil.aube.alarm

import androidx.test.core.app.ApplicationProvider
import com.reveil.aube.settings.AlarmSettings
import com.reveil.aube.settings.WakeWindow
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for the two trickiest pieces of scheduling math: rolling to the next
 * valid day without re-offering an already-handled one, and detecting a deadline that passed
 * entirely unhandled (device off through the window) so [BootReceiver] can fall back to the
 * accountability text instead of silently re-arming for tomorrow.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmSchedulerTest {

    private val zone = ZoneId.of("UTC")
    private val scheduler = AlarmScheduler(ApplicationProvider.getApplicationContext())

    private fun settings(
        alarmEnabled: Boolean = true,
        weekdayWindow: WakeWindow = WakeWindow(7 * 60, 7 * 60 + 30),
        weekendWindow: WakeWindow = WakeWindow(8 * 60 + 30, 9 * 60 + 15),
        useSeparateWeekend: Boolean = true,
        lastHandledDate: LocalDate? = null,
        oneTimeOverrideDate: LocalDate? = null,
        oneTimeOverrideWindow: WakeWindow? = null
    ) = AlarmSettings(
        alarmEnabled = alarmEnabled,
        weekdayWindow = weekdayWindow,
        weekendWindow = weekendWindow,
        useSeparateWeekend = useSeparateWeekend,
        dawnDurationMinutes = 25,
        trackingLeadMinutes = 30,
        qrPayload = null,
        musicUri = null,
        vibrationEnabled = true,
        lastHandledDate = lastHandledDate,
        reminders = emptyList(),
        onboardingCompleted = true,
        oneTimeOverrideDate = oneTimeOverrideDate,
        oneTimeOverrideWindow = oneTimeOverrideWindow,
        emergencyContacts = emptyList(),
        accountabilityMessage = null,
        targetSleepMinutes = 480
    )

    // --- missedWindowSinceLastHandled ---

    @Test
    fun `missedWindow is false when the alarm is disabled`() {
        val now = ZonedDateTime.of(2026, 8, 20, 12, 0, 0, 0, zone)
        assertFalse(scheduler.missedWindowSinceLastHandled(settings(alarmEnabled = false), now))
    }

    @Test
    fun `missedWindow is false when today's deadline has not passed yet`() {
        // Thursday, weekday window 07:00-07:30, now is 06:00 same day.
        val now = ZonedDateTime.of(2026, 8, 20, 6, 0, 0, 0, zone)
        assertFalse(scheduler.missedWindowSinceLastHandled(settings(lastHandledDate = LocalDate.of(2026, 8, 19)), now))
    }

    @Test
    fun `missedWindow is true when yesterday's deadline passed with nothing handled`() {
        // now is Friday 08:00, yesterday's (Thursday) weekday deadline of 07:30 already passed,
        // and lastHandledDate is null - the device was off straight through it.
        val now = ZonedDateTime.of(2026, 8, 21, 8, 0, 0, 0, zone)
        assertTrue(scheduler.missedWindowSinceLastHandled(settings(lastHandledDate = null), now))
    }

    @Test
    fun `missedWindow is false once that day has already been marked handled`() {
        val now = ZonedDateTime.of(2026, 8, 21, 8, 0, 0, 0, zone)
        assertFalse(scheduler.missedWindowSinceLastHandled(settings(lastHandledDate = LocalDate.of(2026, 8, 21)), now))
    }

    @Test
    fun `missedWindow is true when today's own deadline already passed`() {
        // now is 20:00 today, well past the 07:30 weekday deadline, and today was never handled.
        val now = ZonedDateTime.of(2026, 8, 20, 20, 0, 0, 0, zone)
        assertTrue(scheduler.missedWindowSinceLastHandled(settings(lastHandledDate = LocalDate.of(2026, 8, 19)), now))
    }

    // --- previewNextWindow ---

    @Test
    fun `previewNextWindow returns today when it has not been handled and has not passed`() {
        // A window spanning the whole day guarantees "today" is always still valid unless
        // lastHandledDate rules it out, independent of what time the test happens to run.
        val today = LocalDate.now()
        val fullDay = WakeWindow(0, 1439)
        val (_, latest) = scheduler.previewNextWindow(
            settings(weekdayWindow = fullDay, weekendWindow = fullDay, lastHandledDate = today.minusDays(1))
        )
        assertEquals(today, latest.toLocalDate())
    }

    @Test
    fun `previewNextWindow never re-offers a day at or before lastHandledDate`() {
        val today = LocalDate.now()
        val fullDay = WakeWindow(0, 1439)
        val (_, latest) = scheduler.previewNextWindow(
            settings(weekdayWindow = fullDay, weekendWindow = fullDay, lastHandledDate = today)
        )
        assertTrue("expected a day after $today but got ${latest.toLocalDate()}", latest.toLocalDate().isAfter(today))
    }

    @Test
    fun `previewNextWindow respects a one-time override on today`() {
        val today = LocalDate.now()
        // Distinct from the recurring full-day window below, and its latest minute (23:59)
        // stays after "now" regardless of what time of day this test happens to run.
        val override = WakeWindow(23 * 60, 23 * 60 + 59)
        val (earliest, latest) = scheduler.previewNextWindow(
            settings(
                weekdayWindow = WakeWindow(0, 1439),
                weekendWindow = WakeWindow(0, 1439),
                lastHandledDate = today.minusDays(1),
                oneTimeOverrideDate = today,
                oneTimeOverrideWindow = override
            )
        )
        assertEquals(today, earliest.toLocalDate())
        assertEquals(23 * 60, earliest.hour * 60 + earliest.minute)
        assertEquals(23 * 60 + 59, latest.hour * 60 + latest.minute)
    }
}
