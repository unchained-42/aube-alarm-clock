package com.reveil.aube.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.reveil.aube.charge.ChargeGuard
import com.reveil.aube.ringing.launchAlarm
import com.reveil.aube.ringing.resumeAlarmAfterBoot
import com.reveil.aube.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

private const val TAG = "AubeBootReceiver"

/** Exact alarms don't survive a reboot (or an app update on some OEMs) — reschedule them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        Log.i(TAG, "onReceive: ${intent.action}")
        val appContext = context.applicationContext
        // LOCKED_BOOT_COMPLETED arrives before BOOT_COMPLETED — on this device's OEM build,
        // tens of seconds before: BOOT_COMPLETED was measured landing ~50 s after boot, and
        // for a ring being resumed after a forced reboot every one of those seconds is free
        // silence. It's only usable when the user's storage is already unlocked (no lock
        // screen credential — which hardcore mode asks for anyway); otherwise the settings
        // can't be read yet and BOOT_COMPLETED does the job later as before.
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            !appContext.getSystemService(UserManager::class.java).isUserUnlocked
        ) {
            Log.i(TAG, "locked boot with storage still locked, waiting for BOOT_COMPLETED")
            return
        }
        // Both boot broadcasts reach the same process on a normal boot. Handling the second
        // would resume the ring a second time.
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (bootHandledInThisProcess) {
                Log.i(TAG, "boot already handled in this process, ignoring ${intent.action}")
                return
            }
            bootHandledInThisProcess = true
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settingsRepository = SettingsRepository(appContext)
                handleBoot(appContext, settingsRepository)
                // Off the ring path on purpose: a guard failure must never cost the resume.
                runCatching { ChargeGuard.evaluate(appContext, settingsRepository) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        @Volatile
        private var bootHandledInThisProcess = false

        /**
         * The actual reboot-recovery decision, pulled out of [onReceive]'s goAsync/coroutine
         * scaffolding so it's a plain suspend function a test can call and await directly —
         * onReceive itself launches this on Dispatchers.IO with no signal a test could wait on
         * otherwise.
         */
        internal suspend fun handleBoot(context: Context, settingsRepository: SettingsRepository) {
            val now = System.currentTimeMillis()
            if (settingsRepository.isAlarmRingingUnresolved()) {
                Log.i(TAG, "branch: was ringing when device went down, resuming")
                // The alarm was ringing (or in its dawn ramp) with no real dismiss when
                // the device went down — most likely powered off specifically to kill the
                // ringing service, which works regardless of any in-app hardening. Coming
                // back with "see you tomorrow" would make powering off the easiest bypass
                // of all; instead it rings again immediately, at full volume, the same
                // no-time-left path a safety net takes.
                val settings = settingsRepository.settings.first()
                // Sound first, from the service, with the screen following a few seconds
                // later — see resumeAlarmAfterBoot's doc for why a full-screen notification
                // alone wasn't enough here.
                resumeAlarmAfterBoot(context, settings.musicUri, settings.vibrationEnabled)
            } else {
                val settings = settingsRepository.settings.first()
                val scheduler = AlarmScheduler(context)
                val missed = scheduler.missedWindowSinceLastHandled(settings, ZonedDateTime.now())
                Log.i(TAG, "branch: not ringing when device went down, missedWindow=$missed")
                if (missed) {
                    // Never actually started ringing — the exact alarm simply never fired
                    // because the device was powered off straight through the deadline.
                    // The other branch above only catches a reboot *during* an active
                    // ring; this is the same bypass one step earlier: turn the phone off
                    // before the alarm, back on once you're already up, and a plain
                    // reschedule would silently arm tomorrow instead, with today's alarm
                    // never having rung at all. So it rings now instead.
                    // directLaunch=false: see launchAlarm's doc — a direct startActivity()
                    // this soon after boot lost a focus race against the lock screen's own
                    // window (confirmed via the ANR trace), so this leans on the notification's
                    // full-screen intent alone here instead.
                    launchAlarm(context, dawnStartMillis = now, dawnEndMillis = now, directLaunch = false)
                } else {
                    scheduler.scheduleNext(settings)
                }
            }
        }
    }
}
