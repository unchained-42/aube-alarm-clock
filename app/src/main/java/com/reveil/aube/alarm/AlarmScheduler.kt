package com.reveil.aube.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.reveil.aube.settings.AlarmSettings
import java.time.DayOfWeek
import java.time.ZonedDateTime

const val ACTION_START_TRACKING = "com.reveil.aube.action.START_TRACKING"
const val ACTION_FIRE_ALARM = "com.reveil.aube.action.FIRE_ALARM"
const val EXTRA_WINDOW_EARLIEST_MILLIS = "extra_window_earliest_millis"
const val EXTRA_WINDOW_LATEST_MILLIS = "extra_window_latest_millis"
const val EXTRA_DAWN_DURATION_MINUTES = "extra_dawn_duration_minutes"
const val EXTRA_SOURCE = "extra_source"
const val SOURCE_SAFETY_NET = "safety_net"
const val SOURCE_TRACKING_SERVICE = "tracking_service"

/**
 * Computes the next wake window from [AlarmSettings] and schedules two exact alarms:
 * one to start [com.reveil.aube.tracking.SleepTrackingService] ahead of the window so it
 * has time to establish a movement baseline, and a hard safety-net alarm at the window's
 * latest minute that fires the ringer regardless of whether the tracking service ever
 * detected a confident light-sleep signal (it may have been killed, or never seen one).
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true
    }

    fun scheduleNext(settings: AlarmSettings) {
        cancelAll()
        if (!settings.alarmEnabled) return

        val now = ZonedDateTime.now()
        val (earliest, latest) = nextWindow(settings, now)
        val trackingStart = earliest.minusMinutes(settings.trackingLeadMinutes.toLong())

        setExact(
            trackingStart.toInstant().toEpochMilli(),
            trackingPendingIntent(
                earliest.toInstant().toEpochMilli(),
                latest.toInstant().toEpochMilli(),
                settings.dawnDurationMinutes
            )
        )
        setExact(
            latest.toInstant().toEpochMilli(),
            safetyNetPendingIntent(latest.toInstant().toEpochMilli())
        )
    }

    /** For UI display only — same computation [scheduleNext] uses internally. */
    fun previewNextWindow(settings: AlarmSettings): Pair<ZonedDateTime, ZonedDateTime> =
        nextWindow(settings, ZonedDateTime.now())

    fun cancelAll() {
        alarmManager.cancel(trackingPendingIntent(0L, 0L, 0))
        alarmManager.cancel(safetyNetPendingIntent(0L))
    }

    /**
     * Finds the next window whose latest minute has not already passed, up to 8 days out.
     * Skips any day at or before [AlarmSettings.lastHandledDate] — otherwise dismissing
     * early in the window (before its own latest minute) would find "today" still
     * technically valid and re-arm the very alarm that was just dismissed.
     */
    private fun nextWindow(settings: AlarmSettings, now: ZonedDateTime): Pair<ZonedDateTime, ZonedDateTime> {
        for (dayOffset in 0..7) {
            val date = now.toLocalDate().plusDays(dayOffset.toLong())
            if (settings.lastHandledDate != null && !date.isAfter(settings.lastHandledDate)) continue
            val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            val window = settings.windowFor(date, isWeekend)
            val dayStart = date.atStartOfDay(now.zone)
            val latest = dayStart.plusMinutes(window.latestMinute.toLong())
            if (latest.isAfter(now)) {
                val earliest = dayStart.plusMinutes(window.earliestMinute.toLong())
                return earliest to latest
            }
        }
        val fallback = now.plusDays(1)
        return fallback to fallback
    }

    private fun setExact(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // Falls back to an inexact alarm rather than crashing; the UI should be
            // steering the user to grant exact-alarm permission before this happens.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            return
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }

    private fun trackingPendingIntent(earliestMillis: Long, latestMillis: Long, dawnDurationMinutes: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_START_TRACKING
            putExtra(EXTRA_WINDOW_EARLIEST_MILLIS, earliestMillis)
            putExtra(EXTRA_WINDOW_LATEST_MILLIS, latestMillis)
            putExtra(EXTRA_DAWN_DURATION_MINUTES, dawnDurationMinutes)
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE_TRACKING, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun safetyNetPendingIntent(latestMillis: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE_ALARM
            putExtra(EXTRA_WINDOW_LATEST_MILLIS, latestMillis)
            putExtra(EXTRA_SOURCE, SOURCE_SAFETY_NET)
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE_SAFETY_NET, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val REQUEST_CODE_TRACKING = 1001
        private const val REQUEST_CODE_SAFETY_NET = 1002
    }
}
