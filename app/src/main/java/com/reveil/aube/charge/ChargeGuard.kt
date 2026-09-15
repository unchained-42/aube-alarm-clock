package com.reveil.aube.charge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.reveil.aube.NotifChannels
import com.reveil.aube.R
import com.reveil.aube.alarm.AlarmScheduler
import com.reveil.aube.ringing.AlarmRingingService
import com.reveil.aube.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime

private const val TAG = "AubeChargeGuard"

/**
 * Keeps the phone from ever dying of a flat battery — the one "off" hardcore mode can't
 * close. Polls rather than listens: Android refuses to deliver ACTION_POWER_DISCONNECTED (or
 * BATTERY_LOW) to a manifest receiver at all — "Background execution not allowed", confirmed
 * in logcat on a real device — so nothing about a cable coming out can wake this app. Instead
 * the guard always has its own next check armed: every [IDLE_INTERVAL_MS] while everything is
 * fine (worst-case detection latency, which is nothing next to a night), then at the nag
 * cadence from [ChargeGuardPolicy] once it isn't. Boot, app start and the watchdog also run
 * an evaluation, mostly to make sure that chain is armed at all.
 *
 * Nags are scheduled with [AlarmManager.setAlarmClock], not setExactAndAllowWhileIdle: the
 * latter is rate-limited to one firing per ~9 minutes per app once the phone is in Doze —
 * which a phone lying still on a nightstand is — and a "every minute" nag that actually
 * fires every nine isn't the deal. Alarm-clock alarms are exempt (the idle poll, where nine
 * minutes instead of five is fine, stays on the quieter while-idle variant).
 */
object ChargeGuard {

    private const val PREFS = "aube_charge_guard"
    private const val KEY_LAST_CHIRP = "last_chirp_millis"
    private const val NOTIF_ID = 45
    private const val REQUEST_CHECK = 4501
    // A check fired a hair early (alarm jitter, or two triggers close together) must still
    // count as "due" — otherwise a 60 s interval could turn into 120 s every other time.
    private const val DUE_SLACK_MS = 5_000L
    /** Poll cadence while nothing is wrong — the latency at which an unplug gets noticed. */
    const val IDLE_INTERVAL_MS = 5 * 60_000L

    data class PowerState(val batteryPercent: Int, val plugged: Boolean)

