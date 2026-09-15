package com.reveil.aube.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.reveil.aube.alarm.AlarmScheduler
import com.reveil.aube.charge.ChargeGuard
import com.reveil.aube.ringing.AlarmRingingService
import com.reveil.aube.ringing.dismissAlarmNotification
import com.reveil.aube.ringing.launchAlarm
import com.reveil.aube.settings.SettingsRepository
import com.reveil.aube.settings.WakeWindow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/**
 * Debug-only adb hooks. FIRE starts a full-volume ring right now, exactly like a missed
 * deadline. STOP is the emergency exit a device-owner install otherwise doesn't have — the
 * package can't be force-stopped, even over adb — and does what a real QR dismiss does.
 */
class DebugFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        when (intent.action) {
            ACTION_FIRE -> launchAlarm(app, dawnStartMillis = now, dawnEndMillis = now)
            ACTION_GUARD -> runBlocking { ChargeGuard.evaluate(app) }
            // adb shell am broadcast -a com.reveil.aube.debug.CONFIG --ei latest 600 --ei earliest 570 \
            //   --ei sleep 480 --ez weekend false -n com.reveil.aube/.debug.DebugFireReceiver
            ACTION_CONFIG -> runBlocking {
                val repo = SettingsRepository(app)
                val latest = intent.getIntExtra("latest", 10 * 60)
                val earliest = intent.getIntExtra("earliest", latest - 30)
                val window = WakeWindow(earliestMinute = earliest, latestMinute = latest)
                repo.setWeekdayWindow(window)
                repo.setWeekendWindow(window)
                repo.setUseSeparateWeekend(intent.getBooleanExtra("weekend", false))
                repo.setTargetSleepMinutes(intent.getIntExtra("sleep", 480))
                repo.setOnboardingCompleted(true)
                repo.setAlarmEnabled(true)
                val settings = repo.settings.first()
                AlarmScheduler(app).scheduleNext(settings)
                Log.i(TAG, "CONFIG applied: $settings")
            }
            ACTION_STOP -> {
                Log.i(TAG, "STOP: serviceActive=${AlarmRingingService.isActive}")
                val repo = SettingsRepository(app)
                runBlocking {
                    repo.setAlarmRinging(false)
                    repo.setLastHandledDate(LocalDate.now())
                }
                AlarmScheduler(app).cancelAll()
                dismissAlarmNotification(app)
                if (AlarmRingingService.isActive) {
                    AlarmRingingService.dismissRequested = true
                    app.startService(
                        Intent(app, AlarmRingingService::class.java).apply { action = AlarmRingingService.ACTION_DISMISS }
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "AubeDebugFire"
        const val ACTION_FIRE = "com.reveil.aube.debug.FIRE"
        const val ACTION_STOP = "com.reveil.aube.debug.STOP"
        const val ACTION_GUARD = "com.reveil.aube.debug.GUARD"
        const val ACTION_CONFIG = "com.reveil.aube.debug.CONFIG"
    }
}
