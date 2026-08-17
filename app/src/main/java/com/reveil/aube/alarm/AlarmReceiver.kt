package com.reveil.aube.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.reveil.aube.ringing.launchAlarm
import com.reveil.aube.tracking.SleepTrackingService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_TRACKING -> {
                val earliest = intent.getLongExtra(EXTRA_WINDOW_EARLIEST_MILLIS, 0L)
                val latest = intent.getLongExtra(EXTRA_WINDOW_LATEST_MILLIS, 0L)
                val dawnMinutes = intent.getIntExtra(EXTRA_DAWN_DURATION_MINUTES, 25)
                val serviceIntent = Intent(context, SleepTrackingService::class.java).apply {
                    putExtra(EXTRA_WINDOW_EARLIEST_MILLIS, earliest)
                    putExtra(EXTRA_WINDOW_LATEST_MILLIS, latest)
                    putExtra(EXTRA_DAWN_DURATION_MINUTES, dawnMinutes)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }

            ACTION_FIRE_ALARM -> {
                // Safety net: fires regardless of what the tracking service decided — it may
                // never have been started, may have been killed overnight, or may simply
                // never have seen a confident light-sleep signal before the deadline. No time
                // left for a dawn ramp at this point, so it rings immediately.
                context.stopService(Intent(context, SleepTrackingService::class.java))
                val now = System.currentTimeMillis()
                launchAlarm(context, dawnStartMillis = now, dawnEndMillis = now)
            }
        }
    }
}