    fun readPowerState(context: Context): PowerState? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return PowerState(batteryPercent = level * 100 / scale, plugged = plugged)
    }

    /**
     * Bedtime is derived, not configured: the wake deadline minus the target sleep duration
     * (the same arithmetic as HomeScreen's sleep-duration lock). The reminder window starts
     * at the user's chosen [reminderMinute] and runs until bedtime — or for
     * [ChargeGuardPolicy.REMINDER_MIN_WINDOW_MINUTES] if that time is at/after bedtime, and
     * never longer than [ChargeGuardPolicy.REMINDER_MAX_WINDOW_MINUTES]. The reminder window
     * wins over SLEEP: a time the user explicitly picked is honoured even if it lands after
     * the derived bedtime. SLEEP is the rest of bedtime → wake window; no reminder configured
     * means no PRE_SLEEP at all.
     */
    internal fun phaseAt(
        now: ZonedDateTime,
        nextEarliest: ZonedDateTime,
        nextLatest: ZonedDateTime,
        targetSleepMinutes: Int,
        reminderMinute: Int?
    ): ChargeGuardPolicy.Phase {
        val bedtime = nextLatest.minusMinutes(targetSleepMinutes.toLong())
        if (reminderMinute != null) {
            val onBedtimeDay = bedtime.toLocalDate().atTime(reminderMinute / 60, reminderMinute % 60).atZone(bedtime.zone)
            // The reminder is meant for the evening leading up to this bedtime, which may be
            // "yesterday" relative to it (bedtime 00:30, reminder 22:00).
            for (start in listOf(onBedtimeDay, onBedtimeDay.minusDays(1))) {
                val untilBedtime = java.time.Duration.between(start, bedtime).toMinutes()
                val end = when {
                    untilBedtime in ChargeGuardPolicy.REMINDER_MIN_WINDOW_MINUTES..ChargeGuardPolicy.REMINDER_MAX_WINDOW_MINUTES -> bedtime
                    untilBedtime > ChargeGuardPolicy.REMINDER_MAX_WINDOW_MINUTES -> start.plusMinutes(ChargeGuardPolicy.REMINDER_MAX_WINDOW_MINUTES)
                    else -> start.plusMinutes(ChargeGuardPolicy.REMINDER_MIN_WINDOW_MINUTES)
                }
                if (!now.isBefore(start) && now.isBefore(end)) return ChargeGuardPolicy.Phase.PRE_SLEEP
            }
        }
        return if (!now.isBefore(bedtime) && now.isBefore(nextEarliest)) ChargeGuardPolicy.Phase.SLEEP else ChargeGuardPolicy.Phase.DAY
    }

    suspend fun evaluate(context: Context, settingsRepository: SettingsRepository = SettingsRepository(context)) {
        val appContext = context.applicationContext
        val power = readPowerState(appContext) ?: return
        val settings = settingsRepository.settings.first()
        var sleepEndsAt: ZonedDateTime? = null
        val phase = if (!settings.alarmEnabled) ChargeGuardPolicy.Phase.DAY else {
            val (earliest, latest) = AlarmScheduler(appContext).previewNextWindow(settings)
            sleepEndsAt = earliest
            phaseAt(ZonedDateTime.now(), earliest, latest, settings.targetSleepMinutes, settings.chargeReminderMinute)
        }
        val interval = ChargeGuardPolicy.nagIntervalMillis(power.batteryPercent, power.plugged, phase)
        Log.i(TAG, "battery=${power.batteryPercent}% plugged=${power.plugged} phase=$phase -> interval=$interval")

        val prefs = prefs(appContext)
        if (interval == null) {
            NotificationManagerCompat.from(appContext).cancel(NOTIF_ID)
            if (power.plugged) prefs.edit().remove(KEY_LAST_CHIRP).apply()
            // Asleep: nothing this guard could learn before the wake window would change
            // anything (it stays silent regardless), so it doesn't poll through the night
            // — the alarm's own tracking/ring wakes the app then anyway.
            val next = if (phase == ChargeGuardPolicy.Phase.SLEEP && sleepEndsAt != null) {
                sleepEndsAt.toInstant().toEpochMilli()
            } else {
                System.currentTimeMillis() + IDLE_INTERVAL_MS
            }
            scheduleIdleCheck(appContext, next)
            return
        }

        val now = System.currentTimeMillis()
        val lastChirp = prefs.getLong(KEY_LAST_CHIRP, 0L)
        if (now - lastChirp >= interval - DUE_SLACK_MS) {
            prefs.edit().putLong(KEY_LAST_CHIRP, now).apply()
            postNotification(appContext, power.batteryPercent)
            // A ring already blaring says everything a chirp would — and both share the
            // alarm stream's volume, which the two players would otherwise fight over.
            if (!AlarmRingingService.isActive) {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, ChargeNagService::class.java).apply {
                        putExtra(ChargeNagService.EXTRA_URI, settings.musicUri)
                        putExtra(ChargeNagService.EXTRA_VIBRATE, settings.vibrationEnabled)
                    }
                )
            }
        }
        scheduleNextCheck(appContext, now + interval)
    }

    private fun scheduleNextCheck(context: Context, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, null), checkPendingIntent(context))
    }

    private fun scheduleIdleCheck(context: Context, triggerAtMillis: Long) {
        // Same PendingIntent as the nag alarm, so whichever was set last is the only one armed.
        context.getSystemService(AlarmManager::class.java)
            .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, checkPendingIntent(context))
    }

    private fun checkPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CHECK,
            Intent(context, ChargeGuardReceiver::class.java).setAction(ChargeGuardReceiver.ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun postNotification(context: Context, batteryPercent: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val text = if (batteryPercent < ChargeGuardPolicy.LOW_BATTERY_PERCENT) {
            context.getString(R.string.charge_notif_text_low, batteryPercent.toString())
        } else {
            context.getString(R.string.charge_notif_text_night)
        }
        val notification = NotificationCompat.Builder(context, NotifChannels.CHARGE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle(context.getString(R.string.charge_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
    }

    // Device-protected storage, like the ringing flag: tiny, fsync'd, and readable before
    // first unlock — the guard should run from LOCKED_BOOT_COMPLETED onwards.
    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
