package com.reveil.aube.alarm

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.reveil.aube.ringing.AlarmActivity
import com.reveil.aube.tracking.SleepTrackingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [AlarmReceiver] is the exact-alarm entry point for both halves of the schedule
 * [AlarmScheduler] sets up — this locks in which service each action is meant to reach,
 * since a typo in either branch would silently misroute a real wake-up.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val receiver = AlarmReceiver()

    @Test
    fun `ACTION_START_TRACKING starts SleepTrackingService with the window extras`() {
        val intent = Intent(ACTION_START_TRACKING).apply {
            putExtra(EXTRA_WINDOW_EARLIEST_MILLIS, 1_000L)
            putExtra(EXTRA_WINDOW_LATEST_MILLIS, 2_000L)
            putExtra(EXTRA_DAWN_DURATION_MINUTES, 15)
        }

        receiver.onReceive(context, intent)

        val started = shadowOf(context).nextStartedService
        assertNotNull(started)
        assertEquals(SleepTrackingService::class.java.name, started.component?.className)
        assertEquals(1_000L, started.getLongExtra(EXTRA_WINDOW_EARLIEST_MILLIS, -1L))
        assertEquals(2_000L, started.getLongExtra(EXTRA_WINDOW_LATEST_MILLIS, -1L))
        assertEquals(15, started.getIntExtra(EXTRA_DAWN_DURATION_MINUTES, -1))
    }

    @Test
    fun `ACTION_FIRE_ALARM stops the tracking service and launches the alarm screen`() {
        val intent = Intent(ACTION_FIRE_ALARM).apply {
            putExtra(EXTRA_WINDOW_LATEST_MILLIS, 5_000L)
        }

        receiver.onReceive(context, intent)

        val stopped = shadowOf(context).nextStoppedService
        assertNotNull("expected the safety net to stop the tracking service", stopped)
        assertEquals(SleepTrackingService::class.java.name, stopped.component?.className)

        val startedActivity = shadowOf(context).nextStartedActivity
        assertNotNull("expected the safety net to launch the alarm screen", startedActivity)
        assertEquals(AlarmActivity::class.java.name, startedActivity.component?.className)
    }

    @Test
    fun `an unrecognized action is ignored`() {
        receiver.onReceive(context, Intent("com.reveil.aube.action.SOMETHING_ELSE"))

        assertEquals(null, shadowOf(context).peekNextStartedService())
        assertEquals(null, shadowOf(context).peekNextStartedActivity())
    }
}
