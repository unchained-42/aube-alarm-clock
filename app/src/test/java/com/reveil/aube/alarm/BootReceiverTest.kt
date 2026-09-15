package com.reveil.aube.alarm

import android.app.AlarmManager
import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.reveil.aube.settings.SettingsRepository
import com.reveil.aube.settings.WakeWindow
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * [BootReceiver.handleBoot] is the reboot-time anti-bypass logic: it's what stands between
 * "power the phone off to make the alarm disappear" and the alarm actually catching that.
 * Its pure calculation ([AlarmScheduler.missedWindowSinceLastHandled]) already has its own
 * coverage in [AlarmSchedulerTest] — this exercises the three-way branch built on top of it,
 * which is the part that decides what actually happens on a real device.
 */
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val testDataStore = PreferenceDataStoreFactory.create(
        produceFile = { File.createTempFile("test_settings_${UUID.randomUUID()}", ".preferences_pb") }
    )
    private val repository = SettingsRepository(context, testDataStore)

    private fun scheduledAlarmCount(): Int =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size

    @Test
    fun `a ring interrupted by the reboot is resumed, not rescheduled`() = runTest {
        repository.setAlarmRinging(true)
        ShadowLog.clear()

        BootReceiver.handleBoot(context, repository)

        val logs = ShadowLog.getLogs().map { it.msg }
        assertTrue(logs.any { it.contains("branch: was ringing when device went down, resuming") })
        assertFalse(
            "resuming an interrupted ring must not also (re)schedule the next occurrence",
            scheduledAlarmCount() > 0
        )
    }

    @Test
    fun `a deadline missed entirely with the device off rings now instead of silently rescheduling`() = runTest {
        // Full-day windows mean yesterday's deadline (23:59) is always already in the past by
        // the time this runs "today", no matter what time of day the test executes at — same
        // trick AlarmSchedulerTest uses to keep this deterministic without a fake clock.
        repository.setAlarmEnabled(true)
        repository.setWeekdayWindow(WakeWindow(0, 1439))
        repository.setWeekendWindow(WakeWindow(0, 1439))
        ShadowLog.clear()

        BootReceiver.handleBoot(context, repository)

        val logs = ShadowLog.getLogs().map { it.msg }
        assertTrue(logs.any { it.contains("branch: not ringing when device went down, missedWindow=true") })
        assertFalse(
            "a missed deadline must not be silently rearmed as if nothing happened",
            scheduledAlarmCount() > 0
        )
    }

    @Test
    fun `a normal reboot with nothing missed just reschedules`() = runTest {
        repository.setAlarmEnabled(true)
        repository.setWeekdayWindow(WakeWindow(0, 1439))
        repository.setWeekendWindow(WakeWindow(0, 1439))
        // Marking today as already handled means the scan in missedWindowSinceLastHandled
        // never considers any day "missed", regardless of what time the test runs at.
        repository.setLastHandledDate(LocalDate.now())
        ShadowLog.clear()

        BootReceiver.handleBoot(context, repository)

        val logs = ShadowLog.getLogs().map { it.msg }
        assertTrue(logs.any { it.contains("branch: not ringing when device went down, missedWindow=false") })
        assertTrue("a normal reboot must reschedule the next occurrence", scheduledAlarmCount() > 0)
    }
}
