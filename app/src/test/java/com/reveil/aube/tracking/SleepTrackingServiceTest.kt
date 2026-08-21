package com.reveil.aube.tracking

import android.content.Intent
import com.reveil.aube.alarm.EXTRA_DAWN_DURATION_MINUTES
import com.reveil.aube.alarm.EXTRA_WINDOW_EARLIEST_MILLIS
import com.reveil.aube.alarm.EXTRA_WINDOW_LATEST_MILLIS
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Guards against the "Aube surveille ton sommeil" notification outliving the alarm it was
 * tracking for. [SleepTrackingService] hands off to the ringer via `stopSelf()` (or gets
 * stopped externally by [com.reveil.aube.alarm.AlarmReceiver]'s safety net) every single
 * morning — a foreground service ending, by either path, does *not* automatically cancel the
 * notification `startForeground()` posted; only an explicit
 * `stopForeground(STOP_FOREGROUND_REMOVE)` does. Skipping that call is exactly the kind of
 * regression that's easy to reintroduce while touching this file, and easy to miss without a
 * device in hand the next morning.
 *
 * Robolectric's own [org.robolectric.shadows.ShadowService] quietly cancels the notification
 * on `destroy()` regardless of what the app actually called — real devices don't — so this
 * asserts against the shadow's record of whether `stopForeground(remove = true)` was called,
 * not against notification-manager state, which would pass either way.
 */
@RunWith(RobolectricTestRunner::class)
class SleepTrackingServiceTest {

    @Test
    fun `service removes its foreground notification when it hands off to the ringer`() {
        val now = System.currentTimeMillis()
        val intent = Intent().apply {
            putExtra(EXTRA_WINDOW_EARLIEST_MILLIS, now)
            // Latest just barely in the future so the service takes the normal path (not the
            // immediate-fire branch), but a dawn duration far longer than that gap means the
            // "dawn should already be underway" branch fires straight from onStartCommand,
            // with no sensor involved.
            putExtra(EXTRA_WINDOW_LATEST_MILLIS, now + 1_000L)
            putExtra(EXTRA_DAWN_DURATION_MINUTES, 25)
        }

        val controller = Robolectric.buildService(SleepTrackingService::class.java, intent)
        val service = controller.create().startCommand(0, 0).get()
        controller.destroy()

        assertTrue(
            "expected stopForeground(STOP_FOREGROUND_REMOVE) to have been called before the service stopped",
            shadowOf(service).notificationShouldRemoved
        )
    }
}
