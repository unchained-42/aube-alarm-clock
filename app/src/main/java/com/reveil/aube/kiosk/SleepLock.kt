package com.reveil.aube.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.reveil.aube.alarm.AlarmScheduler
import com.reveil.aube.ringing.AlarmRingingService
import com.reveil.aube.settings.AlarmSettings
import java.time.LocalDate
import java.time.ZonedDateTime

private const val TAG = "AubeSleepLock"

/**
 * Hardcore mode's answer to "just power it off before going to sleep, or at 3 am": the phone
 * can't be turned off while the alarm rings (lock task, see [KioskPolicy]) — but until now
 * it could be at any other time, including the whole night before. So, as device owner only,
 * from the derived bedtime (wake deadline minus target sleep) until the ring takes over,
 * [SleepLockActivity] sits pinned in the same lock task: a black screen with a dim clock,
 * no power menu, no Home, no Recents. The screen still turns off normally; waking it just
 * shows the night screen again.
 *
 * Armed from [AlarmScheduler.scheduleNext] (so it follows every reschedule) and re-checked
 * on boot and app start. A no-op without device ownership: plain screen pinning would show
 * a confirmation dialog and still be unpinnable, i.e. theatre.
 */
object SleepLock {

    const val ACTION_START = "com.reveil.aube.action.SLEEP_LOCK_START"
    /** App-internal: tells a running [SleepLockActivity] to re-check whether it should still be up. */
    const val ACTION_RECHECK = "com.reveil.aube.action.SLEEP_LOCK_RECHECK"
    /**
     * Boolean extra on [ACTION_RECHECK]: the ring just ended, leave unconditionally. Sent by
     * [AlarmRingingService] — while it's still alive, so [shouldBeLocked]'s "not during a
     * ring" rule can't be what decides; and before the settings write recording the day as
     * handled has necessarily landed.
     */
    const val EXTRA_RING_ENDED = "extra_ring_ended"
    private const val REQUEST_START = 4601

    /** The night this lock covers: [bedtime, latest] of the next wake window. */
    fun window(context: Context, settings: AlarmSettings): Pair<ZonedDateTime, ZonedDateTime>? {
        if (!settings.alarmEnabled) return null
        val (_, latest) = AlarmScheduler(context).previewNextWindow(settings)
        return latest.minusMinutes(settings.targetSleepMinutes.toLong()) to latest
    }

    /**
     * Whether the night screen belongs on top right now. Not while a ring is active — the
     * alarm screen is the one pinned then — and not once today's alarm has been dismissed.
     * Decides whether to *start* the screen; a screen already up under an active ring must
     * not read this as "leave", see [SleepLockActivity].
     */
    fun shouldBeLocked(context: Context, settings: AlarmSettings, now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        if (!KioskPolicy.isDeviceOwner(context)) return false
        if (AlarmRingingService.isActive) return false
        if (settings.lastHandledDate == LocalDate.now()) return false
        val (bedtime, latest) = window(context, settings) ?: return false
        return !now.isBefore(bedtime) && !now.isAfter(latest)
    }

    /** Starts the night screen now if it's due, otherwise arms it for the next bedtime. */
    fun ensure(context: Context, settings: AlarmSettings) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(startPendingIntent(appContext))
        if (!KioskPolicy.isDeviceOwner(appContext)) return
        val window = window(appContext, settings) ?: return
        if (shouldBeLocked(appContext, settings)) {
            Log.i(TAG, "in sleep window, starting night screen")
            start(appContext)
            return
        }
        // A night screen already up (schedule just changed, alarm turned off) must leave.
        appContext.sendBroadcast(Intent(ACTION_RECHECK).setPackage(appContext.packageName))
        val bedtime = window.first
        if (bedtime.isAfter(ZonedDateTime.now())) {
            Log.i(TAG, "arming night screen for $bedtime")
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(bedtime.toInstant().toEpochMilli(), null),
                startPendingIntent(appContext)
            )
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(startPendingIntent(context.applicationContext))
    }

    fun start(context: Context) {
        val intent = Intent(context, SleepLockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "night screen launch refused", e)
        }
    }

    private fun startPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_START,
            Intent(context, SleepLockReceiver::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
