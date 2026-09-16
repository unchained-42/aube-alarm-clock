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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.reveil.aube.NotifChannels
import com.reveil.aube.R
import com.reveil.aube.alarm.AlarmScheduler
import com.reveil.aube.kiosk.SleepLock
import com.reveil.aube.settings.LocaleHelper
import com.reveil.aube.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
private const val TAG = "AubeRingingService"

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
    // Kept so a stray-command instance (see onStartCommand) can order its "not ringing"
    // write strictly after onCreate's "ringing" one — two independent launches could land
    // in either order.
    private var ringingFlagJob: Job? = null
    private var activityVisible = false
    private val mainHandler = Handler(Looper.getMainLooper())

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
        isActive = true
        // A fresh instance is a fresh ring, even if the companion flag was left true by a
        // previous cycle in this same process (e.g. a ring that ended via autoStopUnresolved
        // without a real dismiss) — see `dismissRequested`'s doc.
        dismissRequested = false
        startForegroundNotification()
        // Marks the alarm as "ringing, not yet dismissed" — see setAlarmRinging's doc for why
        // this is what lets a reboot get caught instead of quietly ending everything.
        ringingFlagJob = scope.launch { SettingsRepository(applicationContext).setAlarmRinging(true) }

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
        // Same reasoning as handleDismissed() setting this before finish(): this is also a
        // legitimate end to the ring, so onTaskRemoved must not relaunch it if the task
        // happens to go away around the same time.
        dismissed = true
        notifyRingEnded()
        soundPlayer.stop()
        alarmVibrator.stop()
        overlay.hide()
        dismissAlarmNotification(applicationContext)
        postMissedNotification()
        scope.launch {
            val settingsRepository = SettingsRepository(applicationContext)
            val settings = settingsRepository.settings.first()
            settingsRepository.setAlarmRinging(false)
            settingsRepository.setLastHandledDate(LocalDate.now())
            AlarmScheduler(applicationContext).scheduleNext(settings)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Tells a still-alive AlarmActivity the ring is over so it finishes itself. A scan
     * already finishes it directly; this covers the ends that don't start on that screen —
     * the auto-stop ceiling, and the debug STOP hook — which used to leave the alarm screen
     * pinned in lock task with nothing ringing behind it and no way off it but a scan.
     */
    private fun notifyRingEnded() {
        sendBroadcast(Intent(ACTION_RING_ENDED).setPackage(packageName))
        // The night screen may be sitting under the alarm screen; the day has been handled,
        // so it must leave rather than reappear when the alarm screen finishes.
        sendBroadcast(Intent(SleepLock.ACTION_RECHECK).setPackage(packageName))
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

        // START_STICKY (below) means a process kill mid-ring — this service getting killed
        // for memory, not the whole device rebooting — brings the service back with a null
        // Intent and a brand new instance, so every field above is back at its declared
        // default: dawnEndMillis is 0, sound/vibration are freshly-created and not playing,
        // and the auto-stop watchdog's `if (end <= 0L) continue` guard means it never fires
        // again. Left alone, that's a silent, no-sound "Time to get up" notification stuck
        // in the tray for good, with no ceiling — confirmed on a real device via `dumpsys
        // activity services`: isForeground=true hours after the deadline, with no alarm
        // screen or sound anywhere. dawnEndMillis == 0L can only mean "this instance never
        // learned a real window" (a legitimate one is always a huge epoch-millis value), so
        // it's a safe, unambiguous signal that this is that restart, not a normal command —
        // recovering means exactly what BootReceiver already does for a reboot mid-ring:
        // relaunch the alarm from scratch instead of leaving it stuck.
        if (dawnEndMillis <= 0L && intent == null) {
            val now = System.currentTimeMillis()
            dawnStartMillis = now
            dawnEndMillis = now
            launchAlarm(applicationContext, now, now)
            return START_STICKY
        }
        // An explicit command reaching an instance that never learned a window. AlarmActivity
        // always sends the window first, synchronously in onCreate, so this can only be a
        // straggler: the activity's once-a-second volume tick (or a second DISMISS) landing
        // after ACTION_DISMISS already stopped the previous instance — which startService
        // then dutifully turned into a brand-new one. This used to fall into the recovery
        // branch above and relaunch the alarm from scratch right after a legitimate scan: a
        // dismiss → ring → dismiss → ring loop, confirmed on a real device once device-owner
        // lock task made finish() slow enough for one more tick to get through every time.
        // It's not a ring, so it must not leave the "ringing" flag onCreate just set behind
        // either — BootReceiver would read that as a ring to resume on the next boot.
        if (dawnEndMillis <= 0L) {
            Log.i(TAG, "stray ${intent?.action} on a fresh instance, ending it")
            dismissed = true
            dismissAlarmNotification(applicationContext)
            scope.launch {
                ringingFlagJob?.join()
                SettingsRepository(applicationContext).setAlarmRinging(false)
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START_SOUND -> {
                lastMusicUri = intent.getStringExtra(EXTRA_URI)
                soundPlayer.start(lastMusicUri, intent.getFloatExtra(EXTRA_VOLUME, 1f))
            }
            ACTION_RESUME_AFTER_BOOT -> {
                lastMusicUri = intent.getStringExtra(EXTRA_URI)
                soundPlayer.start(lastMusicUri, 1f)
                if (intent.getBooleanExtra(EXTRA_VIBRATE, true)) {
                    vibrationWasRequested = true
                    alarmVibrator.start()
                }
                mainHandler.postDelayed({ bringScreenBackAfterBoot() }, RESUME_SCREEN_DELAY_MS)
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
            ACTION_ACTIVITY_VISIBLE -> {
                activityVisible = true
                overlay.hide()
            }
            ACTION_ACTIVITY_HIDDEN -> {
                activityVisible = false
                overlay.show(dawnStartMillis, dawnEndMillis)
            }
            // The one legitimate way this ever ends: a successful scan, routed here from
            // AlarmActivity.handleDismissed().
            ACTION_DISMISS -> {
                dismissed = true
                soundPlayer.stop()
                alarmVibrator.stop()
                overlay.hide()
                notifyRingEnded()
                // AlarmActivity normally clears this itself, but not every dismiss comes from
                // it (debug STOP hook) — and a leftover "Time to get up" whose full-screen
                // intent starts a brand-new ring when tapped is not a harmless leftover.
                dismissAlarmNotification(applicationContext)
                // Cancelled here, synchronously, rather than left to AlarmActivity's
                // lifecycleScope coroutine: that scope dies the moment the activity reaches
                // DESTROYED, which finish() (called right after this) can trigger before the
                // coroutine gets to AlarmScheduler.scheduleNext(). If that races and loses,
                // today's now-stale safety-net alarm (AlarmReceiver.ACTION_FIRE_ALARM, which
                // fires unconditionally with no "already dismissed" check of its own) is still
                // sitting in AlarmManager and goes off later — a second, un-swipeable "Time to
                // get up" notification with no dismiss screen anyone asked for. A Service's
                // onStartCommand isn't tied to that lifecycle, so this cancellation always
                // completes.
                AlarmScheduler(applicationContext).cancelAll()
                // Same reasoning as the cancellation above: this used to be set only from
                // AlarmActivity's lifecycleScope, which can die before it runs, and left stuck
                // true it doesn't just risk one bad reboot — BootReceiver treats it as "a ring
                // is still in progress" forever, with nothing else ever going to clear it.
                //
                // This used to be `runBlocking` on this same (main) thread so the write was
                // guaranteed to land before the service could die. That's what caused a real,
                // on-device ANR: right after boot, with storage under heavy contention from
                // every other app also starting, the DataStore commit stalled long enough to
                // trip Android's "executing service" timeout — confirmed via `adb logcat`
                // ("Timeout executing service" / "ANR in ... AlarmRingingService"), after which
                // MIUI killed the process mid-dismiss. The kill happened before stopSelf() ever
                // ran, so START_STICKY resurrected the service with a null Intent — landing
                // straight in the dawnEndMillis<=0L recovery branch above, which relaunched the
                // alarm from scratch: a "restart right after I dismissed it" loop, not a bug in
                // that branch itself. The write still has to land before the service is allowed
                // to stop — stopSelf() below only runs once it has — it just no longer blocks
                // the thread the OS is timing.
                scope.launch {
                    SettingsRepository(applicationContext).setAlarmRinging(false)
                    withContext(Dispatchers.Main) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    /**
     * The delayed second half of [ACTION_RESUME_AFTER_BOOT]. Sound has been going since the
     * command arrived; this puts the pinned screen back in front of it. Starting an activity
     * from a service is normally restricted, but this app holds SYSTEM_ALERT_WINDOW and, in
     * hardcore mode, is the device owner — both exemptions. The overlay goes up too, in case
     * the launch is refused anyway: its "return to the alarm" button is the same launch,
     * from a user tap.
     */
    private fun bringScreenBackAfterBoot() {
        if (dismissed || activityVisible) return
        val intent = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(EXTRA_DAWN_START_MILLIS, dawnStartMillis)
            putExtra(EXTRA_DAWN_END_MILLIS, dawnEndMillis)
            putExtra(EXTRA_RESUMED_AFTER_REBOOT, true)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "post-boot activity launch refused", e)
        }
        overlay.show(dawnStartMillis, dawnEndMillis)
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
     *
     * The guard below is what keeps this from firing on a *legitimate* dismiss. AlarmActivity
     * is `singleInstance` and `excludeFromRecents`, so handleDismissed()'s finish() — being
     * the sole activity in its own task — tears that task down, and this callback fires from
     * that alone, indistinguishable at the OS level from the user swiping it away unscanned.
     * Confirmed on a real device: every QR dismiss triggered this, relaunching a brand-new
     * ring seconds after the one just dismissed, over and over, because ACTION_DISMISS
     * (delivered separately, asynchronously, via startForegroundService) hadn't necessarily
     * been processed yet. `dismissRequested` exists specifically so AlarmActivity can record
     * that here, synchronously, before calling finish() — no dependency on intent delivery
     * order. It's a *separate* flag from instance-level `dismissed` on purpose: `dismissed`
     * only becomes true once ACTION_DISMISS has actually been handled below (sound stopped,
     * scheduler rearmed, ...), and onStartCommand's own guard depends on that specific
     * meaning — setting it early, before that handling runs, made onStartCommand ignore the
     * real dismiss command as if it were a stale duplicate, silently leaving the sound running
     * forever. Confirmed on a real device the morning after that fix shipped: the QR scan
     * screen closed normally, but the alarm kept ringing and had to be silenced by powering
     * the phone off.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (dismissed || dismissRequested) return
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
        isActive = false
        mainHandler.removeCallbacksAndMessages(null)
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
        /**
         * True only while this Service object is actually alive in this process — unlike the
         * `ringing_unresolved` DataStore flag (which is deliberately permanent, surviving a
         * reboot, so [BootReceiver][com.reveil.aube.alarm.BootReceiver] can resume a ring that
         * a reboot interrupted), this resets to false for free whenever the process restarts:
         * a reinstall, a crash, a force-stop. HomeScreen's reschedule guard reads this instead
         * of the persisted flag for exactly that reason — a persisted "don't reschedule" flag
         * that outlives the process it described nearly bricked scheduling entirely once,
         * stuck true with no ring actually in progress and nothing left to ever clear it.
         */
        @Volatile
        var isActive: Boolean = false
            private set

        /**
         * True once a real QR scan has begun ending this ring — companion, not instance
         * state, so [AlarmActivity] can set it directly and synchronously the moment a scan
         * succeeds, before it does anything else (including finish()). Read only by
         * onTaskRemoved — see its doc for why this has to be a flag separate from instance-
         * level `dismissed` rather than reusing it. Reset to false in onCreate() so a new
         * instance is never mistaken for one that already saw its dismiss.
         */
        @Volatile
        var dismissRequested: Boolean = false

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

        /** Sent (app-internal) once a ring has ended by any legitimate route. */
        const val ACTION_RING_ENDED = "com.reveil.aube.action.RING_ENDED"
        /**
         * Boot-time resume of a ring the device went down in the middle of: starts sound
         * (and vibration) immediately, then brings AlarmActivity back after a short delay
         * — see [resumeAlarmAfterBoot]. Carries the window extras plus [EXTRA_URI]/[EXTRA_VIBRATE].
         */
        const val ACTION_RESUME_AFTER_BOOT = "com.reveil.aube.action.RESUME_AFTER_BOOT"
        const val EXTRA_VIBRATE = "extra_vibrate"
        // Long enough for the system to have finished bringing up its own windows after
        // BOOT_COMPLETED (a direct startActivity() any sooner lost a focus race and ANR'd),
        // short enough that the screen is back before anyone can get their bearings.
        private const val RESUME_SCREEN_DELAY_MS = 3_000L
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
