package com.reveil.aube.tracking

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.reveil.aube.MainActivity
import com.reveil.aube.NotifChannels
import com.reveil.aube.R
import com.reveil.aube.alarm.AlarmScheduler
import com.reveil.aube.alarm.EXTRA_DAWN_DURATION_MINUTES
import com.reveil.aube.alarm.EXTRA_WINDOW_EARLIEST_MILLIS
import com.reveil.aube.alarm.EXTRA_WINDOW_LATEST_MILLIS
import com.reveil.aube.ringing.launchAlarm
import com.reveil.aube.settings.LocaleHelper
import kotlin.math.min

/**
 * Runs from shortly before the wake window opens until it hands off to [com.reveil.aube.ringing.AlarmActivity].
 * Samples the accelerometer, scores movement with [MovementScorer], and decides *when* the
 * dawn-to-ringing sequence should begin — either at the natural dawn start time (latest
 * minute minus the configured ramp length) or earlier, the moment it sees a sustained
 * "stirring" signal inside the window. Once that decision is made the service's job is
 * done: the activity owns the rest of the countdown from light to sound.
 */
class SleepTrackingService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scorer = MovementScorer()

    private var windowEarliestMillis = 0L
    private var windowLatestMillis = 0L
    private var dawnDurationMs = 0L
    private var dawnNaturalStartMillis = 0L
    private var running = false
    private var decided = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val earliest = intent?.getLongExtra(EXTRA_WINDOW_EARLIEST_MILLIS, 0L) ?: 0L
        val latest = intent?.getLongExtra(EXTRA_WINDOW_LATEST_MILLIS, 0L) ?: 0L
        val dawnMinutes = intent?.getIntExtra(EXTRA_DAWN_DURATION_MINUTES, 25) ?: 25

        val now = System.currentTimeMillis()
        if (latest <= now) {
            fireImmediately()
            return START_NOT_STICKY
        }

        windowEarliestMillis = earliest
        windowLatestMillis = latest
        dawnDurationMs = dawnMinutes * 60_000L
        dawnNaturalStartMillis = latest - dawnDurationMs

        startForegroundNotification()

        if (dawnNaturalStartMillis <= now) {
            // We were started late enough that the dawn ramp should already be underway.
            beginDawnSequence(now, latest)
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            startSensing(latest)
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, NotifChannels.TRACKING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(getString(R.string.notif_tracking_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        // The "specialUse" foreground service type is only meaningful (and only declared in
        // the manifest as recognized) from API 34 onward; older platforms don't enforce a
        // type match, so a plain startForeground keeps them working without warnings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startSensing(latestMillis: Long) {
        val manager = getSystemService(SensorManager::class.java)
        sensorManager = manager
        val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            // No usable sensor — fall back to the plain dawn-at-deadline schedule.
            beginDawnSequence(dawnNaturalStartMillis.coerceAtLeast(System.currentTimeMillis()), latestMillis)
            return
        }
        manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)

        val powerManager = getSystemService(PowerManager::class.java)
        val timeoutMs = (latestMillis - System.currentTimeMillis() + 60_000L).coerceAtLeast(60_000L)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aube:SleepTracking").apply {
            acquire(timeoutMs)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (decided) return
        val result = scorer.onSample(event.timestamp / 1_000_000, event.values[0], event.values[1], event.values[2])
            ?: return

        val now = System.currentTimeMillis()
        if (now >= windowLatestMillis) {
            beginDawnSequence(now, now) // no time left for a ramp
            return
        }
        if (now >= dawnNaturalStartMillis) {
            beginDawnSequence(now, windowLatestMillis)
            return
        }
        if (now >= windowEarliestMillis && result.state == MovementScorer.MovementState.STIRRING) {
            // Stirring caught early: still give a short, compressed ramp rather than
            // blasting full brightness/sound immediately.
            val compressedEnd = now + min(dawnDurationMs, EARLY_TRIGGER_RAMP_CAP_MS)
            beginDawnSequence(now, compressedEnd)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun beginDawnSequence(startMillis: Long, endMillis: Long) {
        decided = true
        AlarmScheduler(applicationContext).cancelAll()
        launchAlarm(applicationContext, startMillis, endMillis)
        stopSelf()
    }

    private fun fireImmediately() {
        val now = System.currentTimeMillis()
        AlarmScheduler(applicationContext).cancelAll()
        launchAlarm(applicationContext, now, now)
    }

    override fun onDestroy() {
        // stopSelf() alone (from beginDawnSequence, every normal morning) or an external
        // stopService() (AlarmReceiver's safety net, when the deadline hits before any
        // stirring was seen) both end this service without ever removing the foreground
        // notification on their own — Android only demotes it out of the foreground state,
        // it doesn't cancel it. Left alone, "Aube surveille ton sommeil" sits in the tray
        // forever after the alarm's already been dismissed. Doing it here, in onDestroy(),
        // covers every stop path instead of duplicating the call at each one.
        stopForeground(STOP_FOREGROUND_REMOVE)
        sensorManager?.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        running = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 7
        private const val EARLY_TRIGGER_RAMP_CAP_MS = 10 * 60_000L
    }
}
