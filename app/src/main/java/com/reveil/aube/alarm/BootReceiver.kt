package com.reveil.aube.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reveil.aube.ringing.launchAlarm
import com.reveil.aube.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Exact alarms don't survive a reboot (or an app update on some OEMs) — reschedule them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settingsRepository = SettingsRepository(appContext)
                if (settingsRepository.isAlarmRingingUnresolved()) {
                    // The alarm was ringing (or in its dawn ramp) with no real dismiss when
                    // the device went down — most likely a reboot used specifically to kill
                    // the ringing service, which works regardless of any in-app hardening.
                    // Coming back with "see you tomorrow" would make a reboot the easiest
                    // bypass of all; instead it rings again immediately, at full volume —
                    // the same no-time-left path a safety net takes.
                    val now = System.currentTimeMillis()
                    launchAlarm(appContext, dawnStartMillis = now, dawnEndMillis = now)
                } else {
                    val settings = settingsRepository.settings.first()
                    AlarmScheduler(appContext).scheduleNext(settings)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
