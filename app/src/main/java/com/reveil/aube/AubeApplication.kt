package com.reveil.aube

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.reveil.aube.alarm.AlarmWatchdogWorker
import com.reveil.aube.charge.ChargeGuard
import com.reveil.aube.kiosk.SleepLock
import com.reveil.aube.kiosk.KioskPolicy
import com.reveil.aube.settings.LocaleHelper
import com.reveil.aube.settings.SettingsRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Channel ids referenced across the app. */
object NotifChannels {
    const val TRACKING = "tracking"
    const val ALARM = "alarm"
    const val ROUTINE = "routine"
    const val CHARGE = "charge"
}

class AubeApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        scheduleAlarmWatchdog()
        // No-op unless this app is the device owner. Re-asserted on every process start (not
        // just at enrollment) so an update that adds a policy, or a policy an earlier run
        // missed, is in force before the next ring rather than after the next enrollment.
        KioskPolicy.applyHardening(this)
        evaluateChargeGuard()
    }

    /** Best-effort, same reasoning as [scheduleAlarmWatchdog]: every other trigger re-runs it. */
    private fun evaluateChargeGuard() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ChargeGuard.evaluate(applicationContext) }
            // Same trigger for the night screen: a process start inside the sleep window (a
            // crash, a reboot the boot receiver didn't catch) must put it back up.
            runCatching {
                val settings = SettingsRepository(applicationContext).settings.first()
                if (SleepLock.shouldBeLocked(applicationContext, settings)) SleepLock.start(applicationContext)
            }
        }
    }

    private fun scheduleAlarmWatchdog() {
        // WorkManager initializes itself via a manifest-merged ContentProvider on a real
        // device, but that provider never runs under Robolectric's test Application — every
        // unit test boots this same onCreate(), so a bare call here failed the entire suite
        // with "WorkManager is not initialized" even though nothing about the app itself was
        // broken. This is a best-effort background safety net, not core alarm logic (the
        // exact AlarmManager alarms scheduleNext() itself sets are what actually ring), so
        // losing it in the one environment that never has a real WorkManager anyway is fine.
        runCatching {
            val workManager = WorkManager.getInstance(this)

            // A periodic request's first run only happens after its own interval elapses, not
            // at enqueue time — on its own this wouldn't catch a broken cycle until up to 6h
            // later. This one-time request runs the same check immediately instead, on every
            // process start (not just an explicit app open) — which is what actually closes
            // the gap: something as ordinary as a notification or a routine broadcast starts
            // the process far more often than the user opens the app by hand. REPLACE, not
            // KEEP: once one of these finishes, WorkManager still remembers it as SUCCEEDED
            // under this name — KEEP would then block every future process start from ever
            // enqueueing another one, silently turning "runs on every launch" into "ran
            // exactly once, ever."
            workManager.enqueueUniqueWork(
                "alarm_watchdog_immediate",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AlarmWatchdogWorker>().build()
            )

            // KEEP, not REPLACE: this runs on every process start too, and the point is a
            // stable, always-on cadence — restarting the countdown from zero every time would
            // defeat that, even though the one-time request above already covers the "right
            // now" case.
            workManager.enqueueUniquePeriodicWork(
                "alarm_watchdog_periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AlarmWatchdogWorker>(6, TimeUnit.HOURS).build()
            )
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val tracking = NotificationChannel(
            NotifChannels.TRACKING,
            getString(R.string.notif_channel_tracking_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notif_channel_tracking_desc)
            setShowBadge(false)
        }

        val alarmSound: Uri = Settings.System.DEFAULT_ALARM_ALERT_URI
        val alarm = NotificationChannel(
            NotifChannels.ALARM,
            getString(R.string.notif_channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_alarm_desc)
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setBypassDnd(true)
            enableVibration(true)
            lockscreenVisibility = NotificationManager.IMPORTANCE_HIGH
        }

        val routine = NotificationChannel(
            NotifChannels.ROUTINE,
            getString(R.string.notif_channel_routine_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notif_channel_routine_desc)
        }

        // Its own channel, not the alarm's: the chirp service plays its own sound on the alarm
        // stream, so this channel must stay silent itself or every nag would double up.
        val charge = NotificationChannel(
            NotifChannels.CHARGE,
            getString(R.string.notif_channel_charge_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_charge_desc)
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
        }

        manager.createNotificationChannels(listOf(tracking, alarm, routine, charge))
    }
}
