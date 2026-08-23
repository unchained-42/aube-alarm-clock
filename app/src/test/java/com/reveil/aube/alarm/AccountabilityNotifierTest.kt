package com.reveil.aube.alarm

import android.Manifest
import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.reveil.aube.settings.AlarmSettings
import com.reveil.aube.settings.SettingsRepository
import com.reveil.aube.settings.WakeWindow
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * The one consequence left once a phone is off (or otherwise unreachable) straight through
 * the deadline — see [AccountabilityNotifier]'s doc comment. Covers the two silent-skip
 * guards, which never touch the platform SMS stack and so are safe to assert on directly, plus
 * [AccountabilityNotifier.pickRecipient]'s selection rule on its own (no Android/SMS
 * dependency at all).
 *
 * The actual send (`SmsManager.divideMessage` / `sendMultipartTextMessage`) isn't covered
 * here: `divideMessage` reaches into a real system `ISms` binder service
 * (`TelephonyManager.getSmsService()`) that plain Robolectric doesn't provide, so it throws
 * `UnsupportedOperationException` (silently swallowed by this code's own `runCatching`)
 * regardless of any shadow configuration attempted here — confirmed via log capture, not
 * assumed. Faithfully exercising the real send would need a fake `ISms` service registered
 * through `ServiceManager`, disproportionate to what this method actually needs covered.
 */
@RunWith(RobolectricTestRunner::class)
class AccountabilityNotifierTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val testDataStore = PreferenceDataStoreFactory.create(
        produceFile = { File.createTempFile("test_settings_${UUID.randomUUID()}", ".preferences_pb") }
    )
    private val repository = SettingsRepository(context, testDataStore)

    private fun settings(contacts: List<String>, message: String? = null, lastNotifiedContact: String? = null) = AlarmSettings(
        alarmEnabled = true,
        weekdayWindow = WakeWindow(7 * 60, 7 * 60 + 30),
        weekendWindow = WakeWindow(8 * 60 + 30, 9 * 60 + 15),
        useSeparateWeekend = true,
        dawnDurationMinutes = 25,
        trackingLeadMinutes = 30,
        qrPayload = null,
        musicUri = null,
        vibrationEnabled = true,
        lastHandledDate = null,
        reminders = emptyList(),
        onboardingCompleted = true,
        oneTimeOverrideDate = null,
        oneTimeOverrideWindow = null,
        emergencyContacts = contacts,
        accountabilityMessage = message,
        targetSleepMinutes = 480,
        lastNotifiedContact = lastNotifiedContact
    )

    @Test
    fun `does nothing when there are no emergency contacts`() = runTest {
        ShadowLog.clear()
        AccountabilityNotifier.notifyMissedWakeup(context, repository, settings(emptyList()))

        assertNoAttemptToSend()
    }

    @Test
    fun `does nothing when SEND_SMS is not granted`() = runTest {
        shadowOf(context).denyPermissions(Manifest.permission.SEND_SMS)
        ShadowLog.clear()

        AccountabilityNotifier.notifyMissedWakeup(context, repository, settings(listOf("+33612345678")))

        assertNoAttemptToSend()
    }

    @Test
    fun `attempts a send once contacts are configured and permission is granted`() = runTest {
        // The reverse of the two skip guards above: this doesn't assert the send itself
        // succeeds (see the class doc), only that both guards correctly let it *through* to
        // the point of actually trying — i.e. neither guard is accidentally over-broad.
        shadowOf(context).grantPermissions(Manifest.permission.SEND_SMS)
        ShadowLog.clear()

        AccountabilityNotifier.notifyMissedWakeup(context, repository, settings(listOf("+33612345678")))

        val logs = ShadowLog.getLogs()
        assertTrueLogged(logs, "notifyMissedWakeup called, 1 contact(s) configured")
    }

    @Test
    fun `records the picked contact as last notified even when the send itself fails`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.SEND_SMS)

        AccountabilityNotifier.notifyMissedWakeup(context, repository, settings(listOf("+33612345678")))

        org.junit.Assert.assertEquals("+33612345678", repository.settings.first().lastNotifiedContact)
    }

    @Test
    fun `pickRecipient always returns the only contact when just one is configured`() {
        repeat(10) {
            org.junit.Assert.assertEquals(
                "+33612345678",
                AccountabilityNotifier.pickRecipient(listOf("+33612345678"), lastNotified = "+33612345678")
            )
        }
    }

    @Test
    fun `pickRecipient never repeats the last notified contact when others are available`() {
        val contacts = listOf("a", "b", "c")
        repeat(50) {
            val picked = AccountabilityNotifier.pickRecipient(contacts, lastNotified = "a")
            org.junit.Assert.assertNotEquals("a", picked)
            org.junit.Assert.assertTrue(picked in contacts)
        }
    }

    @Test
    fun `pickRecipient with no prior notification picks from the full list`() {
        val contacts = listOf("a", "b", "c")
        val picked = AccountabilityNotifier.pickRecipient(contacts, lastNotified = null)
        org.junit.Assert.assertTrue(picked in contacts)
    }

    private fun assertNoAttemptToSend() {
        val logs = ShadowLog.getLogs()
        org.junit.Assert.assertTrue(
            "expected a skip to be logged",
            logs.any { it.msg.startsWith("skipped:") }
        )
    }

    private fun assertTrueLogged(logs: List<org.robolectric.shadows.ShadowLog.LogItem>, fragment: String) {
        org.junit.Assert.assertTrue(
            "expected a log entry containing \"$fragment\", got: ${logs.map { it.msg }}",
            logs.any { it.msg.contains(fragment) }
        )
    }
}
