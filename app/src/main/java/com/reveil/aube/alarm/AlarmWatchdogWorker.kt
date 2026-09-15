package com.reveil.aube.alarm

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.reveil.aube.charge.ChargeGuard
import com.reveil.aube.ringing.AlarmRingingService
import com.reveil.aube.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Every normal cycle re-arms itself — a dismiss, an auto-stop, a reboot recovery all end by
 * calling [AlarmScheduler.scheduleNext]. But every one of those is *reached* by something
 * happening first: a ring, a device restart. If a cycle is ever interrupted before its
 * scheduleNext() call lands (a crash mid-dismiss — see AlarmRingingService's ACTION_DISMISS
 * doc for a real, on-device instance of exactly this), nothing is left armed in AlarmManager,
 * and nothing was going to notice short of the user opening the app themselves — which they
 * have no reason to do on a day the alarm never even rang.
 *
 * This is the backstop for that gap: a periodic, no-UI check that re-affirms the schedule
 * unconditionally, on a cadence tight enough that a broken cycle can't silently cost more
 * than a few hours before it's caught and rearmed — running via WorkManager rather than
 * AlarmManager specifically because WorkManager's own periodic jobs persist across reboots on
 * their own, so this needs no BootReceiver wiring to keep working.
 */
class AlarmWatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result = runBlocking {
        // Independent of the alarm state below: the guard has its own "not while ringing" rule.
        runCatching { ChargeGuard.evaluate(applicationContext) }
        // Never touch AlarmManager while a ring is genuinely in progress or still waiting to
        // be resumed — scheduleNext()'s cancelAll() would tear down state that a real ring
        // (or BootReceiver's own resume logic) still needs.
        if (AlarmRingingService.isActive) return@runBlocking Result.success()
        val settingsRepository = SettingsRepository(applicationContext)
        if (settingsRepository.isAlarmRingingUnresolved()) return@runBlocking Result.success()

        val settings = settingsRepository.settings.first()
        if (settings.alarmEnabled) AlarmScheduler(applicationContext).scheduleNext(settings)
        Result.success()
    }
}
