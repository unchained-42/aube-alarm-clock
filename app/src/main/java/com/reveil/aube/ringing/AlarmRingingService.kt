package com.reveil.aube.ringing

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.reveil.aube.NotifChannels
import com.reveil.aube.R
import com.reveil.aube.alarm.AlarmScheduler
import com.reveil.aube.settings.LocaleHelper
import com.reveil.aube.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Owns the alarm's sound and vibration for the whole ringing lifecycle, independently of
 * [AlarmActivity]. An Activity-owned MediaPlayer dies the instant the Activity does — closed
 * from a recents/"active apps" list — which used to silence the alarm completely with no
 * dismiss check at all, even though the activity is `excludeFromRecents` (some launchers,
 * including this device's, still let you close it from their own "running apps" view). A
 * foreground service isn't tied to that lifecycle, and [onTaskRemoved] pulls the ringing
 * screen straight back instead of letting the alarm just vanish.
 */
class AlarmRingingService : Service() {

    private val soundPlayer by lazy { AlarmSoundPlayer(this) }
    private val alarmVibrator by lazy { AlarmVibrator(this) }
    private val overlay by lazy { AlarmOverlayController(this) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dawnStartMillis = 0L
    private var dawnEndMillis = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastMusicUri: String? = null
    private var vibrationWasRequested = false
    private var pulseSoundOn = true
    private var dismissed = false

    // The ramp loop only corrects the volume once a second; this reacts the instant anything
    // else moves the alarm stream — the system's own touch-based volume panel, some other
    // app, a brief window before our own key-event block has focus — instead of leaving a
    // gap for the rest of that second. Re-applying our own last-set value is what triggers
    // this broadcast too, but since nothing changes on that pass, it doesn't re-fire.
    private val volumeWatchdog = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            if (stream == AudioManager.STREAM_ALARM) soundPlayer.reassertVolume()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        // Marks the alarm as "ringing, not yet dismissed" — see setAlarmRinging's doc for why
        // this is what lets a reboot get caught instead of quietly ending everything.
        scope.launch { SettingsRepository(applicationContext).setAlarmRinging(true) }

        ContextCompat.registerReceiver(
            this, volumeWatchdog, IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Keeps the CPU awake independent of screen state for as long as this service is
        // alive — the ramp loop, the volume watchdog, and playback itself all need to keep
        // running even in a state where nothing is currently keeping the screen on.
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aube:AlarmRinging").apply {
            acquire(MAX_RINGING_DURATION_MS)
        }

        startAutoStopWatchdog()
    }

    /**
     * A phone left in another room, or sleeping away from the printed code, would otherwise
     * ring — and drain the battery, and disturb whoever else is around — forever, since the
     * whole point of the rest of this file is that nothing short of a real scan stops it.
     * That's fine for the first stretch (someone nearby, half-asleep, is exactly who this
     * app is for), but past a point it stops being useful and starts just being a problem.
     * So: an hour of normal continuous ringing at the deadline, same as always: then instead
     * of a hard cutoff, it ping-pongs — 30 minutes ringing, 30 minutes silent — for several
     * more hours, in case whoever it is does come back into range before giving up
     * entirely. Only past that final ceiling does it actually go quiet, and it leaves a
     * plain (non-alarm) notification behind saying so, instead of just vanishing.
     */
    private fun startAutoStopWatchdog() {
        scope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (dismissed) return@launch
                val end = dawnEndMillis
                if (end <= 0L) continue
                val elapsed = System.currentTimeMillis() - end
                if (elapsed < 0L) continue // still in the dawn ramp, not ringing yet

                if (elapsed >= TOTAL_AUTO_STOP_MS) {
                    autoStopUnresolved()
                    return@launch
                }
                if (elapsed < CONTINUOUS_RING_MS) continue // normal continuous phase

                val cyclePos = (elapsed - CONTINUOUS_RING_MS) % (PULSE_ON_MS + PULSE_OFF_MS)
                val shouldSound = cyclePos < PULSE_ON_MS
                if (shouldSound && !pulseSoundOn) {
                    soundPlayer.start(lastMusicUri, 1f)
                    if (vibrationWasRequested) alarmVibrator.start()
                    pulseSoundOn = true
                } else if (!shouldSound && pulseSoundOn) {
                    soundPlayer.stop()
                    alarmVibrator.stop()
                    pulseSoundOn = false
                }
            }
        }
    }

    /** Gave up without ever being dismissed — clean up and arm the next occurrence, but skip
     * the post-wake routine reminders: nobody was actually there to wake up. */
    private fun autoStopUnresolved() {
        soundPlayer.stop()
        alarmVibrator.stop()
        overlay.hide()
        dismissAlarmNotification(applicationContext)
        postMissedNotification()
        scope.launch {
            val settingsRepository = SettingsRepository(applicationContext)
            settingsRepository.setAlarmRinging(false)
            settingsRepository.setLastHandledDate(LocalDate.now())
            AlarmScheduler(applicationContext).scheduleNext(settingsRepository.settings.first())
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun postMissedNotification() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(this, NotifChannels.ROUTINE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.notif_missed_title))
            .setContentText(getString(R.string.notif_missed_text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(MISSED_NOTIF_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Belt-and-suspenders alongside the isFinishing guard in AlarmActivity.onStop(): a
        // command sent right as ACTION_DISMISS is being processed could still be in flight —
        // Intent delivery order isn't an absolute guarantee — and once dismissed, nothing
        // should be able to revive the overlay or restart sound from a late-arriving command.
        if (dismissed) return START_NOT_STICKY

        if (intent != null && intent.hasExtra(EXTRA_DAWN_START_MILLIS)) {
            dawnStartMillis = intent.getLongExtra(EXTRA_DAWN_START_MILLIS, dawnStartMillis)
            dawnEndMillis = intent.getLongExtra(EXTRA_DAWN_END_MILLIS, dawnEndMillis)
        }
        when (intent?.action) {
            ACTION_START_SOUND -> {
                lastMusicUri = intent.getStringExtra(EXTRA_URI)
                soundPlayer.start(lastMusicUri, intent.getFloatExtra(EXTRA_VOLUME, 1f))
            }
            ACTION_SET_VOLUME -> soundPlayer.setVolume(intent.getFloatExtra(EXTRA_VOLUME, 1f))
            ACTION_START_VIBRATION -> {
                vibrationWasRequested = true
                alarmVibrator.start()
            }
            ACTION_STOP_SOUND -> {
                soundPlayer.stop()
                alarmVibrator.stop()
            }
            // Mirrors AlarmActivity.onStart()/onStop(): the overlay only needs to be visible
            // when the real screen isn't — showing both at once would just double up.
            ACTION_ACTIVITY_VISIBLE -> overlay.hide()
            ACTION_ACTIVITY_HIDDEN -> overlay.show(dawnStartMillis, dawnEndMillis)
            // The one legitimate way this ever ends: a successful scan, routed here from
            // AlarmActivity.handleDismissed().
            ACTION_DISMISS -> {
                dismissed = true
                soundPlayer.stop()
                alarmVibrator.stop()
                overlay.hide()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notification: Notification = NotificationCompat.Builder(this, NotifChannels.ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.alarm_ringing_message))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /**
     * The alarm's task was removed from a recents/"active apps" list. Ringing must not end
     * from that alone — keep the sound going (it's untouched, still owned by this service)
     * and bring the screen straight back so there's no way to just make the activity
     * disappear without ever scanning.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val relaunch = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(EXTRA_DAWN_START_MILLIS, dawnStartMillis)
            putExtra(EXTRA_DAWN_END_MILLIS, dawnEndMillis)
        }
        startActivity(relaunch)
    }

    override fun onDestroy() {
        soundPlayer.stop()
        alarmVibrator.stop()
        overlay.hide()
        scope.cancel()
        try {
            unregisterReceiver(volumeWatchdog)
        } catch (_: IllegalArgumentException) {
            // Never registered (onCreate didn't complete) — fine.
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 43
        private const val MISSED_NOTIF_ID = 44

        // An hour of normal continuous ringing at full volume after the mandatory deadline —
        // same as always, giving a real chance to wake someone who's just deeply asleep.
        private const val CONTINUOUS_RING_MS = 60 * 60 * 1000L
        // Past that, 30 minutes ringing / 30 minutes silent, repeating — much less punishing
        // on battery and on anyone else around, while still trying in case the phone (or the
        // person) comes back into range.
        private const val PULSE_ON_MS = 30 * 60 * 1000L
        private const val PULSE_OFF_MS = 30 * 60 * 1000L
        // The final ceiling: 6 hours after the deadline, it gives up for the day rather than
        // pulsing indefinitely — this is what actually protects a phone left behind
        // somewhere, or a night spent away from the printed code, from ringing (on and off)
        // all day.
        private const val TOTAL_AUTO_STOP_MS = 6 * 60 * 60 * 1000L
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        // Comfortably past TOTAL_AUTO_STOP_MS so the wake lock never expires mid-cycle.
        private const val MAX_RINGING_DURATION_MS = 7 * 60 * 60 * 1000L

        const val ACTION_START_SOUND = "com.reveil.aube.action.START_SOUND"
        const val ACTION_SET_VOLUME = "com.reveil.aube.action.SET_VOLUME"
        const val ACTION_START_VIBRATION = "com.reveil.aube.action.START_VIBRATION"
        const val ACTION_STOP_SOUND = "com.reveil.aube.action.STOP_SOUND"
        const val ACTION_ACTIVITY_VISIBLE = "com.reveil.aube.action.ACTIVITY_VISIBLE"
        const val ACTION_ACTIVITY_HIDDEN = "com.reveil.aube.action.ACTIVITY_HIDDEN"
        const val ACTION_DISMISS = "com.reveil.aube.action.DISMISS"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_VOLUME = "extra_volume"
    }
}
