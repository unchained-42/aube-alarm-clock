package com.reveil.aube.alarm

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.reveil.aube.settings.AlarmSettings
import com.reveil.aube.settings.WakeWindow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * The one consequence left once a phone is off (or otherwise unreachable) straight through
 * the deadline — see [AccountabilityNotifier]'s doc comment. Covers the two silent-skip
 * guards, which never touch the platform SMS stack and so are safe to assert on directly.
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

    private fun settings(contacts: List<String>, message: String? = null) = AlarmSettings(
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
        targetSleepMinutes = 480
    )

    @Test
    fun `does nothing when there are no emergency contacts`() {
        ShadowLog.clear()
        AccountabilityNotifier.notifyMissedWakeup(context, settings(emptyList()))

        assertNoAttemptToSend()
    }

    @Test
    fun `does nothing when SEND_SMS is not granted`() {
        shadowOf(context).denyPermissions(Manifest.permission.SEND_SMS)
        ShadowLog.clear()

        AccountabilityNotifier.notifyMissedWakeup(context, settings(listOf("+33612345678")))

        assertNoAttemptToSend()
    }

    @Test
    fun `attempts a send once contacts are configured and permission is granted`() {
        // The reverse of the two skip guards above: this doesn't assert the send itself
        // succeeds (see the class doc), only that both guards correctly let it *through* to
        // the point of actually trying — i.e. neither guard is accidentally over-broad.
        shadowOf(context).grantPermissions(Manifest.permission.SEND_SMS)
        ShadowLog.clear()

        AccountabilityNotifier.notifyMissedWakeup(context, settings(listOf("+33612345678")))

        val logs = ShadowLog.getLogs()
        assertTrueLogged(logs, "notifyMissedWakeup called, 1 contact(s) configured")
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
