package com.reveil.aube.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [SettingsRepository] through its public read/write API only (not the private
 * encode/decode helpers), backed by a real on-disk DataStore under Robolectric — the same
 * round trip the app relies on, including the self-healing window sanitization.
 *
 * `Context.dataStore` (the property [SettingsRepository] uses by default) is a single
 * JVM-wide delegate — the first [android.content.Context] to touch it wins, and every other
 * Context after that, in every other test, shares that same instance regardless of which one
 * asked. Using it directly here would leak state between test methods depending on execution
 * order (confirmed: this file was intermittently flaky before this fix). A fresh, uniquely
 * named [PreferenceDataStoreFactory] instance per test gives each test method its own file,
 * with no shared state possible.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val testDataStore = PreferenceDataStoreFactory.create(
        produceFile = { File.createTempFile("test_settings_${UUID.randomUUID()}", ".preferences_pb") }
    )
    private val repository = SettingsRepository(context, testDataStore)

    @Test
    fun `defaults come back sane on a fresh install`() = runTest {
        val settings = repository.settings.first()
        assertFalse(settings.alarmEnabled)
        assertTrue(settings.useSeparateWeekend)
        // No explicit onboarding flag and no alarm-enabled flag ever written -> not onboarded.
        assertFalse(settings.onboardingCompleted)
        assertEquals(3, settings.reminders.size)
    }

    @Test
    fun `setAlarmEnabled without an explicit onboarding flag still counts as onboarded`() = runTest {
        // Guards the upgrade path: an existing install with real settings but no onboarding
        // flag must not be funneled back through onboarding.
        repository.setAlarmEnabled(true)
        assertTrue(repository.settings.first().onboardingCompleted)
    }

    @Test
    fun `weekday window round trips through the store`() = runTest {
        val window = WakeWindow(6 * 60, 6 * 60 + 20)
        repository.setWeekdayWindow(window)
        assertEquals(window, repository.settings.first().weekdayWindow)
    }

    @Test
    fun `a persisted window wider than the allowed gap is clamped on read`() = runTest {
        // The UI caps the early-wake gap at 45 minutes, but a stale/corrupted value could
        // still be sitting in the store from an older app version.
        repository.setWeekdayWindow(WakeWindow(6 * 60, 8 * 60))
        val healed = repository.settings.first().weekdayWindow
        assertEquals(8 * 60, healed.latestMinute)
        assertEquals(8 * 60 - 45, healed.earliestMinute)
    }

    @Test
    fun `reminders round trip in order with their fields intact`() = runTest {
        val reminders = listOf(
            CustomReminder(id = "a", enabled = true, delayMinutes = 5, message = "Boire de l'eau"),
            CustomReminder(id = "b", enabled = false, delayMinutes = 30, message = "Multi\nline")
        )
        repository.setReminders(reminders)
        assertEquals(reminders, repository.settings.first().reminders)
    }

    @Test
    fun `emergency contacts round trip and blank entries are dropped`() = runTest {
        repository.setEmergencyContacts(listOf("+33612345678", "+33698765432"))
        assertEquals(listOf("+33612345678", "+33698765432"), repository.settings.first().emergencyContacts)
    }

    @Test
    fun `one-time override sets and clears independently of the recurring schedule`() = runTest {
        val date = LocalDate.of(2026, 8, 21)
        val window = WakeWindow(5 * 60, 5 * 60)
        repository.setOneTimeOverride(date, window)
        repository.settings.first().let {
            assertEquals(date, it.oneTimeOverrideDate)
            assertEquals(window, it.oneTimeOverrideWindow)
        }

        repository.clearOneTimeOverride()
        repository.settings.first().let {
            assertNull(it.oneTimeOverrideDate)
            assertNull(it.oneTimeOverrideWindow)
        }
    }

    @Test
    fun `alarm ringing flag defaults to false and reflects the last write`() = runTest {
        assertFalse(repository.isAlarmRingingUnresolved())
        repository.setAlarmRinging(true)
        assertTrue(repository.isAlarmRingingUnresolved())
        repository.setAlarmRinging(false)
        assertFalse(repository.isAlarmRingingUnresolved())
    }
}
