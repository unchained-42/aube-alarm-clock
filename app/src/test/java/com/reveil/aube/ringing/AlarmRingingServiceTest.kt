package com.reveil.aube.ringing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
}
