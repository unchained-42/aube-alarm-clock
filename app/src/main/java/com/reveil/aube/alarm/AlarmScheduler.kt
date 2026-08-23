package com.reveil.aube.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.reveil.aube.NotifChannels
import com.reveil.aube.R
import com.reveil.aube.permissions.PermissionsHelper
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

    /**
     * True if an unhandled day's deadline has already passed as of [now] — the device was off
     * (or otherwise unable to fire the exact alarm) straight through it. [BootReceiver] calls
     * this before falling back to a plain reschedule: without it, powering the phone off before
     * the alarm and back on after simply finds "next window still in the future" and quietly
     * re-arms for the following day, with nothing having ever rung. Bounded to the last day
     * (not scanning back to [AlarmSettings.lastHandledDate] indefinitely) so a phone left off
     * for a week doesn't try to catch up on every missed day at once — one ring on the next
     * boot is the point, not a backlog.
     */
    fun missedWindowSinceLastHandled(settings: AlarmSettings, now: ZonedDateTime): Boolean {
        if (!settings.alarmEnabled) return false
        val today = now.toLocalDate()
        val start = maxOf(today.minusDays(1), (settings.lastHandledDate?.plusDays(1)) ?: today.minusDays(1))
        var date = start
        while (!date.isAfter(today)) {
            val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            val window = settings.windowFor(date, isWeekend)
            val dayStart = date.atStartOfDay(now.zone)
            val latest = dayStart.plusMinutes(window.latestMinute.toLong())
            if (latest.isBefore(now)) return true
            date = date.plusDays(1)
        }
        return false
    }

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
            // Falls back to an inexact alarm rather than crashing. The UI gates turning the
            // alarm on in the first place on this same permission (missingBlockingChecks), so
            // reaching this branch means it was granted at enable time and got revoked later —
            // Android allows toggling it anytime, with no callback to this app when it happens.
            // Silently degrading an alarm app's timing guarantee isn't safe to leave unnoticed,
            // so this surfaces it instead of only logging it.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            notifyExactAlarmDegraded()
            return
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }

    /**
     * setOnlyAlertOnce means a second scheduleNext() while still revoked (the very next day,
     * say) silently updates the same notification instead of re-alerting — seen once is enough
     * until the user actually deals with it, same idiom as [com.reveil.aube.ringing.launchAlarm].
     */
    private fun notifyExactAlarmDegraded() {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val pendingIntent = PendingIntent.getActivity(
            context, REQUEST_CODE_EXACT_ALARM_SETTINGS,
            PermissionsHelper.exactAlarmSettingsIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NotifChannels.ROUTINE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.notif_exact_alarm_degraded_title))
            .setContentText(context.getString(R.string.notif_exact_alarm_degraded_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_exact_alarm_degraded_text)))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID_EXACT_ALARM_DEGRADED, notification)
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
        private const val REQUEST_CODE_EXACT_ALARM_SETTINGS = 1003
        private const val NOTIF_ID_EXACT_ALARM_DEGRADED = 45
    }
}
