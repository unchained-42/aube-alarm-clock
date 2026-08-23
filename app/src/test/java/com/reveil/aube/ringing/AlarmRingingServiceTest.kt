package com.reveil.aube.ringing

import android.app.Application
import android.app.Service
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Guards against a silent, do-nothing "Time to get up" notification stuck in the tray for
 * good — confirmed on a real device via `dumpsys activity services` / `dumpsys power`: the
 * service still `isForeground=true`, holding its `Aube:AlarmRinging` wake lock, hours after
 * the alarm should have been dismissed, with no sound and no alarm screen anywhere.
 *
 * The cause: [AlarmRingingService] returns `START_STICKY` so a process kill *while actively
 * ringing* correctly resumes. But Android redelivers that restart as a null `Intent` to a
 * brand-new service instance — every field, including `dawnEndMillis`, is back at its
 * declared default — and the auto-stop watchdog's `if (end <= 0L) continue` guard means it
 * never fires again for that instance. Nothing was left to notice the window info was gone
 * and recover it.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmRingingServiceTest {

    @Test
    fun `a sticky restart with no window state relaunches the alarm instead of sitting silent`() {
        val controller = Robolectric.buildService(AlarmRingingService::class.java)
        val service = controller.create().get()

        // Exactly what the OS delivers when it recreates a killed START_STICKY service: a
        // null Intent, on a fresh instance that never learned the ringing window.
        service.onStartCommand(null, 0, 1)

        val nextActivity = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertNotNull("expected the sticky restart to relaunch the alarm screen", nextActivity)
        assertEquals(AlarmActivity::class.java.name, nextActivity.component?.className)
    }

    @Test
    fun `a real command establishing the window is not mistaken for a bare restart later`() {
        val controller = Robolectric.buildService(AlarmRingingService::class.java)
        val service = controller.create().get()

        val now = System.currentTimeMillis()
        val realCommand = android.content.Intent().apply {
            putExtra(EXTRA_DAWN_START_MILLIS, now)
            putExtra(EXTRA_DAWN_END_MILLIS, now + 60_000L)
        }
        service.onStartCommand(realCommand, 0, 1)
        // A legitimate command carrying the window must not itself trigger a relaunch.
        assertNull(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)

        // A later, unrelated null-intent redelivery (e.g. a benign system requery) must not
        // be mistaken for the bare-restart case now that real state exists.
        service.onStartCommand(null, 0, 2)
        assertNull(
            "a null intent after real window state was already known must not relaunch the alarm",
            shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        )
    }

    /**
     * Reproduces the on-device bug where every QR dismiss was immediately followed by the
     * alarm ringing again: [AlarmActivity] is `singleInstance`/`excludeFromRecents`, so
     * finish()-ing it (the last step of a real dismiss) tears down its own sole task and
     * fires [AlarmRingingService.onTaskRemoved] — indistinguishable at the OS level from the
     * user swiping it away unscanned. `dismissRequested` exists precisely so a legitimate
     * dismiss can tell the two apart.
     */
    @Test
    fun `onTaskRemoved does not relaunch the alarm once a real dismiss has been recorded`() {
        val controller = Robolectric.buildService(AlarmRingingService::class.java)
        val service = controller.create().get()

        // What AlarmActivity.handleDismissed() now does synchronously, before finish() can
        // possibly tear down the task and reach this callback.
        AlarmRingingService.dismissRequested = true

        service.onTaskRemoved(null)

        assertNull(
            "a legitimate dismiss must not be undone by the task-removal callback it triggers",
            shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        )
    }

    /** The reverse of the above: task removal with no dismiss recorded is still a real
     * "swiped away unscanned" case, and must still bring the ringing screen back. */
    @Test
    fun `onTaskRemoved relaunches the alarm when no dismiss was ever recorded`() {
        val controller = Robolectric.buildService(AlarmRingingService::class.java)
        val service = controller.create().get()

        service.onTaskRemoved(null)

        val nextActivity = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertNotNull("expected an unscanned task removal to relaunch the alarm screen", nextActivity)
        assertEquals(AlarmActivity::class.java.name, nextActivity.component?.className)
    }

    /** onCreate() must reset the companion flag — otherwise a ring that ended via
     * autoStopUnresolved (or any prior cycle) would leave the next day's fresh instance
     * looking like it was already dismissed before it ever started. */
    @Test
    fun `a fresh instance is never mistaken for one that was already dismissed`() {
        AlarmRingingService.dismissRequested = true

        Robolectric.buildService(AlarmRingingService::class.java).create().get()

        assertFalse(AlarmRingingService.dismissRequested)
    }

    /**
     * Reproduces the regression this fix's predecessor shipped: AlarmActivity sets
     * `dismissRequested` (to protect against the onTaskRemoved race above) *before* sending
     * ACTION_DISMISS. If ACTION_DISMISS's own handling reused that same flag for
     * onStartCommand's "ignore late commands" guard, it would see it already true and bail
     * out immediately — the real dismiss command itself silently ignored, sound and the
     * foreground notification left running forever. Confirmed on a real device: the QR scan
     * screen closed normally, but the alarm kept ringing until the phone was powered off.
     */
    @Test
    fun `a real dismiss command is processed even though dismissRequested was already set`() {
        val controller = Robolectric.buildService(AlarmRingingService::class.java)
        val service = controller.create().get()

        // Establishes a real ringing window first, exactly like AlarmActivity's initial
        // "register window" call — otherwise this would hit the unrelated bare-restart
        // recovery branch instead of the dismiss path being tested here.
        val now = System.currentTimeMillis()
        service.onStartCommand(
            android.content.Intent().apply {
                putExtra(EXTRA_DAWN_START_MILLIS, now)
                putExtra(EXTRA_DAWN_END_MILLIS, now + 60_000L)
            },
            0,
            1
        )

        // What AlarmActivity.handleDismissed() does, in order: set the flag, then send the
        // dismiss command.
        AlarmRingingService.dismissRequested = true
        val result = service.onStartCommand(
            android.content.Intent(AlarmRingingService.ACTION_DISMISS),
            0,
            2
        )

        assertEquals(
            "the dismiss command itself must be handled, not swallowed by the late-command guard",
            Service.START_STICKY,
            result
        )
    }
}
